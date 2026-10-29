package dev.brikk.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Versioned, path-independent identity of the bytes the plugin owns, excluding its manifest. */
object PluginIdentity {
    const val SCHEMA = "doris-plugin-payload-v1"
    const val API_ATTRIBUTE = "Doris-Connector-Plugin-Api-Version"
    const val MANIFEST = "META-INF/MANIFEST.MF"
    const val PROVIDER_PACKAGE = "dev/brikk/ducklake/doris/plugin/"
    const val PROVIDER_CLASS = "${PROVIDER_PACKAGE}DuckLakeConnectorProvider.class"
    const val PROVIDER_SERVICE = "META-INF/services/org.apache.doris.connector.spi.ConnectorProvider"

    fun sha256(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sorted(entries: Map<String, String>): Map<String, String> = entries.toSortedMap { left, right ->
        java.util.Arrays.compareUnsigned(left.toByteArray(UTF_8), right.toByteArray(UTF_8))
    }

    fun validatePath(path: String) {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && path.none { it.code < 32 }) {
            "Noncanonical plugin path: $path"
        }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Noncanonical plugin path: $path" }
    }

    private fun records(kind: String, entries: Map<String, String>): String = buildString {
        sorted(entries).forEach { (path, hash) ->
            validatePath(path)
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $path" }
            append("$kind\t$path\t$hash\n")
        }
    }

    fun buildId(api: String, payload: Map<String, String>, runtime: Map<String, String>): String {
        require(api.matches(Regex("[0-9]+\\.[0-9]+"))) { "Invalid plugin API version: $api" }
        require(MANIFEST !in payload) { "The identity must not hash its own manifest" }
        val canonical = "$SCHEMA\nAPI\t$api\n" + records("P", payload) + records("D", runtime)
        return "doris-ducklake-v1-" + sha256(canonical.byteInputStream(UTF_8))
    }

    fun sdkId(sdk: Map<String, String>): String =
        sha256(("doris-compile-sdk-v1\n" + records("S", sdk)).byteInputStream(UTF_8))

    fun jarPayload(file: File): Map<String, String> = ZipFile(file).use { zip ->
        val result = linkedMapOf<String, String>()
        zip.entries().asSequence().filter { !it.isDirectory && it.name != MANIFEST }.forEach { entry ->
            validatePath(entry.name)
            require(result.put(entry.name, sha256(zip.getInputStream(entry))) == null) { "Duplicate entry: ${entry.name}" }
        }
        sorted(result)
    }

    fun verifyManifest(file: File, api: String, id: String) = ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry(MANIFEST)) { "Provider manifest missing" }
        val manifest = zip.getInputStream(entry).use { Manifest(it) }
        val allowed = setOf("manifest-version", API_ATTRIBUTE.lowercase(), "implementation-version")
        require(manifest.mainAttributes.keys.all { it.toString().lowercase() in allowed }) { "Unsupported provider manifest attributes" }
        require(manifest.mainAttributes.getValue(API_ATTRIBUTE) == api) { "Provider API mismatch" }
        require(manifest.mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION) == id) { "Provider identity mismatch" }
        manifest.entries.forEach { (name, attributes) ->
            require(name == PROVIDER_PACKAGE && attributes.keys.all { it == Attributes.Name.IMPLEMENTATION_VERSION }) {
                "Unsupported provider manifest section: $name"
            }
            require(attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION) == id) { "Conflicting provider package identity" }
        }
    }

    fun writeJson(file: File, value: Map<String, Any>) {
        file.parentFile.mkdirs()
        file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n", UTF_8)
    }

    fun readJson(file: File): Map<*, *> = JsonSlurper().parse(file, "UTF-8") as Map<*, *>
    fun stringMap(value: Any?): Map<String, String> = (value as Map<*, *>).entries.associate { it.key as String to it.value as String }
}

@CacheableTask
abstract class GeneratePluginIdentity : DefaultTask() {
    @get:Input abstract val apiVersion: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val payloadRoots: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val runtimeJars: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val compileSdkJars: ConfigurableFileCollection
    @get:OutputFile abstract val identityFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val payload = linkedMapOf<String, String>()
        payloadRoots.files.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(root).invariantSeparatorsPath
                require(name != PluginIdentity.MANIFEST) { "Input payload must not supply a manifest" }
                require(payload.put(name, PluginIdentity.sha256(file.inputStream())) == null) { "Duplicate payload path: $name" }
            }
        }
        require(PluginIdentity.PROVIDER_CLASS in payload && PluginIdentity.PROVIDER_SERVICE in payload) { "Provider payload missing" }
        fun jarHashes(files: Set<File>, prefix: String): Map<String, String> {
            val hashes = linkedMapOf<String, String>()
            files.forEach { file ->
                require(file.isFile && file.extension == "jar") { "Expected a JAR: $file" }
                val name = prefix + file.name
                require(hashes.put(name, PluginIdentity.sha256(file.inputStream())) == null) { "Duplicate JAR name: $name" }
            }
            return PluginIdentity.sorted(hashes)
        }
        val runtime = jarHashes(runtimeJars.files, "lib/")
        val sdk = jarHashes(compileSdkJars.files, "")
        require(sdk.isNotEmpty()) { "Compile SDK identity is missing" }
        PluginIdentity.writeJson(identityFile.get().asFile, linkedMapOf(
            "schema" to PluginIdentity.SCHEMA,
            "api_version" to apiVersion.get(),
            "build_id" to PluginIdentity.buildId(apiVersion.get(), payload, runtime),
            "payload_entries" to PluginIdentity.sorted(payload),
            "runtime_jars" to runtime,
            "compile_sdk" to sdk,
            "compile_sdk_id" to PluginIdentity.sdkId(sdk),
        ))
    }
}

@CacheableTask
abstract class PluginArtifactManifest : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val identityFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val providerJar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val pluginZip: RegularFileProperty
    @get:OutputFile abstract val receiptFile: RegularFileProperty

    @TaskAction
    fun attest() {
        val identity = PluginIdentity.readJson(identityFile.get().asFile)
        val api = identity["api_version"] as String
        val id = identity["build_id"] as String
        val jar = providerJar.get().asFile
        val archive = pluginZip.get().asFile
        require(PluginIdentity.jarPayload(jar) == PluginIdentity.stringMap(identity["payload_entries"])) { "Packaged payload differs" }
        PluginIdentity.verifyManifest(jar, api, id)
        val jarHash = PluginIdentity.sha256(jar.inputStream())
        val files = PluginIdentity.stringMap(identity["runtime_jars"]) + ("lib/${jar.name}" to jarHash)
        val actual = ZipFile(archive).use { zip ->
            val entries = linkedMapOf<String, String>()
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                require(entries.put(entry.name, PluginIdentity.sha256(zip.getInputStream(entry))) == null) { "Duplicate ZIP entry" }
            }
            entries
        }
        require(actual == files) { "Plugin ZIP file set/bytes differ from the attested bundle" }
        PluginIdentity.writeJson(receiptFile.get().asFile, linkedMapOf(
            "schema" to "doris-plugin-artifact-v1",
            "api_version" to api,
            "build_id" to id,
            "provider_jar" to "lib/${jar.name}",
            "provider_jar_sha256" to jarHash,
            "plugin_zip_sha256" to PluginIdentity.sha256(archive.inputStream()),
            "files" to PluginIdentity.sorted(files),
            "compile_sdk" to PluginIdentity.stringMap(identity["compile_sdk"]),
            "compile_sdk_id" to (identity["compile_sdk_id"] as String),
        ))
    }
}
