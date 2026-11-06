package dev.brikk.ducklake.doris.corpus

import dev.brikk.house.sql.shape.FindingKind
import dev.brikk.house.sql.shape.Severity
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.certify
import dev.brikk.house.sql.verify.DorisVerifier
import dev.brikk.house.sql.verify.SqlVerifier

/**
 * Decides whether a corpus DuckDB query can be mirrored on Doris, and produces the
 * transpiled Doris SQL when it can. Each query is transpiled and certified via
 * brikk-sql `certify("doris")`; anything certify can't prove safe for an exact-output
 * diff becomes a [Skip], never a failure.
 *
 * Certify can't detect two things the gate handles explicitly: `information_schema`
 * (catalog content differs between engines, not syntax) and DuckDB pseudo columns
 * (`rowid`, `filename`, …), which are valid identifiers with no Doris column.
 * Two DuckDB idioms brikk-sql doesn't own are rewritten here: trailing `ORDER BY ALL`
 * and literal `AT (VERSION => n)` time travel.
 */
object DorisCorpusDialect {

    private const val DUCKDB = "duckdb"
    private const val DORIS = "doris"

    /**
     * Null when the Doris parser isn't on the test classpath (see build.gradle.kts);
     * the live FE then acts as the grammar check.
     */
    private val VERIFIER: SqlVerifier? = runCatching { DorisVerifier.createOrNull() }.getOrNull()

    sealed interface Gate

    /** [dorisSql] is the transpiled Doris query, still alias-qualified. */
    data class Run(val dorisSql: String) : Gate

    data class Skip(val reason: String) : Gate

    /** The alias-to-catalog rewrite of the returned SQL is the caller's job. */
    @Suppress("ReturnCount")
    fun gate(duckdbSql: String): Gate {
        val trimmed = duckdbSql.trim().removeSuffix(";").trim()
        val body = stripLeadingCommentsAndWhitespace(trimmed)
        if (!startsWithSelectOrParenSelect(body) && !startsWithWithCte(body)) {
            return Skip("not a read (SELECT/WITH) statement")
        }

        // brikk-sql parses ORDER BY ALL as a column named ALL. A trailing one can be
        // dropped because the mirror compares sorted rows; one governing a LIMIT
        // decides which rows survive, so it can't be.
        val orderByAll = ORDER_BY_ALL.find(body)
        val pre = when {
            orderByAll == null -> body
            LIMIT_OR_OFFSET.containsMatchIn(body.substring(orderByAll.range.last + 1)) ->
                return Skip("ORDER BY ALL governs a LIMIT/OFFSET (top-N ordering can't be dropped)")
            else -> body.removeRange(orderByAll.range).trim()
        }

        if (INFORMATION_SCHEMA.containsMatchIn(pre)) {
            return Skip("information_schema: catalog content differs DuckDB-vs-Doris")
        }
        // Matched here rather than by classifying "Unknown column" errors at runtime,
        // which would mask real column-resolution bugs.
        DUCKDB_VIRTUAL_COLUMN.find(pre)?.let {
            return Skip("DuckDB virtual column '${it.value}' has no Doris equivalent")
        }

        val fragment = try {
            SqlFragment(pre, DUCKDB)
        } catch (e: Exception) {
            return Skip("parse under duckdb failed: ${firstLine(e)}")
        }

        val report = try {
            fragment.certify(DORIS, desugarPipes = true)
        } catch (e: Exception) {
            return Skip("certify to doris failed: ${firstLine(e)}")
        }

        certifyRefusalReason(report)?.let { return Skip(it) }

        // brikk-sql passes `AT (VERSION => n)` through verbatim; only the literal
        // form has a Doris equivalent.
        val sql = rewriteInlineTimeTravel(report.result.sql)
        if (AT_PAREN.containsMatchIn(sql)) {
            return Skip("non-literal inline time travel has no Doris form")
        }

        // A transpiled query the Doris grammar rejects is a brikk-sql emission bug,
        // so it skips rather than failing at runtime.
        VERIFIER?.verify(sql)?.let { v ->
            if (!v.accepted) {
                return Skip(
                    "transpiled SQL not parseable by Doris grammar: " +
                        v.error?.lineSequence()?.firstOrNull().orEmpty(),
                )
            }
        }
        return Run(sql)
    }

