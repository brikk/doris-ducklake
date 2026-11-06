package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakePartitionTransform
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.ddl.ConnectorBucketSpec
import org.apache.doris.connector.spi.ddl.ConnectorPartitionField
import org.apache.doris.connector.spi.ddl.ConnectorPartitionSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Covers the SPI partition/bucket clause to DuckLake partition field mapping without a catalog;
 * the catalog round-trip lives in [DuckLakeDdlTest].
 */
internal class DuckLakeCreatePartitionMapperTest {

    private fun transformSpec(vararg fields: ConnectorPartitionField) =
        ConnectorPartitionSpec(ConnectorPartitionSpec.Style.TRANSFORM, fields.toList(), false)

    @Test
    fun unpartitionedYieldsNull() {
        assertThat(DuckLakeCreatePartitionMapper.toPartitionFields(null, null)).isNull()
        // An empty bucket spec (no columns) contributes nothing.
        assertThat(
            DuckLakeCreatePartitionMapper.toPartitionFields(null, ConnectorBucketSpec(emptyList(), 0, "doris_default")),
        ).isNull()
    }

    @Test
    fun mapsEveryIcebergTransform() {
        val fields = DuckLakeCreatePartitionMapper.toPartitionFields(
            transformSpec(
                ConnectorPartitionField("a", "identity", emptyList()),
                ConnectorPartitionField("b", "year", emptyList()),
                ConnectorPartitionField("b", "month", emptyList()),
                ConnectorPartitionField("b", "day", emptyList()),
                ConnectorPartitionField("b", "hour", emptyList()),
                ConnectorPartitionField("a", "bucket", listOf(16)),
            ),
            null,
        )!!
        assertThat(fields.map { it.transform }).containsExactly(
            DucklakePartitionTransform.IDENTITY,
            DucklakePartitionTransform.YEAR,
            DucklakePartitionTransform.MONTH,
            DucklakePartitionTransform.DAY,
            DucklakePartitionTransform.HOUR,
            DucklakePartitionTransform.BUCKET,
        )
        assertThat(fields.last().arity).isEqualTo(16)
    }

    @Test
    fun acceptsExplicitMurmur3BucketSpec() {
        // Should a future FE pass an explicit murmur3 / iceberg_bucket DISTRIBUTED BY, honor it.
        for (algorithm in listOf("murmur3", "ICEBERG_BUCKET")) {
            val fields = DuckLakeCreatePartitionMapper.toPartitionFields(
                null,
                ConnectorBucketSpec(listOf("name"), 8, algorithm),
            )!!
            assertThat(fields).singleElement().satisfies({
                assertThat(it.transform).isEqualTo(DucklakePartitionTransform.BUCKET)
                assertThat(it.arity).isEqualTo(8)
                assertThat(it.columnName).isEqualTo("name")
            })
        }
    }

    @Test
    fun rejectsDorisDistribution() {
        assertThatThrownBy {
            DuckLakeCreatePartitionMapper.toPartitionFields(null, ConnectorBucketSpec(listOf("name"), 4, "doris_default"))
        }.isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("DuckLake bucketing")
    }

    @Test
    fun rejectsBucketTransformWithoutArity() {
        assertThatThrownBy {
            DuckLakeCreatePartitionMapper.toPartitionFields(transformSpec(ConnectorPartitionField("a", "bucket", emptyList())), null)
        }.isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("bucket count")
    }

    @Test
    fun rejectsUnknownTransform() {
        assertThatThrownBy {
            DuckLakeCreatePartitionMapper.toPartitionFields(transformSpec(ConnectorPartitionField("a", "truncate", listOf(10))), null)
        }.isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("truncate")
    }

    @Test
    fun mapsTransformFieldsUnderListOrRangeStyle() {
        // Doris stamps iceberg-style partitioning with the grammar keyword (LIST/RANGE) rather
        // than Style.TRANSFORM, carrying the real transform per field, so the mapper ignores style.
        for (style in listOf(ConnectorPartitionSpec.Style.LIST, ConnectorPartitionSpec.Style.RANGE)) {
            val fields = DuckLakeCreatePartitionMapper.toPartitionFields(
                ConnectorPartitionSpec(
                    style,
                    listOf(
                        ConnectorPartitionField("name", "bucket", listOf(4)),
                        ConnectorPartitionField("d", "day", emptyList()),
                    ),
                    false,
                ),
                null,
            )!!
            assertThat(fields.map { it.transform }).containsExactly(
                DucklakePartitionTransform.BUCKET,
                DucklakePartitionTransform.DAY,
            )
            assertThat(fields.first().arity).isEqualTo(4)
        }
    }
}
