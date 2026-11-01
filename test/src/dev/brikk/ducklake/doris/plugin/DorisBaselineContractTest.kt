package dev.brikk.ducklake.doris.plugin

import java.util.Properties
import java.util.concurrent.CancellationException

import org.apache.doris.connector.spi.Connector
import org.apache.doris.connector.spi.scan.ConnectorScanContext
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.doris.thrift.TIcebergTableSink
import org.apache.doris.thrift.TPaimonFileDesc
import org.apache.doris.thrift.TQueryOptions
import org.apache.doris.thrift.TReportExecStatusParams
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Pins the installed SPI and regenerated wire contract to 8e10b235cf0, not mutable Maven coordinates. */
internal class DorisBaselineContractTest {
    @Test
    fun installedSpiReportsApi26() {
        val properties = Properties()
        val resource = requireNotNull(
            Connector::class.java.getResourceAsStream(
                "/META-INF/doris/connector-plugin-api-version.properties",
            ),
        ) { "SPI API version resource missing" }
        resource.use { properties.load(it) }
        assertThat(properties.getProperty("api.version")).isEqualTo("26.0")
    }

    @Test
    fun api26RequestBatchesRetainTheSameExplicitCancellationContext() {
        val context = ConnectorScanContext()
        val handle = DuckLakeTableHandle("main", "facts", 1L, 2L, 3L)
        val request = ConnectorScanRequest.builder(handle, emptyList()).scanContext(context).build()
        val batch = request.withRequiredPartitions(listOf("p=1"))
        assertThat(request.scanContext).isSameAs(context)
        assertThat(batch.scanContext).isSameAs(context)
        context.checkCancelled()
        context.cancel()
        assertThatThrownBy { batch.scanContext.checkCancelled() }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun queryOptionIdsMatchTheRealignedFeBeContract() {
        assertThat(TQueryOptions._Fields.ENABLE_PRUNE_NESTED_COLUMN.thriftFieldId).isEqualTo(230.toShort())
        assertThat(TQueryOptions._Fields.SUPPORTS_EXTERNAL_FILE_REPORT_ACK.thriftFieldId).isEqualTo(236.toShort())
    }

    @Test
    fun icebergSinkIdsMatchTheRealignedWriteContract() {
        assertThat(TIcebergTableSink._Fields.STATIC_PARTITION_NULL_KEYS.thriftFieldId).isEqualTo(19.toShort())
        assertThat(TIcebergTableSink._Fields.NAN_COUNT_FIELD_IDS.thriftFieldId).isEqualTo(20.toShort())
    }

    @Test
    fun externalReportAndPaimonIdsMatchTheApi24Runtime() {
        // #68837 changed these wire meanings after API 13. A matching manifest
        // alone must not certify the old generated Thrift against the new FE/BE.
        assertThat(TReportExecStatusParams._Fields.PAIMON_COMMIT_MESSAGES.thriftFieldId).isEqualTo(34.toShort())
        assertThat(TPaimonFileDesc._Fields.ORIGINAL_FILE_PATH.thriftFieldId).isEqualTo(20.toShort())
    }
}
