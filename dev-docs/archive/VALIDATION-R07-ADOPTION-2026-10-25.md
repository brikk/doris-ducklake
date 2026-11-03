# R07 latest catalog adoption — October 25

## Bounded quality repair: QG-02

The final-1.1 facade now dispatches equals/hashCode/toString/close before loading
its plan. Equality/hash are object identity; toString uses only local identity and
snapshot coordinates. Close remains a no-op on this non-owning view: connector and
statement ownership are unchanged. Metadata methods retain the existing deferred
capture and statement-scoped success/failure memoization.

Three actual-facade regressions failed on the original code and pass after the fix:
unused Object/lifecycle methods do not capture, a failed capture does not poison
Object/close, and two facades share the same statement-scoped failure exactly once.
The context injects a capture failure before SQL/S3, proving those methods never
enter capture; no engine hook, fake plan or new production testing seam is added.

`check java17PluginTest` passes on unchanged catalog `6730bb8`: **265/239 passing
tests** on the overlapping Java25/17 lanes (266/240 totals, one unchanged legacy skip
each), zero failures/errors, lint and Java17 runtime scan (24,767 classes). All three
new cases execute successfully in each lane. This is defect verification, not a new
17-case live FE/BE round or production rollout. Existing live evidence below retains
its original artifact identity.

```sh
GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle mise exec -- ./gradlew \
  check java17PluginTest \
  -Pr07CatalogRepository=/tmp/opencode/catalog-r07-6730bb8/maven-repository \
  --no-daemon --no-configuration-cache
```

Evidence: `/tmp/opencode/r07-step3-doris-final.log`, `r07-step3-doris-final-xml/`;
before-fix failures: `r07-step3-doris-red-tests.log`, `r07-step3-doris-red-xml/`.
The initial test getter compilation errors remain in `r07-step3-doris-red.log`.
The default released-lane JAR was rebuilt byte-identically (`d3a8299e...`), with no
candidate plan classes; the released plugin ZIP remains byte-identical (`832270f2...`)
to the full hashes recorded below. No repaired candidate was staged into an engine.
The following adoption report describes the preceding live-qualified revision.

Qualification only, continuing clean `3448107ad3a0ea7375863e69cf4e442633354e25`.
Verified implementation: `f025a93ebe201109cdce282da98c10307b069cda`.
This supersedes the candidate/API-gap statements in the preceding
[paging qualification](VALIDATION-R07-PAGING-2026-10-25.md), not its historical evidence.
No release, push, deployment, default-on reads, migration, connector1.1 writes,
cleanup, corpus campaign, new skip, vendor/catalog/Trino edit or artifact overwrite.

**Outcome:** latest bytes qualify the bounded ordinary PostgreSQL/S3 native-file
tier. Its needed catalog-side adoption is complete within the unchanged ceilings;
no new library defect was established. Remaining expansion requires the engine,
retention, aggregate-resource and operator choices below, not dead CDC adapters or
broader success claims. R07 as a whole remains NO-GO at those explicit boundaries.

## Exact dependency and adoption audit

Exclusive opt-in repository:
`/tmp/opencode/catalog-r07-6730bb8/maven-repository`; source
`6730bb871350fe27d6c926db2f1d8f50f41cb5e1`.
Resolved catalog SHA256:
`9ae564720d48b78f8aa6d4349cc082a8ff11815dfe8a9e639a5c140cc95cbab6`.
Fixtures/replay/SLT remain the attested0.13.0-SNAPSHOT bytes; JDBC remains2.0.0.0.
The four candidate JARs and JDBC are checked by `verifyR07Candidate` against actual
resolved paths/bytes. Existing scoped `org.apache.doris` Maven-local resolution is
unchanged; no global `maven.repo.local`. Earlier bundles remain immutable.

Production ordinary-file capture audit:

- Schema/table/column/spec/partition-value/name-map/inline-registry transfers use
  `readPlanningMetadata()` pages; active DATA/delete associations use
  `getDataFilesPage`. Every enumeration includes its charged terminal empty.
- `readHierarchy().listRetainedFiles` is already SQL-paged despite its name.
  Retained origin ownership and resolved paths are needed, including dropped
  objects. `getDataFilesByIdsBatch` does not replace DELETE ownership or origin
  hierarchy resolution; adding it would duplicate an existing bounded transfer.
