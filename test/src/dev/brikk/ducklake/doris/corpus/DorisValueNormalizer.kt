package dev.brikk.ducklake.doris.corpus

import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Renders a Doris JDBC cell the way the corpus replay oracle renders the same DuckDB-JDBC value, so
 * rows can be compared by string equality: Java `toString` numerics, `yyyy-MM-dd HH:mm:ss[.ffffff]`
 * with the fraction trimmed, UTC `+00` for zone-aware timestamps, DuckDB `\xHH` blob escapes, `\0`
 * for NUL. SQL NULL stays `null`.
 *
 * Doris BOOLEAN is TINYINT(1) on the wire and may arrive as a `Number`; callers pass
 * [logicalBoolean] from the result-set metadata so it still renders `true`/`false`.
 */
object DorisValueNormalizer {

    fun normalize(value: Any?, logicalBoolean: Boolean = false): String? =
        when (value) {
            null -> null
            is Boolean -> if (value) "true" else "false"
            is String -> value.replace("\u0000", "\\0")
            is Number ->
                if (logicalBoolean) {
                    if (value.toInt() != 0) "true" else "false"
                } else {
                    renderNumber(value)
                }
            is ByteArray -> renderBlob(value)
            else -> renderTemporal(value) ?: value.toString()
        }

    private fun renderTemporal(value: Any): String? =
        when (value) {
            is Timestamp -> renderDateTime(value.toLocalDateTime())
            is LocalDateTime -> renderDateTime(value)
            is OffsetDateTime ->
                renderDateTime(value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()) + "+00"
            is java.sql.Date -> value.toLocalDate().toString()
            is LocalDate -> value.toString()
            is java.sql.Time -> renderTime(value.toLocalTime())
            is LocalTime -> renderTime(value)
            else -> null
        }

    /** `BigDecimal.toString()` can go scientific (`1E+3`); `toPlainString()` cannot. */
    private fun renderNumber(value: Number): String =
        when (value) {
            is BigDecimal -> value.toPlainString()
            else -> value.toString()
        }

    private fun renderDateTime(dt: LocalDateTime): String {
        val base = "%04d-%02d-%02d %02d:%02d:%02d".format(
            dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute, dt.second,
        )
        return base + fraction(dt.nano)
    }

    private fun renderTime(t: LocalTime): String =
        "%02d:%02d:%02d".format(t.hour, t.minute, t.second) + fraction(t.nano)

    private fun fraction(nano: Int): String {
        if (nano == 0) {
            return ""
        }
        val micros = "%06d".format(nano / MICROS_DIVISOR).trimEnd('0')
        return if (micros.isEmpty()) "" else ".$micros"
    }

    private fun renderBlob(bytes: ByteArray): String =
        buildString {
            for (b in bytes) {
                val i = b.toInt() and BYTE_MASK
                when {
                    i == '\\'.code -> append("\\\\")
                    i in PRINTABLE_MIN..PRINTABLE_MAX -> append(i.toChar())
                    else -> append("\\x%02X".format(i))
                }
            }
        }

    private const val MICROS_DIVISOR = 1000
    private const val BYTE_MASK = 0xFF
    private const val PRINTABLE_MIN = 0x20
    private const val PRINTABLE_MAX = 0x7E
}
