# F24 — safe plugin replacement and loaded-build identity

## Status and scope

**F24-A/B implemented and verified (F24-01–07). F24-C through E remain open.**
Audited 2026-10-29 against connector `9b44463` and pristine Doris
`62e3d81a42b2b74ee2a5a577969df62edee06b78` (connector API 24 / JNI 6).
F22 is handled separately. F25's SQL/result assertions are not silently included here.

This work needs plugin packaging and smoke-driver changes, **not another FE/BE
build, catalog release, generic hot-reload SPI or a dependency upgrade**. Keep
catalog 0.12.0, the validated runtime and existing feature gates fixed. Historical
R07 and Duckbridge stacks are not deployment targets for this task.

Goal: before any smoke fixture is allocated, prove that the selected FE process
started after installation and actually admitted the expected DuckLake provider
build from the verified plugin bundle. Same engine SHA, API major, artifact file
name or row count is not sufficient.

[F24-A implementation and evidence](VALIDATION-F24-A-2026-10-29.md) covers the
content-identified bundle and read-only target preflight. It does **not** claim
loaded-provider acceptance: no live bundle was replaced or FE process restarted.

[F24-B implementation and evidence](VALIDATION-F24-B-2026-10-29.md) covers the
daemon-scoped claim, complete rollback snapshot, confirmed stopped installation
and fresh-process boundary. Its 64-test run includes disposable real-Docker
transport/lifecycle checks with a simulated FE, not real Doris/provider loading.
The retained Doris stacks remain unchanged; the legacy-target mismatch still refuses.

## Exact audit

### Baseline driver and packaging (audit at `9b44463`, before F24-A)

| Evidence | Behavior at audit baseline | Consequence |
|---|---|---|
| `compose/smoke.sh:77-94` | Chooses newest matching ZIP by mtime, then replaces files in the named plugin volume. There is no preceding FE stop or deployment lock. | Selection is ambiguous with multiple ZIPs; an existing process may still hold the old JARs while the files are replaced. |
| `compose/smoke.sh:90-94` | Helper uses `apk ... && rm ...; cd ... && unzip ...`; no post-install exact file-set/hash verification. | A successful container command alone is not an installation identity receipt; failure paths need explicit boundaries. |
| `compose/smoke.sh:108-164` | Plain `compose up -d`, SQL readiness and exact FE/BE **engine-source** checks. | Compose need not restart an unchanged running FE. The source guard does not identify the out-of-tree plugin. |
| `compose/smoke.sh:202-211` | `--up-only` returns before the generated run/lake allocation. | Plugin attestation belongs before this return as well as before the full-run UUID/database/S3 allocation. |
| `compose/docker-compose.yml:14-41,110-120` | Single named FE container, persistent metadata, source-qualified plugin volume; the volume name is also duplicated in `smoke.sh`. | Resolve and verify the actual selected project/container/volume, not an independently guessed name; preserve metadata and storage. |
| `build.gradle.kts:330-366` | Provider JAR stamps only `Doris-Connector-Plugin-Api-Version=24.0`; project version is still `0.0.1`. | No unique release/dirty-build identity is currently available through the loaded-provider version field. |
| `compose/test_smoke_driver.py:33-51,76-107` | Fake Docker records commands and returns canned SQL; it does not model a provider remaining loaded across installations. | Current tests prove routing/source guards, not the absence of stale loaded code. |

Line anchors describe the audited revision; follow the named methods if later edits move them.

### What API 24 actually provides

Paths below are relative to the frozen upstream worktree
`/home/jayson/DEV/OSS/doris-ducklake-api24-62e3d81a`:

- `fe/fe-core/.../catalog/Env.java:2203-2213`, `initConnectorPluginManager`:
  creates the manager, loads built-ins and directory providers, then publishes it
  through `ConnectorFactory`. This is FE initialization, not a directory watcher.
- `fe/fe-extension-loader/.../DirectoryPluginRuntimeManager.java:71-78`:
  runtime reload/unload and directory watching are explicit non-goals.
  `loadAll:138-153` retains handles; duplicates do not replace a loaded name.
  `loadFromPluginDir:278-283,346-357` checks API compatibility and snapshots
  `Implementation-Version` from the factory-defining JAR at load time.
  `ManifestVersions.java:56-70` gives a package-specific manifest section priority
  over the main `Implementation-Version`; preflight must check the effective
  value for `dev/brikk/ducklake/doris/plugin/`, not only the main header.
- `fe/fe-core/.../connector/ConnectorPluginManager.java:289-331`:
  load failures allow partial startup. Only an admitted provider is registered
  in the extension inventory. A healthy FE or a generic load-summary line is not
  proof that the intended DuckLake provider was admitted.
- `fe/fe-extension-loader/.../PluginRegistry.java:64-106,184-187`:
  records immutable load-time name/type/version/source metadata. The first
  registration wins. The snapshot is **not** reread from a newly replaced JAR
  when a query lists extensions.
