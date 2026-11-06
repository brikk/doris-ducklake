package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakePartitionField
import dev.brikk.ducklake.catalog.DucklakePartitionSpec
import dev.brikk.ducklake.catalog.DucklakePartitionTransform
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema

/**
 * Builds the Iceberg `PartitionSpec` the write sink carries. The BE reports `partition_values`
 * positionally, so fields are added in `partitionKeyIndex` order, the same order
 * [DuckLakeIcebergCommitMapper] reads them back. Iceberg validates each transform against the source
 * column type at build time, so a malformed spec throws here rather than corrupting a write.
 */
internal object DuckLakeIcebergPartitionSpec {

    /** The BE only needs the `partition_specs_json` key to equal `partition_spec_id`; the DuckLake id rides the commit fragment. */
    const val SINK_SPEC_ID: Int = 0

    fun of(
        spec: DucklakePartitionSpec,
        schema: Schema,
        columnNameById: Map<Long, String>,
    ): PartitionSpec {
        val builder = PartitionSpec.builderFor(schema).withSpecId(SINK_SPEC_ID)
        for (field in spec.fields.sortedBy { it.partitionKeyIndex }) {
            val name = columnNameById[field.columnId]
                ?: throw IllegalArgumentException(
                    "partition column id ${field.columnId} is not a column of the table schema",
                )
            addField(builder, field, name)
        }
        return builder.build()
    }

    private fun addField(builder: PartitionSpec.Builder, field: DucklakePartitionField, name: String) {
        when (field.transform) {
            DucklakePartitionTransform.IDENTITY -> builder.identity(name)
            DucklakePartitionTransform.YEAR -> builder.year(name)
            DucklakePartitionTransform.MONTH -> builder.month(name)
            DucklakePartitionTransform.DAY -> builder.day(name)
            DucklakePartitionTransform.HOUR -> builder.hour(name)
            DucklakePartitionTransform.BUCKET -> builder.bucket(
                name,
                requireNotNull(field.arity) { "BUCKET partition field requires an arity" },
            )
        }
    }
}
