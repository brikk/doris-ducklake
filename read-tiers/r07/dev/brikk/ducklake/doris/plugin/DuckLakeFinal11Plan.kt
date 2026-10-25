package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn
import dev.brikk.ducklake.catalog.DucklakeDataFile
import dev.brikk.ducklake.catalog.DucklakeFileKind
import dev.brikk.ducklake.catalog.DucklakeInlinedDataInfo
import dev.brikk.ducklake.catalog.DucklakeOwnedFile
import dev.brikk.ducklake.catalog.DucklakePartitionSpec
import dev.brikk.ducklake.catalog.DucklakeReadSessionControl
import dev.brikk.ducklake.catalog.DucklakeSchema
import dev.brikk.ducklake.catalog.DucklakeSnapshot
import dev.brikk.ducklake.catalog.DucklakeTable
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import java.util.function.Supplier

/** All SQL planning inputs are captured together, before publishing any view/range. */
internal class DuckLakeFinal11Plan private constructor(
    private val snapshot: DucklakeSnapshot,
    private val root: String,
    private val schemas: List<DucklakeSchema>,
    private val tables: Map<Long, Table>,
    private val budget: DuckLakeReadBudget,
) {
    private var frames: Map<Long, DuckLakeFinal11Plan> = mapOf(snapshot.snapshotId to this)
    private var firstSnapshot = snapshot.snapshotId
    private val schemaByName = schemas.associateBy { key(it.schemaName) }
    private val tablesBySchema = tables.values.groupBy { it.metadata.schemaId }
        .mapValues { java.util.List.copyOf(it.value.map { t -> t.metadata }) }
    private val tableByName = tables.values.associateBy { it.metadata.schemaId to key(it.metadata.tableName) }
    private val byFile = tables.values.flatMap { table -> table.paths.keys.map { it to table } }.toMap()

    fun requireFrame(id: Long) { check(id in frames) { "Snapshot $id is absent/outside the bounded final-1.1 historical window" } }

    fun dispatch(name: String, args: Array<out Any?>, selected: Long?): Any? {
        if (name == "getCurrentSnapshotId") { budget.step(); return snapshot.snapshotId }
        if (name == "getSnapshot") {
            budget.step()
            val id = args[0] as Long
            check(id >= firstSnapshot) { "Snapshot $id is outside the bounded final-1.1 historical window" }
            return frames[id]?.snapshot
        }
        val index = when (name) {
            "listSchemas" -> 0
            "getTable", "qualifiedColumnDefaultSql" -> 2
            "getSchema", "listTables", "getTableById", "getTableColumns", "getAllColumnsWithParentage",
            "getDataFiles", "getInlinedDataInfos", "hasInlinedDeletes", "getPartitionSpecs" -> 1
            else -> -1
        }
        val requested = if (index >= 0) args[index] as Long else selected ?: snapshot.snapshotId
        if (index >= 0 && selected != null) check(requested == selected) { "Mixed snapshot inputs in a pinned scan" }
        val frame = frames[requested] ?: error("Snapshot $requested is absent/outside the bounded historical plan")
        return frame.invoke(name, args)
    }
    internal data class Table(
        val metadata: DucklakeTable,
        val columns: List<DucklakeColumn>,
        val files: List<DucklakeDataFile>,
        val infos: List<DucklakeInlinedDataInfo>,
        val specs: List<DucklakePartitionSpec>,
        val paths: Map<Long, String>,
        val deletes: Map<Long, List<DuckLakePositionDelete>>,
        val unsupported: String?,
        val defaultSql: Map<Long, String> = emptyMap(),
    )

    fun invoke(name: String, args: Array<out Any?>): Any? {
        budget.step()
        return when (name) {
            "getSpecVersion" -> "1.1"
            "getCurrentSnapshotId" -> snapshot.snapshotId
            "getDataPath" -> root
            "readSession" -> (args[0] as Supplier<*>).get() // A detached immutable snapshot, not a new SQL transaction.
            "getSnapshot" -> {
                val id = args[0] as Long
                if (id > snapshot.snapshotId) null else { at(id); snapshot }
            }
            "getSnapshotAtOrBefore" -> error("Final-1.1 timestamp time travel is not qualified; use numeric snapshots within the configured window")
            "close" -> null
            "toString" -> "DuckLakeFinal11Plan(snapshot=${snapshot.snapshotId},tables=${tables.size})"
            else -> lookup(name, args)
        }
    }

    private fun lookup(name: String, args: Array<out Any?>): Any? = when (name) {
        "getTableById" -> { at(args[1]); tables[args[0]]?.metadata }
        "getTableColumns", "getAllColumnsWithParentage" -> { at(args[1]); table(args[0]).columns }
        "getDataFiles" -> { at(args[1]); table(args[0]).files }
        "getInlinedDataInfos" -> { at(args[1]); table(args[0]).infos }
        "hasInlinedDeletes" -> { at(args[1]); table(args[0]); false }
        "getPartitionSpecs" -> { at(args[1]); table(args[0]).specs }
        "qualifiedDataPath" -> plannedFile(args[0]).paths[args[0]]!!
        "qualifiedPositionDeletes" -> plannedFile(args[0]).deletes[args[0]].orEmpty()
        "qualifiedColumnDefaultSql" -> { at(args[2]); table(args[0]).defaultSql[args[1]] }
        else -> namespace(name, args)
    }

    private fun namespace(name: String, args: Array<out Any?>): Any? = when (name) {
        "listSchemas" -> { at(args[0]); schemas }
        "getSchema" -> { at(args[1]); schemaByName[key(args[0] as String)] }
        "listTables" -> { at(args[1]); tablesBySchema[args[0]].orEmpty() }
        "getTable" -> {
            at(args[2])
            val schema = schemaByName[key(args[0] as String)]
            tableByName[schema?.schemaId to key(args[1] as String)]?.metadata
        }
        else -> error("Operation $name is unavailable on read-only final-1.1 statement metadata")
    }

    private fun table(id: Any?): Table = (tables[id] ?: error("Missing planned table $id")).also {
        check(it.unsupported == null) { it.unsupported!! }
    }

    private fun plannedFile(id: Any?): Table = (byFile[id] ?: error("Missing planned file")).also {
        check(it.unsupported == null) { it.unsupported!! }
    }

    private fun at(id: Any?) {
        check(id == snapshot.snapshotId) {
            "Final-1.1 historical planning requires a separate qualified snapshot plan and per-row filters; requested $id, captured ${snapshot.snapshotId}"
        }
    }

    companion object {
        private fun key(name: String) = name.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")

        fun capture(
            c: JdbcDucklakeCatalog, properties: Map<String, String>, objects: DuckLakeReadObjects,
            control: DucklakeReadSessionControl? = null,
        ): DuckLakeFinal11Plan {
            val budget = DuckLakeReadBudget(properties)
            val owned = control ?: DucklakeReadSessionControl(budget.queryTimeoutSeconds)
            require(owned.timeoutSeconds <= budget.queryTimeoutSeconds) { "Read control cannot extend the planning deadline" }
            return c.readSession(owned, Supplier {
                val snapshot = header(c, budget)
                // The new library checks physical planning types once in this owned
                // context: up to257 layout rows plus257 PostgreSQL dimension rows.
                // Reserve that internal work too, before the first planning API call.
                budget.step(514)
                val capture = Capture(c, snapshot, budget, DuckLakeFinal11Files(objects, budget))
                val current = capture.build()
                val start = if (budget.historyWindow == 0) snapshot.snapshotId else maxOf(0L, snapshot.snapshotId - budget.historyWindow + 1)
                val frames = linkedMapOf(snapshot.snapshotId to current)
                for (id in start until snapshot.snapshotId) {
                    budget.step()
                    c.getSnapshot(id)?.let { frames[id] = capture.at(it).build() }
                }
                budget.finish()
                current.frames = frames.toMap()
                current.firstSnapshot = start
                current
            })
        }

        private fun header(c: JdbcDucklakeCatalog, budget: DuckLakeReadBudget): DucklakeSnapshot {
            budget.step()
            val current = c.currentSnapshotId
            val snapshot = c.getSnapshot(current) ?: error("Missing current snapshot")
            val profile = c.readBackendProfile()
            check(profile.formatVersion == "1.1" && profile.snapshotId == current && !profile.qualifiedWrites) { "Invalid reader profile" }
            check(profile.metadataBackend == "postgresql") { "Qualified PostgreSQL profile required" }
            check(profile.sqlEngineSourceId == null && profile.ducklakeExtensionVersion == null && profile.quackExtensionVersion == null) {
                "Unavailable SQL-server provenance must stay NULL"
            }
            check(profile.unsignedExtensionsAllowed == null && profile.richMetadataAvailable && profile.hierarchyAvailable) {
                "Invalid SQL-server capabilities"
            }
            check(!c.isEncrypted()) { "Encrypted final-1.1 files are not qualified" }
            check(snapshot.schemaVersion >= 0) { "Negative schema version" }
            return snapshot
        }
    }

    private class Capture(
        private val c: JdbcDucklakeCatalog, private val snapshot: DucklakeSnapshot,
        private val budget: DuckLakeReadBudget, private val inspector: DuckLakeFinal11Files,
        private val retainedFiles: Pair<Map<Long, DucklakeOwnedFile>, Map<Long, DucklakeOwnedFile>>? = null,
        private val shapes: MutableMap<Long, Shape> = linkedMapOf(),
        private val partitions: MutableSet<List<Pair<Long, Long?>>> = hashSetOf(),
        private val emptyPositionsThrough: MutableMap<Long, Long> = linkedMapOf(),
    ) {
        private data class Shape(
            val schemas: List<DucklakeSchema>, val tables: List<DucklakeTable>,
            val columns: Map<Long, List<DucklakeColumn>>, val specs: Map<Long, List<DucklakePartitionSpec>>,
        )
        private val hierarchy = c.readHierarchy()
        private val data = retainedFiles?.first ?: retained(DucklakeFileKind.DATA)
        private val deletes = retainedFiles?.second ?: retained(DucklakeFileKind.DELETE)
        private val current = snapshot.snapshotId
        private val pages = DuckLakePlanningPages(c, current, budget)

        fun at(snapshot: DucklakeSnapshot): Capture = Capture(
            c, snapshot, budget, inspector, data to deletes, shapes, partitions, emptyPositionsThrough,
        )

        fun build(): DuckLakeFinal11Plan {
            // Schema-version coordinates are the catalog's immutable schema dictionary.
            // Only COMPLETE validated shapes are shared, within this one owned capture.
            val shape = shapes.getOrPut(snapshot.schemaVersion) {
                val schemas = pages.schemas()
                val tables = currentTables(schemas)
                val columns = tables.associate { it.tableId to pages.columns(it.tableId) }
                val specs = tables.associate { it.tableId to pages.specs(it.tableId, columns.getValue(it.tableId)) }
                Shape(schemas, tables, columns, specs)
            }
            val plans = shape.tables.associate { it.tableId to table(it, shape) }
            val root = c.getDataPath() ?: error("Missing stored catalog data path")
            budget.literal(root)
            budget.step(plans.values.sumOf { it.paths.size })
            return DuckLakeFinal11Plan(snapshot, root, java.util.List.copyOf(shape.schemas), plans, budget)
        }

        private fun currentTables(schemas: List<DucklakeSchema>): List<DucklakeTable> {
            val tables = mutableListOf<DucklakeTable>()
            for (schema in schemas) {
                budget.step()
                tables.addAll(pages.tables(schema.schemaId, budget.maxTables - tables.size))
                check(tables.size <= budget.maxTables) { "Final-1.1 total table budget exceeded" }
            }
            check(tables.map { it.tableId }.distinct().size == tables.size) { "Duplicate table identity" }
            return tables
        }

        private fun retained(kind: DucklakeFileKind): Map<Long, DucklakeOwnedFile> {
            var cursor: Long? = null
            val files = linkedMapOf<Long, DucklakeOwnedFile>()
            while (true) {
                budget.page()
                val page = hierarchy.listRetainedFiles(kind, cursor, budget.pageSize)
                check(page.size <= budget.pageSize) { "Oversized companion page" }
                if (page.isEmpty()) return files.toMap()
                for (file in page) {
                    check(file.fileId > (cursor ?: -1) && files.put(file.fileId, file) == null) { "Non-progressing/duplicate companion cursor" }
                    budget.path(file.resolvedPath)
                    budget.step(file.owner.schema.schemaIds.size)
                    cursor = file.fileId
                }
            }
        }

        private fun table(table: DucklakeTable, shape: Shape): Table {
            budget.step()
            val columns = shape.columns.getValue(table.tableId)
            val infos = pages.inline(table.tableId)
            // A COMPLETE empty [0,S] probe also proves any narrower [0,older] empty
            // in this same owned view. Never share a positive probe or failed layout.
            val inlineDeletes = if ((emptyPositionsThrough[table.tableId] ?: -1) >= current) false else {
                pages.hasInlineDeletes(table.tableId).also { if (!it) emptyPositionsThrough[table.tableId] = current }
            }
            val entries = pages.files(table.tableId)
            val files = entries.map { it.dataFile }
            files.mapNotNull { it.mappingId }.distinct().forEach(pages::mapping)
            val unsupported = inspector.unsupported(columns, infos.any { it.hasLiveRows }, inlineDeletes)
                ?: if (files.any { it.mappingId != null }) "Final-1.1 add_files/Hive name mappings are not qualified" else null
            val specs = shape.specs.getValue(table.tableId)
            val partitionKey = files.map { it.dataFileId to it.partitionId }
            // Partition values are unversioned facts of immutable DATA identities.
            // Empty active-file enumeration proves the joined value relation empty.
            if (files.isNotEmpty() && partitionKey !in partitions) {
                pages.partitionValues(table.tableId, entries, specs)
                partitions.add(partitionKey)
            }
            val metadata = entries.associate { it.cursor.dataFileId to it.cursor.deleteFileId }
            val copiedSpecs = java.util.List.copyOf(specs.map { it.copy(fields = java.util.List.copyOf(it.fields)) })
            val plan = Table(table, java.util.List.copyOf(columns), java.util.List.copyOf(files), java.util.List.copyOf(infos),
                copiedSpecs, emptyMap(), emptyMap(), unsupported)
            return try { physical(plan, metadata) } catch (boundary: DuckLakeUnsupportedRead) {
                plan.copy(unsupported = boundary.message ?: "Unqualified physical file semantics")
            }
        }

        private fun physical(plan: Table, metadata: Map<Long, Long?>): Table {
            val paths = linkedMapOf<Long, String>()
            val positions = linkedMapOf<Long, List<DuckLakePositionDelete>>()
            val defaults = linkedMapOf<Long, String>()
            for (file in plan.files) {
                budget.step()
                val owner = data[file.dataFileId] ?: error("Missing retained DATA ownership")
                active(owner, plan.metadata.tableId)
                paths[file.dataFileId] = owner.resolvedPath
                if (plan.unsupported == null) {
                    inspector.data(file, owner.resolvedPath, plan.columns, current) { column ->
                        defaults.getOrPut(column.columnId) {
                            // The public API reloads target schema/history. Reserve work for that
                            // bounded materialization too; this is not a new paged/lazy interface.
                            budget.step(plan.columns.size * 2 + 1)
                            DuckLakeFileDefaults.sql(column, c.getMissingFileColumnDefaultDecoded(
                                plan.metadata.tableId, column.columnId, current,
                            )).also { budget.literal(it) }
                        }
                    }
                    positions[file.dataFileId] = positionDeletes(file, metadata.getValue(file.dataFileId))
                }
            }
            return plan.copy(paths = paths.toMap(), deletes = positions.toMap(), defaultSql = defaults.toMap())
        }

        private fun positionDeletes(file: DucklakeDataFile, id: Long?): List<DuckLakePositionDelete> {
            if (id == null) { check(file.deleteFilePath == null) { "Incomplete delete association" }; return emptyList() }
            val delete = deletes[id] ?: error("Missing retained DELETE ownership")
            active(delete, file.tableId)
            check(delete.path == file.deleteFilePath && delete.pathIsRelative == file.deleteFilePathIsRelative) { "Invalid delete path association" }
            return inspector.deletes(delete.resolvedPath, file.deleteFileFormat ?: error("Missing delete format"), current)
        }

        private fun active(owner: DucklakeOwnedFile, tableId: Long) {
            check(owner.owner.table.tableId == tableId) { "Invalid retained table ownership" }
            check(owner.beginSnapshot <= current && (owner.endSnapshot == null || owner.endSnapshot!! > current)) { "Inactive retained file" }
        }
    }
}
