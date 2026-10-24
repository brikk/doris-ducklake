# R07 Doris implementation on the fixed candidate — October 24

## Scope and decision boundary

This continues `a700166`, rather than treating its NO-GO/refusal report as an
implementation. The trial adds **default-off PostgreSQL + S3 native-file reads**
on the exact catalog candidate, with an optional bounded recent-snapshot window.
It is not a release, deployment, blanket 1.1 certification or completion of R07.
All connector 1.1 writes, DDL, maintenance and cleanup remain refused.

The source/extension/corpus pins did not move. The coordinating upstream review
at catalog `eab57f9` was read; new source `947c8496` is **not** installed in place
of signed DuckLake `417a9117f5`. Native R02=3/R03=1/R04=1/M01=6+4 failures remain
unwaived. MySQL's separately qualified library scalar read tier is not given a
1.0-only ceiling by this Doris PostgreSQL-only scope.

## Build and opt-in

The default build still resolves released catalog/fixtures/replay/SLT **0.12.0**
and test JDBC **1.5.6.0**. Only the explicit repository override selects the
checksum-attested **0.13.0-SNAPSHOT** / JDBC **2.0.0.0** lane:

```sh
GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle mise exec -- ./gradlew \
  pluginZip verifyR07Candidate \
  -Pr07CatalogRepository=/tmp/opencode/catalog-r07-58ef4d7/maven-repository \
  --no-daemon --no-configuration-cache
```

Gradle compiles the library-specific adapter from `read-tiers/r07` only in that
lane; the normal build compiles `read-tiers/released`, which explicitly refuses
this development feature. There is no reflective access to catalog internals,
Maven-local substitution, source composite build or new dependency version.
The existing exclusive FE `org.apache.doris:1.2-SNAPSHOT` Maven-local repository
is unchanged; global `maven.repo.local` is never set. Packaging the trial now
depends on checksum verification too, not just running its tests.

The catalog JAR SHA-256 stays
`5af1a80adceb751a1ea616831953734a09d14d85ce632192b31f9c31b051bc84`;
fixtures `ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f`;
replay `c8f847d4ba8ca8509a8b3a47c709c1e86b34e50c2c4e3c824b79e1256cba85a4`;
SLT `6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409`;
JDBC `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186`.
The source artifact is catalog `58ef4d700024a010bfee793aaf92f6ff239e9e5c`.

| Route | This continuation's evidence |
|---|---|
| PostgreSQL/S3 1.1 | Actual opted-in FE/BE current and bounded-history SQL, wire, schema, count and recovery probes |
| PostgreSQL/S3 1.0 | Actual unchanged legacy SQL lane plus maintained Java-25/Java-17 regressions |
| Local DuckDB 1.1 | Original library/fixture regression probes only; not a public Doris FE route |
| Native/direct-Java Quack | Not enabled or requalified; tested initializers and pins unchanged |
| Existing MySQL 1.1 JDBC | Library scalar tier remains independent; not this Doris scope; no fabricated nullable profile facts |
| Other storage schemes | Not newly enabled or certified |

In a catalog using that build:

```sql
"experimental.final11.reads" = "true",
-- optional: 1..16 recent numeric snapshot coordinates, captured in the SAME session
"final11.history-window" = "16"
```

Without the flag, all previous 1.1 admission/scan guards remain. With the flag,
the declared scope is an existing, unencrypted, root-schema PostgreSQL catalog
with S3 credentials and valid immutable native field-ID Parquet objects. Other
metadata/storage routes, add_files/name mappings, nested/degraded types and live
inline data/deletes reject rather than being adapted into apparent support.
History defaults to **0 (current only)**; requests outside a configured window,
expired/absent snapshot coordinates, and timestamp/named/incremental forms do
not get silently replaced by latest metadata.

## Statement/session ownership and budgets

`DuckLakeFinal11Plan` captures the **complete bounded catalog plan** in one real
`JdbcDucklakeCatalog.readSession`: snapshot records, qualified schema/table
identity, columns/defaults, file/delete associations, original retained ownership,
inline liveness, partition specs and physical footer/vector facts. All requested
recent history frames are built inside that same callback/transaction.

