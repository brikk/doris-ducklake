package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeWriteFragment
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TableColumnSpec
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.ddl.ConnectorCreateTableRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * A [DuckLakeTableHandle] pins the snapshot it was resolved at, and every metadata read through
 * it must keep answering from that snapshot while the catalog advances underneath (ADD/RENAME
 * COLUMN, inserts, DROP TABLE). Complements [DuckLakeConnectorMetadataTimeTravelTest], which
 * covers resolving a past snapshot onto a handle.
 */
internal class DuckLakeSnapshotPinningAuditTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeAuditFixtureSeeder.seed(
                server,
                "pinningaudit",
                listOf(
                    "CREATE SCHEMA dl.pin",
                    "CREATE TABLE dl.pin.facts (id INTEGER, name VARCHAR)",
                    "INSERT INTO dl.pin.facts VALUES (1, 'a'), (2, 'b')",
                    "INSERT INTO dl.pin.facts VALUES (3, 'c')",
                    "CREATE TABLE dl.pin.doomed (id INTEGER)",
                ),
            )
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    private fun openCatalog(): JdbcDucklakeCatalog {
        val config = DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = isolated.jdbcUrl()
            catalogDatabaseUser = isolated.user()
            catalogDatabasePassword = isolated.password()
            dataPath = isolated.dataDir().toAbsolutePath().toString()
        }
        return JdbcDucklakeCatalog(config)
    }

    private inline fun <R> withCatalog(block: (JdbcDucklakeCatalog) -> R): R {
        val catalog = openCatalog()
        return try {
            block(catalog)
        } finally {
            catalog.close()
        }
    }

    private fun columnNames(md: DuckLakeConnectorMetadata, handle: DuckLakeTableHandle): List<String> =
        md.getTableSchema(null, handle).columns.map(ConnectorColumn::getName)

    private fun handleFor(md: DuckLakeConnectorMetadata, table: String): DuckLakeTableHandle =
        md.getTableHandle(null, "pin", table).orFail("expected pin.$table handle") as DuckLakeTableHandle

    @Test
    fun pinnedHandleSchemaIsStableAcrossAddColumn() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val pinned = handleFor(md, "facts")
            assertThat(columnNames(md, pinned)).containsExactly("id", "name")

            // ADD COLUMN commits a new snapshot; columns carry begin/end-snapshot ranges.
            catalog.addColumn(pinned.tableId, TableColumnSpec.leaf("added_later", "int32", true))
            assertThat(catalog.currentSnapshotId).isGreaterThan(pinned.snapshotId)

            assertThat(columnNames(md, pinned)).containsExactly("id", "name")
            assertThat(md.getColumnHandles(null, pinned).keys).containsExactly("id", "name")

            val fresh = handleFor(md, "facts")
            assertThat(fresh.snapshotId).isGreaterThan(pinned.snapshotId)
            assertThat(columnNames(md, fresh)).containsExactly("id", "name", "added_later")
        }
    }

    @Test
    fun pinnedHandleSchemaIsStableAcrossRenameColumn() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            // Own scratch table: JUnit method order is unspecified and pin.facts is
            // mutated by the add-column test.
            md.createTable(
                null,
                ConnectorCreateTableRequest.builder()
                    .dbName("pin")
                    .tableName("rename_scratch")
                    .columns(
                        listOf(
                            ConnectorColumn("id", ConnectorType.of("INT"), "", false, null),
                            ConnectorColumn("name", ConnectorType.of("STRING"), "", true, null),
                        ),
                    )
                    .build(),
            )
            val pinned = handleFor(md, "rename_scratch")

            val nameColumnId = catalog.getTableColumns(pinned.tableId, pinned.snapshotId)
                .first { it.columnName == "name" }
                .columnId
            catalog.renameColumn(pinned.tableId, nameColumnId, "label")

            // Rename keeps the columnId; a plan compiled against the pinned handle
            // still references "name".
            assertThat(columnNames(md, pinned)).containsExactly("id", "name")
            assertThat(columnNames(md, handleFor(md, "rename_scratch")))
                .containsExactly("id", "label")
        }
    }

    @Test
    fun pinnedHandleDataFileSetIsStableWhileInsertsLand() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val pinned = handleFor(md, "facts")
            val filesAtPin = catalog.getDataFiles(pinned.tableId, pinned.snapshotId).size
            // DuckDB may consolidate the seed inserts at CHECKPOINT, so only assert
            // that at least one data file exists.
            assertThat(filesAtPin).isPositive()

            catalog.commitInsert(
                pinned.tableId,
                listOf(
                    DucklakeWriteFragment(
                        "pin/facts/pinning-audit-${System.nanoTime()}.parquet",
                        true,
                        "parquet",
                        128L,
                        0L,
                        1L,
                        emptyList(),
                        emptyMap(),
                        null,
                        null,
                    ),
                ),
            )

            assertThat(catalog.getDataFiles(pinned.tableId, pinned.snapshotId)).hasSize(filesAtPin)
            val fresh = handleFor(md, "facts")
            assertThat(catalog.getDataFiles(fresh.tableId, fresh.snapshotId)).hasSize(filesAtPin + 1)

            // beginQuerySnapshot is what the engine serializes toward the BE; it must
            // use the handle's snapshot, not whatever is current at call time.
            val mvcc = md.beginQuerySnapshot(null, pinned).orFail("expected an mvcc snapshot")
            assertThat(mvcc.snapshotId).isEqualTo(pinned.snapshotId)
            // Retained-schema publication is a Paimon-style opt-in; DuckLake already
            // resolves schema through the snapshot-stamped handle.
            assertThat(mvcc.isSchemaRetained).isFalse()
        }
    }

    @Test
    fun droppedTableStaysReadableThroughItsPinnedHandle() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val pinned = handleFor(md, "doomed")

            md.dropTable(null, pinned)

            assertThat(md.listTableNames(null, "pin")).doesNotContain("doomed")
            assertThat(md.getTableHandle(null, "pin", "doomed")).isEmpty

            // DuckLake drops are soft (end-snapshot stamping), so an in-flight query
            // that resolved the handle before the DROP must still plan.
            assertThat(columnNames(md, pinned)).containsExactly("id")
        }
    }
}
