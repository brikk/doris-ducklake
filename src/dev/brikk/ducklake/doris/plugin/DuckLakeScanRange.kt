package dev.brikk.ducklake.doris.plugin

import java.io.Serializable
import java.util.Locale
import java.util.Optional
import org.apache.doris.connector.spi.scan.ConnectorScanRange
import org.apache.doris.thrift.TFileFormatType
import org.apache.doris.thrift.TFileRangeDesc
import org.apache.doris.thrift.TIcebergDeleteFileDesc
import org.apache.doris.thrift.TIcebergFileDesc
import org.apache.doris.thrift.TTableFormatFileDesc

/**
 * One data file as the BE sees it. Emitted as `table_format_type = "iceberg"` with the file shape in
 * `iceberg_params`, so the BE dispatches its existing Iceberg parquet reader: DuckLake's parquet
 * files already carry field ids, which is what that reader keys on. Position deletes travel as
 * `iceberg_params.delete_files`.
 *
 * The thrift bytes of a normal range are pinned by `DuckLakeScanRangeThriftParityTest`; the
 * FE-plan-level fields ([partitionBearing], [partitionValues], [pushDownRowCount]) and the
 * hive-layout [columnsFromPath] fill leave them unchanged unless set.
 */
internal class DuckLakeScanRange private constructor(builder: Builder) :
    ConnectorScanRange, Serializable {

    private val path: String = requireNotNull(builder.path) { "path" }
    private val start: Long = builder.start
    private val length: Long = builder.length
    private val fileSize: Long = builder.fileSize
    private val fileFormat: String = requireNotNull(builder.fileFormat) { "fileFormat" }
    private val positionDeletes: List<DuckLakePositionDelete> = builder.positionDeletes.toList()
    private val partitionBearing: Boolean = builder.partitionBearing
    private val partitionValues: Map<String, String> = builder.partitionValues.toMap()
    private val pushDownRowCount: Long = builder.pushDownRowCount
    private val columnsFromPath: Map<String, String> = LinkedHashMap(builder.columnsFromPath)

    constructor(path: String, start: Long, length: Long, fileSize: Long, fileFormat: String) :
        this(path, start, length, fileSize, fileFormat, listOf())

    constructor(
        path: String,
        start: Long,
        length: Long,
        fileSize: Long,
        fileFormat: String,
        positionDeletes: List<DuckLakePositionDelete>,
    ) : this(
        Builder()
            .path(path)
            .start(start)
            .length(length)
            .fileSize(fileSize)
            .fileFormat(fileFormat)
            .positionDeletes(positionDeletes),
    )

    @JvmName("positionDeletes")
    fun positionDeletes(): List<DuckLakePositionDelete> = positionDeletes

    override fun getPath(): Optional<String> = Optional.of(path)

    override fun getStart(): Long = start

    override fun getLength(): Long = length

    override fun getFileSize(): Long = fileSize

    override fun getFileFormat(): String = fileFormat

    override fun getProperties(): Map<String, String> = emptyMap()

    override fun getTableFormatType(): String = TABLE_FORMAT_TYPE

    /**
     * Identity partition values keyed by lowercased column name, consumed FE-side only. Native
     * DuckLake files carry partition columns in the parquet body, so nothing goes to thrift here;
     * hive-layout `add_files` files are the exception, handled by [columnsFromPath].
     */
    override fun getPartitionValues(): Map<String, String> = partitionValues

    /**
     * True for every range of a table with an active spec, even a file that recorded no values.
     * The engine must map an empty values map to an empty list, not null: null makes
     * `FileQueryScanNode` parse `key=value` out of the path, which DuckLake's layout doesn't have.
     */
    override fun isPartitionBearing(): Boolean = partitionBearing

    /**
     * The metadata COUNT(*) total on the single collapsed count range; `-1` on every other range.
     * The sentinel matters: a table whose count isn't exactly servable must emit no count range so
     * the BE counts by reading.
     */
    override fun getPushDownRowCount(): Long = pushDownRowCount

    override fun populateRangeParams(formatDesc: TTableFormatFileDesc, rangeDesc: TFileRangeDesc) {
        val fileDesc = TIcebergFileDesc()
        fileDesc.formatVersion = ICEBERG_FORMAT_VERSION
        fileDesc.originalFilePath = path
        // Always set, even when empty, so the BE reads a present-but-empty list like IcebergScanRange's.
        fileDesc.deleteFiles = positionDeletes.map { toThrift(it) }

        formatDesc.icebergParams = fileDesc
        // Thrift defaults table_level_row_count to -1, so leaving it unset on normal ranges keeps
        // their wire bytes identical to the pre-count-pushdown shape.
        if (pushDownRowCount >= 0) {
            formatDesc.tableLevelRowCount = pushDownRowCount
        }
        populateColumnsFromPath(rangeDesc)
    }

    /**
     * Body-absent partition columns of a hive-layout file, filled from the path by the BE. Values
     * are never null; NULL hive partitions are rejected FE-side because the BE fill can't represent
     * them. The scan node must also declare these columns as `path_partition_keys` so the BE
     * excludes them from file decode.
     */
    private fun populateColumnsFromPath(rangeDesc: TFileRangeDesc) {
        if (columnsFromPath.isEmpty()) {
            return
        }
        rangeDesc.columnsFromPathKeys = columnsFromPath.keys.toList()
        rangeDesc.columnsFromPath = columnsFromPath.values.toList()
        rangeDesc.columnsFromPathIsNull = columnsFromPath.map { false }
    }

    internal class Builder {
        var path: String? = null
            private set
        var start: Long = 0L
            private set
        var length: Long = -1L
            private set
        var fileSize: Long = -1L
            private set
        var fileFormat: String? = null
            private set
        var positionDeletes: List<DuckLakePositionDelete> = listOf()
            private set
        var partitionBearing: Boolean = false
            private set
        var partitionValues: Map<String, String> = mapOf()
            private set
        var pushDownRowCount: Long = -1L
            private set
        var columnsFromPath: Map<String, String> = mapOf()
            private set

        fun path(path: String): Builder = apply { this.path = path }

        fun start(start: Long): Builder = apply { this.start = start }

        fun length(length: Long): Builder = apply { this.length = length }

        fun fileSize(fileSize: Long): Builder = apply { this.fileSize = fileSize }

        fun fileFormat(fileFormat: String): Builder = apply { this.fileFormat = fileFormat }

        fun positionDeletes(positionDeletes: List<DuckLakePositionDelete>): Builder =
            apply { this.positionDeletes = positionDeletes }

        fun partitionBearing(partitionBearing: Boolean): Builder =
            apply { this.partitionBearing = partitionBearing }

        fun partitionValues(partitionValues: Map<String, String>): Builder =
            apply { this.partitionValues = partitionValues }

        fun pushDownRowCount(pushDownRowCount: Long): Builder =
            apply { this.pushDownRowCount = pushDownRowCount }

        fun columnsFromPath(columnsFromPath: Map<String, String>): Builder =
            apply { this.columnsFromPath = columnsFromPath }

        fun build(): DuckLakeScanRange = DuckLakeScanRange(this)
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /** v2 is the minimum that enables the BE reader's delete-file dispatch. */
        const val ICEBERG_FORMAT_VERSION: Int = 2

        /** Until the BE recognises "ducklake" as a synonym in file_scanner.cpp, the iceberg reader is dispatched by name. */
        const val TABLE_FORMAT_TYPE: String = "iceberg"

        /** TIcebergDeleteFileDesc.content: 1 = position delete, 2 = equality delete, 3 = deletion vector. */
        const val CONTENT_POSITION_DELETE: Int = 1

        private fun toThrift(pd: DuckLakePositionDelete): TIcebergDeleteFileDesc {
            val t = TIcebergDeleteFileDesc()
            t.setPath(pd.path)
            when (pd.fileFormat.lowercase(Locale.ROOT)) {
                "parquet" -> t.setFileFormat(TFileFormatType.FORMAT_PARQUET)
                else -> throw IllegalStateException("Unsupported DuckLake delete-file format: ${pd.fileFormat}")
            }
            // Position bounds are left unset: the catalog doesn't record them, so the BE scans the whole delete file.
            t.setContent(CONTENT_POSITION_DELETE)
            return t
        }
    }
}
