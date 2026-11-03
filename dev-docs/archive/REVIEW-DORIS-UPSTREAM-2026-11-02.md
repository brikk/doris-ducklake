# Doris upstream check — 2026-11-02

## Outcome and scope

Master checked at **2026-11-02 16:21 UTC**:
`4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146`, **37 commits** beyond our qualified
`8e10b235cf030d486b6840cfd64b6218ab515e6d` pin. Aggregate delta:469 files,
20,236 insertions /62,083 deletions, dominated by retirement of the old file-reader
stack. This is a targeted integration-impact review, not an exhaustive audit.

**Review only.** No re-vendor, SDK installation, dependency/manifest change, build,
new-runtime test, artifact promotion, container restart or deployment. The completed
but uncommitted default catalog0.12 nested-read fix and unrelated Quack report are
preserved. Git base remains `583afbe014edf790af4318fcbe8e72f2df8fbad5`.

## Compatibility boundary

| Contract | Our pin | Checked master |
|---|---:|---:|
| Connector plugin API |26.0|**27.0**|
| Filesystem plugin API |7.0|7.0|
| JNI plugin API |6.0|6.0|
| BE execution maximum |19|19|
| Maven revision |1.2-SNAPSHOT|1.2-SNAPSHOT|

Both catalog build configurations still target Doris API26. Catalog0.12 versus the
published0.13 candidate is a separate dependency axis; neither existing bundle is
an API27 bundle. A new API27 FE rejects the current API26 ZIP. Merely changing its
manifest is not an SDK/runtime upgrade and is blocked by our compile-SDK API guard.

The inspected SPI production delta adds one enum capability,
`SUPPORTS_WRITE_ONLY_DEFAULTS`; the scan-interface subtree is unchanged. No new
mandatory connector method rewrite was found by source inspection. Compilation
against the new SDK has **not** been performed.

The entire `fe/be-java-extensions` source tree and filesystem compile API tree are
unchanged at Git-tree identity. That does not prove equality of complete rebuilt
JNI/transitive packages. Thrift `ExternalTableSchema.thrift` and `PlanNodes.thrift`
are byte-identical. The changed wire surface adds optional tablet-report fields18/19
and changes the deprecated scanner option's default to true while retaining field
ID1002. No claim of mixed-version runtime qualification follows from these facts.

## Changes most relevant to DuckLake

### Mandatory V2 native reader

