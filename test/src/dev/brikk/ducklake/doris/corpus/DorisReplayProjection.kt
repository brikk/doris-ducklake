package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.catalog.DucklakeColumn
import dev.brikk.ducklake.catalog.DucklakeTypeSignature
import dev.brikk.ducklake.catalog.InlinedTypeNode
import dev.brikk.house.sql.ast.Alias
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Identifier
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.ast.Star
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError

/**
 * Resolves the DuckLake column types behind a replayed query's projection, so nested
 * cells can be rendered from catalog types. Only plain column references and stars
 * over a single lake table resolve; anything else yields null for that slot.
 */
internal object DorisReplayProjection {
    private data class Binding(val table: Table, val schema: String, val alias: String, val expressions: List<Expression?>)

    fun types(
        sql: String,
        lakeAlias: String,
        columns: (String, String) -> List<DucklakeColumn>?,
    ): List<InlinedTypeNode?>? {
        val binding = bind(sql, lakeAlias) ?: return null
        val source = columns(binding.schema, binding.table.name)?.filter { it.parentColumn == null }
            ?.sortedBy { it.columnOrder } ?: return null
        return project(binding, source)
    }

    private fun bind(sql: String, lakeAlias: String): Binding? {
        val select = simpleSelect(sql) ?: return null
        val table = select.findAll(Table::class).filterIsInstance<Table>().singleOrNull()?.takeIf(::plainCurrentTable) ?: return null
        val schema = ownedSchema(table, lakeAlias) ?: return null
        val expressions = select.expressionsArg.map { unwrap(it as? Expression) }
        return if (expressions.any { it is Column || it is Star }) Binding(table, schema, lakeAlias, expressions) else null
    }

    private fun simpleSelect(sql: String): Select? {
        val select = try { Dialects.DORIS.parseOne(sql) as? Select } catch (_: ParseError) { null }
        return select?.takeIf {
            it.findAll(Select::class).count() == 1 && it.args["with"] == null && it.args["with_"] == null
        }
    }

    private fun ownedSchema(table: Table, lakeAlias: String): String? = when {
        table.catalog.equals(lakeAlias, true) -> table.db
        table.catalog.isEmpty() && table.db.equals(lakeAlias, true) -> "main"
        else -> null
    }

    private fun plainCurrentTable(table: Table): Boolean {
        if (table.thisArg !is Identifier) return false
        val structural = setOf("this", "db", "catalog", "alias")
        return table.args.none { (name, value) ->
            name !in structural && value != null && value != false && value != emptyList<Any>()
        }
    }

    private fun project(binding: Binding, source: List<DucklakeColumn>): List<InlinedTypeNode?>? {
        val out = mutableListOf<InlinedTypeNode?>()
        for (expression in binding.expressions) {
            val column = expression as? Column
            if (column != null && !belongsToSource(column, binding.table, binding.alias, binding.schema)) {
                out += null
                continue
            }
            val star = if (expression is Star) expression else column?.thisArg as? Star
            when {
                star != null -> {
                    if (star.args.values.any { it != null && it != emptyList<Any>() }) return null
                    out += source.map { DucklakeTypeSignature.parse(it.columnType) }
                }
                column != null -> out += source.singleOrNull { it.columnName.equals(column.name, true) }
                    ?.let { DucklakeTypeSignature.parse(it.columnType) }
                else -> out += null
            }
        }
        return out
    }

    private fun unwrap(expression: Expression?): Expression? =
        if (expression is Alias) expression.thisArg as? Expression else expression

    private fun belongsToSource(column: Column, table: Table, alias: String, schema: String): Boolean {
        if (column.catalog.isNotEmpty() && !column.catalog.equals(alias, true)) return false
        if (column.db.isNotEmpty() && !column.db.equals(schema, true) && !column.db.equals(alias, true)) return false
        return column.table.isEmpty() || column.table.equals(table.aliasOrName, true) || column.table.equals(table.name, true)
    }
}
