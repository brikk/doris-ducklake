package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.ExpireSnapshotsResult

/** Preserve the historical candidate's pre-0.15 API rather than silently changing its dependency lane. */
internal fun expireDuckLakeSnapshots(catalog: DucklakeCatalog, ids: Set<Long>): ExpireSnapshotsResult =
    catalog.expireSnapshots(ids)
