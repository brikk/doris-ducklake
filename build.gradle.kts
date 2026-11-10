import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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

detekt {
    buildUponDefaultConfig = true
    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Error
    ignoreFailures = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src", "test/src")
}

// The Doris FE runs on JDK 17, so this emits 17 bytecode even though the toolchain (from
// buildlogic.kotlin.common) is 25.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    // The Doris SPI artifacts are built from apache/doris master and installed into ~/.m2
    // (dev-docs/DORIS-RUNTIME.md). -PdorisRepository lets a candidate pin be qualified without
    // overwriting them. Exclusive so nothing else can shadow org.apache.doris.
    exclusiveContent {
        forRepository {
            val candidate = providers.gradleProperty("dorisRepository")
            if (candidate.isPresent) maven { url = uri(candidate.get()) } else mavenLocal()
        }
        filter { includeGroup("org.apache.doris") }
    }
    mavenCentral()
}

val dorisVersion = "1.2-SNAPSHOT"

// Must match the Doris-Connector-Plugin-Api-Version the pinned FE expects (fe/fe-connector/pom.xml,
// <connector.plugin.api.version>); the FE rejects a plugin whose manifest major differs. The pinned
// FE/BE pair is recorded in dev-docs/DORIS-RUNTIME.md.
val connectorApiVersion = "30.0"

dependencies {
    // Provided by the FE's parent classloader at runtime.
    compileOnly("org.apache.doris:fe-connector-spi:$dorisVersion")
    compileOnly("org.apache.doris:fe-thrift:$dorisVersion")

    // Bundled: fe-core no longer ships the Iceberg SDK on the shared classpath (it moved into
    // fe-connector-iceberg's own loader), and the write sink needs SchemaParser/PartitionSpecParser.
    // Avro is excluded because the FE provides it and a child-first copy would shadow the host's.
    implementation("org.apache.iceberg:iceberg-api:1.10.1")
    implementation("org.apache.iceberg:iceberg-core:1.10.1") {
        exclude(group = "org.apache.avro", module = "avro")
    }

    // FE-provided; used by the dev-only inlined-data writer. The FE ships parquet 1.18.x while this
    // compiles against 1.17.0 — see prependParquetFormat for why tests must pin the order.
    compileOnly("org.apache.parquet:parquet-avro:1.17.0")
    compileOnly("org.apache.parquet:parquet-hadoop:1.17.0")
    compileOnly("org.apache.parquet:parquet-column:1.17.0")
    compileOnly("org.apache.avro:avro:1.12.1")
    compileOnly("org.apache.hadoop:hadoop-common:3.4.2")

    implementation(libs.ducklake.catalog)
    testImplementation(testFixtures(libs.ducklake.catalog))
    testImplementation(libs.ducklake.test.corpus.replay)

    // S3 client for FE-side blob deletes (cleanup_old_files / remove_orphan_files). The FE has no
    // usable filesystem handle for the warehouse, so the connector talks to object storage itself.
    implementation(libs.minio.client)

    // The FE classloader has no Postgres driver of its own.
    runtimeOnly(libs.postgres.jdbc)

    // Test-only DuckDB→Doris SQL transpiler for the corpus replay dialect gate. brikk-sql-verify
    // re-parses with Doris's real grammar when doris-fe.jar is on the classpath; it is inert otherwise.
    testImplementation(libs.brikk.sql)
    testImplementation(libs.brikk.sql.metadata)
    testImplementation(libs.brikk.sql.verify)

    testImplementation("org.apache.doris:fe-connector-spi:$dorisVersion")
    testImplementation("org.apache.doris:fe-thrift:$dorisVersion")
    testImplementation("org.apache.iceberg:iceberg-api:1.10.1")
    testImplementation("org.apache.iceberg:iceberg-core:1.10.1")
    testImplementation("org.apache.parquet:parquet-avro:1.17.0")
    testImplementation("org.apache.parquet:parquet-hadoop:1.17.0")
    testImplementation("org.apache.parquet:parquet-column:1.17.0")
    testImplementation("org.apache.avro:avro:1.12.1")
    testImplementation("org.apache.hadoop:hadoop-common:3.4.2")
    testImplementation("org.apache.hadoop:hadoop-mapreduce-client-core:3.4.2")

    testImplementation(libs.postgres.jdbc)
    // Bootstraps the DuckLake metadata schema in Postgres via DuckDB's postgres + ducklake extensions.
    testImplementation(libs.duckdb.jdbc)
    testCompileOnly("com.zaxxer:HikariCP:7.0.2")

    // MySQL-protocol driver the corpus replay adapter uses to reach the live compose FE.
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

// Host-provided SPI/Thrift/logging jars must not get a child-first duplicate in the plugin zip.
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
    apiVersion.set(connectorApiVersion)
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
        attributes("Doris-Connector-Plugin-Api-Version" to connectorApiVersion)
    }
    doFirst {
        manifest.attributes("Implementation-Version" to
            (PluginIdentity.readJson(pluginIdentity.get().identityFile.get().asFile)["build_id"] as String))
    }
}

