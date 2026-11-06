package dev.brikk.ducklake.doris.corpus

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

import dev.brikk.ducklake.corpus.CorpusReport
import dev.brikk.ducklake.corpus.CorpusRunner
import dev.brikk.ducklake.corpus.FileResult

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import org.assertj.core.api.Assertions.assertThat

/**
 * Replays the upstream DuckLake corpus with Doris mirroring the lake reads against
 * the DuckDB oracle. Driven by the `corpusReplayTest` gradle task; every
 * precondition is assume-gated, so a plain `test` run never fails on them:
 * a live compose cluster (`compose/smoke.sh --up-only`), `java.io.tmpdir` inside
 * the compose bind-mount so the containerized BE sees the oracle's files at the
 * same absolute paths, installable duckdb `ducklake`/`postgres` extensions, and an
 * initialized corpus submodule.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DorisCorpusReplayTest {

    private val corpusRoot: Path =
        Path.of(
            System.getProperty("ducklake.corpus.root")
                ?: "../ducklake-corpus-replay/ducklake/test/sql",
        )

    private val skipList: Map<String, String> =
        mapOf(
            "general/metadata_parameters.test" to
                "custom METADATA_SCHEMA/METADATA_PARAMETERS not threaded to the FE catalog properties",
            "metadata/ducklake_settings.test" to
                "asserts metadata backend type 'duckdb'; the PG backend-axis rewrite makes it 'postgres' by design",
            "data_inlining/data_inlining_encryption.test" to
                "ENCRYPTED DuckLake attach (DuckDB-only parquet encryption); not a Doris read path",
            "data_inlining/data_inlining_per_schema_alter.test" to
                "column DEFAULT values over inlined data (same gap as issue_1135)",
            "data_inlining/data_inlining_update_inline_verification.test" to
                "UPDATE produces inlined file-deletions the connector can't apply",
            "data_inlining/data_inlining_transaction_local_delete.test" to
                "inlined transaction-local deletes the connector can't apply",
            // These count warehouse files via recursive GLOB('<data_path>/**'). The
            // inlined-data read synthesizes a temp Parquet on the shared warehouse
            // storage (the only path the BE can reach), so the count comes back N+1.
            // The lake reads themselves mirror clean.
            "data_inlining/basic_data_inlining.test" to
                "GLOB('<data_path>/**') counts our synthesized inlined-scratch file — inherent to the " +
                "temp-file-on-shared-storage approach",
            "data_inlining/data_inlining_option.test" to
                "GLOB('<data_path>/**') counts our synthesized inlined-scratch file (see basic_data_inlining)",
            "data_inlining/data_inlining_delete.test" to
                "GLOB('<data_path>/**') counts our synthesized inlined-scratch file (see basic_data_inlining)",
            "issues/issue_865_update_wrong_result.test" to
                "inlined file-deletes (DATA_INLINING_ROW_LIMIT 10): UPDATE mixing a committed delete file " +
                "with inlined file-deletions — the known inlined-delete gap",
            "issues/view_tosql_performance.test" to
                "DuckLake view + duckdb_views(): views are not surfaced by the connector (dialect skip)",
            "issues/corrupted_catalog_fault_isolation.test" to
                "fault-injection harness test (deliberately corrupts the catalog / expects DuckDB-specific " +
                "error text) — not mirrorable against an external engine",
            // Hive partition fill works for the common case (add_files_hive.test and
            // add_files_hive_many_columns.test pass); the cast test hits two BE-side
            // limits of columns_from_path that can't be fixed in the FE plugin.
            "add_files/add_files_hive_partition_cast.test" to
                "BE columns_from_path limits: (1) a NULL hive partition value cannot be represented " +
                    "(the sentinel/is-null flag are not converted to SQL NULL and a NULL predicate over " +
                    "it crashes the reader — the connector fails loud on these), and (2) a BOOLEAN path " +
                    "partition column is not typed for boolean ops (NOT/AND) — the file's own body-absent " +
                    "cases (int/string/date/decimal/timestamp/float/special-char) DO read correctly",
            // These register id-less parquet files whose physical column names collide
            // across a DROP+re-ADD field-id boundary (a file's `col2` is the dropped
            // field-id 2; the live table's `col2` is the new field-id 4). Resolving that
            // needs per-file schema info, but the SPI schema dictionary is scan-node
            // level. The conflict-aware union makes the BE error ("name_mapping must be
            // set") instead of binding wrong rows.
            "add_files/add_files.test" to
                "per-file mapping unsolvable table-level: DROP+re-ADD reuses physical name `col2` across " +
                "field-id boundary over id-less files; needs per-range schema info",
            "delete/delete_legacy_missing_mapping_after_rename_add_files.test" to
                "per-file mapping unsolvable table-level: legacy id-less file after rename+add_files (same " +
                "root cause as add_files.test)",
            "stats/variant_mixed_type_stats.test" to
                "VARIANT-typed columns: the BE parquet reader cannot read DuckLake's variant encoding " +
                "([INTERNAL_ERROR] on read). Variant read support is a separate gap, not a dialect/mirror issue",
            "stats/filter_stress.test" to
                "oracle-local cross-check: compares the lake against a non-lake DuckDB reference table " +
                "(events_ref) via EXCEPT ALL; the reference table only exists oracle-side, so it can't be " +
                "mirrored (not a Doris read gap)",
            "add_files/add_files_type_check_timestamp.test" to
                "ORACLE golden gap: GoldenComparator renders micros only, golden expects timestamp_ns nanos " +
                "(runner-side)",
        )

    @BeforeAll
    fun setUp() {
        val ducklakeOk =
            runCatching {
                DriverManager.getConnection("jdbc:duckdb:").use { c ->
                    c.createStatement().use {
                        it.execute("INSTALL ducklake")
                        it.execute("LOAD ducklake")
                        it.execute("INSTALL postgres")
                        it.execute("LOAD postgres")
                    }
                }
            }.isSuccess
        assumeTrue(ducklakeOk, "ducklake/postgres duckdb extensions not installable (network?)")
        assumeTrue(Files.isDirectory(corpusRoot), "corpus submodule not initialized at $corpusRoot")
        assumeTrue(feAlive(), "no live Doris FE at 127.0.0.1:9030 — run compose/smoke.sh --up-only")
        assumeTrue(pgAlive(), "substrate PostgreSQL not host-mapped at localhost:9432 — is the compose up?")
        // Deliberately no sweep of leftover corpus_doris_* databases: a matching name
        // may belong to a concurrent run, and DorisReplayEngine.close() only reclaims
        // what it created itself.
    }

    private fun feAlive(): Boolean =
        runCatching {
            DriverManager.getConnection(
                System.getProperty("doris.corpus.fe.url", "jdbc:mysql://127.0.0.1:9030/?user=root"),
            ).use { c -> c.createStatement().use { it.executeQuery("SELECT 1").next() } }
        }.getOrDefault(false)

    private fun pgAlive(): Boolean =
        runCatching {
            DriverManager.getConnection(
                System.getProperty("doris.corpus.pg.host.url", "jdbc:postgresql://localhost:9432/ducklake"),
                System.getProperty("doris.corpus.pg.user", "ducklake"),
                System.getProperty("doris.corpus.pg.password", "ducklake"),
            ).use { c -> c.createStatement().use { it.executeQuery("SELECT 1").next() } }
        }.getOrDefault(false)

    @Test
    fun corpusLakeReadsMirrorThroughDoris() {
        val discovery = CorpusRunner(corpusRoot, skipList)
        val dirsProp = System.getProperty("ducklake.corpus.dirs", "general,catalog,time_travel,view")
        val files =
            if (System.getProperty("ducklake.corpus.files") != null) {
                val selected = Files.readAllLines(Path.of(System.getProperty("ducklake.corpus.files")))
                require(selected.isNotEmpty() && selected.distinct().size == selected.size) { "Empty/duplicate corpus selection" }
                selected.map { name -> corpusRoot.resolve(name).normalize().also {
                    require(!Path.of(name).isAbsolute && it.startsWith(corpusRoot.normalize()) && Files.isRegularFile(it)) {
                        "Invalid selected corpus path: $name"
                    }
                } }
            } else if (dirsProp == "all") {
                discovery.discover()
            } else {
                dirsProp.split(',').flatMap { discovery.discover(it.trim()) }
            }
        assertThat(files).isNotEmpty()

        // Each per-file FE catalog carries a HikariCP pool inside the plugin; a fresh
        // engine per chunk bounds PG connection usage.
        val results = mutableListOf<FileResult>()
        for (chunk in files.chunked(CHUNK_SIZE)) {
            DorisReplayEngine().use { engine ->
                val runner =
                    dorisCorpusRunner(
                        corpusRoot,
                        skipList,
                        engine = engine,
                        metadataRewriter = engine.metadataRewriter,
                    )
                results += runner.run(chunk).files
            }
            System.getProperty("doris.corpus.report.path")?.let { reportPath ->
                val path = Path.of(reportPath)
                Files.createDirectories(path.toAbsolutePath().parent)
                Files.writeString(path, "Selected files: ${files.size}\n" + CorpusReport(results).summary(maxFailures = Int.MAX_VALUE))
            }
            println("CORPUS_PROGRESS ${results.size}/${files.size} selected files processed")
        }
        val report = CorpusReport(results)
        println(report.summary(maxFailures = MAX_FAILURES_SHOWN))

        assertThat(report.failures)
            .withFailMessage { "doris corpus-mirror failures:\n" + report.summary(maxFailures = MAX_FAILURES_SHOWN) }
            .isEmpty()
        assertThat(report.crashes)
            .withFailMessage { "harness crashes:\n" + report.summary(maxFailures = MAX_FAILURES_SHOWN) }
            .isEmpty()
    }

    companion object {
        private const val CHUNK_SIZE = 30
        private const val MAX_FAILURES_SHOWN = 40
        private const val GAP_NAME_MAPPING =
            "KNOWN GAP: ducklake_name_mapping (add_files field-id mapping / renamed columns over legacy " +
                "files) not applied on the Doris scan"
    }
}
