# Corpus baseline cleanup — 2026-11-04

The user selected baseline cleanup and requested an upstream Doris review first.
[Upstream review](UPSTREAM-DORIS-2026-11-04.md): 51 commits since our runtime pin,
API29 and useful reader fixes; 4.2 RC2 exists and its branch is actively stabilizing,
but no final release/date was confirmed. **No SDK, FE/BE image, dependency or
tracked corpus pin is changed here.** No upstream builds or cross-project work.

## What the 159 retained failures actually measure

Input: the completed, failed exact-bundle campaign
`build/validation/doris-read-safety/f24-e-h_b33769/`. Its **159 failures in 61 files**
remain unchanged. New controls replay every one of these files without a Doris
adapter, keeping released replay 0.14.0, the 1.0 setup/execution lane, PostgreSQL
ALL_ATTACHES rewriting and the existing metadata SQL binder.

**These are failures from our current pinned build**, not upstream Doris:
source `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146`, plugin API27, catalog 0.14.0,
JDBC 2.0.0.1 and tracked corpus `c23aca43b6033acfac2b8cc8d80b15ae7fa729b2`.
The retained bundle is
`doris-ducklake-v1-ea3d323e35d81a01dad1cce1644ac660728e6b7b6b11a6c9a3506ffb9140a01a`.
The classification below uses that campaign and current-pin backend controls;
neither upstream Doris changes nor the separate producer-corpus comparison are
substituted for current-build results. **Accounted for means classified, not all
fixed.** Seven fixes are verified only by the later four-file rerun on the same
runtime/bundle; the full corpus has not been rerun after scratch isolation.

| Retained failure class | Records | Independent evidence / next action |
|---|---:|---|
| Native or shared replay baseline | 135 | Fails at the same file/line on embedded **and** PostgreSQL with no Doris. Align corpus/native expectations; not 135 demonstrated Doris regressions. |
| Metadata-backend axis | 2 | Embedded passes, PostgreSQL fails: warehouse `*.db` glob expects a local metadata file. Keep distinct from connector reads. |
| Live-only physical-file counts | 7 | Both no-Doris controls pass; all inspect warehouse globs. Investigate dev-only scratch files, not row values. |
| Shared replay format inspection | 1 | Quoted ATTACH identifiers/options are misparsed even with no Doris. Preserve the fail-closed format gate; repair the shared parser. |
| Adapter connection configuration | 2 | Custom metadata schemas are not passed into the FE adapter's default PostgreSQL search path. Native controls pass. |
| Doris safety refusals | 4 | Historical position deletes and known non-finite temporals: intentional errors, **not restored support**. |
| Doris nested-read refusals | 3 | Two legacy/mapped imports and one nested history query; remain unqualified. |
| Doris encrypted-file errors | 3 | Unsupported encrypted Parquet path; upstream build/admission work does not implement DuckLake key transport. |
| Doris virtual-column errors | 2 | `snapshot_id` is not exposed by this connector. |

The machine join deliberately calls the seven live-only cases
`live-only-or-nondeterministic`: an independent rerun alone does not prove cause.
Their scratch-path hypothesis is tested separately below. Classification never
changes the original SQL, golden, record failure or qualification result.

## Matched native/corpus controls

The tracked corpus is **June 17** `c23aca43b6033acfac2b8cc8d80b15ae7fa729b2`.
The native signed DuckLake extension actually loaded by JDBC reports
**`417a9117f5`**, matching the producer's **October 19** documented corpus pin
`417a9117f590e8141742ac0579d02f6a90162e37`. This is substantial source drift, not
just a renderer theory.

All four axes use DuckDB **v2.0.0**, `duckdb_jdbc-2.0.0.1.jar` and identical
catalog/replay/SLT 0.14.0 JAR hashes. The JDBC driver's metadata version string is
only `1.0`; the JAR identity, not that string, establishes its artifact version.
The DuckLake extension SHA256 is
`4b497cf95d768004d34a97e7098d33f687e994dc523945948c321af25061b39b`.

| Corpus | Backend | Present / selected | Ran to end | Record passes | Record failures |
|---|---|---:|---:|---:|---:|
| Current pin | Embedded | 61 / 61 | 60 | 1,770 | **136** |
| Current pin | PostgreSQL | 61 / 61 | 60 | 1,768 | **138** |
| Producer pin snapshot | Embedded | 60 / 61 | 57 | 1,286 | **9** |
| Producer pin snapshot | PostgreSQL | 60 / 61 | 57 | 1,284 | **11** |

These are **diagnostic selections, not full corpus or Doris acceptance**. All have
zero mirrors and zero record skips. Both current-pin runs halt the quoted ATTACH
file after retaining its format-inspection failure. Both producer-pin runs also
halt two files on the shared runner's unsupported `require notmusl`; one original
cache-growth test was removed upstream. Missing/changed/unsupported records are
**not passes**, and lower counts do not mean every old failure was fixed.

The producer-pin residuals are explicit:

- **6 TIME JDBC conversion errors**, three each in `types/all_types.test` and
  `data_inlining/data_inlining_types.test`.
- **2 deletion-vector expectations** in `delete/deletion_vector.test`, still
  needing native 1.0-versus-feature/setup investigation.
- **1 quoted ATTACH format-inspection failure**.
- PostgreSQL adds the **same 2 local-metadata-file glob mismatches**.

Source comparison confirms upstream already changed `stats()` expectations to
DuckDB 2.0's structured output, configured upper-case profiling operators, selected
the intended syntactic-error transaction policy, excluded new empty-struct/tuple
columns from `test_all_types`, and adjusted other native tests. No such expectations
were copied into or edited in our pinned corpus. Exact unambiguous SQL counterparts
are recorded; repeated/changed SQL remains unmapped rather than guessed equivalent.

## Replay scratch isolation

Seven retained failures are warehouse-glob counts in four original files:
`data_inlining/data_inlining_flush.test`,
`sorted_table/data_inlining_flush_sorted_basic.test`,
`sorted_table/data_inlining_flush_sorted_basic_expression.test`, and
`sorted_table/insert_sorted_sort_on_insert_option.test`.

The experimental inline reader's default scratch is a subdirectory of the warehouse,
so recursive self-inspection can count transport artifacts. The isolated replay
controller now supplies the existing **`scratch.path`** option pointing outside
every oracle TEST_DIR/warehouse but inside its same-path FE/BE bind mount. Scratch
children are unique per engine and acknowledged metadata database, preventing
table-ID/snapshot collisions across lakes. Cleanup follows no symlinks, removes
only the allocated child and retains it if FE catalog removal fails.

This is a **test adapter/controller fix**. Production shared-storage prerequisites,
default inline behavior and unsupported distributed transport remain unchanged.
No new skip, exception-to-success rule, SQL rewrite or weakened golden is used.

**Real FE/BE selected-file verification passes** on the unchanged, previously
qualified API27 safety A ZIP, with loaded-provider/source checks:

- **4/4 original files ran to end; 144 records passed, 0 failed, 2 existing skips.**
- All **seven original file-count failures now pass** without editing their SQL
  or goldens. The retained pre-fix full run supplies the failing control.
- **20 Doris mirror attempts: 18 passed, 0 errors, 2 existing attempt-skips** for
  unsupported partial-file history. There are 42 total mirror/gate skips, including
  33 dialect gates and 7 transaction-open gates; these are not extra attempts.
- **60 oracle/golden query passes**, zero file skips and zero JUnit skips. This is
  four-file acceptance, **not a new full 475-file campaign** or restored history
  support. The original full result remains 159 failed records.
- Owned scratch children are gone after adapter close. Owned FE/BE/substrate
  containers, volumes, networks and the Gradle scope were cleaned; independent
  checks confirm cleanup and all six baseline/check/live scopes are unloaded.
  Protected state and the default ZIP/full identity receipt are unchanged.

Live evidence:
`build/validation/corpus-baseline/f24-e-d21azhue/{acceptance.json,summary.json,corpus-report.txt,corpusReplayTest.xml}`.

Fresh isolated JVM checks pass: **313 Java-25 tests** and **270 Java-17 plugin
tests**, with the same one existing disabled test in each lane. Three new scratch
tests cover target isolation, unchanged defaults, path rejection, parent/neighbor
preservation, no-follow symlink cleanup and idempotent close. `check`, Detekt and
the Java-17 runtime/package checks pass. The default distribution remains the exact
prior safety ZIP (`3e231b13fca63a8f4f9a16cd42f9572ea3c5430432feaeb62b7d7d56fad656e6`);
only test adapter/controller sources changed. The focused Python guards pass 24/24.

## Evidence and remaining decisions

Under `build/validation/corpus-baseline/`:

- `baseline-92g0r3jb/`: current-pin embedded.
- `baseline-o_n0dln_/`: current-pin PostgreSQL.
- `baseline-5abp_jv_/`: producer-pin embedded.
- `baseline-fn3y11en/`: producer-pin PostgreSQL.
- `triage/{triage.csv,summary.json}`: all **159** original failures accounted for,
  with backend states, full reasons and optional producer SQL counterparts.
- Every axis retains `campaign.json`, `baseline.json`, `report.txt`, source snapshot,
  file/JAR/extension hashes and JUnit results. A completed diagnostic does **not**
  turn its failing native JUnit assertion green.

Heavy runs were serial, one worker, private 6 GiB/no-swap/300% CPU scopes, no retained
Gradle daemon. Oracle baseline runs did not launch an FE or BE. Tracked submodule,
runtime pins and previous artifact-bound reports remain untouched.

Next baseline work is now bounded: review a producer-pin corpus adoption as its
own coverage change, repair shared TIME/ATTACH/require handling, clarify the native
1.0 deletion-vector expectations, and thread custom metadata-schema context through
the adapter. Do not add exclusions or drop the 1.0 scope gate to make this green.
The full production/read/write safety backlog remains independent.
