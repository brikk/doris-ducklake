package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import org.apache.doris.thrift.TFileScanRangeParams
import org.apache.doris.thrift.TPrimitiveType
import org.apache.doris.thrift.schema.external.TField
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Locale

internal class DuckLakeNativeNestedDictionaryTest {
    @TempDir lateinit var root: Path

    @Test
    fun mixedScalarAndNestedDescriptorsContainEveryRealNativeFieldId() = fixture { catalog, handle, columns ->
        val encoded = encodeReadSchemaDictionary(catalog, handle, columns, emptyList(), false)
        val sent = TFileScanRangeParams()
        DuckLakeSchemaDictionary.apply(sent, encoded)
        val raw = catalog.getAllColumnsWithParentage(handle.tableId, handle.snapshotId)
        val fields = sent.historySchemaInfo.single().rootField.fields.map { it.fieldPtr }
        assertThat(fields.map { it.type.type }).containsExactly(TPrimitiveType.STRING, TPrimitiveType.STRUCT, TPrimitiveType.ARRAY, TPrimitiveType.MAP)
        assertThat(fields.flatMap(::flatten).sortedBy { it.first }).isEqualTo(
            raw.sortedBy { it.columnId }.map { it.columnId.toInt() to it.columnName.lowercase(Locale.ROOT) },
        )
        assertThat(raw).hasSize(11)
        // All-nested projections must work without an existing scalar carrier, too.
        val onlyNested = encodeReadSchemaDictionary(catalog, handle, columns.drop(1), emptyList(), false)
        val nestedSent = TFileScanRangeParams()
        DuckLakeSchemaDictionary.apply(nestedSent, onlyNested)
        assertThat(nestedSent.historySchemaInfo.single().rootField.fields).hasSize(3)
    }

    @Test
    fun unqualifiedNestedHistoryAndLegacyMappingsRefuseButScalarPathIsUnchanged() = fixture { catalog, handle, columns ->
        assertThatThrownBy { encodeReadSchemaDictionary(catalog, handle, columns, emptyList(), true) }
            .hasMessageContaining("nested legacy-file mappings are not qualified")
        assertThatThrownBy { encodeReadSchemaDictionary(catalog, handle.copy(snapshotId = handle.snapshotId - 1), columns, emptyList(), false) }
            .hasMessageContaining("historical nested file scans are not qualified")
        val scalar = columns.take(1)
        assertThat(encodeReadSchemaDictionary(catalog, handle, scalar, emptyList(), true))
            .isEqualTo(DuckLakeSchemaDictionary.encode(scalar, emptyList()))
    }

    private fun fixture(test: (JdbcDucklakeCatalog, DuckLakeTableHandle, List<DuckLakeColumnHandle>) -> Unit) {
        val database = root.resolve("metadata.db")
        val data = root.resolve("data")
        DriverManager.getConnection("jdbc:duckdb:").use { connection -> connection.createStatement().use { statement ->
            statement.execute("INSTALL ducklake; LOAD ducklake")
            val legacy = statement.executeQuery("SELECT version()").use { rows ->
                check(rows.next())
                if (rows.getString(1).startsWith("v2.")) ",DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE" else ""
            }
            statement.execute("ATTACH 'ducklake:$database' AS lake (DATA_PATH '$data'$legacy)")
            statement.execute("CALL lake.set_option('data_inlining_row_limit',0)")
            statement.execute("CREATE TABLE lake.t(id INTEGER,s STRUCT(a INTEGER,label VARCHAR)," +
                "items STRUCT(x VARCHAR)[],attrs MAP(VARCHAR,STRUCT(a VARCHAR)))")
            statement.execute("INSERT INTO lake.t VALUES(1,{'a':7,'label':'NULL'},[{'x':'list'}],map(['k'],[{'a':'map'}]))")
        } }
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:duckdb:$database"
            dataPath = data.toString()
        }).use { catalog ->
            assertThat(catalog.getSpecVersion()).isIn("0.4", "1.0")
            val at = catalog.currentSnapshotId
            val table = catalog.getTable("main", "t", at)!!
            val handle = DuckLakeTableHandle("main", "t", catalog.getSchema("main", at)!!.schemaId, table.tableId, at)
            val columns = DuckLakeConnectorMetadata(catalog).getColumnHandles(null, handle).values.map { it as DuckLakeColumnHandle }
            test(catalog, handle, columns)
        }
    }

    private fun flatten(field: TField): List<Pair<Int, String>> {
        val children = when (field.type.type) {
            TPrimitiveType.STRUCT -> field.nestedField.structField.fields.map { it.fieldPtr }
            TPrimitiveType.ARRAY -> listOf(field.nestedField.arrayField.itemField.fieldPtr)
            TPrimitiveType.MAP -> listOf(field.nestedField.mapField.keyField.fieldPtr, field.nestedField.mapField.valueField.fieldPtr)
            else -> emptyList()
        }
        return listOf(field.id to field.name) + children.flatMap(::flatten)
    }
}
