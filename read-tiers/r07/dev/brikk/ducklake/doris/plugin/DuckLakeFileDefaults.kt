package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Only decoded public-contract values enter here. Catalog text is never an SQL expression. */
internal object DuckLakeFileDefaults {
    private val DECIMAL = Regex("decimal\\((\\d+),\\s*(\\d+)\\)")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")

    fun sql(column: DucklakeColumn, value: Any?): String {
        if (value == null) {
            check(column.nullsAllowed) { "Missing required field has NULL initial default" }
            return "NULL"
        }
        val type = column.columnType.lowercase()
        return when (type) {
            "boolean" -> (value as Boolean).toString()
            "int8", "int16", "int32", "int64" -> integer(type, value as Number)
            "varchar" -> string(value as String)
            "blob" -> binary(value as ByteArray)
            "date" -> { domain(value as LocalDate); quote(value.toString()) }
            "timestamp", "timestamp_s", "timestamp_ms" -> timestamp(type, value as LocalDateTime)
            else -> {
                if (!DECIMAL.matches(type)) throw DuckLakeUnsupportedRead("Final-1.1 decoded initial default for $type is not qualified")
                decimal(type, value as BigDecimal)
            }
        }
    }

    private fun string(value: String): String {
        check(value.length <= 4096) { "Initial default exceeds budget" }
        // Mirrors the pinned FE's Iceberg default encoder: no backslash SQL-mode dependency.
        return if ('\\' in value || value.any { it.code < 32 }) "UNHEX('${hex(value.toByteArray(Charsets.UTF_8))}')" else quote(value)
    }

    private fun binary(value: ByteArray): String {
        check(value.size <= 4096) { "Initial default exceeds budget" }
        return "X'${hex(value)}'"
    }

    private fun integer(type: String, value: Number): String {
        check(value is Byte || value is Short || value is Int || value is Long) { "Non-integral decoded default" }
        val n = value.toLong()
        val bits = type.removePrefix("int").toInt()
        check(bits == 64 || n in -(1L shl (bits - 1)) until (1L shl (bits - 1))) { "Initial default overflow" }
        return n.toString()
    }

    private fun decimal(type: String, value: BigDecimal): String {
        val match = DECIMAL.matchEntire(type)!!
        val n = value.setScale(match.groupValues[2].toInt(), java.math.RoundingMode.UNNECESSARY)
        check(n.precision() <= match.groupValues[1].toInt()) { "Decimal initial default overflow" }
        return n.toPlainString()
    }

    private fun timestamp(type: String, value: LocalDateTime): String {
        domain(value.toLocalDate())
        val quantum = when (type) { "timestamp_s" -> 1_000_000_000; "timestamp_ms" -> 1_000_000; else -> 1000 }
        check(value.nano % quantum == 0) { "Initial default exceeds lossless temporal precision" }
        return quote(TIMESTAMP.format(value))
    }

    private fun domain(value: LocalDate) { check(value.year in 1..9999) { "Initial default outside lossless Doris date domain" } }
    private fun quote(value: String) = "'${value.replace("'", "''")}'"
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
}
