package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeColumn
import java.util.Base64
import java.util.Locale
import org.apache.doris.thrift.TColumnType
import org.apache.doris.thrift.TFileScanRangeParams
import org.apache.doris.thrift.TPrimitiveType
import org.apache.doris.thrift.schema.external.TArrayField
import org.apache.doris.thrift.schema.external.TField
import org.apache.doris.thrift.schema.external.TFieldPtr
import org.apache.doris.thrift.schema.external.TMapField
import org.apache.doris.thrift.schema.external.TNestedField
import org.apache.doris.thrift.schema.external.TSchema
import org.apache.doris.thrift.schema.external.TStructField
import org.apache.thrift.TDeserializer
import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TBinaryProtocol

/** Current native files only. A partial scalar dictionary is NOT a name fallback for omitted fields. */
internal fun encodeReadSchemaDictionary(
    catalog: DucklakeCatalog,
    handle: DuckLakeTableHandle,
    columns: List<DuckLakeColumnHandle>,
    nameMaps: Collection<Map<Long, String>>,
    hasMappings: Boolean,
): String? {
    val nested = columns.filter { it.columnType.typeName in setOf("STRUCT", "ARRAY", "MAP") }
    val scalar = DuckLakeSchemaDictionary.encode(columns, nameMaps)
    if (nested.isEmpty()) return scalar
    require(catalog !is DuckLakeQualifiedReadCatalog) { "Final-1.1 nested file scans are not qualified" }
    require(!hasMappings) { "Doris nested legacy-file mappings are not qualified" }
    require(handle.snapshotId == catalog.currentSnapshotId) { "Doris historical nested file scans are not qualified" }
    val raw = catalog.getAllColumnsWithParentage(handle.tableId, handle.snapshotId)
    require(raw.size <= 4096 && raw.map { it.columnId }.distinct().size == raw.size) { "Invalid/budget-exceeding nested column IDs" }
    val tree = NativeNestedFields(raw)
    val carrier = TFileScanRangeParams()
    if (scalar != null) TDeserializer(TBinaryProtocol.Factory()).deserialize(carrier, Base64.getDecoder().decode(scalar))
    val schema = carrier.historySchemaInfo?.singleOrNull() ?: TSchema().setSchemaId(-1).setRootField(TStructField().setFields(ArrayList()))
    val byId = raw.associateBy { it.columnId }
    nested.forEach { column ->
        val row = requireNotNull(byId[column.columnId]) { "Missing nested root ${column.columnId}" }
        require(row.parentColumn == null && row.columnName == column.columnName) { "Nested root identity mismatch" }
        val field = tree.field(row)
        require(field.type.type.name == column.columnType.typeName) { "Nested root type mismatch" }
        schema.rootField.addToFields(TFieldPtr().setFieldPtr(field))
    }
    carrier.setCurrentSchemaId(-1).setHistorySchemaInfo(listOf(schema))
    return Base64.getEncoder().encodeToString(TSerializer(TBinaryProtocol.Factory()).serialize(carrier))
}

private class NativeNestedFields(rows: List<DucklakeColumn>) {
    private val children = rows.groupBy { it.parentColumn }

    fun field(row: DucklakeColumn, depth: Int = 0): TField {
        require(depth <= 64) { "Nested field depth budget exceeded" }
        require(row.columnId > 0) { "Invalid nested field ID" }
        require(row.columnName.isNotEmpty()) { "Empty nested field name" }
        val nested = children[row.columnId].orEmpty().sortedBy { it.columnOrder }
        val type = when (row.columnType.lowercase(Locale.ROOT)) {
            "struct" -> TPrimitiveType.STRUCT
            "list" -> TPrimitiveType.ARRAY
            "map" -> TPrimitiveType.MAP
            else -> TPrimitiveType.STRING // Scalar tag is only a discriminator on this field-ID path.
        }
        val field = TField().setId(Math.toIntExact(row.columnId)).setName(row.columnName.lowercase(Locale.ROOT))
            .setIsOptional(row.nullsAllowed).setType(TColumnType().setType(type))
        val pointers = nested.map { TFieldPtr().setFieldPtr(this.field(it, depth + 1)) }
        when (type) {
            TPrimitiveType.STRUCT -> {
                require(nested.isNotEmpty() && nested.map { it.columnName.lowercase(Locale.ROOT) }.distinct().size == nested.size) {
                    "Doris nested STRUCT field names are empty or ambiguous"
                }
                field.setNestedField(TNestedField().apply { structField = TStructField().setFields(pointers) })
            }
            TPrimitiveType.ARRAY -> {
                require(nested.map { it.columnName } == listOf("element")) { "Invalid nested LIST arity/identity" }
                field.setNestedField(TNestedField().apply { arrayField = TArrayField().setItemField(pointers.single()) })
            }
            TPrimitiveType.MAP -> {
                require(nested.map { it.columnName } == listOf("key", "value")) { "Invalid nested MAP arity/identity" }
                field.setNestedField(TNestedField().apply { mapField = TMapField().setKeyField(pointers[0]).setValueField(pointers[1]) })
            }
            else -> require(nested.isEmpty()) { "Scalar field unexpectedly has children" }
        }
        return field
    }
}
