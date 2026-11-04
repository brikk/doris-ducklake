import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.zip.ZipFile
import dev.brikk.buildlogic.GeneratePluginIdentity
import dev.brikk.buildlogic.PluginArtifactManifest
import dev.brikk.buildlogic.PluginIdentity
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("buildlogic.kotlin.library")
    id("buildlogic.kotlin.brikk")
    alias(libs.plugins.detekt)
}

group = "dev.brikk.ducklake"
version = "0.0.1"

// Idiomatic-Kotlin quality gate (detekt 2.0; jvmTarget read from Kotlin compilerOptions), same as
// :ducklake-catalog / :trino-ducklake. Custom src + test/src layout; Doris SPI is compileOnly.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src", "test/src", "test/released",
        if (providers.gradleProperty("catalogReadCorrectnessCandidate").orNull == "true") "read-tiers/r07" else "read-tiers/released")
}

// Doris FE pins to JDK 17, so emit JDK 17 bytecode/ABI — but compile with the project-wide
// JDK 25 toolchain (from buildlogic.kotlin.common). Now that this module is Kotlin, the Kotlin
// compiler emits 17 too (jvmTarget below), so there is NO per-module JDK-17 toolchain anymore —
// the whole tree builds on one toolchain (25). Same approach as :ducklake-catalog.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Main AND tests emit JVM 17 (built on the tree-wide JDK 25 toolchain) — 17 is
// the Doris FE ABI, and running tests at 17 matches how the plugin runs inside
// the FE. The corpus replay module (:ducklake-corpus-replay) also targets 17,
// so the test classpath resolves it with no target-JVM conflict.
//
// Test-classpath ordering: the ~/.m2 fe-thrift (from `mvn install -P flatten`)
// is a FAT jar that also bundles incompatible `org.apache.parquet.format.*`
// (a PageHeader without `setData_page_header`), which shadows the real
// parquet-format-structures-1.17.0 and breaks the inlined-data writer test with
// a NoSuchMethodError. The API-24 FE ALSO has a fat fe-thrift, but its
// classpath places parquet-format-structures-1.18.1 before it. Tests explicitly
// place their real 1.17.0 first — see prependParquetFormat. That runtime/test
// version difference is recorded by the re-vendor validation, not silently upgraded.

// The Doris fe-connector-api / spi artifacts are built from apache/doris `master` (the
// connector SPI merged upstream via #64304) and installed into ~/.m2 — see
// dev-docs/DORIS-RUNTIME.md. Master's <revision> is 1.2-SNAPSHOT, so the
// coordinates below are unchanged. A pin-specific repository can qualify a new
// baseline without overwriting the current mavenLocal artifacts. Both choices
// are scoped to org.apache.doris so they can't shadow other dependencies.
// dev.brikk.house (brikk-sql transpiler) resolves from the Central Portal snapshots repo,
// wired centrally by the buildlogic.kotlin.brikk convention plugin (applied above).
repositories {
    // org.apache.doris: FE SPI built from apache/doris master, installed into ~/.m2.
    exclusiveContent {
        forRepository {
            val candidate = providers.gradleProperty("dorisRepository")
            if (candidate.isPresent) maven { url = uri(candidate.get()) } else mavenLocal()
        }
        filter { includeGroup("org.apache.doris") }
    }
    // dev.brikk.ducklake catalog + corpus-replay: released versions resolve from mavenCentral;
    // -SNAPSHOTs from the Central Portal snapshots repo (wired in buildlogic.kotlin.brikk).
    mavenCentral()
}

val dorisVersion = "1.2-SNAPSHOT"

