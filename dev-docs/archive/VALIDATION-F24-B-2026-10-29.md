# F24-B — serialized stopped-FE replacement

## Scope

**Implemented and verified:** completes **F24-03–07**, reusing A's frozen ZIP, adjacent artifact receipt and
content identity. F22 and F24-A changes are preserved, uncommitted. No catalog,
SPI/JNI, FE/BE source pin, provider payload, feature gate or dependency changed.
No commit/push or replacement on the retained Doris infrastructure is performed.

**This is lifecycle/install evidence, not loaded-provider qualification.** F24-C's
SQL inventory gate/qualification receipt, D's full loaded-state regression matrix
and E's real Doris A/B acceptance remain open. F25 assertions remain separate.

## Implementation

`compose/smoke.sh` remains the public entry point. It builds/selects/freezes with
A's read-only helper, then invokes `plugin_deploy.py`. The existing SQL/source
guards and smoke workload move without semantic changes to `smoke_run.sh`, an
internal continuation that runs only after the stopped-install/fresh-start gates.
The parent retains its claim until that continuation exits, including `--up-only`
and failure paths. Bash forwards INT/TERM to the supervisor; the supervisor stops
and reaps the continuation's process group before releasing its claim.

### Exclusive ownership

- Atomic Docker container-name acquisition: `doris-ducklake-deploy-<SHA256(volume)[:40]>`.
  The daemon's container namespace supplies the daemon scope; clients/checkouts
  reaching the same daemon and named volume contend for the same claim. Unrelated
  volumes do not acquire a global lock. No host `flock`, auto-steal, force removal,
  TTL or stale-owner takeover is used.
- A UUID owner label is verified on the exact returned container ID before each
  cleanup. Occupied and abandoned claims refuse. SIGKILL leaves the claim occupied.
- Helpers have the same run-owner label. If an installer cannot be confirmed
  stopped, or an uncertain failed FE cannot be confirmed stopped, retain the claim
  for deliberate recovery instead of allowing another client to race it.
