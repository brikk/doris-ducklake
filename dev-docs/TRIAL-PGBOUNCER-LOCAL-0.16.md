# Owner-local PgBouncer configuration trial — 2026-11-06

The owner explicitly authorized this **local Maven snapshot exception**:
`dev.brikk.ducklake:*:0.16.0-SNAPSHOT`, producer main
`acec1f1b0208e545efad19d2afd3c574e870170d` (not pushed).
No release, published-coordinate adoption, full suites, corpus or FE/BE deployment
is authorized. Trino trials in parallel; the Doris scope is deliberately light.

## Wiring

Added catalog properties, using the connector's dotted/hyphenated naming:

- `catalog.connection-idle-timeout` → `catalogConnectionIdleTimeout`
- `catalog.connection-max-lifetime` → `catalogConnectionMaxLifetime`
- `catalog.connection-timeout` → `catalogConnectionTimeout`
- `catalog.max-connections` → `maxCatalogConnections`
- `catalog.min-idle-connections` → `minIdleCatalogConnections`

The last two were assumed exposed in the consumer note, but source inspection found
neither wired in this checkout; they are included rather than claiming existing
configuration worked. All defaults are unchanged: max 10, min idle 1, idle/lifetime
unset (Hikari defaults), acquisition timeout 30 seconds. Explicit malformed values
and values Hikari would silently reset are refused before constructing a pool.

The actual connector construction now shares a pure configuration-binding method
with the unit tests. The complete `metadata.url` is unchanged, including
`prepareThreshold=0`, other driver parameters and percent escapes. The historical
pre-0.14 candidate's unavailable pool APIs remain isolated and explicitly refuse
unsupported knobs.

[Deployment documentation](PGBOUNCER.md) states the prepared-statement requirement:
PgBouncer ≥ 1.21 with `max_prepared_statements > 0` (default 200 since 1.22), **or**
JDBC `prepareThreshold=0`. It also advises max lifetime below the proxy's
`server_lifetime`, and explicitly warns that this JDBC option **does not fix the
old read-session isolation bug on the still-normal 0.15.1 dependency**.

## Local provenance and normal-pin protection

Only the trial init script registers `~/.m2` for the three catalog-family modules.
It requires the exact local version, checks all four resolved JAR hashes on the
compile/runtime/test configurations, guards included builds, prevents mixing R07,
and confines output to `build/pgbouncer-local-trial/`.

```text
4b9659ef1100a8350b1d09b353e65448e26cdab2cc85c85b6f5d800848c6e234 ducklake-catalog-0.16.0-SNAPSHOT.jar
ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f ducklake-catalog-0.16.0-SNAPSHOT-test-fixtures.jar
3b3ddd334e9eb9cd971062cc2b19b1b2d7ee535538c46f005b5db01277300c11 ducklake-test-corpus-replay-0.16.0-SNAPSHOT.jar
ee37d13d863437eaaf36fe4c34f8d66cfa1cb6d1349ef661ae4f50d2e91120be slt-format-0.16.0-SNAPSHOT.jar
```

The normal version catalog remains **0.15.1**; its hash and the default ZIP/full
identity receipt are protected before/after. The owner-local JARs are also rehashed
after the run to detect a concurrent reinstall. The trial adds Hikari's existing
runtime version to its **test compile-only** classpath for effective-setting
inspection; this is not a new production dependency or local JAR substitution.

## Bounded checks

- Java25: only `DuckLakePoolConfigurationTest` (defaults, all five mappings, partial
  overrides, zero/boundaries, URL fidelity, invalid values/overflow and required keys).
- Java17: the same units plus one real PostgreSQL/PgBouncer smoke. The smoke checks
  actual Hikari values and the driver's `prepareThreshold`, metadata construction,
  snapshot pinning during a direct writer commit, and read-committed/read-write
  defaults on **both** reused proxy backend connections.
- The proxy is a digest-pinned PgBouncer 1.23.1, transaction mode, two server slots,
  `max_prepared_statements=0`, JDBC `prepareThreshold=0`, 64 MiB/quarter CPU. This
  consumer does not duplicate the producer's full proxy/maintenance matrix or claim
  a separately tested protocol-tracking lane.
- One Gradle worker, no retained daemon, private 4 GiB/no-swap/150% CPU scope,
  512 MiB test heap. No assembly or default distribution replacement.
- A preliminary compile failed because Hikari is runtime-only in the catalog's
  public metadata; **no tests ran** in that attempt. The trial-only compile classpath
  correction is recorded separately, without changing assertions or normal pins.

**PASS: Java25 8/8 units; Java17 9/9 (the same eight units plus the proxy smoke),
zero failures, errors or skips.** Compilation and Detekt pass. The real smoke emits:

```text
PGBOUNCER_LOCAL_TRIAL pool-settings=effective prepareThreshold=0 snapshot=pinned both-server-defaults=read-committed/off
```

The scope/source receipts confirm unchanged tested sources, all four owner-local
JARs, the normal 0.15.1 version catalog and the default ZIP/full identity receipt.
Both private scopes are unloaded. The owned labeled proxy is independently verified
removed, and the fixture closes its owned PostgreSQL server; no other session's
services or daemon were stopped. No normal suites, corpus, FE/BE deployment,
assembly, release, default promotion or push was performed.

`summary.json`, `scope.json`, local input hashes, frozen source fingerprints,
`targeted.log` and separate JUnit XML copies live under
`build/validation/pgbouncer-local-trial/`; actual resolved local component IDs,
paths and hashes are under `build/pgbouncer-local-trial/reports/`. The compile-only
first attempt is retained separately, not counted as test execution.

## Stop condition

When this wiring/trial finishes, commit locally, report the properties and counts,
and **WAIT**. Do not push, release, promote the local snapshot or automatically
switch normal dependencies. The owner will provide the final Central 0.16.0,
including further changes, for a later adoption decision.
