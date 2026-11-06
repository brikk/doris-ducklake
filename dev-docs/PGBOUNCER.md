# Metadata pools and PgBouncer

## Status: released catalog 0.16.0

The normal dependency is **0.16.0 from Maven Central**, including the PostgreSQL
`readSession` isolation fix. Its JAR bytes match the completed owner-local trial.
In older catalogs such as 0.15.1, session-level isolation changes can land on a
different backend behind transaction pooling, silently read at READ COMMITTED and
leak settings to other clients; the JDBC prepared-statement workaround alone does
not fix that bug.

The five properties/defaults below retain the configuration work from `de1cfbd`.
The formal adoption verifies hashes and compile/package checks without rerunning
the byte-identical trial's tests or deploying FE/BE. This is not a broader
production/fleet or concurrent-file-rewrite qualification.

## Catalog properties

Each connector instance owns its own pool; these settings are not a fleet-wide
connection budget. They are applied when its catalog/pool is created, not as live
retuning of an existing pool.

Idle eviction only removes connections above the configured minimum; setting
minimum idle equal to maximum size makes the pool fixed-size.

| Property | DucklakeCatalogConfig field | Default | Accepted values |
|---|---|---|---|
| `catalog.max-connections` | `maxCatalogConnections` | 10 | Integer ≥ 1 |
| `catalog.min-idle-connections` | `minIdleCatalogConnections` | 1 | Integer ≥ 0, no larger than max |
| `catalog.connection-idle-timeout` | `catalogConnectionIdleTimeout` | Unset: Hikari 10 minutes | 0 or ≥ 10 seconds |
| `catalog.connection-max-lifetime` | `catalogConnectionMaxLifetime` | Unset: Hikari 30 minutes | 0 or ≥ 30 seconds |
| `catalog.connection-timeout` | `catalogConnectionTimeout` | 30 seconds | 0 or ≥ 250 milliseconds |

Durations are whole numbers with `ms`, `s`, `m`, `h` or `d`, e.g. `500ms`, `30s`,
`10m`. Absent values preserve the library's defaults. Malformed, overflowing or
too-small nonzero durations are rejected before opening a pool, rather than letting
Hikari silently reset them. Zero idle/max-lifetime disables that retirement policy;
zero acquisition timeout uses Hikari's special long-wait convention, not the normal
30-second wait. Prefer finite acquisition timeouts in deployments.

For proxies, keep **`catalog.connection-max-lifetime` below the proxy's
`server_lifetime`**, with a margin, and coordinate server-side limits. Do not disable
pool retirement when relying on this relationship. This tuning does not create a
reader/file lease or solve all concurrent-rewrite safety findings.

The historical pre-0.14 R07 candidate supports only the maximum pool size; the
other four settings explicitly refuse there rather than pretending to take effect.
That separate dependency lane is not upgraded by this work.

## PostgreSQL transaction-pooling requirement

Once using a catalog version with the isolation fix, configure **one** of:

1. **PgBouncer ≥ 1.21 with `max_prepared_statements > 0`.** Its default is 200
   starting with 1.22; explicitly confirm the effective value in your deployment.
2. **`prepareThreshold=0` in `metadata.url`.** This disables the JDBC driver's
   named server-prepared statements and also works with protocol tracking disabled.

With neither, operations can fail loudly with `prepared statement "S_n" already
exists`; writes may surface `DucklakeCommitOutcomeUnknownException`. Do not blindly
retry a write with an unknown commit outcome. `prepareThreshold=0` solves prepared
statement compatibility, **not the old read-session isolation bug by itself**.

The released connector preserves the JDBC URL byte-for-byte, including existing
parameters/percent escapes. It does not strip or automatically insert driver
settings. Use `?` before the first parameter and `&` for subsequent ones.

Example for released **0.16.0**, assuming a proxy
`server_lifetime` of 1,800 seconds (30 minutes):

```sql
CREATE CATALOG dl PROPERTIES (
    'type' = 'ducklake',
    'metadata.url' = 'jdbc:postgresql://proxy:6432/ducklake?prepareThreshold=0&ApplicationName=doris',
    'metadata.user' = '<user>',
    'metadata.password' = '<password>',
    'storage.warehouse' = 's3://<bucket>/<prefix>/',
    'catalog.max-connections' = '4',
    'catalog.min-idle-connections' = '0',
    'catalog.connection-idle-timeout' = '10m',
    'catalog.connection-max-lifetime' = '25m',
    'catalog.connection-timeout' = '30s'
);
```

Storage credentials still need the ordinary catalog configuration; placeholders
above are not real credentials. This example does not enable new DuckLake formats,
experimental reader capabilities or production/fleet qualification. MySQL behind
ProxySQL is outside this PostgreSQL trial.

## Retained opt-in proxy smoke

The owner-local Maven override and `test/pgbouncer-trial` source lane are removed.
The same proxy regression/fixture are retained in the released test source set as
`DuckLakePgBouncerTest` / `PgBouncerFixture`, using the normal Central dependency.
It is opt-in, excluded from ordinary `test` and `java17PluginTest` discovery, so
normal checks do not unexpectedly start a proxy. Configuration unit tests remain
in the ordinary released lane. Hikari's already-bundled runtime version is exposed
as test compile-only content for effective-setting inspection.

```sh
./gradlew pgBouncerSmokeTest \
  --no-daemon --max-workers=1 -Pkotlin.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=512m'
```

The task selects **one** PostgreSQL/PgBouncer smoke on the FE's Java17 runtime. The
smoke uses `pool_mode=transaction`, two tiny server slots,
`max_prepared_statements=0` and `prepareThreshold=0`; it checks effective Hikari/JDBC
settings, snapshot pinning through Doris metadata during an intervening writer,
and isolation/read-only defaults on both reused backend connections. Protocol
prepared-statement tracking is documented from the producer contract, not claimed
as an additional consumer-tested lane.

Keep this serial and light alongside Trino: one worker, private 4 GiB/no-swap/
150% CPU scope, 512 MiB test heap, and a 64 MiB/quarter-CPU proxy. This smoke was
**not rerun for formal adoption** because the owner confirmed identical JARs;
[its historical result](TRIAL-PGBOUNCER-LOCAL-0.16.md) remains separately recorded.
Future execution is explicit and does not imply permission for full suites,
corpus, FE/BE deployment or release work.
