# Metadata connection pool and PgBouncer

Each DuckLake catalog in the FE owns one HikariCP pool to the PostgreSQL metadata database.
The settings below are read when the catalog is created; they are not fleet-wide budgets
and do not retune a live pool.

## Properties

| Property | Default | Accepted |
|---|---|---|
| `catalog.max-connections` | 10 | integer ≥ 1 |
| `catalog.min-idle-connections` | 1 | integer ≥ 0, ≤ max |
| `catalog.connection-idle-timeout` | Hikari's 10 minutes | `0` or ≥ 10 s |
| `catalog.connection-max-lifetime` | Hikari's 30 minutes | `0` or ≥ 30 s |
| `catalog.connection-timeout` | 30 s | `0` or ≥ 250 ms |

Durations are a whole number with `ms`, `s`, `m`, `h` or `d` (`500ms`, `30s`, `10m`). An
absent property keeps the library default. Malformed, overflowing or too-small values are
rejected at catalog creation rather than silently reset by Hikari. `0` disables idle
eviction or lifetime retirement; a `0` acquisition timeout is Hikari's long-wait mode, not
the 30-second default. Setting min-idle equal to max gives a fixed-size pool.

## Behind a transaction-pooling proxy

Catalog library 0.16.0+ sets session isolation correctly behind PgBouncer in
`pool_mode=transaction`. Older versions could land the isolation change on a different
backend and read at READ COMMITTED. Two things still need configuring:

1. **Prepared statements.** Either PgBouncer ≥ 1.21 with `max_prepared_statements > 0`
   (default 200 from 1.22), or `prepareThreshold=0` in `metadata.url` to turn off the
   driver's named server-side statements. Without one of them expect
   `prepared statement "S_n" already exists`, and writes may surface
   `DucklakeCommitOutcomeUnknownException` — do not blindly retry those.
2. **Lifetimes.** Keep `catalog.connection-max-lifetime` below the proxy's
   `server_lifetime` with a margin, and don't disable retirement while relying on that.

The connector passes `metadata.url` through verbatim, including query parameters.

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
    'catalog.connection-max-lifetime' = '25m',    -- proxy server_lifetime is 30m
    'catalog.connection-timeout' = '30s'
);
```

Storage credentials (`s3.*`) are configured as usual.

## Opt-in smoke

`DuckLakePgBouncerTest` runs PostgreSQL + PgBouncer (`pool_mode=transaction`, two server
slots, `max_prepared_statements=0`, `prepareThreshold=0`) on the Java 17 toolchain and
checks the effective Hikari/JDBC settings, snapshot pinning across an intervening writer,
and isolation/read-only defaults on reused backend connections. It is excluded from `test`:

```sh
./gradlew pgBouncerSmokeTest --no-daemon --max-workers=1
```

Pool-setting validation itself is covered by `DuckLakePoolConfigurationTest` in the
ordinary suite.
