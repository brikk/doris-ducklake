package dev.brikk.ducklake.doris.plugin

import java.time.Instant
import java.util.Locale

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeSchema
import dev.brikk.ducklake.catalog.DucklakeTable
import dev.brikk.ducklake.catalog.ExpireSnapshotsResult
import dev.brikk.ducklake.catalog.TransactionConflictException

import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.ConnectorType
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.procedure.ConnectorProcedureOps
import org.apache.doris.connector.spi.procedure.ConnectorProcedureResult
import org.apache.doris.connector.spi.procedure.ProcedureExecutionMode
import org.apache.doris.connector.spi.pushdown.ConnectorPredicate

/**
 * Maintenance procedures exposed as `ALTER TABLE <t> EXECUTE <proc>(...)`:
 *  - `expire_snapshots`: metadata only. Ends old snapshots and schedules their now-dead files for
 *    later deletion.
 *  - `cleanup_old_files`: physically deletes the blobs `expire_snapshots` scheduled, once older than
 *    a grace period, then drops their schedule rows.
 *  - `remove_orphan_files`: deletes DuckLake-managed residue (`ducklake-`-prefixed `.parquet` /
 *    `.puffin` / `.db`, see [OrphanFiles]) under the warehouse that no catalog row references and
 *    that is older than a grace period.
 *
 * Doris routes every `ALTER TABLE EXECUTE` for a connector through this one class and dispatches on
 * the procedure name, so argument parsing, validation and result shaping all live here.
 *
 * ## Catalog-wide vs. table-scoped
 * DuckLake snapshots are catalog-wide versions, not per-table ones, but Doris only offers the
 * table-scoped `ALTER TABLE <t> EXECUTE` syntax. Faking per-table expiry would be silently wrong, so
 * `expire_snapshots` runs catalog-wide, ignores which table was named, and says so in its result row
 * (`scope = "catalog-wide (ignores the named table)"`). `remove_orphan_files` is the one procedure
 * where the named table matters: its `scope` argument widens outward from that table.
 */