// Historical experimental reader candidate, separate from the released 0.14.0 default.
// Do not use maven.repo.local: Doris's exclusive FE/SPI repository above must stay intact.
val readCorrectnessCandidate = providers.gradleProperty("catalogReadCorrectnessCandidate").orNull == "true"
check(!providers.gradleProperty("r07CatalogRepository").isPresent) {
    "Local R07 repositories are retired; use -PcatalogReadCorrectnessCandidate=true for the published pinned candidate"
}
val r07Version = "0.13.0-20261101.082015-33"
val candidateModules = setOf("ducklake-catalog", "slt-format", "ducklake-test-corpus-replay")
kotlin.sourceSets.main { kotlin.srcDir(if (readCorrectnessCandidate) "read-tiers/r07" else "read-tiers/released") }
kotlin.sourceSets.test { kotlin.srcDir(if (readCorrectnessCandidate) "test/r07" else "test/released") }
if (readCorrectnessCandidate) {
    // Honor an already isolated acceptance init-script output, never replace the default ZIP.
    if (layout.buildDirectory.get().asFile == layout.projectDirectory.dir("build").asFile) {
        layout.buildDirectory.set(layout.projectDirectory.dir("build/catalog-read-correctness-33"))
    }
    repositories.exclusiveContent {
        forRepository {
            repositories.maven {
                name = "r07CatalogCandidate"
                url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                metadataSources { gradleMetadata(); mavenPom() }
            }
        }
        filter {
            candidateModules.forEach {
                includeModule("dev.brikk.ducklake", it)
            }
        }
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.duckdb" && requested.name == "duckdb_jdbc") {
                useVersion("2.0.0.0")
            }
        }
    }
}

val verifyR07Candidate by tasks.registering {
    group = "verification"
    description = "Attests published read-correctness candidate coordinates and all four JARs."
    doLast {
        check(readCorrectnessCandidate) { "Set -PcatalogReadCorrectnessCandidate=true" }
        val expected = mapOf(
            "ducklake-catalog-$r07Version.jar" to "01ffed5abb0b970b307dd4de1b7d7ab42320e33016447896204e5a36506a3041",
            "ducklake-catalog-$r07Version-test-fixtures.jar" to "ca33bee56b33703ea7bbe7b76153bbaccc0275478fc992d48768eb912a1a056f",
            "ducklake-test-corpus-replay-$r07Version.jar" to "bd97eba965f2234faa75cf27a788c45a70d4f030115e51b7506104267a9899b1",
            "slt-format-$r07Version.jar" to "6406d8fdf2ed16c7fd59465fbeeeb5d1f6e06274357bfd4b3516f779d2bfc409",
            "duckdb_jdbc-2.0.0.0.jar" to "5170daa90a43296705127ef3c80834ba1cd275ec9d765a9f7eee346ab0087186",
        )
        val resolved = configurations.testRuntimeClasspath.get().files
        val identities = listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").flatMap { name ->
            configurations.getByName(name).incoming.artifacts.artifacts.mapNotNull { artifact ->
                val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                if (id?.group == "dev.brikk.ducklake" && id.module in candidateModules) {
                    check(id.displayName.endsWith(":0.13.0-SNAPSHOT:20261101.082015-33")) {
                        "Mixed candidate component on $name: $id"
                    }
                    "$name ${id.displayName} ${artifact.file.name} ${artifact.variant.displayName}"
                } else null
            }
        }
        expected.forEach { (name, hash) ->
            val jar = resolved.single { it.name == name }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(jar.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == hash) { "Candidate checksum mismatch: $jar ($actual)" }
            println("R07 SHA256 $actual $jar")
        }
        val report = layout.buildDirectory.file("reports/catalog-candidate-identity.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(identities.sorted().joinToString("\n", postfix = "\n"))
    }
}

tasks.withType<Test>().configureEach {
    if (readCorrectnessCandidate) {
        dependsOn(verifyR07Candidate)
        systemProperty("doris.r07", "true")
    }
}

