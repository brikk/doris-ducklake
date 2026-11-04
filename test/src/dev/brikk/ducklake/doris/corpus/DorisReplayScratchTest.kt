package dev.brikk.ducklake.doris.corpus

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

internal class DorisReplayScratchTest {
    @TempDir lateinit var root: Path

    @Test
    fun explicitScratchDoesNotAffectWarehouseAndSeparatesMetadataTargets() {
        val warehouse = Files.createDirectory(root.resolve("warehouse"))
        val transport = Files.createDirectory(root.resolve("transport"))
        DorisReplayScratch(transport).use { scratch ->
            val a = requireNotNull(scratch.forTarget("database_a"))
            val b = requireNotNull(scratch.forTarget("database_b"))
            assertThat(a).isNotEqualTo(b)
            assertThat(scratch.forTarget("database_a")).isEqualTo(a)
            Files.writeString(a.resolve("same-table-id.parquet"), "a")
            Files.writeString(b.resolve("same-table-id.parquet"), "b")
            Files.list(warehouse).use { assertThat(it.count()).isZero() }
            assertThat(Files.readString(a.resolve("same-table-id.parquet"))).isEqualTo("a")
        }
        Files.list(transport).use { assertThat(it.count()).isZero() }
        assertThat(Files.isDirectory(warehouse)).isTrue()
    }

    @Test
    fun cleanupDoesNotFollowLinksOrDeleteTheParentOrUnownedNeighbors() {
        val marker = Files.writeString(root.resolve("keep"), "unowned")
        val scratch = DorisReplayScratch(root)
        val target = requireNotNull(scratch.forTarget("database"))
        Files.createSymbolicLink(target.resolve("outside"), root)
        scratch.close()
        scratch.close()
        assertThat(Files.readString(marker)).isEqualTo("unowned")
        assertThat(Files.exists(target)).isFalse()
        assertThat(Files.isDirectory(root)).isTrue()
    }

    @Test
    fun defaultDoesNotChangeAndUnsafeTargetsCannotAllocateFiles() {
        assertThat(DorisReplayScratch(null).forTarget("database")).isNull()
        DorisReplayScratch(root).use { scratch ->
            for (target in listOf("../other", "/absolute", "a'b", "")) {
                assertThatThrownBy { scratch.forTarget(target) }.isInstanceOf(IllegalArgumentException::class.java)
            }
        }
        Files.list(root).use { assertThat(it.count()).isZero() }
    }
}
