package dev.brikk.ducklake.doris.plugin

import java.util.Optional
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorFilterConstraint
import org.apache.doris.connector.spi.pushdown.ConnectorIn
import org.apache.doris.connector.spi.pushdown.ConnectorLike
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.connector.spi.pushdown.ConnectorOr
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.scan.ConnectorScanRange
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.thrift.TFileFormatType
import org.apache.doris.thrift.TFileRangeDesc
import org.apache.doris.thrift.TFileScanRangeParams
import org.apache.doris.thrift.TTableFormatFileDesc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Goes through the public SPI surface ([DuckLakeConnectorProvider.create] → `getScanPlanProvider`)
 * so regressions in the connector wiring show up here too.
 */
internal class DuckLakeScanPlanProviderTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "scanplan")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    @Test
    @Throws(Exception::class)
    fun emitsOneRangePerActiveDataFile() {
        val properties = DorisTestSupport.isolatedProperties(isolated)

        val ctx = FakeConnectorContext("dl", 1L)
        val provider = DuckLakeConnectorProvider()
        provider.create(properties, ctx).use { connector ->
            val metadata = connector.getMetadata(null)
            val handle = metadata.getTableHandle(null, "sales", "orders")
                .orFail("expected sales.orders handle")

            val plan = connector.getScanPlanProvider()
            assertThat(plan).isNotNull()
            // Column-default backfill on read is the default; Paimon opts out, DuckLake must not.
            assertThat(plan.applyColumnDefaultsOnRead()).isTrue()
            // DuckLake plans synchronously and pins files through its own handle,
            // so the batch/streaming scheduling hooks stay off.
            assertThat(plan.supportsBatchScan(null, handle)).isFalse()
            assertThat(plan.streamingSplitEstimate(null, handle, Optional.empty(), false)).isEqualTo(-1L)
            assertThat(metadata.supportsNativePartitionPlanning(null, handle)).isFalse()
            assertThat(metadata.getSqlCacheVersion(null, handle)).isEmpty()
            assertThat(metadata.getPartitionViewVersion(null, handle)).isEmpty()
            val ranges = plan.planScan(null, handle, listOf(), Optional.empty())

            // DuckLake's writer may coalesce the seeded inserts into a single data
            // file, so assert at least one range rather than an exact count.
            assertThat(ranges).isNotEmpty()

            for (range in ranges) {
                assertThat(range.fileFormat).isEqualTo("parquet")
                assertThat(range.start).isZero()
                assertThat(range.fileSize).isPositive()
                assertThat(range.length).isEqualTo(range.fileSize)
                assertThat(range.partitionValues).isEmpty()
                // File-backed ranges are reusable, unlike ADBC's consumable result tickets.
                assertThat(range.isSingleUse).isFalse()

                val path = range.path
                    .orFail("scan range path missing")
                // Paths in DuckLake start as schema-and-table-relative; the path
                // resolver joins them against the configured warehouse / data dir.
                assertThat(path).startsWith(isolated.dataDir().toAbsolutePath().toString())
                assertThat(path).endsWith(".parquet")
            }

            // sales.customers is seeded empty.
            val customers = metadata.getTableHandle(null, "sales", "customers")
                .orFail("expected sales.customers handle")
            val emptyRanges = plan.planScan(null, customers, listOf(), Optional.empty())
            assertThat(emptyRanges).isEmpty()
        }
    }

    @Test
    @Throws(Exception::class)
    fun emitsPositionDeleteFileForFileBasedDeletePath() {
        // sales.returns_file is seeded with INSERT + DELETE under DATA_INLINING_ROW_LIMIT = 0, so
        // the deletion is a position-delete parquet, not catalog inline-delete rows.
        val properties = DorisTestSupport.isolatedProperties(isolated)

        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val handle = metadata.getTableHandle(null, "sales", "returns_file")
                    .orFail("expected sales.returns_file handle")

                val plan = connector.getScanPlanProvider()
                val ranges = plan.planScan(null, handle, listOf(), Optional.empty())

                // One INSERT batch → one data file, carrying one position-delete file.
                assertThat(ranges).hasSize(1)
                val range = ranges[0]

                // The BE reads delete files from iceberg_params on the wire descriptor.
                val formatDesc = TTableFormatFileDesc()
                val rangeDesc = TFileRangeDesc()
                range.populateRangeParams(formatDesc, rangeDesc)

                assertThat(formatDesc.isSetIcebergParams).isTrue()
                val iceberg = formatDesc.icebergParams
                assertThat(iceberg.deleteFiles).hasSize(1)

                val delete = iceberg.deleteFiles[0]
                // Delete files live under the same table data path as data files.
                assertThat(delete.path)
                    .startsWith(isolated.dataDir().toAbsolutePath().toString())
                    .endsWith(".parquet")
                assertThat(delete.fileFormat).isEqualTo(TFileFormatType.FORMAT_PARQUET)
                assertThat(delete.content).isEqualTo(1) // POSITION_DELETE
            }
    }

    @Disabled(
        "Inlined deletes are refused, not served; the fixture also cannot seed them because " +
            "DuckDB-JDBC ignores data_inlining_row_limit for DELETEs. See dev-docs/TODO.md.",
    )
    @Test
    @Throws(Exception::class)
    fun emitsDeletesForInlineDeletePath() {
        val properties = DorisTestSupport.isolatedProperties(isolated)

        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val handle = metadata.getTableHandle(null, "sales", "returns_inline")
                    .orFail("expected sales.returns_inline handle")

                val plan = connector.getScanPlanProvider()
                val ranges = plan.planScan(null, handle, listOf(), Optional.empty())

                assertThat(ranges).hasSize(1)
                val range = ranges[0]

                val formatDesc = TTableFormatFileDesc()
                val rangeDesc = TFileRangeDesc()
                range.populateRangeParams(formatDesc, rangeDesc)

                assertThat(formatDesc.icebergParams.deleteFiles).isNotEmpty()
            }
    }

    @Test
    @Throws(Exception::class)
    fun scanPlanProviderIsCached() {
        val properties = DorisTestSupport.isolatedProperties(isolated)

        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val first = connector.getScanPlanProvider()
                val second = connector.getScanPlanProvider()
                assertThat(second).isSameAs(first)
            }
    }

    @Test
    @Throws(Exception::class)
    fun scanNodePropertiesCarryStorageCredentialsUnderLocationPrefix() {
        val properties = DorisTestSupport.isolatedProperties(isolated)
        properties["s3.endpoint"] = "http://trino-ducklake-minio:9000"
        properties["s3.access_key"] = "minioadmin"
        properties["s3.secret_key"] = "minioadmin"
        properties["s3.region"] = "us-east-1"
        properties["use_path_style"] = "true"
        // Doris injects this on every CREATE CATALOG; it must not reach the BE.
        properties["enable.mapping.varbinary"] = "false"

        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val plan = connector.getScanPlanProvider()

                val stub = DuckLakeTableHandle("sales", "orders", 0L, 0L, 0L)
                val nodeProps = plan.getScanNodeProperties(
                    null, stub, listOf(), Optional.empty(),
                )

                // file_format_type stays top-level because PluginDrivenScanNode reads
                // it directly to dispatch the BE reader; storage keys go under the prefix.
                assertThat(nodeProps)
                    .containsEntry("file_format_type", "parquet")
                    .containsEntry("ducklake.location.s3.endpoint", "http://trino-ducklake-minio:9000")
                    .containsEntry("ducklake.location.s3.access_key", "minioadmin")
                    .containsEntry("ducklake.location.s3.secret_key", "minioadmin")
                    .containsEntry("ducklake.location.s3.region", "us-east-1")
                    .containsEntry("ducklake.location.use_path_style", "true")
                assertThat(nodeProps).allSatisfy { k, _ ->
                    assertThat(k).matches(
                        "^(file_format_type|" + DuckLakeScanPlanProvider.PROP_LOCATION_PREFIX
                            .replace(".", "\\.") + ".+)$",
                    )
                }
                assertThat(nodeProps).doesNotContainKeys(
                    "ducklake.location.metadata.url",
                    "ducklake.location.metadata.user",
                    "ducklake.location.metadata.password",
                    "ducklake.location.storage.warehouse",
                    "ducklake.location.type",
                    "ducklake.location.enable.mapping.varbinary",
                )

                // The BE's s3_util.cpp reads the AWS_* aliases, so they are emitted
                // alongside the unprefixed FE-style keys.
                val params = TFileScanRangeParams()
                plan.populateScanLevelParams(params, nodeProps)
                assertThat(params.isSetProperties).isTrue()
                assertThat(params.properties)
                    .containsEntry("s3.endpoint", "http://trino-ducklake-minio:9000")
                    .containsEntry("s3.access_key", "minioadmin")
                    .containsEntry("s3.secret_key", "minioadmin")
                    .containsEntry("s3.region", "us-east-1")
                    .containsEntry("use_path_style", "true")
                    .containsEntry("AWS_ENDPOINT", "http://trino-ducklake-minio:9000")
                    .containsEntry("AWS_ACCESS_KEY", "minioadmin")
                    .containsEntry("AWS_SECRET_KEY", "minioadmin")
                    .containsEntry("AWS_REGION", "us-east-1")
                assertThat(params.properties).allSatisfy { k, _ ->
                    assertThat(k).doesNotStartWith(DuckLakeScanPlanProvider.PROP_LOCATION_PREFIX)
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByPartitionEqualityFilter() {
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                // by_region has one file per partition ('us' and 'eu').
                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(2)

                val region = metadata.getColumnHandles(null, handle)["region"] as DuckLakeColumnHandle
                val filter = ConnectorComparison(
                    ConnectorComparison.Operator.EQ,
                    ConnectorColumnRef("region", region.columnType),
                    ConnectorLiteral(region.columnType, "us"),
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                assertThat(applied.isPresent).isTrue()
                val prunedHandle = applied.get().handle.asDuckLakeHandle<DuckLakeTableHandle>()
                assertThat(prunedHandle.prunedFileIds).isNotNull()

                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).isNotEmpty()
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByNumericRangeFilter() {
        // Open numeric range (GE) on a non-partition column, exercising
        // findDataFileIdsInRange's typed stats comparison.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                // by_region is used because CHECKPOINT cannot compact across partitions,
                // so it reliably keeps two files: 'us' (ids 1, 2) and 'eu' (ids 3, 4).
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(2)

                // id >= 3 prunes the 'us' file by its id stats (max id 2).
                val id = metadata.getColumnHandles(null, handle)["id"] as DuckLakeColumnHandle
                val filter = ConnectorComparison(
                    ConnectorComparison.Operator.GE,
                    ConnectorColumnRef("id", id.columnType),
                    ConnectorLiteral(id.columnType, 3),
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push id >= 3")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()

                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).isNotEmpty()
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByBucketEqualityFilter() {
        // Bucket pruning is intersected with stats pruning, so a wrong murmur3 bucket
        // would empty the intersection and fail isNotEmpty below. The hash itself is
        // pinned in DuckLakeBucketTransformTest.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_name_bucket")
                    .orFail("expected sales.by_name_bucket handle")

                // alice/bob/charlie hash to buckets 1/2/3, so three files.
                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(3)

                val name = metadata.getColumnHandles(null, handle)["name"] as DuckLakeColumnHandle
                val filter = ConnectorComparison(
                    ConnectorComparison.Operator.EQ,
                    ConnectorColumnRef("name", name.columnType),
                    ConnectorLiteral(name.columnType, "alice"),
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push name = 'alice'")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()

                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).isNotEmpty()
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByBucketInListFilter() {
        // IN-list over a bucket column exercises the membership-to-bucket-set path
        // that single-equality pruning does not cover.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_name_bucket")
                    .orFail("expected sales.by_name_bucket handle")

                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(3)

                val name = metadata.getColumnHandles(null, handle)["name"] as DuckLakeColumnHandle
                val filter = ConnectorIn(
                    ConnectorColumnRef("name", name.columnType),
                    listOf(
                        ConnectorLiteral(name.columnType, "alice"),
                        ConnectorLiteral(name.columnType, "bob"),
                    ),
                    false,
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push name IN ('alice','bob')")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()

                // alice and bob survive; charlie's bucket-3 file is pruned.
                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).hasSize(2)
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByOrOfEqualitiesFilter() {
        // An OR of equalities on one column must prune identically to the IN-list
        // above, covering the OR-to-IN normalization.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_name_bucket")
                    .orFail("expected sales.by_name_bucket handle")

                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(3)

                val name = metadata.getColumnHandles(null, handle)["name"] as DuckLakeColumnHandle
                fun eq(value: String) = ConnectorComparison(
                    ConnectorComparison.Operator.EQ,
                    ConnectorColumnRef("name", name.columnType),
                    ConnectorLiteral(name.columnType, value),
                )
                val filter = ConnectorOr(listOf(eq("alice"), eq("bob")))
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push name = 'alice' OR name = 'bob'")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()

                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).hasSize(2)
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByNumericInListFilter() {
        // IN-list through the stats path rather than bucket: `id IN (3, 4)` spans 3..4,
        // so by_region's 'us' file (ids 1, 2) is pruned while 'eu' (ids 3, 4) survives.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(2)

                val id = metadata.getColumnHandles(null, handle)["id"] as DuckLakeColumnHandle
                val filter = ConnectorIn(
                    ConnectorColumnRef("id", id.columnType),
                    listOf(ConnectorLiteral(id.columnType, 3), ConnectorLiteral(id.columnType, 4)),
                    false,
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push id IN (3, 4)")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()

                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).isNotEmpty()
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    @Throws(Exception::class)
    fun prunesFilesByPrefixLikeFilter() {
        // `name LIKE 'a%'` becomes the stats range [a, b): alice's file survives,
        // bob and charlie are pruned. A prefix scatters across buckets, so LIKE
        // only feeds the stats path.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_name_bucket")
                    .orFail("expected sales.by_name_bucket handle")

                val allRanges = plan.planScan(null, handle, listOf(), java.util.Optional.empty())
                assertThat(allRanges.size).isGreaterThanOrEqualTo(3)

                val name = metadata.getColumnHandles(null, handle)["name"] as DuckLakeColumnHandle
                val filter = ConnectorLike(
                    ConnectorLike.Operator.LIKE,
                    ConnectorColumnRef("name", name.columnType),
                    ConnectorLiteral(name.columnType, "a%"),
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push name LIKE 'a%'")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()

                val prunedRanges = plan.planScan(null, prunedHandle, listOf(), java.util.Optional.empty())
                assertThat(prunedRanges).hasSize(1)
                assertThat(prunedRanges.size).isLessThan(allRanges.size)
            }
    }

    @Test
    fun canonicalAwsAliasMapping() {
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("s3.access_key"))
            .isEqualTo("AWS_ACCESS_KEY")
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("s3.secret_key"))
            .isEqualTo("AWS_SECRET_KEY")
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("s3.endpoint"))
            .isEqualTo("AWS_ENDPOINT")
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("s3.region"))
            .isEqualTo("AWS_REGION")
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("s3.session_token"))
            .isEqualTo("AWS_TOKEN")
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("use_path_style")).isNull()
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("s3.path-style-access")).isNull()
        assertThat(DuckLakeScanPlanProvider.canonicalAwsAlias("metadata.url")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun populateScanLevelParamsNoopOnEmptyMap() {
        val properties = DorisTestSupport.isolatedProperties(isolated)

        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val plan = connector.getScanPlanProvider()

                val params = TFileScanRangeParams()
                plan.populateScanLevelParams(params, null)
                plan.populateScanLevelParams(params, HashMap())
                // Empty input must not call setProperties(empty), which would
                // change the serialized struct.
                assertThat(params.isSetProperties).isFalse()
            }
    }

    @Test
    fun isStoragePropertyRecognisesS3Aliases() {
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("s3.endpoint")).isTrue()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("s3.access_key")).isTrue()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("s3.path-style-access")).isTrue()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("AWS_ACCESS_KEY")).isTrue()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("aws.region")).isTrue()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("fs.s3a.impl")).isTrue()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("use_path_style")).isTrue()

        assertThat(DuckLakeScanPlanProvider.isStorageProperty("metadata.url")).isFalse()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("metadata.user")).isFalse()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("storage.warehouse")).isFalse()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("type")).isFalse()
        assertThat(DuckLakeScanPlanProvider.isStorageProperty("enable.mapping.varbinary")).isFalse()
    }
}

