package dev.brikk.ducklake.doris.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import dev.brikk.ducklake.catalog.DucklakeColumn
import dev.brikk.ducklake.catalog.DucklakeDataFile
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.ParquetMetadata
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Validates actual physical inputs. Does not rewrite files or synthesize a BE predicate. */
internal class DuckLakeFinal11Files(private val objects: DuckLakeReadObjects, private val budget: DuckLakeReadBudget) {
    fun unsupported(columns: List<DucklakeColumn>, inline: Boolean, inlineDeletes: Boolean): String? {
        if (inline || inlineDeletes) return "Final-1.1 inline data/deletes need a distributed payload channel; shared-FS synthesis is disabled"
        for (c in columns) {
            val t = c.columnType.lowercase()
            if (c.parentColumn != null || (t !in TYPES && !DECIMAL.matches(t))) return "Final-1.1 file type $t is not qualified (no degraded types)"
            if (DECIMAL.matchEntire(t)?.groupValues?.get(1)?.toInt()?.let { it > 38 } == true) return "Final-1.1 decimal exceeds lossless Doris precision"
            if (c.initialDefault != null && t !in DEFAULT_TYPES && !DECIMAL.matches(t)) return "Final-1.1 initial default for $t is not qualified"
        }
        return null
    }

    fun data(file: DucklakeDataFile, path: String, columns: List<DucklakeColumn>, snapshot: Long) {
        require(file.fileFormat.equals("parquet", true) && file.mappingId == null) {
            "Final-1.1 requires native field-ID Parquet (add_files mappings not qualified)"
        }
        val footer = footer(path, file.fileSizeBytes)
        val schema = footer.fileMetaData.schema
        budget.step(schema.fieldCount)
        val fields = schema.fields.associateBy { it.id?.intValue() }
        check(fields.size == schema.fieldCount && null !in fields) { "Missing/duplicate native field IDs" }
        check(footer.blocks.sumOf { it.rowCount } == file.recordCount) { "Physical/gross file count mismatch (not a live-count estimate)" }
        if (file.rowIdStart == null) check(schema.containsField(ROW_ID)) { "NULL rowIdStart requires embedded absolute row IDs" }
        lineage(footer, columns, snapshot)
        for (column in columns) {
            budget.step()
            val field = fields[column.columnId.toInt()]
            if (field == null) {
                check(column.initialDefault != null || column.nullsAllowed) { "Missing required field without an initial default" }
                column.initialDefault?.let { validateDefault(column, it) }
            } else {
                DuckLakeScalarFileTypes.check(column, field, footer, budget)
            }
        }
    }

    private fun lineage(footer: ParquetMetadata, columns: List<DucklakeColumn>, snapshot: Long) {
        val schema = footer.fileMetaData.schema
        for (name in listOf(ROW_ID, SNAPSHOT_ID)) {
            if (schema.containsField(name)) {
                val field = schema.getType(name)
                check(field.isPrimitive && field.asPrimitiveType().primitiveTypeName == PrimitiveTypeName.INT64) { "Invalid embedded $name type" }
                check(field.id?.intValue() !in columns.map { it.columnId.toInt() }) { "Reserved field-ID collision" }
                footer.blocks.forEach { group ->
                    budget.step(group.columns.size)
                    val stats = group.columns.single { it.path.toDotString() == name }.statistics
                    check(stats.isNumNullsSet && stats.numNulls == 0L && stats.hasNonNullValue()) { "Unqualified embedded $name statistics" }
                    check((stats.genericGetMin() as Long) >= 0) { "Negative embedded identity" }
                    if (name == SNAPSHOT_ID && (stats.genericGetMax() as Long) > snapshot) {
                        throw DuckLakeUnsupportedRead("Historical DATA needs per-row snapshot filtering unavailable in this BE")
                    }
                }
            }
        }
    }

