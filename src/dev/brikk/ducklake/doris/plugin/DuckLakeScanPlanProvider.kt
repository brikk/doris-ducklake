package dev.brikk.ducklake.doris.plugin

import java.util.Locale
import java.util.Optional
import java.util.function.Supplier

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeDataFile
import dev.brikk.ducklake.catalog.DucklakeFilePartitionValue
import dev.brikk.ducklake.catalog.DucklakePartitionSpec
import dev.brikk.ducklake.catalog.DucklakePartitionTransform

import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.scan.ConnectorScanPlanProvider
import org.apache.doris.connector.spi.scan.ConnectorScanRange
import org.apache.doris.connector.spi.scan.ConnectorScanRequest
import org.apache.doris.thrift.TFileScanRangeParams
import org.apache.doris.thrift.TIcebergFileDesc
import org.apache.doris.thrift.TTableFormatFileDesc

/**
 * Splits a DuckLake table read into one scan range per data file active at the snapshot pinned on
 * the [DuckLakeTableHandle]. Also collapses COUNT(*) to a single metadata-served range and trims
 * the file list under LIMIT when either is provably exact.
 *
 * Anything the Doris BE reader cannot apply correctly (inlined deletes, historical reads over delete
 * files, partially-compacted files older than the pin) is refused with an explanatory error rather
 * than returning wrong rows.
 */
