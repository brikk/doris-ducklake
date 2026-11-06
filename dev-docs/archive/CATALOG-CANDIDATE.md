# Published catalog candidate and bounded read probes

Default builds use released catalog/fixtures/replay/SLT **0.17.0** and native test
JDBC **2.0.0.1**, including the shared structured parser and replay regressions.
The historical opt-in experimental reader candidate still uses strict published coordinates
**0.13.0-20261101.082015-33**, including catalog test fixtures, replay and SLT, with
JDBC **2.0.0.0**. Normal Gradle resolution and the four producer JAR hashes are
checked; do not substitute local JARs, a mutable snapshot or mixed publications.

```sh
./gradlew -PcatalogReadCorrectnessCandidate=true \
  verifyR07Candidate test java17PluginTest check assemble qualificationDependencies
```

Candidate output is `build/catalog-read-correctness-33/`, unless an acceptance
init-script already selected private output. Default `build/distributions/` is
not promoted/overwritten by this property. The retired `r07CatalogRepository`
property deliberately refuses. Doris SDK selection remains the separate scoped
`-PdorisRepository=...` property; global Maven settings are not a catalog selector.

## What this lane means

Both lanes use the public structured type parser, preserving quoted nested
field names/order/DECIMAL signatures, with explicit empty/case-collision refusals.
Both lanes share the complete parent-aware native descriptor encoder. Current
native STRUCT/LIST/MAP values and NULL/literal-NULL distinctions are checked
independently; nested mapped files, nested history and final-1.1 nested scans refuse.
Release adoption shares this parser without promoting the experimental 1.1 reader.

Final-1.1 admission remains **off by default**. The candidate property alone does
not enable it: `experimental.final11.reads=true` is the separate bounded
PostgreSQL/S3 file-tier opt-in. `final11.history-window` defaults to 0 (at most 16).
Only explicitly qualified scalar native-file/current or bounded recent-history
shapes are admitted; unsupported mapping, inline, snapshot-filtering, defaults
and type cases refuse. All 1.1 writes/DDL/maintenance remain disabled.

The earlier API13 final-1.1 campaign and long-path producer outcomes are
[archived](archive/VALIDATION-R07-ADOPTION-2026-10-25.md). Current API27 qualification
proves the bounded native nested/replay/default-refusal checks, **not** a fresh full
1.1 campaign, all producer routes, distributed inline transport, CDC, physical
leases, end-to-end cancellation or production/performance readiness.

## Actual native read/replay acceptance

Use the exact [API27 runtime/images](DORIS-RUNTIME.md) and the isolated A/B recipe
in [COMPOSE.md](COMPOSE.md). Build candidate A and B with the candidate property
for **both**, using a different private B directory (e.g. `build/candidate-B`).
The released and candidate harnesses require different bundles:

```sh
# Build candidate A and private resource-only B; do not replace the default output.
./gradlew -PcatalogReadCorrectnessCandidate=true assemble verifyJava17Runtime
F24_ACCEPTANCE_PROJECT_ROOT="$PWD" \
F24_ACCEPTANCE_BUILD_DIR="$PWD/build/candidate-B" \
  ./gradlew -I compose/acceptance-build-B.init.gradle \
  -PcatalogReadCorrectnessCandidate=true assemble verifyJava17Runtime
```

```sh
# Released lane: quoted nested values, NULL parents and measured replay on both JVMs.
python3 compose/read_correctness_acceptance.py --released \
  --bundle-a build/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/f24-acceptance-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6 \
  --evidence-root /tmp/opencode/default-read-acceptance

# Candidate lane: quoted nested fields and measured Boolean/float/BLOB replay coverage.
python3 compose/read_correctness_acceptance.py \
  --bundle-a build/catalog-read-correctness-33/distributions/doris-ducklake-0.0.1-plugin.zip \
  --bundle-b build/candidate-B/distributions/doris-ducklake-0.0.1-plugin.zip \
  --duckdb /absolute/path/to/duckdb-1.5.6 \
  --evidence-root /tmp/opencode/candidate-read-acceptance
```

Create owned evidence-root directories before invoking. The runner allocates fresh
UUID infrastructure, runs its own 28-check supported smoke, seeds native PG/S3
fixtures and invokes explicit Java25/17 live tasks with private settings. Candidate
acceptance requires 2/2 positive mirrors per JVM and an intentionally wrong Boolean
hash failing before mirror allocation, followed by a valid floating mirror. Neither
a missing live XML nor skipped assumptions counts as success. It is authored bounded
coverage, not a full corpus run or a final-1.1 rollout.

Evidence: [current API27 qualification](VALIDATION-DORIS-API27-2026-11-02.md) and
[retained publication/parser intake](archive/VALIDATION-CATALOG-READ-CORRECTNESS-2026-11-01.md).