[#69723](https://github.com/apache/doris/pull/69723), `49da8785c881`, removes the
legacy FileScanner and its old Parquet/ORC/table readers. `FileScanLocalState`
constructs `FileScannerV2` unconditionally; `enable_file_scanner_v2` remains as a
deprecated no-op and is emitted true in query options. Reader support validation
and errors now belong to the maintained readers, rather than the old route gate.

Our qualified API26 runtime already used V2, so this is not a first-time V2 opt-in
for DuckLake. It is nevertheless a substantial BE change touching split handling,
loads, row-ID fetches, delete-file helpers, profiles and reader dispatch. It needs
new real native-file/delete/default/TopN/nested-NULL acceptance, not a version-stamp
change or an assumption that a deprecated session switch can restore the old reader.
Upstream PR test counts are their results, not tests executed in this review.

### API27 write-default semantics

[#69488](https://github.com/apache/doris/pull/69488), `7e6f6cb3a34a`, introduces the
write-only-default capability and resolves Iceberg `DEFAULT(column)` from the
statement-pinned writer schema, including INSERT VALUES/SELECT and UPDATE/MERGE
predicates. Non-write uses on a declaring table reject instead of reading NULL
from an inappropriate read schema. Common default-expression casting also changes.

DuckLake does **not** declare this capability. Do not opt in automatically: its
existing read-schema `initial_default` backfill is not an Iceberg write-default
contract. Existing DEFAULT/backfill and reordered/partial-column INSERT probes
should be rerun during any API27 qualification. This is not automatic closure of
DuckLake stale-schema/default or row-level DML gaps.

### Parquet instant semantics and timezone/default fixes

- [#69845](https://github.com/apache/doris/pull/69845), `a9253e979d70`, makes Iceberg
  FE mark every timestamptz dictionary leaf with `timestamp_is_adjusted_to_utc`.
  V2 propagates that marker to matched Parquet data/equality-delete columns,
  including INT96 and nested/id-less wrappers, so they decode as instants rather
  than shifting by the session timezone. Unmarked plans keep prior file-type
  behavior; timestamp_ns and ORC are outside this rewrite.
- Our shared dictionary does not emit that marker. Routing DuckLake through the
  Iceberg-shaped native reader does not automatically acquire the Iceberg FE fix.
  Preserve our timestamp-TZ opt-in and UTC/naive mapping boundary; qualify any
  future marker adoption with non-UTC/INT96/nested cases rather than silently
  changing timezone semantics. Equality deletes are not DuckLake position deletes.
- [#68979](https://github.com/apache/doris/pull/68979), `fcf143c1aa81`, aligns FE/BE
  offset parsing, tightens session-offset endpoints, and bounds serialized
  TIMESTAMPTZ literal offsets to18 hours. This is relevant to timezone/literal
  regressions, not evidence of new DuckLake type support.
- [#69611](https://github.com/apache/doris/pull/69611), `4bc7f426828e`, materializes
  native TIMESTAMPTZ schema-change backfill as UTC literals while preserving
  dynamic expressions for later writes. It is adjacent correctness work, not a
  demonstrated fix for external DuckLake missing-field backfill.

### Dependency and adjacent FE changes

[#69906](https://github.com/apache/doris/pull/69906), `378404eeb12a`, changes required
Brotli from1.0.9 to1.0.10 and patches Arrow24's overridden archive/checksum. Before
building a new native pin, verify the actual installed third-party prefix/build
image contains the matching archives; do not assume the October27 image or old
cached objects satisfy the new source requirements. This records a dependency
change, not an independent vulnerability assessment.

[#69946](https://github.com/apache/doris/pull/69946), `49bfbdecffb5`, classifies
external views before probing their table handles. It is useful generic engine
cleanup but does not make our connector expose DuckLake views. SQL-cache, IVM,
SNII/BM25 and compute-group work comprises much of the remaining delta; no
DuckLake performance claim is inferred from it.

## Our nested fix and outstanding boundaries

The new native timestamp helpers reuse/extract schema identity traversal, but do
not make an incomplete plugin-supplied field-ID dictionary correct. The mapper's
field-ID/name matching logic was not repaired on behalf of DuckLake. Preserve the
recent shared complete STRUCT/LIST/MAP dictionary fix in both build configurations
and its real populated-value/NULL-parent regression; re-run it on a future runtime.

Source-backed limits remain:

- V2 `_read_position_delete_file`, `_init_position_delete_rows` and
  `_init_delete_predicates` have identical extracted function bodies across these
  pins. No DuckLake historical per-position snapshot filtering was added (F08).
- Timestamp annotation/equality-delete work is not imported per-file DuckLake
  mapping authority or general nested evolution/default support (F11/F12).
- `ExprToConnectorExpressionConverter.java` is unchanged (F26 cast erasure).
- `ConnectorExecuteAction.java` is unchanged; no maintenance authority closure
  is inferred (F02). No separate security review was performed.
- SPI scan interfaces are unchanged; no new synchronous JDBC cancellation,
  distributed inline/CDC/lease/fleet or broader R07 support follows from this delta.

## Release branches and recommendation

Latest GitHub stable release remains **4.1.5-release**, published2026-10-15.
Checked branch tips are `dd72933ec78a8a4daf3e1d204a6ea96d6443ada7` (4.1) and
`a623df21d75683c61a39ae830822850e6be0a7f0` (4.2). Neither has the connector-SPI
tree; they are not drop-in released replacements for this master integration.

**Recommendation:** API27 is a worthwhile next bounded re-vendor candidate for
native-reader/temporal correctness, but not an automatic upgrade. Preserve the
current pin until a matching regenerated SDK/full FE/ASAN BE/JNI set, updated
third-party provenance and actual DuckLake correctness/restart/negative controls
pass. Preserve the uncommitted default nested fix; do not reintroduce its scalar-only
dictionary or promote catalog0.13 merely to upgrade Doris. If an actual re-vendor
is requested, deliver the usual build-reuse handoff to Duckbridge afterward.

## Evidence / preservation

`build/verification/doris-upstream-2026-11-02/` contains the isolated shared-object
bare review repository, frozen refs,37-commit log, changed paths/statistics, targeted
diffs, exact tree/blob/function identities, latest-release response and seven
selected PR responses. All seven were confirmed merged via GitHub API. No upstream
test or benchmark described by those PRs was executed/relabelled as our own.

The review uses no writable source worktree and initializes no submodule. Existing
checkout files/uncommitted work are fingerprinted before/after. The ordinary fixed
default artifact and actual compile SDK JARs are checked against the previous
default-nested qualification receipt. Review output/report files are the only new
project files from this check; nothing was committed or pushed.