Only a completely materialized view is published. FE metadata, eager retained
schema publication, schema dictionary construction and range planning share it
through the actual `ConnectorStatementScope`; scan planning requires a real scope.
There is no held transaction across FE threads, per-query actor/executor bridge,
per-callback SQL view drift, or physical connection hidden in the returned plan.
The SQL transaction is rolled back/released before BE execution; the immutable
view lives only in the engine-owned statement scope. Unknown/mutating adapter
operations throw. Failed capture is memoized as the **original failure**, not an
empty or partial catalog; later callbacks in that statement cannot retry with a
fresh budget or different view. A fresh statement can recover/replan.

Materialization is deliberately bounded, not advertised as streaming. Limits can
be lowered via `final11.max-planning-<name>`, never raised past these ceilings:

| Name | Ceiling |
|---|---:|
| `schemas` / `tables` | 64 / 128 |
| `pages` / `paths` | 128 / 4096 (total DATA + DELETE retained references) |
| `work` | 100,000 caller operations/metadata/physical validation work units |
| `bytes` | 64 MiB total physical footer/vector transfer |
| `millis` | 30,000, checked between operations |

Companion pages use exact progressing cursors and 128-item pages, with global
page/path/path-byte/traversal/work budgets shared across tables and history frames.
Parquet footer payloads are at most 1 MiB, groups at most 4096; Puffin objects at
most 16 MiB, footer at most 1 MiB, blobs at most 1024. Range reads enforce ETags,
exact ranges/lengths and total transferred bytes. No payload or temporary file is
written. HTTP redirects/retries are not silently followed; HTTP and JDBC timeouts
are bounded, engine URL/HTTP security and authentication hooks are honored.

The legacy inline-info method is materialized and internally probes its versions.
This adapter admits schema versions only up to **256** and reserves conservative
schema-key-bounded work before invoking it. It does not pretend that method is
paged. Large catalogs need bounded inline-info/table/spec APIs or a different
public catalog read-view API before widening this ceiling. The wall-clock budget
is checked between public operations; it cannot preempt arbitrary internal SQL
work. Per-RPC timeouts do not certify instantaneous cancellation of every internal
query. These are real limits, not waived production-scale qualification.

## Physical scans, identity and deletes

- Native physical field IDs, actual primitive/logical types, finite temporal
  domains, initial numeric/Boolean defaults and physical gross counts are
  checked. Signed integer and FLOAT→DOUBLE widening stay exact; nanoseconds,
  implicit zones, composite/VARIANT/geometry/interval/UUID/string-degraded types,
  unqualified defaults and incompatible physical evolution reject. No type map
  is weakened to make a test pass.
- Embedded absolute row IDs are preserved; NULL contiguous starts never become
  zero. Position deletes still address **physical positions**, not those absolute
  IDs. No public CDC/row-lineage surface is inferred from a successful SELECT.
- DATA snapshot tags are inspected independent of `partial_max`. If physical
  rows extend past the selected snapshot, the table/frame refuses before ranges;
  no absent per-row BE predicate is fabricated.
- Tagged Parquet delete positions apply only when all physical tags qualify for
  the selected snapshot. When future positions require filtering, the historical
  table/frame rejects even with NULL `partial_max`. Current scans use the real
  BE position-delete reader, without rewriting OPTIONAL columns to REQUIRED.
- Bare deletion-vector-v1 blobs and PFA1 containers are validated, and cumulative
  blobs are selected by `ducklake-snapshot-id`. Real content offset/length fields
  are serialized to the existing BE decoder. Equal current/previous paths never
  self-cancel; a historical target selects another byte slice of the same object.
  Bad magic, lengths, CRC, footer flags, codecs, duplicate tags and overlapping
  slices reject. No invented decoder capability or CDC bitmap differencing claim.
- All 1.1 metadata COUNT/LIMIT trimming, raw-bound pruning and planner row-count
  estimates remain declined. Historical/unknown live-count refusal is handled by
  actual scans/absent estimates, never gross/zero metadata guesses.

Checked JDBC/HTTP exceptions are normalized once to a connector exception with
the original cause before crossing the Java proxy boundary; otherwise JDK wraps
them in a message-less `UndeclaredThrowableException`. Runtime budget and
cancellation exception identities are preserved. SQL outage diagnostics now
identify the PSQLException / SocketTimeoutException instead of an empty detail.

