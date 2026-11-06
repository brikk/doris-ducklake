package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.doris.corpus.DorisCorpusDialect.Run
import dev.brikk.ducklake.doris.corpus.DorisCorpusDialect.Skip
import dev.brikk.house.sql.shape.FindingKind
import dev.brikk.house.sql.shape.Severity
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.certify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pins the [DorisCorpusDialect.gate] decisions and the emitted Doris SQL; no live cluster. */
internal class DorisCorpusDialectTest {

    private fun run(sql: String): Run =
        DorisCorpusDialect.gate(sql).let {
            assertThat(it).`as`("expected Run for: $sql").isInstanceOf(Run::class.java)
            it as Run
        }

    private fun skip(sql: String): Skip =
        DorisCorpusDialect.gate(sql).let {
            assertThat(it).`as`("expected Skip for: $sql").isInstanceOf(Skip::class.java)
            it as Skip
        }

    @Test
    fun transpilesPlainSelect() {
        assertThat(run("SELECT a, b FROM lake.s.t WHERE a > 3").dorisSql)
            .contains("SELECT").contains("lake.s.t")
    }

    @Test
    fun rewritesCastShorthandWithoutAdmittingUnprovenSemantics() {
        // brikk-sql versions differ on whether CAST is refused outright or recorded as
        // conditional coverage; neither verdict proves an arbitrary column safe for
        // exact replay, so both shapes are accepted here.
        val report = SqlFragment("SELECT o_orderkey::BIGINT FROM lake.s.orders", "duckdb")
            .certify("doris")
        assertThat(report.result.sql)
            .contains("CAST(o_orderkey AS BIGINT)")
            .doesNotContain("::")
        val castFinding = report.findings.single { it.subject == "CAST" }
        assertThat(castFinding.kind).isIn(
            FindingKind.SEMANTIC_COVERAGE_UNKNOWN,
            FindingKind.SEMANTIC_HAZARD,
        )
        if (castFinding.kind == FindingKind.SEMANTIC_COVERAGE_UNKNOWN) {
            assertThat(castFinding.severity).isEqualTo(Severity.REFUSAL)
            assertThat(report.ok).isFalse()
        } else {
            assertThat(castFinding.severity).isEqualTo(Severity.WARNING)
            assertThat(castFinding.detail).contains("conditionally-equivalent")
            assertThat(report.ok).isTrue()
        }
        assertThat(castFinding.provenance).isNotBlank()
    }

    @Test
    fun rewritesFilterWhereToCase() {
        val sql = run("SELECT COUNT(*) FILTER (WHERE id % 2 = 0) FROM lake.s.t").dorisSql
        assertThat(sql).containsIgnoringCase("CASE WHEN").doesNotContainIgnoringCase("FILTER")
    }

    @Test
    fun acceptsCtes() {
        assertThat(run("WITH x AS (SELECT a FROM lake.s.t) SELECT * FROM x").dorisSql)
            .containsIgnoringCase("WITH")
    }

    @Test
    fun rewritesLiteralInlineTimeTravel() {
        assertThat(run("SELECT * FROM lake.s.t AT (VERSION => 2)").dorisSql)
            .contains("FOR VERSION AS OF 2")
            .doesNotContainIgnoringCase("AT (")
    }

    @Test
    fun dropsTrailingOrderByAll() {
        val sql = run("SELECT a FROM lake.s.t ORDER BY ALL").dorisSql
        assertThat(sql).doesNotContainIgnoringCase("ORDER BY")
    }

    @Test
    fun skipsCastsWithoutTypeAndValueProof() {
        for (sql in listOf(
            "SELECT o_orderkey::BIGINT FROM lake.s.orders",
            "SELECT CAST(o_orderkey AS BIGINT) FROM lake.s.orders",
        )) {
            assertThat(skip(sql).reason)
                .contains("CAST")
                .matches("(?s)(certify refused:.*SEMANTIC_COVERAGE_UNKNOWN.*|semantic hazard.*conditionally-equivalent.*)")
        }
    }

    @Test
    fun skipsTryCastWithoutBorrowingCastCoverage() {
        assertThat(skip("SELECT TRY_CAST('not-an-integer' AS BIGINT)").reason)
            .contains("SEMANTIC_COVERAGE_UNKNOWN")
            .contains("TRY_CAST")
    }

    @Test
    fun skipsNonReads() {
        assertThat(skip("INSERT INTO lake.s.t VALUES (1)").reason).contains("read")
        assertThat(skip("DROP TABLE lake.s.t").reason).contains("read")
    }

    @Test
    fun skipsClass3FunctionHoles() {
        assertThat(skip("SELECT * FROM read_parquet('f.parquet')").reason)
            .contains("UNMAPPABLE_FUNCTION")
        assertThat(skip("SELECT * FROM ducklake_snapshots('lake')").reason)
            .contains("UNMAPPABLE_FUNCTION")
    }

    @Test
    fun skipsPragmaPassthrough() {
        assertThat(skip("PRAGMA database_size").reason).containsIgnoringCase("read")
    }

    @Test
    fun skipsScalarUnnestViaUnsupportedTranslation() {
        assertThat(skip("SELECT unnest([1, 2, 3])").reason).contains("UNSUPPORTED_TRANSLATION")
    }

    @Test
    fun skipsInformationSchemaContentDivergence() {
        assertThat(skip("SELECT * FROM information_schema.columns").reason)
            .contains("information_schema")
    }

    @Test
    fun skipsNonLiteralInlineTimeTravel() {
        // A bare column, not now(): certify flags now() as a semantic hazard before the
        // time-travel branch is reached (see skipsNowInTimeTravelAsHazard).
        assertThat(skip("SELECT * FROM lake.s.t AT (TIMESTAMP => ts_col)").reason)
            .contains("time travel")
    }

    @Test
    fun skipsNowInTimeTravelAsHazard() {
        assertThat(skip("SELECT * FROM lake.s.t AT (TIMESTAMP => now())").reason)
            .contains("NOW")
    }

    @Test
    fun skipsOrderByAllGoverningLimit() {
        assertThat(skip("SELECT a FROM lake.s.t ORDER BY ALL LIMIT 5").reason)
            .contains("LIMIT")
    }
}
