# Archive

Dated reports, completed checklists, superseded designs and old working logs. None of it
is current instructions; counts and hashes describe the artifacts they name. Current docs
start at [../README.md](../README.md).

## What is here

- **Validation reports** (`VALIDATION-*`): each catalog adoption (0.11 → 0.18), each Doris
  pin (API13 → API27), the F-series hardening work, read safety, corpus baseline. The
  current pin's report is `VALIDATION-DORIS-API27-2026-11-02.md`.
- **Trials** (`TRIAL-*`): snapshot-dependency and PgBouncer trials that preceded a release adoption.
- **Upstream reviews** (`REVIEW-DORIS-UPSTREAM-*`, `UPSTREAM-DORIS-*`): what changed on Doris
  master and release branches at the time; none is a runtime bump.
- **R07 / DuckLake 1.1** (`VALIDATION-R07-*`, `R07-QUALIFICATION-API13.md`, `CATALOG-CANDIDATE.md`):
  the experimental bounded 1.1 reader trial, pinned to catalog 0.13.0-SNAPSHOT, removed from
  the tree on 2026-11-06. The connector refuses 1.1 catalogs.
- **Working logs** (`TODO-*-through-2026-11-02.md`, `PLAN-through-2026-11-02.md`,
  `CRUTCHES-AND-SHORTCUTS-through-*`, `ducklake-doris-friction-through-*`): including completed
  items. Open work moved to the current TODO and FINDINGS.
- **Review** (`REVIEW-2026-09-05.md`): origin of the stable F/M/N/O/W finding IDs.
- **FE patches** (`FE-PATCHES.md`, `ducklake-fe.patch`): what was patched into the FE before it
  built patch-free.
- **Designs and research** (`ducklake-doris-*`, `DESIGN-*-original.md`, `RESEARCH-*`, `REPORT-*`):
  the original integration plan, sanity check, roadmap and upstream reports.

Some links point at sibling repositories or machine-local evidence that is not in this clone,
and at source lines that have since moved.