- Adopt a charged one-item
  `getInlinedFileDeletesBetweenPage(table,0,S,null,1)` refusal probe.
  This validates optional positional storage even when empty. A positive result
  only refuses the table, never publishes an enumerated position plan. Empty is
  already terminal. A successfully validated empty `[0,S]` proof is shared only
  within this owned capture for the same table and older/equal coordinates.
  Positive probes and failed validation are not cached/reused across coordinates.
  After an empty valid window, keep the separately charged legacy Boolean
  `hasInlinedDeletes` EXISTS witness: unlike the new `[0,S]` page, it also refuses
  malformed negative begin snapshots. This is bounded existence, not a list or
  unbounded preflight. Dropping it would weaken the prior ordinary-tier refusal.
  An authored negative-begin positional fixture verifies this boundary through FE
  SQL; no raw catalog SQL bypass or broadened page request is introduced.
- Current/recent history uses exact `getSnapshot(id)` lookups, at most the existing
  configured16-coordinate window. Scanning all retained snapshots/change history
  would be worse; no legacy `listSnapshots`/`listSnapshotChanges` is invoked here.
  Numeric historical coordinates remain exact, including absence/expiry gaps.
- Header/profile/encryption/root/default entry points are scalar or independently
  bounded preflights. Missing-file defaults are still requested only after actual
  footer-proven stable-ID absence, with charged schema/history work. Legacy-named
  methods dispatched through the qualified proxy read the already captured view;
  they do not reconnect or issue SQL after publication. Unsupported operations
  have no fallback to the underlying catalog.

The new library's once-per-owned-context physical planning type check can transfer
up to257 layout rows plus257 PostgreSQL dimension rows. The capture reserves514
work units once before the first planning API, in addition to per-page/row work and
the positional probe's bounded layout allowance. This is admission work, not free
merely because the library caches its successful marker inside the transaction.

Targeted probes now use one-item decoded ordinary payload and deletion-candidate
pages, shared page/work/text/physical-byte allowances and progressing seeks through
terminal empties. Metadata/active files and retained ownership are also drained.
The single deliberate legacy rich-list observation checks NULL `deleteCount`
compatibility: the page DTO does not carry that field. It is a tiny authored fixture
observation, NOT used to authorize a capture, scan or exact count. Existing legacy
planner/count refusal assertions and tiny native-fixture count observations remain
explicit compatibility probes, not production planning transfers.

No added-between/CDC, retained-ID, snapshot-change or engine payload adapter is
added merely because its API exists. Library pages provide metadata/payload
candidates, not the missing engine interfaces.

## Preserved scope and remaining owner decisions

The capture remains fully paged, bounded and **materialized, not lazy/streaming**:
one controlled owned statement transaction, original failure memoization, complete
success-only caches scoped to it, shared128 TOTAL pages (including all scopes and
terminal empties), node/depth/path/text/work/deadline and64-MiB physical allowances.
No ceiling is raised and no retry resets allowances. A catalog with many tables,
retained files, metadata scopes or historical coordinates can exhaust128 pages
before other maxima. Small page sizes trade transfer shape for fewer admissible
scopes. Driver/lookahead buffers, SQL-server work, pool/setup/network interruption
and fleet-wide resources are not bounded by those accepted-output allowances.

API13 still lacks mandatory per-row DATA/delete snapshot filters, a generic
distributed inline payload channel, engine CDC and supported in-flight planning
cancellation registration. Inline/mixed historical/unsupported-type reads remain
fail-closed. New payload/deletion/history APIs remove library transfer gaps, not
these engine boundaries. No S3 staging write/GC, shared FS/temp Parquet, invented
RPC/Thrift/cancellation callback, FE patch or semantic degradation is introduced.

Owned capture improvements do not solve aggregate retained pools, cold namespace
diagnostic masking, physical-object retention/leases, fleet admission or other
routes. Resource ownership is per connector, while the FE retains many catalog
instances. Choosing aggregate pool policy/lifecycle and server capacity is an
engine/operator decision, not permission to raise capacity until tests turn green.
Changing engine hooks, physical retention, production topology or default support
requires owner consultation and new qualification.
The unchanged library pool default is maximum10/minimumIdle1 **per connector**;
even single-connection-per-capture qualification cannot establish an aggregate
fleet bound. Failed connector initialization already closes its own pool, and
connector close releases its pool and owned object-client executor/connections.
The PostgreSQL driver registration belongs to the shared plugin classloader:
deregistering it on one catalog close would break other catalogs, not cure pooling.
Plugin-unload lifecycle, aggregate admission/concurrency and cold-namespace exception
handling need explicit engine/operator choices; no arbitrary pool retuning is done.

Native R02=3/R03=1/R04=1/M01=6+4 and the strict long-path producer's one failure
remain unwaived. Fixed signed native/corpus pins, API13 peers, golden/exclusion
lists and the existing legacy inline-delete skip remain unchanged.

## Verification

