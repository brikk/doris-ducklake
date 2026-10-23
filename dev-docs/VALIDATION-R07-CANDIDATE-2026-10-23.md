# Doris R07 consumer qualification — local catalog 58ef4d7 trial

## Decision and authorization boundary

**NO-GO for Doris final-1.1 reads / R07 completion.** This is an executed local
qualification with conservative connector fixes, not an implementation plan or
production/default-on approval. Passing catalog tests do not certify Doris.
The released **0.12.0** catalog remains the default. There is deliberately **no
public Doris 1.1 opt-in** after this work. Native fixture creation is external to
the read-only connector. No catalog source/artifacts, corpus pins, goldens,
remote publications, releases, migrations or lake cleanup were changed.

The useful positive result is candidate dependency/Java-17 compatibility and
actual matched-peer **legacy 1.0** S3 scans. The negative result is that Doris
cannot yet meet mixed inline/file, historical tagged scans, Puffin, CDC or
statement-wide bounded-planning requirements. These are rollout blockers, not
waivers. R07 as a whole remains open; this does not certify the concurrent Trino work.

## Reversible dependency lane and bytes

Source candidate: clean `/home/jayson/DEV/brikk/ducklake-catalog` commit
`58ef4d700024a010bfee793aaf92f6ff239e9e5c`, unchanged by this session.
Only `-Pr07CatalogRepository=/tmp/opencode/catalog-r07-58ef4d7/maven-repository`
selects the trial. Omitting it returns to released catalog/fixtures/replay/SLT
**0.12.0** and test DuckDB JDBC **1.5.6.0**.

The trial selects catalog/fixtures/replay/SLT **0.13.0-SNAPSHOT** and JDBC
**2.0.0.0**. An exclusive repository filter covers exactly those three modules
at that version; it cannot fall back to Maven-local or remote snapshots.
Gradle module metadata remains enabled and the published test-fixtures variant
resolves normally. `verifyR07Candidate` hashes the **resolved files**, not their
version labels. All candidate `SHA256SUMS` entries were independently verified.
The pre-existing exclusive `mavenLocal` scope for `org.apache.doris:1.2-SNAPSHOT`
is unchanged; global `maven.repo.local` was not set.
An offline negative control using an absent exclusive repository fails resolution
even after the real candidate has been cached; searched locations contain only
that absent repository, not alternate SNAPSHOT sources.

| Resolved JAR | SHA-256 |
|---|---|
| catalog | `5af1a80adceb751a1ea616831953734a09d14d85ce632192b31f9c31b051bc84` |
| catalog test fixtures | `ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f` |
| corpus replay | `c8f847d4ba8ca8509a8b3a47c709c1e86b34e50c2c4e3c824b79e1256cba85a4` |
| SLT format | `6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409` |
| DuckDB JDBC 2.0.0.0 | `5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186` |

Native fixture checks assert core **v2.0.0 / d54cbbe3df** and signed DuckLake
**417a9117f5**. The live producer observes PostgreSQL scanner **c0f5a3c0c4**
and HTTPFS **77392a7207**. Quack **f96420ed46**, Java Quack **0.8.0** and MySQL
scanner **f7fc0d4271** were not exercised by this Doris qualification.
The test-only MySQL protocol driver stays **9.5.0** (Doris FE transport), not
catalog's **9.7.0** MySQL-metadata qualification pin. It does not certify that route.
PostgreSQL JDBC remains **42.7.8**, brikk-sql remains test-only **0.18.1**.
Compile/test Parquet remains **1.17.0**; the actual API-13 FE provides **1.18.0**.
This difference was observed, not silently upgraded or treated as equal coverage.
`qualificationDependencies` records both resolved graphs. Their complete diff
changes **only** catalog/fixtures/replay/SLT 0.12.0→0.13.0-SNAPSHOT and test JDBC
1.5.6.0→2.0.0.0; no other selected runtime or test-runtime coordinates change.
The exact graphs/diff are retained under `/tmp/opencode/doris-r07/`.

Consumer corpus checkout is **c23aca43b6033acfac2b8cc8d80b15ae7fa729b2**, not
the candidate's **417a9117f590e8141742ac0579d02f6a90162e37**. It was not repinned;
**no corpus campaign** (full or per-directory) was launched. Maintained connector
tests plus targeted native fixtures were used instead. No semantic goldens changed.

## Changed connector contracts

1. Reader construction explicitly refuses observed final-1.1 before exposing
   metadata, write planning or maintenance. Previously, a default-off library
   configuration could still resolve a 1.1 table handle; that was not safe admission.
   Failed initialization closes its pool, and retrying write/procedure acquisition
   still refuses. This is a connector refusal, not a new public 1.1 configuration.