- `fe/fe-core/.../tablefunction/MetadataGenerator.java:723-745`:
  exposes the current FE's registry as `information_schema.extensions`, without
  calling plugin code. It exports five columns, **not** the internal load time or
  a raw JAR hash.

The read-only check against our retained API-24 FE returned:

```sql
SELECT EXTENSION_NAME, EXTENSION_TYPE, EXTENSION_VERSION, SOURCE, DESCRIPTION
FROM information_schema.extensions
WHERE EXTENSION_NAME = 'ducklake';
```

```text
EXTENSION_NAME  EXTENSION_TYPE  EXTENSION_VERSION  SOURCE    DESCRIPTION
ducklake        CONNECTOR       NULL               EXTERNAL
```

`DESC information_schema.extensions` confirms exactly those five columns.
Do not write gates using nonexistent `NAME`, `VERSION` or `LOAD_TIME` columns.
This audit did not change the live plugin, restart a service or run an A/B deployment.
Raw read-only output: `/tmp/opencode/f24-api24-inventory.tsv`.

### Decision

Use the existing **load-time `EXTENSION_VERSION`** surface, populated by a unique
provider-JAR `Implementation-Version`, together with a stopped-writer installation
boundary, installed-file hashes and proof of a fresh FE process.

Do **not** invent hot reload or use `REFRESH CATALOG`/DROP+CREATE as a provider reload:
those operations do not replace the manager's loaded factory/classloader. Do not
use an endpoint that simply hashes the *current file* beneath an old classloader;
that could attest new disk bytes while old classes still execute.

The build identifier must correlate to the complete expected bundle. A JAR cannot
embed its own final raw SHA-256 without a self-reference; hash canonical payload
inputs, keep the identity out of its own inputs, and separately record the final
JAR/ZIP hashes. This is accidental-staleness protection, not signed supply-chain
authentication or a guarantee against arbitrary non-cooperating filesystem writes.

## Implementation TODO — 7/13 complete

Implement in order; each item has an observable exit criterion. No item below is
completed merely because it is feasible or because the earlier manual re-vendor
qualification passed.

The same work is indexed in Project Todos **F24-A** (01–02), **F24-B** (03–07),
**F24-C** (08–10), **F24-D** (11), **F24-E** (12–13). This file is the detailed
sub-item/acceptance checklist; the Project Todos are the coarse backlog groups.

- [x] **F24-01 — Freeze one explicit artifact and one deployment target.**
  - Replace newest-mtime selection with an explicit ZIP input or a fail-closed
    single-candidate rule; fail missing/ambiguous input before service mutation.
  - Resolve the actual compose project, FE container and named plugin volume from
    rendered compose configuration; check existing target ownership/labels.
  - Test multiple ZIPs, missing ZIP, overridden image and wrong-project/container
    collisions. No foreign container may be stopped or volume overwritten.

- [x] **F24-02 — Give each effective plugin build a load-visible content identity.**
  - Produce a deterministic canonical payload fingerprint from compiled main
    classes/resources, API contract and packaged runtime dependency hashes; record
    compile-SDK identity separately. Account for dirty builds and same-Git-HEAD changes.
  - Stamp it in provider-JAR `Implementation-Version`; keep API major 24, project
    release version, Java-17 floor and host-JAR exclusions unchanged.
    Reject a conflicting provider-package section or stamp the same effective value there.
  - Avoid a task/input/self-hash cycle. Record final provider JAR and ZIP hashes.
    Parse manifest continuation lines correctly; a full content identity need not
    fit on one physical manifest line, so first-line grep is not an identity parser.
  - Tests: same inputs yield the same fingerprint; changed class/resource/dependency
    bytes change it; two builds can share API/release version but not the identity.
    Include the package-section precedence case from the actual upstream loader.

- [x] **F24-03 — Acquire exclusive cooperating-deployer ownership.**
  - Key the lock to the actual Docker daemon/plugin-volume target, not just a host
    checkout path. A uniquely named Docker helper/claim can provide a cross-client
    atomic acquisition without assuming host `flock` exists on macOS.
  - Retain an owner token and release only the current invocation's claim in traps.
    Refuse an occupied/stale claim; do not auto-kill another run or steal its lock.
  - Hold through install, startup, attestation and smoke execution. Document that
    unrelated manual Docker writes are outside the cooperating-lock guarantee.
  - Test two overlapping invocations and interrupted-owner behavior.

- [x] **F24-04 — Validate and preserve the replacement before stopping FE.**
  - Reuse A's frozen, verified local bundle; add the target backup/rollback and
    stopped-writer lifecycle below rather than creating a second identity format.
  - Stage the selected bundle under run ownership; verify layout, the single
    provider, manifest/API/build identity and complete expected file hashes.
  - Remove no active files during preflight. Preserve the previous complete plugin
    set and manifest for deliberate rollback; do not replace a good installation
    with a partially unpacked candidate.
  - Test corrupt/unreadable archive and manifest failures before target writes.

