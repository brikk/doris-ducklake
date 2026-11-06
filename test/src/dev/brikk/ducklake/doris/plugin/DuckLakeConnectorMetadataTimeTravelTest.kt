package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.connector.spi.mvcc.ConnectorMvccSnapshot
import org.apache.doris.connector.spi.mvcc.ConnectorTimeTravelSpec
import org.apache.doris.thrift.TIcebergColumnStats
import org.apache.doris.thrift.TIcebergCommitData
import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TBinaryProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DuckLake has linear history only: `SNAPSHOT_ID` and `TIMESTAMP` resolve, `TAG` / `BRANCH` /
 * `INCREMENTAL` / named `VERSION_REF` return empty.
 */
internal class DuckLakeConnectorMetadataTimeTravelTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "timetravel")
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

    private fun handleFor(catalog: JdbcDucklakeCatalog): DuckLakeTableHandle {
        val snap = catalog.currentSnapshotId
        val table = requireNotNull(catalog.getTable("sales", "orders", snap)) { "expected sales.orders" }
        return DuckLakeTableHandle("sales", "orders", table.schemaId, table.tableId, snap)
    }

    @Test
    fun resolvesSnapshotIdSpecToThatSnapshot() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val current = catalog.currentSnapshotId

            val resolved = md.resolveTimeTravel(
                null,
                handleFor(catalog),
                ConnectorTimeTravelSpec.snapshotId(current.toString()),
            )

            assertThat(resolved).isPresent
            assertThat(resolved.get().snapshotId).isEqualTo(current)
        }
    }

    @Test
    fun resolvesTimestampSpecToSnapshotAtOrBeforeThatInstant() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val current = catalog.currentSnapshotId

            // A digital TIMESTAMP spec carries raw epoch-millis; "now" resolves to the
            // latest committed snapshot.
            val nowMillis = System.currentTimeMillis()
            val resolved = md.resolveTimeTravel(
                null,
                handleFor(catalog),
                ConnectorTimeTravelSpec.timestamp(nowMillis.toString(), true),
            )

            assertThat(resolved).isPresent
            assertThat(resolved.get().snapshotId).isEqualTo(current)
        }
    }

    @Test
    fun timestampBeforeAnyCommitResolvesToEmpty() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)

            val resolved = md.resolveTimeTravel(
                null,
                handleFor(catalog),
                ConnectorTimeTravelSpec.timestamp("0", true),
            )

            assertThat(resolved).isEmpty
        }
    }

    @Test
    fun unknownSnapshotIdResolvesToEmpty() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)

            val resolved = md.resolveTimeTravel(
                null,
                handleFor(catalog),
                ConnectorTimeTravelSpec.snapshotId(Long.MAX_VALUE.toString()),
            )

            assertThat(resolved).isEmpty
        }
    }

    @Test
    fun providerSpecificKindsAreUnsupportedAndResolveToEmpty() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val handle = handleFor(catalog)

            assertThat(md.resolveTimeTravel(null, handle, ConnectorTimeTravelSpec.tag("v1"))).isEmpty
            assertThat(md.resolveTimeTravel(null, handle, ConnectorTimeTravelSpec.branch("main"))).isEmpty
            assertThat(
                md.resolveTimeTravel(null, handle, ConnectorTimeTravelSpec.incremental(emptyMap())),
            ).isEmpty
            assertThat(
                md.resolveTimeTravel(null, handle, ConnectorTimeTravelSpec.versionRef("release-1")),
            ).isEmpty
        }
    }

    // ---- applySnapshot ----

    @Test
    fun applySnapshotThreadsResolvedSnapshotIdOntoHandle() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val handle = handleFor(catalog)
            val targetId = handle.snapshotId - 1 // any different, non-negative id

            val applied = md.applySnapshot(null, handle, mvcc(targetId)) as DuckLakeTableHandle

            assertThat(applied.snapshotId).isEqualTo(targetId)
            assertThat(applied.database).isEqualTo(handle.database)
            assertThat(applied.table).isEqualTo(handle.table)
            assertThat(applied.tableId).isEqualTo(handle.tableId)
        }
    }

    @Test
    fun applySnapshotLeavesHandleUnchangedForNullNegativeOrSameSnapshot() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val handle = handleFor(catalog)

            assertThat(md.applySnapshot(null, handle, null)).isSameAs(handle)
            // A negative id is the empty-table / invalid pin.
            assertThat(md.applySnapshot(null, handle, mvcc(-1))).isSameAs(handle)
            assertThat(md.applySnapshot(null, handle, mvcc(handle.snapshotId))).isSameAs(handle)
        }
    }

    @Test
    fun timeTravelPinResolvesTheHistoricalDataFileSet() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val snap0 = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("sales", "orders", snap0)) {
                "expected sales.orders"
            }.tableId
            val filesAtSnap0 = catalog.getDataFiles(tableId, snap0).size

            val snap1 = commitOneInsertSnapshot(catalog, tableId, snap0)
            assertThat(snap1).isGreaterThan(snap0)
            assertThat(catalog.getDataFiles(tableId, snap1)).hasSize(filesAtSnap0 + 1)

            val handleNow = md.getTableHandle(null, "sales", "orders").orElseThrow() as DuckLakeTableHandle
            assertThat(handleNow.snapshotId).isEqualTo(snap1)

            val resolved = md.resolveTimeTravel(
                null,
                handleNow,
                ConnectorTimeTravelSpec.snapshotId(snap0.toString()),
            ).orElseThrow()
            val pinned = md.applySnapshot(null, handleNow, resolved) as DuckLakeTableHandle

            assertThat(pinned.snapshotId).isEqualTo(snap0)
            assertThat(catalog.getDataFiles(pinned.tableId, pinned.snapshotId)).hasSize(filesAtSnap0)
        }
    }

    // ---- getTableStatistics ----

    @Test
    fun tableStatisticsAreSnapshotScopedForTimeTravelPin() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)
            val snap0 = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("sales", "orders", snap0)) {
                "expected sales.orders"
            }.tableId
            val rows0 = catalog.getDataFiles(tableId, snap0).sumOf { it.recordCount }
            assertThat(rows0).isGreaterThan(0)

            val snap1 = commitOneInsertSnapshot(catalog, tableId, snap0)
            assertThat(snap1).isGreaterThan(snap0)
            val rows1 = catalog.getDataFiles(tableId, snap1).sumOf { it.recordCount }
            assertThat(rows1).isEqualTo(rows0 + 3)

            val table = requireNotNull(catalog.getTable("sales", "orders", snap1)) { "expected sales.orders" }
            val handleCurrent = DuckLakeTableHandle("sales", "orders", table.schemaId, tableId, snap1)
            val handleHistorical = handleCurrent.copy(snapshotId = snap0)

            // At the current snapshot the global ducklake_table_stats row is served.
            assertThat(md.getTableStatistics(null, handleCurrent)).isPresent

            // A historical handle gets snapshot-scoped stats from the files live at snap0.
            val historical = md.getTableStatistics(null, handleHistorical)
            assertThat(historical).isPresent
            assertThat(historical.get().rowCount).isEqualTo(rows0)
            assertThat(historical.get().rowCount).isLessThan(rows1)

            // fe-core passes the pinned snapshot as the third argument with a current handle;
            // the argument must win, or FOR VERSION AS OF cardinality would not match the read.
            val viaSnapshotArg = md.getTableStatistics(null, handleCurrent, mvcc(snap0))
            assertThat(viaSnapshotArg).isPresent
            assertThat(viaSnapshotArg.get().rowCount).isEqualTo(rows0)
            assertThat(md.getTableStatistics(null, handleCurrent, mvcc(snap1))).isPresent
        }
    }

    private fun mvcc(snapshotId: Long): ConnectorMvccSnapshot =
        ConnectorMvccSnapshot.builder().snapshotId(snapshotId).build()

    private fun leInt(value: Int): ByteBuffer =
        ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).flip() as ByteBuffer

    /** Commits one synthetic data-file fragment, advancing the catalog by a new snapshot. */
    private fun commitOneInsertSnapshot(
        catalog: JdbcDucklakeCatalog,
        tableId: Long,
        baseSnapshot: Long,
    ): Long {
        val idColumn = catalog.getTableColumns(tableId, baseSnapshot).first { it.columnName == "id" }
        val commit = TIcebergCommitData().apply {
            filePath = "sales/orders/timetravel-${System.nanoTime()}.parquet"
            rowCount = 3
            fileSize = 999
            columnStats = TIcebergColumnStats().apply {
                columnSizes = mapOf(idColumn.columnId.toInt() to 64L)
                valueCounts = mapOf(idColumn.columnId.toInt() to 3L)
                nullValueCounts = mapOf(idColumn.columnId.toInt() to 0L)
                lowerBounds = mapOf(idColumn.columnId.toInt() to leInt(1000))
                upperBounds = mapOf(idColumn.columnId.toInt() to leInt(2000))
            }
        }
        val serialized = TSerializer(TBinaryProtocol.Factory()).serialize(commit)
        val txn = DuckLakeConnectorTransaction(System.nanoTime(), catalog)
        txn.addCommitData(serialized)
        txn.bindTarget(tableId, baseSnapshot)
        txn.commit()
        return catalog.currentSnapshotId
    }
}
