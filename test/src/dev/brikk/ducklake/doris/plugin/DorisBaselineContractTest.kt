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
import org.apache.doris.thrift.TReportRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Pins the installed SPI and generated Thrift contract to the Doris baseline commit (4bc7f426828) rather than Maven coordinates. */
internal class DorisBaselineContractTest {
    @Test
    fun installedSpiReportsApi27() {
        val properties = Properties()
        val resource = requireNotNull(
            Connector::class.java.getResourceAsStream(
                "/META-INF/doris/connector-plugin-api-version.properties",
            ),
        ) { "SPI API version resource missing" }
        resource.use { properties.load(it) }
        assertThat(properties.getProperty("api.version")).isEqualTo("27.0")
    }

    @Test
    fun scannerV2IsTheWireDefaultAndNewTelemetryFieldsAreAdditive() {
        assertThat(TQueryOptions._Fields.ENABLE_FILE_SCANNER_V2.thriftFieldId).isEqualTo(1002.toShort())
        assertThat(TQueryOptions().getFieldValue(TQueryOptions._Fields.ENABLE_FILE_SCANNER_V2)).isEqualTo(true)
        assertThat(TReportRequest._Fields.TOP_QUERY_TABLETS.thriftFieldId).isEqualTo(18.toShort())
        assertThat(TReportRequest._Fields.TOP_LOAD_TABLETS.thriftFieldId).isEqualTo(19.toShort())
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
        // These field ids changed meaning upstream while the API version manifest stayed the same,
        // so a matching manifest alone cannot certify the generated Thrift against the FE/BE.
        assertThat(TReportExecStatusParams._Fields.PAIMON_COMMIT_MESSAGES.thriftFieldId).isEqualTo(34.toShort())
        assertThat(TPaimonFileDesc._Fields.ORIGINAL_FILE_PATH.thriftFieldId).isEqualTo(20.toShort())
    }
}
