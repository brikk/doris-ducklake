package dev.brikk.ducklake.doris.plugin

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.nio.file.Path
import java.util.Locale

import dev.brikk.ducklake.catalog.DucklakeColumn

import org.apache.hadoop.conf.Configuration
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroup
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.example.GroupWriteSupport
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type
import org.apache.parquet.schema.Types

/**
 * Writes DuckLake inlined rows (rows the catalog keeps in `ducklake_inlined_data_*`, not in data
 * files) to a temp Parquet file the BE can scan like any other data file.
 *
 * Dev/compose only: the BE opens the file by path, so FE and BE must share a filesystem. The caller
 * gates this to local-filesystem warehouses and an explicit opt-in.
 *
 * Each column gets `field_id == column_id` so the BE's field-id matching resolves it like a real
 * DuckLake file. Scalar columns only; values arrive from jOOQ either typed or as DuckDB text
 * depending on backend and type, so every converter accepts both.
 */
internal object DuckLakeInlinedParquetWriter {

    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_MICRO = 1_000L

    /** [rows] in [columns] order, as returned by `DucklakeCatalog.readInlinedData`. Returns the row count. */
    fun write(target: Path, columns: List<DucklakeColumn>, rows: List<List<Any?>>): Long {
        // Before the scratch file is overwritten, and before an infinite timestamp wraps Long arithmetic.
        for (row in rows) {
            columns.forEachIndexed { index, column ->
                validateTemporalValue(column, row[index])
            }
        }
        val schema = buildMessageType(columns)
        val conf = Configuration()
        GroupWriteSupport.setSchema(schema, conf)
        parquetWriter(target, schema, conf).use { writer ->
            for (row in rows) {
                val group = SimpleGroup(schema)
                for (i in columns.indices) {
                    appendValue(group, i, columns[i].columnType, row[i])
                }
                writer.write(group)
            }
        }
        return rows.size.toLong()
    }

    private fun validateTemporalValue(column: DucklakeColumn, value: Any?) {
        if (value == null) return
        DuckLakeTemporalDomain.requireRepresentable(column.columnType, value, column.columnName)
        // Zone normalization can cross a year boundary even when the printed year is valid.
        val encoded = when (column.columnType.lowercase(Locale.ROOT)) {
            "date" -> toEpochDay(value)
            "timestamp", "timestamp_s", "timestamp_ms", "timestamp_ns" -> toEpochMicros(value)
            "timestamptz" -> toEpochMicrosTz(value)
            else -> return
        }
        DuckLakeTemporalDomain.requireRepresentable(column.columnType, encoded, column.columnName)
    }

    private fun parquetWriter(
        target: Path,
        schema: MessageType,
        conf: Configuration,
    ): ParquetWriter<Group> =
        ExampleParquetWriter.builder(LocalOutputFile(target))
            .withType(schema)
            .withConf(conf)
            .withCompressionCodec(CompressionCodecName.ZSTD)
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            .build()

    private fun buildMessageType(columns: List<DucklakeColumn>): MessageType {
        val fields = columns.map { col -> parquetField(col) }
        return MessageType("ducklake_inlined", fields)
    }

    private fun parquetField(col: DucklakeColumn): Type {
        val t = col.columnType.trim().lowercase(Locale.ROOT)
        val id = col.columnId.toInt()
        DECIMAL.matchEntire(t)?.let { m ->
            val precision = m.groupValues[1].toInt()
            val scale = m.groupValues[2].toInt()
            return Types.optional(PrimitiveTypeName.BINARY)
                .`as`(LogicalTypeAnnotation.decimalType(scale, precision))
                .id(id).named(col.columnName)
        }
        val builder = when (t) {
            "boolean" -> Types.optional(PrimitiveTypeName.BOOLEAN)
            "int8", "int16", "int32", "uint8", "uint16" -> Types.optional(PrimitiveTypeName.INT32)
            "int64", "uint32" -> Types.optional(PrimitiveTypeName.INT64)
            "float32" -> Types.optional(PrimitiveTypeName.FLOAT)
            "float64" -> Types.optional(PrimitiveTypeName.DOUBLE)
            "date" -> Types.optional(PrimitiveTypeName.INT32)
                .`as`(LogicalTypeAnnotation.dateType())
            // timestamptz is written zone-naive to match the DATETIMEV2 read mapping: the micros
            // are already UTC, and an isAdjustedToUtc file would hit the BE's DateTimeV2→TimeStampTz gap.
            "timestamp", "timestamp_s", "timestamp_ms", "timestamp_ns", "timestamptz" ->
                Types.optional(PrimitiveTypeName.INT64)
                    .`as`(LogicalTypeAnnotation.timestampType(false, LogicalTypeAnnotation.TimeUnit.MICROS))
            "varchar" -> Types.optional(PrimitiveTypeName.BINARY)
                .`as`(LogicalTypeAnnotation.stringType())
            "blob" -> Types.optional(PrimitiveTypeName.BINARY)
            else -> throw IllegalArgumentException(
                "inlined-data write: unsupported scalar DuckLake type '${col.columnType}'",
            )
        }
        return builder.id(id).named(col.columnName)
    }

