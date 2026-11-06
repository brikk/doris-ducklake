package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.ColumnRangePredicate
import org.apache.doris.connector.spi.pushdown.ConnectorAnd
import org.apache.doris.connector.spi.pushdown.ConnectorBetween
import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorIn
import org.apache.doris.connector.spi.pushdown.ConnectorLike
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.connector.spi.pushdown.ConnectorOr

/**
 * Turns the safely usable parts of a Doris filter into per-column [ColumnRangePredicate]s for
 * file-level statistics pruning. The BE re-evaluates the full predicate, so the only requirement is
 * to never drop a file that could hold a matching row:
 * - only top-level AND conjuncts are considered;
 * - `col <op> literal`, `col BETWEEN lo AND hi`, membership (`IN`, same-column `OR`) collapsed to
 *   its `[min..max]` span, and prefix `LIKE 'abc%'` → `[abc, abd)` are mapped;
 * - functions, REGEXP, non-prefix LIKE, IS NULL, NOT, NOT IN, NE and mixed-column ORs are skipped;
 * - strict bounds are widened to inclusive, which can only keep extra files.
 *
 * String ranges assume codepoint collation, DuckDB's VARCHAR default.
 */
internal object DuckLakePredicateConverter {

    private const val LIKE_SPECIAL_CHARS = "%_\\"

    fun toColumnRangePredicates(
        filter: ConnectorExpression,
        columnIdByName: Map<String, Long>,
    ): List<ColumnRangePredicate> {
        val conjuncts = when (filter) {
            is ConnectorAnd -> filter.conjuncts
            else -> listOf(filter)
        }
        return conjuncts.mapNotNull { tryRange(it, columnIdByName) }
    }

    private fun tryRange(expr: ConnectorExpression, ids: Map<String, Long>): ColumnRangePredicate? =
        when (expr) {
            is ConnectorComparison -> fromComparison(expr, ids)
            is ConnectorBetween -> fromBetween(expr, ids)
            is ConnectorIn, is ConnectorOr -> fromMembership(expr, ids)
            is ConnectorLike -> fromLike(expr, ids)
            else -> null
        }

    private fun fromLike(like: ConnectorLike, ids: Map<String, Long>): ColumnRangePredicate? {
        if (like.operator != ConnectorLike.Operator.LIKE) return null
        val col = like.value as? ConnectorColumnRef ?: return null
        val pattern = (like.pattern as? ConnectorLiteral)?.takeUnless { it.isNull }?.value as? String ?: return null
        val prefix = prefixOf(pattern) ?: return null
        val columnId = ids[col.columnName] ?: return null
        return ColumnRangePredicate(columnId, prefix, prefixUpperBound(prefix))
    }

    /** The literal prefix of `'<prefix>%'`, or null unless it is a clean prefix with exactly one trailing `%`. */
    private fun prefixOf(pattern: String): String? {
        if (!pattern.endsWith('%')) return null
        val prefix = pattern.dropLast(1)
        if (prefix.isEmpty() || prefix.any { it in LIKE_SPECIAL_CHARS }) return null
        return prefix
    }

    /** Codepoint successor of [prefix]; null when every char is 0xFFFF, leaving the upper bound open. */
    private fun prefixUpperBound(prefix: String): String? {
        val chars = prefix.toCharArray()
        var i = chars.size - 1
        while (i >= 0 && chars[i] == Char.MAX_VALUE) {
            i--
        }
        if (i < 0) return null
        val bumped = chars.copyOf(i + 1)
        bumped[i] = bumped[i] + 1
        return String(bumped)
    }

    private fun fromMembership(expr: ConnectorExpression, ids: Map<String, Long>): ColumnRangePredicate? {
        val (column, values) = DuckLakeMembership.of(expr) ?: return null
        val columnId = ids[column] ?: return null
        return spanRange(columnId, values)
    }

    /** Typed comparison, so `id IN (3, 10, 9)` spans `3..10` rather than the string span `"10".."9"`. */
    private fun spanRange(columnId: Long, values: List<Any>): ColumnRangePredicate? {
        val cls = values.first()::class
        if (values.first() !is Comparable<*> || values.any { it::class != cls }) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val byValue = Comparator<Any> { a, b -> (a as Comparable<Any>).compareTo(b) }
        val sorted = values.sortedWith(byValue)
        return ColumnRangePredicate(columnId, sorted.first().toString(), sorted.last().toString())
    }

    private fun fromComparison(cmp: ConnectorComparison, ids: Map<String, Long>): ColumnRangePredicate? {
        val col = cmp.left as? ConnectorColumnRef ?: return null
        val lit = cmp.right as? ConnectorLiteral ?: return null
        if (lit.isNull) return null
        val columnId = ids[col.columnName] ?: return null
        val v = lit.value?.toString() ?: return null
        return when (cmp.operator) {
            ConnectorComparison.Operator.EQ,
            ConnectorComparison.Operator.EQ_FOR_NULL -> ColumnRangePredicate(columnId, v, v)
            ConnectorComparison.Operator.GT,
            ConnectorComparison.Operator.GE -> ColumnRangePredicate(columnId, v, null)
            ConnectorComparison.Operator.LT,
            ConnectorComparison.Operator.LE -> ColumnRangePredicate(columnId, null, v)
            ConnectorComparison.Operator.NE -> null
        }
    }

    private fun fromBetween(b: ConnectorBetween, ids: Map<String, Long>): ColumnRangePredicate? {
        val col = b.value as? ConnectorColumnRef ?: return null
        val lo = (b.lower as? ConnectorLiteral)?.takeUnless { it.isNull }?.value?.toString() ?: return null
        val hi = (b.upper as? ConnectorLiteral)?.takeUnless { it.isNull }?.value?.toString() ?: return null
        val columnId = ids[col.columnName] ?: return null
        return ColumnRangePredicate(columnId, lo, hi)
    }
}
