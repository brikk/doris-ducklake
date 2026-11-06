package dev.brikk.ducklake.doris.plugin

import java.util.Optional
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.doris.plugin.cache.FakeConnectorContext
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.scan.ConnectorScanRange
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.thrift.TTableFormatFileDesc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * LIMIT-pushdown file trimming: on a clean scan the provider emits only the prefix of data
 * files whose cumulative `record_count` covers the limit, and falls back to the full file set
 * whenever a filter, deletes, or a non-positive limit could make trimming under-return.
 *
 * `sales.by_region` is used because CHECKPOINT cannot compact across partitions, so it keeps
 * exactly two data files ('us' ids 1,2 and 'eu' ids 3,4) of two rows each.
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
        // LIMIT 1 and 2 are covered by the first file alone; LIMIT 3 needs both.
        // The BE still applies the real LIMIT on top.
        DuckLakeConnectorProvider()
            .create(DorisTestSupport.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
            .use { connector ->
                val metadata = connector.getMetadata(null)
                val plan = connector.getScanPlanProvider()
                val handle = metadata.getTableHandle(null, "sales", "by_region")
                    .orFail("expected sales.by_region handle")

                val fullRanges = plan.planScan(null, handle, listOf(), Optional.empty())
                assertThat(fullRanges).hasSize(2)

                val oneRow = plan.planScan(null, handle, listOf(), Optional.empty(), 1L, null, false)
                assertThat(oneRow).hasSize(1)
                // Trimmed ranges stay normal ranges, not the count-pushdown collapse.
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
        DuckLakeConnectorProvider()
            .create(DorisTestSupport.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
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
        // -1 is the no-limit sentinel.
        DuckLakeConnectorProvider()
            .create(DorisTestSupport.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
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
        // The BE applies a remaining filter before the limit, so record_count
        // over-counts the rows that reach the limit and trimming could under-return.
        DuckLakeConnectorProvider()
            .create(DorisTestSupport.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
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
        // returns_file carries a position-delete file, so record_count (4) over-counts
        // the 3 live rows.
        DuckLakeConnectorProvider()
            .create(DorisTestSupport.isolatedProperties(isolated), FakeConnectorContext("dl", 1L))
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
