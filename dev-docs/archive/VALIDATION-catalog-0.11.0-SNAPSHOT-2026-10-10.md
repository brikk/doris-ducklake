# DuckLake catalog 0.11.0-SNAPSHOT validation

**Result: green on Doris `dd456ab7a89` / connector API 13, with DuckDB 1.5.6.**
No production adapter changes or Doris image rebuilds were required.

**Superseded by release 0.11.0:** after Maven Central publication became visible,
the release passed Java-25/Java-17 checks and its own full isolated smoke, and
replaced the snapshot in the live FE. See the
[release adoption report](VALIDATION-catalog-0.11.0-2026-10-10.md).
The results below remain historical snapshot evidence.

## Artifact identity

The catalog, test-fixtures and corpus runner were published to the existing
Central Portal snapshots repository:
`https://central.sonatype.com/repository/maven-snapshots/`.

- Snapshot: `0.11.0-SNAPSHOT`, build **2**, published `2026-10-10T16:17:34Z`.
- Tested pin: **`0.11.0-20261010.161734-2`**. The shared version reference pinned
  catalog and corpus-replay together during this validation, rather than allowing
  a later mutable snapshot to silently replace the tested build.
- Catalog JAR SHA-256:
  `97a34111e43e30a164883343c9c7b73444f134d54ba094d4a10ce2f16a52cce9`.
  This matched Sonatype's published checksum and the JAR loaded during snapshot validation.
- Plugin ZIP SHA-256:
  `73a9e770be883eeb1fc0027c87627fa8158d14125cc2733e9e1c001569fcb364`.
- Connector JAR is unchanged:
  `30317dcb9473d2ef5c035a93a51aed32ede058e24c58989e787ff3b2389264d1`.

The ZIP contains one catalog JAR, declares connector API 13.0 and excludes host
SPI/Thrift/libthrift duplicates. Its catalog entry retains the filename
`ducklake-catalog-0.11.0-SNAPSHOT.jar`; the checksum identifies the immutable build.

## Checks

- Java 25: **251 passed, 1 skipped, 0 failed**; Detekt and assembly passed.
- Java 17 plugin suite: **227 passed, 1 skipped, 0 failed**.
- All **18** cluster-free smoke-driver/lake tests passed.
- JDBC dependency is **1.5.6.0**; Python helpers and substrate seed override use
  **DuckDB 1.5.6**. The final headless checks were rerun after the JDBC update.
- FE was stopped before installing the candidate, then restarted with persisted
  metadata. Plugin load summary reports `failureCount=0`.
- Matching FE/ASAN BE both report `doris-0.0.0-dd456ab7a89`; engine images are
  unchanged from the API-13 re-vendor. No unrelated services were stopped.

## Isolated live smoke

Run ID **`bdf6da4ca709432a8998819bc6f1a770`** owns:

- PostgreSQL database / FE catalog: `doris_smoke_bdf6da4ca709432a8998819bc6f1a770`.
- Warehouse: `s3://ducklake/doris-smoke/bdf6da4ca709432a8998819bc6f1a770/`.

Passed: TPC-H reads/counts, nullable COUNT, filter EXPLAIN, OPTIONAL position
deletes (100 -> 93), database/table DDL, INSERT, bucket equivalence, CTAS,
snapshot expiry, scheduled-file cleanup, orphan deletion and foreign-file
preservation. DEFAULT predicates remain green: **3/3** old rows with default 42,
explicit new-row value **99**, and **0** NULLs.

The run-owned resources are retained. Logs and checksum evidence are under
`~/DEV/OSS/doris-ducklake-api13/output/catalog-011/`. The prior API-13 plugin ZIP
with catalog 0.7.1 is preserved there as `plugin-catalog-0.7.1-api13.zip` for rollback.

## Boundary

This validates the existing adapter against the published catalog snapshot; it
does not automatically close F08 historical delete visibility, per-file/nested
mapping gaps, production inline transport, or other review findings. Full corpus
replay was not run while F22's broad cleanup guard remains open. ASAN smoke is
not a release-performance qualification, and no Duckbridge JNI result is implied.
