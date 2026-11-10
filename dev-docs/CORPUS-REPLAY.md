# Corpus replay

The upstream DuckLake repository (the `ducklake/` submodule) carries a sqllogictest corpus.
The replay harness builds each test's state with an embedded DuckDB oracle, then mirrors
the test's **read-only** queries through a live Doris FE and compares rows. Writes are never
routed through Doris. Queries the dialect gate can't translate, and shapes the connector
refuses, are recorded as skips with a reason; a skip is never counted as a mirror.

## Running it

Needs a running stack ([COMPOSE.md](COMPOSE.md)), PostgreSQL reachable from the host, and a
directory mounted into the BE at the same absolute path as on the host (`dorisCorpusDir`,
default `/tmp/ducklake-corpus`, matched by `DORIS_CORPUS_DIR` on the container side). An
SSH tunnel for SQL does not provide that shared path.

```sh
git submodule update --init ducklake
DORIS_BE_PLATFORM=linux/amd64 compose/smoke.sh --up-only
./gradlew corpusReplayTest                                  # starter dirs: general, catalog, time_travel, view
./gradlew corpusReplayTest -Dducklake.corpus.dirs=delete
./gradlew corpusReplayTest -Dducklake.corpus.dirs=all       # the full corpus; a long campaign
```

Defaults: FE at `127.0.0.1:9030`, PostgreSQL at `localhost:9432`
(`doris.corpus.fe.url`, `doris.corpus.pg.host.url`, `…pg.user`, `…pg.password`). Forwarding
of `-D` options into the forked test JVM is incomplete (M15), and a missing FE, PostgreSQL
or DuckDB extension currently skips rather than fails (M14) — always read the mirror counts.

## How it works

- `DorisReplaySetup` creates a DuckLake 1.0 lake per test on UUID-named PostgreSQL databases
  and cleans up only those. An explicit 1.1 `ATTACH`, or an unexpected native SQL
  migration-refusal diagnostic for a 1.1-only feature, halts the file with one
  format-out-of-scope skip, not a pass. Earlier failures and measured mirror coverage
  remain; unexecuted records add no counts. Matching expected-error/maybe records
  still pass and continue. Other errors keep failing.
- The oracle's SQL is rebound from DuckDB's `main` metadata schema to PostgreSQL's `public`
  structurally, without touching literals or custom metadata schemas. Corpus files are
  never edited.
- `DorisCorpusDialect` transpiles each query DuckDB → Doris with brikk-sql and certifies it;
  anything it can't certify is a skip with the classifier's reason.
- `DorisReplayEngine` refreshes catalog metadata after the oracle writes, runs the query,
  normalizes cells with `DorisValueNormalizer` (SQL NULL stays distinct from the string
  `NULL`), and compares.
- Results report oracle checks, accepted / attempted / successful mirrors, skips and
  failures separately.

## Campaign controller

`compose/corpus_acceptance.py` runs a full-directory or selected-file campaign on fresh
UUID-owned infrastructure with resource limits (6 GiB, no swap, 300% CPU, one Gradle
worker). It refuses zero successful mirrors and skipped/missing JUnit execution, keeps all
failures, and distinguishes 1.1 halts from files that ran to the end.

```sh
python3 compose/corpus_acceptance.py \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/f24-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6 \
  --evidence-root build/validation/<name> \
  [--file-list /absolute/path/selection.txt]
```

Existing curated exclusions and the 40-mirrors-per-file cap still apply. Scratch for
replay goes to an owned directory outside every oracle `TEST_DIR`.

## Oracle-only baseline

`compose/corpus_baseline.py` runs `corpusBaselineTest` with no Doris at all, to separate
corpus/producer failures from connector ones. It snapshots the requested corpus commit,
records extension/jar/source hashes and every record outcome. `corpus_baseline_triage.py`
joins those outcomes to original failures and optional producer-pin counterparts.

```sh
python3 compose/corpus_baseline.py --corpus-checkout ducklake --corpus-ref <sha> \
  --failure-index <failure-index.csv> --axis embedded --evidence-root build/validation/corpus-baseline
```

The most recent classification of retained failures is
[archive/VALIDATION-CORPUS-BASELINE-2026-11-04.md](archive/VALIDATION-CORPUS-BASELINE-2026-11-04.md).
The original design sketch is [archive/DESIGN-corpus-replay-adapter-original.md](archive/DESIGN-corpus-replay-adapter-original.md).