2. `planScan` and `getScanNodeProperties` each own a catalog `readSession`;
   nested use reuses a caller's complete session. Query failures propagate instead
   of becoming an empty range list. This **does not** span the whole FE statement's
   independent metadata/schema/filter/range callbacks.
3. For a deliberately injected opt-in catalog, historical final-1.1 file plans
   reject regardless of nullable `partial_max`; all final-1.1 delete-file plans
   reject before ranges, including bare and cumulative Puffin and tagged Parquet.
   Equal current/previous paths are not interpreted as empty changes. Current
   DATA plans also refuse, including COUNT requests: even a long-lived connector
   cannot silently enter the tier after an external format change.
4. Final-1.1 live inline plans reject even with `experimental.inlined.reads=true`.
   No distributed payload transport or shared-filesystem workaround was invented.
   The existing legacy dev-only path is not broadened.
5. Final-1.1 planner statistics are absent, filter pruning is declined, and exact
   metadata COUNT/LIMIT trimming is disabled. Historical/unknown-delete count
   refusal is never replaced by gross counts, zero or an exact aggregate.
6. Under the explicit DuckDB-2 trial, legacy native test fixtures request **1.0 /
   automatic migration false**, so the new peer does not accidentally turn the
   regression lane into 1.1 fixtures. The old 1.5.6 peer does not recognize
   `DUCKLAKE_VERSION`; its original 1.0 bootstrap SQL is preserved, not upgraded
   to make the trial pass. Production type mappings are unchanged;
   existing timestamp precision/type degradations are not 1.1 qualification.

No public DTO/constructor/Thrift/fragment JSON changes or mixed-version negotiation
were added. Complete companion traversal/page/path/depth budgets, file leases,
distributed inline transport and CDC remain unimplemented connector work.

## Executed evidence and its limits

`test/r07/.../DuckLakeR07QualificationTest.kt` uses genuine native 1.1 producers,
closed before database-read-only readers open. It independently expects:

- Raw DATE/timestamp text and decoded `LocalDate`/`LocalDateTime`, microseconds,
  SQL NULL versus literal `NULL`, and BLOBs `005CFF` versus literal bytes `5C783431`.
  This does not qualify Doris nanosecond/offset-second/interval-component payloads
  or nested temporal conversion; the existing lossy/degraded mappings are not widened.
- Stable IDs through rename/add, initial default **42**, preserved stored NULL;
  VARIANT and TIME **24:00** reject through raw and decoded APIs.
- A real native mixed table with **5 file + 2 inline rows**; Doris rejects the
  whole plan, rather than return just the file subset. This is **not** a passing
  Doris mixed scan. The legacy experimental channel stays unqualified.
- A real merge across an inline ID gap: nullable `rowIdStart`, physical embedded
  row/snapshot fields and six file rows. Historical file scanning/counts refuse;
  no generated contiguous row IDs or metadata-count substitute is used.
- Native tagged Parquet deletes with NULL `partial_max` and equal CDC candidate
  paths: independent physical SQL sees two deletion snapshots. Doris rejects
  rather than apply all positions indiscriminately.
- Native bare Puffin vectors: length, magic and CRC checked independently;
  native cumulative containers: two `ducklake-snapshot-id` tags/cardinalities
  **1,2**, with equal candidate paths. Both shapes reject before Doris ranges.
  This is format/oracle evidence, **not** a Doris Puffin decoder.
- A native current file: real footer row count/field IDs and Doris refusal even
  when metadata COUNT/LIMIT is requested. Maintained range/Thrift parity tests
  and the live legacy scan verify serialization and no top-level Iceberg
  delete-content misrouting; no final-1.1 range or BE scan is claimed.
- Native GEOMETRY produces actual Parquet and stable field metadata; the real
  Parquet library parses its footer. No canonical geometry payload decoder or
  Doris 1.1 geometry scan is claimed.
- Unknown active delete count stays unknown; live-count API refuses it and Doris
  supplies no estimate. Caller budget abort retains its original exception;
  missing `file_format` propagates failure. With a one-connection pool the next
  owned session still works. One bounded companion page is a **qualification
  caller** check, not production adoption of a complete traversal budget.

The fixture metadata mutation setting `delete_count=NULL`, and the renamed
required-column failure fixture, are explicitly owned negative fixtures, not
native-writer success claims. No final-1.1 library mutation is used.

### Actual matched FE/BE and route matrix

The separate `compose/r07-qualification.yml` owns its services, subnet and ports.
It shares **no warehouse filesystem between FE and BE**, uses no Trino services,
and only performs connector reads. Native fixture producers write private S3
prefixes in a private MinIO plus unique PostgreSQL databases. Fixtures are retained.

