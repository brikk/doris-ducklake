# R07 controlled capture and decoded defaults — October 24

Subsequent SQL-paged capture and corrected history-kind qualification is recorded
in [the paging continuation](VALIDATION-R07-PAGING-2026-10-25.md). Hashes and
counts below describe this earlier immutable checkpoint.

## Scope

Continuation of `3228a80092bba5757bca391817e73e65f7090c7d`, qualification only.
Controlled-capture unit: `7535fcf6db5f1407f5706d85108380a06c697906`.
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

Resolved SHA-256 manifest (also checked at test/assembly time):

```text
catalog   d579fe355abc03f658b74dad7e991aca9cb4e54f1b4830c6af51ba930beb4ead
fixtures  ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f
replay    c8f847d4ba8ca8509a8b3a47c709c1e86b34e50c2c4e3c824b79e1256cba85a4
SLT       6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409
JDBC2     5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186
default12 e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729
```

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

The first cold-namespace FE probe reproduced the existing engine diagnostic
masking: `Database [main] does not exist`, although the production adapter retained
the actual PostgreSQL cancellation cause. That initial failed assertion is kept in
`/tmp/opencode/doris-r07/control-evidence-initial/`; it is not recast as proof that
FE always exposes the driver cause. Initialized-namespace SQL is checked separately.

Pool acquisition, setup/prepare, network partitions, arbitrary callback work,
other drivers and leases are not certified by this change. Whole-second JDBC
timeouts can round past the remaining millisecond budget; late output still
rejects. `cancel()` itself may block/fail, and a request-sent result alone does
not prove server interruption.

## Decoded missing-field defaults

Only an absent **stable field ID in an inspected native Parquet footer** invokes
`getMissingFileColumnDefaultDecoded(tableId, columnId, targetSnapshot)`, inside the
same controlled statement capture. A present field, including stored NULL, never
invokes it. Nested types remain refused, so a NULL parent is not synthesized into
a non-NULL container. Target identity/type/history validation belongs to the public
catalog API, not a connector re-parser of `initialDefault`.

Decoded values are converted to canonical SQL via the real API-13
`ConnectorColumn.withDefaultValueSql` contract. Only generated numeric/Boolean
forms, quoted/escaped strings, byte-derived hex and finite date/time literals enter
that SQL. No catalog expression text is executed. Defaults are cached once per
target table/field/frame after proven absence and published immutably. Their
encoded bytes share the existing 4-MiB path-text budget; reloaded schema/history
work is charged to the existing total work budget. Missing paged APIs remain
missing; this does not turn materialization into lazy/streaming planning.

The pinned FE's read scan additionally gates default parsing on a non-NULL
`ConnectorColumn.defaultValue`. For this tier that marker carries the same
generated literal as `defaultValueSql`, not raw source metadata; omitting it
would silently project NULL despite setting the SQL override.

Non-NULL defaults supported here: signed int8/16/32/64, Boolean, DECIMAL precision
up to 38, VARCHAR (4096 characters), BLOB (4096 bytes), DATE (years 1..9999),
TIMESTAMP/TIMESTAMP_S/TIMESTAMP_MS with exact target precision. SQL NULL is distinct
from literal VARCHAR `NULL`. Floating-point **missing-field defaults** remain
unqualified even though stored FLOAT/DOUBLE values remain supported. Nanoseconds,
zones, infinities/large-year dates, nested/degraded types and non-NULL containers
are not added. The physical file type allowlist itself does not expand.

Comparison evidence uses both immutable catalog JARs on Java 17 against the same
retained native PostgreSQL fixture: old `58ef4d7` exposes raw records but no public
decoded-file-default method; new `0058e8d` yields Long `42` after widening, literal
String `NULL`, SQL null, bytes `005CFF`, LocalDate, LocalDateTime and exact
BigDecimal. Logs: `defaults-{old,new}-candidate-api.log`. Separately the pre-adoption
FE adapter (new catalog bytes, old default code) actually refuses the evolved
fixture with `initial default for varchar is not qualified`; its intentional red
baseline is retained as `defaults-old-adapter-comparison.{log,xml}`. This distinguishes
an API comparison from falsely claiming a full old-candidate FE campaign.

After the update, actual FE/BE SQL replays **that same retained physical fixture**
successfully (`control-defaults-same-fixture.{sql,log}`): old-file replacement ID
reads `42` rather than dropped value `888`; the new file's later insert default
reads `77`; its explicitly stored NULL row remains NULL. BLOB bytes `005CFF`,
DECIMAL `12345678901234567.125`, date `2024-02-29`, timestamp
`1969-12-31 23:59:59.123456`, literal injection-looking string and backslash/UTF-8
bytes `5C783431C3A9` survive unchanged. Numeric historical snapshot 16 reads the
earlier column name `k` and default `42`. JDBC live regressions separately
distinguish SQL NULL from string `NULL` (the mysql text output spells both NULL).

