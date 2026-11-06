package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.ColumnRangePredicate
import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeCommitOutcomeUnknownException
import dev.brikk.ducklake.catalog.DucklakePostCommitException
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.thrift.TIcebergColumnStats
import org.apache.doris.thrift.TIcebergCommitData
import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TBinaryProtocol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.sql.DriverManager

/**
 * Commits synthetic `TIcebergCommitData` through [DuckLakeConnectorTransaction] and checks
 * the catalog grows a snapshot whose data file and decoded stats the read path sees.
 * The BE side (sink, field ids, footer size) is covered by the compose smoke test.
 */
internal class DuckLakeConnectorTransactionTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "writetxn")
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

    // DucklakeCatalog has close() but is not AutoCloseable, so use {} does not resolve.
    private inline fun <R> withCatalog(block: (JdbcDucklakeCatalog) -> R): R {
        val catalog = openCatalog()
        return try {
            block(catalog)
        } finally {
            catalog.close()
        }
    }

    private fun leInt(value: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(value)
        buffer.flip()
        return buffer
    }

    private fun serialize(data: TIcebergCommitData): ByteArray =
        TSerializer(TBinaryProtocol.Factory()).serialize(data)

    @Test
    @Throws(Exception::class)
    fun commitsIcebergFragmentAsNewSnapshotWithQueryableStats() {
        withCatalog { catalog ->
            val snap0 = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("sales", "orders", snap0)) {
                "expected sales.orders"
            }.tableId
            val idColumn = catalog.getTableColumns(tableId, snap0).first { it.columnName == "id" }
            val recordsBefore = requireNotNull(catalog.getTableStats(tableId)).recordCount

            // No existing file covers id=1500 yet.
            assertThat(
                catalog.findDataFileIdsInRange(
                    tableId, snap0, ColumnRangePredicate(idColumn.columnId, "1500", "1500"),
                ),
            ).isEmpty()

            val commit = TIcebergCommitData().apply {
                filePath = "sales/orders/inserted-by-test.parquet"
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

            val transaction = DuckLakeConnectorTransaction(1L, catalog)
            transaction.addCommitData(serialize(commit))
            transaction.bindTarget(tableId, snap0)
            assertThat(transaction.getUpdateCnt()).isEqualTo(3)

            transaction.commit()

            val snap1 = catalog.currentSnapshotId
            assertThat(snap1).isGreaterThan(snap0)
            assertThat(requireNotNull(catalog.getTableStats(tableId)).recordCount)
                .isEqualTo(recordsBefore + 3)

            // id=1500 matching a file now, and 5000..6000 not matching, shows the
            // little-endian bounds were decoded and recorded as stats.
            assertThat(
                catalog.findDataFileIdsInRange(
                    tableId, snap1, ColumnRangePredicate(idColumn.columnId, "1500", "1500"),
                ),
            ).isNotEmpty()
            assertThat(
                catalog.findDataFileIdsInRange(
                    tableId, snap1, ColumnRangePredicate(idColumn.columnId, "5000", "6000"),
                ),
            ).isEmpty()
        }
    }

    @Test
    @Throws(Exception::class)
    fun commitsPartitionedFileWithBoundPartitionId() {
        // The plan binds the table's active partition id and the BE reports positional
        // partition_values per file; the catalog throws if the partition data is malformed.
        withCatalog { catalog ->
            val snap0 = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("sales", "by_region", snap0)) {
                "expected sales.by_region"
            }.tableId
            val spec = catalog.getPartitionSpecs(tableId, snap0).first()
            val recordsBefore = requireNotNull(catalog.getTableStats(tableId)).recordCount

            val commit = TIcebergCommitData().apply {
                filePath = "inserted-us.parquet"
                rowCount = 2
                partitionValues = listOf("us") // positional: key index 0 is region
            }

            val transaction = DuckLakeConnectorTransaction(4L, catalog)
            transaction.addCommitData(serialize(commit))
            transaction.bindTarget(tableId, snap0, null, spec.partitionId)
            transaction.commit()

            val snap1 = catalog.currentSnapshotId
            assertThat(snap1).isGreaterThan(snap0)
            assertThat(requireNotNull(catalog.getTableStats(tableId)).recordCount)
                .isEqualTo(recordsBefore + 2)
            assertThat(catalog.getFilePartitionValues(tableId, snap1)).isNotEmpty()
        }
    }

    @Test
    @Throws(Exception::class)
    fun rollbackDropsFragmentsAndCommitsNothing() {
        withCatalog { catalog ->
            val snap0 = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("sales", "orders", snap0)).tableId

            val transaction = DuckLakeConnectorTransaction(2L, catalog)
            transaction.addCommitData(
                serialize(TIcebergCommitData().apply { filePath = "x.parquet"; rowCount = 9 }),
            )
            transaction.bindTarget(tableId, snap0)
            transaction.rollback()

            assertThat(transaction.getUpdateCnt()).isZero()
            transaction.commit() // nothing accumulated, so a no-op
            assertThat(catalog.currentSnapshotId).isEqualTo(snap0)
        }
    }

    @Test
    fun unknownCommitOutcomeRetainsPublishedOutputAndClassification() {
        preservesCommittedOutput { snapshot ->
            DucklakeCommitOutcomeUnknownException("INSERT", snapshot, SQLException("lost COMMIT reply", "08006"))
        }
    }

    @Test
    fun acknowledgedCommitFinalizationFailureRetainsPublishedOutputAndClassification() {
        preservesCommittedOutput { _ -> DucklakePostCommitException("INSERT", SQLException("connection close failed")) }
    }

    private fun preservesCommittedOutput(failure: (Long) -> RuntimeException) {
        withCatalog { catalog ->
            val before = catalog.currentSnapshotId
            // Not sales.orders: the pruning test above assumes none of its files cover id=1500,
            // and a file without stats would match.
            val tableId = requireNotNull(catalog.getTable("sales", "returns_inline", before)).tableId
            val output = Files.createTempFile(isolated.dataDir(), "commit-outcome-", ".parquet")
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    Files.delete(output)
                    statement.execute("COPY (SELECT 9876 AS id, 1.25 AS total) TO '${output.toString().replace("'", "''")}' (FORMAT PARQUET)")
                }
            }
            val bytes = Files.readAllBytes(output)
            val problem = failure(before + 1)
            var publications = 0
            val failing = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DucklakeCatalog::class.java)) { _, method, args ->
                val result = try {
                    method.invoke(catalog, *(args ?: emptyArray()))
                } catch (wrapped: InvocationTargetException) {
                    throw wrapped.targetException
                }
                if (method.name == "commitInsert") {
                    publications++
                    throw problem // thrown after the real commit has been published
                }
                result
            } as DucklakeCatalog
            val transaction = DuckLakeConnectorTransaction(5L, failing)
            transaction.bindTarget(tableId, before)
            transaction.addCommitData(serialize(TIcebergCommitData().apply {
                filePath = output.toString(); rowCount = 1; fileSize = bytes.size.toLong()
            }))
            assertThatThrownBy { transaction.commit() }.isSameAs(problem)
            assertThat(publications).isEqualTo(1)
            val committed = catalog.currentSnapshotId
            assertThat(committed).isEqualTo(before + 1)
            transaction.rollback()
            transaction.close()
            assertThat(catalog.currentSnapshotId).isEqualTo(committed)
            assertThat(Files.readAllBytes(output)).isEqualTo(bytes)
            assertThat(catalog.getDataFiles(tableId, committed).map { it.path })
                .contains(output.toString())
        }
    }

    @Test
    @Throws(Exception::class)
    fun commitsMultipleFragmentsInOneSnapshot() {
        withCatalog { catalog ->
            val snap0 = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("sales", "orders", snap0)).tableId
            val idColumn = catalog.getTableColumns(tableId, snap0).first { it.columnName == "id" }
            val recordsBefore = requireNotNull(catalog.getTableStats(tableId)).recordCount

            fun fileWithIdRange(name: String, rows: Long, lo: Int, hi: Int) =
                TIcebergCommitData().apply {
                    filePath = name
                    rowCount = rows
                    columnStats = TIcebergColumnStats().apply {
                        valueCounts = mapOf(idColumn.columnId.toInt() to rows)
                        nullValueCounts = mapOf(idColumn.columnId.toInt() to 0L)
                        lowerBounds = mapOf(idColumn.columnId.toInt() to leInt(lo))
                        upperBounds = mapOf(idColumn.columnId.toInt() to leInt(hi))
                    }
                }

            val transaction = DuckLakeConnectorTransaction(3L, catalog)
            transaction.addCommitData(serialize(fileWithIdRange("sales/orders/a.parquet", 2, 3000, 3100)))
            transaction.addCommitData(serialize(fileWithIdRange("sales/orders/b.parquet", 4, 4000, 4100)))
            transaction.bindTarget(tableId, snap0)
            assertThat(transaction.getUpdateCnt()).isEqualTo(6)

            transaction.commit()

            val snap1 = catalog.currentSnapshotId
            assertThat(snap1).isGreaterThan(snap0)
            assertThat(requireNotNull(catalog.getTableStats(tableId)).recordCount)
                .isEqualTo(recordsBefore + 6)
            assertThat(
                catalog.findDataFileIdsInRange(
                    tableId, snap1, ColumnRangePredicate(idColumn.columnId, "3050", "3050"),
                ),
            ).isNotEmpty()
            assertThat(
                catalog.findDataFileIdsInRange(
                    tableId, snap1, ColumnRangePredicate(idColumn.columnId, "4050", "4050"),
                ),
            ).isNotEmpty()
        }
    }
}
