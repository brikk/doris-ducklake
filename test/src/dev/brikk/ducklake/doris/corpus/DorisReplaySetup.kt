package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.corpus.CorpusRunner
import dev.brikk.ducklake.corpus.ReplayReadEngine
import dev.brikk.ducklake.corpus.ReplayOracleSqlBinder
import dev.brikk.ducklake.corpus.ReplaySetupFormat
import java.nio.file.Path

/**
 * Builds the corpus runner pinned to DuckLake 1.0 for setup, execution and mirroring;
 * fixtures that explicitly ask for 1.1 are left untouched rather than rewritten.
 */
internal fun dorisCorpusRunner(
    root: Path,
    skips: Map<String, String> = emptyMap(),
    engine: ReplayReadEngine? = null,
    metadataRewriter: ((String) -> String)? = null,
): CorpusRunner = CorpusRunner(
    root, skips, engine, metadataRewriter,
    setupFormat = ReplaySetupFormat.DUCKLAKE_1_0,
    mirrorFormatScope = setOf(ReplaySetupFormat.DUCKLAKE_1_0),
    executionFormatScope = setOf(ReplaySetupFormat.DUCKLAKE_1_0),
    oracleSqlBinder = ReplayOracleSqlBinder { sql, context ->
        val schemas = context.lakes.filter {
            it.rewritten && it.explicitMetadataSchema == null && it.attachment.metadataUri.startsWith("postgres:")
        }.associate { it.metadataCatalog to "public" }
        if (schemas.isEmpty()) sql else DorisReplayMetadataBindings.rewrite(sql, schemas)
    },
)
