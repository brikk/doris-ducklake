package dev.brikk.ducklake.doris.corpus

import com.fasterxml.jackson.databind.ObjectMapper
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import dev.brikk.ducklake.corpus.CorpusReport
import dev.brikk.ducklake.corpus.RecordOutcome
import dev.brikk.ducklake.slt.SltQuery
import dev.brikk.ducklake.slt.SltStatement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a corpus selection through the oracle alone, with the same runner setup as the
 * Doris replay but no mirror engine, and writes a receipt of the outcomes. Separates
 * oracle/runner failures from Doris read failures.
 */
internal class DorisCorpusBaselineTest {
    private fun property(name: String): String = requireNotNull(System.getProperty("baseline.$name")) { "Missing baseline.$name" }

    @Test
    fun replaySelectedFilesWithoutDoris() {
        val root = Path.of(property("corpus.root")).toAbsolutePath().normalize()
        val output = Path.of(property("output")).toAbsolutePath()
        val axis = property("axis")
        require(axis in setOf("embedded", "postgres")) { "Unknown baseline axis" }
        val selected = Files.readAllLines(Path.of(property("selection"))).filter { it.isNotBlank() }
        require(selected.isNotEmpty() && selected.distinct().size == selected.size) { "Empty/duplicate baseline selection" }
        val paths = selected.associateWith { relative ->
            root.resolve(relative).normalize().also { require(it.startsWith(root)) { "Selection escapes corpus root" } }
        }
        val hashes = paths.filterValues(Files::isRegularFile).mapValues { digest(it.value) }
        Files.createDirectories(output)
        val identity = runtimeIdentity()
        val server = if (axis == "postgres") TestingDucklakePostgreSqlCatalogServer() else null
        try {
            val counter = AtomicInteger()
            val rewrite: ((String) -> String)? = server?.let { pg -> { _ ->
                val database = "baseline_" + counter.incrementAndGet()
                pg.createDatabase(database)
                pg.getDuckDbAttachUri(database).removePrefix("ducklake:")
            } }
            val runner = dorisCorpusRunner(root, metadataRewriter = rewrite)
            val report = runner.run(paths.values.filter(Files::isRegularFile))
            check(hashes.all { (name, hash) -> digest(paths.getValue(name)) == hash }) { "Corpus changed during replay" }
            writeReport(output, axis, selected, hashes, identity, report)
            assertThat(report.totalMirrorAttempts).isZero()
            assertThat(report.failures).withFailMessage(report.summary(maxFailures = 20)).isEmpty()
            assertThat(report.crashes).withFailMessage(report.summary(maxFailures = 20)).isEmpty()
        } finally {
            server?.close()
        }
    }

    private fun writeReport(output: Path, axis: String, selected: List<String>, hashes: Map<String, String>,
        identity: Map<String, Any>, report: CorpusReport) {
        val files = report.files.map { file -> mapOf(
            "path" to file.path, "sha256" to hashes.getValue(file.path), "file_skip_reason" to file.fileSkipReason,
            "outcomes" to file.outcomes.map { outcome -> mapOf(
                "line" to outcome.record?.line, "kind" to outcome.javaClass.simpleName,
                "sql" to when (val record = outcome.record) {
                    is SltStatement -> record.sql
                    is SltQuery -> record.sql
                    else -> null
                },
                "reason" to when (outcome) {
                    is RecordOutcome.Fail -> outcome.reason
                    is RecordOutcome.Skip -> outcome.reason
                    is RecordOutcome.Pass -> null
                },
            ) },
        ) }
        val receipt = mapOf(
            "schema" to "doris-oracle-baseline-v1", "axis" to axis, "doris_mirrors" to report.totalMirrorAttempts,
            "setup_format" to "1.0", "execution_format_scope" to listOf("1.0"), "metadata_rewrite_scope" to "ALL_ATTACHES",
            "selected" to selected, "missing_files" to selected.filterNot(hashes::containsKey), "runtime" to identity,
            "passed" to report.totalPassed, "failed" to report.failures.size, "skipped_records" to report.totalRecordSkips,
            "ran_files" to report.ranFiles.size, "files" to files,
        )
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("baseline.json").toFile(), receipt)
        Files.writeString(output.resolve("report.txt"), report.summary(maxFailures = Int.MAX_VALUE))
        println("ORACLE_BASELINE axis=$axis selected=${selected.size} present=${files.size} failures=${report.failures.size}")
    }

    private fun runtimeIdentity(): Map<String, Any> = DriverManager.getConnection("jdbc:duckdb:").use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres")
            val version = statement.executeQuery("SELECT version()").use { rows -> check(rows.next()); rows.getString(1) }
            val extensions = statement.executeQuery(
                "SELECT extension_name,extension_version,install_path FROM duckdb_extensions() WHERE extension_name IN ('ducklake','postgres')",
            ).use { rows -> buildList {
                while (rows.next()) add(mapOf("name" to rows.getString(1), "version" to rows.getString(2),
                    "sha256" to digest(Path.of(rows.getString(3)))))
            } }
            val jars = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
                .map(Path::of).filter { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
                .associate { it.fileName.toString() to digest(it) }
            mapOf("duckdb" to version, "jdbc" to connection.metaData.driverVersion, "java" to System.getProperty("java.version"),
                "extensions" to extensions, "classpath_jars_sha256" to jars)
        }
    }

    private fun digest(path: Path): String = MessageDigest.getInstance("SHA-256").let { hash ->
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(8192)
            var count = stream.read(buffer)
            while (count != -1) { hash.update(buffer, 0, count); count = stream.read(buffer) }
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}
