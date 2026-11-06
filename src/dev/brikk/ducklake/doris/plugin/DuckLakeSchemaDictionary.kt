package dev.brikk.ducklake.doris.plugin

import java.util.Base64
import java.util.Locale

import org.apache.doris.thrift.TColumnType
import org.apache.doris.thrift.TFileScanRangeParams
import org.apache.doris.thrift.TPrimitiveType
import org.apache.doris.thrift.schema.external.TField
import org.apache.doris.thrift.schema.external.TFieldPtr
import org.apache.doris.thrift.schema.external.TSchema
import org.apache.doris.thrift.schema.external.TStructField
import org.apache.thrift.TDeserializer
import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TBinaryProtocol

/**
 * The schema dictionary (`current_schema_id` + `history_schema_info`) that makes the BE match file
 * and table columns by field id instead of by name. Name matching silently reads NULL for a renamed
 * column; DuckLake writes `field_id == column_id` into every file, so id matching just works.
 *
 * Files registered through `ducklake_add_data_files` may lack embedded ids and use different column
 * names; their `ducklake_name_mapping` rows (`target_field_id → source_name`) are carried as each
 * field's `name_mapping` so the BE can fall back to binding by name for those files.
 *
 * Scalar columns only. The BE treats a supplied dictionary as the complete field set and fills
 * omitted roots with NULL, and a container field without its full subtree can crash the native
 * reader, so [encodeReadSchemaDictionary] appends complete nested trees on top of this.
 */
internal object DuckLakeSchemaDictionary {

    private const val CURRENT_SCHEMA_ID: Long = -1L

    /** Null when there are no scalar columns to describe (count-only scans). */
    fun encode(
        columns: List<DuckLakeColumnHandle>,
        nameMaps: Collection<Map<Long, String>>,
    ): String? {
        val scalarColumns = columns.filter { isScalar(it.columnType.typeName) }
        if (scalarColumns.isEmpty()) {
            return null
        }
        val alternateNamesByFieldId = safeAlternateNames(nameMaps)

        val root = TStructField()
        for (col in scalarColumns) {
            root.addToFields(fieldPtr(buildField(col, alternateNamesByFieldId[col.columnId])))
        }
        val schema = TSchema()
            .setSchemaId(CURRENT_SCHEMA_ID)
            .setRootField(root)
        val serializer = TSerializer(TBinaryProtocol.Factory())
        // Same carrier struct the BE reads the fields off, as IcebergSchemaUtils does.
        val carrier = TFileScanRangeParams()
            .setCurrentSchemaId(CURRENT_SCHEMA_ID)
        carrier.addToHistorySchemaInfo(schema)
        return Base64.getEncoder().encodeToString(serializer.serialize(carrier))
    }

    /** A decode failure propagates: dropping the dictionary would bring back wrong rows on renamed columns. */
    fun apply(params: TFileScanRangeParams, encoded: String?) {
        if (encoded.isNullOrEmpty()) {
            return
        }
        val carrier = TFileScanRangeParams()
        TDeserializer(TBinaryProtocol.Factory()).deserialize(carrier, Base64.getDecoder().decode(encoded))
        if (carrier.isSetCurrentSchemaId) {
            params.currentSchemaId = carrier.currentSchemaId
        }
        if (carrier.isSetHistorySchemaInfo) {
            params.historySchemaInfo = carrier.historySchemaInfo
        }
    }

    /**
     * The dictionary is table-level while each file carries its own mapping, so a source name that
     * maps to different field ids across files (a column dropped and re-added under the same name)
     * is ambiguous: attached to the new id it would bind an old file's column onto the new one.
     * Such names are dropped entirely; the field then binds by embedded id or exact table name,
     * and old rows correctly read NULL for the new column.
     */
    private fun safeAlternateNames(nameMaps: Collection<Map<Long, String>>): Map<Long, List<String>> {
        val fieldIdsByName = HashMap<String, MutableSet<Long>>()
        for (map in nameMaps) {
            for ((fieldId, sourceName) in map) {
                fieldIdsByName.getOrPut(sourceName.lowercase(Locale.ROOT)) { HashSet() }.add(fieldId)
            }
        }
        val result = LinkedHashMap<Long, MutableList<String>>()
        for (map in nameMaps) {
            for ((fieldId, sourceName) in map) {
                val ambiguous = (fieldIdsByName[sourceName.lowercase(Locale.ROOT)]?.size ?: 0) > 1
                if (ambiguous) {
                    continue
                }
                result.getOrPut(fieldId) { ArrayList() }.let {
                    if (sourceName !in it) it.add(sourceName)
                }
            }
        }
        return result
    }

    private fun fieldPtr(field: TField): TFieldPtr = TFieldPtr().setFieldPtr(field)

    /** The BE only uses `type.type` as a nested-vs-scalar discriminator on the field-id path, so STRING suffices. */
    private fun buildField(col: DuckLakeColumnHandle, alternateNames: List<String>?): TField {
        val field = TField()
            .setId(col.columnId.toInt())
            .setName(col.columnName.lowercase(Locale.ROOT))
            .setIsOptional(true)
            .setType(TColumnType().setType(TPrimitiveType.STRING))
        if (!alternateNames.isNullOrEmpty()) {
            field.nameMapping = ArrayList(alternateNames)
        }
        return field
    }

    private fun isScalar(typeName: String): Boolean =
        when (typeName.uppercase(Locale.ROOT)) {
            "ARRAY", "MAP", "STRUCT" -> false
            else -> true
        }
}
