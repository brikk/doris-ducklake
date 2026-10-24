# R07 controlled capture and decoded defaults — October 24

## Scope

Continuation of `3228a80092bba5757bca391817e73e65f7090c7d`, qualification only.
The default-off PostgreSQL/S3 native-file tier remains bounded and read-only;
released 0.12.0 is still the default dependency lane. No push, publication,
deployment, migration, catalog/Trino source edit, native/corpus repin or cleanup.

The explicit exclusive candidate repository is now
`/tmp/opencode/catalog-r07-0058e8d/maven-repository`, source
`0058e8d574924128738596c72239fc8e5ef67878`. Catalog SHA-256:
`d579fe355abc03f658b74dad7e991aca9cb4e54f1b4830c6af51ba930beb4ead`.
The fixtures/replay/SLT/JDBC hashes in the preceding implementation report are
unchanged. The immutable earlier bundles remain untouched. FE `org.apache.doris`
still resolves exclusively from its scoped Maven-local repository; no global
`maven.repo.local` override.

## Controlled capture

The real statement-wide capture now uses one `DucklakeReadSessionControl` for
the entire read transaction, including history and physical validation callbacks.
Its JDBC timeout is the existing millisecond planning budget rounded up to whole
seconds (maximum 30); each executing library query receives its remaining timeout.
The original total work/bytes/pages/paths/millisecond budgets and original-failure
memoization remain. There is no per-query retry, connection kill or helper thread
in production. The internal capture seam accepts a stricter single-use control
for the explicit cancellation qualification, never one extending the budget.

Pinned API-13 `ConnectorSession` and `ConnectorStatementScope` expose no in-flight
cancellation registration. `ConnectorStatementScopeImpl.closeAll` runs **after**
off-thread pumps quiesce, so it is not misrepresented as a cancellation hook.
Production metadata SQL therefore gets deadlines, not a fabricated FE-cancel
callback. Cooperative thread interruption checks remain as before.

Pool acquisition, setup/prepare, network partitions, arbitrary callback work,
other drivers and leases are not certified by this change. Whole-second JDBC
timeouts can round past the remaining millisecond budget; late output still
rejects. `cancel()` itself may block/fail, and a request-sent result alone does
not prove server interruption.

## Remaining boundaries

Broad R07 remains **NO-GO**: mandatory BE data/delete snapshot predicates,
approved distributed inline channel, engine CDC surface, SQL-paged/lazy metadata
APIs, retention leases and route/fleet qualification are still owner work.
No connector SQL bypass or unbounded prefetch replaces missing catalog APIs.
Native R02=3/R03=1/R04=1/M01=6+4 and the strict long-partition producer failure
remain unwaived. Existing exclusions/disabled tests are not expanded.
