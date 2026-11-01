# Published catalog read-correctness candidate — 2026-11-01

## Status and boundary

**Candidate integration and bounded qualification complete.** No artifact/default-on
promotion was performed. Candidate intake is separate
from the completed uncommitted Doris API26 re-vendor. The default released catalog
is still0.12.0 with JDBC1.5.6.0; its distribution/receipt was backed up before edits.
This work does not adopt Quack, enable final1.1 by default, migrate retained metadata,
claim full corpus/production/performance support, or change the existing raw-key S3
deletion refusal. Existing unsupported inline/history/mapping/CDC limits remain.

## Inputs

- Producer source `72aae19d0deaf05a6c61e6782a6d263325ff23dd`.
- Publication workflow: https://github.com/brikk/ducklake-catalog/actions/runs/41288389109
- Repository: `https://central.sonatype.com/repository/maven-snapshots/`.
- Catalog, catalog test fixtures, replay and SLT: **0.13.0-20261101.082015-33**.
- Producer handoffs: sibling `ducklake-catalog/dev-docs/HANDOFF-READ-CORRECTNESS-2026-11-01.md`
  and `HANDOFF-N03-NESTED-TYPES-2026-11-01.md`; publication instructions/receipt under
  its `build/verification/read-correctness-handoff/published/` and parent directory.
- Doris source/SDK/runtime remains `8e10b235cf030d486b6840cfd64b6218ab515e6d`,
  connector API26/filesystem7/JNI6/BEexec19, the matching pristine FE/full ASAN BE.

No local-JAR resolution, producer rebuild or mutable local SNAPSHOT installation is
used. The three modules have strict timestamp constraints; the fixture self-edge and
replay's base-SNAPSHOT SLT dependency cannot select a different publication. All four
compile/runtime/test component graphs are checked for the exact timestamped identity.
Normal Gradle resolution of all four published JARs matches producer SHA256s:

| JAR | SHA256 |
|---|---|
| catalog | `01ffed5abb0b970b307dd4de1b7d7ab42320e33016447896204e5a36506a3041` |
| test fixtures | `ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f` |
| replay | `bd97eba965f2234faa75cf27a788c45a70d4f030115e51b7506104267a9899b1` |
| SLT | `6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409` |

Candidate keeps its existing JDBC2.0.0.0 pin, SHA256
`5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
Neither that choice nor producer library tests qualify an engine read.

## Implementation

`-PcatalogReadCorrectnessCandidate=true` selects `read-tiers/r07`, extra targeted
tests, the published strict constraints and an isolated
`build/catalog-read-correctness-33/` output. Old `r07CatalogRepository` local
repository overrides refuse with a migration diagnostic rather than silently using
unverified bytes. Omitting the candidate property continues to select the released lane.

The original type mapper moved byte-for-byte into `read-tiers/released/`; the
candidate mapper uses public `DucklakeTypeSignature.parse`. It recursively maps
`InlinedTypeNode`, preserving field names/order and decimal commas. Scalar mappings,
timestamp-TZ opt-in and explicit documented degradations remain unchanged. The
historical top-level `linestring z` alias is retained. Doris STRUCT lookup is
case-insensitive; empty names/case-colliding fields explicitly refuse, not rename
or drop. The pinned FE preserves original external spelling separately.
Catalog parent/child stable IDs and physical Parquet field IDs are checked without
rewriting source metadata. Live verification exposed a separate descriptor bug:
the API26 BE treats a supplied scalar-only dictionary as the complete field set,
so omitted nested roots became NULL. A shared scan-planner call now dispatches to
a lane-specific encoder. The released encoder delegates to the unchanged scalar
implementation. The candidate appends complete STRUCT/LIST/MAP `TField` trees from
`getAllColumnsWithParentage`, retaining every native field ID, parent and ordinal;
names on the runtime lookup wire are locale-independently normalized as Doris
requires, while external metadata retains original spelling. Existing scalar
name-map conflict filtering is reused, not weakened.

This candidate extension is limited to current native files. Nested legacy mapped
files, historical nested scans and final1.1 nested scans explicitly refuse. Depth,
node/ID/arity/root identity/type validation prevents partial/fabricated descriptors.
This does **not** qualify nested rename/re-add/default backfill, per-file mapping,
inline transport, CDC, or broader historical/final1.1 reads. No nested-pruning
capability or new Iceberg reader semantic mode is enabled.

The test-only Doris replay adapter delegates Float/Double cells to the pinned
`GoldenComparator.renderCell`, instead of Java number `toString`. Its Boolean,
temporal/blob and raw NULL handling remain unchanged; no replay dependency enters
production. No direct untyped result-hash caller was added to the real adapter.
New conformance tests exercise the additive typed hash API and old descriptor.

## Qualification gates

Pre-descriptor candidate checks pass: Java25 **283 passed/one existing skip**, Java17
**252 passed/same skip**, including five new nested-signature/replay conformance
tests on both JVMs. Malformed/arity/budget/refusal tests, real native metadata,
native floating and typed/untyped Boolean hash checks pass. Detekt, Java17
runtime scan (**24,697 classes**), candidate packaging and exact-component gates
pass. Pre-bridge fresh released checks also pass: Java25 **266 passed/one existing skip**,
Java17 **235 passed/same skip**, Detekt/runtime/package gates. Its full ZIP and
adjacent receipt remain byte-identical to the saved API26 default. Bounded live
qualification then exposed the descriptor bug described below. Post-fix live
acceptance passes; fresh post-fix/post-bridge regression closeout remains pending.

Prepared bounded live runner `compose/read_correctness_acceptance.py` creates only
UUID-owned API26 FE/BE and PostgreSQL/S3 infrastructure. It requires a full28-check
supported candidate smoke, then native file-backed quoted STRUCT/LIST/MAP fixtures
with independent NULL/literal-NULL expectations and real metadata/Parquet IDs.
Explicit Java25/17 tests measure same-PG/S3-lake replay mirror attempts/passes and
ensure a wrong Boolean hash is a failure before mirror coverage, with later valid
queries continuing. These are small authored probes, not a full corpus campaign.
No parser-only or setup total will be called engine read coverage.

## Evidence / preservation

Reproduce only the bounded candidate lanes (not historical API13 or a full corpus):

```sh
mise exec -- ./gradlew --project-cache-dir build/verification/catalog-33/project-cache-candidate \
  -PcatalogReadCorrectnessCandidate=true \
  verifyR07Candidate test java17PluginTest check assemble qualificationDependencies
