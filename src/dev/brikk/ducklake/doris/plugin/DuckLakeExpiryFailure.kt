package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeExpiryCleanupIncompleteException
import dev.brikk.ducklake.catalog.ExpireSnapshotsResult
import org.apache.doris.connector.spi.DorisConnectorException

/**
 * Expiry is two phases in the catalog: the snapshot expiry commits first, then cleanup pages run.
 * When cleanup fails after the commit, retrying `expire_snapshots` would be wrong (the snapshots are
 * already gone); the operator has to resume cleanup instead, so the message says exactly that.
 */
internal fun expireDuckLakeSnapshots(catalog: DucklakeCatalog, ids: Set<Long>): ExpireSnapshotsResult =
    try {
        catalog.expireSnapshots(ids)
    } catch (failure: DucklakeExpiryCleanupIncompleteException) {
        throw DorisConnectorException(
            "Snapshot expiry committed (${failure.expiredSnapshotCount} snapshots); cleanup failed after scheduling " +
                "${failure.scheduledFileCount} files. Do not retry expire_snapshots. Resume metadata cleanup with " +
                "DucklakeCatalog.cleanUpExpiredObjects; cleanup_old_files only deletes already scheduled objects.",
            failure,
        )
    }
