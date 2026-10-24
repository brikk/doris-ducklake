package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

internal class DuckLakeFileDefaultsTest {
    private fun column(type: String, nullable: Boolean = true) = DucklakeColumn(
        columnId = 1, tableId = 1, columnOrder = 0, columnName = "v", columnType = type,
        initialDefault = "UNTRUSTED TEXT MUST NOT BE PARSED",
        nullsAllowed = nullable, parentColumn = null, beginSnapshot = 0, endSnapshot = null,
    )

    @Test fun decodedValuesAreEncodedNotRawCatalogSql() {
        assertThat(DuckLakeFileDefaults.sql(column("int64"), 42L)).isEqualTo("42")
        assertThat(DuckLakeFileDefaults.sql(column("boolean"), true)).isEqualTo("true")
        assertThat(DuckLakeFileDefaults.sql(column("decimal(20,3)"), BigDecimal("12345678901234567.125")))
            .isEqualTo("12345678901234567.125")
        assertThat(DuckLakeFileDefaults.sql(column("varchar"), "NULL")).isEqualTo("'NULL'")
        assertThat(DuckLakeFileDefaults.sql(column("varchar"), "x'); DROP TABLE t; --")).isEqualTo("'x''); DROP TABLE t; --'")
        assertThat(DuckLakeFileDefaults.sql(column("varchar"), "\\x41")).isEqualTo("UNHEX('5C783431')")
        assertThat(DuckLakeFileDefaults.sql(column("blob"), byteArrayOf(0, 0x5c, -1))).isEqualTo("X'005CFF'")
        assertThat(DuckLakeFileDefaults.sql(column("date"), LocalDate.parse("2024-02-29"))).isEqualTo("'2024-02-29'")
        assertThat(DuckLakeFileDefaults.sql(column("timestamp"), LocalDateTime.parse("1969-12-31T23:59:59.123456")))
            .isEqualTo("'1969-12-31 23:59:59.123456'")
        assertThat(DuckLakeFileDefaults.sql(column("timestamp_s"), LocalDateTime.parse("2024-02-29T12:34:56")))
            .isEqualTo("'2024-02-29 12:34:56.000000'")
        assertThat(DuckLakeFileDefaults.sql(column("timestamp_ms"), LocalDateTime.parse("2024-02-29T12:34:56.123")))
            .isEqualTo("'2024-02-29 12:34:56.123000'")
    }

    @Test fun sqlNullIsNotLiteralNullAndUnsupportedDomainsStayClosed() {
        assertThat(DuckLakeFileDefaults.sql(column("blob"), null)).isEqualTo("NULL")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("int32", false), null) }.hasMessageContaining("required field")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("int8"), 128) }.hasMessageContaining("overflow")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("int32"), 42.5) }.hasMessageContaining("Non-integral")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("float64"), 1.5) }.hasMessageContaining("not qualified")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("timestamp"), LocalDateTime.parse("2024-02-29T00:00:00.123456789")) }
            .hasMessageContaining("precision")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("timestamp_s"), LocalDateTime.parse("2024-02-29T00:00:00.001")) }
            .hasMessageContaining("precision")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("date"), LocalDate.of(0, 1, 1)) }.hasMessageContaining("domain")
        assertThatThrownBy { DuckLakeFileDefaults.sql(column("struct<a:int32>"), mapOf("a" to 1)) }.hasMessageContaining("not qualified")
    }
}
