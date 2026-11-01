package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeTypeSignature
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import org.apache.doris.thrift.TFileScanRangeParams
import org.apache.doris.thrift.TPrimitiveType
import org.apache.doris.thrift.schema.external.TField

internal class DuckLakeNestedSignatureTest {
    @TempDir lateinit var root: Path

    @Test
    fun quotedNamesDecimalCommasAndRecursiveTimezoneMappingAreLossless() {
        val names = listOf("a,b", "x:y", "<angle>", "q\"uote", "back\\slash", " spaced ", "雪", "MiXeD")
        val fields = names.joinToString(",") { "\"${it.replace("\"", "\"\"")}\":DECIMAL(18,2)" }
        for (signature in listOf("STRUCT<$fields>", "LIST<STRUCT<$fields>>", "MAP<VARCHAR,STRUCT<$fields>>")) {
            val type = DuckLakeTypeMapping.fromDucklakeType(signature)
            val struct = when (type.typeName) { "ARRAY" -> type.children.single(); "MAP" -> type.children[1]; else -> type }
            assertThat(struct.fieldNames).containsExactlyElementsOf(names)
            assertThat(struct.children).allSatisfy {
                assertThat(it.typeName).isEqualTo("DECIMALV3")
                assertThat(it.precision).isEqualTo(18)
                assertThat(it.scale).isEqualTo(2)
            }
        }
        val on = DuckLakeTypeMapping.fromDucklakeType("struct<MiXeD:list<timestamptz>>", true)
        assertThat(on.fieldNames).containsExactly("MiXeD")
        assertThat(on.children.single().children.single().typeName).isEqualTo("TIMESTAMPTZ")
        val off = DuckLakeTypeMapping.fromDucklakeType("struct<MiXeD:list<timestamptz>>")
        assertThat(off.children.single().children.single().typeName).isEqualTo("DATETIMEV2")
    }

    @Test
    fun malformedArityBudgetsAndDorisAmbiguousNamesRefuseWithoutFallback() {
        for (signature in listOf("map<int32>", "list<int32,int32>", "struct<a:int32,>", "struct<\"unterminated:int32>",
            "decimal(39,0)", "map<int32,int32,int32>", "struct<a:unknown>", "struct<\"\":int32>", "struct<A:int32,a:int64>",
            "list<".repeat(66) + "int32" + ">".repeat(66), "struct<" + (1..4096).joinToString(",") { "f$it:int32" } + ">",
            " ".repeat(DucklakeTypeSignature.MAX_LENGTH + 1))) {
            assertThatThrownBy { DuckLakeTypeMapping.fromDucklakeType(signature) }
                .describedAs(signature.take(100)).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun nativeCatalogProjectionAndDorisMetadataRetainNamesOrderAndStableIds() {
        val database = root.resolve("catalog.db")
        val data = root.resolve("data")
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake")
            s.execute("ATTACH 'ducklake:$database' AS lake (DATA_PATH '$data',DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)")
            s.execute("CALL lake.set_option('data_inlining_row_limit',0)")
            s.execute("CREATE TABLE lake.t(id INTEGER,s STRUCT(\"a,b\" INTEGER,\"MiXeD\" VARCHAR,\"x:y\" DECIMAL(18,2)))")
            s.execute("INSERT INTO lake.t VALUES(1,{'a,b':7,'MiXeD':'NULL','x:y':12.34}),(2,NULL)")
        } }
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:duckdb:$database"; dataPath = data.toString()
        }).use { c ->
            val snapshot = c.currentSnapshotId
            val table = c.getTable("main", "t", snapshot)!!
            val before = c.getTableColumns(table.tableId, snapshot)
            val top = before.filter { it.parentColumn == null }
            assertThat(top.map { it.columnName }).containsExactly("id", "s")
            val nested = DuckLakeTypeMapping.fromDucklakeType(top[1].columnType)
            assertThat(nested.fieldNames).containsExactly("a,b", "MiXeD", "x:y")
            val handle = DuckLakeTableHandle("main", "t", c.getSchema("main", snapshot)!!.schemaId, table.tableId, snapshot)
            val schema = DuckLakeConnectorMetadata(c).getTableSchema(null, handle)
            assertThat(schema.columns.map { it.name }).containsExactly("id", "s")
            assertThat(schema.columns[1].type.fieldNames).containsExactly("a,b", "MiXeD", "x:y")
            assertThat(c.getTableColumns(table.tableId, snapshot)).isEqualTo(before)
            assertThat(before.map { it.columnId }.distinct()).hasSameSizeAs(before)
            val handles = DuckLakeConnectorMetadata(c).getColumnHandles(null, handle).values.map { it as DuckLakeColumnHandle }
            val encoded = encodeReadSchemaDictionary(c, handle, handles, emptyList(), false)
            val wire = TFileScanRangeParams()
            DuckLakeSchemaDictionary.apply(wire, encoded)
            val fields = wire.historySchemaInfo.single().rootField.fields.flatMap { flatten(it.fieldPtr) }
            val raw = c.getAllColumnsWithParentage(table.tableId, snapshot)
            assertThat(fields.sortedBy { it.first }).isEqualTo(raw.sortedBy { it.columnId }.map { it.columnId.toInt() to it.columnName.lowercase(java.util.Locale.ROOT) })
            assertThatThrownBy { encodeReadSchemaDictionary(c, handle, handles, emptyList(), true) }
                .hasMessageContaining("nested legacy-file mappings are not qualified")
            assertThatThrownBy { encodeReadSchemaDictionary(c, handle.copy(snapshotId = snapshot - 1), handles, emptyList(), false) }
                .hasMessageContaining("historical nested file scans are not qualified")
        }
    }

    private fun flatten(field: TField): List<Pair<Int, String>> {
        val nested = when (field.type.type) {
            TPrimitiveType.STRUCT -> field.nestedField.structField.fields.map { it.fieldPtr }
            TPrimitiveType.ARRAY -> listOf(field.nestedField.arrayField.itemField.fieldPtr)
            TPrimitiveType.MAP -> listOf(field.nestedField.mapField.keyField.fieldPtr, field.nestedField.mapField.valueField.fieldPtr)
            else -> emptyList()
        }
        return listOf(field.id to field.name) + nested.flatMap(::flatten)
    }
}