dependencies {
    // FE supplies this via the parent classloader at runtime — compile-only.
    // #66407 merged fe-connector-api INTO fe-connector-spi (the whole plugin contract —
    // types a plugin implements AND the engine services it consumes — now lives in one
    // module, Trino-style; the `org.apache.doris.connector.api.*` packages were renamed to
    // `…spi.*`). So the single fe-connector-spi artifact carries everything now.
    compileOnly("org.apache.doris:fe-connector-spi:$dorisVersion")
    // fe-thrift is also FE-supplied; we touch it from populateRangeParams to
    // build TIcebergFileDesc per sanity-check §2.1 Option A. Stays compileOnly
    // so the plugin jar does NOT ship a second copy of the thrift classes.
    compileOnly("org.apache.doris:fe-thrift:$dorisVersion")
    // Iceberg schema serialization for the write-sink schema_json (SchemaParser /
    // PartitionSpecParser / Types, mirroring native IcebergTableSink).
    //
    // BUNDLED in the plugin zip (child-first), NOT compileOnly. Until the
    // 2026-07-22 baseline (branch-catalog-spi #65893 "strip residual iceberg/hive/hudi
    // deps from fe-core") the FE shipped the iceberg SDK on the shared classpath, so
    // compileOnly sufficed. #65893 moved the iceberg SDK into fe-connector-iceberg's
    // OWN plugin classloader, so fe-core no longer supplies it to us → INSERT/CTAS hit
    // NoClassDefFoundError: org.apache.iceberg.types.Types$IntegerType at write-plan
    // time. We now own our copy, exactly like fe-connector-iceberg owns its SDK
    // (child-first, isolated per plugin classloader). Excludes below drop the
    // transitives fe-core still provides (avro/parquet/guava/slf4j) to avoid shadowing.
    implementation("org.apache.iceberg:iceberg-api:1.10.1")
    implementation("org.apache.iceberg:iceberg-core:1.10.1") {
        // Avro is FE-provided (our parquet-avro inlined-writer path uses the FE's copy);
        // iceberg's SchemaParser/PartitionSpecParser/Types don't need it. Don't bundle a
        // second child-first Avro that could shadow the host's across the SPI boundary.
        exclude(group = "org.apache.avro", module = "avro")
    }
    // Inlined-data reads (DuckLakeInlinedParquetWriter): the FE synthesizes a
    // temp Parquet from catalog-inlined rows so the BE can scan them. Written
    // with parquet-avro (field_id-carrying). Compile/test pins stay 1.17.0;
    // API-24 FE core Parquet is 1.18.1. Its experimental inline-writer classpath
    // is not qualified by the native-file re-vendor (see validation report).
    // compileOnly so the plugin
    // jar ships no second copy of parquet/avro/hadoop.
    compileOnly("org.apache.parquet:parquet-avro:1.17.0")
    compileOnly("org.apache.parquet:parquet-hadoop:1.17.0")
    compileOnly("org.apache.parquet:parquet-column:1.17.0")
    compileOnly("org.apache.avro:avro:1.12.1")
    compileOnly("org.apache.hadoop:hadoop-common:3.4.2")

    if (readCorrectnessCandidate) {
        implementation("dev.brikk.ducklake:ducklake-catalog:$r07Version") { version { strictly(r07Version) } }
        testImplementation(testFixtures("dev.brikk.ducklake:ducklake-catalog:$r07Version"))
        testImplementation("dev.brikk.ducklake:ducklake-test-corpus-replay:$r07Version") { version { strictly(r07Version) } }
        constraints {
            testImplementation("dev.brikk.ducklake:slt-format:$r07Version") { version { strictly(r07Version) } }
        }
    } else {
        implementation(libs.ducklake.catalog)
        testImplementation(testFixtures(libs.ducklake.catalog))
        testImplementation(libs.ducklake.test.corpus.replay)
    }

    // S3-compatible object-store client for FE-side warehouse blob ops (cleanup_old_files:
    // physically delete files that expire_snapshots scheduled). The connector talks to blob
    // storage itself using the vended s3.* credentials — the sanctioned path, since the FE has no
    // usable Doris FileSystem handle and must NOT assume local-drive access across nodes (the
    // warehouse is S3/MinIO). Bundled child-first in the plugin zip; MinIO's client also drives
    // AWS S3 / OSS / COS / OBS. See dev-docs research on the connector storage abstraction.
    implementation(libs.minio.client)

    // Bundled in the plugin zip; FE classloader has no Postgres driver of its own.
    runtimeOnly(libs.postgres.jdbc)

    // brikk-sql: DuckDB→Doris SQL transpiler for the corpus-replay dialect gate
    // (transpile-first: run what transpiles + is mappable, engine-skip the rest).
    // TEST-ONLY — the production plugin never sees raw SQL (Doris parses it).
    // The `-jvm` KMP artifact; brikk-sql-metadata-jvm declared explicitly (matching :trino-ducklake)
    // rather than relying on transitive resolution of the KMP metadata artifact.
    testImplementation(libs.brikk.sql)
    testImplementation(libs.brikk.sql.metadata)
    // Native-grammar verifier (belt-and-braces after certify): re-parses the
    // transpiled SQL under Doris's real grammar so a brikk-sql emission bug is a
    // skip+report, not a runtime failure. DorisVerifier.createOrNull() reflects
    // into the Doris parser and returns null when it's absent — so this is INERT
    // until doris-fe.jar (the FE fat jar, not on Central) is on the test
    // classpath. Not forced today: the corpus already re-validates grammar by
    // executing on the live FE, and the fat jar risks shadowing the module's
    // carefully-ordered fe-thrift/parquet test classpath. See DorisCorpusDialect.
    testImplementation(libs.brikk.sql.verify)

    // SPI types are compileOnly above; tests instantiate the plugin so they need them too.
    // (junit/assertj/kotlin-test come from buildlogic.kotlin.common.)
    // fe-connector-spi now carries the whole contract (#66407 merged fe-connector-api in).
    testImplementation("org.apache.doris:fe-connector-spi:$dorisVersion")
    // Parity test serializes TFileRangeDesc with TSerializer, so thrift classes
    // must be on the test runtime classpath.
    testImplementation("org.apache.doris:fe-thrift:$dorisVersion")
    // Write-sink schema tests round-trip schema_json through iceberg's own
    // SchemaParser (the independent oracle), so iceberg is needed at test runtime.
    testImplementation("org.apache.iceberg:iceberg-api:1.10.1")
    testImplementation("org.apache.iceberg:iceberg-core:1.10.1")
    // Inlined-data writer tests read the written Parquet back to assert
    // field_ids/types/values, so parquet-avro + hadoop are on the test runtime.
    testImplementation("org.apache.parquet:parquet-avro:1.17.0")
    testImplementation("org.apache.parquet:parquet-hadoop:1.17.0")
    testImplementation("org.apache.parquet:parquet-column:1.17.0")
    testImplementation("org.apache.avro:avro:1.12.1")
    testImplementation("org.apache.hadoop:hadoop-common:3.4.2")
    // parquet-hadoop's ExampleParquetWriter/GroupWriteSupport reference
    // hadoop-mapreduce input/output format classes; FE-supplied at runtime.
    testImplementation("org.apache.hadoop:hadoop-mapreduce-client-core:3.4.2")

    // Shared Testcontainer fixture lives in the catalog's java-test-fixtures source set,
    // published as the -test-fixtures variant of dev.brikk.ducklake:ducklake-catalog.
    testImplementation(libs.postgres.jdbc)
    // Used by tests to bootstrap the DuckLake metadata schema in Postgres via DuckDB's
    // postgres + ducklake extensions — same pattern as :ducklake-catalog and :trino-ducklake.
    testImplementation(libs.duckdb.jdbc)

    // Corpus replay adapter (DorisReplayEngine): the runner seam + the mysql-protocol
    // driver the adapter uses to reach the live compose FE. Test-only; the corpus
    // test itself runs via the dedicated :doris-ducklake:corpusReplayTest task.
    testRuntimeOnly("com.mysql:mysql-connector-j:9.5.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val verifyJava17Runtime by tasks.registering {
    description = "Checks main classes and every runtime JAR for the Doris Java 17 classfile floor."
    group = "verification"
    dependsOn(tasks.classes)
    doLast {
        fun major(bytes: ByteArray): Int = ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255)
        var classes = 0
        val lines = mutableListOf<String>()
        sourceSets.main.get().output.classesDirs.files.filter { it.exists() }.forEach { directory ->
            directory.walkTopDown().filter { it.extension == "class" }.forEach {
                val version = major(it.readBytes())
                check(version <= 61) { "Java >17 class: $it ($version)" }
                classes++
            }
        }
        configurations.runtimeClasspath.get().files.filter { it.extension == "jar" }.forEach { jar ->
            var maximum = 0
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                    val mrVersion = Regex("META-INF/versions/(\\d+)/.*").matchEntire(entry.name)
                        ?.groupValues?.get(1)?.toInt()
                    if (mrVersion == null || mrVersion <= 17) {
                        val version = zip.getInputStream(entry).use { major(it.readNBytes(8)) }
                        check(version <= 61) { "Java >17 class: $jar!${entry.name} ($version)" }
                        maximum = maxOf(maximum, version)
                        classes++
                    }
                }
            }
            lines.add("${jar.name} max=$maximum")
        }
        lines.add("Java17 runtime PASS: $classes classes")
        val report = layout.buildDirectory.file("reports/java17-runtime.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(lines.joinToString("\n", postfix = "\n"))
        println(lines.last())
    }
}

