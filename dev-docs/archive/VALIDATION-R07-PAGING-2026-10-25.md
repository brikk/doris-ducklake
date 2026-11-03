# R07 SQL-paged bounded capture — October 25

Latest6730bb8 adoption and requalification:
[R07 adoption](VALIDATION-R07-ADOPTION-2026-10-25.md). The older candidate/API-gap
statements below describe this report's historical10f81f9 run only.

Continuation of `a6aa725d0de6f39836ee33625a1a2e309d992390`, qualification only.
No formal release, push, deployment, default-on, migration, 1.1 write, cleanup,
catalog/Trino edit, source/extension/corpus repin, new skip or baseline rewrite.

## Candidate and implementation

The exclusive opt-in repository is now
`/tmp/opencode/catalog-r07-10f81f9/maven-repository`, immutable catalog source
`10f81f91df69ab6bc024cc02b6a0530628c54166`. Its README/SHA256SUMS remain intact.
Resolved catalog SHA-256:
`370e4ad265c253b4dfbc791a962df2ab2f44e05faba981dc19c2d8ce5a5f25f7`.
Fixtures/replay/SLT/JDBC hashes remain those in the preceding report. FE dependencies
remain scoped exclusively to the existing `org.apache.doris` Maven-local repository;
no global `maven.repo.local`, composite/source replacement or vendor change.

`DuckLakePlanningPages` drains schemas, tables, columns, partition fields/values,
name-map rows and inline registry versions through `readPlanningMetadata()`, and
active DATA/delete associations through `getDataFilesPage`. It shares the original
statement control, owned transaction and budgets with retained ownership, recent
history frames and physical validation. No legacy schema/table/column/spec/inline
or active/rich-file list call remains in the capture. The active composite cursor
provides delete IDs directly: the rich-file list is no longer needed for association
identity. Multiple active associations for one DATA identity still fail closed.

Every drain requires strict progressing seek keys, bounded page length and a final
empty page, charged before calling SQL. Exhaustion is failure, never successful
truncation; there is no retry/reread with a fresh or larger allowance. Column and
mapping trees are fully assembled under node/work/text bounds, then checked for
duplicate IDs/sibling names/orders, missing parents, cycles, depth64 and qualified
name length16384 before projection/publication. Root schema status comes from the
owned library session's nested-history refusal, NOT an inference from a legacy DTO
that omits parent IDs. Partition specs require contiguous indices and valid column
references; partition values must refer to active file/spec fields. Native paths
remain opaque and body-absent Hive mappings remain distinct and refused.

All metadata string bytes now share the existing4-MiB path/default text allowance.
That is an accepted/materialized-text budget, not a driver/network heap guarantee:
the library's SQL-bounded page/lookahead/sentinel can arrive before caller rejection.
The original64-MiB physical transfer allowance, exact range/ETag checks, path/work/
deadline ceilings, original-failure memoization and one-statement scope remain.
Only complete bounded full views publish: **not lazy/streaming**. New optional
`final11.max-planning-page-size` lowers the existing128-item page size (1..128);
it does not raise any ceiling. The128 total-page ceiling includes terminal pages
and ALL metadata scopes/history frames, so it can bind before schema/table maxima.

Within that SAME owned capture, fully validated schema dictionaries are shared by
the public snapshot schema-version coordinate; completed partition-value checks
are shared only for the same immutable DATA-ID/partition-ID set. No liveness or
active DATA/delete association is reused across snapshot coordinates. Empty active
file enumeration already proves the partition-value join empty. These caches hold
only successes, never outlive the transaction/capture, and do not reconnect, lazily
read SQL after publication or replenish budgets.

The avoidable schema-version<=256 guard and version-space reservation for the old
materialized inline-info method are removed. Returned registry entries, including
absent physical tables with `hasLiveRows=false`, retain their seek positions; each
entry reserves layout/liveness work. Missing-field decoded defaults still invoke
the public validated API only after actual footer-proven field absence. That API
reloads its internally bounded target schema and a LIMIT1 history-kind row; work
is reserved from the validated target column count, not the numeric schema version.

`getDataFilesAddedBetweenPage` is available but no added-between legacy transfer
exists in this ordinary-read capture to replace. Incremental/CDC remains refused;
using an added-file candidate page would not establish a CDC surface or physical
snapshot predicates.

## Verification

