# Corpus replay contract and usage

The replay adapter mirrors **read-only queries** against state built by the
embedded native DuckDB oracle. It never translates/exercises DuckDB writes through
Doris. The oracle validates sqllogictest semantics; the Doris adapter compares live
rows against the same catalog, with explicit dialect/unsupported-case skips.
Historical green counts are not current-runtime coverage.

## Current workflow

Catalog **0.17.0** retains 0.14.0's independent replay lanes. The released Doris factory
selects `DUCKLAKE_1_0` for setup, execution and mirroring. Explicit 1.1 attachments
halt the 1.0 file before its feature SQL and are reported as format-scope halts,
not passed files. Explicit ATTACH versions and SET/RESET retain their native
semantics; existing newer catalogs fail a downgrade.
The oracle SQL binder structurally rebinds only known rewritten PostgreSQL
metadata catalogs from native `main` to `public`, preserving logical schemas,
custom metadata schemas and SQL literal values. No corpus/golden files are edited.
This fixes the [0.13.0 bootstrap blocker](VALIDATION-catalog-0.13.0-2026-11-03.md#full-corpus-follow-up-bootstrap-format-blocker).
The historical candidate keeps its original replay API and is not this campaign lane.

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
  replay rendering for Float/Double/BLOB. No production replay dependency or fabricated
  golden values are introduced.
- Report oracle checks, accepted/attempted/successful mirrors, skips and failures
  separately. One query skipped before mirroring cannot contribute mirror coverage.

## Isolated, resource-bounded full-directory campaign

`compose/corpus_acceptance.py` uses the existing ownership/loaded-provider controller
with fresh UUID resources, loopback endpoints and same-path read-only repository
fixtures. It requires verified A/resource-only B bundles with catalog 0.17.0.
Evidence belongs in a persistent directory such as `build/validation/catalog-0.14.0/`.
The Gradle/oracle process tree is a private user-systemd scope limited to 6 GiB,
no swap and 300% CPU; Gradle has one worker and no retained daemon. Do not run other
campaigns beside it. FE/BE retain their established container limits.

```sh
python3 compose/corpus_acceptance.py \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/catalog-0.14.0-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6 \
  --evidence-root build/validation/catalog-0.14.0
```

The native oracle is JDBC 2.0.0.1; the CLI argument is the unchanged deployment
preflight prerequisite. All discovered files are selected, but existing curated
exclusions and the shared harness's 40-mirrors-per-file cap remain explicit.
The controller refuses skipped/missing JUnit execution and zero successful mirrors,
retains all failures, distinguishes 1.1 scope halts from ran-to-end files and marks
watchdog/quarantine cases non-green. It kills only its own scope and cleans only
acknowledged disposable infrastructure, checking protected state afterward.

For a bounded regression, `--file-list /absolute/path/to/selection.txt` selects
exact relative corpus paths, one per line. Empty, duplicate or unknown paths refuse;
the receipt is a **selected-corpus** campaign, not full-directory coverage.
Existing exclusions and mirror caps still apply. The isolated controller now sets
`doris.corpus.scratch.root` to a shared, owned directory **outside every oracle
TEST_DIR/warehouse**. The adapter allocates separate engine/database children and
removes only its acknowledged child after catalog removal. It does not change the
production inline transport or the direct task's default scratch behavior.

## Oracle-only baseline diagnostics

`compose/corpus_baseline.py` runs the explicit `corpusBaselineTest` with **no Doris
adapter**, on embedded or disposable PostgreSQL metadata. It exports the requested
Git commit into an immutable evidence snapshot, selects all paths represented by a
retained failure index, records native extension/JAR/source hashes and every record
outcome, and leaves the tracked submodule and goldens untouched.

```sh
python3 compose/corpus_baseline.py \
  --corpus-checkout ducklake \
  --corpus-ref c23aca43b6033acfac2b8cc8d80b15ae7fa729b2 \
  --failure-index build/validation/doris-read-safety/f24-e-h_b33769/failure-index.csv \
  --axis embedded --evidence-root build/validation/corpus-baseline
# Repeat serially with --axis postgres; a different --corpus-ref is a separate diagnostic axis.
```

The task is excluded from ordinary `test`/Java17 tests and cannot assumption-skip.
A completed **diagnostic** can exit the controller successfully while its JUnit
assertion and receipt retain native record failures. Missing counterpart files,
unmet requirements and scope halts are recorded, never counted as passes. Build,
timeout, quarantine and source-integrity failures cannot become completed evidence.
This tool does not count any Doris mirrors or weaken corpus assertions.

`compose/corpus_baseline_triage.py` joins original file/line failures to both backend
controls and optional producer-pin counterparts. It verifies the original corpus
bytes, runtime and selection, preserves original failure reasons and leaves
missing/ambiguous SQL counterparts unmapped. See the
[November 4 baseline investigation](VALIDATION-CORPUS-BASELINE-2026-11-04.md).

Current focused native/quoted/Boolean/float replay acceptance is independently
strict and measured on Java25/17; see [CATALOG-CANDIDATE.md](CATALOG-CANDIDATE.md).
It does not repair every broad-corpus M13–M17 finding or qualify a full campaign.

The original interface sketch, bring-up checklist and old counts are retained in
[the archived design](archive/DESIGN-corpus-replay-adapter-original.md).
