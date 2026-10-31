# F01 — referenced objects versus S3 URI aliases

## Scope and status

**Implemented and storage-integration verified.** Frozen-source full Java-25 and
Java-17 suites, real released-catalog/S3 cases, Detekt, ABI and packaging gates
pass. F23 was committed/pushed first as
`1e5efcd8c9b13a895ca57ed92c79b4c2d4a2579b` on request. F01 verification preceded
the subsequent user-requested commit/push. No further retained-container work is
part of F01.

This is an orphan-reference/storage-identity repair, not a maintenance permission
change, generic S3 client replacement or production qualification. F02 authority,
F07 concurrency, wider scope completeness and broader R07 remain separate.

## Current-dependency revalidation

The released catalog is still **0.12.0**, MinIO Java client **8.6.0**, DuckDB/JDBC
**1.5.6/1.5.6.0**, and Doris source/API/JNI pins are unchanged. The published
catalog-0.12.0 source JAR was independently downloaded and verified against its
published module SHA-256:
`388a77b5fb2091a4c814152a56004559d51dc1de7fed6f23956a435832a09dee`.
It is retained as `/tmp/opencode/f01-catalog-0.12.0-sources.jar`.

`getDataPath()` returns the persisted catalog-wide metadata value. Its
`listAllReferencedFiles()` returns unnormalized owning schema/table/file paths,
including data/delete files from dropped-but-unexpired tables and scheduled paths.
No alias canonicalization in this dependency repairs the connector's string
comparison. The production lister always emits `s3://bucket/<raw objectName>`.
Thus accepted `s3a://`/`s3n://` references still mismatch the same listed object.

## Implementation contract

- `S3WarehouseBlobStore.objectIdentity` canonicalizes **only** accepted S3 scheme
  aliases/case to `s3`. Bucket/key text is retained exactly: no URL decoding,
  Unicode normalization, case folding, slash collapsing or dot-segment rewriting.
  Other schemes remain opaque.
- `OrphanFiles.find` applies this comparison identity to **both** known references
  and listed objects, including directory-boundary comparisons, and returns the
  original listed URI for deletion. The `ducklake-`/extension gate and strict age
  comparison are unchanged. No foreign-file deletion gate is broadened.
- A directory suffix is appended only when absent. An existing `/` or `///`
  remains part of the raw key; it is not trimmed or duplicated. This also repairs
  the trailing-slash guard symptom recorded as N07, without advertising new
  dataset formats or closing scope/completeness/performance follow-ons.
- Owning catalog/schema/table/file resolution remains authoritative. A differently
  configured fallback warehouse must not mask persisted catalog paths. Scheduled
  references still resolve against the catalog root, not the named table.

### Pinned SDK path rewriting

The real-store fixture exposed another identity boundary: MinIO client 8.6.0's
`S3Escaper.encodePath` explicitly drops empty path segments before object requests;
HTTP path construction also cannot safely preserve literal dot segments. A
repeated-slash delete can therefore target a different existing key. No dependency
upgrade or silent key normalization is used as a workaround.

The delete path now reports repeated-slash and literal `.`/`..` segment keys as
**per-object failures before the SDK call**. No different object is deleted;
scheduled cleanup retains failed rows under its existing contract. Listing keeps
the exact prefix (and fails loudly on backend rejection), never broadens it to a
different prefix. Ordinary supported keys and batch partial-progress behavior are
unchanged. This is an explicit unsupported deletion shape, not a green xfail.

## Regression and storage evidence

Before production edits, the targeted old-code run executed 18 methods and failed
five (`/tmp/opencode/f01-before.log`), including accepted alias comparisons,
directory protection, mixed reference ownership/scope and repeated-slash prefix
handling. A raw-key test fixture was subsequently corrected so every compared
candidate actually has a managed basename; no production residue gate was loosened.

Pure/recording-store tests cover all accepted alias forms and both comparison sides,
raw percent spelling/case, literal separators, `+` versus space, encoded spaces,
Unicode NFC/NFD, repeated slashes, dot segments and sibling directory boundaries.
The fake lister now parses input aliases but renders results as canonical `s3://`,
independently of the selection helper, matching the real producer rather than
accidentally echoing the catalog's scheme. A table/schema/catalog × three-scheme ×
dry-run/real matrix preserves live, absolute, dropped, scheduled and directory
references and checks the exact deletion set and authoritative root.