Candidate maintained evidence (Java25/Java17 lanes overlap):262/236 passed,
zero failures/errors, one existing legacy inline-delete skip in each lane.
Targeted native/refusal probes:14 passed, zero failures/errors/skips. Production
lint passes without baseline/suppression changes. Java17 runtime scan:24,749
classes across44 runtime JARs plus main output, applicable maximum61. The four
attested candidate runtime/fixture JARs independently scan at maximum61. Catalog
frozen ABI/wire and903+86 upstream checks are bundle-provided evidence, not a
newly rerun catalog/corpus campaign here. Logs/reports:
`paging-candidate-complete.log`, `paging-candidate-summary.txt`,
`paging-candidate-evidence/`, `paging-four-jar-abi.txt` under
`/tmp/opencode/doris-r07/`.

Candidate connector JAR SHA-256:
`b36efee0d9355030821f805324b1dbabf3868ede1bff815caca7f97121f00763`;
candidate ZIP SHA-256:
`a3c6b016b07c3aa3fbd94fe279beeb1020c38dc6f82401ad03b95c5eb4d07cf9`.
The staged files are compared byte-for-byte to this retained ZIP. Actual private
FE paths report the same connector/catalog hashes; plugin loading reports
failureCount=0, with FE Java17.0.2 and the same curated matched API13 FE/BE peers.
The previous Parquet host1.18.0 vs compile/test1.17.0 distinction is unchanged.

Final fresh-private-FE suite: **16 live tests passed, zero failures/errors/skips**,
79.823s. Retained XML/stdout are in `paging-candidate-evidence/r07LiveQualificationTest/`
and `paging-live-summary.txt`; task log is `paging-live-complete.log`.

The new one-item-page SQL fixture has3 schemas,4 tables, native schema version>256,
multiple body-carried partition values (escaped text and NULL), defaults and a
self-join. Lowering the caller schema budget to2 fails and memoizes the same
original exception. Owned malformed metadata tests exercise SQL-bounded oversized
text, a duplicate column seek key at the one-item boundary, a cyclic parent, and
name-map registry IDs11/22 distinct from target IDs1/2. Name maps remain refused
as Hive/add_files, not mistaken for native opaque paths; all negative metadata is
repaired, and a fresh statement recovers. A real PostgreSQL drain that can afford
one nonempty schema page but NOT its terminal empty fails instead of returning a
successful list. An absent first inline physical table retains its registry
position and cannot hide later live rows; after restoring/flush, real FE/BE SQL
returns both independently expected rows.

Corrected history-kind coverage now rejects `unknown` with BOTH NULL and non-NULL
initial values through actual FE SQL. Known expression-kind plus materialized42
and native literal VARCHAR `NULL` remain allowed; expression-kind without its
materialized initial value rejects. Repair restores literal42, and fresh SQL
recovers. Stored NULLs, changed INSERT defaults77, stable replacement IDs, exact
BLOB/decimal/date/timestamp/string bytes, current/historical Parquet/Puffin,
same-container slices, native embedded IDs, multi-rowgroup scans and self/cross-
snapshot joins all execute again. No raw SQL expression is evaluated.

Locked production captures stop before fixture unlock at41ms (explicit control)
and3,012ms (three-second JDBC deadline), retaining PostgreSQL57014 and recovering
on the same one-connection pool. These are observations, not universal cancellation
latency bounds or an API13 in-flight engine callback. Same-statement original
failure memoization, initialized-FE driver diagnostics and fresh-query recovery
pass. The S3 outage still takes about10.026s before its socket timeout; network/
pool/driver behavior is not certified by metadata page limits. At suite end the
private PostgreSQL limit remains100, with62 ordinary idle retained-pool clients
and zero idle transactions (plus one diagnostic client). Aggregate capacity is
still an operator limitation, not a raised pool/server ceiling.

The strict long-partition native producer is rerun separately: **one failure,
zero errors/skips**, HTTP400 `XMinioInvalidObjectName`, still unwaived. Its XML is
retained separately from reader successes; R02/R03/R04/M01 are not rerun here.
All18 maintained Python smoke units and shell syntax pass. Only the four owned
qualification services are stopped; containers, PostgreSQL catalogs, S3 data and
failed producers remain. No Trino/shared service or global daemon is stopped.

The final build runs with **no override**, restoring catalog/fixtures/replay/SLT
0.12.0 and JDBC1.5.6.0. Released maintained Java25/Java17 lanes pass253/227,
zero failures/errors and the same one existing skip; lint and assembly pass.
Java17 runtime scan returns24,630 classes, maximum61,44 runtime JARs plus main
output. Neither resolved graph contains0.13.0. The restored ZIP has no candidate-
only class (including `DuckLakePlanningPages`), test fixture or JDBC JAR, and retains
the API13 manifest. Its catalog is byte-for-byte equal to the released cached JAR;
released fixture/replay/SLT/JDBC hashes are also rechecked against the preceding
report. Logs: `paging-released-final.log`, `paging-default-summary.txt`,
`paging-default-evidence/`, `paging-default-assembly.sha256`,
`paging-default-family.sha256`. The final bytes equal the prior released assembly:

