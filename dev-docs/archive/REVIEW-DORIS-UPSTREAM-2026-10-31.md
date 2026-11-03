# Doris upstream check — 2026-10-31

## Outcome and scope

**Subsequent action:** the user then authorized the exact-snapshot API26 re-vendor.
Its completed build/runtime qualification, promotion and Duckbridge delivery are
recorded [separately](VALIDATION-DORIS-API26-2026-10-31.md). The review-only
observations below remain the original check, not substituted test evidence.

F01 was committed/pushed first as **`8c68181c0d6a2df4a53dcc7b3ebb49b758914276`**.
The unrelated Quack report was excluded and remains unchanged.

Upstream master checked at **2026-10-31 19:11 UTC**:
`8e10b235cf030d486b6840cfd64b6218ab515e6d`, **59 commits** beyond our qualified
`62e3d81a42b2b74ee2a5a577969df62edee06b78` pin. Aggregate delta: 754 files,
49,582 additions / 2,540 deletions. This is targeted compatibility/impact triage,
not an exhaustive implementation review of every changed line.

**Review only:** no re-vendor, SDK installation, manifest/dependency change,
FE/BE build, tests against the new runtime, artifact promotion or deployment.
Current API-24 qualification still refers to its original source/artifact bytes.

## Compatibility boundary

| Contract | Qualified pin | Checked master | Meaning |
|---|---:|---:|---|
| Connector plugin API | 24.0 | **26.0** | Current ZIP is rejected by the new major-version gate. |
| Filesystem plugin API | 6.0 | **7.0** | Matching filesystem plugins/host are needed when re-vendoring. |
| JNI plugin API | 6.0 | 6.0 | Entire `fe/be-java-extensions` tree is byte-identical at the Git-tree level. |
| BE execution version maximum | 19 | 19 | Unchanged version number does not qualify the changed BE reader/runtime. |
| Maven revision | 1.2-SNAPSHOT | 1.2-SNAPSHOT | Coordinates alone cannot identify which SDK/runtime was built. |

