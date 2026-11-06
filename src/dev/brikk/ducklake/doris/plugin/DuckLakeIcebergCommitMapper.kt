package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeFileColumnStats
import dev.brikk.ducklake.catalog.DucklakeWriteFragment
import org.apache.doris.thrift.TIcebergCommitData
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Maps a BE commit fragment (`TIcebergCommitData`: path, size, row count, per-field-id column stats,
 * partition values) to the [DucklakeWriteFragment] that `DucklakeCatalog.commitInsert` records.
 * Parquet field ids are DuckLake column ids, so the Iceberg stats key directly.
 *
 * ## Bound decoding is deliberately conservative
 * Min/max arrive as Iceberg single-value binary. Only the unambiguous cases are decoded; anything
 * else becomes `null`, which the catalog stores as unknown so pruning never eliminates that file on
 * that column. A wrong bound could prune a file holding a matching row, so nothing is guessed.
 * The output strings must parse under `DucklakeStatTypes.parseStat`, which the read-path prune uses.
 *
 * Decoded: int8/16/32 and int64 (little-endian) → decimal string; float32/64 → `BigDecimal` string,
 * non-finite dropped; date → ISO `yyyy-MM-dd` (sorts chronologically); decimal(p,s) → big-endian
 * two's-complement unscaled at scale `s`; varchar → UTF-8.
 * Not decoded: timestamps (DuckDB's exact stored text form isn't pinned, and a mismatch against
 * DuckDB-written stats would mis-merge the table-level bound), unsigned, int128, blob, uuid, boolean,
 * nested, and a `decimal` without `(p,s)`.
 *
 * ## Gaps coupled to the BE
 * `footer_size` is not in `TIcebergCommitData`, so `0` is passed and stored as NULL. Partition values
 * are positional and assumed to follow DuckLake's partition-key index order (the order
 * [DuckLakeIcebergPartitionSpec] added the fields in); the DuckLake `partitionId` is bound by the
 * write plan because it isn't derivable from the Iceberg spec id.
 */
internal object DuckLakeIcebergCommitMapper {

    fun toWriteFragment(
        data: TIcebergCommitData,
        typeByColumnId: Map<Long, String>,
        tableDataDir: String? = null,
        partitionId: Long? = null,
    ): DucklakeWriteFragment {
        val recordCount = if (data.isSetRowCount) data.rowCount else 0L
        val (path, pathIsRelative) = resolvePath(if (data.isSetFilePath) data.filePath else "", tableDataDir)
        return DucklakeWriteFragment(
            path = path,
            pathIsRelative = pathIsRelative,
            fileFormat = "parquet",
            fileSizeBytes = if (data.isSetFileSize) data.fileSize else 0L,
            footerSize = 0L,
            recordCount = recordCount,
            columnStats = toColumnStats(data, recordCount, typeByColumnId),
            partitionValues = partitionValues(data),
            partitionId = partitionId,
            nameMap = null,
        )
    }

    /**
     * The BE reports absolute paths; DuckLake records INSERT files relative to the table dir. A path
     * outside the dir stays absolute so the reader doesn't join it under the table dir twice.
     */
    private fun resolvePath(filePath: String, tableDataDir: String?): Pair<String, Boolean> {
        if (tableDataDir != null) {
            val dir = tableDataDir.trimEnd('/')
            if (filePath.startsWith(dir)) {
                return filePath.removePrefix(dir).trimStart('/') to true
            }
        }
        val absolute = filePath.contains("://") || filePath.startsWith("/")
        return filePath to !absolute
    }

    private fun toColumnStats(
        data: TIcebergCommitData,
        recordCount: Long,
        typeByColumnId: Map<Long, String>,
    ): List<DucklakeFileColumnStats> {
        if (!data.isSetColumnStats) {
            return emptyList()
        }
        // Thrift getters return null for unset map fields.
        val maps = with(data.columnStats) {
            IcebergStatMaps(
                sizes = columnSizes ?: emptyMap(),
                valueCounts = valueCounts ?: emptyMap(),
                nullCounts = nullValueCounts ?: emptyMap(),
                nanCounts = nanValueCounts ?: emptyMap(),
                lowers = lowerBounds ?: emptyMap(),
                uppers = upperBounds ?: emptyMap(),
            )
        }
        return maps.fieldIds().mapNotNull { fieldId ->
            val type = typeByColumnId[fieldId.toLong()] ?: return@mapNotNull null
            buildStat(fieldId, type, recordCount, maps)
        }
    }