Both peers report **doris-0.0.0-dd456ab7a89**, plugin API **13.0**. The actual FE
runs Java **17.0.2**. Its `doris-fe.jar` SHA-256 is
`4e37f5a6227b421be25fac0c49901216b9b3a8ddc4f8bd362b0a9482a0f80d7b`;
live plugin loading reports **failureCount=0**. The catalog JAR inside that FE
matches the resolved candidate hash above. BE is the existing matching ASAN
validation image, not performance/release qualification.
The final trial plugin JAR SHA-256 is
`4e43754889313ca973700f4e61e1d108eebf9afeb9f4dec67a9358322f227201`;
its local plugin ZIP is
`26b52be40bba5b5ec1cba69efb7a631b4f9d8b06f21a5bfa80f2a70115d35269`.
Those refer to the tested candidate staging, not the later default-lane assembly.

| Route | What actually ran | Not established |
|---|---|---|
| Local DuckDB 1.1 | Opt-in read-only metadata/raw/decoded fixtures, physical footer checks, Doris planning refusals; public connector rejection | Public local-FE driver/1.1 BE scan, distributed inline/history/CDC |
| PostgreSQL 1.0 | **Actual FE→BE S3 scans**, rename/stable IDs, default 42 projection/predicate, bytes, NULL/literal NULL, dates and nullable counts | New 0.4-native parity or all legacy types |
| PostgreSQL 1.1 | Native initialized file-backed fixture, actual FE admission refusal, subsequent 1.0 read recovery | 1.1 SQL-server profile/payload/evolution/BE scan |
| Native Quack / Java Quack | Not run | Driver packaging, non-bootstrap/read sessions, profile, scans/publication/cancellation |
| Existing MySQL 1.1 JDBC | Not run; public Doris 1.1 tier stays closed | UTC/InnoDB/scalar/profile/driver boundaries in this connector |

MySQL's **library** existing-catalog scalar 1.1 JDBC tier is not given a 1.0-only
ceiling by this report. Native MySQL creation/upgrade/mutation is still deferred;
neither that failure nor a successful DuckDB-backed route certifies MySQL Doris.
No unavailable SQL-server provenance facts were fabricated.

### FE diagnostic defect / failure transparency

The first live probe expected the connector's 1.1 refusal text at SQL and failed:
FE instead returns `Database [main] does not exist`. The original failure is retained
at `/tmp/opencode/doris-r07/initial-live-diagnostic-failure.xml`.
FE logs independently show the explicit `DuckLake 1.1 reads are not qualified`
exception followed by `ExternalCatalog.buildDbForInit`: **"Failed to check db main
exist in remote system, ignore it."** The final probe pins this observed refusal,
not a successful scan or improved error propagation. This FE diagnostic loss
remains a rollout limitation; no FE source patch was made here.

### CDC, cancellation and leases

Doris exposes snapshot/time travel, not a DuckLake CDC reader/assembler. The library
candidate deletion descriptors above are **not connector CDC execution**. Update
pairing, transient-row suppression, full-file/window assembly and absolute-row-ID
CDC semantics remain unsupported/unverified; incremental time-travel kinds still
decline. No CDC result was fabricated from native oracle rows.

Caller abort/query-failure recovery is tested at the catalog/provider boundary.
The live cancellation probes use JDBC query timeout for both `SELECT SLEEP(10)`
and a query over the real S3-backed table, followed by `SELECT 1` / another exact
table scan. Cancellation occurs at the SLEEP expression, not during a stalled
storage read. It must not be promoted into metadata-SQL interruption, network
outage, snapshot/file-lease or physical-file retention qualification. Complete
FE statement planning sessions and all total traversal budgets remain a NO-GO
item. A resolved S3 URI or read session is **not a physical file lease**.

Final live fixture suffix is `5fcbebb6f5bb424085f818faf9e4f5b4`: PostgreSQL
databases `r07_10_<suffix>` / `r07_11_<suffix>` and matching
`s3://r07/r07_10_<suffix>/` / `s3://r07/r07_11_<suffix>/` prefixes. Only the
isolated R07 services were stopped after verification; containers, metadata and
object data are retained. Shared Trino/Doris services were not stopped.

## Catalog-owner observation / minimal reproduction

No catalog artifacts or sources were modified. One contract observation needs
the catalog owner's decision: on local DuckDB, default-off metadata methods can
still resolve final-1.1 metadata (the original public-connector probe resolved a
handle rather than throwing). Minimal reproduction against a native initialized
1.1 file is `JdbcDucklakeCatalog(config with enableFinal11Reads=false)` followed
by `currentSnapshotId` and `getTable("main","t",snapshot)` outside a read session.
This is **not** proof that all raw payloads/writes are allowed, but consumers must
not assume that successful handle lookup attests the opt-in read contract. Doris
now refuses the format explicitly during initialization. Other targeted canonical,
count, lineage and descriptor checks found no new catalog defect in their tested scope.

