package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn
import org.apache.parquet.hadoop.metadata.ParquetMetadata
import org.apache.parquet.schema.LogicalTypeAnnotation
import org.apache.parquet.schema.PrimitiveType
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.apache.parquet.schema.Type
import java.time.LocalDate

/** No implicit zones, narrowing, nanosecond truncation or degraded string/binary types. */
internal object DuckLakeScalarFileTypes {
    private const val MICROS_DAY = 86_400_000_000L
    private val DATE_MIN = LocalDate.of(1, 1, 1).toEpochDay()
    private val DATE_MAX = LocalDate.of(9999, 12, 31).toEpochDay()
    private val DECIMAL = Regex("decimal\\((\\d+),\\s*(\\d+)\\)")

    fun check(column: DucklakeColumn, field: Type, footer: ParquetMetadata, budget: DuckLakeReadBudget) {
        check(field.isPrimitive) { "Nested physical field is not qualified" }
        val primitive = field.asPrimitiveType()
        val t = column.columnType.lowercase()
        val valid = when {
            t.startsWith("int") -> integer(primitive, t.removePrefix("int").toInt())
            t.startsWith("timestamp") -> timestamp(primitive)
            DECIMAL.matches(t) -> decimal(primitive, t)
            else -> simple(primitive, t)
        }
        if (!valid) throw DuckLakeUnsupportedRead("Unqualified physical type/evolution for ${column.columnName}: $primitive -> $t")
        if (t in setOf("date", "timestamp", "timestamp_s", "timestamp_ms", "int8", "int16")) checkDomain(primitive, t, footer, budget)
    }

    private fun integer(field: PrimitiveType, width: Int): Boolean {
        val physical = field.primitiveTypeName
        val logical = field.logicalTypeAnnotation
        val annotation = logical as? LogicalTypeAnnotation.IntLogicalTypeAnnotation
        val physicalFits = physical == PrimitiveTypeName.INT32 || (width == 64 && physical == PrimitiveTypeName.INT64)
        val logicalFits = logical == null || (annotation != null && annotation.isSigned && annotation.bitWidth <= width)
        return physicalFits && logicalFits
    }

    private fun timestamp(field: PrimitiveType): Boolean {
        val annotation = field.logicalTypeAnnotation as? LogicalTypeAnnotation.TimestampLogicalTypeAnnotation ?: return false
        val unitFits = annotation.unit in setOf(LogicalTypeAnnotation.TimeUnit.MILLIS, LogicalTypeAnnotation.TimeUnit.MICROS)
        return field.primitiveTypeName == PrimitiveTypeName.INT64 && !annotation.isAdjustedToUTC && unitFits
    }

    private fun decimal(field: PrimitiveType, type: String): Boolean {
        val target = DECIMAL.matchEntire(type)!!
        val annotation = field.logicalTypeAnnotation as? LogicalTypeAnnotation.DecimalLogicalTypeAnnotation ?: return false
        return annotation.precision <= target.groupValues[1].toInt() && annotation.scale == target.groupValues[2].toInt()
    }

    private fun simple(field: PrimitiveType, type: String): Boolean {
        val physical = field.primitiveTypeName
        val logical = field.logicalTypeAnnotation
        return when (type) {
            "boolean" -> physical == PrimitiveTypeName.BOOLEAN && logical == null
            "float32" -> physical == PrimitiveTypeName.FLOAT && logical == null
            "float64" -> physical in setOf(PrimitiveTypeName.FLOAT, PrimitiveTypeName.DOUBLE) && logical == null
            "varchar" -> physical == PrimitiveTypeName.BINARY && logical is LogicalTypeAnnotation.StringLogicalTypeAnnotation
            "blob" -> physical == PrimitiveTypeName.BINARY && logical == null
            "date" -> physical == PrimitiveTypeName.INT32 && logical is LogicalTypeAnnotation.DateLogicalTypeAnnotation
            else -> false
        }
    }

    private fun checkDomain(field: PrimitiveType, type: String, footer: ParquetMetadata, budget: DuckLakeReadBudget) {
        val domain = when (type) {
            "date" -> DATE_MIN..DATE_MAX
            "int8" -> -128L..127L
            "int16" -> -32768L..32767L
            else -> timeDomain(field)
        }
        for (group in footer.blocks) {
            budget.step(group.columns.size)
            val stats = group.columns.single { it.path.toDotString() == field.name }.statistics
            if (stats.isNumNullsSet && stats.numNulls == group.rowCount) continue
            check(stats.hasNonNullValue()) { "Temporal/narrow integer domain cannot be validated without physical bounds" }
            val min = (stats.genericGetMin() as Number).toLong()
            val max = (stats.genericGetMax() as Number).toLong()
            check(min <= max && min in domain && max in domain) { "Value outside lossless Doris domain (infinity/BCE/large year rejected)" }
        }
    }

    private fun timeDomain(field: PrimitiveType): LongRange {
        val logical = field.logicalTypeAnnotation as LogicalTypeAnnotation.TimestampLogicalTypeAnnotation
        val divisor = if (logical.unit == LogicalTypeAnnotation.TimeUnit.MILLIS) 1000L else 1L
        return (DATE_MIN * MICROS_DAY / divisor)..((DATE_MAX + 1) * MICROS_DAY / divisor - 1)
    }
}
