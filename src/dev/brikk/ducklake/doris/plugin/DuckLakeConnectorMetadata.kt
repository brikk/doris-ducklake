package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeColumn
import dev.brikk.ducklake.catalog.DucklakeFilePartitionValue
import dev.brikk.ducklake.catalog.DucklakePartitionField
import dev.brikk.ducklake.catalog.DucklakePartitionTransform
import dev.brikk.ducklake.catalog.DucklakeSnapshot
import dev.brikk.ducklake.catalog.TableColumnSpec
import org.apache.doris.connector.spi.ConnectorColumn
import org.apache.doris.connector.spi.ConnectorDatabaseMetadata
import org.apache.doris.connector.spi.ConnectorMetadata
import org.apache.doris.connector.spi.ConnectorSession
import org.apache.doris.connector.spi.ConnectorTableSchema
import org.apache.doris.connector.spi.ConnectorTableStatistics
import org.apache.doris.connector.spi.DorisConnectorException
import org.apache.doris.connector.spi.ddl.ConnectorCreateTableRequest
import org.apache.doris.connector.spi.handle.ConnectorColumnHandle
import org.apache.doris.connector.spi.handle.ConnectorTableHandle
import org.apache.doris.connector.spi.handle.ConnectorTransaction
import org.apache.doris.connector.spi.mvcc.ConnectorMvccSnapshot
import org.apache.doris.connector.spi.mvcc.ConnectorTimeTravelSpec
import org.apache.doris.connector.spi.pushdown.ConnectorAnd
import org.apache.doris.connector.spi.pushdown.ConnectorColumnAssignment
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorFilterConstraint
import org.apache.doris.connector.spi.pushdown.FilterApplicationResult
import org.apache.doris.connector.spi.pushdown.ProjectionApplicationResult
import java.time.Instant
import java.util.Optional

/**
 * Schema, listing, pushdown, statistics, snapshot pinning and DDL for DuckLake. Every read resolves
 * by `(tableId, snapshotId)`; the snapshot is pinned on the [DuckLakeTableHandle] when it is created.
 */