API 25 comes from [#69683](https://github.com/apache/doris/pull/69683); API 26 /
filesystem API 7 from [#69835](https://github.com/apache/doris/pull/69835).
The SPI changes are additive in the inspected surface: `partitionBatchSize`,
`ConnectorScanContext`, request context access/builder propagation and scheduler
task removal. No required connector source rewrite was demonstrated by this
source check, but no compilation against the new SDK was performed. Merely
changing the manifest stamp is **not** an upgrade/qualification.

Thrift/protobuf changes include additive row-TTL fields, AI structured-output
configuration and optional Parquet `Statistics.nan_count` field 9. No renumbering
of our pinned `TQueryOptions`/Iceberg sink/external report/Paimon contract was found
in this delta. This does not replace regenerated SDK and matched FE/BE testing.
Unchanged JNI source/API is also not a complete transitive build-input reuse proof.

## Changes most relevant to DuckLake

### 1. Bounded external split production

[#69683](https://github.com/apache/doris/pull/69683), `dcee644c957`:

- Default partition/file batch triggers: 1024 → 1.
- Producer batch: 128 → 8 splits.
- Backend queue capacity: 32 **batches** → 1024 **individual splits**.
- Separate Hive partition processing size, default 16.
- Running-but-not-finished split fetches return `INCOMPLETE` and are retried;
  completion, failure and cancellation remain distinct. This touches FE and BE.

Our `DuckLakeScanPlanProvider` still overrides synchronous `planScan`, inherits
`supportsBatchScan=false` and `streamingSplitEstimate=-1`, and does not implement
`streamSplits`. These new defaults therefore do **not** opt DuckLake into streaming
or turn one plan into repeated partition batches. Preserve that opt-out until an
explicit adapter/ownership/cancellation design is qualified.

### 2. Query-lifetime cancellation and resource cleanup

[#69835](https://github.com/apache/doris/pull/69835), `ea3558d1ed3`, adds a shared
scan cancellation/progress context, query/attempt-owned split cleanup and queued
task removal. Statement metadata/read transactions remain retained until their
split work actually exits; cleanup does not concurrently close a running source.
New settings include `first_split_wait_timeout_ms=30000` and
`split_producer_parallelism=0` (pool-derived). Cancellation is cooperative, not a
promise to forcibly interrupt arbitrary SDK/JDBC work already running.

This is useful infrastructure for future R07 lifecycle work, **not** automatic
closure of it. Source check shows the engine binds the query cancellation context
in the asynchronous request path; its synchronous request builder still uses the
default context, and our provider does not consume the new context today. Existing
bounded JDBC capture/deadline logic must not be removed on the strength of this PR.

[#69718](https://github.com/apache/doris/pull/69718), `5b3d2244210`, independently
fixes closed statement scopes accepting late loads, releases values racing close
exactly once, and cleans the separate context used by EXPLAIN REFRESH MV. These
generic engine lifetime fixes are relevant to statement-scoped connector metadata.

[#69665](https://github.com/apache/doris/pull/69665), `082736138b7`, defers external
catalog resource cleanup out of replay. [#69720](https://github.com/apache/doris/pull/69720),
`f2b8e022359`, refreshes external table lookups on a cold master. Neither establishes
a coherent DuckLake physical planning snapshot or solves stale table-handle DDL.

### 3. Parquet floating-point pruning

[#69655](https://github.com/apache/doris/pull/69655), `2a4191c1dc9`, uses explicitly
zero, validated Parquet NaN counts to improve V2 row-group/page pruning. Missing,
positive or inconsistent counts retain the conservative path, and page proofs
stay scoped to their owning row group/physical column. This is relevant to the
native Parquet path, but needs files that actually carry a trustworthy zero count;
it is not a general speedup claim for our current producer.

It does **not** repair F16's catalog/global-statistics interaction. Upstream reader
test/benchmark descriptions were inspected, not executed or independently
reproduced here; their numbers are not DuckLake runtime qualification.

### 4. Reader and I/O changes worth regression coverage

- [#69711](https://github.com/apache/doris/pull/69711), `978d5c8d34b`, and
  [#69691](https://github.com/apache/doris/pull/69691), `2760a7663b9`, compare Iceberg
  **equality-delete** keys in an appropriate promoted type (V2/V1). DuckLake's
  historical **position-delete snapshot visibility** is a different contract.
- [#69757](https://github.com/apache/doris/pull/69757), `49f3cf87d8f`, preserves
  assigned IDs for older Iceberg REST catalogs. It does not make DuckLake's
  imported per-file name mapping authoritative over physical Parquet IDs.
- [#67292](https://github.com/apache/doris/pull/67292), `9e61225e3ee`, is a substantial
  PageIO read-ahead/coalescing/cache-hole change (73 files, 14,453 additions /
  132 deletions). Relevant when qualifying the new native BE, not proof of a
  measured performance gain for this connector.
- [#69874](https://github.com/apache/doris/pull/69874), `a72dcaa17b1`, retains ADBC
  drivers until active calls finish. Useful adjacent connector lifecycle work,
  not a change to our JDBC catalog or the staged Duckbridge type handler.

### 5. Configuration/loader notes for any future upgrade

[#69740](https://github.com/apache/doris/pull/69740), `81cbb5b4206`, changes
`skip_localhost_auth_check` from true to false. Local health/smoke clients should
be tested with the actual accounts/authentication configuration during an upgrade;
this does not itself set account passwords. It is not evidence that this user's
firewalled host is exposed to the internet, nor a reason for extra container work.

[#67286](https://github.com/apache/doris/pull/67286), `fb61aeeb63f`, permits a
documented legacy unversioned **authentication-plugin** family. The connector
family remains strict; it does not admit an API-24 DuckLake ZIP into an API-26 FE.

## Important gaps not closed by this delta

| Finding / boundary | Source evidence and disposition |
|---|---|
| F08 historical delete visibility | V2 `_read_position_delete_file` is byte-for-byte unchanged; it still has no DuckLake embedded-snapshot filtering. Keep open. |
| F11 imported IDs / F12 nested mapping | V2 `column_mapper.cpp/.h` are unchanged; Iceberg REST/equality-delete fixes are not DuckLake mapping fixes. Keep open. |
| F26 cast erasure | `ExprToConnectorExpressionConverter.java` is unchanged. Keep open. |
| F02 procedure authority | `ConnectorExecuteAction.java` is unchanged. Localhost auth and catalog resource cleanup do not implement catalog-wide maintenance authorization. |
| Broader R07 | New async lifecycle seams are relevant, but do not enable inline/CDC/history filters, leases, route/fleet support or a new sync JDBC cancellation contract. |

## Release branches

Latest GitHub stable release remains
[4.1.5-release](https://github.com/apache/doris/releases/tag/4.1.5-release),
published **2026-10-15**, peeled commit `917b20be83878e98f94e3ebf7efccc80346e3e50`.
Checked branch tips:

- `branch-4.1`: `b4afc8c693ef6fbb2a657f0a5fcbcd14e52a30d2`.
- `branch-4.2`: `67eef18c2d8be6af55b5d16b921ca0e698383239`.

Neither branch nor the 4.1.5 release contains the `fe/fe-connector` tree/current
`org.apache.doris.connector.spi.Connector` contract. They are not drop-in release
replacements for this master-SPI integration.

## Recommendation and evidence

A **separate, bounded API-26 re-vendor/qualification** is worth considering for
statement-resource correctness and the new cancellation infrastructure. Keep the
current qualified pin until matching SDK/FE/BE packages and relevant correctness/
restart/lifecycle checks pass. Do not automatically enable batch/streaming or other
capabilities. If re-vendoring is requested, preserve the usual Duckbridge build-reuse
handoff; no new build exists to hand off from this review.

Evidence directory: `/tmp/opencode/doris-upstream-2026-10-31-2s_cmaz1/`.
`facts.json` records versions, refs, exact cause-file/function comparisons and
the original pin. `commits.txt`, `changed-paths.txt`, targeted diffs and GitHub PR
metadata retain the inputs. All 13 selected PRs were confirmed merged via the API;
stale draft/performance text in commit bodies was not treated as current PR state.

Fetch/blob reads used a separate shared-object bare review repository. The shared
`/home/jayson/DEV/OSS/doris` checkout stayed on its original HEAD, and its three
staged Duckbridge files and unstaged/status fingerprints stayed unchanged. No
build environment, local Maven SDK, runtime image, artifact or container changed.
