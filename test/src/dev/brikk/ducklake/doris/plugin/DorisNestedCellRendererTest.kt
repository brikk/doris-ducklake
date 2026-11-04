package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeTypeSignature
import dev.brikk.ducklake.corpus.GoldenComparator
import dev.brikk.ducklake.doris.corpus.DorisNestedCellRenderer
import dev.brikk.ducklake.doris.corpus.normalizeReplayCell
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Typed protocol conversion only; JSON-looking scalar strings must never be inferred as containers. */
internal class DorisNestedCellRendererTest {
    @Test
    fun mapsMatchTheNativeRendererAndPreserveSqlNullVersusLiteralStrings() {
        val type = DucklakeTypeSignature.parse("map<varchar,varchar>")
        val source = linkedMapOf("i" to "NULL", "j" to "", "nil" to null, "a,b" to "x:y", "slash" to "a\\b")
        val payload = """{"i":"NULL","j":"","nil":null,"a,b":"x:y","slash":"a\\b"}"""
        assertThat(DorisNestedCellRenderer.render(payload, type)).isEqualTo(GoldenComparator.renderCell(source))
        assertThat(DorisNestedCellRenderer.render(null, type)).isNull()
        assertThat(DorisNestedCellRenderer.render("{}", type)).isEqualTo("{}")
    }

    @Test
    fun integerMapsRecoverNativeTextInsteadOfJsonPunctuation() {
        val type = DucklakeTypeSignature.parse("map<varchar,int32>")
        assertThat(DorisNestedCellRenderer.render("""{"i":1,"j":2}""", type))
            .isEqualTo(GoldenComparator.renderCell(linkedMapOf("i" to 1, "j" to 2)))
    }

    @Test
    fun arraysAndStructsRespectDeclaredFieldOrderAndNullContainers() {
        val type = DucklakeTypeSignature.parse("struct<\"a,b\":int32,Name:list<varchar>>")
        assertThat(DorisNestedCellRenderer.render("""{"Name":["NULL",null,""],"a,b":1}""", type))
            .isEqualTo("{'a,b': 1, 'Name': ['NULL', NULL, '']}")
        val list = DucklakeTypeSignature.parse("list<map<varchar,int32>>")
        assertThat(DorisNestedCellRenderer.render("""[{"i":1},null,{}]""", list)).isEqualTo("[{i=1}, NULL, {}]")
    }

    @Test
    fun decimalsAndSignedFloatingZeroUseTheirDeclaredTypes() {
        val decimal = DucklakeTypeSignature.parse("map<varchar,decimal(18,2)>")
        assertThat(DorisNestedCellRenderer.render("""{"v":1.2}""", decimal))
            .isEqualTo(GoldenComparator.renderCell(mapOf("v" to BigDecimal("1.20"))))
        val floating = DucklakeTypeSignature.parse("map<varchar,float32>")
        assertThat(DorisNestedCellRenderer.render("""{"v":-0.0}""", floating))
            .isEqualTo(GoldenComparator.renderCell(mapOf("v" to -0.0f)))
    }

    @Test
    fun varcharPayloadsAreNotGuessedToBeJson() {
        val payload = """{"i":1}"""
        assertThat(normalizeReplayCell(payload, false, DucklakeTypeSignature.parse("varchar"))).isEqualTo(payload)
        assertThat(normalizeReplayCell("[1,2]", false, null)).isEqualTo("[1,2]")
    }

    @Test
    fun malformedDuplicateAndWronglyTypedPayloadsFailInsteadOfInventingValues() {
        val type = DucklakeTypeSignature.parse("map<varchar,int32>")
        for (payload in listOf("""{"i":1,"i":2}""", """{"i":"1"}""", """{"i":2147483648}""", "[]", "null", "{} {}")) {
            assertThatThrownBy { DorisNestedCellRenderer.render(payload, type) }.describedAs(payload).isInstanceOf(Exception::class.java)
        }
        assertThatThrownBy { DorisNestedCellRenderer.render("{}", DucklakeTypeSignature.parse("struct<a:int32>")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(DorisNestedCellRenderer.canRender(DucklakeTypeSignature.parse("map<int32,varchar>"))).isFalse()
    }

    @Test
    fun largePayloadsAreBounded() {
        val type = DucklakeTypeSignature.parse("list<int32>")
        val payload = (1..4097).joinToString(",", "[", "]")
        assertThatThrownBy { DorisNestedCellRenderer.render(payload, type) }.hasMessageContaining("budget")
    }
}
