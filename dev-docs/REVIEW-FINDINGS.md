# Active review findings

Stable IDs originate in [the retained review](archive/REVIEW-2026-09-05.md).
Its examples and source lines describe their stated baselines, not fresh API27
reproductions. This register preserves open/partial work; revalidate the affected
code, dependency lane and runtime before fixing or closing an item. No new review
or live defect campaign is claimed by this documentation cleanup.

## Correctness and data protection

| ID | Remaining contract / closure requirement |
|---|---|
| F02 | Maintenance authority must cover catalog-wide expiry/cleanup and wider orphan scopes. Add role/grant-based, two-table regressions. |
| F05 | Unsigned bucket pruning must use the source type's hash encoding or decline pruning. |
| F06 | Membership bounds and prefix successors must use Unicode-scalar ordering, not UTF-16 code-unit assumptions. |
| F07 | One logical snapshot does not fence physical flush/compaction. Carry/revalidate one coherent physical view across planning. |
| F08 | **Mitigated by refusal:** ordinary historical scans with delete files now fail closed, including NULL advisory bounds. Latest deletes and delete-free history pass real FE/BE controls; correct per-position historical filtering remains unimplemented. [Read-safety proof](VALIDATION-DORIS-READ-SAFETY-2026-11-04.md). |
| F09 | **Partial:** TIMESTAMP_NS statistics pruning is disabled while retaining the BE predicate. Lossy mapping semantics and other source-domain/cast preservation cases still need qualification. [Read-safety proof](VALIDATION-DORIS-READ-SAFETY-2026-11-04.md). |
| F10 | Time-travel timestamp parsing must retain microsecond precision instead of round-tripping through epoch milliseconds. |
| F11 | Registered imported-file mappings must be authoritative over conflicting embedded field IDs; reject unsupported shapes. |
| F12 | **Partial:** complete native nested dictionaries and ordinary NULL-parent reads are fixed/qualified. Imported/mapped, historical, rename/re-add/default/evolution and final-1.1 nested behavior remain unqualified/refused. |
| F13 | Mixed Hive/native/imported files need compatible actual path-backed column sets, not only a partition-ID check. |
| F14 | Hive raw NULL spellings, escaped literals and empty strings must match DuckDB semantics. |
| F15 | Unknown non-null write bounds must remain unknown in persisted global stats across mixed-engine appends/rebuilds. |
| F16 | Missing NaN/null counts must not become false/zero certainty. |
| F17 | Commit must atomically fence schema/DDL changes since write planning. |
| F18 | Unsigned write-domain checks must happen before file production, not just CREATE mapping. |
| F19 | Protect user values from reserved Iceberg lineage-name behavior; validate at the current BE before admitting writes. |
| F20 | DROP must enforce expected table identity inside the mutation transaction. |
| F26 | FE cast erasure can make file pruning unsafe despite a residual predicate; preserve casts or decline unproven conversion. |

Native F12 evidence is in the [retained regression](archive/VALIDATION-DEFAULT-NESTED-READS-2026-11-01.md)
and [current API27 qualification](VALIDATION-DORIS-API27-2026-11-02.md). It does not
close F11 or expand nested support beyond the tested shapes.

## Functionality and validation gaps

| ID | Remaining follow-up |
|---|---|
| M02 | Parse ordinary SQL time-travel timestamps using session zone, retaining offsets and precision (F10). |
| M03 | Value-aware default expressions, including non-finite floats; type-only admission is insufficient. |
| M04 | Persist CREATE defaults atomically or reject unsupported declarations. |
| M05 | Reject explicit LIST/RANGE definitions whose values/bounds are not represented. |
| M06 | Translate temporal partition values and SQL NULL using source type/transform semantics. |
| M07 | Validate redundant temporal transforms and generated-name collisions before CREATE succeeds. |
| M08 | Reclaim known aborted BE output without broadening deletion to arbitrary unreferenced Parquet. |
| M09 | Inline materializations must be immutable and catalog/run-namespaced under concurrent planning. |
| M10 | Inline timestamptz annotation and zone-aware mapping must agree; compare flushed/inline values in non-UTC zones. |
| M11 | Maintenance S3 session-token support and reader/maintenance credential parity. |
| M12 | Reject unknown procedure args and duplicate case-folded keys before effects. |
| M13 | Narrow exception-to-skip rules; unexpected missing relations or unsupported errors must fail. |
| M14 | Explicit corpus tasks must fail missing prerequisites and require measured successful mirrors. |
| M15 | Safe forwarding of forked-JVM endpoints/credentials and one consistent shared-path configuration. |
| M16 | SQL rewrite must preserve literals/comments and quoted/Unicode names. |
| M17 | Use context-correct SQL literal/identifier/libpq escaping and avoid secret logging. |

