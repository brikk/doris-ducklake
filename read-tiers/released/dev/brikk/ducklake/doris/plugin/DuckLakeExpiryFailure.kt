package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeExpiryCleanupIncompleteException
import dev.brikk.ducklake.catalog.ExpireSnapshotsResult
import org.apache.doris.connector.spi.DorisConnectorException

/** The released catalog distinguishes committed expiry from failed cleanup; never suggest re-expiry. */
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