The first positive SQL attempt exposed unset Thrift optional bits: assigning Java's
public `content` field from Kotlin bypassed `setContent`. BE saw no DV discriminator
and returned undeleted rows. Explicit setters and an independent serialized-wire
round trip fix it. The failing SQL/profile evidence is retained separately;
format recognition alone was not treated as a working decoder.

FE actually runs Parquet **1.18.0**, versus compile/test **1.17.0**. Unlike the
previous refusal-only report, successful opted-in SQL now exercises the host FE's
physical footer reader as well as the BE scanner. The old fat-fe-thrift shadowing
audit remains valid; no FE inline writer or shared-filesystem assumption is used.

## Upstream review / real engine blockers

- **No unsafe transformed-value pruning:** the 1.1 tier declines all predicate
  pruning and reads native partition columns from their bodies. Legacy Doris
  bucket pruning is not Trino's TIMESTAMPTZ calendar matcher; no UTC/calendar
  matcher was added. Format 1.1 is not used as UTC provenance. TIMESTAMPTZ itself
  is not in this new lossless type scope.
- Native paths remain opaque original-owner references. Body-absent add_files
  Hive mappings remain distinguished by mapping metadata and are rejected in
  this new scope, not parsed as native hashed/identity values. Existing legacy
  Hive behavior was not globally disabled.
- The signed writer + this MinIO cannot produce the long escaped partition
  object from the new gate. `r07LongPartitionNativeTest` preserves the original
  long input as a **strict failing producer gate**, separate from valid-file
  reader acceptance. This does not prove all S3 implementations share the limit.
  No next-source hash-name behavior was smuggled into the signed binary.
- **Historical mixed DATA / tagged Parquet filtering:** in pinned source
  `gensrc/thrift/PlanNodes.thrift`, `TIcebergFileDesc.file_select_conjunct` is
  deprecated and has no BE consumer. `TIcebergDeleteFileDesc` has no snapshot
  predicate. `getMustReadColumns` preserves declared slots but cannot install a
  mandatory per-range filter for every scan. A real data/deletion snapshot hook
  and matched-peer capability contract are needed to widen this boundary.
- **Inline:** API-13 has engine-owned filesystem reads/writes, but not a generic
  query inline-row payload channel. `TFileRangeDesc.load_id` is for stream/routine
  ingestion, not registering query payloads. Creating S3 scratch objects plus GC
  would be an architectural write/lifecycle decision requiring owner approval;
  no shared-FS/temp-Parquet or invented JNI/RPC reader was introduced.
- **CDC:** there is no implemented DuckLake change-feed/assembler in this engine
  path. Incremental kinds remain declined. Library candidates and successful
  historical SELECTs are not update pairing/transient-row suppression evidence.
- **Leases and rollout:** an immutable plan/URI is not a file lease. Published
  paths must remain immutable and retained for active queries. There is no
  acquired physical retention lease here and no cleanup authorization. Exact
  matching FE/BE `dd456ab7a89` is the tested fleet; API-major/unknown-field
  tolerance is not mixed-version capability negotiation. Other routes/fleets
  and production rollout still require separate approval/qualification.

## Admission diagnostic

`preCreateValidation` is a real existing SPI hook. Admission/layout/budget/storage
validation runs there so a failed CREATE surfaces the original refusal before
`ExternalCatalog.buildDbForInit` can mask it as a missing database. Failed
admission closes connector resources without replacing the original exception.
This fixes admission inside the connector, not by patching FE/vendor source.
FE's generic runtime namespace-failure masking remains an upstream diagnostic
limitation, not proof of a successful or empty unsupported scan.

## Evidence and final result

The isolated harness remains `compose/r07-qualification.yml`, with no shared
FE/BE warehouse mount, Trino service or shared-daemon stop. Native producers alone
create disposable catalogs/files; connector SQL only reads. Targeted native
fixtures and maintained checks are used, not a full corpus campaign or repin.
Detailed final commands, counts, positive SQL, retained fixtures and commit are
recorded with the final verification evidence under `/tmp/opencode/doris-r07/`.

