# F24-C — loaded-provider attestation and qualification receipts

## Scope and status

**Implemented and verified:** F24-08–10 are complete (overall **10/13**). The preceding work
was committed and pushed before starting C, as requested:

- `f248b4b`: F22 acknowledged-resource corpus cleanup and isolated regressions.
- `79ec919`: F24-A/B packaging identity and serialized stopped-FE replacement.

The C implementation was verified before the subsequent user-requested commit/push.
No catalog/runtime/SPI/JNI/dependency pin, provider
payload, capability opt-out or F25 assertion changed. No retained Doris deployment,
legacy migration, full corpus replay or real Doris A/B acceptance was performed.
F24-D/E remain separate; targeted loaded-state tests required by C are groundwork,
not a declaration that D's complete matrix or E's live acceptance is closed.

## Loaded identity and ordering

`compose/plugin_attest.py` is called by the existing supervisor while it retains
the daemon/volume claim. It queries the exact fresh FE container **ID** through
local MySQL, not a load-balanced endpoint or a disk-hashing version endpoint:

```sql
SELECT EXTENSION_NAME, EXTENSION_TYPE, EXTENSION_VERSION, SOURCE
FROM information_schema.extensions
WHERE LOWER(EXTENSION_NAME) = 'ducklake';
```

Batch/raw/no-header output must contain exactly one four-field row with exact
`ducklake`, `CONNECTOR`, `EXTERNAL` and the selected bundle's non-null content
identity. Missing/null/stale/duplicate/conflicting/wrong-kind/built-in rows,
malformed output and SQL errors fail. Case variants cannot hide a conflicting row.
Only the real API-24 columns are used. DESCRIPTION, credentials, private VERSION
tokens and nonexistent load-time/raw-hash columns are not collected.

The upstream API-24 inventory is immutable **load-time registry metadata**, already
audited in the detailed F24 TODO. A current on-disk manifest version never supplies
the claimed loaded version. Installed-file proof is a separate required gate.

Order, all before the continuation, `--up-only` success, UUID generation or any
database/bucket/prefix/bootstrap/catalog/fixture mutation:

1. B's verified stopped installation and new FE process; FE-local `SHOW FRONTENDS`
   readiness needs no BE. Save/verify exact process and runtime-config identity.
2. Resolve BE container/image from the same rendered configuration digest. Check
   existing project/service/image ownership **before** starting only the selected
   BE, without implicitly recreating FE. Wait for live backend registration.
3. Parse named `Alive`/`Version` columns; enforce the exact expected FE source and,
   in the normal lane, matching exact BE source. Malformed/missing/conflicting
   live source inventories fail closed. An empty BE result waits or times out;
   an empty result is never treated as readiness.
4. Query and validate the loaded provider. Then require exact `SELECT 1` readiness.
5. Independently recheck the complete installed file/hash set using a read-only
   helper, and confirm the same FE and BE processes still exist. Changing process
   ID/start time/image/config/mount identity or changed disk bytes fails.
6. Publish the run-owned deployment observation; only then invoke `smoke_run.sh`.
   The continuation requires the attested state and uses the exact FE ID. Its
   original compatibility shim and smoke workload remain unchanged.

The source/readiness gates moved from the shell into the parent so a single owner
controls their outcomes and receipts. A failed identity/runtime gate now leaves
the owned FE stopped for inspection, retains recovery evidence and allocates no
lake. The existing claim/unsafe-cleanup recovery rules remain in force.

### BE availability discovery

A real read-only query against the retained API-24 FE returned **No backend
available as scan node** because its BE is intentionally unavailable. Although
the registry is FE-owned, this inventory's SQL scan still requires an available
BE. Consequently the provider query runs **after** BE readiness/source checks,
not before BE startup. The fake driver now enforces this dependency too.

No BE was started to work around the audit, and no new inventory success/null
observation is claimed. The earlier NULL observation remains historical evidence
for its original running stack. This attempt changed neither the retained FE's
container ID/image/start time nor the runtime-config hash. The default legacy
plugin-volume mismatch remains a refusal, not an authorized migration.

### Explicit stock-BE lane