Strict native failures are unchanged and were **not rerun/waived here**:
**R02=3, R03=1, R04=1; M01 MySQL=6+4, zero skips** in the coordinating evidence.
They still block blanket native parity, persistence/optimization and wider claims.

## Reproduction and final verification

See `compose/R07-QUALIFICATION.md` for local staging and startup. Commands use
`GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle`, an isolated daemon registry,
and `mise exec -- ./gradlew`. No global daemon/service shutdown was used.

```sh
./gradlew check java17PluginTest r07ReadQualificationTest r07LiveQualificationTest pluginZip \
  -Pr07CatalogRepository=/tmp/opencode/catalog-r07-58ef4d7/maven-repository \
  --rerun-tasks --continue --no-daemon --no-configuration-cache
```

The compiler toolchain remains Java **25**, emitting **61**. `verifyJava17Runtime`
checks actual main/runtime classfile versions including applicable multi-release
entries. The ordinary test lane remains Java 25 because **test-only** brikk-sql
0.18.1 artifacts contain classfiles **65**, and verifier **69**. Forcing the whole
test discovery to Java 17 fails before execution; `java17PluginTest` runs the
complete plugin tests at 17 while normal tests still execute the corpus-tooling
unit tests at 25. This is not a skipped test or a product runtime dependency.
The plugin ZIP does not contain that tooling.

The separate Parquet `PageHeader` issue is fat `fe-thrift` shadowing, **not a
Java-25 bug**. Test tasks prepend real `parquet-format-structures-1.17.0`.
Contrary to stale repository comments, the live API-13 FE is **not slim**:
both its fe-thrift and Maven-local's contain **382** Parquet-format classes and
identical PageHeader bytes (SHA-256
`7a663815435a03e170fce6712df2be54980c0b3ec4a5ab3565c3ae2a03018429`).
`javap` on the copied live jar shows the bundled class exposes
`setDataPageHeader`, not Parquet 1.17.0's expected `setData_page_header`.
The actual FE's exported classpath puts parquet-format-structures **1.18.0** at
index **80**, before fe-thrift at **296**. That explains the runtime winner,
not absence of duplicate classes. `fe-classpath-env.txt` and the copied live
fe-thrift inspection under the local evidence directory preserve this audit.
The actual S3 file scan exercises BE Parquet, not FE's inline writer. The
compile/test 1.17.0 versus host 1.18.0 writer/footer gap is therefore explicitly
unverified at FE runtime. Misleading comments were corrected, not used as a skip.

Final maintained checks (the Java-17 lane overlaps the Java-25 lane; do not sum
them as unique tests):

| Lane | Passed | Failures/errors | Existing skips |
|---|---:|---:|---:|
| Default 0.12.0 / JDBC 1.5.6.0, Java 25 | 253 | 0 | 1 |
| Default 0.12.0, complete plugin tests at Java 17 | 227 | 0 | 1 |
| Candidate 0.13.0 / JDBC 2.0.0.0, Java 25 | 253 | 0 | 1 |
| Candidate, complete plugin tests at Java 17 | 227 | 0 | 1 |
| Targeted R07 native/planning probes at Java 17 | 14 | 0 | 0 |
| Isolated matched-peer live probes at Java 17 | 2 | 0 | 0 |

The one pre-existing disabled legacy inline-delete test remains visible in each
maintained lane; no tests were disabled or skipped to qualify the trial.
Detekt, assembly and actual runtime-classfile checks pass in both dependency
lanes: **24,626 default / 24,701 candidate classes**, each across **44 runtime
JARs plus main output**, maximum applicable classfile **61**. All **18** maintained
Python smoke-harness unit tests and `bash -n compose/smoke.sh` also pass.

The first default-lane rerun caught this trial's unconditional fixture option:
native 1.5.6 rejects `DUCKLAKE_VERSION`. That failure was fixed by selecting the
explicit version syntax only in the locked DuckDB-2 trial; the old lane retains
its original SQL and both lanes rerun, rather than changing a driver or golden.
The initial failure log is preserved as `default-012-unsupported-option-initial.log`.

The trial ended by omitting the candidate property and rerunning assembly/ABI
checks. The restored default ZIP contains exactly the released 0.12.0 catalog
JAR (SHA-256 `e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729`),
not a cached candidate. No private service is left running and no default build
setting was changed to the snapshot.

Primary evidence is under `/tmp/opencode/doris-r07/`: `candidate-final.log`,
`live-final.log`, `default-012-final.log`, `missing-exclusive-repository.log`,
`candidate-evidence/`, `default-evidence/`, `dependency-delta.diff`,
`live-final-provenance.txt` and `retained-services.txt`. The exact committed
revision is returned in the coordinating session result.
No approval for publication, deployment, default-on reads, automatic migration,
1.1 writes, physical cleanup or mixed-version negotiation follows.
