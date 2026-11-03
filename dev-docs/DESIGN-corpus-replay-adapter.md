# Corpus replay contract and usage

The replay adapter mirrors **read-only queries** against state built by the
embedded native DuckDB oracle. It never translates/exercises DuckDB writes through
Doris. The oracle validates sqllogictest semantics; the Doris adapter compares live
rows against the same catalog, with explicit dialect/unsupported-case skips.
Historical green counts are not current-runtime coverage.

## Current workflow

Initialize the pinned `ducklake/` corpus submodule. Prepare the
[matched development stack](COMPOSE.md), PostgreSQL host access and a same-absolute-path
host/FE/BE data mount. The standard task uses `dorisCorpusDir` (default
`/tmp/ducklake-corpus`) for its temporary files; match `DORIS_CORPUS_DIR` on the
container side. Remote-daemon file sharing is an explicit prerequisite, not
provided by an SSH SQL tunnel.

```sh
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --up-only
./gradlew corpusReplayTest
./gradlew corpusReplayTest -Dducklake.corpus.dirs=delete
```

Starter directories are `general,catalog,time_travel,view`. A full corpus run uses
`-Dducklake.corpus.dirs=all` but is an explicit long-running campaign, not an
interactive default or a consequence of documentation cleanup. The ordinary
`test` and `java17PluginTest` tasks do not run live corpus replay.

The existing harness probes local FE SQL at 127.0.0.1:9030 and PostgreSQL at
localhost:9432 by default. Endpoint property forwarding remains an open finding
(M15); do not assume arbitrary `-D` options reach a forked test JVM. Missing FE,
PostgreSQL, corpus or DuckDB extensions can assumption-skip this task today (M14).
Read XML and actual mirrored counts; zero failures is not nonzero coverage.

## Adapter invariants

- Native JDBC initializes the oracle's DuckLake/PostgreSQL extensions. Each engine
  instance uses UUID-owned catalog/database names and cleans only acknowledged
  resources. Abandoned prefix-matching databases are never swept at startup.
- Refresh metadata before mirrored reads after an external writer. Name reuse is
  not permission to drop a foreign catalog/database.
- `DorisCorpusDialect`/SQL adaptation deny unsupported forms and rewrite accepted
  reads. Literal/quoted-name rewrite fidelity and broad error-to-skip classification
  remain M13/M16, not proof that every accepted query is safe.
- Normalize real cells, preserve SQL NULL versus literal `NULL`, and use pinned
  replay rendering for Float/Double. No production replay dependency or fabricated
  golden values are introduced.
- Report oracle checks, accepted/attempted/successful mirrors, skips and failures
  separately. One query skipped before mirroring cannot contribute mirror coverage.

Current focused native/quoted/Boolean/float replay acceptance is independently
strict and measured on Java25/17; see [CATALOG-CANDIDATE.md](CATALOG-CANDIDATE.md).
It does not repair every broad-corpus M13–M17 finding or qualify a full campaign.

The original interface sketch, bring-up checklist and old counts are retained in
[the archived design](archive/DESIGN-corpus-replay-adapter-original.md).
