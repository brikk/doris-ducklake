# Catalog 0.17.0 adoption — 2026-11-06

Owner-directed catch-up to formal **0.17.0 from Maven Central**, release commit
`5a1c9bab66f9100e926791e1e6d1cbef8c75e667`, tag `v0.17.0`.
Catalog, test fixtures, replay and SLT use the shared 0.17.0 version pin. The normal
bundle is rebuilt; JDBC 2.0.0.1, Doris API27 SDK/images, corpus pin, stored-format
1.0 admission, existing pool settings and feature flags stay unchanged.

## API / behavior review

- **Optional current bounds are not adopted in planning.** No production caller
  uses `getCurrentColumnBounds`; the existing statistics/pruning paths are kept.
  A future optimization must use an owned `readSession` at the latest snapshot,
  retain `getColumnStats` for time travel and omitted/unknown columns, treat NaN
  bounds as non-pruning, and never derive MIN/MAX answers from gross superset bounds.
- `DuckLakeQualifiedReadCatalog` is an interface extending `DucklakeCatalog`, not
  a concrete non-delegating implementation. It inherits the additive method;
  compilation checks the remaining delegated/test implementations. A small
  released-lane reflection regression checks the inherited signature. The separate
  historical R07 proxy keeps its bounded/read-only dispatch and old dependency;
  unqualified operations already refuse, rather than falling back to live state.
- **Write guard batching:** the library now reads its three guard values in one
  locking query. Consumer quick checks exercise real PostgreSQL commit, multiple
  fragments, partitioned publication, rollback and commit-outcome preservation.
  No consumer write semantics or automatic retry behavior are changed here.
- The library's existing drop-vs-rename safety regressions do not close the
  connector's separate stale destructive-handle/fencing backlog.

## Independently verified published JARs

Downloaded from `https://repo.maven.apache.org/maven2/`; all owner-supplied SHA256
values and published SHA1 sidecars match. The last three JARs keep their 0.16 hashes:

```text
c6ec443055058e8843d42041ab63deb9f58a975e2ba8fe2a083126cee7bb5455 ducklake-catalog-0.17.0.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.17.0-test-fixtures.jar
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay-0.17.0.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.17.0.jar
```

Normal resolved artifacts and the bundled catalog are checked against the same
hashes. The previous 0.16 default ZIP/receipt is preserved under
`build/validation/catalog-0.17.0/previous-default/`.

## Bounded quick checks

Selected on Java25 and Java17, not the full suites:

- `DuckLakeConnectorTransactionTest` — native PostgreSQL commit/rollback/outcome paths.
- `DorisBaselineContractTest` — current API27/wire baseline.
- `DuckLakeConnectorCapabilitiesTest` — unchanged capability admission.
- `DuckLakePoolConfigurationTest` — unchanged defaults, mappings and JDBC URL fidelity.
- `DuckLakeCatalog017ApiTest` — additive inherited API signature; no planning activation.

Main/test compilation, Detekt, assembly, artifact identity, Java17 runtime scanning
and exact dependency recording accompany the quick checks. The three cluster-free
release bundle guards pass. No full suite, corpus, proxy smoke, FE/BE deployment
or Doris release is invoked. Heavy work is serial, one worker, private
4 GiB/no-swap/150% CPU scope, no retained daemon.

**PASS: 23/23 Java25 and 23/23 Java17, zero failures, errors or skips.** Main/test
compilation, Detekt, assembly, package identity and the Java17 runtime scan
(**24,773 classes**) pass. All four normal resolved release artifacts match the
independent Central hashes; the default ZIP contains exactly
`lib/ducklake-catalog-0.17.0.jar` with the supplied catalog hash.

```text
build_id doris-ducklake-v1-42d87117d5c0803014cb4351b6ae2ef09b3d5e9f9714852550efce2314b968f9
plugin_zip_sha256 ea70eef2fb08071393d40e963c61f223748d0fb7261f12bc56140920383ddf10
```

The tested sources stayed unchanged during execution. The private scope is unloaded
and no containers remain running; only disposable PostgreSQL fixtures were started.
Evidence root `build/validation/catalog-0.17.0/` retains `summary.json`, `scope.json`,
`quick-checks.log`, source hashes, verified downloads, per-JVM JUnit XMLs and the
previous default ZIP/receipt. Normal runtime/dependency reports remain under
`build/reports/`. No full suites, corpus, proxy/FE/BE deployment or Doris release
was performed.

This dependency update does not change previous full-corpus results or broaden
read/write, historical-reader, physical-file-lease or production/fleet qualification.
Publishing a Doris release remains the owner's decision.
