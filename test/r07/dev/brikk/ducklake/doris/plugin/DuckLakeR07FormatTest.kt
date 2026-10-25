package dev.brikk.ducklake.doris.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.doris.thrift.TFileRangeDesc
import org.apache.doris.thrift.TTableFormatFileDesc
import org.apache.thrift.TDeserializer
import org.apache.thrift.TSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal class DuckLakeR07FormatTest {
    // Envelope-only authored fixture. Native SQL tests separately qualify real Roaring decoding.
    private fun frame(): ByteArray {
        val bytes = ByteBuffer.allocate(24).putInt(16).put(byteArrayOf(-47, -45, 57, 100)).putLong(0).putInt(0).array()
        ByteBuffer.wrap(bytes, 20, 4).putInt(CRC32().apply { update(bytes, 4, 16) }.value.toInt())
        return bytes
    }

    private fun container(tags: List<String> = listOf("1", "2")): ByteArray {
        val vector = frame()
        val footer = ObjectMapper().writeValueAsBytes(mapOf("blobs" to tags.mapIndexed { i, tag -> mapOf(
            "type" to "deletion-vector-v1", "offset" to 4 + vector.size * i, "length" to vector.size,
            "properties" to mapOf("ducklake-snapshot-id" to tag),
        ) }))
        return "PFA1".toByteArray() + vector + vector + "PFA1".toByteArray() + footer +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(footer.size).putInt(0).array() + "PFA1".toByteArray()
    }

    @Test fun vectorWireHasAllOptionalBitsAndNeverMisroutesDataAsDeletes() {
        val range = DuckLakeScanRange.Builder().path("s3://b/data.parquet").length(100).fileSize(100)
            .fileFormat("parquet").positionDeletes(listOf(DuckLakePositionDelete("s3://b/vector.puffin", "puffin", 4, 24))).build()
        val format = TTableFormatFileDesc()
        range.populateRangeParams(format, TFileRangeDesc())
        val received = TTableFormatFileDesc()
        TDeserializer().deserialize(received, TSerializer().serialize(format))
        assertThat(received.icebergParams.isSetContent).isFalse()
        val vector = received.icebergParams.deleteFiles.single()
        assertThat(vector.isSetContent).isTrue()
        assertThat(vector.content).isEqualTo(3)
        assertThat(vector.isSetContentOffset).isTrue()
        assertThat(vector.contentOffset).isEqualTo(4)
        assertThat(vector.isSetContentSizeInBytes).isTrue()
        assertThat(vector.contentSizeInBytes).isEqualTo(24)
        assertThat(received.isSetTableLevelRowCount).isFalse()
    }

    @Test fun sameContainerPathSelectsDifferentSnapshotSlicesNotSelfCancellation() {
        val bytes = container()
        assertThat(DuckLakeFinal11Files.vectorSlice(bytes, 1)).isEqualTo(4 to 24)
        assertThat(DuckLakeFinal11Files.vectorSlice(bytes, 2)).isEqualTo(28 to 24)
        assertThatThrownBy { DuckLakeFinal11Files.vectorSlice(bytes, 0) }.hasMessageContaining("No applicable")
    }

    @Test fun vectorFramesRejectBadMagicLengthAndCrc() {
        DuckLakeFinal11Files.validateVector(frame())
        assertThatThrownBy { DuckLakeFinal11Files.validateVector(frame().also { it[4] = 0 }) }.hasMessageContaining("magic")
        assertThatThrownBy { DuckLakeFinal11Files.validateVector(frame().copyOf(23)) }.hasMessageContaining("length")
        assertThatThrownBy { DuckLakeFinal11Files.validateVector(frame().also { it[23] = (it[23].toInt() xor 1).toByte() }) }.hasMessageContaining("CRC")
    }

    @Test fun containersRejectAmbiguousTagsAndUnsupportedFlags() {
        assertThatThrownBy { DuckLakeFinal11Files.vectorSlice(container(listOf("1", "1")), 2) }.hasMessageContaining("duplicate")
        assertThatThrownBy { DuckLakeFinal11Files.vectorSlice(container().also { it[it.size - 8] = 1 }, 2) }.hasMessageContaining("flags")
    }

    @Test fun callerBudgetsAreTotalNotPerPageOrTransfer() {
        val pages = DuckLakeReadBudget(mapOf("final11.max-planning-pages" to "1"))
        pages.page()
        assertThatThrownBy { pages.page() }.hasMessageContaining("page budget")
        val paths = DuckLakeReadBudget(mapOf("final11.max-planning-paths" to "1"))
        paths.path("s3://b/a")
        assertThatThrownBy { paths.path("s3://b/b") }.hasMessageContaining("retained-path budget")
        val bytes = DuckLakeReadBudget(mapOf("final11.max-planning-bytes" to "4"))
        bytes.transfer(3)
        assertThatThrownBy { bytes.transfer(2) }.hasMessageContaining("byte budget")
        assertThatThrownBy { DuckLakeReadBudget(mapOf("final11.max-planning-work" to "0")) }.hasMessageContaining("Invalid")
        assertThatThrownBy { DuckLakeReadBudget(mapOf("final11.history-window" to "17")) }.hasMessageContaining("0..16")
        assertThat(DuckLakeReadBudget(mapOf("final11.max-planning-page-size" to "1")).pageSize).isEqualTo(1)
        assertThatThrownBy { DuckLakeReadBudget(mapOf("final11.max-planning-page-size" to "129")) }.hasMessageContaining("maximum 128")
    }

    @Test fun checkedPlanningFailuresRetainTheirCauseAndRuntimeFailuresKeepIdentity() {
        val checked = java.io.IOException("owned object read failed")
        val exposed = DuckLakeFinal11.planningFailure(checked)
        assertThat(exposed).isInstanceOf(org.apache.doris.connector.spi.DorisConnectorException::class.java)
            .hasMessageContaining("owned object read failed").hasCause(checked)
        val budget = IllegalStateException("budget exhausted")
        assertThat(DuckLakeFinal11.planningFailure(budget)).isSameAs(budget)
        val cancelled = java.util.concurrent.CancellationException("cancelled")
        assertThat(DuckLakeFinal11.planningFailure(cancelled)).isSameAs(cancelled)
    }

    @Test fun jdbcDeadlineRoundsUpWithoutRaisingTheTotalPlanningBudget() {
        assertThat(DuckLakeReadBudget(emptyMap()).queryTimeoutSeconds).isEqualTo(30)
        assertThat(DuckLakeReadBudget(mapOf("final11.max-planning-millis" to "1")).queryTimeoutSeconds).isEqualTo(1)
        assertThat(DuckLakeReadBudget(mapOf("final11.max-planning-millis" to "1001")).queryTimeoutSeconds).isEqualTo(2)
        assertThatThrownBy { DuckLakeReadBudget(mapOf("final11.max-planning-millis" to "30001")) }.hasMessageContaining("Invalid")
        val text = DuckLakeReadBudget(emptyMap())
        text.literal("x".repeat(4_194_300))
        text.path("s3:x")
        assertThatThrownBy { text.literal("1") }.hasMessageContaining("path/default-byte budget")
    }
}