M01's nested-DECIMAL parser defect is addressed by the shared-parser cutover in
[catalog 0.13 adoption](VALIDATION-catalog-0.13.0-2026-11-03.md); broader nested
value/evolution/default boundaries remain N17/F12/M03.

M18's JVM split and M19's exact-artifact/loaded-runtime gates are implemented for
the qualified lanes; see [DEVELOPMENT.md](DEVELOPMENT.md) and the API27 evidence.
They do not close M14's coverage problem, N20's FE-like loader test or general
configuration audit M17. M20's standalone command/doc drift is addressed by this
cleanup; historical commands remain explicitly archived.

## Hardening and retained knowledge

| ID | Remaining follow-up / invariant |
|---|---|
| N01 | Honest DuckLake reader discriminator requires supported BE dispatch; retain working Iceberg-shaped transport. |
| N02 | Document/test storage alias precedence and maintenance's narrower credential surface. |
| N03 | Scan-level `file_format_type` controls dispatch; range `getFileFormat()` alone is insufficient. |
| N04 | LIST/RANGE grammar labels can carry transforms; preserve per-field mapping and M05 rejection. |
| N05 | Test scan-wide/per-range thrift ownership with engine-prepopulated descriptors. |
| N06 | JDBC deregistration belongs to plugin unload, not individual catalog close. |
| N08 | Define wider orphan scope for absolute table/schema roots outside the selected directory. |
| N09 | Measure/bound eager listing/reference sets, synchronous deletion, progress and cancellation. |
| N10 | Retention is not a lock against long writers or concurrent imported old objects. |
| N11 | Directory boundary required for path relativization; `/table2` is not inside `/table`. |
| N12 | Reject field IDs outside the native Int range, including write dictionaries. |
| N13 | Revalidate transaction lifecycle/fragment bounds before adding fences for possible SPI sequences. |
| N14 | CREATE syntax must match persisted comments/properties/types/partition semantics and supported INSERT. |
| N15 | Define behavior when an already-pruned/projected handle is repinned; clear/recompute if reachable. |
| N16 | Actual int128 boundary values, not just DECIMAL(38,0) descriptor assertions. |
| N17 | **Partial:** catalog 0.13 adoption uses the shared quoted-name/DECIMAL parser in both lanes; broader nested identity/evolution remain bounded. |
| N18 | Distinguishing multi-file and exact retained/new-ID regressions; revalidate each weak fixture separately. |
| N19 | Default inline/delete refusals need independent tests; parked future-feature tests do not prove them. |
| N20 | Assembled ZIP through an FE-like isolated classloader, service/API/host identity and runtime closure. |
| N21 | Tracked/linkable reproducible cold-build, lint, package and isolated integration automation. |
| N22 | Revalidate tracked stale binary payloads; don't delete retained machine artifacts under a docs cleanup. |
| N23 | Revalidate wrapper distribution checksum against the actual wrapper configuration. |
| N24 | Revalidate stale Detekt suppressions/empty artifacts before removal. |
| N25 | Keep source comments aligned with actual safety contracts; dated source commentary needs a separate scoped pass. |
| N26 | Confirm consumers/API before moving the value normalizer to test-only sources. |
| N27 | Current setup guide is [COMPOSE.md](COMPOSE.md); keep plugin roots, FE-local health, JVM flags and injected-property rules accurate. |

## Boundaries carried forward from earlier review

O02/O03/O04/O05 remain inline, partial-file history, per-file mapping and narrow-int
limits. O08 statistics are estimates, not exact counts after deletes/inline rows.
O09 shared catalog NaN/swapped-bound read guards are not missing; F15/F16 are distinct.
O12 feature backlog is in the read/write TODOs. O13 function execution pushdown and
DuckDB-native file reads remain non-goals.

F01/N07, F03/F04, F21/F22, repository containment F23, provenance F24 and smoke
assertion F25 are closed only within their recorded scopes. Their diagnoses,
tests and limits remain in the archive and current API27/F01 evidence, not as
unfinished TODOs. F23 does not authorize restarting/migrating legacy bindings or
claim that wildcard bindings alone bypass the host firewall. W01–W04 semantic,
test, maintainability and operations concerns still guide the [plan](PLAN.md).