    private fun buildStat(
        fieldId: Int,
        type: String,
        recordCount: Long,
        maps: IcebergStatMaps,
    ): DucklakeFileColumnStats {
        val nulls = maps.nullCounts[fieldId] ?: 0L
        // Iceberg value_counts includes nulls; DuckLake wants the non-null count.
        val total = maps.valueCounts[fieldId] ?: recordCount
        return DucklakeFileColumnStats(
            columnId = fieldId.toLong(),
            columnSizeBytes = maps.sizes[fieldId] ?: 0L,
            valueCount = (total - nulls).coerceAtLeast(0L),
            nullCount = nulls,
            minValue = maps.lowers[fieldId]?.let { decodeBound(type, it) },
            maxValue = maps.uppers[fieldId]?.let { decodeBound(type, it) },
            containsNan = (maps.nanCounts[fieldId] ?: 0L) > 0L,
        )
    }

    private class IcebergStatMaps(
        val sizes: Map<Int, Long>,
        val valueCounts: Map<Int, Long>,
        val nullCounts: Map<Int, Long>,
        val nanCounts: Map<Int, Long>,
        val lowers: Map<Int, ByteBuffer>,
        val uppers: Map<Int, ByteBuffer>,
    ) {
        fun fieldIds(): Set<Int> =
            (sizes.keys + valueCounts.keys + nullCounts.keys + lowers.keys + uppers.keys).toSortedSet()
    }

    private fun partitionValues(data: TIcebergCommitData): Map<Int, String?> {
        if (!data.isSetPartitionValues || data.partitionValues.isEmpty()) {
            return emptyMap()
        }
        return data.partitionValues.withIndex().associate { (index, value) -> index to value }
    }

    private fun decodeBound(ducklakeType: String, buffer: ByteBuffer): String? {
        val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val type = ducklakeType.trim().lowercase()
        val width = bytes.remaining()
        return when {
            isIntegerType(type) && width == Int.SIZE_BYTES -> bytes.int.toString()
            isIntegerType(type) && width == Long.SIZE_BYTES -> bytes.long.toString()
            type == "float32" && width == Float.SIZE_BYTES -> decodeFinite(bytes.float.toDouble())
            type == "float64" && width == Double.SIZE_BYTES -> decodeFinite(bytes.double)
            type == "date" && width == Int.SIZE_BYTES -> LocalDate.ofEpochDay(bytes.int.toLong()).toString()
            type.startsWith("decimal") -> decodeDecimal(type, buffer)
            type == "varchar" -> StandardCharsets.UTF_8.decode(bytes).toString()
            else -> null
        }
    }

    /** Iceberg decimals are big-endian two's-complement, unlike the little-endian primitives above. */
    private fun decodeDecimal(ducklakeType: String, buffer: ByteBuffer): String? {
        val scale = decimalScale(ducklakeType) ?: return null
        val raw = ByteArray(buffer.remaining())
        buffer.duplicate().get(raw)
        if (raw.isEmpty()) {
            return null
        }
        return BigDecimal(BigInteger(raw), scale).toPlainString()
    }

    /** `decimal(p)` has scale 0. */
    private fun decimalScale(ducklakeType: String): Int? {
        val open = ducklakeType.indexOf('(')
        val close = ducklakeType.indexOf(')', open + 1)
        if (open < 0 || close < 0) {
            return null
        }
        val args = ducklakeType.substring(open + 1, close).split(',')
        return when (args.size) {
            1 -> 0
            2 -> args[1].trim().toIntOrNull()
            else -> null
        }
    }

    private fun decodeFinite(value: Double): String? =
        if (value.isFinite()) value.toBigDecimal().toPlainString() else null

    private fun isIntegerType(type: String): Boolean =
        type == "int8" || type == "int16" || type == "int32" || type == "int64"
}