### Positive SQL / negative boundaries actually executed

On matching FE/BE **doris-0.0.0-dd456ab7a89**, API **13.0**, FE Java **17.0.2**,
with no shared warehouse mount:

| Case | Independently expected SQL result |
|---|---|
| Native 1.1 renamed/defaulted table | `(1,NULL,005CFF,2024-02-29,42)`, `(2,'NULL',5C783431,NULL,42)`, `(3,'three',NULL,2000-01-01,42)` |
| Historical schema before rename/add | Same original three rows, using the old column name `s` and no invented new field |
| Parquet delete, then bare Puffin | Rows `1,3`, then `1`; COUNT returns `1` by scan |
| Inline-flush tagged Parquet | Current rows `1,3,5`; older window requiring position-tag filtering rejects with NULL `partial_max` |
| Inline-flush cumulative Puffin | Current rows `1,3,5`; earlier deletion snapshot rows `1,3,4,5`, COUNT `4` |
| Same physical Puffin path | Actual serialized descriptors select **4+42** vs **46+44** byte slices, discriminator present/3; actual BE queries return the different states |
| Same-statement join of deletion shapes | `1,3,5`, without independently rereading metadata per scan |
| Current versus historical self-join | Same table/path at two snapshot coordinates in one statement; anti-join returns the later-deleted row `4` |
| Merge across an inline-ID gap | NULL `rowIdStart`, embedded absolute IDs; after a physical-position DV, rows `1,2,3,5,7`, COUNT `5` |
| That file before the DV | Historical rows `1,2,3,5,6,7`; an earlier target containing future DATA rows rejects |
| Multiple physical row groups | 300,000 rows, MIN `0`, MAX `299999`, SUM `44999850000`; observed native row-group count >1 |
| Native identity partitions | Escaped UTF-8/slash/percent/question-mark values and SQL NULL read from body, not decoded/replaced from directory spelling |
| Scalars | Boolean predicates/defaults; int8/int16 endpoints and signed int64 min/max; FLOAT/DOUBLE; DECIMAL/default; microsecond/second/millisecond timestamps |
| Exact widening / replacement IDs | int32→int64; stored FLOAT bits widen to `1.2345677614212036`; same-named replacement field reads default `42`, not dropped value `888` |
| Unsupported types/inline | Nanoseconds reject instead of truncation; mixed 5-file/2-inline rows reject even with old dev flag, including COUNT |
| Unknown delete count | Owned negative fixture sets `delete_count=NULL`; actual SQL scans return rows `1,3` and COUNT `2`, not gross/zero/guessed metadata |

These are actual native producers and connector SQL checks, not rewritten corpus
goldens or native-oracle rows substituted for BE results. The named owned negative
fixture changes only delete-count metadata, not the catalog format or positive
inline storage DDL. The source corpus remains `c23aca43...`, different from the
catalog's `417a9117...`; no source/corpus change or corpus campaign was performed.

### Session, cancellation and network evidence

The real production adapter is also exercised with a live PostgreSQL/MinIO and
an authored SPI scope. The test deliberately collides the engine metadata key's
ConcurrentHashMap bin with the plan key: deferred capture avoids recursive
compute during metadata construction. One complete plan is reused across
metadata/range calls; external native publication does not change it, and a new
statement sees the new head. A one-page global budget abort is cached as the same
original exception, never retried as a partial plan. PostgreSQL reports **zero
idle-in-transaction** connections after success/failure.

Only the private R07 PostgreSQL/MinIO containers were paused for fault tests.
Cold planning SQL fails at approximately **30.010s / 10.086s**, respectively
(the final PostgreSQL case reaches Hikari's bounded acquisition timeout, while
the MinIO case reaches the HTTP read timeout). Both retain meaningful causes;
fresh statements scan the exact original rows after unpause. An opted-in
S3-backed query is cancelled at SLEEP using JDBC timeout; the next scan succeeds.
No new test assumes a session/path is a file lease. Interrupted storage transfers,
all cancellation timings, external snapshot expiry and physical-file lease
acquisition/recovery are **not** thereby certified. No cleanup/expiry was run.

### Checks / ownership handoff

