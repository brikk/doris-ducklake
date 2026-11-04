package dev.brikk.ducklake.doris.corpus

import dev.brikk.ducklake.corpus.CorpusRunner
import dev.brikk.ducklake.corpus.ReplayReadEngine
import java.nio.file.Path

/** Historical candidate API, without the 0.13.1 setup-format contract; not the full campaign lane. */
internal fun dorisCorpusRunner(
    root: Path,
    skips: Map<String, String> = emptyMap(),
    engine: ReplayReadEngine? = null,
    metadataRewriter: ((String) -> String)? = null,
): CorpusRunner = CorpusRunner(root, skips, engine, metadataRewriter)
