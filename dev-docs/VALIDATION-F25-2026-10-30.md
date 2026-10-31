# F25 — supported-scope smoke correctness gates

## Scope and status

**Implemented and scoped live-verified.** All 117 frozen-source Python tests,
28 required real supported-workload assertions and same-count tuple-corruption
refusal pass. F24-D/E was committed and
pushed first on request as `6d55b9f05a9aa74be76ea04279a9c0950fa44dad`.
The unrelated Quack verification report remains untouched. F25 does not change
catalog/Doris/JNI/dependency pins, provider payload, capability opt-outs, F23
exposure, R07 engine hooks or retained-stack ownership/migration.

## False-success countercheck

Before the fix, two whole-driver regressions deliberately supplied zero TPC-H
counts and incorrect nullable COUNT results. Both still exited zero and printed
`Smoke complete`. Three native write-helper regressions supplied same-count
corrupted strings, boolean IDs and floating IDs; all were merely printed, not
rejected. These **five intended assertion failures** are retained in
`/tmp/opencode/f25-before.log`; this is baseline evidence, not a final test failure.

## Required correctness proof

`smoke_checks.py` defines a fixed supported-workload scope and mandatory case
policy. Each check records typed expected/actual values, operator, outcome and
timestamp in a fresh owner/build/run/runtime/target-bound assertion ledger.
Required checks cannot be omitted, duplicated, weakened or silently replaced by
a different expectation. SQL/setup/transport errors abort; no destructive follow-on
work is attempted after an unexpected setup failure. Earlier successful assertions
remain visible and the abort phase is recorded.

The full run covers exact pinned TPC-H counts and reference rows, nullable count
and rows, filter plan plus actual filtered rows, position-delete count and exact
rows, DDL/schema/column/order/empty/insert/partition/drop, unpartitioned and bucketed
writes, exact bucket arity/values/pruning, CTAS rows/files, exact DEFAULT backfill,
expiry/scheduled cleanup/orphan selectivity and actual referenced-file reads.
All expected write/default/nullable tuples are typed; bool/float/string IDs cannot
masquerade as equal integers. Native DuckDB verifies INTEGER/VARCHAR write schema
and tuples; Doris JSON rows are compared field-for-field, not by row count.

No current wildcard `KNOWN-BLOCKED` or version-independent fallback remains.
The reviewed current-pin expected-failure allowlist is empty. Future expected
failures must match exact case, FE/BE source and observation and remain non-green.
Unsafe narrow-int Iceberg writes are outside this INT32/STRING workload, not a
newly claimed passing capability or an automatically added skip.

`smoke-correctness.json` can be emitted only after the entire required policy
passes. The supervisor independently validates completeness, typed predicates,
owner/build/run/runtime/target, ledger hash and policy hash, and checks FE/BE
process stability. Zero child exit alone is insufficient. Generic final success
is printed only by the supervisor after claim cleanup and successful run-result
publication. `--up-only` remains deployment-only, with correctness unexercised.
Later command/cleanup errors retire current passing observations to explicit
before-error history, never a passing current run.

Deployment attestation remains a distinct earlier observation. A successful full
run now has explicit supported-scope correctness evidence; this is not an assertion
that arbitrary SQL/features, production performance or all R07 paths are certified.

## Safe independent native checks and configured routing

Full mode resolves PostgreSQL, MinIO, common network and actual image identities
from the selected compose files and checks existing ownership before start. It
does not guess global container/network names. Secret values go to the child
environment, never deployment/correctness receipts. Native helpers validate the
run-owned database/prefix before connecting, require DuckDB 1.5.6 and escape SQL
and libpq layers separately. This is local smoke wiring, not a general tenant or
credential-isolation claim or full closure of the broader M17 audit.

MinIO absence requires structured `NoSuchKey`/`NoSuchObject`, or the pinned MC's
exact code-free structured object-missing response **plus** an independent
successful empty exact-path listing. A tiny isolated MinIO/MC probe confirms that
format/list behavior. Auth, missing bucket or transport failure cannot become a
false `gone`. Procedure result schema,
non-dry-run status, counters and expiry IDs are checked against metadata/storage
effects. After GC, both engines read exact referenced fields; a metadata-only
COUNT cannot hide a lost/corrupted Parquet file. Successful marker cleanup is
also required, scoped only to the current run's exact keys.

## Verification and limits

The strict stand-in full run and focused assertion/helper/receipt/routing/guard
regressions pass. Final **117/117 tests, no skips**, execute once across six
disjoint subprocess shards with Python/shell source hashes unchanged before/after;
slowest shard is 289.971 seconds. Earlier development runs are not final evidence:
their source-hash guard refused certification while sources changed, and one
recorder assertion needed to distinguish new read-only object LIST checks from
the initial namespace-allocation preflight. Final output:
`/tmp/opencode/f25-final-v4-results.json` and its shard logs.

