package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogFormat
import dev.brikk.ducklake.catalog.DucklakeCatalogFormatNotAdmittedException
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.DorisConnectorException
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

/** DuckLake 1.0 lakes are read; 1.1 lakes are refused, both at connector creation and when a lake migrates under a live connector. */
internal class DuckLakeCatalogFormatTest {
    @TempDir lateinit var root: Path

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        @BeforeAll @JvmStatic fun start() { server = TestingDucklakePostgreSqlCatalogServer() }
        @AfterAll @JvmStatic fun stop() { server.close() }
    }

    private data class Fixture(val database: String, val data: Path)

    @Test
    fun connectorLocksOneZeroAndReadsItsParquetAndInlineMetadata() {
        val fixture = fixture("1.0")
        val config = connector(fixture).catalogConfiguration()
        assertThat(config.requiredCatalogFormat).isEqualTo(DucklakeCatalogFormat.V1_0)
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
    fun connectorOpensOneZeroAndListsItsSchemas() {
        connector(fixture("1.0")).use { connector ->
            connector.preCreateValidation(null)
            assertThat(connector.getMetadata(null).listDatabaseNames(null)).contains("main")
        }
    }

    @Test
    fun connectorRefusesOneOneWithAnExplanation() {
        connector(fixture("1.1")).use { connector ->
            assertThatThrownBy { connector.preCreateValidation(null) }
                .isInstanceOf(DorisConnectorException::class.java)
                .hasMessageContaining("DuckLake 1.1")
                .hasMessageContaining("not supported")
        }
    }

    @Test
    fun storedOneOneRefusesLibraryReadsButRemainsDiagnosable() {
        JdbcDucklakeCatalog(connector(fixture("1.1")).catalogConfiguration()).use { catalog ->
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
        JdbcDucklakeCatalog(connector(fixture).catalogConfiguration()).use { catalog ->
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

    private fun connector(fixture: Fixture) = DuckLakeConnector(
        mapOf(
            DuckLakeConnectorProperties.METADATA_URL to server.getJdbcUrl(fixture.database),
            DuckLakeConnectorProperties.METADATA_USER to server.getUser(),
            DuckLakeConnectorProperties.METADATA_PASSWORD to server.getPassword(),
            DuckLakeConnectorProperties.STORAGE_WAREHOUSE to fixture.data.toString(),
            DuckLakeConnectorProperties.CATALOG_MAX_CONNECTIONS to "1",
            DuckLakeConnectorProperties.CATALOG_MIN_IDLE_CONNECTIONS to "0",
        ),
        FakeConnectorContext("format-test", 1L),
    )

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