`DuckLakeOrphanStorageTest` uses the released JDBC catalog with real isolated
PostgreSQL metadata initialized by the pinned native DuckDB producer, plus the
actual production Java S3 list/delete implementation against a disposable MinIO.
Native data and position-delete files, a dropped table, mixed absolute/relative
schema/table/file references and scheduled rows participate. The wrong fallback
warehouse is deliberate. Real object ages, exact surviving object bytes and
structured `NoSuchKey` checks verify effects, not only metadata counts.
After native initialization/checkpoint/drop on local fixture storage, prepared SQL
updates **only the owned fixture's** path/relative flags and scheduled rows to
exercise alias/override cases; native-produced file bytes are uploaded to the
corresponding test object keys. ProcedureOps is called directly. This is not a
claim that a native S3 writer emitted every adversarial path shape, that uploaded
files were queried through a live FE/BE, or that full smoke was rerun on this ZIP.

The three scheme cases pass in **both final test JVMs**: each
preserves **15 exact objects** and deletes only **five old unreferenced managed
objects**. Dry run first preserves every object. Percent-encoded paths, plus/space,
encoded spaces and NFC/NFD keys are distinct real object controls; foreign and
young objects survive. The final full suite is the authoritative certification.

The fixture uses independently signed literal-key HTTP writes/read-back rather
than the SDK's path-folding object builder. The pinned MinIO *server* rejects raw
repeated-slash object names/prefixes with `XMinioInvalidObjectName`; those cannot be
honestly claimed as stored/round-tripped on this backend. They remain exact-key
unit cases. The dedicated live refusal test keeps the existing single-slash object,
demonstrates the SDK's read-side aliasing of the absent double-slash spelling, and
requires repeated/dot-segment deletes to fail without removing that live object.
Unsupported raw listing prefixes must throw, not be collapsed into a wider scan.

Failed development runs (`f01-focused*`/`f01-storage-probe.log`) remain failed
evidence: one fixture initially expected a non-managed basename to be reclaimed;
another exposed the SDK collapse, then the server's explicit raw-path rejection.
No network/auth failure is reinterpreted as object absence and no new skip is added.

## Final gates and delivery boundary

Authoritative final results are `/tmp/opencode/f01-final-results.json` and
`/tmp/opencode/f01-final-build.log`. **Java 25: 265 passed/one existing skip;
Java 17: 234 passed/one existing skip; zero failures/errors.** Both test tasks were
forced to execute freshly. The sole skip in each is unchanged
`DuckLakeScanPlanProviderTest.emitsDeletesForInlineDeletePath`; its source matches
the preceding committed version. No new skip or expected failure was introduced.

Kotlin/build/version hashes are unchanged before/after the run. All three real
catalog/S3 alias cases and the SDK deletion-refusal case execute in both JVMs.
Detekt, Java-17 runtime scanning of **24,630 classes**, packaging/identity validation
and dependency qualification pass. The seven unchanged build-logic tests are
UP-TO-DATE/reused, not falsely counted as fresh execution. The build completes in
2m12s; this is not a runtime workload benchmark. A temporary build init
script writes only the qualification ZIP/receipt under
`build/f01-qualification/distributions/`; the ordinary default ZIP/receipt and
unrelated Quack report must stay byte-identical. Candidate SDK/API/runtime dependency
closure must match the existing default; only provider payload identity may change.
Those closure/default-preservation checks pass. The qualification candidate is:

```text
build ID: doris-ducklake-v1-60ea4ed3e38357bb907a6dae37a0c121643eca79ca8ae5d0ba04d9a4103e4ec3
ZIP SHA256: e4acd4d501f920873a52a65892dcfc8373429e49993a5d5647f99a016a7b2bdf
```

`/tmp/opencode/f01-closeout-results.json` independently reconfirms source/result
bindings, exact fresh JVM counts and unchanged skip identity, package/API/SDK/
dependency closure and no remaining F01-owned MinIO containers. Frozen XMLs and
the checked candidate ZIP/receipt are retained in `/tmp/opencode/f01-final-evidence/`.
To repeat the JVM/storage regressions without building/promoting the default ZIP:

```bash
JAVA_HOME=/path/to/java-25 ./gradlew test --rerun java17PluginTest --rerun detekt verifyJava17Runtime
```

Use local Docker for the PostgreSQL fixture and the UUID-owned MinIO CLI fixture;
the latter uses `minio/minio:RELEASE.2025-01-20T14-49-07Z`, pulling only if absent.
No retained service is a test target.

The first temporary qualification-init attempt applied its packaging override to
the included build-logic project and failed before tests; it was scoped to the
root with the packaging task. That failed setup is retained in
`/tmp/opencode/f01-final-init-failure.log`, not counted as passing verification.

No retained FE/BE deployment, default distribution promotion, catalog migration,
corpus campaign, re-vendor, dependency upgrade or broader authorization/production
claim is performed. Disposable MinIO uses a UUID owner label, loopback dynamic port
and tmpfs, and cleanup verifies that owner before stopping only its own container.
The shared PostgreSQL test fixture owns its own test infrastructure. Testdata
directories/databases are unique to each fixture invocation, not adopted or swept.
