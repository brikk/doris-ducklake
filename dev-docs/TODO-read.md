# Read-path backlog

Open work only. Stable IDs refer to [REVIEW-FINDINGS.md](REVIEW-FINDINGS.md), with
original evidence retained in the archive. Revalidate an older diagnosis against
the selected lane and runtime before implementing it; do not infer closure from
an upstream merge or an unrelated green suite.

## Correctness

- [ ] **F07:** carry one coherent physical metadata view across filter collection,
  range planning and inline flush/compaction; per-call read sessions are insufficient.
- [ ] **F08:** filter historical delete entries by pinned snapshot or reject unsafe
  history, including files whose nullable partial-max metadata hides later entries.
- [ ] **F05/F06/F09/F26:** source-type-aware unsigned bucket hashing, Unicode-scalar
  bounds, conservative lossy timestamp pruning and FE cast-preservation contracts.
- [ ] **F10/M02:** preserve microsecond timestamp resolution and parse SQL local
  timestamps in session timezones, retaining explicit offsets.
- [ ] **F11/F12/O04:** authoritative per-file imported mappings; complete nested
  rename/re-add/default/history behavior. Current native descriptor fix does not
  qualify imported or historical nested scans. Keep their refusals.
- [ ] **F13/F14:** validate every file's path-backed columns in mixed Hive/native/
  imported layouts; preserve empty values and classify all NULL spellings correctly.
- [ ] **M01/M03/N17:** default parser nested DECIMAL/quoted-name limits and value-aware
  defaults. The candidate parser is separate, not a default-lane fix.
- [ ] **N12/N15/N16:** field-ID bounds, snapshot reapplication after pushdown, and
  actual int128 boundary reads; do not treat descriptor tests as value proof.

## Inline and candidate lifecycle

- [ ] **O02/M09/M10/N19:** distributed inline DATA transport, inline DELETEs, immutable
  namespaced materializations and lifecycle/GC; verify UTC annotations and default
  refusals independently. The parked inline-delete test remains visible until its
  fixture and implementation are real; don't simply un-disable it.
- [ ] **R07:** close statement-wide capture/cancellation/deadline/budget and physical
  lease requirements before widening the [candidate tier](CATALOG-CANDIDATE.md).
  Broader routes, per-row history filters, inline payloads, CDC, mixed versions,
  fleet/production/performance and producer long-path failures remain unqualified.

## Harness and compatibility

- [ ] **M13–M17:** narrow exception-to-skip classification, fail explicit runs with
  missing prerequisites/zero mirrors, forward endpoint settings safely, preserve SQL
  literals/quoted names during rewrites and escape each credential/SQL/libpq context.
- [ ] **N03/N05/N18/N20/N21:** scan-level format and thrift ownership regressions,
  distinguishing multi-file tests, packaged FE-like classloader test and reproducible CI.
- [ ] **N06:** plugin-unload JDBC deregistration, never per-catalog close deregistration.
- [ ] **N22–N26:** revalidate remaining binary/wrapper/baseline/comment/test-placement
  hygiene against the current tree; move only proven dead artifacts with reviewed scope.

## Feature/optimization work (lower priority)

- [ ] Views; MTMV table freshness and MVCC partition views.
- [ ] Audit `ignorePartitionPruneShortCircuit()` against actual NULL partition values.
- [ ] Statement memoization using `ConnectorStatementScopes`; measure first.
- [ ] Nereids partition items/range-cache adoption if repeated planning cost warrants it.
- [ ] Scan profiles and TABLESAMPLE support; expand accepted replay dialect deliberately.
- [ ] COUNT/delete netting or no-stats identity pruning only if measured valuable;
  conservative BE-count/keep-file fallbacks are correct.
- [ ] Supply real NDV only if a trustworthy source exists; never fabricate it to fill
  Doris column statistics. Preserve snapshot-scoped table estimates.

Upstream discriminator/plugin-root ergonomics are tracked in
[the friction reference](ducklake-doris-friction.md). Fixture invariants: CHECKPOINT
can consolidate INSERTs; assert actual metadata/files rather than one-file-per-INSERT.
Shared catalog NaN/swapped-bound read guards already exist; do not duplicate them
here or confuse them with unresolved write/global-statistics F15/F16.
