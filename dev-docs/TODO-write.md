# Write and maintenance backlog

Open work only; [REVIEW-FINDINGS.md](REVIEW-FINDINGS.md) preserves stable IDs.
INSERT/CTAS/basic DDL and the three maintenance procedures already exist. Old
missing-executor/P4 capability descriptions are [archived](archive/TODO-write-through-2026-11-02.md),
not current SPI requirements.

## Protect existing operations

- [ ] **F02:** enforce authority matching actual maintenance scope, including widened
  orphan scopes; table ALTER permission is not catalog-wide authorization.
- [ ] **F20:** expected-table-ID fencing inside DROP's mutation transaction, including
  drop/recreate and rename/name-reuse races.
- [ ] **F15/F16:** preserve unknown bounds/null/NaN counts through commit and catalog
  global-stat merges. Mixed-engine appends must not persist false pruning certainty.
  Pin timestamp-bound text against real DuckDB metadata before decoding it.
- [ ] **F17:** atomic planning-snapshot/schema precondition at write commit; a standalone
  pre-commit schema check races. Allow a legitimate concurrent append as a control.
- [ ] **F18/F19:** target-domain checks for unsigned writes and protection against
  Iceberg-reserved user names; CREATE type mapping alone is insufficient.
- [ ] **M04/M05/M07/N14:** preserve or explicitly reject defaults/comments/properties,
  explicit LIST/RANGE definitions and incompatible/redundant partition specs before
  catalog mutation. Ensure CREATE-admitted types can actually reach a supported sink.
- [ ] **M06:** source-type/transform-aware temporal partition values and an unambiguous
  SQL-NULL representation; distinguish string `null` from NULL.
- [ ] **M08/N11/N13:** recover known aborted output without widening managed-residue
  deletion, directory-safe path relativization and transaction/fragment state validation.
- [ ] **M11/M12/N02:** session-token/credential parity for maintenance; reject unknown
  or duplicate case-folded procedure args before effects (`dryrun` must not delete).
- [ ] **N08–N10:** define wider scope across absolute external table roots, bound
  listings/deletes and specify concurrency/retention ownership. A grace period is
  not a reader/writer lease; don't claim universal GC safety.
- [ ] **O05:** narrow-int writer support needs BE value-level validation. INT/BIGINT
  is the workaround; do not admit CTAS-inferred TINYINT/SMALLINT as qualified writes.

## New capabilities (not admitted today)

- [ ] DELETE/UPDATE/MERGE: use current generic write/row-identity seams only after
  tracing both FE and BE at the exact pin; map files/deletes atomically and prove
  Doris and DuckDB reads. Old missing-executor diagnoses are superseded.
- [ ] INSERT OVERWRITE/static-partition INSERT: pin target partition-set semantics,
  conflict detection and BE fragment reporting before enabling OVERWRITE. Dynamic
  partition overwrite replaces touched partitions, not the whole partitioned table.
  Commit the replacement in one snapshot; never truncate-then-insert.
- [ ] Sorted writes: persist declared sort keys, populate sink sort metadata and
  qualify actual order before enabling `SUPPORTS_SORT_ORDER`.
- [ ] ALTER-column/partition/schema evolution, TRUNCATE and SHOW CREATE fidelity.
- [ ] `rewrite_data_files`: distributed rewrite planning/commit and partial-file
  backdating; coordinate with read-side snapshot filtering.
- [ ] `flush_inlined_data`: materialization/write/lifecycle support first. If added,
  preserve table/schema/catalog scope rules and deliberate handling of partitioned
  tables; a procedure shell alone is not a flush implementation.
- [ ] Catalog/database procedure entry points: upstream grammar/authorization/context
  integration, not a table statement pretending to confer wider authority.

Do not enable API27 write-only-default or other new capability flags automatically.
All 1.1 writes/DDL/maintenance remain disabled. Event-source/CDC feasibility belongs
to [TODO-research.md](TODO-research.md), not implied write support.
