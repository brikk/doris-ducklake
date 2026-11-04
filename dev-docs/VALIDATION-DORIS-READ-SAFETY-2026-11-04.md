# Doris-local read correctness — 2026-11-04

Follow-up [corpus baseline cleanup](VALIDATION-CORPUS-BASELINE-2026-11-04.md)
independently classifies the 159 retained failures and verifies a test-only scratch
isolation fix on four original files. The full-run numbers below remain historical
exact-artifact evidence; they are not retrospectively edited by that follow-up.

The user authorized Doris-side progress without waiting for upstream 5.x feature
work. Catalog **0.14.0**, SDK/API27, FE/ASAN-BE image pins and feature opt-ins stay
unchanged. No upstream Doris source/runtime rebuild, commit or push is involved.

## Local changes

### F08: unsafe historical deletes now refuse

The ordinary Iceberg reader cannot apply the delete file's per-position snapshot
filter. A historical scan with any active joined delete file therefore refuses
**before** COUNT/LIMIT or file pruning, even when `deleteFilePartialMax` is NULL.
Latest-snapshot deletes and history without delete files remain available.

This is a conservative admission fix, **not** historical-delete implementation:
some physically safe historical files are also refused because metadata alone
cannot prove absence of embedded future deletions. The separately qualified
detached reader facade retains its own bounded contract. F07 physical-view/lease
requirements are not closed by this guard.

### Temporal values: early known-unsafe admission and safe inline conversion

Schema/column binding checks actual temporal column types and known file bounds,
plus literal initial-default evidence, before FE constant folding can turn an
unrepresentable infinity predicate into a silent zero. VARCHAR values spelling
`infinity` are not treated as timestamps. The qualified subset is years 1–9999;
known infinities, BC and out-of-range values refuse.

Live inline scalar temporal values are inspected in one owned read session, with
limits of **64 schemas / 4,096 rows / 16,384 projected values**. Native decoder
refusals retain their cause and become explicit Doris domain errors. Nested/large
inline shapes refuse rather than performing unbounded validation. The writer
checks raw and encoded temporal values before creating/replacing a scratch file,
including timezone year overflow and byte-encoded infinity.

**Remaining boundary:** missing/unknown file statistics are not proof of finite
values; no file payload/footer inspection was added. Expression defaults, broader
temporal domains, timezone semantics and unknown-stat files need further work.
This does not claim every DuckLake temporal value is now representable in Doris.

### F09: no unsafe nanosecond statistics point

TIMESTAMP_NS is still exposed as microsecond DATETIMEV2. Its columns no longer
participate in file min/max pruning until source-aware range widening is qualified;
the full predicate remains for the BE. Other columns/conjuncts keep safe pruning.
This disables an optimization, not precision loss, and is not a general closure of
cast-preservation or source-domain mapping findings.

### Typed complex replay, without JSON guessing

For direct slots/stars from one current owned lake table, the replay adapter loads
source column types under a catalog read session. It normalizes only qualified
MAP/ARRAY/STRUCT JSON cells with that type evidence. Joins, CTEs, computed containers,
time travel and table functions do not establish those hints. JSON-looking VARCHARs
remain scalar strings; SQL NULL, literal `NULL`, empty text and nested NULL differ.

The streaming renderer preserves native field order, integer ranges, decimal scale
and floating signed zero, rejects duplicate/wrongly typed payloads, and bounds
depth/values/text. Current qualification includes VARCHAR-key maps and supported
primitive/nested values; not every map-key/degraded type is admitted.
This is test-only protocol normalization, not a new production nested-data feature.

## Verification so far

- The native historical-delete regression failed before the guard (no refusal).
  It now passes across NULL advisory bounds and all COUNT/LIMIT/pruning combinations.
- Native file bounds retain timestamp `infinity`; a kept-open native writer creates
  true inline rows. The catalog's decoded reader already refuses their non-finite
  value; Doris now establishes that boundary at schema binding rather than later.
- Finite timestamps and VARCHAR infinity literals retain their behavior. Inline
  budget refusal occurs before payload reads; bad temporal values cannot create
  or truncate a scratch file.
- Focused safety/writer/temporal audit checks and typed projection/rendering tests
  pass. The current-type resolver also refuses historical/function type guessing.
- Fresh default gates: **310 Java-25 passes + 1 existing disabled test**, **270
  Java-17 plugin passes + the same disabled test**, 8 build-logic tests; Detekt,
  package identity/assembly and Java-17 runtime scan pass. Logs and the captured
  JVM summary are in `build/validation/doris-read-safety/`.
- Historical candidate checksum attestation, test compilation, lint, package and
  Java-17 runtime ABI checks pass without changing its dependency lane. This is
  not a new candidate live campaign.
- Matching A/resource-only B bundles are assembled and have identical provider
  class bytes. Exact-bundle owned live verification **passes** as described below.

All heavier work uses one private 6 GiB / no-swap / 300% CPU scope, one Gradle
worker, no retained daemon and no overlapping campaigns.

