package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeTypeSignature
import dev.brikk.ducklake.catalog.InlinedTypeNode
import org.apache.doris.connector.spi.ConnectorType

/** Published candidate only: parse the catalog grammar without normalizing source field names. */
internal object DuckLakeTypeMapping {
    private const val MICROS_SCALE = 6
    private const val VARBINARY_DEFAULT_LEN = 65535
    private const val UUID_BYTE_LEN = 16
    private val decimalPattern = Regex("decimal\\((\\d+),(\\d+)\\)")

    fun fromDucklakeType(ducklakeType: String?, enableTimestampTz: Boolean = false): ConnectorType {
        requireNotNull(ducklakeType) { "ducklakeType is null" }
        // Historical scalar alias, not a composite grammar: retain the existing spatial mapping.
        if (ducklakeType.trim().equals("linestring z", ignoreCase = true)) return scalar("linestring_z", enableTimestampTz)
        return convert(DucklakeTypeSignature.parse(ducklakeType), enableTimestampTz)
    }

    private fun convert(node: InlinedTypeNode, enableTimestampTz: Boolean): ConnectorType = when (node.type) {
        "list" -> ConnectorType.arrayOf(convert(node.children.single(), enableTimestampTz))
        "map" -> ConnectorType.mapOf(convert(node.children[0], enableTimestampTz), convert(node.children[1], enableTimestampTz))
        "struct" -> {
            val names = node.children.map { it.name }
            // Doris StructType is case-insensitive. Refuse collisions instead of dropping/renaming fields.
            require(names.none { it.isEmpty() } && names.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == names.size) {
                "Doris STRUCT requires nonempty, case-insensitively unique field names: $names"
            }
            ConnectorType.structOf(names, node.children.map { convert(it, enableTimestampTz) })
        }
        else -> scalar(node.type, enableTimestampTz)
    }

    // Same scalar behavior and explicit degradations as the released mapper; no new type opt-ins.
    private fun scalar(type: String, enableTimestampTz: Boolean): ConnectorType {
        if (type == "timestamptz") return ConnectorType.of(if (enableTimestampTz) "TIMESTAMPTZ" else "DATETIMEV2", MICROS_SCALE, 0)
        scalarTypes[type]?.let { return it }
        val decimal = decimalPattern.matchEntire(type)
        requireNotNull(decimal) { "Unsupported DuckLake type: $type" }
        return ConnectorType.of("DECIMALV3", decimal.groupValues[1].toInt(), decimal.groupValues[2].toInt())
    }

    private val scalarTypes = mapOf(
        "boolean" to ConnectorType.of("BOOLEAN"),
        "int8" to ConnectorType.of("TINYINT"), "int16" to ConnectorType.of("SMALLINT"),
        "int32" to ConnectorType.of("INT"), "int64" to ConnectorType.of("BIGINT"),
        "uint8" to ConnectorType.of("SMALLINT"), "uint16" to ConnectorType.of("INT"), "uint32" to ConnectorType.of("BIGINT"),
        "uint64" to ConnectorType.of("DECIMALV3", 20, 0), "int128" to ConnectorType.of("DECIMALV3", 38, 0),
        "uint128" to ConnectorType.of("STRING"), "float32" to ConnectorType.of("FLOAT"), "float64" to ConnectorType.of("DOUBLE"),
        "date" to ConnectorType.of("DATEV2"), "timestamp" to ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0),
        "timestamp_ns" to ConnectorType.of("DATETIMEV2", MICROS_SCALE, 0), "timestamp_s" to ConnectorType.of("DATETIMEV2", 0, 0),
        "timestamp_ms" to ConnectorType.of("DATETIMEV2", 3, 0), "blob" to ConnectorType.of("VARBINARY", VARBINARY_DEFAULT_LEN, 0),
        "uuid" to ConnectorType.of("VARBINARY", UUID_BYTE_LEN, 0),
    ) + listOf("time", "timetz", "varchar", "json", "variant", "interval").associateWith { ConnectorType.of("STRING") } +
        listOf("geometry", "point", "linestring", "linestring_z", "polygon", "multipoint", "multilinestring", "multipolygon",
            "geometrycollection").associateWith { ConnectorType.of("VARBINARY", VARBINARY_DEFAULT_LEN, 0) }
}
