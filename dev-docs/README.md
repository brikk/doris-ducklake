# Developer documentation

The [project README](../README.md) describes current use and user-visible limits.
This directory holds maintained developer instructions, current evidence and open
work. [archive/](archive/README.md) retains superseded instructions and completed work;
archived commands/results apply only to their stated artifacts.

## Start here

- [DEVELOPMENT.md](DEVELOPMENT.md): build, tests, source layout and acceptance gates.
- [DORIS-RUNTIME.md](DORIS-RUNTIME.md): exact engine/SDK inputs and re-vendor contract.
- [COMPOSE.md](COMPOSE.md): local/remote stack, replacement, receipts and recovery.
- [TYPES.md](TYPES.md): default type mappings and write restrictions.
- [CATALOG-CANDIDATE.md](CATALOG-CANDIDATE.md): published candidate, opt-ins and limits.
- [DESIGN-corpus-replay-adapter.md](DESIGN-corpus-replay-adapter.md): replay contract and usage.
- [CRUTCHES-AND-SHORTCUTS.md](CRUTCHES-AND-SHORTCUTS.md): load-bearing safety invariants.
- [ducklake-doris-friction.md](ducklake-doris-friction.md): current upstream integration seams.

## Active work

- [PLAN.md](PLAN.md): priorities and exit criteria.
- [REVIEW-FINDINGS.md](REVIEW-FINDINGS.md): stable review IDs and remaining knowledge.
- [TODO-read.md](TODO-read.md), [TODO-write.md](TODO-write.md),
  [TODO-research.md](TODO-research.md): open work only. A green smoke does not close
  a finding without its own distinguishing regression.

## Current evidence

- [API27 qualification](VALIDATION-DORIS-API27-2026-11-02.md): tested engine/SDK/bundle
  hashes, JVM/native/live checks, rollback and limits.
- [Requester-only Duckbridge handoff](HANDOFF-FOR-DUCKBRIDGE-DORIS-4bc7f426-API27.md):
  build reuse, not Duckbridge adoption; no message or dispatch was sent.
- [Latest upstream review](REVIEW-DORIS-UPSTREAM-2026-11-03.md): master and 4.2
  observations; review only, not a new runtime pin.
- [F01 regression evidence](VALIDATION-F01-2026-10-31.md): S3 aliases/raw keys and
  referenced-object preservation; F02 authority/concurrency remain separate.
- [Quack JDBC verification](VALIDATION-QUACK-JDBC-0.11.0-2026-10-29.md): related
  dependency research only. Quack is not adopted into this connector. The report
  remains at its protected path used by the acceptance preservation checks.

Keep exact versions and qualification results in their evidence records rather
than accumulating chronology in README or the active TODOs. When completing work,
remove its active task only after retaining any unresolved boundary or regression
knowledge here or in an archived closeout. Do not delete old runtime/rollback
artifacts merely because their documentation is archived.
