# Isolated R07 local trial

This is **qualification, not deployment or 1.1 enablement**. It does not use the
normal smoke driver (which includes writes/maintenance), the shared Trino
substrate, the shared Doris containers, or any shared FE/BE filesystem mount.
Only native fixture producers create disposable catalogs; Doris only reads them.
The public connector must reject final-1.1, including already-initialized MySQL
1.1; that is a Doris qualification limit, **not** a library 1.0-only read ceiling.

Prerequisites: the local API-13 FE/ASAN BE images at `dd456ab7a89`, Java 25 compiler,
Java 17 test runtime, Docker, and the immutable candidate repository. Verify image
IDs against `compose/README.md`. Ports 15487, 19087, 19387 and 18087 plus subnet
172.30.87.0/24 must be unused. Do not repurpose these settings for production.

Use an isolated `GRADLE_USER_HOME` daemon registry (it may link the existing caches,
wrapper and toolchains). Never set global `maven.repo.local`, stop other Gradle
daemons, or stop the shared services.

```sh
export GRADLE_USER_HOME=/tmp/opencode/doris-r07-gradle
export DORIS_R07_ROOT=/tmp/opencode/doris-r07
candidate=/tmp/opencode/catalog-r07-58ef4d7/maven-repository
mise exec -- ./gradlew verifyR07Candidate r07ReadQualificationTest pluginZip \
  -Pr07CatalogRepository="$candidate" --no-daemon --no-configuration-cache
```

Stage the ZIP into a **new, run-owned** `$DORIS_R07_ROOT/plugin` directory, preserving
its `lib/` layout. Compare the staged catalog JAR SHA-256 with the candidate
`SHA256SUMS`. Copy `compose/fe.conf` to `$DORIS_R07_ROOT/fe.conf` and append
`priority_networks = 172.30.87.0/24`. Do not overwrite another run's staging tree.

```sh
docker compose -f compose/r07-qualification.yml up -d
mise exec -- ./gradlew r07LiveQualificationTest \
  -Pr07CatalogRepository="$candidate" --no-daemon --no-configuration-cache
```

The live test allocates unique PostgreSQL databases and S3 prefixes and prints
their names. It verifies matched peers, actual S3-backed 1.0 scans (field IDs,
rename, initial default, SQL NULL/literal NULL, bytes, dates and nullable counts),
and rejection of native final-1.1 before scans. It neither implements CDC nor
claims a final-1.1 BE scan. A failure retains fixtures for investigation.

Run the maintained regression lanes separately from these explicit probes:

```sh
mise exec -- ./gradlew test java17PluginTest detekt verifyJava17Runtime \
  -Pr07CatalogRepository="$candidate" --no-daemon --no-configuration-cache
```

Omit the candidate property to return to 0.12.0 / JDBC 1.5.6.0. Nothing is published,
no corpus pin or semantic golden changes, and no native broad-corpus run follows.
The existing disabled legacy inline-delete test stays visible; the new probes
have no assumptions/disabled tests.

When finished, stop **only** this qualification project's services (without
deleting its containers/data or lake files):

```sh
docker compose -f compose/r07-qualification.yml stop
```

There is no physical-file lease, cleanup, migration, mixed-version negotiation,
1.1 writer or production approval in this harness.
