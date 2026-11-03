# F22 — corpus startup and cleanup ownership

## Status

**Implemented and verified** against Doris connector base `9b44463`.
All five ownership regressions and full-module checks pass. This is a test-harness
fix, not a new connector feature or a catalog/Doris re-vendor. Changes are not
committed or pushed by this task.

## Change

`DorisCorpusReplayTest.setUp()` now performs prerequisites only. It no longer
enumerates matching PostgreSQL databases or force-drops previous runs. The old
`LIKE 'corpus_doris_%'` query matched both real corpus names and wildcard lookalikes;
neither a matching prefix nor an inactive connection proves ownership.

`DorisReplayEngine` retains the existing per-engine ownership lifecycle:

- A UUID-based, internally generated namespace is used for databases/catalogs.
- A database is recorded only after successful CREATE. CREATE has no adopt-existing
  fallback; generated PostgreSQL identifiers are quoted.
- A fresh FE catalog is never pre-dropped. An existing catalog is replaced only
  when this engine previously acknowledged its CREATE. Successful DROP releases
  that registration before replacement, so failed replacement cannot authorize a
  later DROP. A set avoids duplicate cleanup registrations on reattachment.
- Close attempts only registered database/catalog names, with quoted identifiers;
  pooled connections are force-closed only for those owned databases. Cleanup
  failures remain visible. No wildcard/name scan grants cleanup authority.

Abandoned runs are intentionally retained. This task does not add an automatic
stale collector or authorize an operator sweep. Any future stale collector needs
its own explicit ownership/age/active-run policy and approval.

## Regression design and countercheck

`DorisCorpusIsolationTest` uses a **disposable real PostgreSQL Testcontainer**, not
the shared substrate. A recording JDBC FE implements only readiness and catalog
CREATE/DROP control flow; it is not a live FE/classloader or query-result oracle.

The startup regression calls the actual `DorisCorpusReplayTest.setUp()` entry point
with isolated PostgreSQL and recording-FE properties. Its normal DuckDB extension,
directory and JDBC prerequisites still run; an assumption abort is treated as a
test failure, not silently accepted as a new skip.

| Case | Required result |
|---|---|
| Startup with an active run, abandoned run, `corpusxdorisyforeign` wildcard lookalike and unrelated DB | All four databases survive; the active connection and marker table remain usable. |
| Close run A on a worker while B holds a live connection/marker | Only A's two acknowledged databases/catalog are removed; B stays connected with unchanged marker data. |
| Failed first catalog CREATE | No DROP of that unowned catalog; the engine's successfully created database is reclaimed. |
| Reattach to an acknowledged catalog | CREATE → owned DROP → CREATE → one final DROP, all for the same owned identifier. |
| Failed replacement after successful owned DROP | CREATE → owned DROP → failed CREATE; close does not issue an extra unowned DROP. |

Before the fix, the initial three regressions all failed at the intended ownership
assertions: startup removed the active/abandoned/lookalike databases; first connect
and refused CREATE issued a preemptive DROP. Countercheck log/XML:
`/tmp/opencode/f22-before.log`, `/tmp/opencode/f22-before-results.xml`.
The lookalike fixture uses lowercase to match PostgreSQL's unquoted identifier
folding; the original countercheck also showed both canonical corpus databases gone.

Targeted checks on the first repair passed three tests and Detekt. Two additional
reattachment cases protect existing behavior and ownership release; all five pass
in the final normal test run, with zero failures/errors/skips in the new class.

## Verification scope

Tests use one Gradle worker, a 1 GiB Gradle heap, in-process Kotlin compilation and
a 768 MiB test heap because other Doris sessions are active. No existing stack was
stopped, restarted, redeployed or repinned. No shared database, object prefix,
metadata volume or old artifact was swept. Testcontainer destruction affects only
the disposable test fixture.

Final checks (unchanged released catalog **0.12.0**, Doris API **24**, no R07 property):

- Java-25 normal suite: **259 passed, 1 existing skip, 0 failures/errors** (260 total).
- Java-17 plugin suite: **228 passed, 1 existing skip, 0 failures/errors** (229 total).
- Detekt and Java-17 runtime closure gate pass (**24,630 classes**).
- Existing Python smoke-driver/lake tests: **19/19**; shell syntax and diff whitespace pass.
- Production plugin ZIP remains byte-identical to the previous live-qualified one:
  `01e576a7480bfd991cae9c6d4399340e17897cec864de4a68ec680bb72ac6c6a`.

Reproduction:

```bash
JAVA_HOME=/home/jayson/.local/share/mise/installs/java/25.0.2 \
  ./gradlew -I /tmp/opencode/f22-small-tests.init.gradle check java17PluginTest \
  --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=512m' \
  -Pkotlin.compiler.execution.strategy=in-process
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s compose -p 'test_*.py'
bash -n compose/smoke.sh
git diff --check
```

The external init script sets `Test.maxHeapSize='768m'` and `maxParallelForks=1`;
it changes resources only, not inclusion/exclusion, expectations or failure policy.
Evidence: `/tmp/opencode/f22-final-check.log`, `f22-final-isolation.xml`,
`f22-smoke-unit.log` and the normal Gradle XML reports. No new skip or golden change.

No starter/full corpus campaign or live FE/BE qualification was run. The existing
API-24/released-catalog qualification remains evidence for its own unchanged plugin
bytes; this harness repair does not imply broader R07 acceptance.

## F24 follow-up

The separate [F24 feasibility audit and implementation TODO](TODO-F24-PLUGIN-PROVENANCE.md)
records the exact existing API-24 inventory surface, confirms that the current
provider's loaded version is NULL, and supplies 13 ordered implementation items.
F24/F25 are not closed by the F22 cleanup fix.
F24's 13 open items are also grouped into Project Todos **F24-A through F24-E**
(artifact identity, serialized replacement, runtime attestation, stateful regressions,
then bounded real A/B acceptance/closeout). This audit does not authorize executing them.