Candidate maintained lanes pass262/236 Java25/Java17, zero failures/errors and
the one pre-existing legacy inline-delete skip in each overlapping lane. Targeted
native/refusal Java17 checks pass14, zero failures/errors/skips. Production lint
passes without baseline/suppression edits; the Java17 runtime scan checks24,767
classes across44 runtime JARs plus main output, applicable maximum61. The four
attested candidate JARs independently scan16+335+33+18 classes at maximum61.
`javap -public` comparisons for11 existing file/column/snapshot/change/ownership/
fragment/page records plus the catalog interface/config match10f81f9 exactly.
The JDBC class retains all116 existing non-synthetic-public declarations and adds
the seven documented page/batch methods (`adoption-jdbc-additive-abi.txt`);
compiler-private `access$` bridges are expressly excluded from that comparison.
Full frozenABI/wire and upstream985+86
tests/17,602 corpus records are supplied bundle evidence, not rerun here.

Candidate connector JAR SHA256:
`8f78edc3ff517074dc55559fe8717883272b100ce1b195966a068cd251b82faf`.
Candidate ZIP SHA256:
`b925d7bcfb74a4e6f6c5fb8edf0886317e3558f1e8200e7e31a58ea744a6081b`.
The fresh owned FE uses separately staged `/tmp/opencode/doris-r07-adoption-complete/plugin`,
byte-compared to that retained archive; its catalog/connector paths report the same
hashes. The previous staging tree and all earlier bundles/evidence are untouched.
FE Java17.0.2 and the curated matched API13 FE/BE images remain unchanged.
The fresh FE connector plugin load summary reports failureCount=0.
The existing FE-provided Parquet1.18.0 versus compile/test1.17.0 distinction is
preserved, not silently repinned; no host Thrift/SPI classes are bundled.

The strict long-path producer is separately rerun: one failure, zero errors/skips,
not included in connector acceptance totals. Its original native/object-name failure
remains red; no input, pin, expected result or exclusion is changed. All18 Python
smoke units and shell syntax pass. Evidence is under `/tmp/opencode/doris-r07/`:
`adoption-complete-candidate-live.log`, `adoption-complete-candidate-evidence/`,
`adoption-complete-candidate-summary.txt`, `adoption-complete-candidate-assembly.sha256`,
`adoption-four-jar-java17.txt`, `adoption-public-record-abi.txt`,
`adoption-strict-long-native-evidence/` and `adoption-python.log`.

The early exact-byte baseline also passes262/236 and14 targeted probes; a subsequent
run was interrupted by the session interruption before its targeted task completed
(`adoption-candidate.log`). Neither is substituted for the final complete run.
The first live attempt is retained as14/17 passing, three failures, zero errors/
skips (`adoption-live-initial.log`, `adoption-live-initial-evidence/`). Two failures
are harness readiness: recreating only the FE left no backend registration, and
the retained BE entrypoint exited waiting for heartbeat. The third is an authored
message expectation: negative DATA row starts correctly reject as
`Invalid paged data-file identity/lifetime`, not the assumed `Negative` prefix.
The assertion now names the actual corruption diagnostic; it does not waive the
refusal or classify it as a JDBC driver failure. A fresh owned FE/BE pair precedes
the final full suite; no PG/S3 fixture or capacity changes. None of this partial
run is counted as full reader acceptance or an excuse for a new test skip.
The next fresh-pair run passes16/17 with the sole new test exposing another
incorrect authored expectation: API13 resolves current table metadata before
pinning historical SQL, so current live inline deletes also refuse an older SQL
request. That refusal is now positively asserted, NOT bypassed or declared an
older SQL success. A separate production host capture proves the older frame
remains supported and a positive current probe is not cached into it. Evidence:
`adoption-final-candidate-live.log`, `adoption-pre-reservation-evidence/`.
The final artifact additionally reserves the514 physical-preflight work units;
earlier artifacts/evidence/staging are retained separately, not overwritten.
The intervening pre-fallback artifact also passes17 live checks and is retained in
`adoption-pre-fallback-evidence/`. Final acceptance reruns the complete suite on
the final artifact, including the negative-begin conservative EXISTS witness.

### Final SQL and fault scope

**17 live tests pass, zero failures/errors/skips, 78.884s**, on a fresh owned FE/BE
pair with no service restart inside recovery assertions. Real SQL reexecutes
current/historical native DATA, field IDs, NULL/literal NULL, BLOB/decimal/date/
timestamp/string bytes, rename/widening/replacement IDs, old-file defaults and
changed INSERT defaults, unknown-history NULL/non-NULL refusals, native VARCHAR
literal `NULL`, Parquet/Puffin deletes and same-container snapshot slices, embedded
absolute IDs/gaps, multiple physical row groups, counts, partitions and self/cross-
snapshot joins. The original mixed historical/tagged-Parquet/inline/type and write
refusals remain. No engine CDC or successful live-inline historical SQL is claimed.