The first expression-history negative test incorrectly expected rejection merely
from setting `default_value_type='expression'` while retaining literal initial
value `42`. The public contract intentionally allows a validated literal initial
value alongside an INSERT expression (native VARCHAR `NULL` uses this shape).
That failed assertion is retained in `defaults-expression-initial.xml`. The final
regression explicitly tests that safe case, then removes the literal initial
value and requires the real `non-literal initial-default history` refusal. It
restores the owned fixture and checks a fresh successful query. This is a corrected
negative fixture, not an expression evaluator, new skip or corpus golden change.

A later complete live rerun passed the new control/default cases but exhausted
PostgreSQL's private fixture client limit while creating the final test database:
`FATAL: sorry, too many clients already`. Repeated runs had accumulated retained
FE catalog pools. Evidence is retained in `defaults-live-fixture-initial.xml` and
`control-defaults-all-final.log`; it is not counted as a green full live run.
Stopping only the owned qualification FE released those clients (PostgreSQL then
showed only its background processes plus the diagnostic connection). No catalog
or object fixture was dropped, no PostgreSQL connection was killed to qualify
cancellation, and no pool/server capacity limit was raised. The final live run
starts that same private FE afresh. Cancellation/deadline recovery occurs **inside
each test before any infrastructure restart**. Aggregate retained-catalog/fleet
connection capacity is not certified by the single-capture bounds.

The final fresh-FE run passes **13 live tests, zero failures/errors/skips**.
Actual locked production capture stops before fixture unlock at **40 ms** for
explicit cancellation (measured from capture start, not a universal cancel-latency
bound) and **3,011 ms** for the three-second JDBC deadline. Both retain PostgreSQL
`PSQLException` SQLSTATE `57014`; the one-connection pool subsequently captures the
original head. The production adapter memoizes the same original timeout failure;
a new statement recovers. Initialized-namespace FE SQL reports the actual cancelled
metadata query before unlock, then scans the exact original rows. The cold namespace
masking limitation above remains real.

The live scalar case also executes missing TIMESTAMP_S and TIMESTAMP_MS defaults
and independently expects `.000000` and `.123000`, alongside stored microsecond
values; no time precision is truncated. Existing Parquet/Puffin/same-container
slices, field IDs, embedded IDs, multiple row groups, bounded history, unsupported
inline/types and genuine body COUNT regressions all execute again. Private network
outages return meaningful PSQLException / SocketTimeoutException after about
10.015 / 10.025 seconds, with fresh-query recovery. These network observations are
not guarantees from the new control API.

At the end of this single fresh-FE suite, private PostgreSQL (`max_connections=100`)
has 52 ordinary idle JDBC connections for retained catalog pools and **zero idle
transactions**. This reinforces the explicit aggregate pool-capacity limitation,
not a physical retention lease or a fleet resource certification.

## Remaining boundaries

Broad R07 remains **NO-GO**: mandatory BE data/delete snapshot predicates,
approved distributed inline channel, engine CDC surface, SQL-paged/lazy metadata
APIs, retention leases and route/fleet qualification are still owner work.
No connector SQL bypass or unbounded prefetch replaces missing catalog APIs.
Native R02=3/R03=1/R04=1/M01=6+4 and the strict long-partition producer failure
remain unwaived. Existing exclusions/disabled tests are not expanded.

Concrete owner handoff:

- Doris: expose a supported in-flight planning cancellation registration, distinct
  from statement-end close, and preserve original namespace initialization failures.
- Doris BE + matched-peer contract: mandatory DATA and deletion-position snapshot
  predicates before accepting mixed historical physical files.
- Architecture/engine owners: approve a real distributed query payload channel (or
  separately designed staging lifecycle) for inline data, and choose a CDC surface.
- Catalog coordinator/consumer: separately adopt and requalify the SQL-paged APIs
  being implemented in later immutable candidates. This trial stays on `0058e8d`;
  it does not bypass the library with connector SQL or claim those newer APIs tested.
- Operators: immutable-object retention/file leases and route/fleet qualification
  remain prerequisites for any rollout. No cleanup or mixed-peer deployment is
  authorized by these reads.

## Reproduction and evidence locations

Final verification (the Java-25 and Java-17 maintained lanes overlap):

| Check | Passed | Failures/errors | Existing skips |
|---|---:|---:|---:|
| Candidate maintained, Java 25 | 262 | 0 | 1 |
| Candidate plugin, Java 17 | 236 | 0 | 1 |
| Released 0.12.0 maintained, Java 25 | 253 | 0 | 1 |
| Released 0.12.0 plugin, Java 17 | 227 | 0 | 1 |
| Targeted native/refusal regressions, Java 17 | 14 | 0 | 0 |
| Final clean live FE/BE/SPI suite, Java 17 | 13 | 0 | 0 |
| Strict long-partition producer | 0 | **1 failure**, 0 errors | 0 |

