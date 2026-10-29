package dev.brikk.buildlogic

import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PluginIdentityTest {
    @TempDir lateinit var directory: Path

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun canonicalVectorIgnoresMapInsertionOrderButIncludesPathsAndApi() {
        val payload = linkedMapOf("z.class" to b, "a.class" to a)
        val runtime = mapOf("lib/example.jar" to a)
        val canonical = "doris-plugin-payload-v1\nAPI\t24.0\n" +
            "P\ta.class\t$a\nP\tz.class\t$b\nD\tlib/example.jar\t$a\n"
        val expected = "doris-ducklake-v1-" + PluginIdentity.sha256(canonical.byteInputStream())
        assertThat(PluginIdentity.buildId("24.0", payload, runtime)).isEqualTo(expected)
        assertThat(PluginIdentity.buildId("24.0", payload.entries.reversed().associate { it.toPair() }, runtime))
            .isEqualTo(expected)
        assertThat(PluginIdentity.buildId("25.0", payload, runtime)).isNotEqualTo(expected)
    }

    @Test
    fun compiledResourceAndDependencyChangesAlterIdentityWithoutAnyGitInput() {
        val baseline = PluginIdentity.buildId("24.0", mapOf("x.class" to a, "setting.txt" to a), mapOf("lib/x.jar" to a))
        for ((payload, runtime) in listOf(
            mapOf("x.class" to b, "setting.txt" to a) to mapOf("lib/x.jar" to a),
            mapOf("x.class" to a, "setting.txt" to b) to mapOf("lib/x.jar" to a),
            mapOf("x.class" to a, "setting.txt" to a) to mapOf("lib/x.jar" to b),
            mapOf("renamed.class" to a, "setting.txt" to a) to mapOf("lib/x.jar" to a),
        )) assertThat(PluginIdentity.buildId("24.0", payload, runtime)).isNotEqualTo(baseline)
        assertThat(PluginIdentity.sdkId(mapOf("sdk.jar" to a)))
            .isNotEqualTo(PluginIdentity.sdkId(mapOf("sdk.jar" to b)))
    }

    @Test
    fun sortingUsesUtf8RatherThanPlatformOrUtf16Order() {
        val entries = linkedMapOf("\uD800\uDC00.class" to a, "\uE000.class" to b)
        assertThat(PluginIdentity.sorted(entries).keys).containsExactly("\uE000.class", "\uD800\uDC00.class")
    }

    @Test
    fun manifestCannotBeItsOwnFingerprintInputAndPathsAreUnambiguous() {
        assertThatThrownBy { PluginIdentity.buildId("24.0", mapOf(PluginIdentity.MANIFEST to a), emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        for (path in listOf("../x", "/absolute", "a//b", "a\\b", "a\tb", "a\nb")) {
            assertThatThrownBy { PluginIdentity.validatePath(path) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun foldedImplementationVersionAndMatchingPackageSectionAreAccepted() {
        val id = "doris-ducklake-v1-" + a
        val jar = manifestJar(id, id)
        PluginIdentity.verifyManifest(jar.toFile(), "24.0", id)
        val manifest = java.util.zip.ZipFile(jar.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry(PluginIdentity.MANIFEST)).bufferedReader().readText()
        }
        assertThat(manifest).contains("\r\n ")
    }

    @Test
    fun conflictingPackageSectionCannotOverrideTheExpectedMainIdentity() {
        val id = "doris-ducklake-v1-" + a
        val jar = manifestJar(id, "stale-build")
        assertThatThrownBy { PluginIdentity.verifyManifest(jar.toFile(), "24.0", id) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("package identity")
    }

    @Test
    fun manifestHeaderCaseFollowsTheUpstreamJarParser() {
        val id = "doris-ducklake-v1-" + a
        PluginIdentity.verifyManifest(manifestJar(id, id, PluginIdentity.API_ATTRIBUTE.lowercase()).toFile(), "24.0", id)
    }

    private fun manifestJar(main: String, section: String, apiAttribute: String = PluginIdentity.API_ATTRIBUTE): Path {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(apiAttribute, "24.0")
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = main
            entries[PluginIdentity.PROVIDER_PACKAGE] = Attributes().apply {
                this[Attributes.Name.IMPLEMENTATION_VERSION] = section
            }
        }
        return directory.resolve("provider.jar").also { path ->
            JarOutputStream(path.toFile().outputStream(), manifest).use { }
        }
    }
}