## Exact live proof

Current A identity:

```text
build_id doris-ducklake-v1-ea3d323e35d81a01dad1cce1644ac660728e6b7b6b11a6c9a3506ffb9140a01a
plugin_zip_sha256 3e231b13fca63a8f4f9a16cd42f9572ea3c5430432feaeb62b7d7d56fad656e6
```

Controller log: `build/validation/doris-read-safety/live-controller.log`.

**Passed: 3/3 live tests, zero errors/failures/skips**, executing the selected
plugin on the real API27 FE/ASAN-BE pair:

- Historical COUNT/SUM and LIMIT reads with deletes throw the explicit safety
  error. The native oracle confirms the older view contains 7,500 rows; Doris's
  latest view still returns **5,000 / sum 25,000,000**, and delete-free historical
  data returns its expected **42**.
- File-backed and actually live inline timestamp infinities refuse for all three
  predicates that previously produced wrong zero counts. The finite timestamp /
  VARCHAR `infinity` control still returns one matching row.
- The **original, unedited `types/map.test` passes 5/5 Doris mirrors**, including
  the three formerly divergent MAP results. An authored VARCHAR control passes
  **1/1**, preserving JSON-looking strings, empty strings, literal NULL and SQL NULL.
- Source/corpus/tooling fingerprints and loaded-provider identity passed. Owned
  containers, volumes, networks and the 6 GiB scope were cleaned; independent
  checks find no remaining owned resources or running containers. Protected state
  is unchanged, and the default ZIP plus full identity receipt still equal the
  selected live A bundle.

Persistent live evidence:
`build/validation/doris-read-safety/f24-e-5d15a4nl/{acceptance.json,summary.json,readSafetyLiveTest.xml}`.

`compose/read_safety_acceptance.py` creates only acknowledged disposable resources
and checks the loaded plugin identity. `readSafetyLiveTest` is explicit, not part
of ordinary tests, and has no assumption skips. It checks historical-delete
refusals and positive controls, file/inline infinity refusals before wrong counts,
the original unedited `types/map.test`, and JSON-looking VARCHAR/null distinctions.
Source/corpus hashes, marker-complete JUnit results, scope shutdown and protected
state/owned cleanup gate acceptance.

The earlier [full 0.14 corpus](VALIDATION-catalog-0.14.0.md) stays a **failed**,
artifact-bound baseline. These changes do not retrospectively erase its failures
or mark newly refused upstream queries as passing. Native/backend/golden-baseline
failures remain a separate follow-up; no new upstream skips/goldens are introduced.

## Comparable full rerun

A single serial full 1.0-lane rerun **completed, still failed overall**, on the new
exact bundle. Corpus commit, every selected SQL-file hash, existing exclusions,
475-file selection and the 40-mirror cap match the earlier run. Guarded refusals
remain errors against original positive expectations, not manufactured passes.

| Observation | Before local changes | After |
|---|---:|---:|
| Doris result divergences | 7 | **0 observed** |
| Doris mirror passes | 296 | **299** |
| Doris mirror failures | 15 | **12** |
| Record passes | 8,734 | **8,738** |
| Record failures | 163 | **159** |

Both runs processed 426 files to completion, with 49 skipped/halted, 100 record
skips, 399 mirror attempts and 88 attempt-skips. One explicit format-scope halt
remains; neither run timed out or blocked on a quarantined worker.

The three MAP divergences are now passes. The historical-delete divergence and
three infinity-predicate divergences are now **explicit safety errors**, not
restored historical/infinite-value feature support. The literal-only SHOW binder
correction removes one oracle/harness failure. Total mirror/gate skips increase
from 1,969 to 1,970 because that native query now passes and reaches the existing
non-mirrorable/dialect gate; no new skip rule was introduced.

Remaining failure stages:

- **12 mirror errors:** 4 new intentional safety refusals plus the existing nested
  import/history, encrypted-Parquet and `snapshot_id` virtual-column errors.
- **2 custom metadata-schema adapter-connect failures**.
- **1 quoted/modified connection format-inspection failure**.
- **144 other oracle/backend/golden/harness record failures** requiring a matched
  baseline and separate investigation. They are not all proven catalog regressions.

This is **zero observed Doris value divergences in this measured campaign**, not
proof that all reads/types/features or unknown-stat temporal data are correct.

Persistent rerun evidence:
`build/validation/doris-read-safety/f24-e-h_b33769/` contains `acceptance.json`,
`summary.json`, `comparison.json`, `corpus-report.txt`, `failure-index.csv` and XML.
The controller log is `build/validation/doris-read-safety/full-controller.log`.

The rerun's owned containers/volumes/networks and systemd scope are cleaned;
independent checks confirm no owned resources remain. Protected state is unchanged,
the default ZIP/full receipt still match the accepted safety A artifact, and no
containers remain running. Validation finished before the user's subsequent
commit/push request; evidence remains tied to the exact bundle identities above.
