package dev.brikk.ducklake.doris.corpus

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties

/**
 * Guards the connector's read-safety refusals (historical reads over delete files,
 * non-finite temporal predicates) against a live FE, using a fixture described by the
 * `DORIS_READ_SAFETY_FIXTURE` properties file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DorisReadSafetyLiveTest {
    private val p = Properties().apply {
        Files.newInputStream(Path.of(requireNotNull(System.getenv("DORIS_READ_SAFETY_FIXTURE")))).use(::load)
    }
    private val catalog = p.getProperty("catalog")
    private val table = "$catalog.main"
    private var writer: Connection? = null
    private var deletedSnapshot = -1L
    private var safeSnapshot = -1L

    private fun fe() = DriverManager.getConnection(p.getProperty("fe.url"), "root", "")
    private fun sqlText(value: String) = value.replace("'", "''")

    @BeforeAll
    fun seed() {
        writer = DriverManager.getConnection("jdbc:duckdb:").also { connection -> connection.createStatement().use { s ->
            s.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            val target = "postgres:dbname=${p.getProperty("database")} ${p.getProperty("pg.oracle.hostport")} " +
                "user=${p.getProperty("pg.user")} password=${p.getProperty("password")}"
            s.execute("ATTACH 'ducklake:${sqlText(target)}' AS lake (DATA_PATH '${sqlText(p.getProperty("data.path"))}', " +
                "DUCKLAKE_VERSION '1.0',AUTOMATIC_MIGRATION FALSE,DATA_INLINING_ROW_LIMIT 100)")
            seedHistory(s)
            seedTemporals(s)
            assertThat(nativeRows(s, "SELECT count(*),sum(id) FROM lake.history AT (VERSION => $deletedSnapshot)"))
                .containsExactly(listOf("7500", "37500000"))
        } }
        fe().use { connection -> connection.createStatement().use { s ->
            s.execute("CREATE CATALOG `$catalog` PROPERTIES ('type'='ducklake'," +
                "'metadata.url'='jdbc:postgresql://${p.getProperty("pg.fe.hostport")}/${p.getProperty("database")}'," +
                "'metadata.user'='${p.getProperty("pg.user")}','metadata.password'='${p.getProperty("password")}'," +
                "'storage.warehouse'='${sqlText(p.getProperty("data.path"))}')")
        } }
    }

    private fun seedHistory(s: Statement) {
        s.execute("CREATE TABLE lake.history AS SELECT i AS id FROM range(10000) t(i)")
        s.execute("CALL lake.set_option('data_inlining_row_limit',0,table_name=>'history')")
        s.execute("BEGIN; DELETE FROM lake.history WHERE id%8=0; DELETE FROM lake.history WHERE id%4=0; COMMIT")
        deletedSnapshot = nativeRows(s, "SELECT max(snapshot_id) FROM __ducklake_metadata_lake.public.ducklake_snapshot").single().single()!!.toLong()
        s.execute("DELETE FROM lake.history WHERE id%2=0")
        s.execute("CREATE TABLE lake.safe_history AS SELECT 42 AS id")
        s.execute("CALL ducklake_flush_inlined_data('lake')")
        safeSnapshot = nativeRows(s, "SELECT max(snapshot_id) FROM __ducklake_metadata_lake.public.ducklake_snapshot").single().single()!!.toLong()
    }

    private fun seedTemporals(s: Statement) {
        s.execute("CREATE TABLE lake.ts_files(ts TIMESTAMP)")
        s.execute("INSERT INTO lake.ts_files VALUES(TIMESTAMP '1992-01-01'),('infinity')")
        s.execute("CREATE TABLE lake.finite(ts TIMESTAMP,label VARCHAR)")
        s.execute("INSERT INTO lake.finite VALUES(TIMESTAMP '1992-01-01','infinity'),(TIMESTAMP '2020-01-01','-infinity')")
        s.execute("CALL ducklake_flush_inlined_data('lake')")
        s.execute("CREATE TABLE lake.ts_inline(ts TIMESTAMP)")
        s.execute("CALL lake.set_option('data_inlining_row_limit',100,table_name=>'ts_inline')")
        s.execute("INSERT INTO lake.ts_inline VALUES(TIMESTAMP '1992-01-01'),('infinity')")
    }

    @AfterAll fun closeWriter() { writer?.close() }

    @Test
    fun historicalDeletesRefuseWhileLatestAndDeleteFreeHistoryStillReadCorrectly() {
        fe().use { connection -> connection.createStatement().use { s ->
            refused(s, "SELECT count(*),sum(id) FROM $table.history FOR VERSION AS OF $deletedSnapshot", "Historical reads with delete files")
            refused(s, "SELECT id FROM $table.history FOR VERSION AS OF $deletedSnapshot LIMIT 1", "Historical reads with delete files")
            assertThat(nativeRows(s, "SELECT count(*),sum(id) FROM $table.history")).containsExactly(listOf("5000", "25000000"))
            assertThat(nativeRows(s, "SELECT id FROM $table.safe_history FOR VERSION AS OF $safeSnapshot")).containsExactly(listOf("42"))
        } }
        println("READ_SAFETY historical=refused latest=5000 safe-history=42")
    }

    @Test
    fun nonFiniteTemporalsRefuseBeforeFalsePredicatesButVarcharInfinityIsPreserved() {
        fe().use { connection -> connection.createStatement().use { s ->
            for (source in listOf("ts_files", "ts_inline")) {
                for (predicate in listOf("ts='infinity'", "ts<='infinity'", "ts>'-infinity'")) {
                    s.execute("REFRESH CATALOG `$catalog`")
                    refused(s, "SELECT count(*) FROM $table.$source WHERE $predicate", "temporal domain")
                }
            }
            assertThat(nativeRows(s, "SELECT count(*) FROM $table.finite WHERE label='infinity'")).containsExactly(listOf("1"))
        } }
        println("READ_SAFETY temporal=file+inline-refused varchar=preserved")
    }

    @Test
    fun originalMapCorpusMirrorsPassAndJsonLookingVarcharIsNotCanonicalized() {
        val root = Path.of(System.getProperty("ducklake.corpus.root"))
        val file = root.resolve("types/map.test")
        val before = digest(file)
        engine().use { engine ->
            val report = dorisCorpusRunner(root, engine = engine, metadataRewriter = engine.metadataRewriter).run(listOf(file))
            assertThat(report.failures).withFailMessage(report.summary()).isEmpty()
            assertThat(report.skippedFiles).isEmpty()
            assertThat(report.totalMirrorPasses).isEqualTo(5)
            assertThat(report.totalMirrorFailures).isZero()
            val authored = Files.createDirectories(Path.of(p.getProperty("authored.path")))
            val text = """
                require ducklake

                statement ok
                ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake (DATA_PATH '{TEST_DIR}/data')

                statement ok
                CREATE TABLE lake.t(v VARCHAR)

                statement ok
                INSERT INTO lake.t VALUES ('{"i":1}'),('[1,2]'),('NULL'),(''),(NULL)

                query T
                SELECT v FROM lake.t ORDER BY v NULLS FIRST
                ----
                NULL
                (empty)
                NULL
                [1,2]
                {"i":1}
            """.trimIndent()
            val strings = Files.writeString(authored.resolve("strings.test"), text)
            val checked = dorisCorpusRunner(authored, engine = engine, metadataRewriter = engine.metadataRewriter).run(listOf(strings))
            assertThat(checked.failures).withFailMessage(checked.summary()).isEmpty()
            assertThat(checked.skippedFiles).isEmpty()
            assertThat(checked.totalMirrorPasses).isEqualTo(1)
            assertThat(Files.readString(strings)).isEqualTo(text)
        }
        assertThat(digest(file)).isEqualTo(before)
        println("READ_SAFETY map mirrors=5/5 varchar=1/1")
    }

    private fun engine() = DorisReplayEngine(
        feJdbcUrl = p.getProperty("fe.url") + "&user=root",
        pgHostUrl = p.getProperty("pg.url"), pgUser = p.getProperty("pg.user"), pgPassword = p.getProperty("password"),
        pgOracleHostPort = p.getProperty("pg.oracle.hostport"), pgFeHostPort = p.getProperty("pg.fe.hostport"),
    )

    private fun refused(s: Statement, sql: String, message: String) {
        assertThatThrownBy { s.executeQuery(sql).use { rows -> while (rows.next()) Unit } }
            .isInstanceOf(SQLException::class.java).hasMessageContaining(message)
    }

    private fun nativeRows(s: Statement, sql: String): List<List<String?>> = s.executeQuery(sql).use { rows ->
        buildList { while (rows.next()) add((1..rows.metaData.columnCount).map(rows::getString)) }
    }

    private fun digest(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
}