/**
 * COUNT(*) pushdown and partition-bearing ranges. Split from [DuckLakeScanPlanProviderTest] only to
 * stay under the detekt LargeClass budget; same fixture.
 */
internal class DuckLakeScanPlanCountAndPartitionTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "scanplanagg")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    @Test
    @Throws(Exception::class)
    fun countPushdownCollapsesCleanTableToSingleCountRange() {
        // A clean table (no deletes, filter, or pruning) collapses to one range carrying
        // the exact total, mirroring IcebergScanPlanProvider.planCountPushdown.
        // sales.orders is seeded with 3 rows.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "orders")
                    .orFail("expected sales.orders handle")

                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.empty(), -1L, null, true,
                )
                assertThat(ranges).hasSize(1)
                val range = ranges[0]
                assertThat(range.pushDownRowCount).isEqualTo(3L)

                // The BE's count reader serves table_level_row_count from the thrift
                // descriptor, not pushDownRowCount.
                val formatDesc = TTableFormatFileDesc()
                val rangeDesc = TFileRangeDesc()
                range.populateRangeParams(formatDesc, rangeDesc)
                assertThat(formatDesc.isSetTableLevelRowCount).isTrue()
                assertThat(formatDesc.tableLevelRowCount).isEqualTo(3L)
            }
    }

    @Test
    @Throws(Exception::class)
    fun countPushdownOnEmptyTableEmitsNoRanges() {
        // No ranges means the BE sums nothing and COUNT(*) returns 0, as with Iceberg.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "customers")
                    .orFail("expected sales.customers handle")

                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.empty(), -1L, null, true,
                )
                assertThat(ranges).isEmpty()
            }
    }

    @Test
    @Throws(Exception::class)
    fun countPushdownServedForPartitionedTableAndCarriesPartitionShape() {
        // The collapsed count range keeps the partition-bearing shape of the table's
        // normal ranges, since it goes through the same range builder.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.empty(), -1L, null, true,
                )
                assertThat(ranges).hasSize(1)
                val range = ranges[0]
                assertThat(range.pushDownRowCount).isEqualTo(4L)
                assertThat(range.isPartitionBearing).isTrue()
            }
    }

    @Test
    @Throws(Exception::class)
    fun countPushdownRefusedWhenTableHasDeleteFiles() {
        // returns_file carries a position-delete file, so the file-metadata count (4)
        // over-counts the 3 live rows. Every range must keep the -1 sentinel so
        // PluginDrivenScanNode.resolvePushDownRowCount stays -1 and the BE counts by reading.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "returns_file")
                    .orFail("expected sales.returns_file handle")

                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.empty(), -1L, null, true,
                )
                assertThat(ranges).hasSize(1)
                for (range in ranges) {
                    assertThat(range.pushDownRowCount).isEqualTo(-1L)
                    val formatDesc = TTableFormatFileDesc()
                    range.populateRangeParams(formatDesc, TFileRangeDesc())
                    // Left unset rather than set to -1: the thrift default is already -1
                    // and setting it changes the serialized bytes.
                    assertThat(formatDesc.isSetTableLevelRowCount).isFalse()
                    assertThat(formatDesc.icebergParams.deleteFiles).hasSize(1)
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun countPushdownRefusedWhenRemainingFilterPresent() {
        // A remaining filter means COUNT(*) is over a filtered row set, so a
        // whole-table metadata count would be wrong.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "orders")
                    .orFail("expected sales.orders handle")

                val id = metadata.getColumnHandles(null, handle)["id"] as DuckLakeColumnHandle
                val remaining = ConnectorComparison(
                    ConnectorComparison.Operator.EQ,
                    ConnectorColumnRef("id", id.columnType),
                    ConnectorLiteral(id.columnType, 1),
                )

                val normalRanges = plan.planScan(null, handle, listOf(), Optional.empty())
                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.of(remaining), -1L, null, true,
                )
                assertThat(ranges).hasSameSizeAs(normalRanges)
                for (range in ranges) {
                    assertThat(range.pushDownRowCount).isEqualTo(-1L)
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun countPushdownRefusedWhenHandleCarriesPrunedFileSet() {
        // After applyFilter the engine-visible remaining filter may be empty, but the
        // handle's prunedFileIds mean the scan is no longer whole-table.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val region = metadata.getColumnHandles(null, handle)["region"] as DuckLakeColumnHandle
                val filter = ConnectorComparison(
                    ConnectorComparison.Operator.EQ,
                    ConnectorColumnRef("region", region.columnType),
                    ConnectorLiteral(region.columnType, "us"),
                )
                val applied = metadata.applyFilter(null, handle, ConnectorFilterConstraint(filter))
                    .orFail("expected applyFilter to push region = 'us'")
                val prunedHandle = applied.handle.asDuckLakeHandle<DuckLakeTableHandle>()
                assertThat(prunedHandle.prunedFileIds).isNotNull()

                val ranges = plan.planScan(
                    null, prunedHandle, listOf(), Optional.empty(), -1L, null, true,
                )
                assertThat(ranges).isNotEmpty()
                for (range in ranges) {
                    assertThat(range.pushDownRowCount).isEqualTo(-1L)
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun countSentinelNeverLeaksWhenCountPushdownIsOff() {
        // A stray precomputed count here would let a non-COUNT query read a
        // collapsed single-range plan.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "orders")
                    .orFail("expected sales.orders handle")

                val normalRanges = plan.planScan(null, handle, listOf(), Optional.empty())
                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.empty(), -1L, null, false,
                )
                assertThat(ranges).hasSameSizeAs(normalRanges)
                for (range in ranges) {
                    assertThat(range.pushDownRowCount).isEqualTo(-1L)
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun partitionedTableRangesArePartitionBearingWithIdentityValues() {
        // Partition-bearing makes PluginDrivenSplit emit an empty partition-values list
        // instead of null, which skips Hive key=value path parsing. Values are keyed by
        // lowercased column name, as in iceberg's getIdentityPartitionInfoMap.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val ranges = plan.planScan(null, handle, listOf(), Optional.empty())
                assertThat(ranges.size).isGreaterThanOrEqualTo(2)

                val seenRegions = HashSet<String>()
                for (range in ranges) {
                    assertThat(range.isPartitionBearing).isTrue()
                    assertThat(range.partitionValues).containsOnlyKeys("region")
                    seenRegions.add(range.partitionValues.getValue("region"))

                    // Partition values stay FE-side; the BE reads partition columns from
                    // the parquet body, so nothing goes out as columns_from_path.
                    val rangeDesc = TFileRangeDesc()
                    range.populateRangeParams(TTableFormatFileDesc(), rangeDesc)
                    assertThat(rangeDesc.isSetColumnsFromPath).isFalse()
                    assertThat(rangeDesc.isSetColumnsFromPathKeys).isFalse()
                }
                assertThat(seenRegions).containsExactlyInAnyOrder("us", "eu")
            }
    }

    @Test
    @Throws(Exception::class)
    fun bucketPartitionedRangesArePartitionBearingWithoutRawValues() {
        // A bucket partition stores the derived bucket number, not the column value,
        // so it is never surfaced as a raw value (iceberg exposes identity transforms
        // only). The ranges are still partition-bearing because the table has a spec.
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_name_bucket")
                    .orFail("expected sales.by_name_bucket handle")

                val ranges = plan.planScan(null, handle, listOf(), Optional.empty())
                assertThat(ranges.size).isGreaterThanOrEqualTo(3)
                for (range in ranges) {
                    assertThat(range.isPartitionBearing).isTrue()
                    assertThat(range.partitionValues).isEmpty()
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun unpartitionedTableRangesStayNonPartitionBearing() {
        val properties = DorisTestSupport.isolatedProperties(isolated)
        DuckLakeConnectorProvider()
            .create(properties, FakeConnectorContext("dl", 1L)).use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "orders")
                    .orFail("expected sales.orders handle")

                val ranges = plan.planScan(null, handle, listOf(), Optional.empty())
                assertThat(ranges).isNotEmpty()
                for (range in ranges) {
                    assertThat(range.isPartitionBearing).isFalse()
                    assertThat(range.partitionValues).isEmpty()
                }
            }
    }
}

// Test-only convenience overloads that pack positional arguments into a ConnectorScanRequest.
private fun ConnectorScanPlanProvider.planScan(
    session: ConnectorSession?,
    handle: ConnectorTableHandle,
    columns: List<ConnectorColumnHandle>,
    filter: Optional<ConnectorExpression>,
): List<ConnectorScanRange> =
    planScan(session, ConnectorScanRequest.builder(handle, columns).filter(filter).build())

private fun ConnectorScanPlanProvider.planScan(
    session: ConnectorSession?,
    handle: ConnectorTableHandle,
    columns: List<ConnectorColumnHandle>,
    filter: Optional<ConnectorExpression>,
    limit: Long,
    requiredPartitions: List<String>?,
    countPushdown: Boolean,
): List<ConnectorScanRange> =
    planScan(
        session,
        ConnectorScanRequest.builder(handle, columns)
            .filter(filter)
            .limit(limit)
            .requiredPartitions(requiredPartitions)
            .countPushdown(countPushdown)
            .build(),
    )
