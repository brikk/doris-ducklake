package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakePartitionTransform
import dev.brikk.ducklake.catalog.PartitionFieldSpec
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.ddl.ConnectorBucketSpec
import org.apache.doris.connector.spi.ddl.ConnectorPartitionField
import org.apache.doris.connector.spi.ddl.ConnectorPartitionSpec

/**
 * Maps a `CREATE TABLE`'s partition and bucket clauses to DuckLake [PartitionFieldSpec]s. The catalog
 * assigns `partition_key_index` by list order, so the returned order matters. DuckLake only has the
 * Iceberg transform family, so anything else is rejected rather than dropped.
 *
 * [ConnectorPartitionSpec] is mapped by each field's transform regardless of its `style`: Doris's
 * grammar forces an external partitioned table to be written `PARTITION BY LIST (transform(col)) ()`,
 * so the real transform lives in the fields and the style keyword carries nothing.
 *
 * [ConnectorBucketSpec] (`DISTRIBUTED BY`) is rejected unless its algorithm is murmur3: Doris's
 * `doris_default` is CRC32, and treating it as DuckLake bucketing would bucket files by the wrong hash.
 */
internal object DuckLakeCreatePartitionMapper {

    private val MURMUR3_BUCKET_ALGORITHMS = setOf("murmur3", "iceberg_bucket")

    /** `PARTITION BY` fields first, then accepted `DISTRIBUTED BY` columns; null for an unpartitioned table. */
    fun toPartitionFields(
        partitionSpec: ConnectorPartitionSpec?,
        bucketSpec: ConnectorBucketSpec?,
    ): List<PartitionFieldSpec>? {
        val fields = ArrayList<PartitionFieldSpec>()
        if (partitionSpec != null) {
            fields.addAll(fromPartitionSpec(partitionSpec))
        }
        if (bucketSpec != null) {
            fields.addAll(fromBucketSpec(bucketSpec))
        }
        return fields.ifEmpty { null }
    }

    private fun fromPartitionSpec(spec: ConnectorPartitionSpec): List<PartitionFieldSpec> =
        spec.fields.map { toFieldSpec(it) }

    private fun toFieldSpec(field: ConnectorPartitionField): PartitionFieldSpec {
        val column = field.columnName
        return when (val transform = field.transform.lowercase()) {
            "identity" -> PartitionFieldSpec(column, DucklakePartitionTransform.IDENTITY)
            "year" -> PartitionFieldSpec(column, DucklakePartitionTransform.YEAR)
            "month" -> PartitionFieldSpec(column, DucklakePartitionTransform.MONTH)
            "day" -> PartitionFieldSpec(column, DucklakePartitionTransform.DAY)
            "hour" -> PartitionFieldSpec(column, DucklakePartitionTransform.HOUR)
            "bucket" -> PartitionFieldSpec(column, DucklakePartitionTransform.BUCKET, bucketArity(field))
            else -> throw DorisConnectorException(
                "partition transform '$transform' on column '$column' is not supported by DuckLake " +
                    "(supported: identity, year, month, day, hour, bucket)",
            )
        }
    }

    /** The `N` in `bucket(N, col)`; the FE puts it first in `transformArgs`. */
    private fun bucketArity(field: ConnectorPartitionField): Int =
        field.transformArgs.firstOrNull()
            ?: throw DorisConnectorException(
                "bucket(${field.columnName}) requires a bucket count, e.g. bucket(16, ${field.columnName})",
            )

    private fun fromBucketSpec(spec: ConnectorBucketSpec): List<PartitionFieldSpec> {
        if (spec.columns.isEmpty()) {
            return emptyList()
        }
        val algorithm = spec.algorithm.lowercase()
        if (algorithm !in MURMUR3_BUCKET_ALGORITHMS) {
            throw DorisConnectorException(
                "DISTRIBUTED BY uses algorithm '${spec.algorithm}', which is Doris-native distribution, " +
                    "not DuckLake bucketing — DuckLake buckets by Iceberg murmur3. " +
                    "Use PARTITION BY LIST (bucket(N, col)) () instead",
            )
        }
        if (spec.numBuckets <= 0) {
            throw DorisConnectorException("bucket count must be positive, got ${spec.numBuckets}")
        }
        return spec.columns.map { column ->
            PartitionFieldSpec(column, DucklakePartitionTransform.BUCKET, spec.numBuckets)
        }
    }
}
