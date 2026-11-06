# Doris upstream check — 2026-11-03

## Summary

At **2026-11-03 01:56 UTC**, master is
**7cca01bc6cd6596c6c5b6248ea7db6ab3dbde80f**, just **one commit** beyond our
qualified `4bc7f426828e4cedfd2b7ce9b76fe95b14b5b146` / API27 pin.
Delta:14 files,770 insertions /92 deletions.

[#69790](https://github.com/apache/doris/pull/69790), merged2026-11-03 01:05 UTC,
fixes `score()` for `MATCH` on untokenized/keyword inverted indexes. Such matches
previously scored0 while equivalent `SEARCH` expressions scored BM25. The source
now admits keyword MATCH scoring, collects the complete keyword term without
lowercasing/splitting its case/spaces, and skips term-statistics collection for
numeric BKD/missing VARIANT-path segments. Equality and IN remain filters; analyzed
index rules remain separate. This is native Doris inverted-index query work, not
an external DuckLake Parquet-reader correction.

## Integration impact

The complete changed-path list is restricted to native index reader/scoring code
and its tests/goldens. Exact Git-tree/blob comparisons show no change to connector
or filesystem interfaces, JNI sources, FE parent POM, Thrift/protobuf, V2 file
readers, external scan sources, BE execution-version definitions or third-party
requirements. Contracts therefore remain connector API27 /filesystem7 /JNI6 /
BE execution19, with the same current build-input requirements.

Latest GitHub stable release is still **4.1.5-release**, published2026-10-15.
The4.1 branch remains `dd72933ec78a8a4daf3e1d204a6ea96d6443ada7`;4.2 advances to
`d11b86e2c55cb37ee1d31699f29cd792430ad095`. Neither release branch is substituted
for the qualified master-SPI integration.

**Recommendation:** keep our qualified API27 pin for DuckLake. This delta matters
if testing native Doris keyword-index MATCH/score queries, but provides no direct
DuckLake native-file-read reason to repeat the re-vendor/qualification immediately.
No performance or runtime correctness claim for this new commit is inferred from
its merged status or upstream test descriptions.

## Scope and evidence

Review only: no source/dependency/manifest change, SDK installation, build, runtime
test, image, container restart, artifact promotion or deployment. No Duckbridge
message/dispatch/project note is sent. Existing Quack report remains untouched.

`build/verification/doris-upstream-2026-11-03/` retains the isolated shared-object
bare review repository, frozen refs, commit/path/stat data, production diff, exact
contract/tree comparisons, latest-release response and merged PR API response.
Existing project files/HEAD are fingerprinted before/after; only this review
report and ignored review evidence are added. No commit/push is performed.

## 4.2 clarification — subsequent check

The master-only one-commit summary does **not** mean the4.2 branch was unchanged.
While checking,4.2 advances further to **1897247bfdf78ec960b49ac9788f9eca064a75fb**:
**nine commits** beyond yesterday's `a623df21d75683c61a39ae830822850e6be0a7f0`
(40 files,1,448 insertions /102 deletions). Branch-log subjects show backports of:

- Brotli1.0.10 (#69937, original master #69906).
- Index-file sealing after non-vertical index compaction (#69936).
- `sum0` type coercion aligned with `sum` (#69940).
- Nested JSON insertion retaining following siblings (#69941).
- Delete-bitmap lock-inversion fix (#69947).
- SNII docid decoding, position counts, bulk BKD range-hit collection and
  container-at-a-time Roaring bitmap construction (#69955/#69956/#69957/#69958).

These are branch-log/source-location observations, not a full independent review
or fresh execution of those backports' tests. The extra branch commits are not
additional commits beyond our master pin; the two branch histories are distinct.

The annotated **4.2.0-rc02** tag exists, tagged2026-10-30, and peels to
`37c5abc6fe1dc5c4941b7cbbae6a671275e355ba`. It is a release candidate, not a newly
published final4.2 stable release. Both that RC2 tree and the new4.2 branch tip
still lack `fe/fe-connector/fe-connector-spi`; neither replaces this out-of-tree
master-SPI plugin's qualified API27 FE.

Evidence: `branch-4.2-update-commits.txt` and the isolated `review-4.2-latest` /
`review-4.2.0-rc02` refs. No project build, runtime or dependency is changed by
this clarification.
