# F24-D/E — stateful failure closure and real loaded-build acceptance

## Scope and status

The user's `F23-D/E` instruction is interpreted as **F24-D/E**, the two items in
the immediately preceding backlog list. F23's default development-network exposure
and F25's broad smoke correctness assertions are not silently included.

**Implemented and verified:** D's full regression matrix and E's real loaded A/B
acceptance pass. F24 is **13/13 complete** within its provenance/replacement scope.
Base: `7a83a8d` (A–C
committed/pushed). No FE/BE rebuild, catalog/SDK/JNI/dependency upgrade, feature
enablement, full corpus campaign or retained-stack migration is performed. The
uncommitted Quack 0.11.0 verification report is preserved, not adopted or deleted.

## D — stateful orchestration coverage

The stand-in still executes the real ZIP/backup/install/hash worker against
isolated local files; SQL replies are a model, not Doris/classloader proof. Loaded
identity is snapshotted at simulated start, separately from selected and disk bytes.

New regressions run **two actual complete smoke-driver processes** against one
shared simulated daemon. The first pauses at inventory or at its first UUID
request, releasing the stand-in's state-transaction mutex so the rival can proceed.
The second selects a different same-API bundle and must promptly refuse the occupied
daemon/volume claim. It cannot stop FE, write/install, execute SQL, allocate a lake
or release the first owner's claim. The first then completes. This proves the
claim remains held during attestation **and throughout the continuation**, not
merely while unpacking.

`fcntl` protects only simulated Docker state transactions. The production claim
is still the atomic daemon container name and needs no host `flock` binary.

| Boundary | Coverage and required result |
|---|---|
| Selection/preflight | Missing/ambiguous/explicit ZIPs, corrupt archive, bad receipt/hash/API/package override, unreadable-copy injection; no target writes and only own staging cleaned. |
| Ownership/serialization | Wrong FE project/service/mount, foreign/orphan plugin volume/consumer, foreign BE, occupied/killed claim, changed token, unconfirmed cleanup and overlapping full invocations; no foreign stop/adoption. |
| Preparation/stop | Preparation/backup/stop failure or unconfirmed stop in full/headless modes; no plugin-volume writes or lake allocations. |
| Complete installation | Partial copy failure, exact hash/extra-file failure, changed old snapshot and a successful Docker client with failed worker `ExitCode`; FE stopped, rollback retained, no current passing receipt. |
| Process/start | Absent/running/stopped FE, explicit start failure, stale start time, changed cluster ID, SQL timeout, changed process or runtime config; no reset as a reload workaround. |
| Loaded identity | Correct, stale same-API A over verified disk B, absent/null/duplicate/conflicting/malformed/wrong-kind/built-in/unknown-source rows and inventory SQL errors; full/headless allocation is blocked on failure. |
| Runtime source/readiness | Exact FE/BE pin, stale matched or mismatched pair, empty backend registration/timeout, malformed headers, failed readiness and explicit stock lane; stock cannot skip FE/provider gates. |
| Success/failure evidence | Claim → stopped writes → verified files → fresh FE → registry gate → final files/process checks → receipt → allocation; failed later commands retain observations only as history, never F25 correctness. |

The original routing/guard cases remain. The focused new overlap/install/IO run
passes five tests. The complete **96/96** Python run passes without skips,
including the optional real-Docker lifecycle transport test; its six disjoint
shards execute each discovered test once and preserve all Python/shell source
hashes through the run. Slowest shard: 173.194 seconds.

## Two real-engine findings fixed without rebuilding Doris

1. **Empty trailing MySQL columns:** `SHOW FRONTENDS` on the actual API-24 FE has
   21 columns, including a final empty value. The old Docker wrapper's `.strip()`
   removed its trailing tab and made strict TSV parsing reject a healthy source
   inventory. It now removes CR/LF only. A regression retains the empty field;
   a real read-only probe returns all 21 fields and the exact pinned version.