internal class DuckLakeConnectorMetadata(
    private val catalog: DucklakeCatalog,
    private val enableTimestampTz: Boolean = false,
) : ConnectorMetadata {

    override fun listDatabaseNames(session: ConnectorSession?): List<String> =
        catalog.listSchemas(catalog.currentSnapshotId).map { it.schemaName }

    override fun databaseExists(session: ConnectorSession?, database: String): Boolean =
        catalog.getSchema(database, catalog.currentSnapshotId) != null

    override fun getDatabase(session: ConnectorSession?, database: String): ConnectorDatabaseMetadata {
        val schema = catalog.getSchema(database, catalog.currentSnapshotId)
            ?: throw IllegalArgumentException("database '$database' not found in DuckLake catalog")
        return ConnectorDatabaseMetadata(schema.schemaName, emptyMap())
    }

    override fun listTableNames(session: ConnectorSession?, database: String): List<String> {
        val snapshotId = catalog.currentSnapshotId
        val schema = catalog.getSchema(database, snapshotId) ?: return emptyList()
        return catalog.listTables(schema.schemaId, snapshotId).map { it.tableName }
    }

    override fun getTableHandle(
        session: ConnectorSession?,
        database: String,
        table: String,
    ): Optional<ConnectorTableHandle> {
        val snapshotId = catalog.currentSnapshotId
        val schema = catalog.getSchema(database, snapshotId) ?: return Optional.empty()
        val ducklakeTable = catalog.getTable(database, table, snapshotId) ?: return Optional.empty()
        return Optional.of(DuckLakeTableHandle(database, table, schema.schemaId, ducklakeTable.tableId, snapshotId))
    }

    override fun getTableSchema(
        session: ConnectorSession?,
        tableHandle: ConnectorTableHandle,
    ): ConnectorTableSchema {
        val handle = tableHandle.asDuckLakeHandle<DuckLakeTableHandle>()
        val columns = loadTopLevelColumns(handle).map { col ->
            ConnectorColumn(
                col.columnName,
                DuckLakeTypeMapping.fromDucklakeType(col.columnType, enableTimestampTz),
                "", // DuckLake has no per-column comments
                col.nullsAllowed,
                backfillDefaultValue(col),
            )
        }
        return ConnectorTableSchema(handle.table, columns, DuckLakeConnectorProvider.TYPE, emptyMap())
    }

    override fun getColumnHandles(
        session: ConnectorSession?,
        tableHandle: ConnectorTableHandle,
    ): Map<String, ConnectorColumnHandle> {
        val handle = tableHandle.asDuckLakeHandle<DuckLakeTableHandle>()
        // Insertion order matters: the planner iterates this map for DESC and SELECT * ordering.
        val out = LinkedHashMap<String, ConnectorColumnHandle>()
        loadTopLevelColumns(handle).forEachIndexed { ordinal, col ->
            out[col.columnName] = DuckLakeColumnHandle(
                col.columnId,
                col.columnName,
                DuckLakeTypeMapping.fromDucklakeType(col.columnType, enableTimestampTz),
                ordinal,
            )
        }
        return out
    }

    // ---- Pushdown: projection ----

    override fun applyProjection(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        projections: List<ConnectorColumnHandle>,
    ): Optional<ProjectionApplicationResult<ConnectorTableHandle>> {
        if (projections.isEmpty()) {
            return Optional.empty()
        }
        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()
        val projectedIds = projections.mapNotNull { (it as? DuckLakeColumnHandle)?.columnId }
        if (projectedIds.size != projections.size) {
            return Optional.empty() // a foreign handle: opt out rather than guess
        }
        // The engine applies to a fixed point; returning empty when nothing changes ends the loop.
        if (dlHandle.projectedColumnIds == projectedIds) {
            return Optional.empty()
        }

        val outProjections = ArrayList<ConnectorExpression>(projections.size)
        val outAssignments = ArrayList<ConnectorColumnAssignment>(projections.size)
        for (col in projections) {
            val ch = col as DuckLakeColumnHandle
            val ref = ConnectorColumnRef(ch.columnName, ch.columnType)
            outProjections.add(ref)
            outAssignments.add(ConnectorColumnAssignment(col, ref))
        }
        val newHandle = dlHandle.copy(projectedColumnIds = projectedIds)
        return Optional.of(ProjectionApplicationResult(newHandle, outProjections, outAssignments))
    }

    // ---- Pushdown: file-level pruning ----

    override fun applyFilter(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        constraint: ConnectorFilterConstraint,
    ): Optional<FilterApplicationResult<ConnectorTableHandle>> {
        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()
        if (dlHandle.pushedFilter != null) {
            return Optional.empty() // already applied on a prior fixed-point iteration
        }
        val filter = constraint.expression ?: return Optional.empty()

        val columns = catalog.getTableColumns(dlHandle.tableId, dlHandle.snapshotId)

        // Two prunings, intersected: column-stats ranges (non-partition columns plus identity/temporal
        // partitions, whose source column carries stats) and bucket matching, which stats cannot do
        // because each bucket file holds a scattered value range.
        //
        // TIMESTAMP_NS is exposed as microsecond DATETIMEV2, so a microsecond equality point is not a
        // nanosecond point in the file's domain; keep those files and let the BE filter.
        val statsColumns = columns.filterNot { it.columnType.equals("timestamp_ns", ignoreCase = true) }
        val statsKept = statsPrune(dlHandle, filter, statsColumns.associate { it.columnName to it.columnId })
        val bucketKept = bucketPrune(dlHandle, filter, columns)

        val prunedFileIds = intersectNullable(statsKept, bucketKept)
            ?: return Optional.empty() // nothing pushable (functions / LIKE / OR only)

        val newHandle = dlHandle.copy(pushedFilter = filter, prunedFileIds = prunedFileIds)
        // The BE re-evaluates the full predicate, so the whole expression is reported as unenforced.
        return Optional.of(FilterApplicationResult(newHandle, filter, false))
    }

    /** A file survives only if its column stats overlap every pushed range. */
    private fun statsPrune(
        handle: DuckLakeTableHandle,
        filter: ConnectorExpression,
        columnIdByName: Map<String, Long>,
    ): Set<Long>? {
        val rangePredicates = DuckLakePredicateConverter.toColumnRangePredicates(filter, columnIdByName)
        if (rangePredicates.isEmpty()) {
            return null
        }
        var kept: Set<Long>? = null
        for (pred in rangePredicates) {
            val ids = catalog.findDataFileIdsInRange(handle.tableId, handle.snapshotId, pred).toHashSet()
            kept = kept?.intersect(ids) ?: ids
        }
        return kept
    }

    /**
     * For a membership constraint (`col = x`, `col IN (…)`, same-column `col = a OR col = b`) on a bucket
     * field, keep only files whose stored bucket is one of `{ bucket(v) : v ∈ candidates }`.
     */
    private fun bucketPrune(
        handle: DuckLakeTableHandle,
        filter: ConnectorExpression,
        columns: List<DucklakeColumn>,
    ): Set<Long>? {
        val spec = catalog.getPartitionSpecs(handle.tableId, handle.snapshotId).singleOrNull()
            ?: return null
        val bucketFields = spec.fields.filter { it.transform == DucklakePartitionTransform.BUCKET }
        if (bucketFields.isEmpty()) {
            return null
        }
        val candidates = membershipCandidates(filter)
        val nameById = columns.associate { it.columnId to it.columnName }
        val targetBuckets = bucketFields
            .mapNotNull { field -> targetBucketsFor(field, nameById, candidates) }
            .toMap()
        if (targetBuckets.isEmpty()) {
            return null
        }
        val valuesByFileId = catalog.getFilePartitionValues(handle.tableId, handle.snapshotId)
        // Key indexes belong to a spec, so files written under another spec are kept unconditionally.
        return catalog.getDataFiles(handle.tableId, handle.snapshotId)
            .filter { file ->
                file.partitionId != spec.partitionId ||
                    fileMatchesBuckets(valuesByFileId[file.dataFileId].orEmpty(), targetBuckets)
            }
            .mapTo(HashSet()) { it.dataFileId }
    }

    /** Null when any candidate can't be hashed: an unknown bucket means no bucket can be excluded. */
    private fun targetBucketsFor(
        field: DucklakePartitionField,
        nameById: Map<Long, String>,
        candidates: Map<String, List<Any>>,
    ): Pair<Int, Set<Int>>? {
        val arity = field.arity ?: return null
        val values = nameById[field.columnId]?.let { candidates[it] } ?: return null
        val buckets = values.map { DuckLakeBucketTransform.bucket(it, arity) ?: return null }.toSet()
        return field.partitionKeyIndex to buckets
    }

    private fun fileMatchesBuckets(
        values: List<DucklakeFilePartitionValue>,
        targetBuckets: Map<Int, Set<Int>>,
    ): Boolean = targetBuckets.all { (keyIndex, targets) ->
        val pv = values.find { it.partitionKeyIndex == keyIndex }?.partitionValue
        pv == null || pv.toIntOrNull() in targets
    }

    /** Candidate values per column, unioned across conjuncts. A union can only widen, never wrongly narrow. */
    private fun membershipCandidates(filter: ConnectorExpression): Map<String, List<Any>> {
        val conjuncts = if (filter is ConnectorAnd) filter.conjuncts else listOf(filter)
        val byColumn = LinkedHashMap<String, MutableList<Any>>()
        conjuncts.mapNotNull { DuckLakeMembership.of(it) }
            .forEach { (column, values) -> byColumn.getOrPut(column) { ArrayList() }.addAll(values) }
        return byColumn.mapValues { (_, values) -> values.distinct() }
    }

    private fun intersectNullable(a: Set<Long>?, b: Set<Long>?): Set<Long>? = when {
        a == null -> b
        b == null -> a
        else -> a.intersect(b)
    }

    // ---- Statistics ----

    override fun getTableStatistics(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
    ): Optional<ConnectorTableStatistics> {
        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()
        return statsAtSnapshot(dlHandle.tableId, dlHandle.snapshotId)
    }

    /**
     * fe-core passes the pinned snapshot separately for a time-travel read while `handle` is still
     * at the current snapshot; using `snapshot` keeps CBO cardinality aligned with what the scan reads.
     */
    override fun getTableStatistics(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        snapshot: ConnectorMvccSnapshot,
    ): Optional<ConnectorTableStatistics> {
        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()
        return statsAtSnapshot(dlHandle.tableId, snapshot.snapshotId)
    }

    /**
     * Current snapshot: the whole-table `ducklake_table_stats` row. Historical: summed over the files
     * live at that snapshot. Neither subtracts position deletes or counts inlined rows; the planner
     * applies filter selectivity on top.
     */
    private fun statsAtSnapshot(tableId: Long, snapshotId: Long): Optional<ConnectorTableStatistics> {
        if (snapshotId == catalog.currentSnapshotId) {
            val stats = catalog.getTableStats(tableId) ?: return Optional.empty()
            return Optional.of(ConnectorTableStatistics(stats.recordCount, stats.fileSizeBytes))
        }
        val files = catalog.getDataFiles(tableId, snapshotId)
        if (files.isEmpty()) return Optional.empty()
        return Optional.of(
            ConnectorTableStatistics(files.sumOf { it.recordCount }, files.sumOf { it.fileSizeBytes }),
        )
    }

    // ---- MVCC snapshot pinning and time travel ----

    override fun beginQuerySnapshot(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
    ): Optional<ConnectorMvccSnapshot> =
        Optional.of(toMvccSnapshot(handle.asDuckLakeHandle<DuckLakeTableHandle>().snapshotId))

    /**
     * DuckLake has a single linear history identified by numeric snapshot id, so only SNAPSHOT_ID and
     * TIMESTAMP resolve. Named refs, tags, branches and incremental scans return empty and the engine
     * reports them as unsupported.
     */
    override fun resolveTimeTravel(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        spec: ConnectorTimeTravelSpec,
    ): Optional<ConnectorMvccSnapshot> {
        val snap: DucklakeSnapshot? = when (spec.getKind()) {
            ConnectorTimeTravelSpec.Kind.SNAPSHOT_ID ->
                catalog.getSnapshot(spec.getStringValue().toLong())
            ConnectorTimeTravelSpec.Kind.TIMESTAMP ->
                catalog.getSnapshotAtOrBefore(Instant.ofEpochMilli(timestampMillisOf(spec)))
            else -> null
        }
        return if (snap == null) Optional.empty() else Optional.of(toMvccSnapshot(snap.snapshotId))
    }

    /** A digital value is raw epoch millis; anything else is an ISO-8601 instant. */
    private fun timestampMillisOf(spec: ConnectorTimeTravelSpec): Long =
        if (spec.isDigital()) {
            spec.getStringValue().toLong()
        } else {
            Instant.parse(spec.getStringValue()).toEpochMilli()
        }

    private fun toMvccSnapshot(snapshotId: Long): ConnectorMvccSnapshot =
        ConnectorMvccSnapshot.builder().snapshotId(snapshotId).build()

    /**
     * Re-stamps the resolved snapshot onto the handle before `planScan`, so the whole read path
     * resolves at that snapshot. A null snapshot or negative id (empty table / invalid pin) leaves
     * the handle at latest.
     */
    override fun applySnapshot(
        session: ConnectorSession?,
        handle: ConnectorTableHandle,
        snapshot: ConnectorMvccSnapshot?,
    ): ConnectorTableHandle {
        val dlHandle = handle.asDuckLakeHandle<DuckLakeTableHandle>()
        if (snapshot == null || snapshot.snapshotId < 0 || snapshot.snapshotId == dlHandle.snapshotId) {
            return dlHandle
        }
        return dlHandle.copy(snapshotId = snapshot.snapshotId)
    }

    // ---- Writes and DDL ----
    // The FE resolves IF [NOT] EXISTS before calling these, so they act unconditionally and let the
    // catalog throw on a real conflict. Each catalog op is its own atomic snapshot.

    override fun beginTransaction(session: ConnectorSession?): ConnectorTransaction {
        val transactionId = requireNotNull(session) {
            "beginTransaction requires a session to allocate the transaction id"
        }.allocateTransactionId()
        return DuckLakeConnectorTransaction(transactionId, catalog)
    }

    override fun createDatabase(session: ConnectorSession?, database: String, properties: Map<String, String>?) {
        catalog.createSchema(database)
    }

    override fun dropDatabase(
        session: ConnectorSession?,
        database: String,
        ifExists: Boolean,
        force: Boolean,
    ) {
        if (ifExists && catalog.getSchema(database, catalog.currentSnapshotId) == null) {
            return
        }
        // The catalog never cascades: it refuses to drop a schema that still has tables. Rejecting an
        // explicit FORCE is better than silently performing a non-cascading drop.
        if (force) {
            throw DorisConnectorException(
                "DROP DATABASE ... FORCE (CASCADE) is not supported for DuckLake catalogs; " +
                    "drop the tables first, then DROP DATABASE.",
            )
        }
        catalog.dropSchema(database)
    }

    override fun createTable(session: ConnectorSession?, request: ConnectorCreateTableRequest) {
        val partitionFields = DuckLakeCreatePartitionMapper.toPartitionFields(
            request.partitionSpec,
            request.bucketSpec,
        )
        val columns = request.columns.map { column ->
            TableColumnSpec(
                column.name,
                DuckLakeCreateTableMapper.toDucklakeType(column.type),
                column.isNullable,
                emptyList(), // scalar columns only; the type mapper rejects nested types
            )
        }
        catalog.createTable(request.dbName, request.tableName, columns, partitionFields, null)
    }

    override fun dropTable(session: ConnectorSession?, tableHandle: ConnectorTableHandle) {
        val handle = tableHandle.asDuckLakeHandle<DuckLakeTableHandle>()
        catalog.dropTable(handle.database, handle.table)
    }

    /** Nested-field rows (struct/list/map children) are skipped: the parent's type string carries their shape. */
    private fun loadTopLevelColumns(handle: DuckLakeTableHandle): List<DucklakeColumn> {
        val rows = catalog.getTableColumns(handle.tableId, handle.snapshotId)
        // Checked before FE constant folding, which could otherwise turn a comparison against an
        // unrepresentable temporal value into a silent empty result.
        DuckLakeTemporalDomain.checkKnownBounds(catalog, handle, rows)
        return rows.filter { it.parentColumn == null }.sortedBy { it.columnOrder }
    }

    internal companion object {
        private val BINARY_SPATIAL_TYPES = setOf("blob", "uuid", "geometry", "geography")

        /**
         * The value a column projects for rows in files written before the column existed
         * (`ALTER TABLE ADD COLUMN b INT DEFAULT 42` → old rows read 42). The FE re-parses and casts
         * this eagerly at plan time, so an unparseable value would break the whole table scan; it is
         * therefore surfaced only for types whose DuckDB rendering is a safe Doris literal. A skipped
         * default degrades to NULL on old rows.
         */
        internal fun backfillDefaultValue(col: DucklakeColumn): String? {
            val raw = col.initialDefault ?: return null
            return if (isBackfillDefaultSafe(col.columnType)) raw else null
        }

        internal fun isBackfillDefaultSafe(ducklakeType: String): Boolean {
            val t = ducklakeType.trim().lowercase()
            if (t.startsWith("list<") || t.startsWith("struct<") || t.startsWith("map<")) {
                return false
            }
            return t !in BINARY_SPATIAL_TYPES
        }
    }
}
