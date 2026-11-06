package dev.brikk.ducklake.doris.plugin

import com.zaxxer.hikari.HikariDataSource
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.PGConnection
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.function.Supplier

/** Explicit local-snapshot trial only: never discovered by normal 0.15.1 tests. */
internal class DuckLakePgBouncerLocalTrialTest {
    @TempDir lateinit var root: Path

    @Test
    fun connectorPoolAndUrlSettingsWorkBehindTransactionPoolingWithoutIsolationLeakage() {
        PgBouncerTrialFixture(root).use { fixture ->
            DuckLakeConnector(fixture.properties(), FakeConnectorContext("pgbouncer-trial", 1L)).use { connector ->
                assertThat(connector.catalogConfiguration().catalogDatabaseUrl).isEqualTo(fixture.url)
                connector.preCreateValidation(null)
                // Read-only inspection verifies actual Hikari/JDBC settings, not merely a config bean.
                val catalog = field(connector, "catalog") as JdbcDucklakeCatalog
                val pool = field(catalog, "hikariDataSource") as HikariDataSource
                verifyPool(pool, fixture.url)
                verifyPinnedRead(connector, catalog, fixture)
                assertThat(connector.getMetadata(null).listDatabaseNames(null)).contains("proxy_new_schema")
            }
            verifyBothServerDefaults(fixture)
            println("PGBOUNCER_LOCAL_TRIAL pool-settings=effective prepareThreshold=0 snapshot=pinned both-server-defaults=read-committed/off")
        }
    }

    private fun verifyPinnedRead(connector: DuckLakeConnector, catalog: JdbcDucklakeCatalog, fixture: PgBouncerTrialFixture) {
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = fixture.directUrl
            catalogDatabaseUser = fixture.user
            catalogDatabasePassword = fixture.password
            maxCatalogConnections = 1
            minIdleCatalogConnections = 0
            requiredCatalogFormat = DucklakeCatalogFormat.V1_0
        }).use { writer ->
            DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("SELECT 1") } // Occupy server A during reader setup.
                catalog.readSession(Supplier {
                    holder.commit()
                    val before = catalog.currentSnapshotId
                    writer.createSchema("proxy_new_schema")
                    assertThat(catalog.currentSnapshotId).isEqualTo(before)
                    val handle = connector.getMetadata(null).getTableHandle(null, "main", "t").orElseThrow() as DuckLakeTableHandle
                    assertThat(handle.snapshotId).isEqualTo(before)
                    assertThat(connector.getMetadata(null).listDatabaseNames(null)).doesNotContain("proxy_new_schema")
                })
            }
        }
    }

    private fun verifyPool(pool: HikariDataSource, url: String) {
        assertThat(pool.jdbcUrl).isEqualTo(url)
        assertThat(pool.maximumPoolSize).isEqualTo(1)
        assertThat(pool.minimumIdle).isZero()
        assertThat(pool.idleTimeout).isEqualTo(30_000)
        assertThat(pool.maxLifetime).isEqualTo(180_000)
        assertThat(pool.connectionTimeout).isEqualTo(500)
        pool.connection.use { connection ->
            assertThat(connection.unwrap(PGConnection::class.java).prepareThreshold).isZero()
            assertThat(connection.getClientInfo("ApplicationName")).isEqualTo("doris-pgbouncer-trial")
        }
    }

    private fun verifyBothServerDefaults(fixture: PgBouncerTrialFixture) {
        DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { first ->
            DriverManager.getConnection(fixture.url, fixture.user, fixture.password).use { second ->
                first.autoCommit = false
                second.autoCommit = false
                // Keep A leased while acquiring B, so neither backend's default can hide behind LIFO reuse.
                assertThat(defaults(first)).isEqualTo("read committed/off")
                assertThat(defaults(second)).isEqualTo("read committed/off")
                first.rollback()
                second.rollback()
            }
        }
    }

    private fun defaults(connection: Connection): String = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT current_setting('transaction_isolation'), current_setting('transaction_read_only')").use { rows ->
            check(rows.next()); rows.getString(1) + "/" + rows.getString(2)
        }
    }

    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(instance)
    }
}