// Flat lib/ layout, same shape as fe-connector-iceberg's plugin-zip assembly.
val pluginZip by tasks.registering(Zip::class) {
    dependsOn(tasks.jar)
    archiveBaseName.set("doris-ducklake")
    archiveClassifier.set("plugin")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.FAIL

    from(tasks.jar) { into("lib") }
    from(packagedRuntimeJars) { into("lib") }
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

// ---- Live-cluster tests (dev-docs/CORPUS-REPLAY.md) ----
// These need `compose/smoke.sh --up-only` and are kept out of `test`. The corpus mirror writes oracle
// files under a host dir bind-mounted into the BE at the same absolute path, so java.io.tmpdir is
// pinned there.
val corpusTmpDir = providers.gradleProperty("dorisCorpusDir").orElse("/tmp/ducklake-corpus")

// The ~/.m2 fe-thrift is a fat jar bundling a stale org.apache.parquet.format.* that shadows
// parquet-format-structures and breaks the inlined-data writer test. First on the classpath wins.
fun prependParquetFormat(base: FileCollection): FileCollection {
    val format = base.filter { it.name.startsWith("parquet-format-structures") }
    return format + base
}

val liveTestClasses = listOf(
    "**/DorisCorpusReplayTest*",
    "**/DuckLakeReadCorrectnessLiveTest*",
    "**/DuckLakeDefaultNestedLiveTest*",
    "**/DorisReadSafetyLiveTest*",
    "**/DorisCorpusBaselineTest*",
    "**/DuckLakePgBouncerTest*",
)

tasks.test {
    liveTestClasses.forEach { exclude(it) }
    classpath = prependParquetFormat(classpath)
}

fun registerTestTask(name: String, jvm: Int, pattern: String, description: String, configure: Test.() -> Unit = {}) =
    tasks.register<Test>(name) {
        this.description = description
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = prependParquetFormat(sourceSets.test.get().runtimeClasspath)
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jvm)) })
        useJUnitPlatform()
        include(pattern)
        outputs.upToDateWhen { false }
        configure()
    }

registerTestTask("java17PluginTest", 17, "**/plugin/*Test*",
    "Plugin tests at the FE Java 17 runtime; the brikk-sql corpus tooling requires Java 25.") {
    exclude("**/DuckLakePgBouncerTest*")
}

registerTestTask("pgBouncerSmokeTest", 17, "**/DuckLakePgBouncerTest*",
    "Opt-in PostgreSQL transaction-pooling smoke; no FE/BE.") {
    maxHeapSize = "512m"
    maxParallelForks = 1
}

for ((taskName, jvm) in listOf("defaultNestedLiveTest" to 25, "defaultNestedLiveJava17Test" to 17)) {
    registerTestTask(taskName, jvm, "**/DuckLakeDefaultNestedLiveTest*",
        "Native mixed scalar/nested reads on an owned API30 cluster.")
}

for ((taskName, jvm) in listOf("readCorrectnessLiveTest" to 25, "readCorrectnessLiveJava17Test" to 17)) {
    registerTestTask(taskName, jvm, "**/DuckLakeReadCorrectnessLiveTest*",
        "Bounded nested/replay/BOOLEAN-dictionary probes on an owned API30 cluster; no full corpus.")
}

registerTestTask("corpusReplayTest", 25, "**/DorisCorpusReplayTest*",
    "Mirrors upstream DuckLake corpus reads through a live compose Doris FE+BE.") {
    maxHeapSize = "2g"
    systemProperty("java.io.tmpdir", corpusTmpDir.get())
    systemProperty("ducklake.corpus.root", rootProject.projectDir.resolve("ducklake/test/sql").absolutePath)
    // Starter dirs by default; -Dducklake.corpus.dirs=all for the full corpus.
    System.getProperty("ducklake.corpus.dirs")?.let { systemProperty("ducklake.corpus.dirs", it) }
}

registerTestTask("readSafetyLiveTest", 25, "**/DorisReadSafetyLiveTest*",
    "FE/BE regression checks for historical-delete/temporal refusals and typed MAP replay.") {
    maxHeapSize = "768m"
    systemProperty("ducklake.corpus.root", rootProject.projectDir.resolve("ducklake/test/sql").absolutePath)
}

registerTestTask("corpusBaselineTest", 25, "**/DorisCorpusBaselineTest*",
    "Oracle-only corpus diagnostics; never counts as Doris mirror qualification.") {
    maxHeapSize = "1536m"
}
