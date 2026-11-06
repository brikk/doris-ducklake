package dev.brikk.ducklake.doris.corpus

import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

import org.junit.jupiter.api.Test

import org.assertj.core.api.Assertions.assertThat

/** Pins [DorisValueNormalizer] to the forms the oracle's `GoldenComparator.renderCell` produces. */
internal class DorisValueNormalizerTest {

    @Test
    fun nullStaysNullAndEmptyStringStaysEmpty() {
        // The corpus runner canonicalizes NULL to "NULL" and "" to "(empty)" itself;
        // doing it here too would double-encode.
        assertThat(DorisValueNormalizer.normalize(null)).isNull()
        assertThat(DorisValueNormalizer.normalize("")).isEqualTo("")
    }

    @Test
    fun stringsEscapeNulBytesLikeDuckDbGoldenText() {
        assertThat(DorisValueNormalizer.normalize("a\u0000b")).isEqualTo("a\\0b")
        assertThat(DorisValueNormalizer.normalize("plain")).isEqualTo("plain")
    }

    @Test
    fun booleansRenderTrueFalseIncludingDorisTinyintForm() {
        // Doris BOOLEAN is TINYINT(1) on the mysql protocol; depending on connection
        // props the driver hands back Boolean or a Number.
        assertThat(DorisValueNormalizer.normalize(true)).isEqualTo("true")
        assertThat(DorisValueNormalizer.normalize(false)).isEqualTo("false")
        assertThat(DorisValueNormalizer.normalize(1, logicalBoolean = true)).isEqualTo("true")
        assertThat(DorisValueNormalizer.normalize(0, logicalBoolean = true)).isEqualTo("false")
        assertThat(DorisValueNormalizer.normalize(1)).isEqualTo("1")
    }

    @Test
    fun timestampsTrimMicrosecondFractionDuckDbStyle() {
        val base = LocalDateTime.of(2026, 7, 5, 12, 34, 56)
        assertThat(DorisValueNormalizer.normalize(Timestamp.valueOf(base)))
            .isEqualTo("2026-07-05 12:34:56")
        assertThat(DorisValueNormalizer.normalize(base.withNano(500_000_000)))
            .isEqualTo("2026-07-05 12:34:56.5")
        assertThat(DorisValueNormalizer.normalize(base.withNano(123_456_000)))
            .isEqualTo("2026-07-05 12:34:56.123456")
        // The comparator renders micros only, so sub-micro nanos truncate.
        assertThat(DorisValueNormalizer.normalize(base.withNano(999)))
            .isEqualTo("2026-07-05 12:34:56")
    }

    @Test
    fun zoneAwareTimestampsNormalizeToUtcWithPlusZeroZeroSuffix() {
        val odt = OffsetDateTime.of(2026, 7, 5, 14, 30, 0, 0, ZoneOffset.ofHours(2))
        assertThat(DorisValueNormalizer.normalize(odt)).isEqualTo("2026-07-05 12:30:00+00")
    }

    @Test
    fun datesAndTimesRenderIso() {
        assertThat(DorisValueNormalizer.normalize(LocalDate.of(2026, 7, 5))).isEqualTo("2026-07-05")
        assertThat(DorisValueNormalizer.normalize(java.sql.Date.valueOf("2026-07-05")))
            .isEqualTo("2026-07-05")
        assertThat(DorisValueNormalizer.normalize(LocalTime.of(1, 2, 3))).isEqualTo("01:02:03")
        assertThat(DorisValueNormalizer.normalize(LocalTime.of(1, 2, 3, 450_000_000)))
            .isEqualTo("01:02:03.45")
    }

    @Test
    fun decimalsKeepScaleAndNeverGoScientific() {
        assertThat(DorisValueNormalizer.normalize(BigDecimal("1.50"))).isEqualTo("1.50")
        assertThat(DorisValueNormalizer.normalize(BigDecimal("1E+3"))).isEqualTo("1000")
        assertThat(DorisValueNormalizer.normalize(BigDecimal("-0.001"))).isEqualTo("-0.001")
    }

    @Test
    fun floatsMatchTheOraclesJavaRenderingsNotDuckDbCliText() {
        // The oracle side goes through JDBC getObject and Java toString, so
        // "Infinity"/"NaN" are the agreed forms rather than DuckDB's "inf"/"nan".
        assertThat(DorisValueNormalizer.normalize(1.5)).isEqualTo("1.5")
        assertThat(DorisValueNormalizer.normalize(Double.POSITIVE_INFINITY)).isEqualTo("Infinity")
        assertThat(DorisValueNormalizer.normalize(Double.NaN)).isEqualTo("NaN")
        assertThat(DorisValueNormalizer.normalize(1.5f)).isEqualTo("1.5")
    }

    @Test
    fun varbinaryRendersDuckDbBlobEscapes() {
        assertThat(DorisValueNormalizer.normalize("abc".toByteArray())).isEqualTo("abc")
        assertThat(DorisValueNormalizer.normalize("a\\b".toByteArray())).isEqualTo("a\\\\b")
        assertThat(DorisValueNormalizer.normalize(byteArrayOf(0x00, 0x1F, 0x7F.toByte())))
            .isEqualTo("\\x00\\x1F\\x7F")
        assertThat(DorisValueNormalizer.normalize(byteArrayOf(0xFF.toByte()))).isEqualTo("\\xFF")
    }

    @Test
    fun integersPassThrough() {
        assertThat(DorisValueNormalizer.normalize(42L)).isEqualTo("42")
        assertThat(DorisValueNormalizer.normalize(-7)).isEqualTo("-7")
    }
}
