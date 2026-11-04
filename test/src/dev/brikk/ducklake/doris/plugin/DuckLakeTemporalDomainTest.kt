package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn
import org.apache.doris.connector.spi.DorisConnectorException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal class DuckLakeTemporalDomainTest {
    @TempDir lateinit var root: Path

    @Test
    fun knownNonFiniteAndOutOfRangeTemporalValuesRefuse() {
        for (value in listOf("infinity", "-infinity", "+INF", "10000-01-01", "0001-01-01 (BC)",
            LocalDateTime.MAX, Timestamp.valueOf(LocalDateTime.of(10000, 1, 1, 0, 0)), Long.MAX_VALUE)) {
            assertThatThrownBy { DuckLakeTemporalDomain.requireRepresentable("timestamp", value, "ts") }
                .describedAs(value.toString()).isInstanceOf(DorisConnectorException::class.java)
                .hasMessageContaining("temporal domain")
        }
        assertThatThrownBy { DuckLakeTemporalDomain.requireRepresentable("date", LocalDate.MIN, "d") }
            .isInstanceOf(DorisConnectorException::class.java)
    }

    @Test
    fun finiteValuesAndNonTemporalInfinityStringsRemainUnchanged() {
        for (value in listOf("1992-01-01 00:00:00", LocalDateTime.of(2020, 1, 1, 0, 0), 0L)) {
            assertThatCode { DuckLakeTemporalDomain.requireRepresentable("timestamp", value, "ts") }.doesNotThrowAnyException()
        }
        assertThatCode { DuckLakeTemporalDomain.requireRepresentable("varchar", "infinity", "text") }.doesNotThrowAnyException()
        assertThatCode { DuckLakeTemporalDomain.requireRepresentable("float64", Double.POSITIVE_INFINITY, "number") }
            .doesNotThrowAnyException()
        assertThatCode { DuckLakeTemporalDomain.requireRepresentable("date", null, "d") }.doesNotThrowAnyException()
    }

    @Test
    fun timezoneNormalizationCannotMoveAnInlineValueOutsideTheDorisDomain() {
        val value = OffsetDateTime.of(9999, 12, 31, 23, 0, 0, 0, ZoneOffset.ofHours(-2))
        assertThatThrownBy { DuckLakeTemporalDomain.requireRepresentable("timestamptz", value, "ts") }
            .isInstanceOf(DorisConnectorException::class.java)
    }

    @Test
    fun inlineTemporalValidationPrecedesFileCreationOrReplacement() {
        val columns = listOf(DucklakeColumn(1L, 1L, null, 1L, 0L, "ts", "timestamp", true, null))
        val rows = listOf(listOf<Any?>(LocalDateTime.of(2020, 1, 1, 0, 0)), listOf<Any?>(LocalDateTime.MAX))
        val target = root.resolve("scratch.parquet")
        assertThatThrownBy { DuckLakeInlinedParquetWriter.write(target, columns, rows) }
            .isInstanceOf(DorisConnectorException::class.java)
        assertThat(Files.exists(target)).isFalse()
        val original = "existing output must not be truncated".toByteArray()
        Files.write(target, original)
        assertThatThrownBy { DuckLakeInlinedParquetWriter.write(target, columns, rows) }
            .isInstanceOf(DorisConnectorException::class.java)
        assertThat(Files.readAllBytes(target)).isEqualTo(original)
    }

    @Test
    fun encodedTimezoneOverflowAndByteEncodedInfinityCannotCreateScratchFiles() {
        val columns = listOf(DucklakeColumn(1L, 1L, null, 1L, 0L, "ts", "timestamptz", true, null))
        val target = root.resolve("overflow.parquet")
        for (value in listOf("9999-12-31 23:00:00-02:00", "infinity".toByteArray())) {
            assertThatThrownBy { DuckLakeInlinedParquetWriter.write(target, columns, listOf(listOf(value))) }
                .isInstanceOf(DorisConnectorException::class.java)
            assertThat(Files.exists(target)).isFalse()
        }
    }
}