All 18 maintained Python smoke units and shell syntax checks pass. No new
exclusion, JUnit disable, assumption, lint suppression or baseline change was
introduced. The existing legacy inline-delete skip remains visible; the strict
native failure is separate and red. R02/R03/R04/M01 were not rerun or waived here.

Candidate Java-17 runtime/classfile check: **24,723 classes**, 44 runtime JARs plus
main output, applicable maximum **61**. All four attested runtime/fixture JARs
independently scan at max61. Production lint passes without a baseline rewrite.
The candidate's frozen catalog ABI/wire checks are upstream-provided evidence in
its immutable README, not a newly rerun catalog campaign here. Actual plugin SPI
linkage and defaults are exercised on matching FE/BE `dd456ab7a89`, API13, FE
Java17.0.2. Parquet host1.18.0 vs compile/test1.17.0 remains unchanged.

Tested candidate connector JAR SHA-256:
`492bbf77632122889863f11458a7954c3f3a32add87c4f476a1eacae75856d9c`;
candidate ZIP `081d8c01c73864065b1ce795b1df8b33838c0f5e6fe58445a0e1de8ecb56adb4`.
Staged bytes are verified against that ZIP; the FE loaded these same connector /
catalog hashes with plugin `failureCount=0`. The candidate ZIP is retained in
`control-defaults-candidate-evidence/` before restoring the released assembly.

The final default build is restored with **no override**: catalog/fixtures/replay/
SLT0.12.0, JDBC1.5.6.0, no0.13.0 artifacts in either resolved graph. Default ABI
scan: **24,630 classes**, same maximum61 and 44 runtime JARs plus main output.
Both default maintained lanes, lint and assembly pass. The ZIP's catalog bytes
are compared byte-for-byte to the released cached JAR and its attested SHA above;
the main JAR contains no candidate plan/default/budget classes. API13 manifest
remains intact. Default connector SHA
`d3a8299e8c65ada6101e74d8074646d5a5e00f89e1a044c7cdb7fdcb384b5c59`;
restored ZIP SHA
`832270f2cf95e79e1464c72cdd6f8d66fc092264dff4fa1af656f5e0ac20fc45`.

Default fixture/replay/SLT bytes are also rechecked against the prior release
report: `b91aa5154c67dc35daecacdb0971bb3b6cc155e4b5c217547c09c6ba8f9e3859`,
`6dc1a7b48cda90ea6a5b05fb7559458440a87ff4b968cd18a3247ead28a8e35e`,
and `6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409`.
Released-lane JDBC1.5.6.0 SHA is
`04ae3375d7c40dc88dc9bf0014b82ba5e66715564584af4e3abea39c7ba144d3`.

Only the four owned qualification services are stopped at completion; their
containers, PostgreSQL catalogs and S3 fixtures (including failing producers)
are retained. No shared service, Trino process, lake cleanup or global daemon
shutdown was used. The build output left behind is the released default assembly,
not the candidate plugin loaded into the now-stopped private FE.

With only `compose/r07-qualification.yml`'s isolated retained services running:

```sh
export GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle
candidate=/tmp/opencode/catalog-r07-0058e8d/maven-repository
mise exec -- ./gradlew check java17PluginTest r07ReadQualificationTest pluginZip \
  qualificationDependencies r07LiveQualificationTest \
  -Pr07CatalogRepository="$candidate" --continue --no-daemon --no-configuration-cache
# Separate strict producer gate, still expected to fail; never merged into reader pass totals.
mise exec -- ./gradlew r07LongPartitionNativeTest -Pr07CatalogRepository="$candidate" \
  --no-daemon --no-configuration-cache
# Released lane: NO candidate override. Restore its assembly last.
mise exec -- ./gradlew check java17PluginTest pluginZip qualificationDependencies \
  --rerun-tasks --continue --no-daemon --no-configuration-cache
```

Logs and XML remain under `/tmp/opencode/doris-r07/`, notably
`control-defaults-all-final.log`, `control-defaults-candidate-evidence/`,
`control-defaults-live-clean.log` (the final 13/13 live pass),
`control-defaults-live-final.log` (retains the earlier negative-fixture assertion
and the separate strict producer failure),
the old/new comparison logs above, the four-JAR ABI scan, assembly hashes and
container provenance. FE and BE have no shared warehouse mount. Infrastructure
operations address only this compose project's own named containers; the Trino
stack and shared daemon registries are not stopped.
Released verification is `control-defaults-released-final.log` and
`control-defaults-default-evidence/`; byte checks are
`control-defaults-default-assembly.sha256` and
`control-defaults-release-family.sha256`.
