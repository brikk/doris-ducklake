package dev.brikk.ducklake.doris.plugin

import java.time.Instant

/**
 * FE-side warehouse blob operations for `cleanup_old_files` and `remove_orphan_files`. The connector
 * talks to storage itself: an out-of-tree connector has no usable Doris `FileSystem` handle and the
 * warehouse is object storage, not a local drive. Tests inject a fake.
 */
internal interface WarehouseBlobStore {

    /** Per-object failures are reported, not thrown; deleting a missing object counts as success. */
    fun delete(uris: Collection<String>): BlobDeleteResult

    /** Recursive; must not return objects outside [prefixUri]'s directory (`.../t` must not match `.../t2/`). */
    fun list(prefixUri: String): List<BlobEntry>
}

internal data class BlobDeleteResult(
    val deleted: Set<String>,
    val failed: Map<String, String>,
)

internal data class BlobEntry(
    val uri: String,
    val lastModified: Instant,
)
