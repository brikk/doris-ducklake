package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.ColumnRangePredicate
import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakeFilePartitionValue
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorFilterConstraint
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** F04: old specs and missing partition metadata must never exclude potentially matching files. */
internal class DuckLakeBucketPruningEvolutionTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer

        @BeforeAll
        @JvmStatic
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    @Test
    fun keepsUnpartitionedFilesAfterAddingBucketSpec() {
        assertEvolution("bucket_added", null)
    }

    @Test
    fun keepsOldFilesAfterBucketCountChanges() {
        assertEvolution("bucket_count", "bucket(4, name)")
    }

    @Test
    fun keepsOldFilesAfterBucketColumnChanges() {
        assertEvolution("bucket_column", "bucket(8, region)")
    }

    @Test
    fun keepsOldFilesAfterBucketKeyOrderChanges() {
        assertEvolution(
            "bucket_order",
            "bucket(8, name), bucket(8, region)",
            "bucket(8, region), bucket(8, name)",
        )
    }

    @Test
    fun keepsActiveFileWithMissingPartitionValues() {
        assertEvolution("bucket_missing_values", null, omitPositive = true)
    }

    private fun seedCatalog(testName: String, oldSpec: String?, activeSpec: String): JdbcDucklakeCatalog {
        val isolated = DuckLakeAuditFixtureSeeder.seed(
            server,
            testName,
            buildList {
                add("CALL dl.set_option('data_inlining_row_limit', '0')")
                add("CREATE TABLE dl.main.facts (id INTEGER, name VARCHAR, region VARCHAR)")
                if (oldSpec != null) add("ALTER TABLE dl.main.facts SET PARTITIONED BY ($oldSpec)")
                add("INSERT INTO dl.main.facts VALUES (1, 'alice', 'bob')")
                // Flush before evolution; otherwise the writer can materialize old rows under the new spec.
                add("CHECKPOINT dl")
                add("ALTER TABLE dl.main.facts SET PARTITIONED BY ($activeSpec)")
                add("INSERT INTO dl.main.facts VALUES (2, 'alice', 'bob'), (3, 'bob', 'bob')")
            },
        )
        return JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = isolated.jdbcUrl()
            catalogDatabaseUser = isolated.user()
            catalogDatabasePassword = isolated.password()
            dataPath = isolated.dataDir().toAbsolutePath().toString()
        })
    }

    private fun assertEvolution(
        testName: String,
        oldSpec: String?,
        activeSpec: String = "bucket(8, name)",
        omitPositive: Boolean = false,
    ) {
        seedCatalog(testName, oldSpec, activeSpec).use { catalog ->
            val metadata = DuckLakeConnectorMetadata(catalog)
            val handle = metadata.getTableHandle(null, "main", "facts")
                .orFail("expected main.facts handle") as DuckLakeTableHandle
            val files = catalog.getDataFiles(handle.tableId, handle.snapshotId)
            assertThat(files).isNotEmpty()
            val spec = catalog.getPartitionSpecs(handle.tableId, handle.snapshotId).single()
            val oldSnapshot = files.minOf { it.beginSnapshot }
            val oldPartitionId = catalog.getPartitionSpecs(handle.tableId, oldSnapshot).singleOrNull()?.partitionId
            if (oldSpec == null) {
                assertThat(oldPartitionId).isNull()
            } else {
                assertThat(oldPartitionId).isNotNull().isNotEqualTo(spec.partitionId)
            }
            val oldFiles = files.filter { it.beginSnapshot == oldSnapshot }
            assertThat(oldFiles).isNotEmpty().allSatisfy { file ->
                assertThat(file.partitionId).isEqualTo(oldPartitionId)
            }
            val resolver = DuckLakePathResolver(catalog, catalog.getDataPath())
            val tablePath = resolver.resolveTableDataPath(
                requireNotNull(catalog.getSchema("main", handle.snapshotId)),
                requireNotNull(catalog.getTable("main", "facts", handle.snapshotId)),
            )
            oldFiles.forEach { file ->
                assertThat(Path.of(resolver.resolveFilePath(file.path, file.pathIsRelative, tablePath))).isRegularFile()
            }
            val activeFiles = files.filter { it.partitionId == spec.partitionId }
            assertThat(files).containsExactlyInAnyOrderElementsOf(oldFiles + activeFiles)
            val name = metadata.getColumnHandles(null, handle).getValue("name") as DuckLakeColumnHandle
            val keyIndex = spec.fields.single { it.columnId == name.columnId }.partitionKeyIndex
            // DuckLake's reference murmur3 bucket(8, 'alice') is 5; do not use the connector hasher as oracle.
            val originalValues = catalog.getFilePartitionValues(handle.tableId, handle.snapshotId)
            val positive = activeFiles.filter { file ->
                originalValues.getValue(file.dataFileId).single { it.partitionKeyIndex == keyIndex }.partitionValue == "5"
            }.map { it.dataFileId }.toSet()
            val negative = activeFiles.map { it.dataFileId }.toSet() - positive
            assertThat(positive).describedAs("active name=alice bucket-5 candidates").isNotEmpty()
            assertThat(negative).describedAs("active nonmatching bucket candidates").isNotEmpty()
            val pruningCatalog = object : DucklakeCatalog by catalog {
                // Statistics must not hide a broken bucket prune or determine the expected active set.
                override fun findDataFileIdsInRange(
                    tableId: Long,
                    snapshotId: Long,
                    predicate: ColumnRangePredicate,
                ): List<Long> = catalog.getDataFiles(tableId, snapshotId).map { it.dataFileId }

                override fun getFilePartitionValues(
                    tableId: Long,
                    snapshotId: Long,
                ): Map<Long, List<DucklakeFilePartitionValue>> = catalog.getFilePartitionValues(tableId, snapshotId)
                    .filterKeys { !omitPositive || it !in positive }
            }
            val filter = ConnectorComparison(
                ConnectorComparison.Operator.EQ,
                ConnectorColumnRef("name", name.columnType),
                ConnectorLiteral(name.columnType, "alice"),
            )
            // Check bucket pruning alone, then its normal intersection with real file statistics.
            for (source in listOf(pruningCatalog, catalog)) {
                val applied = DuckLakeConnectorMetadata(source)
                    .applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected name = 'alice' filter pushdown")
                val kept = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>().prunedFileIds
                assertThat(kept).describedAs("$testName: all old files plus only the active positive candidates")
                    .containsExactlyInAnyOrderElementsOf(oldFiles.map { it.dataFileId }.toSet() + positive)
            }
        }
    }
}
