package dev.brikk.ducklake.doris.plugin

import java.util.Optional
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.api.pushdown.ConnectorColumnRef
import org.apache.doris.connector.api.pushdown.ConnectorComparison
import org.apache.doris.connector.api.pushdown.ConnectorLiteral
import org.apache.doris.thrift.TTableFormatFileDesc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * LIMIT-pushdown file trimming on [DuckLakeScanPlanProvider] (7-arg `planScan`
 * `limit`). On a clean scan — the exact conditions under which COUNT(*) is
 * servable from metadata — the provider emits only the minimal PREFIX of data
 * files whose cumulative `record_count` covers the limit, since the BE stops
 * reading after `limit` rows. Any doubt (a filter in play, position deletes,
 * inlined rows, partial files, a non-latest snapshot, or a non-positive limit)
 * must fall back to the full file set so the query can never under-return.
 *
 * The `sales.by_region` fixture is ideal: CHECKPOINT can't compact across
 * partitions, so it keeps exactly two data files ('us' ids 1,2 and 'eu' ids 3,4),
 * two rows each — a stable multi-file scan to trim.
 */
internal class DuckLakeScanPlanLimitPushdownTest {

    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        private lateinit var isolated: DuckLakeTestCatalogBootstrap.IsolatedCatalog

        @BeforeAll
        @JvmStatic
        @Throws(Exception::class)
        fun setUp() {
            server = TestingDucklakePostgreSqlCatalogServer()
            isolated = DuckLakeTestCatalogBootstrap.bootstrap(server, "scanplanlimit")
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            server.close()
        }
    }

    @Test
    @Throws(Exception::class)
    fun limitTrimsCleanTableToMinimalFilePrefix() {
        // by_region: two files, two rows each. LIMIT 1 (and LIMIT 2) is covered by
        // the first file alone (2 >= limit) → one range; LIMIT 3 needs the second
        // file too (2 < 3, 4 >= 3) → both ranges. The BE still applies the real
        // LIMIT on top; we only spare it the unreachable second file.
        DuckLakeConnectorProvider()
            .create(DorisTestIdiomKit.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
            .use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val fullRanges = plan.planScan(null, handle, listOf(), Optional.empty())
                assertThat(fullRanges).hasSize(2)

                val oneRow = plan.planScan(null, handle, listOf(), Optional.empty(), 1L, null, false)
                assertThat(oneRow).hasSize(1)
                // Trimmed ranges stay NORMAL ranges (not the count-pushdown
                // collapse): the -1 sentinel is intact so BE reads + limits.
                assertThat(oneRow[0].pushDownRowCount).isEqualTo(-1L)
                val formatDesc = TTableFormatFileDesc()
                oneRow[0].populateRangeParams(formatDesc, org.apache.doris.thrift.TFileRangeDesc())
                assertThat(formatDesc.isSetTableLevelRowCount).isFalse()

                val twoRows = plan.planScan(null, handle, listOf(), Optional.empty(), 2L, null, false)
                assertThat(twoRows).hasSize(1)

                val threeRows = plan.planScan(null, handle, listOf(), Optional.empty(), 3L, null, false)
                assertThat(threeRows).hasSize(2)
            }
    }

    @Test
    @Throws(Exception::class)
    fun limitKeepsAllFilesWhenLimitExceedsRowCount() {
        // LIMIT larger than the whole table (4 rows) can't drop anything.
        DuckLakeConnectorProvider()
            .create(DorisTestIdiomKit.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
            .use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val ranges = plan.planScan(null, handle, listOf(), Optional.empty(), 100L, null, false)
                assertThat(ranges).hasSize(2)
            }
    }

    @Test
    @Throws(Exception::class)
    fun limitNonPositiveIsNoOp() {
        // -1 (the no-limit sentinel) and 0 must behave exactly like the 4-arg
        // planScan: full file set, no trim.
        DuckLakeConnectorProvider()
            .create(DorisTestIdiomKit.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
            .use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                assertThat(plan.planScan(null, handle, listOf(), Optional.empty(), -1L, null, false))
                    .hasSize(2)
                assertThat(plan.planScan(null, handle, listOf(), Optional.empty(), 0L, null, false))
                    .hasSize(2)
            }
    }

    @Test
    @Throws(Exception::class)
    fun limitRefusedWhenRemainingFilterPresent() {
        // A remaining (BE-evaluated) filter means the BE applies the predicate
        // BEFORE the limit, so a file's record_count over-counts the rows that
        // survive to the limit — trimming could under-return. The gate refuses:
        // the full file set is emitted even though LIMIT 1 would otherwise trim
        // to one file.
        DuckLakeConnectorProvider()
            .create(DorisTestIdiomKit.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
            .use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val amount = metadata.getColumnHandles(null, handle)["amount"] as DuckLakeColumnHandle
                val remaining = ConnectorComparison(
                    ConnectorComparison.Operator.GT,
                    ConnectorColumnRef("amount", amount.columnType),
                    ConnectorLiteral(amount.columnType, 0.0),
                )

                val ranges = plan.planScan(
                    null, handle, listOf(), Optional.of(remaining), 1L, null, false,
                )
                assertThat(ranges).hasSize(2)
                for (range in ranges) {
                    assertThat(range.pushDownRowCount).isEqualTo(-1L)
                }
            }
    }

    @Test
    @Throws(Exception::class)
    fun limitRefusedWhenTableHasDeleteFiles() {
        // returns_file carries a position-delete file, so record_count (4 inserted)
        // over-counts the 3 live rows. The gate refuses trimming: the data file +
        // its delete survive, and the range stays a normal (-1) range.
        DuckLakeConnectorProvider()
            .create(DorisTestIdiomKit.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
            .use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "returns_file")
                    .orFail("expected sales.returns_file handle")

                val ranges = plan.planScan(null, handle, listOf(), Optional.empty(), 1L, null, false)
                assertThat(ranges).hasSize(1)
                assertThat(ranges[0].pushDownRowCount).isEqualTo(-1L)
                val formatDesc = TTableFormatFileDesc()
                ranges[0].populateRangeParams(formatDesc, org.apache.doris.thrift.TFileRangeDesc())
                assertThat(formatDesc.icebergParams.deleteFiles).hasSize(1)
            }
    }
}
