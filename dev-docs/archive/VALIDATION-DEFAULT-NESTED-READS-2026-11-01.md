# Default catalog0.12 nested file reads — 2026-11-01

## Scope / status

User requested a straightforward default-lane reproduction/fix, without promoting
the published catalog candidate. **Reproduced, fixed and qualified; uncommitted.**
Git base is `583afbe014edf790af4318fcbe8e72f2df8fbad5`.
The unrelated Quack report is not an adoption target and remains untouched.

Default dependencies remain catalog/fixtures/replay/SLT0.12.0 and JDBC1.5.6.0;
Doris remains the already-built API26/FS7/JNI6 source `8e10b235cf030d486b6840cfd64b6218ab515e6d`.
Catalog0.12 already exposes the complete raw `getAllColumnsWithParentage` API, so
the candidate's native descriptor encoder does not require a dependency upgrade.
No new kernel, image, SDK installation, retained-stack deployment or migration.

## Reproducer

The owned API26 PostgreSQL/S3 harness now accepts `--released`. It checks that
the actual selected plugin bundles contain catalog0.12.0 and runs two explicit
default-only live tasks (Java25/17), with separate Gradle project caches/output.
The test cannot silently select the candidate or a shared/retained FE.

`compose/default_nested_seed.py` writes one native1.0 file-backed table containing
an ordinary scalar ID plus STRUCT, LIST-of-STRUCT and MAP-to-STRUCT. It uses simple
ASCII field names, avoiding the separate quoted-name/decimal-signature parser
limitations in catalog0.12. This isolates the field-ID dictionary failure rather
than testing the candidate's N03 parser against the wrong library.

Three independent expected rows distinguish populated nested fields, literal
`NULL`, NULL parents/containers and non-NULL parents with NULL children. Native
rows, raw PostgreSQL parent/child IDs and actual Parquet footer IDs are recorded.
The exact positive expectations are unchanged before/after any fix. Both runs
also require the public full28-check supported smoke with actual loaded bundle
identity, and clean only the infrastructure whose creation they own.

Evidence is disk-backed under `build/verification/default-nested/`.

## Confirmed default-lane failure and fix

Before-fix real acceptance: `runtime/f24-e-bew4vcum/acceptance.json`. The catalog0.12
bundle passed all28 supported scalar smoke checks, then the same positive nested
read assertion failed on **both Java25 and17**. Actual first row was
`[1,NULL,NULL,NULL,NULL]`; native expected was `[1,7,'NULL','list','map']`.
The XMLs are retained next to that receipt. This is now reproduced default-lane
wrong data, not merely an inference from the candidate.

The fix moves the already-proven complete native field-ID encoder from the candidate
source directory into shared `src/`, and removes the released scalar-only delegate.
No parser or dependency promotion: the encoding implementation uses APIs already
present in catalog0.12. Scalar-only wire bytes are unchanged; native STRUCT/LIST/MAP
requests get complete raw parent/child trees. Existing nested mapped-file, historical
and final1.1 refusals remain; no nested pruning or writer capability is enabled.

New common Java25/17 regressions check all11 real native IDs/parent names, including
same-named children under different parents, all-nested projections without a scalar
carrier, and explicit nested history/mapping refusals while scalar behavior stays
unchanged. The test producer uses its existing legacy default on JDBC1.5.6 and an
explicit1.0 attach on JDBC2; the former does not accept `DUCKLAKE_VERSION`. An initial
fixture-option error and two line-length lint errors were corrected without changing
production logic, expected rows, skips or lint settings.

README's previous blanket “full nesting” language is narrowed to the actual current
native-file scope and remaining parser/history/mapping/default limitations. No failure
is treated as passing acceptance merely because metadata parsing succeeds.

## Post-fix live result

**Passed:** `runtime/f24-e-1kujlwel/acceptance.json`; independent before/after binding
is in `native-before-after-verified.json`. Both bundles contain the identical released
catalog0.12 JAR, and both native fixtures have the same expected rows and complete
raw11-field parent/child tree. The native PostgreSQL metadata records format **1.0**.

- Actual API26 FE/BE full supported smoke: **28/28**, before and after the fix.
- Java25.0.2 and Java17.0.19: the exact mixed scalar/STRUCT/LIST/MAP value assertion
  fails before and passes after; post-fix live tests have no skips/failures.
- Literal `NULL` is text; NULL parents/containers remain distinct from non-NULL
  containers/parents holding NULL children. All raw metadata and Parquet field IDs
  agree; the scan no longer substitutes NULL for populated nested values.
- Owned containers/volumes/networks are cleaned; protected retained stack/default
  bundle/Quack files match before/after each isolated run.

Post-fix tested bundle: build ID
`doris-ducklake-v1-d88344c08502d147a49da28848196b2797dfb419680c107ea0d010296603e2ba`,
ZIP SHA256 `d4bc729073a98a500988d3277e861f6de4f6bc276adc580855bb827bee378636`.
The previous ordinary default bundle remains under `rollback-default/`. The ordinary
default output has now been rebuilt as these exact tested bytes; no retained FE
has been reloaded.

## Final checks and default rebuild

**Passed:** `build/verification/default-nested/receipt.json`.

- Fresh default Java25: **268 passed/one unchanged existing skip**; Java17:
  **237 passed/same skip**. New shared descriptor tests pass on both JVMs.
- Candidate compatibility: Java25 **285 passed/one existing skip**; Java17
  **254 passed/same skip**. No candidate dependency pin or parser promotion.
- Detekt, package/dependency checks and Java17 runtime floor pass for both modes.
- **38 focused Python checks** pass; the default and existing candidate harness
  share the same scoped implementation, with explicit per-lane tasks/output and
  private project caches. No broad corpus campaign or new expected failure/skip.
- Normal `assemble` (without the candidate property) produces a ZIP and adjacent
  receipt identical to the actual fixed bundle loaded/tested live. Default runtime
  dependencies remain catalog0.12.0 and JDBC1.5.6.0; version catalog, global API26
  SDK and Quack report hashes are unchanged.
- Independent final inspection confirms both owned before/after compose projects
  are absent and retained container identities/running states/start times match.

Default rebuild commands/logs and archived per-JVM XMLs are retained beside the
receipt. The old default ZIP/receipt are available under `rollback-default/`; no
SDK, image or retained metadata rollback/migration was performed.

Reproduce the dedicated live gate with `compose/read_correctness_acceptance.py
--released`, two verified released A/resource-B bundles, the released DuckDB1.5.6
CLI and an owned evidence directory. Unlike the positive post-fix gate, the archived
before gate remains explicitly failed despite its passing28-check scalar smoke.

Changes are **not committed or pushed** by this follow-up. This fixes ordinary
current native nested reads in the default lane, not the remaining legacy parser,
nested history/mapping/default/evolution, inline, final1.1 or production limits.