tasks.check { dependsOn(verifyJava17Runtime) }

tasks.register("qualificationDependencies") {
    description = "Records exact runtime and test-runtime coordinates for reversible qualification comparisons."
    group = "verification"
    doLast {
        val report = layout.buildDirectory.file("reports/qualification-dependencies.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(listOf("runtimeClasspath", "testRuntimeClasspath").joinToString("\n") { name ->
            val artifacts = configurations.getByName(name).resolvedConfiguration.resolvedArtifacts
                .map { "${it.moduleVersion.id} ${it.file.name}" }.sorted()
            "[$name]\n" + artifacts.joinToString("\n", postfix = "\n")
        })
        println("Resolved dependency report: $report")
    }
}

// #66211 (doris branch-catalog-spi 88abe41a4e3, "replace the always-true connector apiVersion()
// with a manifest-carried API version gate for all four plugin families"): the FE's ApiVersionGate
// now REJECTS any directory-loaded connector plugin whose factory-defining jar does not declare a
// matching Doris-Connector-Plugin-Api-Version MANIFEST main attribute (fail-closed — absent ==
// rejected). The gate reads this attribute from the jar that defines our ConnectorProvider factory
// class (DirectoryPluginRuntimeManager.readManifestMainAttribute via ManifestVersions.jarOf), i.e.
// THIS jar, and compares MAJOR only against the kernel resource
// /META-INF/doris/connector-plugin-api-version.properties (api.version) baked into fe-connector-spi.
// Master's <connector.plugin.api.version> (fe/fe-connector/pom.xml) has stepped 1 -> 5 (#66407
// api-into-spi merge) -> 6 (#66413 forward-port Variant/Parquet + new connector capability defaults
// isWritesDataFiles/getBeExecVersion/canServeMetadataOnlyCount) -> 7 (#67182 adds the public
// SUPPORTS_STORAGE_PREDICATE_PRUNING enum constant) -> 8 (#67904 adds retained-schema state to
// ConnectorMvccSnapshot) -> 9 (#68027 Hive OpenCSV) -> 10 (#66530 statement scan reuse)
// -> 11 (#68320 generic write/DML contracts) -> 12 (#66399 Fluss scan capabilities)
// -> 13 (#68786 binary/UUID/timestamp contracts) -> 24 (#69298 query task scheduling).
// Qualified API27 pin: 4bc7f426828; matching FE/ASAN-BE evidence is indexed in dev-docs/DORIS-RUNTIME.md.
// DuckLake retains INSERT-only writes and its existing type mappings; new opt-ins stay off.
// Keep this equal to the pinned FE/SPI major. FE and BE must upgrade together: #68785/#68822
// realigned Thrift field ids and are not compatible with an older master runtime pair.
// One exclusion set for BOTH the payload identity and the ZIP: host-provided
// SPI/Thrift/logging must not acquire a child-first duplicate across the ABI boundary.
val hostJarPrefixes = listOf(
    "fe-connector-api-", "fe-connector-spi-", "fe-extension-spi-", "fe-filesystem-api-", "fe-thrift-",
    "libthrift-", "log4j-api-", "log4j-core-", "log4j-slf4j2-impl-", "log4j-1.2-api-", "slf4j-api-",
)
val packagedRuntimeJars = configurations.runtimeClasspath.get().filter { file ->
    hostJarPrefixes.none { file.name.startsWith(it) }
}
val pluginIdentity by tasks.registering(GeneratePluginIdentity::class) {
    group = "build"
    description = "Fingerprints effective plugin payload/runtime bytes without Git/mtime or self-hash cycles."
    dependsOn(tasks.classes)
    apiVersion.set("27.0")
    payloadRoots.from(sourceSets.main.get().output)
    runtimeJars.from(packagedRuntimeJars)
    compileSdkJars.from(configurations.compileClasspath.get().incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && it.group == "org.apache.doris" }
    }.files)
    identityFile.set(layout.buildDirectory.file("plugin-identity/payload.json"))
}