    fun deletes(path: String, format: String, snapshot: Long): List<DuckLakePositionDelete> {
        return when (format.lowercase()) {
            "parquet" -> {
                val metadata = footer(path)
                val schema = metadata.fileMetaData.schema
                check(schema.getType("pos").asPrimitiveType().primitiveTypeName == PrimitiveTypeName.INT64) { "Invalid delete position type" }
                check(schema.getType("file_path").asPrimitiveType().primitiveTypeName == PrimitiveTypeName.BINARY) { "Invalid delete path type" }
                metadata.blocks.forEach { group ->
                    budget.step(group.columns.size * 3)
                    for (name in listOf("file_path", "pos")) {
                        val stats = group.columns.single { it.path.toDotString() == name }.statistics
                        check(stats.isNumNullsSet && stats.numNulls == 0L) { "NULL/unknown delete positions or paths" }
                    }
                    if (schema.containsField(SNAPSHOT_ID)) {
                        check(schema.getType(SNAPSHOT_ID).asPrimitiveType().primitiveTypeName == PrimitiveTypeName.INT64) { "Invalid deletion snapshot type" }
                        val stats = group.columns.single { it.path.toDotString() == SNAPSHOT_ID }.statistics
                        check(stats.isNumNullsSet && stats.numNulls == 0L && stats.hasNonNullValue()) { "Unknown deletion snapshot domain" }
                        check((stats.genericGetMin() as Long) >= 0) { "Negative deletion snapshot" }
                        if ((stats.genericGetMax() as Long) > snapshot) {
                            throw DuckLakeUnsupportedRead("Historical tagged Parquet deletes require per-position snapshot filtering unavailable in this BE")
                        }
                    }
                }
                listOf(DuckLakePositionDelete(path, "parquet"))
            }
            "puffin" -> {
                val file = objects.open(path, budget)
                check(file.size in 12..16_777_216) { "Puffin object exceeds bounded vector scope" }
                val bytes = file.bytes(0, file.size.toInt())
                val slice = vectorSlice(bytes, snapshot)
                validateVector(bytes.copyOfRange(slice.first, slice.first + slice.second))
                listOf(DuckLakePositionDelete(path, "puffin", slice.first.toLong(), slice.second.toLong()))
            }
            else -> error("Unsupported final-1.1 delete format $format")
        }
    }

