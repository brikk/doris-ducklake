package dev.brikk.ducklake.doris.corpus

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import dev.brikk.ducklake.catalog.InlinedTypeNode
import dev.brikk.ducklake.corpus.GoldenComparator
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale

/** MySQL complex-cell JSON -> native replay text, ONLY with a trusted source type. */
internal object DorisNestedCellRenderer {
    private const val MAX_TEXT = 1_048_576
    private const val MAX_VALUES = 4096
    private val decimal = Regex("decimal\\((\\d+),(\\d+)\\)")
    private val integerBits = mapOf("int8" to 8, "int16" to 16, "int32" to 32, "int64" to 64,
        "uint8" to 8, "uint16" to 16, "uint32" to 32, "uint64" to 64)
    private val scalar = integerBits.keys + setOf("varchar", "boolean", "float32", "float64")
    private val factory: JsonFactory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
            .maxStringLength(MAX_TEXT).maxNumberLength(128).build()).build()

    fun canRender(type: InlinedTypeNode): Boolean = type.type in setOf("list", "map", "struct") && qualified(type)

    private fun qualified(type: InlinedTypeNode): Boolean = when (type.type) {
        "list" -> type.children.size == 1 && qualified(type.children.single())
        "map" -> type.children.size == 2 && type.children[0].type == "varchar" && qualified(type.children[1])
        "struct" -> type.children.isNotEmpty() && type.children.all { qualified(it) } &&
            type.children.map { it.name.lowercase(Locale.ROOT) }.distinct().size == type.children.size
        else -> type.type in scalar || decimal.matches(type.type)
    }

    fun render(value: Any?, type: InlinedTypeNode): String? {
        require(canRender(type)) { "No qualified nested replay rendering for ${type.type}" }
        if (value == null) return null
        require(value is String && value.length <= MAX_TEXT) { "Invalid or oversized typed Doris complex cell" }
        return factory.createParser(value).use { parser ->
            require(parser.nextToken() != null && parser.currentToken() != JsonToken.VALUE_NULL) { "Missing typed complex value" }
            val result = Reader().read(parser, type)
            require(parser.nextToken() == null) { "Trailing content in typed complex value" }
            result
        }
    }

    private class Reader {
        private var values = 0

        fun read(parser: JsonParser, type: InlinedTypeNode): String {
            require(++values <= MAX_VALUES) { "Typed complex replay value budget exceeded" }
            if (parser.currentToken() == JsonToken.VALUE_NULL) return "NULL"
            return when (type.type) {
                "list" -> list(parser, type)
                "map" -> map(parser, type)
                "struct" -> struct(parser, type)
                else -> scalar(parser, type.type)
            }
        }

        private fun list(parser: JsonParser, type: InlinedTypeNode): String {
            require(parser.currentToken() == JsonToken.START_ARRAY) { "Expected a typed ARRAY cell" }
            val items = mutableListOf<String>()
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                require(parser.currentToken() != null) { "Incomplete typed ARRAY cell" }
                items += read(parser, type.children.single())
            }
            return items.joinToString(", ", "[", "]")
        }

        private fun map(parser: JsonParser, type: InlinedTypeNode): String {
            require(parser.currentToken() == JsonToken.START_OBJECT) { "Expected a typed MAP cell" }
            val items = mutableListOf<String>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME) { "Missing typed MAP key" }
                val key = GoldenComparator.renderNested(requireNotNull(parser.currentName()))
                require(parser.nextToken() != null) { "Missing typed MAP value" }
                items += "$key=${read(parser, type.children[1])}"
            }
            return items.joinToString(", ", "{", "}")
        }

        private fun struct(parser: JsonParser, type: InlinedTypeNode): String {
            require(parser.currentToken() == JsonToken.START_OBJECT) { "Expected a typed STRUCT cell" }
            val fields = mutableMapOf<String, String>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME) { "Missing typed STRUCT field" }
                val name = requireNotNull(parser.currentName())
                val field = requireNotNull(type.children.singleOrNull { it.name == name }) { "Unexpected typed STRUCT field" }
                require(parser.nextToken() != null) { "Missing typed STRUCT value" }
                fields[name] = read(parser, field)
            }
            require(fields.size == type.children.size) { "Missing typed STRUCT fields" }
            return type.children.joinToString(", ", "{", "}") { field ->
                val key = field.name.replace("\\", "\\\\").replace("'", "\\'")
                "'$key': ${fields.getValue(field.name)}"
            }
        }

        private fun scalar(parser: JsonParser, type: String): String {
            val value: Any = when {
                type == "varchar" -> {
                    require(parser.currentToken() == JsonToken.VALUE_STRING) { "Expected a typed VARCHAR value" }
                    parser.text
                }
                type == "boolean" -> {
                    require(parser.currentToken() in setOf(JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE)) { "Expected a typed BOOLEAN value" }
                    parser.booleanValue
                }
                type in integerBits -> integer(parser, type)
                else -> number(parser, type)
            }
            return GoldenComparator.renderNested(value)
        }

        private fun integer(parser: JsonParser, type: String): BigInteger {
            require(parser.currentToken() == JsonToken.VALUE_NUMBER_INT) { "Expected a typed integer value" }
            val value = BigInteger(parser.text)
            val bits = integerBits.getValue(type)
            val min = if (type.startsWith("u")) BigInteger.ZERO else BigInteger.ONE.shiftLeft(bits - 1).negate()
            val max = BigInteger.ONE.shiftLeft(if (type.startsWith("u")) bits else bits - 1).subtract(BigInteger.ONE)
            require(value in min..max) { "Typed integer is outside its declared range" }
            return value
        }

        private fun number(parser: JsonParser, type: String): Any {
            require(parser.currentToken()?.isNumeric == true) { "Expected a typed numeric value" }
            decimal.matchEntire(type)?.let { match ->
                val value = BigDecimal(parser.text).setScale(match.groupValues[2].toInt(), RoundingMode.UNNECESSARY)
                require(value.precision() <= match.groupValues[1].toInt()) { "Typed decimal exceeds declared precision" }
                return value
            }
            return if (type == "float32") parser.text.toFloat().also { require(it.isFinite()) }
                else parser.text.toDouble().also { require(it.isFinite()) }
        }
    }
}