tasks.jar {
    dependsOn(pluginIdentity)
    inputs.file(pluginIdentity.flatMap { it.identityFile }).withPathSensitivity(PathSensitivity.NONE)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes("Doris-Connector-Plugin-Api-Version" to "27.0")
    }
    doFirst {
        manifest.attributes("Implementation-Version" to
            (PluginIdentity.readJson(pluginIdentity.get().identityFile.get().asFile)["build_id"] as String))
    }
}

// Plugin zip mirroring fe-connector-iceberg/src/main/assembly/plugin-zip.xml.
// Produces a flat lib/ layout containing this jar + every runtime dep that
// the FE parent classloader doesn't already supply. The excludes track the
// iceberg reference (api/spi/extension-spi/filesystem-api/logging) plus
// fe-thrift, which is provided by fe-core at runtime.
val pluginZip by tasks.registering(Zip::class) {
    if (readCorrectnessCandidate) dependsOn(verifyR07Candidate)
    dependsOn(tasks.jar)
    archiveBaseName.set("doris-ducklake")
    archiveClassifier.set("plugin")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(tasks.jar) { into("lib") }
    from(packagedRuntimeJars) {
        into("lib")
    }
}

val pluginArtifactManifest by tasks.registering(PluginArtifactManifest::class) {
    group = "verification"
    description = "Checks packaged provider identity and records final ZIP/JAR/SDK hashes beside the ZIP."
    dependsOn(pluginZip)
    identityFile.set(pluginIdentity.flatMap { it.identityFile })
    providerJar.set(tasks.jar.flatMap { it.archiveFile })
    pluginZip.set(tasks.named<Zip>("pluginZip").flatMap { it.archiveFile })
    receiptFile.set(layout.buildDirectory.file("distributions/doris-ducklake-${project.version}-plugin.identity.json"))
}

