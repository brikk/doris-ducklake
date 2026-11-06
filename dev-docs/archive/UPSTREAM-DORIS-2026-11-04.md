# Upstream Doris review — 2026-11-04

Requested before corpus-baseline cleanup. This is a source/release-status review,
not a runtime upgrade or local qualification of the new upstream bytes.

## Mainline since our runtime pin

Upstream calls its main branch **`master`**. At inspection its head was
`19dfb8711ae3dbb8f56cc945f5dc7af20a844607` (November 4, 13:02 UTC), **51 commits ahead**
of our qualified `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146` (November 2).
[Exact comparison](https://github.com/apache/doris/compare/4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146...19dfb8711ae3dbb8f56cc945f5dc7af20a844607).

Important changes for a future coordinated refresh:

- [#69828](https://github.com/apache/doris/pull/69828), merged November 4: fixes
  **missing query rows** from treating month/quarter/year datetime arithmetic as
  globally monotonic during partition/runtime-filter pruning. It also aligns
  month/quarter/year difference constant folding with BE evaluation. This is a
  high-priority general FE correctness fix, not merely an optimization. Our plugin
  does not expose Nereids range-partition views, but the shared constant-folding
  change means its relevance is not limited to native OLAP partitions. No local
  before/after calendar-arithmetic qualification was performed in this task.
- [#69967](https://github.com/apache/doris/pull/69967), merged November 3: native
  Parquet BOOLEAN dictionaries are now readable. Valid interoperability files
  previously failed. Relevant to our native Parquet path, but not a demonstrated
  cause of any of the 159 retained record failures.
- [#69832](https://github.com/apache/doris/pull/69832), merged November 4: correctly
  open children of nested Iceberg struct expressions, fixing the remaining
  initialization bug after the earlier traversal fix. Relevant to the reader we
  dispatch through; does **not** implement DuckLake historical-delete filtering,
  nested import identity or nested history support.
- [#69806](https://github.com/apache/doris/pull/69806), merged November 4: guarded
  external-table absence cleanup preserves refreshed/recreated tables. Adds a typed
  SPI absence exception and bumps **plugin API 28 → 29**; the intervening HMS
  partition-pruning work had already moved our API27 baseline to API28. Do not
  drop the currently compiled API27 bundle into this new FE.
- [#70057](https://github.com/apache/doris/pull/70057), merged November 4: corrects
  schema-table enum values and routine-load attachment wire IDs to match 4.1/4.2.
  These IDs change relative to our unpatched mainline runtime. This is a reason
  to rebuild/requalify a matched SDK/FE/BE set, not mix old and new components.
- [#69889](https://github.com/apache/doris/pull/69889), merged November 3: standard
  UTF-8/UTF-16 conversion at JNI string/map boundaries, including supplementary
  characters/NULs. Important for JNI-backed integrations; it does not repair our
  separate Unicode statistics-pruning finding.
- [#69697](https://github.com/apache/doris/pull/69697), merged November 3: rejects
  out-of-domain Arrow timestamps before narrowing. Its description explicitly
  distinguishes the native Parquet timestamp path, which already range-checks.
  Do not treat it as replacing our FE binding/inline non-finite-value guard.
- [#70087](https://github.com/apache/doris/pull/70087), merged November 4: enables
  Parquet modular encryption in the Arrow third-party build. Existing prebuilt
  third-party packages need rebuilding. [#70159](https://github.com/apache/doris/pull/70159)
  adds **Iceberg read admission/refusal**, not encrypted execution. Neither supplies
  DuckLake key transport, backend negotiation or a fix for our three encrypted-file
  mirror errors. The generic writer page-index controls in
  [#69971](https://github.com/apache/doris/pull/69971) are new controls, not a reason
  to change baseline results or writer defaults in this connector.

**Decision:** substantive correctness fixes have landed, including wrong-result
calendar arithmetic. A coordinated API29 re-vendor is a worthwhile next task,
not something that must wait for new 5.x features. No reviewed change establishes an emergency replacement requirement
for this disposable, matched API27 validation stack, or removes the known feature
gates. Keep pins unchanged during baseline cleanup so its before/after evidence
does not mix engine and harness changes. Any later refresh needs explicit scope,
the new SDK/API, fresh third-party dependencies where needed, and complete matched
runtime/bundle qualification.

## Doris 4.2 progress

- [4.2.0-rc01](https://github.com/apache/doris/tree/4.2.0-rc01): annotated October 23,
  commit `202578e8c2b8a23400f1e3047c23609a6b5afb6d`.
- [4.2.0-rc02](https://github.com/apache/doris/tree/4.2.0-rc02): annotated October 30,
  commit `37c5abc6fe1dc5c4941b7cbbae6a671275e355ba`.
- At inspection `branch-4.2` was
  [`bdf6d2c12ecc1c25924fd74036b8a0272d208e46`](https://github.com/apache/doris/commit/bdf6d2c12ecc1c25924fd74036b8a0272d208e46),
  updated November 4 at 10:34 UTC, **89 commits beyond RC2**. Today's backports
  include external metadata cleanup, timezone parsing, remote CDC driver ADMIN
  checks, and other correctness/security fixes. Release stabilization is active.
- The [published release listing](https://github.com/apache/doris/releases) still
  reports **4.1.5** (October 15) as the latest stable release. No final 4.2 tag or
  published final release was found. RC tags and backport activity establish
  progress, **not a confirmed release date or a completed release vote**.
- Released/RC 4.x lacks the mainline connector SPI required by this repository;
  4.2 shipping does not itself make stock 4.2 an interchangeable runtime.

No upstream builds, deployments, PRs or messages to other projects were initiated.