`apache/doris:be-4.1.4` remains an explicit experiment, not a provenance bypass.
It must still pass exact FE source, loaded provider, installed files and stable
process gates. Its receipt says `qualification=stock-be-compatibility-experiment`;
the nonmatching BE-source gate is explicitly non-required, not silently true.
Normal matching runtime receipts say `matching-pinned-fe-be`. These labels do not
make the stock BE compatible with arbitrary API-24 query plans.

## Receipt semantics and failure boundaries

Each UUID-owned evidence directory retains A/B's candidate, backup and lifecycle
records, plus:

- `deployment-attested.json`: only written after every required identity gate.
  Contains exact ZIP/JAR/file/SDK identity, target/config digest, tooling-file
  hashes, selected and actual FE/BE image identities, source versions, prior/new
  FE process, BE process, SQL inventory result, qualification label and gate
  outcomes. It includes a host observation timestamp, **not** an invented SQL load
  time. No full Docker environment or raw metadata VERSION file is persisted.
- `run-result.json`: written after claim cleanup, records command completion or
  failure separately. A successful deployment observation has `run_success=null`
  while the continuation is outstanding. Consumers need the same-owner current
  deployment observation **and** successful run result for invocation completion.
- All records explicitly keep `smoke_correctness_passed=false` and
  `f25_complete=false`. A zero exit from existing smoke code does not repair its
  soft assertions or certify result correctness.
- An error before attestation creates no current passing deployment record. A
  later command/cleanup error preserves the observation as
  `deployment-attested-before-error.json` and writes a failed run result, leaving
  no current `deployment-attested.json`. Earlier runs' receipts/artifacts are
  never overwritten, relabelled or deleted. An abrupt killed owner may leave a
  pending observation and occupied claim, never a final successful run result.

The original B Docker transport test now explicitly calls only the lower-level
lifecycle primitives and asserts **no** attested receipt. Its simulated SQL
executable cannot prove C's real registry semantics. It preserves the B protocol
coverage without adding a production attestation-bypass flag.

## Verification

All **78/78 Python tests pass, with no skips**, including the opt-in disposable
Docker lifecycle-only transport test. Six disjoint subprocess shards execute every
discovered test once; the slowest shard takes 121.970 seconds. Compose source
hashes match before/after the run. Both Bash syntax checks and whitespace checks
pass. New tests cover exact/raw SQL
shape, stale same-API loaded A with verified B bytes installed, absent/null/
duplicate/built-in/wrong-kind/SQL-error inventories in full and `--up-only` modes,
changed disk/process, foreign BE, backend timeout/malformed source data, exact
source checks even with stock BE, credential-free receipts before allocation,
and failure/command-completion receipt distinctions. Fake loaded identity is
snapshotted at simulated start and retained independently from subsequent disk
changes; it is not simply the selected build echoed on every query.

The earlier serial `f24-c-python.log` finished later with 13 stale-assertion
failures: it had imported expectations that inventory ran before BE startup,
before the live scan-availability discovery corrected that order. It is retained
as development history, not the final result. The final hash-stable rerun executes
all 78 current tests with the corrected BE dependency; its source hashes were
reconfirmed against the working tree after the late serial notification.

Java/build gates pass with unchanged inputs: `check java17PluginTest assemble`,
Detekt, seven build-logic tests and 24,630-class Java-17 runtime scan. Cached normal
suites retain 259 passed/one existing skip (Java 25) and 228 passed/one existing skip
(Java 17); these are not new live engine runs. The default artifact receipt and
raw JAR/ZIP hashes are byte-identical to F24-A/B.

Evidence under `/tmp/opencode/`: `f24-c-first.log`, `f24-c-python.log`,
`f24-c-final-results.json` and its shard logs, `f24-c-build.log`,
`f24-c-build-results.json`, `f24-c-read-only-inventory.log`,
`f24-c-read-only-results.json`, `f24-c-closeout-results.json`,
`f24-c-docker-transport-results.json` and the
retained disposable transport evidence referenced there. Parallel final test
execution discovers every test once, assigns disjoint subprocess shards and
checks compose source hashes remain unchanged through the run.

**Qualification boundary:** C implements a gate for real load-time SQL evidence;
its positive scenarios here use mocks, and the real inventory attempt is blocked
by the unavailable BE. No successful real loaded-B assertion is made. F24 remains
open until the separately approved real Doris A/B acceptance and closeout.