tasks.assemble {
    dependsOn(pluginArtifactManifest)
}

tasks.check {
    dependsOn(pluginArtifactManifest, gradle.includedBuild("build-logic").task(":test"))
}

// ---- Corpus replay (dev-docs/DESIGN-corpus-replay-adapter.md) ----
// The corpus mirror needs a LIVE compose cluster (`compose/smoke.sh --up-only`)
// and writes oracle data files under a host dir bind-mounted into the BE at
// the SAME absolute path (compose mounts ${DORIS_CORPUS_DIR:-/tmp/ducklake-corpus}).
// java.io.tmpdir is pinned there so DuckDbOracle's per-file temp dirs land
// inside the mount — that's how a host-side oracle and a containerized BE read
// the same lake. Kept OUT of the normal `test` task (no cluster in CI).
val corpusTmpDir = providers.gradleProperty("dorisCorpusDir").orElse("/tmp/ducklake-corpus")

// Reorders the real parquet-format-structures ahead of the fat m2 fe-thrift on
// a test classpath, so its (correct) org.apache.parquet.format.* classes win
// over fe-thrift's stale bundled copy. First-on-classpath wins for duplicate
// classes. See the header note above.
fun prependParquetFormat(base: FileCollection): FileCollection {
    val format = base.filter { it.name.startsWith("parquet-format-structures") }
    return format + base
}

tasks.test {
    exclude("**/DorisCorpusReplayTest*")
    exclude("**/DuckLakeR07QualificationTest*")
    exclude("**/DuckLakeR07LiveQualificationTest*")
    exclude("**/DuckLakeR07LongPartitionNativeTest*")
    exclude("**/DuckLakeReadCorrectnessLiveTest*")
    exclude("**/DuckLakeDefaultNestedLiveTest*")
    exclude("**/DorisReadSafetyLiveTest*")
    exclude("**/DorisCorpusBaselineTest*")
    classpath = prependParquetFormat(classpath)
}

