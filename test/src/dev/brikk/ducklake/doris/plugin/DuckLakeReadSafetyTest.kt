package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeDataFile
import dev.brikk.ducklake.catalog.DucklakeInvalidOperationException
import dev.brikk.ducklake.catalog.ColumnRangePredicate
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorFilterConstraint
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.thrift.TFileRangeDesc
import org.apache.doris.thrift.TTableFormatFileDesc
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Connection
import java.time.LocalDateTime

/** Native fixtures for source shapes the ordinary Iceberg reader cannot represent safely. */
internal class DuckLakeReadSafetyTest {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog
        private var inlineWriter: Connection? = null

        @BeforeAll @JvmStatic fun start() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeAuditFixtureSeeder.seed(server, "read_safety", listOf(
                "CALL dl.set_option('data_inlining_row_limit',0)",
                "CREATE TABLE dl.history AS SELECT i AS id FROM range(10000) t(i)",
                "BEGIN",
                "DELETE FROM dl.history WHERE id%8=0",
                "DELETE FROM dl.history WHERE id%4=0",
                "COMMIT",
                "DELETE FROM dl.history WHERE id%2=0",
                "CREATE TABLE dl.safe_history AS SELECT 42 AS id",
                "CREATE TABLE dl.temporal_files(ts TIMESTAMP, label VARCHAR)",
                "INSERT INTO dl.temporal_files VALUES(TIMESTAMP '1992-01-01','infinity'),('infinity','finite')",
                "CREATE TABLE dl.nanos(ts TIMESTAMP_NS)",
                "INSERT INTO dl.nanos VALUES(TIMESTAMP_NS '2026-01-01 00:00:00.000000001')",
                "CALL dl.set_option('data_inlining_row_limit',100)",
                "CREATE TABLE dl.temporal_inline(ts TIMESTAMP, label VARCHAR)",
                "INSERT INTO dl.temporal_inline VALUES(TIMESTAMP '1992-01-01','infinity'),('infinity','finite')",
                "CALL dl.set_option('data_inlining_row_limit',0)",
                "CREATE TABLE dl.finite_temporal(ts TIMESTAMP, label VARCHAR)",
                "INSERT INTO dl.finite_temporal VALUES(TIMESTAMP '1992-01-01','infinity'),(TIMESTAMP '2020-01-01','-infinity')",
            ))
            // Keep the native writer open: CHECKPOINT/close may flush inline rows into files.
            inlineWriter = DriverManager.getConnection("jdbc:duckdb:").also { writer -> writer.createStatement().use { statement ->
                statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
                statement.execute("ATTACH '${server.getDuckDbAttachUri("ducklake_read_safety")}' AS dl " +
                    "(DATA_PATH '${isolated.dataDir().toAbsolutePath()}', DATA_INLINING_ROW_LIMIT 100" +
                    "${DuckLakeTestCatalogBootstrap.LEGACY_FORMAT_OPTIONS})")
                statement.execute("CALL dl.set_option('data_inlining_row_limit',100)")
                statement.execute("CREATE TABLE dl.temporal_live_inline(ts TIMESTAMP, label VARCHAR)")
                statement.execute("CALL dl.set_option('data_inlining_row_limit',100,table_name=>'temporal_live_inline')")
                statement.execute("INSERT INTO dl.temporal_live_inline VALUES(TIMESTAMP '1992-01-01','infinity'),('infinity','finite')")
            } }
        }

        @AfterAll @JvmStatic fun stop() {
            try { inlineWriter?.close() } finally { server.close() }
        }
    }

    private fun catalog() = JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = isolated.jdbcUrl()
        catalogDatabaseUser = isolated.user()
        catalogDatabasePassword = isolated.password()
        dataPath = isolated.dataDir().toAbsolutePath().toString()
        maxCatalogConnections = 1
    })

    private fun handle(catalog: DucklakeCatalog, table: String): DuckLakeTableHandle =
        DuckLakeConnectorMetadata(catalog).getTableHandle(null, "main", table).orElseThrow() as DuckLakeTableHandle

    private fun provider(catalog: DucklakeCatalog) = DuckLakeScanPlanProvider(
        catalog, DuckLakePathResolver(catalog, isolated.dataDir().toAbsolutePath().toString()), emptyMap(),
    )

    @Test
    fun refusesHistoricalDeletesEvenWithNullAdvisoryBoundsAndBeforePushdowns() {
        catalog().use { catalog ->
            val latest = handle(catalog, "history")
            val snapshot = firstDeletionSnapshot(latest.tableId)
            assertThat(snapshot).isLessThan(latest.snapshotId)
            val historical = latest.copy(snapshotId = snapshot)
            assertThat(catalog.getDataFiles(latest.tableId, snapshot).any { it.deleteFilePath != null }).isTrue()
            val noHints = object : DucklakeCatalog by catalog {
                override fun getDataFiles(tableId: Long, snapshotId: Long): List<DucklakeDataFile> =
                    catalog.getDataFiles(tableId, snapshotId).map { it.copy(deleteFilePartialMax = null) }
            }
            for (request in historicalRequests(historical)) {
                assertThatThrownBy { provider(noHints).planScan(null, request) }
                    .isInstanceOf(DorisConnectorException::class.java)
                    .hasMessageContaining("Historical reads with delete files")
            }
        }
    }

    private fun firstDeletionSnapshot(tableId: Long): Long =
        DriverManager.getConnection(isolated.jdbcUrl(), isolated.user(), isolated.password()).use { pg ->
            pg.createStatement().use { statement ->
                statement.executeQuery("SELECT min(begin_snapshot) FROM ducklake_delete_file WHERE table_id=$tableId").use { rows ->
                    check(rows.next()); rows.getLong(1)
                }
            }
        }

    private fun historicalRequests(handle: DuckLakeTableHandle): List<ConnectorScanRequest> {
        val requests = mutableListOf<ConnectorScanRequest>()
        for (count in listOf(false, true)) {
            for (limit in listOf(-1L, 1L)) {
                for (pruned in listOf(false, true)) {
                    val selected = if (pruned) handle.copy(prunedFileIds = emptySet()) else handle
                    requests += ConnectorScanRequest.builder(selected, emptyList()).countPushdown(count).limit(limit).build()
                }
            }
        }
        return requests
    }

    @Test
    fun latestPositionDeletesAndHistoryWithoutDeletesRemainAvailable() {
        catalog().use { catalog ->
            val latest = handle(catalog, "history")
            val plan = provider(catalog)
            val ranges = plan.planScan(null, ConnectorScanRequest.builder(latest, emptyList()).countPushdown(true).limit(1).build())
            assertThat(ranges).isNotEmpty()
            val format = TTableFormatFileDesc()
            ranges.first().populateRangeParams(format, TFileRangeDesc())
            assertThat(plan.getDeleteFiles(format)).isNotEmpty()
            val safe = handle(catalog, "safe_history")
            val earlier = catalog.getDataFiles(safe.tableId, safe.snapshotId).first().beginSnapshot
            assertThat(earlier).isLessThan(safe.snapshotId)
            val safeHistory = safe.copy(snapshotId = earlier)
            assertThat(plan.planScan(null, ConnectorScanRequest.builder(safeHistory, emptyList()).build())).isNotEmpty()
        }
    }

    @Test
    fun nativeTemporalFixturesExposeTheirNonFiniteSourceValues() {
        catalog().use { catalog ->
            val files = handle(catalog, "temporal_files")
            val stats = catalog.getColumnStats(files.tableId, files.snapshotId)
            println("TEMPORAL FILE BOUNDS: $stats")
            val tsId = catalog.getTableColumns(files.tableId, files.snapshotId).single { it.columnName == "ts" }.columnId
            assertThat(stats.single { it.columnId == tsId }.maxValue).isEqualTo("infinity")
            val inline = handle(catalog, "temporal_live_inline")
            val columns = catalog.getTableColumns(inline.tableId, inline.snapshotId).filter { it.parentColumn == null }
            val infos = catalog.getInlinedDataInfos(inline.tableId, inline.snapshotId).filter { it.hasLiveRows }
            assertThat(infos).isNotEmpty()
            val rows = infos.flatMap {
                catalog.readInlinedData(inline.tableId, it.schemaVersion, inline.snapshotId, columns)
            }
            println("TEMPORAL INLINE VALUES: ${rows.map { row -> row.map { it?.let { value -> value.javaClass.name + ":" + value } } }}")
            assertThat(rows).hasSize(2)
            assertThatThrownBy {
                catalog.readInlinedDataDecoded(inline.tableId, infos.first().schemaVersion, inline.snapshotId, columns)
            }.isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("non-finite TIMESTAMP")
        }
    }

    @Test
    fun refusesKnownInfiniteTemporalDataDuringSchemaBindingButNotVarcharLiterals() {
        catalog().use { catalog ->
            val metadata = DuckLakeConnectorMetadata(catalog)
            for (table in listOf("temporal_files", "temporal_live_inline")) {
                val handle = handle(catalog, table)
                assertThatThrownBy { metadata.getTableSchema(null, handle) }
                    .isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("temporal domain")
                assertThatThrownBy { metadata.getColumnHandles(null, handle) }
                    .isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("temporal domain")
            }
            val finite = handle(catalog, "finite_temporal")
            assertThat(metadata.getTableSchema(null, finite).columns.map { it.name }).containsExactly("ts", "label")
            assertThat(metadata.getColumnHandles(null, finite).keys).containsExactly("ts", "label")
        }
    }

    @Test
    fun lossyNanosecondEqualityCannotUseAnUnwidenedSourceStatsPoint() {
        catalog().use { catalog ->
            val noUnsafePruning = object : DucklakeCatalog by catalog {
                override fun findDataFileIdsInRange(tableId: Long, snapshotId: Long, predicate: ColumnRangePredicate): List<Long> =
                    error("TIMESTAMP_NS must not use an unwidened statistics point")
            }
            val metadata = DuckLakeConnectorMetadata(noUnsafePruning)
            val handle = handle(catalog, "nanos")
            assertThat(catalog.getDataFiles(handle.tableId, handle.snapshotId)).isNotEmpty()
            val type = (metadata.getColumnHandles(null, handle).getValue("ts") as DuckLakeColumnHandle).columnType
            val filter = ConnectorComparison(ConnectorComparison.Operator.EQ, ConnectorColumnRef("ts", type),
                ConnectorLiteral(type, LocalDateTime.of(2026, 1, 1, 0, 0)))
            assertThat(metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))).isEmpty()
        }
    }

    @Test
    fun inlineTemporalBudgetIsCheckedBeforeAnyPayloadRead() {
        catalog().use { catalog ->
            val handle = handle(catalog, "temporal_live_inline")
            var reads = 0
            val bounded = object : DucklakeCatalog by catalog {
                override fun countInlinedRows(tableId: Long, schemaVersion: Long, snapshotId: Long): Long = 4097
                override fun readInlinedDataDecoded(tableId: Long, schemaVersion: Long, snapshotId: Long,
                    columns: List<dev.brikk.ducklake.catalog.DucklakeColumn>): List<List<Any?>> {
                    reads++
                    error("Inline budget must be checked first")
                }
            }
            assertThatThrownBy { DuckLakeConnectorMetadata(bounded).getTableSchema(null, handle) }
                .isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("budget")
            assertThat(reads).isZero()
        }
    }
}
