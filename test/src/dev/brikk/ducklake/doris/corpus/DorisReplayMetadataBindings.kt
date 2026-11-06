package dev.brikk.ducklake.doris.corpus

import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.ast.toIdentifier
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.traverseScope
import dev.brikk.house.sql.parser.TokenType

/**
 * Rewrites oracle-side `<metadata_catalog>.main.<table>` references to the PostgreSQL
 * schema (`public`) when the metadata catalog was rewritten from DuckDB to PostgreSQL.
 * Works on the parsed tree, scoped to the known catalogs, so unrelated identifiers
 * named `main` are untouched.
 */
internal object DorisReplayMetadataBindings {
    private val identifierTokens = setOf(TokenType.VAR, TokenType.IDENTIFIER)

    fun rewrite(sql: String, schemas: Map<String, String>): String {
        if (schemas.keys.none { sql.contains(it, true) || sql.contains(it.replace("\"", "\"\""), true) }) return sql
        if (!referencesMetadataSchema(sql, schemas)) return sql
        val tree = Dialects.DUCKDB.parseOne(sql)
        val rebound = bindTables(tree, schemas)
        if (rebound.isEmpty()) return sql
        for (scope in traverseScope(tree)) {
            val aliases = scope.selectedSources.keys
            for (column in scope.columns) {
                val catalog = rebound.firstOrNull { matches(column.catalog, it) }
                if (catalog != null && aliases.none { matches(it, catalog) } && matches(column.db, "main")) {
                    column.set("db", toIdentifier(schemas.getValue(catalog)))
                }
            }
        }
        return Dialects.DUCKDB.generate(tree)
    }

    private fun bindTables(tree: Expression, schemas: Map<String, String>): Set<String> {
        val rebound = mutableSetOf<String>()
        for (table in tree.findAll(Table::class).filterIsInstance<Table>()) {
            val catalog = schemas.keys.firstOrNull { matches(table.catalog, it) }
            if (catalog != null && matches(table.db, "main")) {
                table.set("db", toIdentifier(schemas.getValue(catalog)))
                rebound += catalog
            }
        }
        return rebound
    }

    private fun referencesMetadataSchema(sql: String, schemas: Map<String, String>): Boolean =
        Dialects.DUCKDB.tokenize(sql).windowed(4).any { tokens ->
            if (tokens[1].tokenType != TokenType.DOT || tokens[3].tokenType != TokenType.DOT) return@any false
            val catalog = tokens[0]
            val schema = tokens[2]
            if (catalog.tokenType !in identifierTokens || schema.tokenType !in identifierTokens) return@any false
            matches(schema.text, "main") && schemas.keys.any { matches(catalog.text, it) }
        }

    private fun matches(a: String, b: String): Boolean = a.length == b.length && a.indices.all { index ->
        fun asciiLower(c: Char) = if (c in 'A'..'Z') c.lowercaseChar() else c
        asciiLower(a[index]) == asciiLower(b[index])
    }
}