tasks.register<Test>("java17PluginTest") {
    description = "Plugin tests at the FE Java 17 runtime; test-only brikk-sql corpus tooling requires Java 25."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    useJUnitPlatform()
    include("**/plugin/*Test*")
    exclude("**/DuckLakeR07QualificationTest*")
    exclude("**/DuckLakeR07LiveQualificationTest*")
    exclude("**/DuckLakeR07LongPartitionNativeTest*")
}

for ((taskName, jvm) in listOf("defaultNestedLiveTest" to 25, "defaultNestedLiveJava17Test" to 17)) {
    tasks.register<Test>(taskName) {
        description = "Current native mixed scalar/nested reads on explicitly owned default API27/catalog0.14 infrastructure."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jvm)) })
        useJUnitPlatform()
        include("**/DuckLakeDefaultNestedLiveTest*")
        doFirst { check(!readCorrectnessCandidate) { "Default-lane test must not select the candidate" } }
        outputs.upToDateWhen { false }
    }
}

for ((taskName, jvm) in listOf("readCorrectnessLiveTest" to 25, "readCorrectnessLiveJava17Test" to 17)) {
    tasks.register<Test>(taskName) {
        description = "Bounded N03/H15/N19 probes on explicitly owned API27 infrastructure; no full corpus."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jvm)) })
        useJUnitPlatform()
        include("**/DuckLakeReadCorrectnessLiveTest*")
        outputs.upToDateWhen { false }
    }
}
if (readCorrectnessCandidate) {
    tasks.register<Test>("r07ReadQualificationTest") {
        description = "Targeted native R07 consumer probes; no corpus campaign or deployment."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        useJUnitPlatform()
        include("**/DuckLakeR07QualificationTest*")
        outputs.upToDateWhen { false }
    }
    tasks.register<Test>("r07LiveQualificationTest") {
        description = "Read-only connector probes against compose/r07-qualification.yml (explicit local trial)."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        useJUnitPlatform()
        include("**/DuckLakeR07LiveQualificationTest*")
        outputs.upToDateWhen { false }
    }
    tasks.register<Test>("r07LongPartitionNativeTest") {
        description = "Strict long-path native producer gate; failures remain visible, not connector acceptance."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
        useJUnitPlatform()
        include("**/DuckLakeR07LongPartitionNativeTest*")
        outputs.upToDateWhen { false }
    }
}

val corpusReplayTest by tasks.registering(Test::class) {
    description = "Mirrors upstream DuckLake corpus reads through a live compose Doris FE+BE."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
    useJUnitPlatform()
    include("**/DorisCorpusReplayTest*")
    maxHeapSize = "2g"
    systemProperty("java.io.tmpdir", corpusTmpDir.get())
    systemProperty(
        "ducklake.corpus.root",
        rootProject.projectDir.resolve("ducklake/test/sql").absolutePath,
    )
    // Directory selection mirrors the runner module: starter dirs by default,
    // "-Dducklake.corpus.dirs=all" for the full corpus.
    System.getProperty("ducklake.corpus.dirs")?.let { systemProperty("ducklake.corpus.dirs", it) }
    outputs.upToDateWhen { false } // always re-run against the live cluster
}

tasks.register<Test>("readSafetyLiveTest") {
    description = "Owned FE/BE regression checks for historical-delete/temporal refusals and typed MAP replay."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    useJUnitPlatform()
    include("**/DorisReadSafetyLiveTest*")
    maxHeapSize = "768m"
    systemProperty("ducklake.corpus.root", rootProject.projectDir.resolve("ducklake/test/sql").absolutePath)
    doFirst { check(!readCorrectnessCandidate) { "Read-safety live checks require the released lane" } }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("corpusBaselineTest") {
    description = "Explicit oracle-only corpus diagnostics; never counts as Doris mirror qualification."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    useJUnitPlatform()
    include("**/DorisCorpusBaselineTest*")
    maxHeapSize = "1536m"
    doFirst { check(!readCorrectnessCandidate) { "Baseline diagnostics require the released replay lane" } }
    outputs.upToDateWhen { false }
}
