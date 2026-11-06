package dev.brikk.ducklake.doris.plugin

import org.apache.doris.thrift.TFileFormatType
import org.apache.doris.thrift.TFileRangeDesc
import org.apache.doris.thrift.TIcebergDeleteFileDesc
import org.apache.doris.thrift.TIcebergFileDesc
import org.apache.doris.thrift.TTableFormatFileDesc
import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TBinaryProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Locks the wire shape of [DuckLakeScanRange.populateRangeParams] to what the BE's Iceberg
 * reader consumes: it branches on `table_format_type == "iceberg"` and reads `iceberg_params`
 * unchanged. The discriminator is parameterised in the goldens so a future switch to a
 * `"ducklake"` table format type is a one-string change.
 */
internal class DuckLakeScanRangeThriftParityTest {

    @Test
    @Throws(Exception::class)
    fun formatDescMatchesIcebergGoldenForDataOnlyRange() {
        val actual = populatedFormatDesc(
            DuckLakeScanRange(PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT),
        )
        val golden = goldenFormatDesc(PATH, "iceberg")

        assertThat(serialize(actual)).isEqualTo(serialize(golden))
    }

    @Test
    @Throws(Exception::class)
    fun rangeDescStaysUntouchedForUnpartitionedRange() {
        // Guards against setting empty columns_from_path lists when there is nothing to fill.
        val range = DuckLakeScanRange(PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT)

        val formatDesc = TTableFormatFileDesc()
        val rangeDesc = TFileRangeDesc()
        range.populateRangeParams(formatDesc, rangeDesc)

        assertThat(rangeDesc.isSetColumnsFromPath).isFalse()
        assertThat(rangeDesc.isSetColumnsFromPathKeys).isFalse()
        assertThat(rangeDesc.isSetColumnsFromPathIsNull).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun rangeDescCarriesColumnsFromPathForHiveLayoutRange() {
        // Hive-layout add_files files carry partition columns in the path, not the
        // parquet body, so the BE's constant-fill triple (keys / values / is-null) is
        // emitted. NULL hive partitions are rejected FE-side, so is-null is always false.
        val columnsFromPath = LinkedHashMap<String, String>()
        columnsFromPath["part_key"] = "1"
        columnsFromPath["region"] = "us east" // decoded value with a space
        val range = DuckLakeScanRange.Builder()
            .path(PATH).start(START).length(LENGTH).fileSize(FILE_SIZE).fileFormat(FILE_FORMAT)
            .columnsFromPath(columnsFromPath)
            .build()

        val formatDesc = TTableFormatFileDesc()
        val rangeDesc = TFileRangeDesc()
        range.populateRangeParams(formatDesc, rangeDesc)

        assertThat(rangeDesc.columnsFromPathKeys)
            .containsExactly("part_key", "region")
        assertThat(rangeDesc.columnsFromPath)
            .containsExactly("1", "us east")
        assertThat(rangeDesc.columnsFromPathIsNull)
            .containsExactly(false, false)
        // Data files still dispatch to the iceberg reader; columns_from_path is orthogonal.
        assertThat(formatDesc.isSetIcebergParams).isTrue()
    }

    @Test
    fun tableFormatTypeIsIcebergUntilOptionB() {
        // Changing DuckLakeScanRange.TABLE_FORMAT_TYPE without a matching BE change
        // breaks reader dispatch at deploy time; this assertion makes that explicit.
        assertThat(
            DuckLakeScanRange(PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT)
                .tableFormatType,
        ).isEqualTo("iceberg")
    }

    @Test
    @Throws(Exception::class)
    fun icebergParamsCarryFormatVersion2AndEmptyDeleteFiles() {
        // Field-level checks give better diagnostics than the byte-identity test.
        val formatDesc = populatedFormatDesc(
            DuckLakeScanRange(PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT),
        )

        assertThat(formatDesc.isSetIcebergParams).isTrue()
        val iceberg = formatDesc.icebergParams
        assertThat(iceberg.formatVersion).isEqualTo(2)
        assertThat(iceberg.originalFilePath).isEqualTo(PATH)
        assertThat(iceberg.isSetDeleteFiles).isTrue()
        assertThat(iceberg.deleteFiles).isEmpty()
        // Left unset so the BE reader picks its defaults.
        assertThat(iceberg.isSetPartitionSpecId).isFalse()
        assertThat(iceberg.isSetPartitionDataJson).isFalse()
        assertThat(iceberg.isSetContent).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun formatDescMatchesIcebergGoldenForPositionDeleteRange() {
        val range = DuckLakeScanRange(
            PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT,
            listOf(DuckLakePositionDelete(DELETE_PATH, FILE_FORMAT)),
        )

        val actual = populatedFormatDesc(range)
        val golden = goldenFormatDescWithPositionDelete(PATH, "iceberg", DELETE_PATH)

        assertThat(serialize(actual)).isEqualTo(serialize(golden))
    }

    @Test
    @Throws(Exception::class)
    fun icebergParamsCarryPositionDeleteFile() {
        // Position bounds stay unset because DuckLake's catalog does not track them;
        // the BE then scans the full delete file, which is correct but unpruned.
        val range = DuckLakeScanRange(
            PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT,
            listOf(DuckLakePositionDelete(DELETE_PATH, FILE_FORMAT)),
        )

        val formatDesc = populatedFormatDesc(range)
        val iceberg = formatDesc.icebergParams

        assertThat(iceberg.deleteFiles).hasSize(1)
        val delete = iceberg.deleteFiles[0]
        assertThat(delete.path).isEqualTo(DELETE_PATH)
        assertThat(delete.fileFormat).isEqualTo(TFileFormatType.FORMAT_PARQUET)
        // DuckLake has no equality deletes, so content is always POSITION_DELETE (1).
        assertThat(delete.content)
            .isEqualTo(DuckLakeScanRange.CONTENT_POSITION_DELETE)
            .isEqualTo(1)
        assertThat(delete.isSetPositionLowerBound).isFalse()
        assertThat(delete.isSetPositionUpperBound).isFalse()
        assertThat(delete.isSetFieldIds).isFalse()
    }

    @Test
    fun positionDeletesAccessorIsImmutable() {
        val pd = DuckLakePositionDelete(DELETE_PATH, FILE_FORMAT)
        val range = DuckLakeScanRange(
            PATH, START, LENGTH, FILE_SIZE, FILE_FORMAT, listOf(pd),
        )

        val deletes = range.positionDeletes()
        assertThat(deletes).containsExactly(pd)
        // Kotlin's List has no add(), so cast to MutableList to exercise the
        // Java-visible mutation a caller could attempt.
        Assertions.assertThrows(
            UnsupportedOperationException::class.java,
        ) { (deletes as MutableList<DuckLakePositionDelete>).add(DuckLakePositionDelete("x", "parquet")) }
    }

    companion object {
        private const val PATH = "s3://warehouse/sales/orders/data_0.parquet"
        private const val DELETE_PATH = "s3://warehouse/sales/orders/delete_0.parquet"
        private const val START = 0L
        private const val LENGTH = 4096L
        private const val FILE_SIZE = 4096L
        private const val FILE_FORMAT = "parquet"

        private fun populatedFormatDesc(range: DuckLakeScanRange): TTableFormatFileDesc {
            val formatDesc = TTableFormatFileDesc()
            val rangeDesc = TFileRangeDesc()
            range.populateRangeParams(formatDesc, rangeDesc)
            // The engine, not the range, fills table_format_type; set it here so the golden matches.
            formatDesc.setTableFormatType(range.tableFormatType)
            return formatDesc
        }

        /** Hand-built mirror of `IcebergScanRange.populateRangeParams` for a no-deletes v2 data file. */
        private fun goldenFormatDesc(path: String, discriminator: String): TTableFormatFileDesc {
            val formatDesc = TTableFormatFileDesc()
            formatDesc.setTableFormatType(discriminator)

            val iceberg = TIcebergFileDesc()
            iceberg.setFormatVersion(2)
            iceberg.setOriginalFilePath(path)
            iceberg.setDeleteFiles(ArrayList())

            formatDesc.setIcebergParams(iceberg)
            return formatDesc
        }

        /** Hand-built golden for a v2 data file carrying one position-delete file with no bounds. */
        private fun goldenFormatDescWithPositionDelete(
            path: String,
            discriminator: String,
            deletePath: String,
        ): TTableFormatFileDesc {
            val formatDesc = TTableFormatFileDesc()
            formatDesc.setTableFormatType(discriminator)

            val iceberg = TIcebergFileDesc()
            iceberg.setFormatVersion(2)
            iceberg.setOriginalFilePath(path)

            val deletes = ArrayList<TIcebergDeleteFileDesc>()
            val delete = TIcebergDeleteFileDesc()
            delete.setPath(deletePath)
            delete.setFileFormat(TFileFormatType.FORMAT_PARQUET)
            delete.setContent(1) // POSITION_DELETE
            deletes.add(delete)
            iceberg.setDeleteFiles(deletes)

            formatDesc.setIcebergParams(iceberg)
            return formatDesc
        }

        @Throws(Exception::class)
        private fun serialize(desc: TTableFormatFileDesc): ByteArray {
            // Explicit protocol so byte equality does not depend on the thrift default.
            return TSerializer(TBinaryProtocol.Factory()).serialize(desc)
        }
    }
}