One-item SQL pages cover3 schemas/4 tables/native schema version>256, defaults and
self-joins. Total/terminal-page budget failures retain the original exception;
duplicate seek keys, oversized text, cycles and distinct mapping-registry IDs
reject, repair and recover. The new authored physical tests reject fractional
NUMERIC footer storage before downcast, negative DATA starts, wrong optional
positional physical layout even when empty, and negative file-local positions.
These are catalog admission/corruption exceptions, explicitly checked NOT to contain
a PostgreSQL driver exception. Valid inline positions and malformed negative begin
snapshots refuse instead of becoming incomplete scans. Fresh SQL recovers after
each repair; positive current refusal is not cached into a valid older host frame.

Actual locked PostgreSQL statements stop **before fixture unlock** at40ms explicit
control and3,010ms three-second deadline, retain driver SQLSTATE57014, then recover
on the same one-connection pool. Same-statement original-failure memoization and
initialized-FE driver diagnostics also pass. Network outages remain slower:
PostgreSQL10,014ms and MinIO10,059ms before their I/O/socket failures. These are
observations, not universal latency bounds, network/lease guarantees or an API13
in-flight cancellation callback. Exact stdout/XML and timings are retained in
`adoption-complete-fault-observations.txt` and the complete candidate evidence.

At suite end PostgreSQL remains at max_connections100:70 idle retained-pool
connections, one active diagnostic and one NULL-state backend; **zero idle-in-
transaction**. This higher aggregate is exposed, not hidden by individual capture
budgets or a server-capacity increase (`adoption-complete-pool-state.txt`).

### Released restoration and owned shutdown

With NO override, maintained Java25/Java17 lanes pass253/227, zero failures/errors,
the same existing skip in each. Lint/assembly pass; Java17 scans24,630 classes across
44 runtime JARs plus main output, maximum61. Both resolved graphs exclude0.13.0;
catalog/fixtures/replay/SLT0.12.0 and JDBC1.5.6.0 bytes match retained release hashes.
The ZIP contains no candidate-only classes, fixture/JDBC JARs or host Thrift/SPI,
and retains the API13 manifest. It is **byte-identical** to the starting released
assembly, not merely equivalent:

- Released catalog: `e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729`.
- Released connector: `d3a8299e8c65ada6101e74d8074646d5a5e00f89e1a044c7cdb7fdcb384b5c59`.
- Restored ZIP: `832270f2cf95e79e1464c72cdd6f8d66fc092264dff4fa1af656f5e0ac20fc45`.

Evidence: `adoption-released-complete.log`, `adoption-released-evidence/`,
`adoption-released-summary.txt`, `adoption-default-assembly.txt`,
`adoption-default-assembly.sha256`, `adoption-released-family.sha256`.
Only the four owned services are stopped. The initial compose default10-second
shutdown grace killed PostgreSQL; its retained storage was restarted for crash
recovery/retention verification, then stopped with a longer shutdown grace. This
is an explicit harness lifecycle observation, NOT a planning-budget retry or an
additional SQL recovery pass. `adoption-retention-recovery.log` and
`adoption-retained-services-final.txt` retain the final state. All PG/S3 fixtures,
failed native producers, candidate stages and old bundles remain. No Trino/shared
service or global Gradle daemon is stopped.
PostgreSQL recovery verifies203 retained R07 databases and its final exit is0;
the MinIO container/data remains retained and stopped as well.

```sh
export GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle
candidate=/tmp/opencode/catalog-r07-6730bb8/maven-repository
mise exec -- ./gradlew check java17PluginTest r07ReadQualificationTest pluginZip \
  qualificationDependencies -Pr07CatalogRepository="$candidate" \
  --rerun-tasks --continue --no-daemon --no-configuration-cache
# Stage and byte-check the candidate ZIP in a fresh OWNED FE; retain PG/S3 fixtures.
mise exec -- ./gradlew r07LiveQualificationTest -Pr07CatalogRepository="$candidate" \
  --continue --no-daemon --no-configuration-cache
# Separate native producer gate, deliberately still red.
mise exec -- ./gradlew r07LongPartitionNativeTest -Pr07CatalogRepository="$candidate" \
  --no-daemon --no-configuration-cache
# Restore released build output last: NO override.
mise exec -- ./gradlew check java17PluginTest pluginZip qualificationDependencies \
  --rerun-tasks --continue --no-daemon --no-configuration-cache
```
