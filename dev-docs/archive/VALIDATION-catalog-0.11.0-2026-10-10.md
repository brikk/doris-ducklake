# DuckLake catalog 0.11.0 release adoption

**Result: release 0.11.0 adopted and live-green on Doris `dd456ab7a89` / API 13.**
DuckDB JDBC remains **1.5.6.0**, and Python smoke/seed helpers use **1.5.6**.
No production adapter changes or FE/BE image rebuilds were needed.

## Published artifacts

`gradle/libs.versions.toml` selects **0.11.0** for both catalog and corpus runner;
the corpus runner's `slt-format` dependency is also 0.11.0. Release POMs, Gradle
metadata and test fixtures resolve from Maven Central, not the snapshot repository.

- Catalog JAR SHA-256:
  `e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729`.
- Plugin ZIP SHA-256:
  `b067a3bdf1f3ac4b00c85ed22109a409260e30fdd1c7b962fdb35817b4b55e1a`.
- Connector JAR remains unchanged:
  `30317dcb9473d2ef5c035a93a51aed32ede058e24c58989e787ff3b2389264d1`.

The packaged catalog bytes match a direct download of the public Maven Central
0.11.0 JAR. The ZIP contains only `ducklake-catalog-0.11.0.jar` (no snapshot),
declares connector API 13.0 and excludes host SPI/Thrift/libthrift duplicates.
The live FE's catalog checksum matches that release artifact.

## Verification

- Java 25: **251 passed, 1 skipped, 0 failed**; Detekt and assembly passed.
- Java 17 plugin suite: **227 passed, 1 skipped, 0 failed**.
- All **18** smoke-driver/lake tests passed.
- FE was stopped before the release plugin was installed, then restarted using
  persisted metadata. Connector plugin load reported `failureCount=0`.
- Existing FE/ASAN BE both report `doris-0.0.0-dd456ab7a89`; other services and
  run-owned evidence were not removed.

Full isolated live run **`fc1302e7a82a480f8df96f924e920a84`** passed TPC-H
reads/counts, nullable COUNT, filter EXPLAIN, OPTIONAL position deletes (100 -> 93),
database/table DDL, INSERT, buckets, CTAS, snapshot expiry, scheduled-file cleanup,
orphan deletion and foreign-file preservation. DEFAULT predicates remain green:
**3/3** old rows with value 42, explicit new-row value **99**, and **0** NULLs.

The retained database/catalog is
`doris_smoke_fc1302e7a82a480f8df96f924e920a84`, with warehouse
`s3://ducklake/doris-smoke/fc1302e7a82a480f8df96f924e920a84/`.
Logs and the downloaded release artifact are under
`~/DEV/OSS/doris-ducklake-api13/output/catalog-011-release/`.
The earlier snapshot and 0.7.1 plugin ZIPs remain under `output/catalog-011/`
for rollback and historical comparison.

## Qualification boundary

This replaces the [tested snapshot](VALIDATION-catalog-0.11.0-SNAPSHOT-2026-10-10.md).
F08 historical delete visibility, nested/per-file mapping gaps, production inline
transport and other review items are not automatically closed by the dependency
bump. Full corpus replay remains deferred while F22's broad cleanup guard is open.
ASAN smoke does not establish release performance or Duckbridge JNI correctness.