The candidate's complete maintained Java-25 and Java-17 plugin lanes, the original
14 native/refusal regression probes, new format/budget tests, ABI and lint are
run separately from **9 live tests (zero failures/errors/skips)**. The new strict
long-partition producer gate is **1 failure / 0 errors / 0 skips**, kept visible
and not counted as a reader pass. Exact maintained/default-lane totals and final
commit are returned with the final session result.

| Final check | Passed | Failures/errors | Existing skips |
|---|---:|---:|---:|
| Candidate maintained + six new format/budget/diagnostic cases, Java 25 | 259 | 0 | 1 |
| Candidate plugin lane, Java 17 | 233 | 0 | 1 |
| Original targeted native/refusal regressions, Java 17 | 14 | 0 | 0 |
| Live SQL/SPI/publication/recovery checks, Java 17 | 9 | 0 | 0 |
| Released 0.12.0 default maintained lane, Java 25 | 253 | 0 | 1 |
| Released default plugin lane, Java 17 | 227 | 0 | 1 |
| Strict long-partition native producer gate | 0 | **1 failure**, 0 errors | 0 |

The maintained lanes overlap; these are not additive unique-test totals. The one
old disabled legacy inline-delete test is unchanged and visible in both lanes.
No new assumption, disable or exclusion hides a failing reader check. The named
strict native producer gate is explicitly executed separately and remains red.
Detekt now covers the active production read-tier sources too, with no baseline
rewrite or new production lint suppression. Assembly and runtime ABI checks pass:
**24,720 candidate / 24,630 default classes**, 44 runtime JARs plus main output,
applicable classfile maximum **61**. Compiler toolchain stays **25**. The complete
plugin lane executes at **17**, while existing corpus-tooling units retain their
Java-25 launcher. All **18** maintained Python smoke-harness unit tests and shell
syntax checks pass. No full corpus campaign was run.

Primary new logs: `implementation-candidate-final.log`,
`implementation-default-final.log`, `implementation-live.log` and
`implementation-evidence/` (XML, SQL/wire/recovery output, ABI/dependency graph and
live provenance). The initial unset-content-bit failure and BE profile are kept
as `implementation-live-unset-content-initial.*` / `vector-profile.*`.
Native long-path failed fixtures are retained, not repaired or used as green data.

Final tested connector JAR SHA-256:
`ae43a787eedb18cfed1a0c3f205f4e0b9a44176a76b6caa5db7fddb9c6a8395b`.
The running FE's catalog hash still matches `5af1a80a...`; plugin loading reports
`failureCount=0`. The private services were stopped after the final run, with
their containers/catalogs/object data retained. Default assembly was restored
without the override, including the released catalog JAR hash `e76e0b16...`.

Reproduction (with the separately documented isolated services running):

```sh
export GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle
candidate=/tmp/opencode/catalog-r07-58ef4d7/maven-repository
mise exec -- ./gradlew check java17PluginTest r07ReadQualificationTest pluginZip qualificationDependencies \
  -Pr07CatalogRepository="$candidate" --rerun-tasks --continue --no-daemon --no-configuration-cache
mise exec -- ./gradlew r07LiveQualificationTest \
  -Pr07CatalogRepository="$candidate" --no-daemon --no-configuration-cache
# Strict producer gate: expected to remain visibly failed, not part of reader green counts.
mise exec -- ./gradlew r07LongPartitionNativeTest \
  -Pr07CatalogRepository="$candidate" --no-daemon --no-configuration-cache
# Independently verify the released dependency/default lane, with NO candidate override.
mise exec -- ./gradlew check java17PluginTest pluginZip qualificationDependencies \
  --rerun-tasks --continue --no-daemon --no-configuration-cache
```

Concrete remaining owner choices: approve a real query payload/read channel or a
separately designed staging lifecycle before inline work; supply supported BE
mandatory data/delete snapshot predicates for mixed historical files; choose an
engine CDC surface before implementing an assembler; extend public bounded
catalog/view APIs before production-scale lazy planning; separately qualify any
future signed extension upgrade for path hashing/Quack/row-group changes; and
coordinate immutable-object retention/leases and matched-peer deployment. No
source patch, artifact repin, schema golden rewrite, publication, push, production
deployment or default-on change is inferred from this guarded subset.