internal class DuckLakeScanPlanProvider(
    private val catalog: DucklakeCatalog,
    private val pathResolver: DuckLakePathResolver,
    catalogProperties: Map<String, String>,
) : ConnectorScanPlanProvider {

    private val catalogProperties: Map<String, String> = catalogProperties.toMap()
    private val inlinedData = DuckLakeInlinedDataRanges(catalog, this.catalogProperties)

    /**
     * `requiredPartitions` is not consumed: file pruning is predicate-driven via
     * [DuckLakeTableHandle.prunedFileIds]. `limit` is consumed when the trim is provably safe.
     */
    override fun planScan(
        session: ConnectorSession?,
        request: ConnectorScanRequest,
    ): List<ConnectorScanRange> =
        catalog.readSession(Supplier {
            planScanInternal(
                handle = request.tableHandle,
                filter = request.filter,
                countPushdown = request.isCountPushdown,
                limit = request.limit,
            )
        })

    /** Delete-file paths carried on a range's iceberg params, for the VERBOSE per-backend EXPLAIN block. */
    override fun getDeleteFiles(tableFormatParams: TTableFormatFileDesc?): List<String> {
        if (tableFormatParams == null || !tableFormatParams.isSetIcebergParams) {
            return emptyList()
        }
        val icebergParams: TIcebergFileDesc = tableFormatParams.icebergParams
        if (!icebergParams.isSetDeleteFiles) {
            return emptyList()
        }
        return icebergParams.deleteFiles
            ?.filter { it != null && it.isSetPath }
            ?.map { it.path }
            ?: emptyList()
    }

    private fun planScanInternal(
        handle: ConnectorTableHandle,
        filter: Optional<ConnectorExpression>,
        countPushdown: Boolean,
        limit: Long,
    ): List<ConnectorScanRange> {
        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()

        val schema = catalog.getSchema(dlHandle.database, dlHandle.snapshotId)
            ?: throw IllegalStateException(
                "Schema metadata missing for '${dlHandle.database}' at snapshot ${dlHandle.snapshotId}",
            )
        val table = catalog.getTableById(dlHandle.tableId, dlHandle.snapshotId)
            ?: throw IllegalStateException(
                "Table metadata missing for tableId=${dlHandle.tableId} at snapshot ${dlHandle.snapshotId}",
            )

        failOnInlinedDeletes(dlHandle)

        val tableDataPath = pathResolver.resolveTableDataPath(schema, table)
        val dataFiles = catalog.getDataFiles(dlHandle.tableId, dlHandle.snapshotId)
        failOnHistoricalDeleteFiles(dlHandle, dataFiles)
        failOnUnfilterablePartialFile(dlHandle, dataFiles)

        val partitions = resolvePartitionContext(dlHandle, dataFiles)

        if (countPushdown && isCountServableFromMetadata(dlHandle, filter, dataFiles)) {
            return planCountPushdown(dataFiles, tableDataPath, partitions)
        }

        val prunedIds = dlHandle.prunedFileIds
        val prunedFiles = if (prunedIds != null) dataFiles.filter { it.dataFileId in prunedIds } else dataFiles
        val scanFiles = trimFilesToLimit(prunedFiles, dlHandle, filter, dataFiles, limit)

        val ranges = ArrayList<ConnectorScanRange>(scanFiles.size)
        for (file in scanFiles) {
            ranges.add(buildRange(file, tableDataPath, partitions, PUSH_DOWN_COUNT_NONE))
        }
        // Inlined rows live in no catalog data file, so a file-id prune can't exclude them; the BE
        // re-applies the pushed filter to the synthesized range. Dropping them under a prune made
        // `WHERE ...` return empty for inlined tables.
        ranges.addAll(inlinedData.plan(dlHandle))
        return ranges
    }

    /**
     * Inlined DELETEs tombstone file-resident rows via catalog positions the BE can't apply, so
     * scanning would over-return. Inlined DATA rows are served by [DuckLakeInlinedDataRanges].
     */
    private fun failOnInlinedDeletes(handle: DuckLakeTableHandle) {
        if (catalog.hasInlinedDeletes(handle.tableId, handle.snapshotId)) {
            throw DorisConnectorException(
                "DuckLake table has inlined DELETEs in the metadata catalog (rows in " +
                    "ducklake_inlined_delete_*, not a delete file); the Doris connector can't apply " +
                    "them at scan time yet. Flush them (DuckDB: CALL <lake>.flush_inlined_data()) or " +
                    "disable inlining (CALL <lake>.set_option('data_inlining_row_limit', '0')) and rewrite.",
            )
        }
    }

    /**
     * A delete file may carry positions from several snapshots; the Iceberg-style reader applies all
     * of them, so a read pinned before some of those deletions would under-return. A null
     * `partial_max` is not proof the file holds a single snapshot.
     */
    private fun failOnHistoricalDeleteFiles(handle: DuckLakeTableHandle, files: List<DucklakeDataFile>) {
        if (files.none { it.deleteFilePath != null }) return
        if (handle.snapshotId == catalog.currentSnapshotId) return
        throw DorisConnectorException(
            "Historical reads with delete files require per-position snapshot filtering; " +
                "the Doris reader cannot establish deletion visibility at snapshot ${handle.snapshotId}. " +
                "This read is refused rather than applying later deletions. Latest-snapshot reads remain available.",
        )
    }

    /**
     * A compacted file whose `partial_max` is newer than the pin physically holds rows from later
     * snapshots, tagged by the hidden `_ducklake_internal_snapshot_id` column. The BE has no hook to
     * filter on it, so scanning the file wholesale would over-return. Never fires at latest.
     */
    private fun failOnUnfilterablePartialFile(
        handle: DuckLakeTableHandle,
        dataFiles: List<DucklakeDataFile>,
    ) {
        if (dataFiles.any { (it.partialMax ?: Long.MIN_VALUE) > handle.snapshotId }) {
            throw DorisConnectorException(
                "Time travel to snapshot ${handle.snapshotId} reads a compacted (partial) data file " +
                    "that also contains rows from newer snapshots; the Doris connector cannot yet apply " +
                    "the per-row snapshot filter (hidden _ducklake_internal_snapshot_id column) the BE " +
                    "would need, so this read is not supported. Reads at the latest snapshot are unaffected.",
            )
        }
    }

    /**
     * Whether a no-grouping COUNT(*) is exactly the sum of `ducklake_data_file.record_count`. It is
     * not when:
     *  - any filter is in play: the count must be over all rows;
     *  - a data file has a position-delete file: deletes reduce the count below file metadata;
     *  - the pin is not the latest snapshot: compaction merges rows from several snapshots into one
     *    file whose record_count is the merged total, even when `partial_max` is null;
     *  - a partial file extends past the pin;
     *  - the table has inlined deletes or live inlined rows at the snapshot: those are in the catalog
     *    DB, not in any file.
     *
     * In-memory checks run first; the two catalog round-trips only fire for a clean scan.
     */
    private fun isCountServableFromMetadata(
        dlHandle: DuckLakeTableHandle,
        filter: Optional<ConnectorExpression>,
        dataFiles: List<DucklakeDataFile>,
    ): Boolean {
        if (filter.isPresent || dlHandle.pushedFilter != null || dlHandle.prunedFileIds != null) {
            return false
        }
        if (dlHandle.snapshotId != catalog.currentSnapshotId) {
            return false
        }
        if (dataFiles.any { it.deleteFilePath != null }) {
            return false
        }
        if (dataFiles.any { (it.partialMax ?: Long.MIN_VALUE) > dlHandle.snapshotId }) {
            return false
        }
        if (catalog.hasInlinedDeletes(dlHandle.tableId, dlHandle.snapshotId)) {
            return false
        }
        return catalog.getInlinedDataInfos(dlHandle.tableId, dlHandle.snapshotId)
            .none { it.hasLiveRows }
    }

    /**
     * One range on the first data file carrying the summed total; the BE's count reader serves
     * `table_level_row_count` without opening the file. An empty table yields no range, so COUNT
     * returns 0.
     */
    private fun planCountPushdown(
        dataFiles: List<DucklakeDataFile>,
        tableDataPath: String,
        partitions: PartitionContext,
    ): List<ConnectorScanRange> {
        val first = dataFiles.firstOrNull() ?: return listOf()
        val totalRecords = dataFiles.sumOf { it.recordCount }
        return listOf(buildRange(first, tableDataPath, partitions, totalRecords))
    }

    /**
     * The minimal prefix of [scanFiles] whose cumulative `record_count` reaches [limit]. Safe only when
     * `record_count` is an exact count of the rows the BE will read from each file, which is exactly
     * what [isCountServableFromMetadata] certifies (under that gate `scanFiles == dataFiles`). The BE
     * still applies the real LIMIT; this only spares it ranges it would never reach. `limit <= 0` is
     * the no-limit sentinel.
     */
    private fun trimFilesToLimit(
        scanFiles: List<DucklakeDataFile>,
        dlHandle: DuckLakeTableHandle,
        filter: Optional<ConnectorExpression>,
        dataFiles: List<DucklakeDataFile>,
        limit: Long,
    ): List<DucklakeDataFile> {
        if (limit <= 0 || scanFiles.isEmpty()) {
            return scanFiles
        }
        if (!isCountServableFromMetadata(dlHandle, filter, dataFiles)) {
            return scanFiles
        }
        var cumulative = 0L
        var keep = 0
        for (file in scanFiles) {
            keep++
            cumulative += file.recordCount
            if (cumulative >= limit) {
                break
            }
        }
        return if (keep >= scanFiles.size) scanFiles else scanFiles.subList(0, keep)
    }

    /**
     * Full-file extent (start=0, length=fileSize); the BE splits along parquet row groups itself.
     * Identity partition values (body-carried) and hive path values (body-absent) are merged so a
     * partition-bearing range always reports its values.
     */
    private fun buildRange(
        file: DucklakeDataFile,
        tableDataPath: String,
        partitions: PartitionContext,
        pushDownRowCount: Long,
    ): DuckLakeScanRange {
        val absolutePath = pathResolver.resolveFilePath(file.path, file.pathIsRelative, tableDataPath)
        val hiveColumns = hivePartitionValues(file, partitions, absolutePath)
        val fePartitionValues = LinkedHashMap<String, String>()
        fePartitionValues.putAll(identityPartitionValues(file, partitions))
        fePartitionValues.putAll(hiveColumns)
        return DuckLakeScanRange.Builder()
            .path(absolutePath)
            .start(0L)
            .length(file.fileSizeBytes)
            .fileSize(file.fileSizeBytes)
            .fileFormat(normalizeFileFormat(file.fileFormat))
            .positionDeletes(resolvePositionDeletes(file, tableDataPath))
            .partitionBearing(partitions.partitionBearing)
            .partitionValues(fePartitionValues)
            .columnsFromPath(hiveColumns)
            .pushDownRowCount(pushDownRowCount)
            .build()
    }

    /**
     * Body-absent partition columns of a hive-layout `add_files` file, parsed from the path. The name
     * map keys by the original hive dir key (pre-rename); the emitted key is the current column name.
     *
     * A NULL hive value (`__HIVE_DEFAULT_PARTITION__`, or a key missing from the path) is refused: the
     * BE's `columns_from_path` fill cannot represent SQL NULL — it fills the literal string, and a NULL
     * predicate over such a column crashes the reader.
     */
    private fun hivePartitionValues(
        file: DucklakeDataFile,
        partitions: PartitionContext,
        absolutePath: String,
    ): Map<String, String> {
        val mappingId = file.mappingId ?: return emptyMap()
        val pathKeysByColumnId = partitions.partitionNameMapsByMappingId[mappingId] ?: return emptyMap()
        if (pathKeysByColumnId.isEmpty()) {
            return emptyMap()
        }
        val pathValues = HivePartitionPath.parse(absolutePath)
        val out = LinkedHashMap<String, String>(pathKeysByColumnId.size)
        for ((columnId, pathKey) in pathKeysByColumnId) {
            val columnName = partitions.columnNamesById[columnId] ?: continue
            val raw = pathValues[pathKey.lowercase(Locale.ROOT)]
            if (raw == null || raw == HivePartitionPath.HIVE_DEFAULT_PARTITION) {
                throw DorisConnectorException(
                    "reading a hive-layout add_files file with a NULL partition value (column " +
                        "'$columnName', file '$absolutePath') is not supported yet by the Doris connector: " +
                        "the BE's columns_from_path fill cannot represent SQL NULL for a path partition " +
                        "(it would return the literal sentinel, and a NULL predicate over it crashes the " +
                        "reader). Rewrite the table so partition values are non-null, or read via DuckDB.",
                )
            }
            out[columnName] = HivePartitionPath.urlDecode(raw)
        }
        return out
    }

    /**
     * Partition inputs resolved once per plan. The active spec is the last of the table's specs. Only
     * identity fields are surfaced as partition values (derived values like `year(ts)` must not be
     * presented as raw column values), so bucket/temporal-only specs skip the extra round-trips.
     */
    private fun resolvePartitionContext(
        dlHandle: DuckLakeTableHandle,
        dataFiles: List<DucklakeDataFile>,
    ): PartitionContext {
        val specs = catalog.getPartitionSpecs(dlHandle.tableId, dlHandle.snapshotId)
        val activeSpec = specs.lastOrNull()

        // `is_partition` name-map entries are the only per-file signal that a file is hive-layout
        // (partition columns in the path) rather than native (partition columns in the body).
        val mappingIds = dataFiles.mapNotNull { it.mappingId }.toSet()
        val partitionNameMaps = if (mappingIds.isEmpty()) emptyMap() else catalog.getPartitionNameMaps(mappingIds)
        val hasHiveLayout = partitionNameMaps.values.any { it.isNotEmpty() }
        val hasIdentityField =
            activeSpec?.fields?.any { it.transform == DucklakePartitionTransform.IDENTITY } == true

        if (activeSpec == null && !hasHiveLayout) {
            return PartitionContext.UNPARTITIONED
        }

        // Lowercased to match Doris's identifier convention.
        val columnNamesById =
            if (hasIdentityField || hasHiveLayout) {
                catalog.getTableColumns(dlHandle.tableId, dlHandle.snapshotId)
                    .associate { it.columnId to it.columnName.lowercase(Locale.ROOT) }
            } else {
                mapOf()
            }
        val valuesByFileId =
            if (hasIdentityField) {
                catalog.getFilePartitionValues(dlHandle.tableId, dlHandle.snapshotId)
            } else {
                mapOf()
            }
        return PartitionContext(activeSpec, valuesByFileId, columnNamesById, partitionNameMaps)
    }

    /**
     * `columnName -> value` for identity fields of the active spec, in spec order. A file written under
     * a retired spec yields an empty map: stored values are keyed by that spec's `partition_key_index`,
     * and every spec numbers keys from 0, so mapping them through the active spec would be wrong.
     */
    private fun identityPartitionValues(
        file: DucklakeDataFile,
        partitions: PartitionContext,
    ): Map<String, String> {
        val spec = partitions.activeSpec ?: return mapOf()
        val filePartitionId = file.partitionId
        if (filePartitionId != null && filePartitionId != spec.partitionId) {
            return mapOf()
        }
        val values = partitions.valuesByFileId[file.dataFileId] ?: return mapOf()
        if (values.isEmpty()) {
            return mapOf()
        }
        val byKeyIndex = values.associate { it.partitionKeyIndex to it.partitionValue }
        val out = LinkedHashMap<String, String>()
        for (field in spec.fields) {
            if (field.transform == DucklakePartitionTransform.IDENTITY) {
                val value = byKeyIndex[field.partitionKeyIndex]
                val columnName = partitions.columnNamesById[field.columnId]
                if (value != null && columnName != null) {
                    out[columnName] = value
                }
            }
        }
        return out
    }

    private class PartitionContext(
        val activeSpec: DucklakePartitionSpec?,
        val valuesByFileId: Map<Long, List<DucklakeFilePartitionValue>>,
        val columnNamesById: Map<Long, String>,
        /** mappingId → (columnId → hive path key); empty for native tables. */
        val partitionNameMapsByMappingId: Map<Long, Map<Long, String>> = mapOf(),
    ) {
        /** A table-level fact, independent of whether a given file recorded values. */
        val partitionBearing: Boolean
            get() = activeSpec != null || partitionNameMapsByMappingId.values.any { it.isNotEmpty() }

        companion object {
            val UNPARTITIONED = PartitionContext(null, mapOf(), mapOf(), mapOf())
        }
    }

    /** The catalog guarantees at most one active delete file per data file per snapshot and inlines it on the row. */
    private fun resolvePositionDeletes(
        file: DucklakeDataFile,
        tableDataPath: String,
    ): List<DuckLakePositionDelete> {
        val deletePath = file.deleteFilePath ?: return listOf()
        val isRelative = file.deleteFilePathIsRelative ?: true
        val absolutePath = pathResolver.resolveFilePath(deletePath, isRelative, tableDataPath)
        val format = normalizeFileFormat(file.deleteFileFormat ?: "parquet")
        return listOf(DuckLakePositionDelete(absolutePath, format))
    }

    /**
     * Scan-node properties: the BE reader dispatch, storage credentials (prefixed so
     * [populateScanLevelParams] can strip them back off), the field-id schema dictionary, and the
     * hive-layout partition keys.
     */
    override fun getScanNodeProperties(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        columns: List<ConnectorColumnHandle>,
        filter: Optional<ConnectorExpression>,
    ): Map<String, String> = catalog.readSession(Supplier {
        val out: MutableMap<String, String> = linkedMapOf()
        // Without this PluginDrivenScanNode defaults to FORMAT_JNI and the iceberg reader bails.
        out[PROP_FILE_FORMAT_TYPE] = PARQUET_FORMAT
        for ((key, value) in catalogProperties) {
            if (isStorageProperty(key)) {
                out[PROP_LOCATION_PREFIX + key] = value
            }
        }

        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()
        val dataFiles = catalog.getDataFiles(dlHandle.tableId, dlHandle.snapshotId)
        val mappingIds = dataFiles.mapNotNull { it.mappingId }.toSet()
        val hivePartitionColumnIds =
            if (mappingIds.isEmpty()) {
                emptySet()
            } else {
                catalog.getPartitionNameMaps(mappingIds).values.flatMapTo(HashSet()) { it.keys }
            }

        schemaDictionaryProp(dlHandle, columns, mappingIds, hivePartitionColumnIds)
            ?.let { out[PROP_SCHEMA_DICTIONARY] = it }

        pathPartitionKeysProp(dlHandle, dataFiles, hivePartitionColumnIds)
            ?.let { out[PROP_PATH_PARTITION_KEYS] = it }
        out
    })

    /**
     * Hive-layout partition columns are excluded from the dictionary: they are body-absent and
     * constant-filled from the path, and leaving them in makes the BE's field-id reader fail
     * "name_mapping must be set when read missing field id data file" on the partition slot.
     */
    private fun schemaDictionaryProp(
        handle: DuckLakeTableHandle,
        columns: List<ConnectorColumnHandle>,
        mappingIds: Set<Long>,
        hivePartitionColumnIds: Set<Long>,
    ): String? {
        val dlColumns = columns.mapNotNull { it as? DuckLakeColumnHandle }
        if (dlColumns.isEmpty()) {
            return null
        }
        val bodyColumns = dlColumns.filter { it.columnId !in hivePartitionColumnIds }
        val nameMaps = if (mappingIds.isEmpty()) emptyList() else catalog.getNameMaps(mappingIds).values
        return encodeReadSchemaDictionary(catalog, handle, bodyColumns, nameMaps, mappingIds.isNotEmpty())
    }

    /**
     * `path_partition_keys` tells FileQueryScanNode to classify these columns as PARTITION_KEY and
     * exclude them from file decode; the per-range `columns_from_path` then fills them.
     *
     * The declaration is scan-node-wide, so a table mixing hive-layout files (partition columns in the
     * path) with natively-written partitioned files (partition columns in the body) is refused: the BE
     * would wrongly skip decoding the body column for the native files.
     */
    private fun pathPartitionKeysProp(
        dlHandle: DuckLakeTableHandle,
        dataFiles: List<DucklakeDataFile>,
        hivePartitionColumnIds: Set<Long>,
    ): String? {
        if (hivePartitionColumnIds.isEmpty()) {
            return null
        }
        if (dataFiles.any { it.partitionId != null && it.mappingId == null }) {
            throw DorisConnectorException(
                "DuckLake table mixes hive-layout add_files partition files (partition columns in the " +
                    "path) with natively-written partitioned files (partition columns in the parquet " +
                    "body); the Doris connector cannot serve both in one scan yet, because the " +
                    "scan-node path_partition_keys declaration is table-wide. Rewrite the table " +
                    "(e.g. INSERT ... SELECT into a fresh table) so all files share one layout.",
            )
        }
        val names = catalog.getTableColumns(dlHandle.tableId, dlHandle.snapshotId)
            .filter { it.columnId in hivePartitionColumnIds }
            .map { it.columnName.lowercase(Locale.ROOT) }
        return if (names.isEmpty()) null else names.joinToString(",")
    }

    override fun populateScanLevelParams(
        params: TFileScanRangeParams,
        nodeProperties: Map<String, String>?,
    ) {
        if (nodeProperties.isNullOrEmpty()) {
            return
        }
        DuckLakeSchemaDictionary.apply(params, nodeProperties[PROP_SCHEMA_DICTIONARY])
        if (params.properties == null) {
            params.properties = mutableMapOf()
        }
        val out = params.properties
        for ((key, value) in nodeProperties) {
            if (!key.startsWith(PROP_LOCATION_PREFIX)) {
                continue
            }
            val stripped = key.substring(PROP_LOCATION_PREFIX.length)
            // The BE's S3ClientFactory reads `AWS_*` keys verbatim and the FE-side `s3.*` normaliser
            // does not run on the parquet reader path, so both spellings are emitted.
            out[stripped] = value
            val alias = canonicalAwsAlias(stripped)
            if (alias != null) {
                out.putIfAbsent(alias, value)
            }
        }
    }

    companion object {
        /** Prefix that ferries storage credentials through scan-node properties; stripped before the BE sees them. */
        const val PROP_LOCATION_PREFIX: String = "ducklake.location."

        /** Base64 field-id schema dictionary; `getScanNodeProperties` and `populateScanLevelParams` share no instance state. */
        const val PROP_SCHEMA_DICTIONARY: String = "ducklake.schema_dictionary"

        /** Read verbatim by PluginDrivenScanNode.getPathPartitionKeys. */
        const val PROP_PATH_PARTITION_KEYS: String = "path_partition_keys"

        /** Read by PluginDrivenScanNode.mapFileFormatType; `ConnectorScanRange.getFileFormat()` is not consulted. */
        const val PROP_FILE_FORMAT_TYPE: String = "file_format_type"

        const val PARQUET_FORMAT: String = "parquet"

        private const val PUSH_DOWN_COUNT_NONE: Long = -1L

        /** FE-form storage key → the BE's `AWS_*` alias (`be/src/util/s3_util.cpp`), or null. */
        fun canonicalAwsAlias(unprefixedKey: String): String? =
            when (unprefixedKey) {
                "s3.access_key" -> "AWS_ACCESS_KEY"
                "s3.secret_key" -> "AWS_SECRET_KEY"
                "s3.endpoint" -> "AWS_ENDPOINT"
                "s3.region" -> "AWS_REGION"
                "s3.session_token" -> "AWS_TOKEN"
                else -> null
            }

        /** Storage-credential keys forwarded to the BE. DuckLake-specific keys (`metadata.*`, `storage.warehouse`, …) are FE-only. */
        fun isStorageProperty(key: String): Boolean =
            key.startsWith("s3.") ||
                key.startsWith("AWS_") ||
                key.startsWith("aws.") ||
                key.startsWith("fs.") ||
                key == "use_path_style"

        /** The BE's reader dispatch is case-sensitive on the file format string. */
        private fun normalizeFileFormat(catalogFormat: String?): String =
            catalogFormat?.lowercase(Locale.ROOT) ?: "parquet"
    }
}
