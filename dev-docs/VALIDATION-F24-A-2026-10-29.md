# F24-A — artifact and target identity

## Scope and status

**Implemented and verified:** F24-A completes **F24-01–02 only**: explicit artifact selection/freezing, read-only
compose-target ownership checks and a content-addressed provider build identity.
Functional reproducibility, JVM/build and final Python checks pass. Changes remain
uncommitted; no commit/push or deployment is performed by this task.

F22's existing uncommitted repair is preserved. This task does not implement
F24-B's lock/stop/backup/install/start protocol, F24-C's loaded-provider gate,
F24-D's full deployment state machine, F24-E's live two-build acceptance or F25's
SQL assertion work. No live plugin was installed, no FE/BE was restarted/rebuilt,
and no dependency/SPI/native pin or feature gate changed.

## Packaging contract

`build-logic/.../PluginIdentity.kt` supplies typed Gradle tasks:

1. `pluginIdentity` depends on main classes/resources, the exact packaged runtime
   JAR set and the compile SDK files. It writes `build/plugin-identity/payload.json`
   outside all JAR/resource input roots.
2. The provider JAR stamps `Implementation-Version=doris-ducklake-v1-<SHA-256>`;
   `Doris-Connector-Plugin-Api-Version` stays **24.0**, project version **0.0.1**,
   and Java bytecode floor **17**. Jar and ZIP timestamps/order are reproducible.
3. `pluginArtifactManifest` verifies the *actual packaged* provider payload and
   effective manifest, then the exact ZIP file set and hashes. It writes the
   adjacent `build/distributions/doris-ducklake-0.0.1-plugin.identity.json` receipt.
   Both `assemble` and `check` require this gate; `check` also runs build-logic tests.

The fingerprint's canonical UTF-8 input is:

```text
doris-plugin-payload-v1\n
API\t24.0\n
P\t<provider entry path>\t<entry SHA-256>\n
... provider entries sorted by unsigned UTF-8 path bytes ...
D\tlib/<runtime JAR name>\t<raw JAR SHA-256>\n
... runtime JARs in the same path ordering ...
```

All provider files except its own manifest are included. Control/delimiter paths,
noncanonical paths, duplicates and unsupported manifest attributes/sections reject.
The API header is explicit. The producer and preflight honor the upstream
provider-package `Implementation-Version` override and reject a conflicting value.
Continuation lines are parsed rather than grepped.

There is no Git/mtime/file-location input and no self-hash cycle. The final raw
provider-JAR/ZIP hashes are recorded **after** packaging, separately from the
canonical payload identity. Compile-SDK JAR hashes and a separate SDK digest are
recorded without bundling host JARs or making them payload-dependency inputs.
One shared exclusion set drives both fingerprinting and ZIP assembly; the previous
SPI/Thrift/logging exclusions remain in force.

This identifies effective artifact bytes, not source-review quality, authenticated
publisher identity or loaded code. It is not a signed supply-chain attestation.

## Artifact selection and freezing

`compose/plugin_preflight.py` uses Python standard-library code only (Python 3.9+).

- With no explicit input it requires **exactly one** matching distribution ZIP;
  newest-mtime guessing is removed. `--plugin-zip PATH` selects an already-built
  ZIP and adjacent `.identity.json`, including paths containing spaces.
- It copies those two files to a fresh private directory under
  `build/smoke-artifacts/artifact-*`, then independently checks ZIP/provider/runtime
  bytes, service-provider identity, effective manifest, canonical payload ID and
  SDK metadata consistency. Copying from the selected source after another build
  replaces that path cannot silently change the deployment's frozen bytes.
- A missing/ambiguous/corrupt/mismatched artifact fails before any Docker query,
  infrastructure start, live-config write or plugin-volume mutation.
- The Bash driver uses only the frozen ZIP. Its exit trap removes only that
  invocation's two local copied files and private directory. Original artifacts
  and other runs' directories are not deleted.

The SDK information is build-produced metadata checked for internal consistency;
the plugin archive cannot independently prove which SDK a compiler previously used.

## Target resolution and ownership boundary

Read-only `docker compose ... config --format json`, container/volume listing and
inspection resolve the actual selected project, explicit single FE container,
FE image and named plugin volume. There is no prefix-based adoption:

- Existing FE project/service labels and its plugin mount must match the rendered
  target. Wrong project/service or a different mount is a refusal, not a migration.
- Existing volume ownership labels must agree. A legacy unlabelled volume is
  accepted only when an already-owned FE proves the exact mount; an unlabelled
  orphan cannot be adopted. Foreign volume consumers reject.
- The normal driver's FE commands and volume creation now use these resolved
  values; newly created plugin volumes receive the matching compose labels.
- Missing/new target resources are described, not created during preflight.
  Arbitrary unnamed/multi-FE/bind-mounted plugin topologies are not silently guessed.

This is a **snapshot of target ownership**, not a lock. F24-B must still serialize
cooperating writers and revalidate before replacement. The existing normal
replacement path is not declared safe for a running FE by this A-only work.

