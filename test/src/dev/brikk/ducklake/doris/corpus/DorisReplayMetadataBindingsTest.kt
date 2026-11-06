package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.house.sql.ast.Column
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class DorisReplayMetadataBindingsTest {
    @TempDir lateinit var root: Path
    private val schemas = mapOf("__ducklake_metadata_lake" to "public")

    @Test
    fun rewritesOnlyKnownMetadataTableSchemasInQueriesAndMutations() {
        for (sql in listOf(
            "SELECT * FROM __DUCKLAKE_METADATA_LAKE.MAIN.ducklake_view",
            "UPDATE __ducklake_metadata_lake.main.ducklake_view SET column_aliases=NULL WHERE view_name='v'",
        )) {
            val bound = DorisReplayMetadataBindings.rewrite(sql, schemas)
            val table = Dialects.DUCKDB.parseOne(bound).findAll(Table::class).filterIsInstance<Table>().single()
            assertThat(table.db).isEqualTo("public")
        }
    }

    @Test
    fun qualifiedColumnsAndQuotedIdentifiersAreReboundStructurally() {
        val bound = DorisReplayMetadataBindings.rewrite(
            """SELECT "meta.name".main.t.i FROM "meta.name".main.t""", mapOf("meta.name" to "schema.with.dot"),
        )
        val tree = Dialects.DUCKDB.parseOne(bound)
        assertThat(tree.findAll(Table::class).filterIsInstance<Table>().single().parts.map { it.name })
            .containsExactly("meta.name", "schema.with.dot", "t")
        assertThat(tree.findAll(Column::class).filterIsInstance<Column>().single().parts.map { it.name })
            .containsExactly("meta.name", "schema.with.dot", "t", "i")
    }

    @Test
    fun leavesLogicalSchemasCustomSchemasLiteralsAndCommentsAlone() {
        for (sql in listOf(
            "SELECT '__ducklake_metadata_lake.main.ducklake_view' FROM lake.main.t",
            "SELECT * FROM __ducklake_metadata_lake.custom.ducklake_view",
            "SELECT * FROM unrelated.main.ducklake_view",
            "-- __ducklake_metadata_lake.main.ducklake_view\nSELECT 1",
            "CALL lake.set_option('x','main')",
        )) {
            assertThat(DorisReplayMetadataBindings.rewrite(sql, schemas)).isEqualTo(sql)
        }
    }

    @Test
    fun metadataNamesOnlyInLiteralsDoNotSendNativeShowSyntaxThroughTheAstParser() {
        val sql = "SELECT count(*) FROM (SHOW ALL TABLES) WHERE database='__ducklake_metadata_lake'"
        assertThat(DorisReplayMetadataBindings.rewrite(sql, schemas)).isEqualTo(sql)
    }

    @Test
    fun actualPostgresOracleFixtureUsesPublicWithoutEditingCorpusSql() {
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val source = """
                require ducklake

                statement ok
                INSTALL postgres; LOAD postgres

                statement ok
                ATTACH 'ducklake:{TEST_DIR}/catalog.db' AS lake (DATA_PATH '{TEST_DIR}/data')

                statement ok
                CREATE TABLE lake.t(i INTEGER)

                query I
                SELECT count(*) FROM (SHOW ALL TABLES) WHERE database='__ducklake_metadata_lake'
                ----
                0

                query I
                SELECT count(*) FROM __ducklake_metadata_lake.main.ducklake_table
                ----
                1
            """.trimIndent()
            val file = Files.writeString(root.resolve("metadata.test"), source)
            val report = dorisCorpusRunner(root, metadataRewriter = {
                server.getDuckDbAttachUri().removePrefix("ducklake:")
            }).run(listOf(file))
            assertThat(report.failures).withFailMessage(report.summary()).isEmpty()
            assertThat(report.skippedFiles).isEmpty()
            assertThat(report.totalOracleQueryPasses).isEqualTo(2)
            assertThat(Files.readString(file)).isEqualTo(source)
        }
    }
}