    private fun footer(path: String, expectedSize: Long? = null): ParquetMetadata {
        val file = objects.open(path, budget)
        check(file.size >= 12 && (expectedSize == null || expectedSize == file.size)) { "Parquet size mismatch" }
        val tail = file.bytes(file.size - 8, 8)
        check(String(tail.copyOfRange(4, 8), Charsets.US_ASCII) == "PAR1") { "Invalid Parquet footer magic" }
        val size = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).int
        check(size in 1..1_048_576 && size <= file.size - 12) { "Parquet footer size outside planning budget" }
        return ParquetFileReader.open(file).use { reader ->
            reader.footer.also {
                budget.step(it.blocks.size + it.blocks.sumOf { group -> group.columns.size })
                check(it.blocks.size <= 4096) { "Physical row-group budget exceeded" }
            }
        }
    }

    private fun validateDefault(column: DucklakeColumn, value: String) {
        check(value.length <= 4096) { "Initial default exceeds budget" }
        val t = column.columnType.lowercase()
        when {
            t == "boolean" -> check(value in setOf("true", "false")) { "Unqualified Boolean initial default" }
            t.startsWith("int") -> {
                check(Regex("[-+]?\\d{1,20}").matches(value)) { "Unqualified integer initial default" }
                val n = value.toBigInteger()
                val bits = t.removePrefix("int").toInt()
                check(n >= java.math.BigInteger.ONE.shiftLeft(bits - 1).negate() && n < java.math.BigInteger.ONE.shiftLeft(bits - 1)) {
                    "Initial default overflow"
                }
            }
            DECIMAL.matches(t) -> {
                check(Regex("[-+]?\\d{1,38}(\\.\\d{1,38})?").matches(value)) { "Unqualified decimal initial default" }
                val m = DECIMAL.matchEntire(t)!!
                val n = value.toBigDecimal().setScale(m.groupValues[2].toInt(), java.math.RoundingMode.UNNECESSARY)
                check(n.precision() <= m.groupValues[1].toInt()) { "Decimal initial default overflow" }
            }
            else -> error("Unqualified initial default")
        }
    }

    companion object {
        private const val SNAPSHOT_ID = "_ducklake_internal_snapshot_id"
        private const val ROW_ID = "_ducklake_internal_row_id"
        private val TYPES = setOf("boolean", "int8", "int16", "int32", "int64", "float32", "float64",
            "varchar", "blob", "date", "timestamp", "timestamp_s", "timestamp_ms")
        private val DEFAULT_TYPES = setOf("boolean", "int8", "int16", "int32", "int64")
        private val DECIMAL = Regex("decimal\\((\\d+),\\s*(\\d+)\\)")

        internal fun vectorSlice(bytes: ByteArray, snapshot: Long): Pair<Int, Int> {
            if (String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) != "PFA1") return 0 to bytes.size
            check(bytes.size >= 20 && String(bytes.copyOfRange(bytes.size - 4, bytes.size), Charsets.US_ASCII) == "PFA1") { "Invalid Puffin container" }
            check(bytes.copyOfRange(bytes.size - 8, bytes.size - 4).all { it == 0.toByte() }) { "Compressed/unknown Puffin footer flags" }
            val footerSize = ByteBuffer.wrap(bytes, bytes.size - 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
            check(footerSize in 1..1_048_576 && footerSize <= bytes.size - 16) { "Invalid Puffin footer size" }
            val start = bytes.size - 12 - footerSize
            check(start >= 8 && String(bytes.copyOfRange(start - 4, start), Charsets.US_ASCII) == "PFA1") { "Missing Puffin footer start magic" }
            val footer = ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readTree(bytes.copyOfRange(start, bytes.size - 12))
            val blobs = footer["blobs"]
            check(blobs != null && blobs.isArray && blobs.size() in 1..1024) { "Invalid Puffin blob budget" }
            val seen = mutableSetOf<Long>()
            val slices = blobs.map { blob ->
                check(blob["type"]?.asText() == "deletion-vector-v1" && blob["compression-codec"] == null) { "Unsupported Puffin blob type/codec" }
                val id = blob["properties"]?.get("ducklake-snapshot-id")?.asText()?.toLong() ?: error("Missing Puffin snapshot tag")
                check(id >= 0 && seen.add(id)) { "Invalid/duplicate Puffin snapshot tag" }
                check(validInteger(blob["offset"]) && validInteger(blob["length"])) { "Invalid Puffin offset/length" }
                val offset = blob["offset"].longValue()
                val length = blob["length"].longValue()
                check(offset >= 4 && length >= 12 && offset <= start - 4 - length) { "Puffin blob range overlaps footer" }
                Triple(id, offset.toInt(), length.toInt())
            }
            val ordered = slices.sortedBy { it.second }
            check(ordered.zipWithNext().all { (a, b) -> a.second.toLong() + a.third <= b.second }) { "Overlapping Puffin blobs" }
            val selected = slices.filter { it.first <= snapshot }.maxByOrNull { it.first } ?: error("No applicable cumulative Puffin vector")
            return selected.second to selected.third
        }

        internal fun validateVector(bytes: ByteArray) {
            check(bytes.size >= 12) { "Truncated deletion vector" }
            val length = ByteBuffer.wrap(bytes).int
            check(length >= 4 && length.toLong() + 8 == bytes.size.toLong()) { "Deletion-vector length mismatch" }
            check(bytes.copyOfRange(4, 8).contentEquals(byteArrayOf(-47, -45, 57, 100))) { "Deletion-vector magic mismatch" }
            check(CRC32().apply { update(bytes, 4, length) }.value == Integer.toUnsignedLong(ByteBuffer.wrap(bytes, length + 4, 4).int)) {
                "Deletion-vector CRC mismatch"
            }
        }

        private fun validInteger(node: com.fasterxml.jackson.databind.JsonNode?): Boolean =
            node?.isIntegralNumber == true && node.canConvertToLong()
    }
}
