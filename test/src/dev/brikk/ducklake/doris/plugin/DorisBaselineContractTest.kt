package dev.brikk.ducklake.doris.plugin

import java.util.Properties

import org.apache.doris.connector.spi.Connector
import org.apache.doris.thrift.TIcebergTableSink
import org.apache.doris.thrift.TQueryOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pins the installed SPI and regenerated wire contract to dd456ab7a89, not mutable Maven coordinates. */
internal class DorisBaselineContractTest {
    @Test
    fun installedSpiReportsApi13() {
        val properties = Properties()
        val resource = requireNotNull(
            Connector::class.java.getResourceAsStream(
                "/META-INF/doris/connector-plugin-api-version.properties",
            ),
        ) { "SPI API version resource missing" }
        resource.use { properties.load(it) }
        assertThat(properties.getProperty("api.version")).isEqualTo("13.0")
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
}