- `--down` is an explicit teardown (still deletes the selected Doris stack's volumes),
  now subject to the same claim and ownership checks. It is not used for replacement.
- Revalidate rendered compose project/service/container/volume/image and a full
  resolved-configuration digest under the claim and at lifecycle boundaries.
  Wrong existing FE image requires explicit migration, not automatic recreation.

These are cooperating-deployer guarantees, not protection against unrelated manual
Docker writes, force-removal of claims or malicious users of the daemon socket.

### Validate and preserve before stopping

- Resolve the **locally available** `python:3.12-slim` helper to its immutable image
  ID before claiming. All helpers use that ID, `--pull=never` and `--network none`.
  Standard-library ZIP/hash/tar/file operations replace `apk`/unzip shell chaining.
  No opportunistic network/package installation occurs during replacement.
- Retain the frozen selected ZIP and identity JSON in a private, UUID-owned
  `build/smoke-deployments/deploy-*/` evidence directory. Independently verify again,
  prepare/unpack/hash the candidate in the no-volume claim helper before FE stop.
- Snapshot the complete previous plugin volume through a **read-only** mount.
  `previous.tar` and `previous.json` preserve all regular files (including hidden
  receipts and embedded provider manifests), directory paths and modes. Links and
  special files reject. Verify archive hashes/content before proceeding. An absent
  volume gets a verified empty snapshot, not an implicitly mounted/created volume.
- Detect changes during backup and compare the stopped volume to that preserved
  snapshot before deleting any old entry. A changed old installation is a refusal.

### Stopped installation and fresh restart

- Record the prior container ID/image/hostname/mounts/running state/`StartedAt`.
  If available on a running FE, capture only `clusterId` from metadata VERSION;
  never persist its private token or the raw VERSION file.
- Stop the resolved, label-verified FE **by its exact ID**, then revalidate ownership,
  ID and not-running/not-restarting/not-paused state before the first plugin-volume
  write. No BE stop, metadata reset, PostgreSQL or object-store operation is used.
- Preserve existing `.fe.conf.runtime` bytes, including election-network selection.
  Bootstrap the repository default only when no prior FE/runtime config exists.
- The worker validates again before deleting old plugin entries, installs the whole
  set while FE is stopped and checks every expected file/hash, rejecting extras and
  unreadable/nonregular entries. A separate read-only helper verifies it again.
- A copy/unpack/hash/start/freshness/readiness failure keeps FE stopped and retains
  the candidate, rollback archive and lifecycle/error evidence. No automatic rollback
  or attempt to run correctness queries against an uncertain installation occurs.
- Existing FE uses `docker start <same-ID>`, not compose recreation. A missing FE
  uses explicit `compose up ... --no-deps --no-build --pull never doris-fe`. Require
  a fresh, later `State.StartedAt`, the selected immutable image and direct FE-local
  `SHOW FRONTENDS` SQL readiness. Do not wait for BE-dependent `SELECT 1` before
  starting a missing BE. Existing container/mount/hostname identity must remain unchanged;
  a previously observed cluster ID must still match. `RestartCount` is not used.
- The continuation starts BE with `--no-deps` so it cannot implicitly recreate FE.
  Existing engine-source/stock-BE guards remain before lake allocation. The shared
  substrate starts only its PostgreSQL/MinIO services, never shared-lake bootstrap.

## Receipts, recovery and boundaries

`lifecycle.json` is incrementally written, with timestamps and gate/error events,
artifact and SDK identities, helper image ID, prior/new process and target identity.
It explicitly records `loaded_provider_verified=false`. `lifecycle_complete` means
only installation/fresh-process/SQL-readiness passed; the later smoke command can
still fail. No F24-C deployment-attested or F25 correctness-passed claim is emitted.

Evidence survives success and failure. It includes `plugin.zip`,
`plugin.identity.json`, `prepared.json`, `previous.tar`, `previous.json`, and (as
those gates pass) `installed.json`, `verified.json`, `lifecycle.json`. The original
A input copies remain ephemeral; the new per-deployment copies are deliberate
recovery artifacts. Evidence contains no substrate credentials or raw VERSION token.
The lifecycle also records owned helper IDs, tooling-file hashes and the preserved
runtime-config hash. Successful cleanup removes only those exact owned helper IDs.

Recovery is **deliberate**, not automatic: inspect the exact lifecycle target,
owner and helper IDs; verify no original client/installer remains active; preserve
the archive and evidence. Never clear a claim based solely on age. Before restoring,
acquire the same daemon/volume claim and confirm the owned FE stopped. Prefer a
previously preserved ZIP+valid identity JSON through the normal deployment path.
A legacy tar-only rollback requires reviewed offline restoration of the verified
complete archive plus comparison to `previous.json` before a deliberate start;
there is no automatic legacy identity fabrication or tar-only rollback command.
Never use `--down`, delete metadata/storage or sweep lake resources as recovery.

## Verification

The real-Docker transport test uses Linux and the existing local Python
image, a UUID-owned simulated FE process and synthetic same-format plugin fixture:
running/stopped restarts preserve container and metadata; complete install/backup
and exact hashes verify; a separate client refuses an occupied claim; SIGKILL of
an owner leaves a claim that a later client cannot steal. The test explicitly
cleans only its own disposable resources. **No Doris/provider/classloader proof**
is inferred from a simulated SQL executable. Podman/macOS is not available here;
its streaming/no-host-bind path is maintained but not live-qualified by this run.

The focused **16-test** claim/worker/failure run passes, including seven injected
install/hash/changed-old-volume/start/freshness/SQL-readiness/cluster-ID failures.
An earlier expanded run caught a conservative cleanup-order defect: failed helpers
still counted as foreign volume consumers during FE revalidation, unnecessarily
retaining claims. The fix quiesces/removes owned workers before that revalidation;
the regression verifies FE stays stopped, rollback artifacts survive and safe claims
can be released. Unconfirmed helper/FE stop still deliberately retains its claim.

The final combined run passes **64/64 tests, no skips** in 234.363 seconds,
including the opt-in real-Docker test. Shell syntax for both entry point and
continuation, and `git diff --check`, also pass. This includes FE-local readiness
and canonical mount comparison corrections. Readiness follows the existing compose FE healthcheck: waiting
for a BE-dependent query before starting a missing BE could circularly block a
fresh cluster. The driver regression now asserts only `SHOW FRONTENDS` is used
before BE startup; all original post-BE engine/source/query gates remain intact.
The real Docker rerun also exposed unstable ordering of otherwise identical
`Mounts` entries in container inspection. Process records now sort by destination;
the added regression accepts order changes but still rejects changed volume targets.

Build gates pass:
`check java17PluginTest assemble`, Detekt, 7 build-logic tests and 24,630-class
Java-17 runtime scan. Unchanged root suites are correctly up-to-date, retaining
259 passed/1 skip (Java 25) and 228 passed/1 skip (Java 17); they are not fresh live
engine runs. Default provider identity and raw JAR/ZIP hashes remain A's values.

Read-only default-target preflight still safely refuses the retained legacy FE's
different plugin volume. The qualified `doris-api24-fe` container ID/image/start time
and the default runtime-config hash are unchanged across this check. No legacy
migration/adoption/deletion was authorized or performed.

Logs/results: `/tmp/opencode/f24-b-build.log`, `f24-b-legacy-read-only.json`,
`f24-b-claims.log`, `f24-b-python.log`, `f24-b-docker.log`,
`f24-b-failure-countercheck.log`, `f24-b-final-python-v3.log`,
`f24-b-artifact-build-results.json`, `f24-b-closeout-results.json`, `f24-b-docker-results.json` and retained
disposable-test evidence under `/tmp/opencode/f24-b-docker-evidence/`.

Final disposable real-Docker evidence is retained at
`/tmp/opencode/f24-b-docker-evidence/f24-b-5797340bb1fa44f3/` (`running/` and
`stopped/`). The summary records exact synthetic artifact/runtime target identity,
prior/new process starts and the metadata, competing-client and killed-owner results.
The simulated provider build is intentionally **not** the default production bundle
and does not establish F24-E's loaded A/B acceptance. Detailed checklist: **7/13**
complete; only A/B are closed. C–E are not authorized automatically by this result.
