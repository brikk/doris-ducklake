package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.corpus.GoldenComparator
import dev.brikk.ducklake.corpus.ReplayDriver
import dev.brikk.ducklake.doris.corpus.normalizeReplayCell
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SortMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/** Exercises the resolved published replay JAR, not a copied producer implementation. */
internal class DuckLakeReplayConformanceTest {
    @Test
    fun typedBooleanHashesPreserveVarcharAndRawRowsAcrossEverySortMode() {
        val raw = listOf(listOf("true", "9"), listOf("false", "true"), listOf(null, "false"))
        val expected = mapOf(SortMode.NOSORT to "5efb26c1b2452e75ae993e36f7931249",
            SortMode.ROWSORT to "3caa583fb6e97db58d6e459e0be3ba32", SortMode.VALUESORT to "6d544ba3f4e91363e4c718e42dd827a8")
        for ((mode, digest) in expected) {
            val query = SltQuery(1, "SELECT b,t", "IT", mode, null, null, emptyList())
            assertThat(GoldenComparator.resultHash(query, raw, listOf("BOOLEAN", "VARCHAR")))
                .isEqualTo("6 values hashing to $digest")
        }
        assertThat(raw[0]).containsExactly("true", "9")
        val query = SltQuery(1, "SELECT TRUE", "I", SortMode.NOSORT, null, null, emptyList())
        assertThat(GoldenComparator.resultHash(query, listOf(listOf("true"))))
            .isEqualTo("1 values hashing to 74d9a83219cabaab06a69fd318873f33")
        assertThatThrownBy { GoldenComparator.resultHash(query, listOf(listOf("garbage")), listOf("BOOLEAN")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val file = "query I nosort\nSELECT TRUE\n----\n1 values hashing to b026324c6904b2a9cb4b88d6d61c81d1\n\n"
        val result = ReplayDriver().replay(SltParser.parse("authored-boolean.test", file))
        assertThat(result.failed).isEmpty()
        assertThat(result.passed).isEqualTo(1)
        val wrong = ReplayDriver().replay(SltParser.parse("wrong-boolean.test", file.replace("b026324c6904b2a9cb4b88d6d61c81d1", "74d9a83219cabaab06a69fd318873f33")))
        assertThat(wrong.failed).hasSize(1)
    }

    @Test
    fun floatingRendererMatchesNativeRawBitsIncludingSubnormalFallbackAndSignedNan() {
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            for ((sql, value) in listOf(
                "CAST('1e-45' AS FLOAT)" to Float.fromBits(1),
                "CAST('1.17549435e-38' AS FLOAT)" to java.lang.Float.MIN_NORMAL,
                "CAST('3.4028235e38' AS FLOAT)" to Float.MAX_VALUE,
                "CAST('-0.0' AS FLOAT)" to -0.0f,
                "CAST('0.1' AS FLOAT)" to 0.1f,
                "CAST('nan' AS DOUBLE)" to Double.NaN,
                "CAST('-nan' AS DOUBLE)" to Double.fromBits(-2251799813685248L),
                "CAST('1e-5' AS DOUBLE)" to 1e-5,
                "CAST('1e16' AS DOUBLE)" to 1e16,
                "CAST('1.7976931348623157e308' AS DOUBLE)" to Double.MAX_VALUE,
                "CAST('4.9406564584124654e-324' AS DOUBLE)" to Double.MIN_VALUE,
            )) {
                s.executeQuery("SELECT CAST($sql AS VARCHAR)").use { r ->
                    check(r.next())
                    assertThat(GoldenComparator.renderCell(value)).describedAs(sql).isEqualTo(r.getString(1))
                    assertThat(normalizeReplayCell(value)).describedAs("Doris adapter: $sql").isEqualTo(r.getString(1))
                }
            }
        } }
    }
}