Both Bash syntax/whitespace checks and `check java17PluginTest assemble`/Detekt
pass. Java-17 runtime floor scans 24,630 classes. The unchanged JVM suites correctly
reuse 259 passed/one existing skip (Java 25), 228 passed/one existing skip (Java 17)
and seven build-logic tests; these are not claimed as fresh JVM test executions.

`smoke_acceptance.py` reuses the F24 UUID-owned pinned FE/BE/PostgreSQL/MinIO
sandbox and a byte-identical copy of the existing TPC-H generator. It never
targets retained services. A full supported S3 smoke must produce complete
correctness proof. A second full run verifies actual correct three-row data before
injecting only one same-count wrong string; it must fail at tuple equality with
no current passing receipt/summary. Recorder guards still reject foreign targets;
credential-bearing arguments are redacted in its private trace.

The first live attempt correctly failed before summary at an overly format-specific
plan assertion. Internal node/predicate spelling is not the SQL contract: plan
production is now checked structurally and actual filtered results are independently
compared against native DuckDB. No count/row/value expectation was relaxed.
The next attempt found that persisted column-order values are allocated beyond
zero (the correct ordered names were `id,name`, at 16/17). The contract now checks
relative column-name order plus native INTEGER/VARCHAR schema, not invented
table-local zero-based IDs. A later guard-only refusal rejected the deliberately
seeded cleanup schedule row in the owned database; its allowance is now limited
to that exact UUID database/prefix, row ID and filename, with foreign/shared
variants rejected by a regression. All these attempts remain failed evidence.
The subsequent run reached cleanup but safely rejected MC's code-free missing-key
response; the independently confirmed-absence handling above fixes that transport
representation without treating arbitrary errors as missing files.

Raw evidence is under `/tmp/opencode/f25-*.log` and the isolated fixture's printed
`/tmp/opencode/f24-e-*/` directory, including copied code, generator hash, all
receipts, ledger, raw correct and injected tuple values, image/source identities
and cleanup/protected-state results. No false live-green result, engine workaround,
new skip, dependency upgrade or retained deployment is claimed. Verification
preceded the subsequent user-requested F25 commit/push.

## Real full-S3 result

**Passed** at `/tmp/opencode/f24-e-bx3p7t76/acceptance.json`, using the unchanged
pinned API-24 FE/full ASAN BE, catalog 0.12.0 and native DuckDB 1.5.6:

- Full supported workload: **28/28 required checks**, exit 0, final
  `run_success=true`, `smoke_correctness_passed=true`, `f25_complete=true`.
  Scope is `doris-smoke-supported-api24-v1`; run ID is
  `0de3015ce5ce40d6b4fe676ea5cf9b49`.
- Second full run: the recorder first observes real correct rows
  `[[1,"alice"],[2,"bob"],[3,"charlie"]]`, substitutes only
  `bob → CORRUPTED-SAME-COUNT`, and preserves the three-row cardinality.
  The `w2_rows` assertion fails; exit 1, both final success/correctness flags are
  false, and no current deployment/correctness passing receipt survives.
- Complete cleanup/expiry/orphan counters and effects, foreign-marker survival,
  referenced Parquet field reads in **both** engines and final marker removal
  pass. Only markers proven present are deleted; the already-proven-absent orphan
  is independently verified absent, not blindly removed again. The pinned MC
  rejects removal of an absent key even with `--force`; that earlier attempt is
  retained as failed evidence, not waived as a passing cleanup.
- All acknowledged disposable resources are cleaned. Retained containers/config,
  the ordinary plugin distribution and unrelated Quack report remain unchanged.
  No native/Doris rebuild, metadata reset workaround or capability/pin change is
  used. Resource-only B has the same existing classes/dependency/SDK bytes as A.

Exact copied code and default artifact hashes were independently reconfirmed
against the live report. Authoritative final regression output is
`/tmp/opencode/f25-final-v4-results.json`; build gate log is
`/tmp/opencode/f25-build-check.log`. MC transport probes are
`f25-mc-absent-probe.json` and `f25-mc-empty-list-probe.json`.
`/tmp/opencode/f25-closeout-results.json` independently verifies source/result
bindings, all 28 cases, positive/negative outcomes, absence of credentials in
receipts/ledger, no current passing negative receipt, unchanged retained state/
ordinary plugin/Quack report, and no remaining owned Docker resources/claim.

This closes F25's existing supported-workload correctness-gate scope. It does not
certify arbitrary data types/queries, production/performance, TLS/fleet operation,
broader R07 features, F23 exposure, all M17 configuration paths or legacy migration.