    private fun appendValue(group: Group, index: Int, ducklakeType: String, value: Any?) {
        if (value == null) {
            return
        }
        val t = ducklakeType.trim().lowercase(Locale.ROOT)
        DECIMAL.matchEntire(t)?.let { m ->
            group.add(index, Binary.fromConstantByteArray(decimalUnscaledBytes(value, m.groupValues[2].toInt())))
            return
        }
        when (t) {
            "boolean" -> group.add(index, toBoolean(value))
            "int8", "int16", "int32", "uint8", "uint16" -> group.add(index, toLong(value).toInt())
            "int64", "uint32" -> group.add(index, toLong(value))
            "float32" -> group.add(index, toFloat(value))
            "float64" -> group.add(index, toDouble(value))
            "date" -> group.add(index, toEpochDay(value).toInt())
            "timestamp", "timestamp_s", "timestamp_ms", "timestamp_ns" ->
                group.add(index, toEpochMicros(value))
            "timestamptz" -> group.add(index, toEpochMicrosTz(value))
            "varchar" -> group.add(index, Binary.fromString(toStringValue(value)))
            "blob" -> group.add(index, Binary.fromConstantByteArray(toBytes(value)))
            else -> throw IllegalArgumentException(
                "inlined-data write: unsupported scalar DuckLake type '$ducklakeType'",
            )
        }
    }

    private fun toBoolean(value: Any): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> toStringValue(value).toBooleanStrict()
    }

    private fun toLong(value: Any): Long =
        if (value is Number) value.toLong() else toStringValue(value).trim().toLong()

    private fun toFloat(value: Any): Float =
        if (value is Number) value.toFloat() else normalizeNonFinite(toStringValue(value)).toFloat()

    private fun toDouble(value: Any): Double =
        if (value is Number) value.toDouble() else normalizeNonFinite(toStringValue(value)).toDouble()

    private fun toEpochDay(value: Any): Long = when (value) {
        is LocalDate -> value.toEpochDay()
        is java.sql.Date -> value.toLocalDate().toEpochDay()
        is Number -> value.toLong()
        else -> LocalDate.parse(toStringValue(value).trim()).toEpochDay()
    }

    private fun toEpochMicros(value: Any): Long = when (value) {
        is Number -> value.toLong()
        is java.sql.Timestamp -> instantToMicros(value.toInstant())
        is LocalDateTime -> localDateTimeToMicros(value)
        else -> localDateTimeToMicros(parseLocalDateTime(toStringValue(value)))
    }

    private fun toEpochMicrosTz(value: Any): Long = when (value) {
        is Number -> value.toLong()
        is java.sql.Timestamp -> instantToMicros(value.toInstant())
        is OffsetDateTime -> instantToMicros(value.toInstant())
        is Instant -> instantToMicros(value)
        is LocalDateTime -> instantToMicros(value.toInstant(ZoneOffset.UTC))
        else -> instantToMicros(parseInstant(toStringValue(value)))
    }

    private fun toBytes(value: Any): ByteArray = when (value) {
        is ByteArray -> value
        else -> decodeBlobText(toStringValue(value))
    }

    private fun decimalUnscaledBytes(value: Any, scale: Int): ByteArray {
        val decimal = when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal.valueOf(value.toDouble())
            else -> BigDecimal(toStringValue(value).trim())
        }.setScale(scale, RoundingMode.UNNECESSARY)
        return decimal.unscaledValue().toByteArray()
    }

    private fun instantToMicros(instant: Instant): Long =
        instant.epochSecond * MICROS_PER_SECOND + instant.nano / NANOS_PER_MICRO

    private fun localDateTimeToMicros(dt: LocalDateTime): Long =
        dt.toEpochSecond(ZoneOffset.UTC) * MICROS_PER_SECOND + dt.nano / NANOS_PER_MICRO

    private fun parseLocalDateTime(text: String): LocalDateTime =
        LocalDateTime.parse(text.trim().replace(' ', 'T'))

    private fun parseInstant(text: String): Instant {
        val s = text.trim()
        return runCatching { OffsetDateTime.parse(s.replace(' ', 'T')).toInstant() }
            .getOrElse { parseLocalDateTime(s).toInstant(ZoneOffset.UTC) }
    }

    private fun normalizeNonFinite(text: String): String = when (text.trim().lowercase(Locale.ROOT)) {
        "inf", "+inf", "infinity", "+infinity" -> "Infinity"
        "-inf", "-infinity" -> "-Infinity"
        "nan", "-nan" -> "NaN"
        else -> text.trim()
    }

    private fun toStringValue(value: Any): String = when (value) {
        is ByteArray -> String(value, Charsets.UTF_8)
        else -> value.toString()
    }

    /** DuckDB's `Blob::ToString` form: printable ASCII verbatim, everything else `\xNN`. */
    private fun decodeBlobText(text: String): ByteArray {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            if (isHexEscapeAt(text, i)) {
                out.add(text.substring(i + 2, i + 4).toInt(HEX_RADIX).toByte())
                i += 4
            } else {
                out.add(text[i].code.toByte())
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun isHexEscapeAt(text: String, i: Int): Boolean {
        if (i + 3 >= text.length) {
            return false
        }
        return text[i] == '\\' && text[i + 1] == 'x'
    }

    private val DECIMAL = Regex("""decimal\((\d+),\s*(\d+)\)""")
    private const val HEX_RADIX = 16
}
