package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.doris.corpus.dorisCorpusRunner
import dev.brikk.ducklake.corpus.CorpusRunner
import dev.brikk.ducklake.corpus.ReplaySetupFormat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Exercise the same factory as the Doris campaign against the published replay JAR. */
internal class DuckLakeReplaySetupFormatTest {
    @TempDir lateinit var root: Path

    @Test
    fun ordinaryCorpusAttachCreatesOneZeroWithoutRewritingSql() {
        replay("", "1.0")
    }

    @Test
    fun explicitOneOneHaltsTheOneZeroLaneBeforeFeatureSql() {
        val sql = """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake (DATA_PATH '{TEST_DIR}/data', DUCKLAKE_VERSION '1.1')

            statement ok
            THIS MUST NEVER EXECUTE
        """.trimIndent()
        val file = Files.writeString(root.resolve("one-one.test"), sql)
        val report = dorisCorpusRunner(root).run(listOf(file))
        assertThat(report.failures).isEmpty()
        assertThat(report.ranFiles).isEmpty()
        assertThat(report.haltedFiles).hasSize(1)
        assertThat(report.files.single().fileSkipReason).contains("format-out-of-scope", "1.1")
        assertThat(report.totalMirrorPasses).isZero()
        assertThat(Files.readString(file)).isEqualTo(sql)
    }

    @Test
    fun existingOneOneCannotBeSilentlyDowngraded() {
        val file = Files.writeString(root.resolve("newer.test"), """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake (DATA_PATH '{TEST_DIR}/data', DUCKLAKE_VERSION '1.1')

            statement ok
            DETACH lake

            statement error
            ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake
            ----
            Cannot downgrade a DuckLake catalog

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake (DUCKLAKE_VERSION '1.1')

            query I
            SELECT value FROM __ducklake_metadata_lake.ducklake_metadata WHERE key='version'
            ----
            1.1
        """.trimIndent())
        // Uses the plain CorpusRunner: this checks the native downgrade contract, which the Doris runner's 1.0 lane would halt on.
        val report = CorpusRunner(root, setupFormat = ReplaySetupFormat.DUCKLAKE_1_0).run(listOf(file))
        assertThat(report.skippedFiles).isEmpty()
        assertThat(report.failures).isEmpty()
        assertThat(report.totalPassed).isEqualTo(5)
    }

    private fun replay(options: String, version: String) {
        val sql = """
            require ducklake

            statement ok
            ATTACH 'ducklake:{TEST_DIR}/v.db' AS lake (DATA_PATH '{TEST_DIR}/data'$options)

            query I
            SELECT value FROM __ducklake_metadata_lake.ducklake_metadata WHERE key='version'
            ----
            $version
        """.trimIndent()
        val file = Files.writeString(root.resolve("format.test"), sql)
        val report = dorisCorpusRunner(root).run(listOf(file))
        assertThat(report.skippedFiles).isEmpty()
        assertThat(report.failures).isEmpty()
        assertThat(report.totalPassed).isEqualTo(2)
        assertThat(Files.readString(file)).isEqualTo(sql)
    }
}