- [x] **F24-05 — Establish a confirmed stopped-FE boundary.**
  - Stop only the resolved/owned FE service before the first plugin-volume write.
    Confirm its process is no longer running; abort if stop/ownership verification fails.
  - Never delete FE metadata, BE storage, PostgreSQL databases or S3 objects to reload
    the plugin. BE restart is not a substitute for FE provider reload.
  - Record prior Docker `State.StartedAt` and FE identity when available; do not rely
    on `RestartCount` changing for an explicit stop/start.

- [x] **F24-06 — Install and verify the complete bundle while FE is stopped.**
  - Use fail-fast helper control flow, not a semicolon-masked setup failure.
    Choose an unpacking path that does not require an opportunistic `apk` download
    during replacement; test it on the supported Docker/Podman lane.
  - Remove stale plugin dependencies only within the resolved owned plugin volume,
    install the staged complete set, then compare every expected file/hash and reject extras.
  - On failure, retain evidence and keep FE stopped or perform an explicit verified
    rollback. Never continue to correctness checks with an uncertain installation.

- [x] **F24-07 — Start a new FE process without resetting its metadata.**
  - Explicitly start/recreate the owned FE after installation; `up -d` is acceptable
    only after a proven stop, not as evidence that a running process reloaded.
  - Confirm fresh `State.StartedAt` and readiness; preserve elected address/name,
    cluster ID and priority-network configuration. Keep runtime images pinned.
  - Test stopped/absent/already-running FE states and restart timeout.

- [ ] **F24-08 — Attest the admitted provider through the existing SQL inventory.**
  - Require exactly one DuckLake `CONNECTOR` row, `SOURCE='EXTERNAL'`, and a non-null
    `EXTENSION_VERSION` exactly matching the selected bundle's expected build identity.
  - Query the intended FE directly. Missing, stale, null, duplicate/conflicting or
    built-in identity must fail; general SQL health and FE version equality cannot override it.
  - Test old loaded build A after disk replacement with B, even when both stamp API 24.
    Do not read a version out of the live disk file and call that loaded-code attestation.

- [ ] **F24-09 — Put all identity gates before allocation and `--up-only` success.**
  - Run installed-file, fresh-process, provider-identity and existing exact FE/BE
    source checks before the UUID/database/S3/lake allocation boundary.
  - `--up-only` must satisfy the same provider gate. The explicit stock-BE experiment
    is not permission to skip FE plugin provenance; keep its qualification label separate.
  - Every failure path must show zero lake allocations/mutations in the command recorder.

- [ ] **F24-10 — Write a run-owned deployment/qualification receipt.**
  - Record selected ZIP/JAR/file-set hashes, content identity, SDK identity, runtime
    image IDs/source versions, target project/container/volume, new process start,
    SQL inventory result and gate outcomes. Do not include credentials.
  - Separate deployment-attested from smoke-correctness-passed; F25 still governs
    whether SQL result checks are complete. Do not publish a passing receipt on error.
  - Retain old/new receipts and artifacts without silently relabelling old live evidence.

- [ ] **F24-11 — Add stateful command-orchestration regressions.**
  - Extend `compose/test_smoke_driver.py` to retain running/loaded state and injected
    failures, not always return the desired plugin version.
  - Assert lock → stopped FE → volume writes → verified install → fresh start →
    loaded-build gate → allocation ordering. Cover all preflight/stop/unpack/hash/start/
    inventory failures, stale same-API builds, wrong targets and two concurrent invocations.
  - Preserve current 19 routing/guard tests. Canned Docker responses remain orchestration
    evidence only, never a live provider/classloader proof.

- [ ] **F24-12 — Run one bounded real two-build acceptance on owned infrastructure.**
  - Prepare distinguishable provider bundles A and B with the same API/release
    version; verify that the new path loads B and records B, not merely its disk files.
  - Retain metadata through replacement/restart, run `--up-only` and a bounded exact
    read check, then an injected stale-identity refusal before fixture allocation.
  - Use the existing FE/BE images, unchanged catalog dependency and isolated owned
    volumes/network. Do not touch Duckbridge or the historical R07 stack.
  - Record exact A/B/runtime hashes and results; do not launch full corpus replay.

- [ ] **F24-13 — Close out docs and compatibility boundaries.**
  - Update smoke usage, override/lock/rollback instructions, the F24 review entry
    and evidence links. Remove obsolete claims that a mounted-volume update plus
    `up -d` proves a plugin reload.
  - Re-run shell syntax, Python orchestration, plugin packaging/Java-17 checks and
    the bounded acceptance. Keep FE/SPI/native pins and capability opt-outs fixed.
  - Mark F24 complete only with implementation **and** real loaded-B acceptance;
    keep F25 and broader corpus/candidate acceptance as separate work.
