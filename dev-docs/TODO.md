# Backlog

IDs in bold refer to [FINDINGS.md](FINDINGS.md). Re-check an item against the current
catalog version and Doris pin before implementing it; the original diagnosis may be stale.

## Priorities

1. **Close silent-wrong-result paths.** Maintenance authority (F02), stale DROP handles
   (F20), a coherent physical read view (F07), historical deletes (F08), pruning
   semantics (F05/F06/F09/F26) and imported field identity (F11). Prefer an explicit
   refusal over a speculative bridge. Closing one needs a regression that fails before
   the fix, ideally cross-engine.
2. **Complete the Parquet read contract** before richer writes: distributed inlined
   transport, nested evolution/history/mappings, and a fresh design for DuckLake 1.1.
3. **Harden writes** (commit atomicity, stats, partition values) before admitting row
   DML, overwrite, sorted writes, ALTER or compaction.

Non-goals: function execution pushdown and reading DuckDB-native `.db` files. Other file
formats are a feasibility question under Research, not a planned phase.

## Reads

- [ ] **F07** One coherent physical metadata view across filter collection, range planning
  and inline flush/compaction; per-call read sessions are not enough.
- [ ] **F08** Per-position historical delete filtering. Today historical scans with delete
  files are refused; keep that until the BE can filter.
- [ ] **F05/F06/F09/F26** Source-type-aware unsigned bucket hashing, Unicode-scalar string
  bounds, TIMESTAMP_NS pruning, and cast preservation in pushed predicates.
- [ ] **F10/M02** Keep microsecond precision in time-travel timestamps; parse SQL local
  timestamps in the session zone.
- [ ] **F11/F12/O04** Per-file imported mappings as the authority over embedded ids;
  nested rename/re-add/default/history. Keep the current refusals until then.
- [ ] **F13/F14** Validate every file's path-backed columns in mixed layouts; classify all
  NULL spellings and empty strings like DuckDB.
- [ ] **M03/N17** Value-aware defaults (non-finite floats); broader nested evolution.
- [ ] **N12/N15/N16** Field-id bounds, snapshot re-pin after pushdown, real int128
  boundary values.
- [ ] Temporal domain checks for unknown file bounds and expression defaults.
- [ ] **O02/M09/M10/N19** Distributed inlined-data transport; inlined DELETEs; immutable,
  namespaced materializations with lifecycle. The `@Disabled` inlined-delete test stays
  until the fixture and implementation exist.
- [ ] **R07 / DuckLake 1.1.** The connector refuses 1.1 catalogs. The removed experimental
  reader's findings are in [archive/CATALOG-CANDIDATE.md](archive/CATALOG-CANDIDATE.md);
  a new design should build on the released catalog's `Final11*` APIs. Needs: statement-wide
  capture with cancellation and a deadline, physical file leases, per-row history filters,
  inline payloads, mixed-version handling.

## Writes and maintenance

- [ ] **F02** Authority must match actual scope: catalog-wide expiry and widened orphan scopes.
- [ ] **F20** Expected-table-id fencing inside DROP's mutation, covering drop/recreate and rename.
- [ ] **F15/F16** Preserve unknown bounds and null/NaN counts through commit and global-stat
  merges. Pin timestamp-bound text against real DuckDB metadata before decoding it.
- [ ] **F17** Atomic schema precondition at write commit; a standalone pre-check races.
- [ ] **F18/F19** Unsigned write-domain checks before file production; protect user values
  from Iceberg-reserved names.
- [ ] **M04/M05/M07/N14** Persist or reject defaults, comments, properties and explicit
  LIST/RANGE definitions; make sure CREATE-admitted types can reach a supported sink.
- [ ] **M06** Source-type-aware temporal partition values; distinguish string `"null"` from NULL.
- [ ] **M08/N11/N13** Reclaim known aborted output without widening deletion; directory-safe
  path relativization; transaction/fragment state validation.
- [ ] **M11/M12/N02** Session-token parity for maintenance; reject unknown and duplicate
  case-folded procedure arguments before any effect.
- [ ] **N08–N10** Orphan scope for absolute external roots; bounded listings and deletes;
  retention versus a real reader/writer lease.
- [ ] **O05** Narrow-int writes need BE value-level validation.
- [ ] Not admitted yet: DELETE/UPDATE/MERGE, INSERT OVERWRITE (one-snapshot partition
  replacement, never truncate-then-insert), sorted writes, ALTER/TRUNCATE/SHOW CREATE
  fidelity, `rewrite_data_files`, `flush_inlined_data`, catalog-level procedure entry points.

## Harness

- [ ] Corpus baseline: shared TIME JDBC conversion, quoted ATTACH parsing, `require notmusl`,
  1.0 deletion-vector expectations; custom metadata-schema context in the replay adapter.
  The 159 retained failures are classified in
  [archive/VALIDATION-CORPUS-BASELINE-2026-11-04.md](archive/VALIDATION-CORPUS-BASELINE-2026-11-04.md).
- [ ] **M13–M17** Narrow exception-to-skip classification; fail explicit runs with missing
  prerequisites or zero mirrors; forward endpoint settings to forked JVMs; preserve
  literals and quoted names in rewrites; context-correct escaping.
- [ ] **N03/N05/N18/N20/N21** Scan-level format and thrift ownership regressions;
  multi-file fixtures; an FE-like isolated-classloader test of the assembled zip; CI.
- [ ] **N06** JDBC deregistration on plugin unload (not catalog close).

## Lower priority

- [ ] Views; MTMV freshness and MVCC partition views.
- [ ] Audit `ignorePartitionPruneShortCircuit()` against NULL partition values.
- [ ] Statement memoization via `ConnectorStatementScopes` — measure first.
- [ ] Scan profiles, TABLESAMPLE, a wider accepted replay dialect.
- [ ] COUNT netting of deletes, no-stats identity pruning — only if measured valuable.
- [ ] Real NDV only from a trustworthy source; never fabricate it.

## Research

- Doris as the catalog metadata backend: transaction and constraint semantics first.
- DuckLake metadata/system tables through Doris: needs an FE→BE row transport.
- Snapshot-log-driven invalidation / MV refresh.
- An owned filesystem SPI handle for FE-side maintenance instead of the MinIO client.
- Other file formats: only after read correctness and write hardening.
