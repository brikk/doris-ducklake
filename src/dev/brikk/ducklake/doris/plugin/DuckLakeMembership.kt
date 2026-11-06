package dev.brikk.ducklake.doris.plugin

import org.apache.doris.connector.spi.pushdown.ConnectorColumnRef
import org.apache.doris.connector.spi.pushdown.ConnectorComparison
import org.apache.doris.connector.spi.pushdown.ConnectorExpression
import org.apache.doris.connector.spi.pushdown.ConnectorIn
import org.apache.doris.connector.spi.pushdown.ConnectorLiteral
import org.apache.doris.connector.spi.pushdown.ConnectorOr

/**
 * Recognizes predicates that constrain a single column to a finite set of literals: `col = x`,
 * `col IN (…)` and same-column `col = a OR col = b`. Stats pruning collapses the set to its span;
 * bucket pruning hashes each value. `NOT IN` is excluded because its complement is unbounded.
 */
internal object DuckLakeMembership {

    fun of(expr: ConnectorExpression): Pair<String, List<Any>>? = when (expr) {
        is ConnectorComparison -> equality(expr)?.let { (column, value) -> column to listOf(value) }
        is ConnectorIn -> fromIn(expr)
        is ConnectorOr -> fromOr(expr)
        else -> null
    }

    private fun fromIn(inExpr: ConnectorIn): Pair<String, List<Any>>? {
        if (inExpr.isNegated) return null
        val column = (inExpr.value as? ConnectorColumnRef)?.columnName ?: return null
        val values = literalValues(inExpr.inList) ?: return null
        return column to values
    }

    private fun fromOr(orExpr: ConnectorOr): Pair<String, List<Any>>? {
        val facts = orExpr.disjuncts.map { equality(it) ?: return null }
        if (facts.isEmpty()) return null
        val column = facts.map { it.first }.distinct().singleOrNull() ?: return null
        return column to facts.map { it.second }
    }

    private fun equality(expr: ConnectorExpression): Pair<String, Any>? {
        if (expr !is ConnectorComparison) return null
        if (expr.operator != ConnectorComparison.Operator.EQ &&
            expr.operator != ConnectorComparison.Operator.EQ_FOR_NULL
        ) {
            return null
        }
        val column = (expr.left as? ConnectorColumnRef)?.columnName ?: return null
        val value = (expr.right as? ConnectorLiteral)?.takeUnless { it.isNull }?.value ?: return null
        return column to value
    }

    private fun literalValues(exprs: List<ConnectorExpression>): List<Any>? {
        if (exprs.isEmpty()) return null
        return exprs.map { e ->
            (e as? ConnectorLiteral)?.takeUnless { it.isNull }?.value ?: return null
        }
    }
}
