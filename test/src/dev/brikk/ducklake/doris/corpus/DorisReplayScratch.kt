package dev.brikk.ducklake.doris.corpus

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Optional supervisor-mounted scratch, OUTSIDE oracle TEST_DIR/warehouse inspection paths. */
internal class DorisReplayScratch(private val root: Path?) : AutoCloseable {
    private var owned: Path? = null

    fun forTarget(target: String): Path? {
        if (root == null) return null
        require(target.matches(Regex("[a-z0-9_]+"))) { "Scratch target must be an internally generated database name" }
        val directory = owned ?: Files.createTempDirectory(root, "doris-replay-scratch-").toAbsolutePath().also { owned = it }
        // Table/schema/snapshot IDs are only unique within a metadata database.
        return Files.createDirectories(directory.resolve(target))
    }

    override fun close() {
        val directory = owned ?: return
        // No symlink following, no fallback to the supplied parent, no broad stale-directory sweep.
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        owned = null
    }
}