F24_ACCEPTANCE_PROJECT_ROOT="$PWD" \
F24_ACCEPTANCE_BUILD_DIR="$PWD/build/catalog-read-correctness-33-B" \
  mise exec -- ./gradlew --project-cache-dir build/verification/catalog-33/project-cache-candidate-B \
    -I compose/acceptance-build-B.init.gradle \
    -PcatalogReadCorrectnessCandidate=true assemble
mkdir -p build/verification/catalog-33/runtime
mise exec -- python3 compose/read_correctness_acceptance.py \
  --bundle-a build/catalog-read-correctness-33/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/catalog-read-correctness-33-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6 \
  --evidence-root build/verification/catalog-33/runtime
```

The live runner requires the already-qualified exact API26 image IDs and local
helper images used by the public smoke. It generates its own private test-init/
connection files and disk-backed test temporary directory. It never discovers
or falls back to a shared FE/metadata service. Resource B is only an inherited
sandbox-construction prerequisite; the supported smoke and read checks use exact A.
Omit the candidate flag for normal released-lane checks/output.
Run these lane builds sequentially, not alongside the live runner. For preservation
verification, an isolated released build directory/cache is used rather than
rebuilding or promoting the previously qualified default distribution.

Disk-backed `build/verification/catalog-33/` retains initial failures, current
logs and pre-intake API26 working diff/untracked documents/default bundle/receipt.
The temporary-directory backup attempt hit a quota before completing; the complete
backup is disk-backed. `compile-attempt2.log` shows normal published resolution,
all five checksums (including JDBC), and successful initial compile. Initial script
compile failed on one remaining old variable reference; corrected without changing
the resolved producer bytes. First broad check passed both JVM suites but Detekt
rejected the scalar switch complexity; it was replaced with a declarative mapping
table, without a suppression or baseline change, and the next full check passes.
The first live-run plan was rejected before mutation because F23 requires a
concrete published FE port, not an empty ephemeral-port token. The runner now
chooses an available loopback port explicitly; bind races fail rather than touch
another process. No preflight gate was relaxed. Failed runs remain retained.

The first fresh released-lane JVM run failed the unchanged F01 one-second
object-age fixture (dry-run orphan count6 vs5, consistent with its young object
aging under load). The procedure/key guards and test were not changed. The same
two real PostgreSQL/S3 F01 tests then pass unchanged for all three URI schemes,
exact survivors and SDK-rewrite refusals; both original and rerun XMLs are saved.
The full released matrix then passes both JVMs unchanged
(`released-final-check-attempt2.log`, `released-final-results.json`,
`released-final-xml/`). This records a passing rerun, not a claim the timing-sensitive
fixture has been stabilized; the first failure is not discarded.
An initial130-test Python run is invalidated by its source-freeze check because
the new helper was edited during that run. It is not recorded as final green;
a frozen final rerun is required after helper edits finish.

The next isolated run passes the full supported28-check candidate smoke and
measures actual same-lake Boolean/float replay mirrors on Java25, but the new
nested metadata test incorrectly expected child rows from `getTableColumns`.
That API projects top-level signatures; the unchanged raw parent/child API is
`getAllColumnsWithParentage`, as the producer handoff specifies. The test now
compares that complete raw tree against PostgreSQL and the projected signature,
without changing the catalog/connector implementation or relaxing expectations.
The failure XML and positive replay stdout remain under the failed run's evidence.
The subsequent final live pass includes both JVMs and real NULL-parent/container
identity, not only matching leaf NULLs.

The following live run reached actual nested file scans and exposed populated
values returning NULL, while native expected values, raw/projected metadata and
same-lake replay mirrors were correct. The positive nested expectations were
**not changed**. Wrong-NULL XML/native IDs/footers remain under
`runtime/f24-e-a5vdtsp8/`. Inspection of pinned
`iceberg_reader.cpp:576-583` and `table_schema_change_helper.cpp:801-822` confirmed
that a nonempty `history_schema_info` selects complete field-ID matching, with
no name fallback for dictionary-omitted roots. The complete candidate descriptor
fix above passes the final live verification below; the producer parser alone was
not called sufficient acceptance.

Because the shared dispatch adds a small compile-time bridge, new released source
assemblies have a new byte identity even though their scalar descriptor behavior
is unchanged. Post-bridge released checks/assembly use isolated
`build/catalog-released-preservation/`; they do not overwrite/promote the existing
`build/distributions/` qualified API26 ZIP/receipt. The historical API26 receipt
still names its original tested bytes, not this later source tree. Existing API26
source changes, reports, images, SDK and rollback directories remain preserved.

A frozen130-test Python run encountered temporary-directory quota failures and
30-second mock-driver timeouts under concurrent load. No assertion, timeout or
containment rule was weakened. The final rerun uses a disk-backed owned `TMPDIR`
and two disjoint workers instead of six; it must execute all130 tests with frozen
Python/shell/YAML hashes.

An isolated-output released run passed Java25 but lost its generated class
directories during concurrent candidate compilation, causing five Java17
`HivePartitionPath` class-load failures. The JAR contained that class, and both
generated main/test directories were empty afterward: separate output paths alone
are insufficient when Gradle task history is shared across variants. Final lanes
must use separate project-cache directories and execute compilation/verification
sequentially. No missing class or timeout is counted as a semantic pass.

The two-worker frozen Python rerun passes129 cases; the one Docker transport case
still fails at a hardcoded `/tmp/opencode` write, before starting its fixture.
`TMPDIR` alone cannot relocate that literal. Its only test-source adjustment is
an optional `F24_DOCKER_TEST_ROOT` directory (same `/tmp/opencode` default); no
assertion, expected value, lifecycle step, timeout or production driver changes.
The final full130-case rerun passes, with both `TMPDIR` and `F24_DOCKER_TEST_ROOT` set to the
owned disk-backed `build/verification/catalog-33/python-tmp/`, with normal disjoint
sharding and no simultaneous JVM/native qualification workloads.

Deliberately requesting the older `-32` publication as a second strict constraint
fails resolution for each of catalog, replay and SLT. The three expected failures
are retained in `strict-pin-negative-results.json` and per-module logs. The old
local-repository property also refuses before dependency resolution. Neither
control can publish a mixed or locally substituted qualified bundle.

## Verified final live result

**Passed:** `build/verification/catalog-33/runtime/f24-e-a_oyddm_/acceptance.json`;
independently checked in `live-results-verified.json`.

- Exact corrected candidate A build ID:
  `doris-ducklake-v1-bf735a19dde53a550f13750c57eee8d64ddd7f8a1f13e51767361974bb4bbe27`.
- ZIP SHA256: `c59e44c93280f490dfae3c7fcf5e45148bac3e7ca5634edcd8c3129b96a25c6c`.
- API26 full supported smoke **28/28**, actual loaded identity bound to A.
- Java17.0.19 and25.0.2 live tests: **2/2 per JVM, zero failures/skips**. They
  verify metadata names/types/order/raw parent IDs against PostgreSQL, all17
  Parquet field IDs against that metadata, and actual BE values for quoted STRUCT
  fields, STRUCT inside LIST/MAP, and SQL NULL parents/containers vs non-NULL
  all-NULL children. Literal `NULL` remains a string, not a sentinel.
- Each JVM measures **2 mirror attempts /2 mirror passes** on the same PG/S3
  lake, not setup totals. An intentionally wrong Boolean hash remains one failed
  record with **no mirror attempt for it**, followed by **1/1** valid floating
  mirror. Native/rendered rows and Boolean/VARCHAR provenance are not rewritten.
- Source/tooling hashes match their executed copies; native expected rows and
  original positive nested expectations remain unchanged. B differs from A only
  by the acceptance resource, with identical classes, SDK and dependencies.
- All owned containers/volumes/networks are cleaned; protected retained services,
  the default ZIP and Quack report are unchanged before/after the live run.

This is current native1.0 file-read evidence, not final1.1 nested/history/legacy
mapping, inline, all-JNI, full-corpus, production or performance qualification.
The released lane's historical nested-reader assertions are not requalified by
this candidate-only descriptor correction. Existing API26/catalog0.12 runtime
receipts continue to identify their original scoped tested bundle.

## Final regression closeout and source identity

**Passed:** `build/verification/catalog-33/receipt.json`, generated by
`closeout.py` only after all completed-job notifications and strict binding checks.

| Fresh final lane | Passed | Existing skips | Failures/errors |
|---|---:|---:|---:|
| Candidate Java25 | 283 | 1 | 0 |
| Candidate Java17 | 252 | 1 | 0 |
| Isolated released Java25 | 266 | 1 | 0 |
| Isolated released Java17 | 235 | 1 | 0 |
| Build logic | 8 | 0 | 0 |
| Frozen Python/shell/YAML regression cases | 130 | 0 | 0 |
| Live nested/replay Java25 | 2 | 0 | 0 |
| Live nested/replay Java17 | 2 | 0 | 0 |

The one JVM skip per lane is the unchanged existing parked inline-delete test.
No new skip, expected failure, semantic golden change or timeout waiver was added.
The Python suite executes all130 disjoint cases in one final frozen run, including
the real Docker transport/claim/lifecycle case on the relocated disk root.
Earlier failures and environmental invalidations remain explicitly failed.

Both lanes pass Detekt, dependency/package and Java17 runtime checks. The final
candidate runtime scan covers **24,788 classes**. Its fresh final ZIP/receipt is
**exactly** the A bundle tested live above, not just a matching version label.
The isolated released build continues resolving catalog/fixtures/replay/SLT0.12.0
and JDBC1.5.6.0; its newly compiled bridge artifact is not promoted. The saved
ordinary `build/distributions/` ZIP/receipt remain the original qualified API26
bytes: build ID `doris-ducklake-v1-af57a1321fa06b9d0028a24d1569ac2a78c4ad83aabe7794c4ab199142354f66`,
ZIP SHA256 `db08a07eb6498f9da014e284b985e64ed8b6cd2ded6b318465129f0855a287af`.

Consumer Git base remains **8c68181c0d6a2df4a53dcc7b3ebb49b758914276**, with preserved
uncommitted API26 work plus this intake. The receipt records exact source hashes;
its canonical source-manifest SHA256 is
`697e86d93316f6eaf3551a0decdce258fb4516147077a37aa8669d03fa6e5eb2`.
It also binds all ten normally resolved producer JAR/POM/module files, five actual
compile SDK JARs, executed XML sets, frozen Python sources and live ledgers.

Independent final checks confirm owned live resources/claim are absent, retained
container states and protected files match, original mapper bytes are preserved,
global API26 SDK JARs are unchanged, and no unrelated preexisting file was altered.
`retained-state-final.json` and `preservation-results.json` supplement the receipt.

All changes remain **uncommitted/unpushed**. No released default switch, retained
stack migration, broader R07 campaign, Quack/Duckbridge adoption or production/
performance enablement followed this candidate qualification.
