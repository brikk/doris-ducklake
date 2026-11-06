package dev.brikk.ducklake.doris.plugin

import org.apache.doris.connector.spi.ConnectorType

/**
 * Doris CREATE TABLE column type → DuckLake `column_type` string; the inverse of [DuckLakeTypeMapping]
 * on the non-degraded core. Only unambiguous scalars map; nested, HLL/BITMAP/AGG_STATE, JSON/VARIANT
 * and IPV4/IPV6 throw, because a guessed type would silently corrupt the table definition.
 */
internal object DuckLakeCreateTableMapper {

    private val SCALAR = mapOf(
        "BOOLEAN" to "boolean",
        "TINYINT" to "int8",
        "SMALLINT" to "int16",
        "INT" to "int32", "INTEGER" to "int32",
        "BIGINT" to "int64",
        "LARGEINT" to "int128",
        "FLOAT" to "float32",
        "DOUBLE" to "float64",
        "DATE" to "date", "DATEV2" to "date",
        "TIMESTAMPTZ" to "timestamptz", "TIMESTAMPTZV2" to "timestamptz",
        "CHAR" to "varchar", "VARCHAR" to "varchar", "STRING" to "varchar", "TEXT" to "varchar",
        "VARBINARY" to "blob", "BINARY" to "blob",
    )

    fun toDucklakeType(type: ConnectorType): String {
        val name = type.typeName.trim().uppercase()
        SCALAR[name]?.let { return it }
        return when (name) {
            "DECIMAL", "DECIMALV2", "DECIMALV3" -> "decimal(${type.precision},${type.scale})"
            // DuckLakeTypeMapping carries the fractional-second resolution in the precision slot.
            "DATETIME", "DATETIMEV2" -> datetimeType(type.precision)
            else -> throw IllegalArgumentException(
                "unsupported Doris type for DuckLake CREATE TABLE v1: ${type.typeName} ($name)",
            )
        }
    }

    private fun datetimeType(precision: Int): String = when (precision) {
        0 -> "timestamp_s"
        3 -> "timestamp_ms"
        9 -> "timestamp_ns"
        else -> "timestamp"
    }
}
