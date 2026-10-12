# DuckLake catalog 0.12.0 release adoption

**Result: release 0.12.0 adopted; published JARs match the qualified snapshot.**

The shared version reference selects catalog and corpus runner **0.12.0**;
test fixtures and transitive `slt-format` also resolve at 0.12.0 from Maven Central.
No catalog Maven-local override or snapshot pin remains. DuckDB JDBC stays at
1.5.6.0, Python helpers at 1.5.6, and test-only brikk-sql at 0.18.1.

## Release verification

All three artifacts' POM/module/JAR URLs and the catalog test-fixtures JAR returned
HTTP 200. All four released JARs are byte-identical to the tested public snapshot
**`0.12.0-20261012.203218-2`**. Gradle release resolution and assembly passed;
resolved JAR bytes and the packaged catalog were compared to direct Central downloads.

- Catalog SHA-256: `e76e0b167e03cb355a4463183995a10d737ba974e0d8d5a190c93ea2e946b729`.
- Fixtures SHA-256: `b91aa5154c67dc35daecacdb0971bb3b6cc155e4b5c217547c09c6ba8f9e3859`.
- Corpus runner SHA-256: `6dc1a7b48cda90ea6a5b05fb7559458440a87ff4b968cd18a3247ead28a8e35e`.
- SLT format SHA-256: `6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409`.
- Release plugin ZIP SHA-256: `f60aada7908cebe606cfe5f43693c8954ca01f3d2ecc395968b48537d1ffe184`.

The ZIP contains one `ducklake-catalog-0.12.0.jar`, excludes host SPI/Thrift/libthrift
and brikk-sql, and retains the existing API-13 connector. The production catalog
JAR is also byte-identical to 0.11.0; 0.12.0 changes test fixtures/preparation.

## Reused snapshot validation

Completed immediately before release adoption on matching Doris `dd456ab7a89`:

- Java 25: **253 passed, 1 skipped**; Java-17 plugin suite: **227 passed, 1 skipped**.
- Detekt (explicit rerun), assembly and all **18** Python smoke-driver/lake tests passed.
- Isolated live smoke passed reads, nullable counts, deletes (100 -> 93), database/table
  DDL, INSERT, buckets, CTAS, DEFAULT backfill and all three maintenance procedures.
- Persisted-metadata FE restart retained exact INSERT/bucket values and read counts;
  connector plugin loading reported `failureCount=0` before and after restart.

Run `039bcfc5f4794fa4985e953218bb8710` retains its database/catalog
`doris_smoke_039bcfc5f4794fa4985e953218bb8710` and warehouse
`s3://ducklake/doris-smoke/039bcfc5f4794fa4985e953218bb8710/`.
Detailed local logs/hashes are under ignored `build/reports/catalog-0.12.0-snapshot/`
and `build/reports/catalog-0.12.0-release/`.

No tests were repeated and no infrastructure was started for the byte-identical
release. Test services remain stopped; the release ZIP has not been redeployed to FE.
Full corpus replay remains excluded while F22's cleanup guard is open. F08 historical
delete visibility and other existing adapter gaps remain open. This is not
DuckLake 1.1/DuckDB 2.0 support or ASAN-build performance qualification.
