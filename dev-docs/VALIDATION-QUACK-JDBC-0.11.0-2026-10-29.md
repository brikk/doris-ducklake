# Published Quack JDBC 0.11.0 verification

## Outcome and scope

**Verified:** `dev.brikk.duckdb:quack-jdbc:0.11.0` is published on Maven Central,
loads on Java 17/25, and passes the release contract/oracle suites as an actual
consumer dependency against signed DuckDB 1.5.6/v1 and released 2.0.0/v3 servers.
No driver production sources were recompiled for these consumer runs.

This is release verification, **not adoption into Doris or DuckBridge**. The
Doris connector currently consumes no Quack JDBC artifact: its native
`org.duckdb:duckdb_jdbc:1.5.6.0` dependency is test-only and serves `jdbc:duckdb:`.
Quack instead serves `jdbc:quack://...` and requires a remote Quack server. It is
not a drop-in replacement for those embedded bootstrap/oracle calls.

No production/test dependency pin, catalog version, feature opt-out, FE/BE image,
plugin bytes or retained service was changed. F24-C was independently committed
and pushed as `7a83a8d` before continuing this verification; F24-D/E and F25 are
not closed by these driver results. This report is the only repository change
from the Quack verification and is not automatically committed/pushed.

## Exact public artifact

Fetched directly from
`https://repo.maven.apache.org/maven2/dev/brikk/duckdb/quack-jdbc/0.11.0/`.
Each POM/JAR/sources-JAR SHA-256 matches its public Central checksum sidecar:

| Artifact | SHA-256 |
|---|---|
| `quack-jdbc-0.11.0.jar` (244,458 bytes) | `7c5889640b38e494a39c0bf818f1e0e35fb68152a00e0dcc9f273a5f29935ff3` |
| POM | `78b9f7f9e7a0c19e7037e1eb148925f33ca4153ce2b7f0a09d8eccc336917200` |
| Sources JAR | `a699e6a7536635d350927e575a25058b18b1f18a3f0a51c0f3aeb847a5a3467b` |

- **105 classfiles**, maximum class major **61** (Java 17), no bundled native
  DuckDB libraries or `org.duckdb` classes.
- JDBC service: `com.gizmodata.quack.jdbc.sql.QuackDriver`.
- Automatic module: `com.gizmodata.quack.jdbc`.
- The published POM has no runtime dependencies; JUnit is test-only.
- Published production source entries were compared byte-for-byte to release tag
  `v0.11.0`, commit `60a6367592a9bb1f9371a806f9ab058e961a2241`.

Central checksums establish byte consistency here, not an independently verified
PGP publisher trust chain. Signed-extension statements below refer to DuckDB's
own enforced extension-signature checks, not an unstated JAR signature check.

## Published-JAR consumer verification

Two fresh Maven projects under `/tmp/opencode/` contain the exact release tag's
test sources, **no `src/main`**, and depend on the public coordinate above.
They do not run `mvn clean` or build inside the driver's working checkout, whose
HEAD is already the subsequent `0.12.0-SNAPSHOT` development revision.

The complete release suite is augmented by one independent origin/URL-routing
test: loaded Quack classes must come from a JAR named `quack-jdbc-0.11.0.jar`,
whose actual bytes match the independent public SHA-256 and Maven version
properties. It rejects a rebuilt `target/classes` driver, checks JDBC version
0.11 and proves Quack and native DuckDB drivers own their distinct URL namespaces.

Both profiles use **Java 17.0.2**, Maven **3.9.16**, Linux x86-64, and a
version-matched native JDBC oracle. Each server fixture starts an isolated
in-memory CLI server on an ephemeral loopback port, with private extension paths,
signed **core** Quack/httpfs, unsigned extensions disabled and unsafe crypto off.

| Profile | Actual server / signed Quack | Native oracle | Tests total | Passed | Optional skips | Failures/errors |
|---|---|---|---:|---:|---:|---:|
| v1 | DuckDB `v1.5.6` / `7e80f7f` | `1.5.6.0` | 699 | 696 | 3 | 0 |
| v3 | released DuckDB `v2.0.0` / `f96420ed46` | `2.0.0.0` | 699 | 696 | 3 | 0 |

