package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.corpus.OracleAttachment
import dev.brikk.ducklake.corpus.ReplayDriver
import dev.brikk.ducklake.corpus.ReplayReadEngine
import dev.brikk.ducklake.slt.SltParser
import dev.brikk.ducklake.catalog.DucklakeCatalogConfig
import dev.brikk.ducklake.catalog.JdbcDucklakeCatalog
import dev.brikk.ducklake.doris.plugin.DuckLakeTypeMapping
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Types
import java.util.Properties

/** Release/candidate private fixture only; never discover shared or retained services. */
internal class DuckLakeReadCorrectnessLiveTest {
    private val p = Properties().apply {
        Files.newInputStream(Path.of(requireNotNull(System.getenv("DORIS_READ_CORRECTNESS_FIXTURE")))).use(::load)
    }
    private val catalog = p.getProperty("catalog")
    private fun fe() = DriverManager.getConnection(p.getProperty("fe.url"), "root", "")

    @Test
    fun nestedFileValuesKeepPunctuationNamesContainersAndNullIdentity() {
        verifyCatalogProjection()
        verifyNativeValues()
    }

    private fun verifyCatalogProjection() {
        JdbcDucklakeCatalog(DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:postgresql://127.0.0.1:${p.getProperty("pg.port")}/${p.getProperty("database")}"
            catalogDatabaseUser = "ducklake"; catalogDatabasePassword = p.getProperty("password")
            dataPath = p.getProperty("data.path")
        }).use { metadata ->
            val at = metadata.currentSnapshotId
            val table = metadata.getTable("tpch", "n03", at)!!
            val projected = metadata.getTableColumns(table.tableId, at)
            val columns = metadata.getAllColumnsWithParentage(table.tableId, at)
            val struct = projected.single { it.columnName == "s" }
            val children = columns.filter { it.parentColumn == struct.columnId }.sortedBy { it.columnOrder }
            assertThat(DuckLakeTypeMapping.fromDucklakeType(struct.columnType).fieldNames)
                .containsExactlyElementsOf(children.map { it.columnName })
            assertThat(children.map { it.columnName }).containsExactly("a,b", "MiXeD", "x:y", "q\"uote", "back\\slash", " spaced ", "雪", "<angle>")
            DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${p.getProperty("pg.port")}/${p.getProperty("database")}",
                "ducklake", p.getProperty("password")).use { pg -> pg.createStatement().use { s ->
                s.executeQuery("SELECT column_id,column_name,parent_column,column_order FROM ducklake_column " +
                    "WHERE table_id=${table.tableId} AND end_snapshot IS NULL ORDER BY column_id").use { r ->
                    val raw = buildList { while (r.next()) add(listOf(r.getLong(1), r.getString(2), r.getObject(3), r.getLong(4))) }
                    assertThat(columns.sortedBy { it.columnId }.map { listOf(it.columnId, it.columnName, it.parentColumn, it.columnOrder) })
                        .isEqualTo(raw)
                }
            } }
        }
    }

    private fun verifyNativeValues() {
        fe().use { c -> c.createStatement().use { s ->
            s.executeQuery("SHOW FRONTENDS").use { r -> check(r.next()); assertThat(r.getString("Version")).contains("4bc7f426828") }
            s.executeQuery("SELECT id,s.`a,b`,s.`MiXeD`,s.`x:y`,s.`q\"uote`,s.`back\\slash`,s.` spaced `,s.`雪`,s.`<angle>`," +
                "struct_element(element_at(items,1),'x:y'),struct_element(element_at(attrs,'k'),'a,b') FROM $catalog.tpch.n03 ORDER BY id").use { r ->
                val actual = buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
                assertThat(actual).containsExactly(
                    listOf("1", "7", "NULL", "12.34", "quote", "slash", "space", "snow", "angle", "list", "map"),
                    listOf("2", null, null, null, null, null, null, null, null, null, null),
                    listOf("3", null, null, null, null, null, null, null, null, null, null),
                )
            }
            s.executeQuery("DESC $catalog.tpch.n03").use { r ->
                val types = buildMap { while (r.next()) put(r.getString(1), r.getString(2)) }
                assertThat(types.getValue("s")).contains("a,b", "MiXeD", "x:y", "q\"uote", "back\\slash", " spaced ", "雪", "<angle>")
            }
            s.executeQuery("SELECT s IS NULL,items IS NULL,attrs IS NULL FROM $catalog.tpch.n03 ORDER BY id").use { r ->
                val nullParents = buildList { while (r.next()) add((1..3).map { r.getBoolean(it) }) }
                assertThat(nullParents).containsExactly(listOf(false, false, false), listOf(true, true, true), listOf(false, false, false))
            }
        } }
    }

    @Test
    fun boundedReplayMeasuresActualSharedLakeMirrorsAndRejectsWrongBooleanHash() {
        val engine = object : ReplayReadEngine {
            override val name = "doris-api27-read-correctness"
            override fun connect(attachment: OracleAttachment) {
                assertThat(attachment.dataPath).isEqualTo(p.getProperty("data.path"))
            }
            override fun accepts(sql: String) = sql.contains("lake.tpch.replay_values")
            override fun executeQuery(sql: String): List<List<String?>> = fe().use { c -> c.createStatement().use { s ->
                s.executeQuery(sql.replace("lake.tpch.replay_values", "$catalog.tpch.replay_values")).use { r ->
                    val boolean = (1..r.metaData.columnCount).map { r.metaData.getColumnType(it) in listOf(Types.BOOLEAN, Types.BIT) }
                    buildList { while (r.next()) add((1..r.metaData.columnCount).map { normalizeReplayCell(r.getObject(it), boolean[it - 1]) }) }
                }
            } }
            override fun close() = Unit
        }
        fun statement(sql: String) = "statement ok\n$sql\n\n"
        val setup = statement("LOAD ducklake; LOAD postgres; LOAD httpfs") +
            statement("CREATE SECRET (TYPE S3,KEY_ID 'f24-fixture',SECRET '${p.getProperty("password")}'," +
                "ENDPOINT '${p.getProperty("minio.host")}',URL_STYLE 'path',USE_SSL false)") +
            statement("ATTACH 'ducklake:postgres:host=127.0.0.1 port=${p.getProperty("pg.port")} dbname=${p.getProperty("database")} " +
                "user=ducklake password=${p.getProperty("password")}' AS lake (DATA_PATH '${p.getProperty("data.path")}'," +
                "DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE)")
        val boolean = "query I nosort\nSELECT b FROM lake.tpch.replay_values ORDER BY id\n----\n" +
            "3 values hashing to 184e6f979a6b9d76049c9b0ff19e1068\n\n"
        val floating = "query RRT nosort\nSELECT f,d,payload FROM lake.tpch.replay_values ORDER BY id\n----\n" +
            "0.1\t1e+16\t\\x00\\x22\\x27\\x5C\\x7F\\xFF\n-0.0\t1e-05\tA\nNULL\tNULL\tNULL\n\n"
        val text = setup + boolean + floating
        val result = ReplayDriver(engine).replay(SltParser.parse("authored-read-correctness.test", text))
        assertThat(result.fileSkipReason).isNull()
        assertThat(result.failed).isEmpty()
        assertThat(result.skipped).isEmpty()
        assertThat(result.queryCoverage!!.mirrorAttempts).isEqualTo(2)
        assertThat(result.queryCoverage!!.mirrorPasses).isEqualTo(2)
        val negative = ReplayDriver(engine).replay(SltParser.parse("authored-wrong-boolean.test",
            setup + boolean.replace("184e6f979a6b9d76049c9b0ff19e1068", "00000000000000000000000000000000") + floating))
        assertThat(negative.failed).hasSize(1)
        assertThat(negative.failed.single().reason).contains("result hash")
        assertThat(negative.queryCoverage!!.mirrorAttempts).isEqualTo(1)
        assertThat(negative.queryCoverage!!.mirrorPasses).isEqualTo(1)
        println("MEASURED same PG/S3 lake: positive mirrors=2/2; wrong-hash=1 failed, later mirror=1/1; JVM=" + System.getProperty("java.version"))
    }
}
