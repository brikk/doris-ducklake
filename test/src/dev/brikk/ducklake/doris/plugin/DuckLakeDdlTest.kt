package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.DucklakePartitionField
import dev.brikk.ducklake.catalog.DucklakePartitionTransform
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.ddl.ConnectorBucketSpec
import org.apache.doris.connector.spi.ddl.ConnectorCreateTableRequest
import org.apache.doris.connector.spi.ddl.ConnectorPartitionField
import org.apache.doris.connector.spi.ddl.ConnectorPartitionSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Exercises [DuckLakeConnectorMetadata]'s CREATE/DROP DATABASE and TABLE against a real
 * Postgres-backed DuckLake catalog. DDL is pure catalog metadata, so no BE is involved.
 */
internal class DuckLakeDdlTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "writeddl")
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

    private fun col(name: String, type: ConnectorType, nullable: Boolean) =
        ConnectorColumn(name, type, "", nullable, null)

    @Test
    @Throws(Exception::class)
    fun createDropDatabaseAndTableLifecycle() {
        withCatalog { catalog ->
            val md = DuckLakeConnectorMetadata(catalog)

            md.createDatabase(null, "w1_schema", emptyMap())
            assertThat(md.listDatabaseNames(null)).contains("w1_schema")

            val request = ConnectorCreateTableRequest.builder()
                .dbName("w1_schema")
                .tableName("t1")
                .columns(
                    listOf(
                        col("id", ConnectorType.of("INT"), nullable = false),
                        col("label", ConnectorType.of("STRING"), nullable = true),
                        col("amount", ConnectorType.of("DECIMALV3", 10, 2), nullable = true),
                    ),
                )
                .ifNotExists(false)
                .build()
            md.createTable(null, request)

            val snap = catalog.currentSnapshotId
            val table = requireNotNull(catalog.getTable("w1_schema", "t1", snap)) { "table not created" }
            val columns = catalog.getTableColumns(table.tableId, snap)
            val typeByName = columns.associate { it.columnName to it.columnType }
            assertThat(typeByName).containsEntry("id", "int32")
                .containsEntry("label", "varchar")
                .containsEntry("amount", "decimal(10,2)")
            val nullableByName = columns.associate { it.columnName to it.nullsAllowed }
            assertThat(nullableByName).containsEntry("id", false).containsEntry("label", true)

            val handle = md.getTableHandle(null, "w1_schema", "t1").orElseThrow()
            md.dropTable(null, handle)
            assertThat(catalog.getTable("w1_schema", "t1", catalog.currentSnapshotId)).isNull()

            md.dropDatabase(null, "w1_schema", false, false)
            assertThat(md.listDatabaseNames(null)).doesNotContain("w1_schema")
        }
    }

    /** Partition fields of a freshly created table, keyed by column name. */
    private fun partitionFieldsByColumn(
        catalog: JdbcDucklakeCatalog,
        database: String,
        table: String,
    ): Map<String, DucklakePartitionField> {
        val snap = catalog.currentSnapshotId
        val tableId = requireNotNull(catalog.getTable(database, table, snap)) { "table not created" }.tableId
        val nameById = catalog.getTableColumns(tableId, snap).associate { it.columnId to it.columnName }
        return catalog.getPartitionSpecs(tableId, snap)
            .flatMap { it.fields }
            .associateBy { requireNotNull(nameById[it.columnId]) { "partition column ${it.columnId} not in schema" } }
    }

    @Test
    @Throws(Exception::class)
    fun createsIcebergBucketPartitionedTable() {
        withCatalog { catalog ->
            val request = ConnectorCreateTableRequest.builder()
                .dbName("w1b_bucket")
                .tableName("by_name_bucket")
                .columns(listOf(col("id", ConnectorType.of("INT"), nullable = false), col("name", ConnectorType.of("STRING"), nullable = true)))
                .partitionSpec(
                    ConnectorPartitionSpec(
                        ConnectorPartitionSpec.Style.TRANSFORM,
                        listOf(ConnectorPartitionField("name", "bucket", listOf(4))),
                        false,
                    ),
                )
                .build()
            val md = DuckLakeConnectorMetadata(catalog)
            md.createDatabase(null, "w1b_bucket", emptyMap())
            md.createTable(null, request)

            val fields = partitionFieldsByColumn(catalog, "w1b_bucket", "by_name_bucket")
            assertThat(fields).containsOnlyKeys("name")
            assertThat(fields.getValue("name").transform).isEqualTo(DucklakePartitionTransform.BUCKET)
            assertThat(fields.getValue("name").arity).isEqualTo(4)
            assertThat(fields.getValue("name").partitionKeyIndex).isEqualTo(0)
        }
    }

    @Test
    @Throws(Exception::class)
    fun createsIdentityAndTemporalPartitionedTable() {
        withCatalog { catalog ->
            // partition_key_index must follow clause order.
            val request = ConnectorCreateTableRequest.builder()
                .dbName("w1b_part")
                .tableName("sales")
                .columns(
                    listOf(
                        col("id", ConnectorType.of("INT"), nullable = false),
                        col("region", ConnectorType.of("STRING"), nullable = true),
                        col("d", ConnectorType.of("DATE"), nullable = true),
                    ),
                )
                .partitionSpec(
                    ConnectorPartitionSpec(
                        ConnectorPartitionSpec.Style.TRANSFORM,
                        listOf(
                            ConnectorPartitionField("region", "identity", emptyList()),
                            ConnectorPartitionField("d", "year", emptyList()),
                            ConnectorPartitionField("d", "day", emptyList()),
                        ),
                        false,
                    ),
                )
                .build()
            val md = DuckLakeConnectorMetadata(catalog)
            md.createDatabase(null, "w1b_part", emptyMap())
            md.createTable(null, request)

            val snap = catalog.currentSnapshotId
            val tableId = requireNotNull(catalog.getTable("w1b_part", "sales", snap)).tableId
            val nameById = catalog.getTableColumns(tableId, snap).associate { it.columnId to it.columnName }
            val ordered = catalog.getPartitionSpecs(tableId, snap)
                .flatMap { it.fields }
                .sortedBy { it.partitionKeyIndex }
                .map { nameById.getValue(it.columnId) to it.transform }
            assertThat(ordered).containsExactly(
                "region" to DucklakePartitionTransform.IDENTITY,
                "d" to DucklakePartitionTransform.YEAR,
                "d" to DucklakePartitionTransform.DAY,
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun rejectsDorisDistributedByAsNonMurmur3() {
        withCatalog { catalog ->
            // DISTRIBUTED BY HASH(...) arrives as a ConnectorBucketSpec with algorithm
            // "doris_default" (CRC32), which is not DuckLake's murmur3 bucketing.
            val request = ConnectorCreateTableRequest.builder()
                .dbName("sales")
                .tableName("wont_exist")
                .columns(listOf(col("id", ConnectorType.of("INT"), nullable = true), col("name", ConnectorType.of("STRING"), nullable = true)))
                .bucketSpec(ConnectorBucketSpec(listOf("name"), 4, "doris_default"))
                .build()
            assertThatThrownBy { DuckLakeConnectorMetadata(catalog).createTable(null, request) }
                .isInstanceOf(DorisConnectorException::class.java)
                .hasMessageContaining("DuckLake bucketing")
            assertThat(catalog.getTable("sales", "wont_exist", catalog.currentSnapshotId)).isNull()
        }
    }

    @Test
    @Throws(Exception::class)
    fun createsBucketPartitionedTableUnderListStyle() {
        withCatalog { catalog ->
            // Doris expresses Iceberg-style partitioning as `PARTITION BY LIST (bucket(4,name)) ()`,
            // and the FE stamps Style.LIST with the transform in the field. The connector maps
            // by field transform, not by the style keyword.
            val request = ConnectorCreateTableRequest.builder()
                .dbName("w1bb_list")
                .tableName("by_name_bucket_list")
                .columns(listOf(col("id", ConnectorType.of("INT"), nullable = false), col("name", ConnectorType.of("STRING"), nullable = true)))
                .partitionSpec(
                    ConnectorPartitionSpec(
                        ConnectorPartitionSpec.Style.LIST,
                        listOf(ConnectorPartitionField("name", "bucket", listOf(4))),
                        false,
                    ),
                )
                .build()
            val md = DuckLakeConnectorMetadata(catalog)
            md.createDatabase(null, "w1bb_list", emptyMap())
            md.createTable(null, request)

            val fields = partitionFieldsByColumn(catalog, "w1bb_list", "by_name_bucket_list")
            assertThat(fields).containsOnlyKeys("name")
            assertThat(fields.getValue("name").transform).isEqualTo(DucklakePartitionTransform.BUCKET)
            assertThat(fields.getValue("name").arity).isEqualTo(4)
        }
    }

    @Test
    @Throws(Exception::class)
    fun rejectsUnknownTransform() {
        withCatalog { catalog ->
            // truncate() is a valid Iceberg transform that DuckLake does not support. The
            // mapper throws before the catalog is touched, so no schema setup is needed.
            val truncReq = ConnectorCreateTableRequest.builder()
                .dbName("sales")
                .tableName("nope_trunc")
                .columns(listOf(col("id", ConnectorType.of("INT"), nullable = true)))
                .partitionSpec(
                    ConnectorPartitionSpec(
                        ConnectorPartitionSpec.Style.TRANSFORM,
                        listOf(ConnectorPartitionField("id", "truncate", listOf(10))),
                        false,
                    ),
                )
                .build()
            assertThatThrownBy { DuckLakeConnectorMetadata(catalog).createTable(null, truncReq) }
                .isInstanceOf(DorisConnectorException::class.java)
                .hasMessageContaining("truncate")
            assertThat(catalog.getTable("sales", "nope_trunc", catalog.currentSnapshotId)).isNull()
        }
    }

    @Test
    @Throws(Exception::class)
    fun dropDatabaseIfExistsIsNoOpWhenAbsent() {
        withCatalog { catalog ->
            DuckLakeConnectorMetadata(catalog).dropDatabase(null, "never_existed", true, false)
        }
    }

    @Test
    @Throws(Exception::class)
    fun rejectsUnsupportedColumnType() {
        withCatalog { catalog ->
            val request = ConnectorCreateTableRequest.builder()
                .dbName("sales")
                .tableName("wont_exist2")
                .columns(listOf(col("v", ConnectorType.of("BITMAP"), nullable = true)))
                .build()
            assertThatThrownBy { DuckLakeConnectorMetadata(catalog).createTable(null, request) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
