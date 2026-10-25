package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeColumn
import dev.brikk.ducklake.catalog.DucklakeDataFileCursor
import dev.brikk.ducklake.catalog.DucklakeDataFilePageEntry
import dev.brikk.ducklake.catalog.DucklakeFilePartitionCursor
import dev.brikk.ducklake.catalog.DucklakeInlinedDataInfo
import dev.brikk.ducklake.catalog.DucklakeNameMappingRow
import dev.brikk.ducklake.catalog.DucklakePartitionFieldCursor
import dev.brikk.ducklake.catalog.DucklakePartitionSpec
import dev.brikk.ducklake.catalog.DucklakeSchema
import dev.brikk.ducklake.catalog.DucklakeTable
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog

/** SQL-bounded transfers into a bounded FULL view. Never publish a partial hierarchy. */
internal class DuckLakePlanningPages(
    private val catalog: JdbcDucklakeCatalog, private val snapshot: Long, private val budget: DuckLakeReadBudget,
) {
    private val metadata = catalog.readPlanningMetadata()

    // The owned legacy-DTO 1.1 session rejects ANY nested-schema history before
    // these pages run; never infer root parentage from a DTO that omits parents.
    fun schemas(): List<DucklakeSchema> = longs(budget.maxSchemas,
        { after, size -> metadata.listSchemasPage(snapshot, after, size) }, { it.schemaId }) {
        check(it.schemaName.isNotEmpty()) { "Empty schema name" }
        text(it.schemaName, it.schemaUuid.toString(), it.path)
    }.also { rows -> check(rows.map { key(it.schemaName) }.distinct().size == rows.size) { "Ambiguous schema names" } }

    fun tables(schema: Long, remaining: Int): List<DucklakeTable> = longs(remaining,
        { after, size -> metadata.listTablesPage(schema, snapshot, after, size) }, { it.tableId }) {
        check(it.schemaId == schema) { "Wrong table scope" }
        check(it.tableName.isNotEmpty()) { "Empty table name" }
        text(it.tableName, it.tableUuid.toString(), it.path)
    }.also { rows -> check(rows.map { key(it.tableName) }.distinct().size == rows.size) { "Ambiguous table names" } }

    fun columns(table: Long): List<DucklakeColumn> = longs(1024,
        { after, size -> metadata.getColumnsPage(table, snapshot, after, size) }, { it.columnId }) {
        check(it.tableId == table && it.columnId in 0..Int.MAX_VALUE.toLong()) { "Invalid native field identity" }
        text(it.columnName, it.columnType, it.initialDefault)
    }.also { rows ->
        tree(rows, { it.columnId }, { it.parentColumn }, { it.columnName })
        check(rows.map { it.parentColumn to it.columnOrder }.distinct().size == rows.size) { "Ambiguous column order" }
    }.sortedBy { it.columnOrder }.let { java.util.List.copyOf(it) }

    fun inline(table: Long): List<DucklakeInlinedDataInfo> = longs(100_000,
        { after, size -> metadata.getInlineVersionsPage(table, snapshot, after, size) }, { it.schemaVersion }) {
        check(it.tableId == table) { "Wrong inline registry scope" }
        text(it.tableName)
        // Charge bounded layout/liveness probes as well as returned registry nodes.
        budget.step(10)
    }

    fun hasInlineDeletes(table: Long): Boolean {
        // Existence/refusal only, NOT an enumeration or a file-local position plan.
        // Empty is terminal; one validated row suffices to refuse the whole table.
        budget.page()
        budget.step(10) // Bounded physical-layout and visible-snapshot preflights.
        val page = catalog.getInlinedFileDeletesBetweenPage(table, 0, snapshot, null, 1)
        check(page.size <= 1) { "Oversized inline-delete probe" }
        if (page.isNotEmpty()) return true
        // The windowed API intentionally excludes negative begin snapshots. Keep
        // the bounded legacy EXISTS as a conservative refusal witness for those
        // malformed older rows too; an empty valid window must not authorize them.
        budget.step(3)
        return catalog.hasInlinedDeletes(table, snapshot)
    }

    fun files(table: Long): List<DucklakeDataFilePageEntry> = drain(4096,
        { after, size -> catalog.getDataFilesPage(table, snapshot, after, size) }, { it.cursor },
        compareBy<DucklakeDataFileCursor> { it.dataFileId }.thenBy { it.deleteFileId ?: -1L }) {
        val f = it.dataFile
        check(f.tableId == table && f.dataFileId == it.cursor.dataFileId) { "Wrong active file scope" }
        text(f.path, f.fileFormat, f.deleteFilePath, f.deleteFileFormat)
    }.also { rows -> check(rows.map { it.dataFile.dataFileId }.distinct().size == rows.size) { "Duplicate active DATA identity" } }

    fun specs(table: Long, columns: List<DucklakeColumn>): List<DucklakePartitionSpec> {
        val fields = drain(64, { after, size -> metadata.getPartitionFieldsPage(table, snapshot, after, size) }, { it.cursor },
            compareBy<DucklakePartitionFieldCursor> { it.partitionId }.thenBy { it.partitionKeyIndex }) {
            check(it.tableId == table && columns.any { c -> c.columnId == it.field.columnId }) { "Invalid partition field parent" }
        }
        return fields.groupBy { it.cursor.partitionId }.map { (id, rows) ->
            check(rows.map { it.field.partitionKeyIndex } == rows.indices.toList()) { "Incomplete partition spec" }
            DucklakePartitionSpec(id, table, java.util.List.copyOf(rows.map { it.field }))
        }
    }

    fun partitionValues(table: Long, files: List<DucklakeDataFilePageEntry>, specs: List<DucklakePartitionSpec>) {
        val byFile = files.associateBy { it.dataFile.dataFileId }
        val bySpec = specs.associateBy { it.partitionId }
        val values = drain(100_000, { after, size -> metadata.getFilePartitionValuesPage(table, snapshot, after, size) },
            { DucklakeFilePartitionCursor(it.dataFileId, it.partitionKeyIndex) },
            compareBy<DucklakeFilePartitionCursor> { it.dataFileId }.thenBy { it.partitionKeyIndex }) {
            val file = byFile[it.dataFileId]?.dataFile ?: error("Partition value without active file")
            check(bySpec[file.partitionId]?.fields?.any { f -> f.partitionKeyIndex == it.partitionKeyIndex } == true) {
                "Partition value without active spec field"
            }
            text(it.partitionValue) // Native path text is opaque, never a Hive value decoder.
        }
        val counts = values.groupingBy { it.dataFileId }.eachCount()
        files.map { it.dataFile }.filter { it.partitionId != null }.forEach {
            budget.step()
            val spec = bySpec[it.partitionId] ?: error("Missing active file partition spec")
            check(counts[it.dataFileId] == spec.fields.size) { "Incomplete file partition values" }
        }
    }

    fun mapping(id: Long) {
        val rows = longs(1024, { after, size -> metadata.getNameMappingPage(id, after, size) }, { it.mappingColumnId }) {
            text(it.sourceName)
        }
        tree(rows, DucklakeNameMappingRow::mappingColumnId, DucklakeNameMappingRow::parentMappingColumnId,
            DucklakeNameMappingRow::sourceName)
        // Registry IDs are NOT target field IDs. Body-absent Hive remains refused by the caller.
    }

    private fun <T> tree(rows: List<T>, id: (T) -> Long, parent: (T) -> Long?, name: (T) -> String) {
        val byId = rows.associateBy(id)
        check(byId.size == rows.size) { "Duplicate hierarchy identity" }
        check(rows.map { parent(it) to key(name(it)) }.distinct().size == rows.size) { "Ambiguous sibling names" }
        rows.forEach { row ->
            check(name(row).isNotEmpty()) { "Empty hierarchy name" }
            var node: T? = row
            val seen = hashSetOf<Long>()
            var pathLength = 0
            while (node != null) {
                budget.step()
                check(seen.add(id(node)) && seen.size <= 64) { "Cyclic/deep metadata hierarchy" }
                pathLength += name(node).length
                check(pathLength <= 16_384) { "Qualified metadata path budget exceeded" }
                node = parent(node)?.let { byId[it] ?: error("Missing metadata parent") }
            }
        }
    }

    private fun text(vararg values: String?) {
        values.filterNotNull().forEach {
            check(it.length <= 16_384) { "Final-1.1 metadata text length exceeded" }
            budget.literal(it)
        }
    }
    private fun key(value: String) = value.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")

    private fun <T> longs(max: Int, fetch: (Long?, Int) -> List<T>, cursor: (T) -> Long, charge: (T) -> Unit): List<T> =
        drain(max, fetch, cursor, naturalOrder(), charge)

    private fun <T, C> drain(
        max: Int, fetch: (C?, Int) -> List<T>, cursor: (T) -> C, order: Comparator<C>, charge: (T) -> Unit,
    ): List<T> {
        val result = mutableListOf<T>()
        var after: C? = null
        while (true) {
            budget.page() // Includes terminal empties; no reread after failure or text-budget retry.
            val page = fetch(after, budget.pageSize)
            check(page.size <= budget.pageSize) { "Oversized metadata page" }
            if (page.isEmpty()) return java.util.List.copyOf(result)
            check(result.size + page.size <= max) { "Final-1.1 metadata node budget exceeded" }
            for (row in page) {
                budget.step()
                val next = cursor(row)
                check(after == null || order.compare(next, after) > 0) { "Non-progressing/duplicate metadata cursor" }
                charge(row)
                result.add(row)
                after = next
            }
        }
    }
}