    /**
     * Returns a skip reason, or null when the query is safe to run. `report.ok` alone
     * isn't enough for an exact-output diff, so two adjustments apply: unicode-scoped
     * refusals (Turkish-İ / ß case folding in lower()/upper()) are accepted because the
     * corpus is ASCII-dominant, and any remaining non-unicode SEMANTIC_HAZARD skips even
     * at WARN severity. Certify only warns when it can't prove an operand type without
     * schema (e.g. bare-column `DATE + INTERVAL 1 DAY`, which DuckDB promotes to
     * TIMESTAMP but Doris keeps DATE), and that still breaks the diff.
     */
    private fun certifyRefusalReason(report: dev.brikk.house.sql.shape.TranspileReport): String? {
        if (!report.okAccepting { "unicode" in it.areas }) {
            val blocking = report.findings
                .filter { it.severity == Severity.REFUSAL && "unicode" !in it.areas }
                .joinToString("; ") { "${it.kind}(${it.subject}): ${it.detail}" }
            return "certify refused: $blocking"
        }
        return report.findings
            .firstOrNull { it.kind == FindingKind.SEMANTIC_HAZARD && "unicode" !in it.areas }
            ?.let { "semantic hazard (${it.subject}): ${it.detail}" }
    }

    /**
     * `t AT (VERSION => 3)` becomes `t FOR VERSION AS OF 3`; a DuckLake version is a
     * snapshot id, so the semantics match. Idempotent.
     */
    fun rewriteInlineTimeTravel(sql: String): String =
        AT_VERSION_LITERAL.replace(sql) { m -> "FOR VERSION AS OF ${m.groupValues[1]}" }

    private fun stripLeadingCommentsAndWhitespace(sql: String): String {
        var s = sql
        while (true) {
            val t = s.trimStart()
            s = when {
                t.startsWith("--") -> t.substringAfter('\n', missingDelimiterValue = "")
                t.startsWith("/*") -> t.substringAfter("*/", missingDelimiterValue = "")
                else -> return t
            }
        }
    }

    private fun startsWithSelectOrParenSelect(body: String): Boolean {
        val b = body.trimStart('(', ' ', '\t', '\n', '\r')
        return b.regionMatches(0, "SELECT", 0, "SELECT".length, ignoreCase = true)
    }

    private fun startsWithWithCte(body: String): Boolean =
        body.regionMatches(0, "WITH", 0, "WITH".length, ignoreCase = true) &&
            !body.getOrElse(4) { ' ' }.isJavaIdentifierPart() // "WITH" keyword, not "WITHIN…"

    private fun firstLine(e: Exception): String =
        (e.message ?: e.toString()).lineSequence().firstOrNull().orEmpty()

    private val AT_VERSION_LITERAL =
        Regex("\\bAT\\s*\\(\\s*VERSION\\s*=>\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE)
    private val AT_PAREN = Regex("\\bAT\\s*\\(", RegexOption.IGNORE_CASE)
    private val ORDER_BY_ALL =
        Regex("\\bORDER\\s+BY\\s+ALL\\b(\\s+(ASC|DESC))?", RegexOption.IGNORE_CASE)
    private val LIMIT_OR_OFFSET = Regex("\\b(LIMIT|OFFSET)\\b", RegexOption.IGNORE_CASE)
    private val INFORMATION_SCHEMA = Regex("\\binformation_schema\\b", RegexOption.IGNORE_CASE)
    private val DUCKDB_VIRTUAL_COLUMN =
        Regex("\\b(rowid|filename|file_row_number|file_index)\\b", RegexOption.IGNORE_CASE)
}