internal class DuckLakeProcedureOps(
    private val catalog: DucklakeCatalog,
    private val properties: Map<String, String>,
    blobStoreProvider: (() -> WarehouseBlobStore)? = null,
) : ConnectorProcedureOps {

    // Floor for every procedure's retention_threshold: protects recent time-travel snapshots and
    // files an in-flight (possibly cross-engine) reader or writer may still touch. Parsed at
    // construction so a misconfigured catalog fails fast.
    private val minRetentionMillis: Long =
        parseDuration(
            properties[DuckLakeConnectorProperties.MAINTENANCE_MIN_RETENTION]
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_RETENTION,
            DuckLakeConnectorProperties.MAINTENANCE_MIN_RETENTION,
        )

    // Scheduled-file paths are relative to the catalog data_path, not a table dir.
    private val warehouseRoot: String? by lazy {
        catalog.getDataPath() ?: properties[DuckLakeConnectorProperties.STORAGE_WAREHOUSE]
    }

    // Lazy so expire_snapshots never builds an S3 client and a missing credential only fails the
    // procedures that need storage.
    private val blobStore: WarehouseBlobStore by lazy {
        (blobStoreProvider ?: { S3WarehouseBlobStore.fromProperties(properties) })()
    }

    private val pathResolver: DuckLakePathResolver by lazy {
        DuckLakePathResolver(catalog, properties[DuckLakeConnectorProperties.STORAGE_WAREHOUSE])
    }

    override fun getSupportedProcedures(): List<String> =
        listOf(EXPIRE_SNAPSHOTS, CLEANUP_OLD_FILES, REMOVE_ORPHAN_FILES)

    /** All three are metadata + FE-side storage ops, never a distributed rewrite. */
    override fun getExecutionMode(procedureName: String): ProcedureExecutionMode =
        ProcedureExecutionMode.SINGLE_CALL

    @Suppress("ThrowsCount")
    override fun execute(
        session: ConnectorSession?,
        table: ConnectorTableHandle?,
        procedureName: String?,
        properties: Map<String, String>?,
        whereCondition: ConnectorPredicate?,
        partitionNames: List<String>?,
    ): ConnectorProcedureResult {
        val proc = SUPPORTED_BY_LOWER[procedureName?.lowercase(Locale.ROOT)]
            ?: throw DorisConnectorException(
                "DuckLake connector does not support procedure '$procedureName'; " +
                    "supported: ${getSupportedProcedures()}",
            )
        // Rejected rather than ignored: an operator who wrote WHERE expects it to scope something.
        if (whereCondition != null) {
            throw DorisConnectorException("$proc does not accept a WHERE condition")
        }
        if (!partitionNames.isNullOrEmpty()) {
            throw DorisConnectorException("$proc does not accept a PARTITION clause")
        }
        return when (proc) {
            EXPIRE_SNAPSHOTS -> expireSnapshots(properties.orEmpty())
            CLEANUP_OLD_FILES -> cleanupOldFiles(properties.orEmpty())
            REMOVE_ORPHAN_FILES -> removeOrphanFiles(table, properties.orEmpty())
            else -> error("unreachable: $proc") // SUPPORTED_BY_LOWER only maps known procedures
        }
    }

    /**
     * Selection is either an explicit `snapshot_ids` list or everything older than
     * `retention_threshold` (floored by [minRetentionMillis]). The catalog never expires the latest
     * snapshot. A dry run only calls the read-only [DucklakeCatalog.listExpirableSnapshots].
     */
    @Suppress("ThrowsCount")
    private fun expireSnapshots(properties: Map<String, String>): ConnectorProcedureResult {
        val args = CaseInsensitiveArgs(properties)
        val dryRun = parseBoolean(args[DRY_RUN], DRY_RUN)
        val explicitRaw = args[SNAPSHOT_IDS]
        val retentionRaw = args[RETENTION_THRESHOLD]

        if (!explicitRaw.isNullOrBlank() && !retentionRaw.isNullOrBlank()) {
            throw DorisConnectorException(
                "snapshot_ids and retention_threshold are mutually exclusive; pass only one",
            )
        }

        val expirable: List<Long> = if (!explicitRaw.isNullOrBlank()) {
            val explicit = parseSnapshotIds(explicitRaw)
            catalog.listExpirableSnapshots(null, explicit)
        } else {
            val retention = retentionRaw?.takeIf { it.isNotBlank() } ?: DEFAULT_RETENTION
            val retentionMillis = parseDuration(retention, RETENTION_THRESHOLD)
            if (retentionMillis < minRetentionMillis) {
                throw DorisConnectorException(
                    "retention_threshold '$retention' is below the minimum " +
                        "'${DuckLakeConnectorProperties.MAINTENANCE_MIN_RETENTION}' floor " +
                        "(${minRetentionMillis}ms); refusing to expire recent snapshots. " +
                        "Pass an explicit snapshot_ids list to override.",
                )
            }
            val cutoff: Instant = Instant.now().minusMillis(retentionMillis)
            catalog.listExpirableSnapshots(cutoff, null)
        }

        if (dryRun || expirable.isEmpty()) {
            return result(dryRun = dryRun, expiredCount = 0, scheduledFileCount = -1, ids = expirable)
        }
        val result: ExpireSnapshotsResult = try {
            expireDuckLakeSnapshots(catalog, expirable.toSet())
        } catch (e: TransactionConflictException) {
            throw DorisConnectorException(
                "expire_snapshots conflicted with a concurrent catalog commit; retry: ${e.message}", e,
            )
        }
        return result(
            dryRun = false,
            expiredCount = result.expiredSnapshotCount,
            scheduledFileCount = result.scheduledFileCount,
            ids = expirable,
        )
    }

    /** `scheduled_file_count` is `"n/a"` on a dry run, since nothing was scheduled. */
    private fun result(
        dryRun: Boolean,
        expiredCount: Int,
        scheduledFileCount: Int,
        ids: List<Long>,
    ): ConnectorProcedureResult {
        val row = listOf(
            dryRun.toString(),
            CATALOG_WIDE_SCOPE,
            (if (dryRun) ids.size else expiredCount).toString(),
            if (scheduledFileCount < 0) "n/a" else scheduledFileCount.toString(),
            ids.joinToString(","),
        )
        return ConnectorProcedureResult(RESULT_SCHEMA, listOf(row))
    }

    /**
     * Deletes are best-effort per file and only the successes have their schedule rows removed, so a
     * partial run makes progress and is safely resumable.
     */
    @Suppress("ThrowsCount")
    private fun cleanupOldFiles(properties: Map<String, String>): ConnectorProcedureResult {
        val args = CaseInsensitiveArgs(properties)
        val dryRun = parseBoolean(args[DRY_RUN], DRY_RUN)
        val retention = args[RETENTION_THRESHOLD]?.takeIf { it.isNotBlank() } ?: DEFAULT_RETENTION
        val retentionMillis = parseDuration(retention, RETENTION_THRESHOLD)
        if (retentionMillis < minRetentionMillis) {
            throw DorisConnectorException(
                "retention_threshold '$retention' is below the minimum " +
                    "'${DuckLakeConnectorProperties.MAINTENANCE_MIN_RETENTION}' floor " +
                    "(${minRetentionMillis}ms); refusing to delete recently-scheduled files.",
            )
        }
        val cutoff: Instant = Instant.now().minusMillis(retentionMillis)
        val scheduled = catalog.listFilesScheduledForDeletion(cutoff)
        if (scheduled.isEmpty() || dryRun) {
            return fileGcResult(dryRun, retention, deleted = 0, wouldDelete = scheduled.size, failed = 0)
        }

        // URI → data_file_id so exactly the deleted rows are dropped.
        val idByUri = LinkedHashMap<String, Long>(scheduled.size)
        for (f in scheduled) {
            idByUri[resolveScheduledUri(f.path, f.pathIsRelative)] = f.dataFileId
        }

        val outcome = blobStore.delete(idByUri.keys)
        val deletedIds = outcome.deleted.mapNotNull { idByUri[it] }
        if (deletedIds.isNotEmpty()) {
            catalog.removeScheduledFileRows(deletedIds)
        }
        return fileGcResult(
            dryRun = false,
            retention = retention,
            deleted = outcome.deleted.size,
            wouldDelete = scheduled.size,
            failed = outcome.failed.size,
        )
    }

    /**
     * `scope` widens from the named table: `table` (default) scans its data dir, `schema` (alias
     * `database`) scans the schema dir, `catalog` scans the warehouse root. Scanning the directory
     * rather than per-table is what reaches failed-CREATE residue that has no catalog row.
     *
     * The known set is catalog-wide ([DucklakeCatalog.listAllReferencedFiles]) and includes files of
     * dropped-but-unexpired tables and scheduled files, so neither time-travel data nor a pending
     * cleanup can be deleted here. Table files resolve against their table path; scheduled files
     * against the catalog root.
     */
    @Suppress("ThrowsCount")
    private fun removeOrphanFiles(table: ConnectorTableHandle?, properties: Map<String, String>): ConnectorProcedureResult {
        val args = CaseInsensitiveArgs(properties)
        val dryRun = parseBoolean(args[DRY_RUN], DRY_RUN)
        val retention = args[RETENTION_THRESHOLD]?.takeIf { it.isNotBlank() } ?: DEFAULT_RETENTION
        val retentionMillis = parseDuration(retention, RETENTION_THRESHOLD)
        if (retentionMillis < minRetentionMillis) {
            throw DorisConnectorException(
                "retention_threshold '$retention' is below the minimum " +
                    "'${DuckLakeConnectorProperties.MAINTENANCE_MIN_RETENTION}' floor " +
                    "(${minRetentionMillis}ms); refusing to delete recently-written files.",
            )
        }
        val scope = parseScope(args[SCOPE])
        val handle = table?.asDuckLakeHandle<DuckLakeTableHandle>()
            ?: throw DorisConnectorException(
                "remove_orphan_files must be run as ALTER TABLE <table> EXECUTE remove_orphan_files(...); " +
                    "use scope='schema'/'catalog' to widen from that table",
            )

        val (_, scanRoots) = resolveOrphanScope(scope, handle)
        val rootDataPath = pathResolver.rootDataPath()
        val referenced = catalog.listAllReferencedFiles()
        val knownPaths = buildSet {
            referenced.tableFiles.forEach { ref ->
                val schemaDataPath = DuckLakePathResolver.resolveScopedPath(
                    ref.schemaPath,
                    ref.schemaPathIsRelative,
                    rootDataPath,
                )
                val tableDataPath = DuckLakePathResolver.resolveScopedPath(
                    ref.tablePath,
                    ref.tablePathIsRelative,
                    schemaDataPath,
                )
                add(pathResolver.resolveFilePath(ref.path, ref.pathIsRelative, tableDataPath))
            }
            referenced.scheduledFiles.forEach { ref ->
                add(pathResolver.resolveFilePath(ref.path, ref.pathIsRelative, rootDataPath))
            }
        }

        val cutoff: Instant = Instant.now().minusMillis(retentionMillis)
        val orphans = scanRoots
            .flatMap { OrphanFiles.find(blobStore.list(it), knownPaths, cutoff) }
            .distinct()

        if (orphans.isEmpty() || dryRun) {
            return fileGcResult(dryRun, retention, deleted = 0, wouldDelete = orphans.size, failed = 0)
        }
        val outcome = blobStore.delete(orphans)
        return fileGcResult(
            dryRun = false,
            retention = retention,
            deleted = outcome.deleted.size,
            wouldDelete = orphans.size,
            failed = outcome.failed.size,
        )
    }

    private fun resolveOrphanScope(
        scope: OrphanScope,
        handle: DuckLakeTableHandle,
    ): Pair<List<Pair<DucklakeSchema, DucklakeTable>>, List<String>> = when (scope) {
        OrphanScope.TABLE -> {
            val schema = catalog.getSchema(handle.database, handle.snapshotId)
                ?: throw DorisConnectorException("schema not found: ${handle.database}")
            val tbl = catalog.getTable(handle.database, handle.table, handle.snapshotId)
                ?: throw DorisConnectorException("table not found: ${handle.database}.${handle.table}")
            listOf(schema to tbl) to listOf(pathResolver.resolveTableDataPath(schema, tbl))
        }
        OrphanScope.SCHEMA -> {
            val schema = catalog.getSchema(handle.database, handle.snapshotId)
                ?: throw DorisConnectorException("schema not found: ${handle.database}")
            catalog.listTables(schema.schemaId, handle.snapshotId).map { schema to it } to
                listOf(pathResolver.resolveSchemaDataPath(schema))
        }
        OrphanScope.CATALOG ->
            catalog.listSchemas(handle.snapshotId)
                .flatMap { s -> catalog.listTables(s.schemaId, handle.snapshotId).map { s to it } } to
                listOf(pathResolver.rootDataPath())
    }

    private fun parseScope(raw: String?): OrphanScope {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "table" -> OrphanScope.TABLE
            "schema", "database" -> OrphanScope.SCHEMA
            "catalog" -> OrphanScope.CATALOG
            else -> throw DorisConnectorException(
                "Invalid scope '$raw': expected 'table' (default), 'schema' (alias 'database'), or 'catalog'",
            )
        }
    }

    private fun resolveScheduledUri(path: String, isRelative: Boolean): String {
        if (!isRelative) {
            return path
        }
        val root = warehouseRoot
            ?: throw DorisConnectorException(
                "cleanup_old_files: relative scheduled path '$path' but no warehouse root " +
                    "('storage.warehouse') is configured to resolve it against",
            )
        return if (root.endsWith("/")) root + path else "$root/$path"
    }

    /** On a dry run `deleted_file_count` reports what would be deleted. */
    private fun fileGcResult(
        dryRun: Boolean,
        retention: String,
        deleted: Int,
        wouldDelete: Int,
        failed: Int,
    ): ConnectorProcedureResult {
        val row = listOf(
            dryRun.toString(),
            retention,
            (if (dryRun) wouldDelete else deleted).toString(),
            failed.toString(),
        )
        return ConnectorProcedureResult(FILE_GC_RESULT_SCHEMA, listOf(row))
    }

    private fun parseSnapshotIds(raw: String): Set<Long> {
        val ids = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                token.toLongOrNull() ?: throw DorisConnectorException(
                    "Invalid snapshot_ids element '$token': expected a bigint",
                )
            }
        if (ids.isEmpty()) {
            throw DorisConnectorException("snapshot_ids was given but empty")
        }
        return ids.toSet()
    }

    private enum class OrphanScope { TABLE, SCHEMA, CATALOG }

    /** Doris may lowercase argument keys. */
    private class CaseInsensitiveArgs(properties: Map<String, String>) {
        private val byLowerKey: Map<String, String> =
            properties.entries.associate { (k, v) -> k.lowercase(Locale.ROOT) to v }

        operator fun get(key: String): String? = byLowerKey[key.lowercase(Locale.ROOT)]
    }

    internal companion object {
        internal const val EXPIRE_SNAPSHOTS = "expire_snapshots"
        internal const val CLEANUP_OLD_FILES = "cleanup_old_files"
        internal const val REMOVE_ORPHAN_FILES = "remove_orphan_files"

        private val SUPPORTED_BY_LOWER: Map<String, String> =
            listOf(EXPIRE_SNAPSHOTS, CLEANUP_OLD_FILES, REMOVE_ORPHAN_FILES).associateBy { it }

        internal const val RETENTION_THRESHOLD = "retention_threshold"
        internal const val SNAPSHOT_IDS = "snapshot_ids"
        internal const val DRY_RUN = "dry_run"
        internal const val SCOPE = "scope"

        internal const val DEFAULT_RETENTION = "7d"

        private const val CATALOG_WIDE_SCOPE = "catalog-wide (ignores the named table)"

        private const val MILLIS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L
        private const val HOURS_PER_DAY = 24L

        private val RESULT_SCHEMA: List<ConnectorColumn> = listOf(
            column("dry_run", ConnectorType.of("BOOLEAN")),
            column("scope", ConnectorType.of("STRING")),
            column("expired_snapshot_count", ConnectorType.of("BIGINT")),
            column("scheduled_file_count", ConnectorType.of("STRING")),
            column("snapshot_ids", ConnectorType.of("STRING")),
        )

        private val FILE_GC_RESULT_SCHEMA: List<ConnectorColumn> = listOf(
            column("dry_run", ConnectorType.of("BOOLEAN")),
            column("retention_threshold", ConnectorType.of("STRING")),
            column("deleted_file_count", ConnectorType.of("BIGINT")),
            column("failed_file_count", ConnectorType.of("BIGINT")),
        )

        private fun column(name: String, type: ConnectorType): ConnectorColumn =
            ConnectorColumn(name, type, "", true, null)

        /** `<number><unit>` with a lowercase unit of `s`, `m`, `h` or `d`; anything else is rejected. */
        @Suppress("ThrowsCount")
        internal fun parseDuration(value: String, argName: String): Long {
            val trimmed = value.trim()
            val match = DURATION_PATTERN.matchEntire(trimmed)
                ?: throw DorisConnectorException(
                    "Invalid $argName '$value': expected <number><unit> where unit is one of " +
                        "s/m/h/d (e.g. 7d, 24h, 30m, 60s)",
                )
            val magnitude = match.groupValues[1].toLongOrNull()
                ?: throw DorisConnectorException("Invalid $argName '$value': magnitude out of range")
            val unitMillis = when (match.groupValues[2]) {
                "s" -> MILLIS_PER_SECOND
                "m" -> MILLIS_PER_SECOND * SECONDS_PER_MINUTE
                "h" -> MILLIS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR
                "d" -> MILLIS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR * HOURS_PER_DAY
                else -> throw DorisConnectorException("Invalid $argName '$value': unknown unit")
            }
            return try {
                Math.multiplyExact(magnitude, unitMillis)
            } catch (e: ArithmeticException) {
                throw DorisConnectorException("Invalid $argName '$value': duration overflow", e)
            }
        }

        // Multi-letter or uppercase units match here and are rejected by the `when` above.
        private val DURATION_PATTERN = Regex("""(\d+)\s*([a-zA-Z]+)""")

        /** Blank or absent is false; anything other than true/false is rejected. */
        internal fun parseBoolean(value: String?, argName: String): Boolean {
            val v = value?.trim()?.lowercase(Locale.ROOT)
            return when {
                v.isNullOrEmpty() -> false
                v == "true" -> true
                v == "false" -> false
                else -> throw DorisConnectorException(
                    "Invalid $argName '$value': expected true or false",
                )
            }
        }
    }
}
