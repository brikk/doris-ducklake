package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
import dev.brikk.ducklake.catalog.DucklakeCatalogFormatNotAdmittedException
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.function.Supplier

/** Stored-format admission through the released Doris configuration, not a manually edited version string. */
internal class DuckLakeCatalogFormatTest {
    @TempDir lateinit var root: Path

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        @BeforeAll @JvmStatic fun start() { server = TestingDucklakePostgreSqlCatalogServer() }
        @AfterAll @JvmStatic fun stop() { server.close() }
    }

    private data class Fixture(val database: String, val data: Path)

    @Test
    fun releasedConfigLocksOneZeroAndReadsItsParquetAndInlineMetadata() {
        val fixture = fixture("1.0")
        val config = config(fixture)
        assertThat(config.requiredCatalogFormat).isEqualTo(DucklakeCatalogFormat.V1_0)
        assertThat(config.enableFinal11Reads).isFalse()
        JdbcDucklakeCatalog(config).use { catalog ->
            catalog.readSession(Supplier {
                val snapshot = catalog.currentSnapshotId
                val parquet = catalog.getTable("main", "p", snapshot)!!
                assertThat(catalog.getDataFiles(parquet.tableId, snapshot)).hasSize(1)
                val inline = catalog.getTable("main", "inl", snapshot)!!
                val columns = catalog.getTableColumns(inline.tableId, snapshot)
                assertThat(catalog.getInlinedDataInfos(inline.tableId, snapshot).flatMap {
                    catalog.readInlinedDataDecoded(inline.tableId, it.schemaVersion, snapshot, columns)
                }).containsExactly(listOf(7))
            })
        }
    }

    @Test
    fun storedOneOneRefusesLayoutReadsButRemainsDiagnosable() {
        JdbcDucklakeCatalog(config(fixture("1.1"))).use { catalog ->
            assertThat(catalog.getSpecVersion()).isEqualTo("1.1")
            assertThatThrownBy { catalog.readSession(Supplier { catalog.currentSnapshotId }) }
                .isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java)
                .hasMessageContaining("V1_0")
            assertThatThrownBy { catalog.currentSnapshotId }
                .isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java)
            assertThat(catalog.getSpecVersion()).isEqualTo("1.1")
        }
    }

    @Test
    fun migrationAfterConstructionIsRefusedOnTheNextReadAndWrite() {
        val fixture = fixture("1.0")
        JdbcDucklakeCatalog(config(fixture)).use { catalog ->
            assertThat(catalog.readSession(Supplier { catalog.currentSnapshotId })).isGreaterThan(0)
            native(fixture, "1.1", migrate = true)
            assertThat(catalog.getSpecVersion()).isEqualTo("1.1")
            assertThatThrownBy { catalog.readSession(Supplier { catalog.currentSnapshotId }) }
                .isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java)
            assertThatThrownBy { catalog.createSchema("forbidden") }
                .isInstanceOf(DucklakeCatalogFormatNotAdmittedException::class.java)
            DriverManager.getConnection(server.getJdbcUrl(fixture.database), server.getUser(), server.getPassword()).use { pg ->
                pg.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM ducklake_schema WHERE schema_name='forbidden'").use { rows ->
                        check(rows.next())
                        assertThat(rows.getInt(1)).isZero()
                    }
                }
            }
        }
    }

    private fun config(fixture: Fixture) = DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = server.getJdbcUrl(fixture.database)
        catalogDatabaseUser = server.getUser()
        catalogDatabasePassword = server.getPassword()
        dataPath = fixture.data.toString()
        maxCatalogConnections = 1
        minIdleCatalogConnections = 0
        DuckLakeFinal11(emptyMap(), FakeConnectorContext("format-test", 1L)).configure(this)
    }

    private fun fixture(version: String): Fixture {
        val name = "format_" + UUID.randomUUID().toString().replace("-", "")
        server.createDatabase(name)
        return Fixture(name, root.resolve(name)).also { native(it, version) }
    }

    private fun native(fixture: Fixture, version: String, migrate: Boolean = false) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            statement.execute("ATTACH '${server.getDuckDbAttachUri(fixture.database).replace("'", "''")}' AS lake " +
                "(DATA_PATH '${fixture.data}', DUCKLAKE_VERSION '$version', AUTOMATIC_MIGRATION ${if (migrate) "TRUE" else "FALSE"})")
            if (!migrate) {
                statement.execute("CREATE TABLE lake.p(i INTEGER)")
                statement.execute("CALL lake.set_option('data_inlining_row_limit',0,table_name=>'p')")
                statement.execute("INSERT INTO lake.p VALUES (42)")
                statement.execute("CREATE TABLE lake.inl(i INTEGER); INSERT INTO lake.inl VALUES (7)")
            }
        } }
    }
}