2. **First-boot runtime configuration:** the pinned image's
   `/usr/local/bin/init_fe.sh:403-415` appends `priority_networks` when initializing
   empty metadata, even when the same value is already staged. The first real
   attempt refused this safely, kept its owned FE stopped and produced no passing
   receipt. The driver now permits exactly one appended duplicate of the existing
   active value on a first/never-started container, records before/after hashes,
   and freezes the initialized bytes. Different networks, other byte changes,
   repeat appends and any change on a previously started container still reject.

There is no generic config-hash opt-out, hot-reload path or image patch. Existing
FE configuration remains byte-for-byte protected through replacement/restart.

## E — bounded real acceptance design

`compose/plugin_acceptance.py` is an explicitly invoked acceptance runner, not a
default test or production deployment mode. It copies the **unmodified public
driver and helpers**, with exact source hashes, into a new private sandbox. Its
compose layout matches the driver's expected sibling structure, without adding a
production ownership/attestation bypass or targeting retained compose files.

- Unique FE/BE, PostgreSQL/MinIO, volumes and networks; every chosen name must be
  absent before creation. No published FE/BE ports; PostgreSQL's ephemeral producer
  port binds loopback only. PostgreSQL 18 mounts its actual data root explicitly.
- Exact existing FE image
  `sha256:f41c6a1710c66f0d0ab0334d5431af85c2002e8399de09ae10b8559bccb8c22a`
  and complete ASAN BE/JNI image
  `sha256:ccca761cae5bc1e6122b4449c21d01117d74a095ce203cf9f4d27094ede8a2de`;
  source `62e3d81a42b`, connector API 24 / JNI 6, released catalog 0.12.0.
- Existing default provider A:
  `doris-ducklake-v1-00dca0beef3e3c6e60521c127a701dfe58097616c0cf92eabe0a00fefc83479d`.
- B: `doris-ducklake-v1-2c41c72289a0e38ba360588591f6a82c8734968c938e1dc4df7019c284b0248d`.
  It is a separately built resource-only variant. API/release/SDK/dependencies and
  every existing non-manifest provider entry are identical; only the new
  `META-INF/f24-acceptance-build.txt` entry and identity-bearing manifest differ.
  B is not promoted over the default distribution or installed into a retained FE.
- A and B both use the actual `smoke.sh --up-only` path. A released DuckDB 1.5.6
  producer seeds one owned PostgreSQL-backed, native-file DuckLake fixture with
  inlining disabled. Exact BIGINT/string/decimal/NULL/quoted-string rows are read
  through real Doris before and after replacement using the same catalog ID.
- The FE container, cluster ID, catalog ID and mounts survive; FE start time must
  advance. The BE container/image/start time must not change. B's complete rollback
  archive is checked against A's exact file hashes. Warehouse bytes stay unchanged.
- A guarded real-Docker recorder forwards actual replies for positive phases. The
  negative **full-run** phase verifies the actual inventory is B, then substitutes
  only version A in that one response. It must refuse at the provider gate before
  any UUID/database/bucket/prefix/bootstrap/catalog/data allocation attempt, keep
  the owned FE stopped and leave no current passing receipt. Guard unit tests
  additionally prevent foreign targets or allocations if a gate regression occurs.
- This is an **injected stale observation**, not a claim that the correctly
  restarted real process actually retained A. No JAR is swapped beneath a running
  classloader. Positive loaded A and B observations are real SQL registry values.
- A final verified B recovery checks the same metadata and exact rows again.
  Only acknowledged disposable test resources are torn down after evidence is
  saved. An uncertain retained deployment claim forbids automatic teardown/steal.

## Results and evidence

**Real acceptance passes** at `/tmp/opencode/f24-e-h492r05w/acceptance.json`:

| Phase | Actual SQL load-time observation | Outcome / allocation attempts |
|---|---|---|
| A, real `--up-only` | A's full build identity | exit 0 / 0 |
| B, real stopped replacement + `--up-only` | B's full build identity | exit 0 / 0 |
| Negative full run | actual SQL B is recorded, then only its returned version is injected as A | provider refusal, exit 1 / 0; FE stopped; no current passing receipt |
| Verified B recovery | B's full build identity again | exit 0 / 0 |

Cluster ID **1252009376** and catalog ID **1793395895548** are unchanged. The FE
container/mounts/hostname are unchanged, while its start advances from
`2026-10-30T21:31:33.477005461Z` to `2026-10-30T21:32:17.547268142Z` for B.
The BE container/image/start stay unchanged at `2026-10-30T21:31:41.190148115Z`.
All three exact read checks (A, B, recovered B) return:

```text
1 | alpha   | 10.25
2 | NULL    | -3.50
3 | quote'd | NULL
```

The complete rollback archive hashes to A's full installed set. Warehouse bytes
are unchanged. All disposable resources are cleaned after evidence capture;
protected retained containers/config/default artifact and the Quack report are
checked unchanged. Early setup failure and first-boot refusal remain as failed
evidence; they are not relabelled as passing runs.

| Artifact | Provider JAR SHA-256 | ZIP SHA-256 |
|---|---|---|
| A | `621c9160599713482ae9e171efa61798e987a2754801faea6c5615afac5d7595` | `e0cf35bc092bfcf19a39086fd6473b6da4d71f13b3c58b9f4b7677411216a1f5` |
| B | `eb45cf26cfe0d7d63af70dde2a05950c1162d20b3c0fdef874241c95fb0d7734` | `5d7f726de6c7412fab7aace29145092f57b343cacd501a1a2211dfd6daffcdba` |

The compile-SDK identity is unchanged:
`5348ee7f19b2782abded11eff86a9b7e9ce27710ed26ae9679ac448c6c3ea831`.
Both A and B pass the 24,630-class Java-17 runtime floor. Default `check
java17PluginTest assemble` and Detekt pass; unchanged Java suites correctly retain
259 passed/one existing skip (Java 25), 228 passed/one existing skip (Java 17) and
7 build-logic tests. Those cached results are not described as fresh executions.
Both Bash syntax checks and diff whitespace checks pass.

Raw evidence: `/tmp/opencode/f24-d-focused.log`, `f24-de-parsers.log`,
`f24-de-real-findings-regressions.log`, `f24-e-scope-guard.log`,
`f24-e-build-B.log`, `f24-e-bundle-results.json`, `f24-e-live*.log`, and each
UUID-owned `/tmp/opencode/f24-e-*/acceptance.json` with preserved code, artifacts,
receipts, raw real inventory at injection, metadata identity and read results.
Authoritative full regression result: `/tmp/opencode/f24-de-final-results.json`
and its shard logs. Build gate: `f24-de-build-check.log`. The checked-in B
reproduction init script and marker fixture replace the original local-only build
instructions; its independent reproduction result is recorded separately.
`/tmp/opencode/f24-e-reproduction-results.json` confirms that the checked-in init
script/marker produce the **same complete receipt and raw ZIP/JAR bytes** as the
live-qualified B, in a different build directory. Its guard also rejects the
default output before task execution. Logs: `f24-e-reproduce-B.log` and
`f24-e-reproduce-output-guard.log`.
`/tmp/opencode/f24-de-closeout-results.json` independently reconfirms the final
source hashes, live observations/negative refusal, one recorded bootstrap config
initialization, stable config through B/recovery, exact reproduced bytes, 13/13
ledger, untouched retained state and absence of all disposable Docker resources.

F24 is closed with the real loaded-B result and required checks, not merely the
mock tests or engine/API equality. D/E were verified before the subsequent
user-requested commit/push. F25 remains open; a three-row bounded read is not full smoke correctness
or production/performance qualification, and pristine DuckLake BE is not
DuckBridge's patched JDBC/Quack qualification.
