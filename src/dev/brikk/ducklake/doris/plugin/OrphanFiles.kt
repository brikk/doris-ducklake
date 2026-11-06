package dev.brikk.ducklake.doris.plugin

import java.time.Instant

/**
 * The orphan selection for `remove_orphan_files`, kept pure so it can be tested exhaustively.
 *
 * A listed object is an orphan only if all hold: its basename is `ducklake-*` with a managed
 * extension (the gate that keeps a user's own `data.parquet` or `_SUCCESS` safe), it is not a known
 * referenced path, it is not inside a known dataset directory, and it is older than the cutoff.
 * When in doubt a file is kept.
 *
 * Managed extensions are `.parquet`, `.puffin` (deletion vectors) and `.db` (DuckDB-format data);
 * the Trino plugin's `.vortex` and `.lance` are not present in a Doris deployment.
 */
internal object OrphanFiles {

    private const val DUCKLAKE_FILE_PREFIX = "ducklake-"

    private val MANAGED_FILE_EXTENSIONS = listOf(".parquet", ".puffin", ".db")

    /** Known and listed URIs are compared by scheme-independent identity; the original listed URI is returned. */
    fun find(listed: List<BlobEntry>, knownPaths: Set<String>, cutoff: Instant): List<String> {
        val knownIdentities = knownPaths.mapTo(HashSet(), S3WarehouseBlobStore::objectIdentity)
        val knownDirPrefixes = knownIdentities.map(S3WarehouseBlobStore::dirUri)
        return listed
            .filter { entry ->
                val identity = S3WarehouseBlobStore.objectIdentity(entry.uri)
                isDucklakeManagedResidue(entry.uri) &&
                    identity !in knownIdentities &&
                    knownDirPrefixes.none { prefix -> identity.startsWith(prefix) } &&
                    entry.lastModified.isBefore(cutoff)
            }
            .map { it.uri }
    }

    fun isDucklakeManagedResidue(uri: String): Boolean {
        val basename = uri.substringAfterLast('/')
        return basename.startsWith(DUCKLAKE_FILE_PREFIX) &&
            MANAGED_FILE_EXTENSIONS.any { basename.endsWith(it, ignoreCase = true) }
    }
}