- Released catalog SHA-256:
  `e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729`.
- Released connector JAR SHA-256:
  `d3a8299e8c65ada6101e74d8074646d5a5e00f89e1a044c7cdb7fdcb384b5c59`.
- Restored default ZIP SHA-256:
  `832270f2cf95e79e1464c72cdd6f8d66fc092264dff4fa1af656f5e0ac20fc45`.

Final verification summary (the maintained JVM lanes overlap):

| Check | Passed | Failures/errors | Existing skips |
|---|---:|---:|---:|
| Candidate maintained Java25 | 262 | 0 | 1 |
| Candidate plugin Java17 | 236 | 0 | 1 |
| Targeted native/refusal Java17 | 14 | 0 | 0 |
| Fresh matched FE/BE/SPI Java17 | 16 | 0 | 0 |
| Released maintained Java25 | 253 | 0 | 1 |
| Released plugin Java17 | 227 | 0 | 1 |
| Strict long-path native producer | 0 | **1 failure**, 0 errors | 0 |

Reproduction uses only the isolated retained compose services, with fresh owned
FE before the full live run (no service restart inside recovery assertions):

```sh
export GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle
candidate=/tmp/opencode/catalog-r07-10f81f9/maven-repository
mise exec -- ./gradlew check java17PluginTest r07ReadQualificationTest pluginZip \
  qualificationDependencies -Pr07CatalogRepository="$candidate" \
  --continue --no-daemon --no-configuration-cache
# Stage/byte-check that ZIP only into the owned stopped qualification FE, then start it.
mise exec -- ./gradlew r07LiveQualificationTest -Pr07CatalogRepository="$candidate" \
  --continue --no-daemon --no-configuration-cache
# Separate strict producer gate: still RED, never merged into reader totals.
mise exec -- ./gradlew r07LongPartitionNativeTest -Pr07CatalogRepository="$candidate" \
  --no-daemon --no-configuration-cache
# Restore released assembly last, with NO override.
mise exec -- ./gradlew check java17PluginTest pluginZip qualificationDependencies \
  --rerun-tasks --continue --no-daemon --no-configuration-cache
```

`paging-services-stop.log` and `paging-retained-services.txt` attest the final
owned-only shutdown. Candidate plugin staging is retained separately from the
restored released build output; all old catalog bundles and fixtures remain.

The first maintained run caught an overbroad new per-cell guard in the shared
`literal` budget helper: the existing aggregate-budget unit intentionally charges
nearly4MiB in one operation. The metadata-specific per-cell guard now lives in
the page adapter instead; the original default-budget contract/test is preserved.
Initial red evidence: `/tmp/opencode/doris-r07/paging-candidate-initial.log`.

The first live run passes13/16, with three honest page-budget refusals: earlier
tests requested16 whole-catalog frames, although their actual historical assertions
needed fewer. Adding bounded pages for every metadata scope now makes that work
visible. A cache-only rerun still refuses those three overly broad captures;
neither result is counted as a full live pass. Logs/XML are retained in
`paging-live-initial.log`, `paging-live-initial-evidence/` and `paging-cache-live.log`.
Final fixtures keep every original positive/negative historical coordinate and row
assertion, but explicitly request the exact recent coordinate window needed. The
merged-DATA historical checks execute before adding unrelated row-group/partition
tables to the same retained fixture; those later current scans still execute.
The evolved-default fixture explicitly asserts that the original16-frame request
fails the unchanged128-page budget before qualifying its narrower, exact window.
The simple existing fixture still exercises a successful16-frame configuration.
This is a visible practical scope limit, not a raised ceiling, skip or relaxed
mixed-snapshot predicate boundary.

## Unchanged NO-GO boundary

Decoded ordinary/CDC inline **payload** pages and deletion-candidate pages remain
missing in this candidate; registry pages do not fill either gap. API13 still has
no mandatory DATA/delete snapshot predicate, generic distributed inline query
payload channel or engine CDC surface. Mixed historical filtering, inline data,
nested/degraded types and missing-field default allowlists stay fail-closed. No
staging objects, shared FS, temporary Parquet, invented RPC/cancellation hook or raw
SQL default evaluator is introduced. PostgreSQL deadlines are real; API13 still
has no supported in-flight planning cancellation registration.

Retention leases, cold-namespace diagnostic masking, aggregate retained pool
capacity and other-route/fleet qualification remain limitations, not hidden by
single-capture budgets. Native R02=3/R03=1/R04=1/M01=6+4 and the strict long-native-
path producer failure remain unwaived. No full-corpus campaign is run here.