`--preflight-only` implies no build/deployment, returns artifact/target JSON and
does not stage live config. Its result explicitly says
`scope=artifact-and-target-only`, `loaded_provider_verified=false`. Private copies
are ephemeral and removed when the wrapper exits; this is not F24-C/10's durable
loaded-provider or successful-smoke receipt. The standalone Python helper hands
ownership of a successful local staging directory to its caller.

The explicit `--down` teardown remains outside plugin-artifact selection; it does
not require a ZIP and cannot be combined with artifact/preflight-only options.

## Checks completed so far

- Seven build-logic unit tests: canonical cross-language vector/order, same-input
  determinism, compiled/resource/runtime/API mutations, SDK identity, UTF-8 ordering,
  invalid/self-reference paths and folded/package-specific/case-insensitive manifest parsing.
- **44** Python checks pass: the previous 19 smoke/lake cases, 15 artifact/target
  cases and ten new driver cases. They cover explicit/missing/ambiguous ZIPs,
  missing receipts/corruption, image overrides, foreign ownership, exact dynamic
  FE routing, preflight-only nonmutation and frozen copies surviving a selected-path
  replacement. Tiny synthetic fixtures and canned Docker are orchestration evidence,
  not JVM/classloader or SQL-correctness evidence.
- The actual newly built provider ZIP passes the independent Python verifier,
  not merely the fixture builder or producer's own assertions.

### Read-only real-target checks

The default compose target has a retained legacy FE container mounting a different
plugin volume. `smoke.sh --preflight-only` correctly refuses it **before** live
config or Docker mutation. This task does not delete/adopt that container; an
explicit later migration/target decision is required.

The existing owned `doris-api24-qualification` target passes the standalone helper's
artifact/target preflight using the new local bundle. The FE container ID, image
and `State.StartedAt` are unchanged across the audit, as is the default live-config
hash. No SQL load claim is made; the running provider was not replaced.

Evidence: `/tmp/opencode/f24-a-default-preflight.log`,
`f24-a-read-only-results.json`, `f24-a-build.log`, `f24-a-python.log` and Gradle XML.

## Functional packaging proof

Five isolated real Gradle packaging builds use external test-only source/resource/
file-dependency inputs and separate output roots under `build/f24-a-functional/`.
Default production source directories and dependency coordinates are not edited.
Git HEAD remains `9b44463` throughout; all variants retain API 24 / release 0.0.1 and
the same compile-SDK identity.

| Variant | Change | Payload digest suffix |
|---|---|---|
| A | Initial compiled class/resource/dependency probe | `9383949c57b18c27459d6cb86f7aa9cb99a1bd5119cf01d15e65a9cfe24dc318` |
| A-repeat | Same inputs, different output directory | identical to A, including raw JAR/ZIP hashes and entire receipt |
| B-class | Java-17 compiled class constant A → B | `93f837f702f64c1e3e870cf98f5cc9dd3aed68f7702626d122419a0456beb7d6` |
| C-resource | Resource bytes A → B | `0c7a8179fd580fed4889bb50826611f1adfb210ec22f95c81478b9df2538aa36` |
| D-dependency | Packaged file-dependency JAR bytes A → B | `3456dccb493c0972678e942a7843201fd47cfdf743eb9ea97b01a36d2db1e3a9` |

All builds and identity assertions pass. These are packaging tests only; no probe
bundle is deployed and they do not satisfy F24-E's real loaded A/B acceptance.
Runner and results: `/tmp/opencode/f24-a-functional.py`,
`f24-a-functional.init.gradle`, `f24-a-functional.log` and
`f24-a-functional-results.json`, with a separate log per build.

## Final JVM/build checks and artifact

`check java17PluginTest assemble` passes with the normal released-catalog lane:

- Build-logic **7/7** tests, zero skips/failures.
- Java-25 normal suite retains **259 passed + 1 existing skip** (260 total).
- Java-17 plugin suite retains **228 passed + 1 existing skip** (229 total).
- Detekt and the **24,630-class** Java-17 runtime floor gate pass.

The root Java suites are correctly up-to-date for unchanged class/dependency bytes;
their earlier validated results are reused, not falsely described as a new live run.
The new default provider has **60 non-manifest payload entries byte-identical to
the preserved baseline JAR**. Probe-only classes/resources do not leak into it.
New build tooling lives under `dev.brikk.buildlogic` so source files are not hidden
by the repository's generic `build/` ignore rule.

| Field | Value |
|---|---|
| Default build identity | `doris-ducklake-v1-00dca0beef3e3c6e60521c127a701dfe58097616c0cf92eabe0a00fefc83479d` |
| Provider JAR SHA-256 | `621c9160599713482ae9e171efa61798e987a2754801faea6c5615afac5d7595` |
| ZIP SHA-256 | `e0cf35bc092bfcf19a39086fd6473b6da4d71f13b3c58b9f4b7677411216a1f5` |
| Compile-SDK identity | `5348ee7f19b2782abded11eff86a9b7e9ce27710ed26ae9679ac448c6c3ea831` (five SDK JARs) |

The final normal bundle passes the independent Python verifier. Raw results and
logs: `/tmp/opencode/f24-a-final-results.json`, `f24-a-final-check.log` and normal
Gradle/build-logic XML. Final **44/44** Python, shell-syntax and whitespace checks
pass (`/tmp/opencode/f24-a-final-python.log`). No new live qualification is claimed.
