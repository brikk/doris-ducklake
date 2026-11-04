package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.catalog.DucklakeColumn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class DorisReplayProjectionTest {
    private val columns = listOf(
        DucklakeColumn(1L, 1L, null, 1L, 0L, "s", "map<varchar,int32>", true, null),
        DucklakeColumn(2L, 1L, null, 1L, 1L, "text", "varchar", true, null),
    )

    @Test
    fun starsAndDirectAliasedSlotsUseTrustedMetadataInResultOrder() {
        val calls = mutableListOf<Pair<String, String>>()
        val star = DorisReplayProjection.types("SELECT * FROM lake.test WHERE s.i=1", "lake") { schema, table ->
            calls += schema to table; columns
        }
        assertThat(calls).containsExactly("main" to "test")
        assertThat(star!!.map { it!!.type }).containsExactly("map", "varchar")
        val projected = DorisReplayProjection.types("SELECT t.text AS a,t.s AS b FROM lake.main.test AS t", "lake") { _, _ -> columns }
        assertThat(projected!!.map { it!!.type }).containsExactly("varchar", "map")
    }

    @Test
    fun computedJsonLiteralsJoinsAndForeignLakesCannotEstablishContainerTypes() {
        for (sql in listOf(
            "SELECT '{\"i\":1}' FROM lake.test",
            "SELECT s FROM other.test",
            "SELECT a.s FROM lake.test a JOIN lake.test b ON 1=1",
            "WITH x AS (SELECT * FROM lake.test) SELECT * FROM x",
            "SELECT * FROM (SELECT * FROM lake.test) x",
            "SELECT s FROM lake.test FOR VERSION AS OF 1",
            "SELECT * FROM lake.test()",
        )) {
            var called = false
            assertThat(DorisReplayProjection.types(sql, "lake") { _, _ -> called = true; columns }).describedAs(sql).isNull()
            assertThat(called).isFalse()
        }
    }
}