Each profile passes the release integration-report gate: **16/16 integration
classes**, 51 total reports including the added origin class. No required
integration test is skipped. The three explicitly whitelisted optional skips are:

- The external unsigned v3 preview fixture (not supplied; signed released v3 is
  exercised separately).
- IPv6 localhost fallback on an environment without that listener/address support.
- The transport test needing multiple localhost addresses.

The release tests include JDBC scalar/nested/metadata/prepared/batch/transaction
and streaming contracts, driver/handshake version identity, decoder limits,
invalidation and lifecycle regressions. The v3 profile additionally exercises the
new UUID cancellation, buffered-result cancellation, producer cancellation/reuse,
bounded DISCONNECT and exact `TIMESTAMPTZ_NS`/`epoch_ns` paths. v3-only test bodies
are version-gated; the v1 result does not claim v3 features work on old servers.

Exact CLI identities:

- 1.5.6: `069cc9f9b5`; binary SHA-256
  `61238cfbe9dfeaad4bcfcd8a48f6f7123dae2e9603aaadb1f77a4217aeb8980c`.
- Released 2.0.0: `d54cbbe3df`; binary SHA-256
  `c41f957db5645d545a9caef430994cf262d24fad9297fef70c691395aadb43fb`.

The v1 fixture uses required mode. The release's v3 test mode uses the real CLI
path plus an explicit post-run gate for exact released server/Quack identities;
that gate rejects missing/skipped required integration reports. No expectations
or skips were added to make the public JAR pass.

## Standalone Java 17/25 and Doris boundary

A separate probe compiles with `--release 17` and runs using **only the public
Quack JAR**, not native DuckDB or test libraries, on Java 17.0.2 and 25.0.2:

- Automatic JDBC discovery, driver version 0.11 and URL ownership pass.
- `jdbc:duckdb:` and PostgreSQL URLs are not intercepted; the absence of an
  embedded native DuckDB driver on this standalone classpath is verified.
- Invalid TLS configuration fails before connecting and does not expose its
  submitted endpoint/options in the configuration error.

This is a standalone loading/configuration check on Java 25, **not** a second
full Java-25 integration certification.

A read-only Gradle dependency-resolution probe confirms this Doris checkout still
uses released catalog **0.12.0**, native test JDBC **1.5.6.0**, and no Quack JDBC
in either runtime or test-runtime closure. The existing plugin receipt/hash is
preserved; no rebuilt plugin is deployed. The driver source checkout remains
unchanged as well.

## Remaining limits

- No Doris catalog, DuckBridge JDBC/Quack adoption, native BE/JNI, R07 engine hooks
  or live cross-engine acceptance was exercised by these isolated driver suites.
- v1 cancellation remains explicitly unsupported. Ambiguous v3 cancellation
  closes the session rather than reconnecting/replaying SQL or promising reuse.
- Per-request timeout does not certify an end-to-end whole-body query deadline;
  general query/network deadlines remain separate.
- Exact nanosecond reads/APPEND do not make interpolated prepared timestamp
  literals nanosecond-precision. The documented TUPLE/native-JDBC parity exception
  and other supported-type limits still apply.
- No production TLS/proxy/fleet/performance qualification is implied.

## Evidence

All raw inputs, POM/probe sources, consumer projects, server identity logs,
Surefire XML and results remain under:

`/tmp/opencode/quack-0.11.0-published-verification/`

Key files: `artifact-results.json`, `published-consumer-results.json`,
`verification-summary.json`, `v1-java17.log`, `v3-java17.log`, each profile's
`*-integration-gate.log`, `standalone-results.json`,
`doris-baseline-dependencies.json`, `doris-baseline.log` and `closeout-results.json`.
The setup script records the immutable release test commit and checks the driver's
working checkout is unchanged before/after both consumer runs.
Closeout confirms no owned test processes remain, and the retained Doris FE
process/image/runtime-config hash and original plugin receipt are unchanged.
