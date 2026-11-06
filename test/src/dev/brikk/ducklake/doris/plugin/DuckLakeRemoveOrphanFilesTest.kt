package dev.brikk.ducklake.doris.plugin

import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

import dev.brikk.ducklake.catalog.DucklakeCatalog
import dev.brikk.ducklake.catalog.DucklakeFilePathRef
import dev.brikk.ducklake.catalog.DucklakeReferencedFiles
import dev.brikk.ducklake.catalog.DucklakeSchema
import dev.brikk.ducklake.catalog.DucklakeTable
import dev.brikk.ducklake.catalog.DucklakeTableFilePathRef

import org.apache.doris.connector.spi.DorisConnectorException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Covers `remove_orphan_files`: the pure [OrphanFiles] selection (the `ducklake-` residue gate,
 * known-set exact match, directory-prefix guard, age grace) and the table/schema/catalog scope
 * orchestration in [DuckLakeProcedureOps] against a fake [WarehouseBlobStore] and catalog.
 */
internal class DuckLakeRemoveOrphanFilesTest {

    private val now: Instant = Instant.parse("2026-07-10T00:00:00Z")
    private val old: Instant = now.minusSeconds(30L * 24 * 3600) // 30d ago — past any grace period
    private val recent: Instant = now.minusSeconds(60) // 1 min ago — inside any grace period

    // ---- OrphanFiles.find + isDucklakeManagedResidue (pure) ----

    @Test
    fun onlyDeletesDucklakeManagedResidue() {
        // Only ducklake-prefixed managed residue is a candidate; foreign files, including a user's
        // own non-ducklake parquet, are never deleted.
        val listed = listOf(
            BlobEntry("s3://b/t/ducklake-1.parquet", old), // data residue → delete
            BlobEntry("s3://b/t/ducklake-delete-2.parquet", old), // delete-file residue → delete
            BlobEntry("s3://b/t/ducklake-3-delete.puffin", old), // DuckDB-named puffin delete → delete
            BlobEntry("s3://b/t/ducklake-4.db", old), // DuckDB-format data → delete
            BlobEntry("s3://b/t/data.parquet", old), // user's own parquet (no prefix) → keep
            BlobEntry("s3://b/t/_SUCCESS", old), // Hive marker → keep
            BlobEntry("s3://b/t/ducklake-5.parquet.crc", old), // checksum sidecar (wrong ext) → keep
            BlobEntry("s3://b/t/ducklake-6.vortex", old), // vortex not managed by Doris → keep
            BlobEntry("s3://b/t/ducklake-7.lance/part.lance", old), // lance member (no prefix on basename) → keep
            BlobEntry("s3://b/t/notes.txt", old), // stray → keep
        )
        assertThat(OrphanFiles.find(listed, emptySet(), now)).containsExactlyInAnyOrder(
            "s3://b/t/ducklake-1.parquet",
            "s3://b/t/ducklake-delete-2.parquet",
            "s3://b/t/ducklake-3-delete.puffin",
            "s3://b/t/ducklake-4.db",
        )
    }

    @Test
    fun isDucklakeManagedResidueRules() {
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/ducklake-x.parquet")).isTrue()
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/ducklake-x.PARQUET")).isTrue() // case-insensitive ext
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/ducklake-x.puffin")).isTrue()
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/ducklake-x.db")).isTrue()
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/data.parquet")).isFalse() // no prefix
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/ducklake-x.vortex")).isFalse() // Doris omits
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/ducklake-x.txt")).isFalse() // unmanaged ext
        assertThat(OrphanFiles.isDucklakeManagedResidue("s3://b/t/_SUCCESS")).isFalse()
    }

    @Test
    fun keepsKnownReferencedAndYoungResidue() {
        val listed = listOf(
            BlobEntry("s3://b/t/ducklake-known.parquet", old), // referenced → keep
            BlobEntry("s3://b/t/ducklake-orphan.parquet", old), // orphan → delete
            BlobEntry("s3://b/t/ducklake-young.parquet", recent), // young → keep
        )
        val known = setOf("s3://b/t/ducklake-known.parquet")
        val cutoff = now.minusSeconds(7L * 24 * 3600) // 7d grace
        assertThat(OrphanFiles.find(listed, known, cutoff)).containsExactly("s3://b/t/ducklake-orphan.parquet")
    }

    @Test
    fun keepsMembersOfKnownDatasetDirectories() {
        val listed = listOf(
            BlobEntry("s3://b/t/ducklake-ds/ducklake-part.parquet", old),
            BlobEntry("s3://b/t/ducklake-stray.parquet", old),
        )
        val known = setOf("s3://b/t/ducklake-ds") // a known directory
        assertThat(OrphanFiles.find(listed, known, now)).containsExactly("s3://b/t/ducklake-stray.parquet")
    }

    @Test
    fun acceptedS3AliasesProtectTheSameReferencedObject() {
        val live = "b/t/ducklake-live.parquet"
        for (scheme in listOf("s3", "s3a", "s3n", "S3A", "S3N")) {
            assertThat(OrphanFiles.find(listOf(BlobEntry("s3://$live", old)), setOf("$scheme://$live"), now))
                .describedAs("known $scheme reference versus production s3 listing").isEmpty()
            assertThat(OrphanFiles.find(listOf(BlobEntry("$scheme://$live", old)), setOf("s3://$live"), now))
                .describedAs("listed $scheme reference uses the same comparison identity").isEmpty()
        }
    }

    @Test
    fun directoryAliasesPreserveTrailingSlashesAndRespectDirectoryBoundaries() {
        val listed = listOf(
            BlobEntry("s3://b/t/dataset/ducklake-part.parquet", old),
            BlobEntry("s3://b/t/dataset2/ducklake-orphan.parquet", old),
            BlobEntry("s3://b/t/repeated///ducklake-part.parquet", old),
            BlobEntry("s3://b/t/repeated/ducklake-orphan.parquet", old),
        )
        val known = setOf("s3a://b/t/dataset/", "s3n://b/t/repeated///")
        assertThat(OrphanFiles.find(listed, known, now)).containsExactly(
            "s3://b/t/dataset2/ducklake-orphan.parquet", "s3://b/t/repeated/ducklake-orphan.parquet",
        )
    }

    @Test
    fun aliasComparisonDoesNotDecodeOrNormalizeObjectKeys() {
        val distinctKeys = listOf(
            "t/a%2Fb/ducklake-key.parquet", "t/a%2fb/ducklake-key.parquet", "t/a/b/ducklake-key.parquet",
            "t/ducklake-a+b.parquet", "t/ducklake-a b.parquet", "t/ducklake-a%20b.parquet",
            "t//ducklake-case.parquet", "t/ducklake-case.parquet", "t/ducklake-CASE.parquet",
            "t/./ducklake-dot.parquet", "t/ducklake-dot.parquet", "t/x/../ducklake-dot.parquet",
            "t/ducklake-é.parquet", "t/ducklake-e\u0301.parquet", "t/ducklake-?#.parquet",
        )
        for (key in distinctKeys) {
            val listed = distinctKeys.map { BlobEntry("s3://b/$it", old) }
            assertThat(OrphanFiles.find(listed, setOf("s3n://b/$key"), now))
                .describedAs("only the exact raw key is protected: %s", key)
                .containsExactlyElementsOf(listed.filter { it.uri != "s3://b/$key" }.map { it.uri })
        }
    }

    // ---- orchestration: scope tiers from the named table's viewpoint ----

    @Test
    fun tableScopeDefaultSweepsOnlyTheNamedTable() {
        val cat = catalog()
        val store = FakeBlobStore(
            listing = listOf(
                BlobEntry("s3://dl/data/sales/orders/ducklake-live.parquet", old), // referenced → keep
                BlobEntry("s3://dl/data/sales/orders/ducklake-orphan.parquet", old), // orphan → delete
                BlobEntry("s3://dl/data/sales/orders/data.parquet", old), // foreign → keep
            ),
        )
        val res = ops(cat, store).execute(
            null, handle("sales", "orders"), "remove_orphan_files", mapOf("retention_threshold" to "7d"), null, emptyList(),
        )
        assertThat(store.listedPrefixes).containsExactly("s3://dl/data/sales/orders") // table dir only
        assertThat(store.deleteRequested).containsExactly("s3://dl/data/sales/orders/ducklake-orphan.parquet")
        assertThat(res.rows.single()).containsExactly("false", "7d", "1", "0")
    }

    @Test
    fun schemaScopeSweepsTheSchemaDirAndUnionsItsTables() {
        val cat = catalog()
        val store = FakeBlobStore(
            listing = listOf(
                BlobEntry("s3://dl/data/sales/orders/ducklake-live.parquet", old), // referenced (orders) → keep
                BlobEntry("s3://dl/data/sales/customers/ducklake-live-c.parquet", old), // referenced (customers) → keep
                BlobEntry("s3://dl/data/sales/failed_create/ducklake-ghost.parquet", old), // failed CREATE residue → delete
            ),
        )
        val res = ops(cat, store).execute(
            null, handle("sales", "orders"), "remove_orphan_files",
            mapOf("scope" to "schema", "retention_threshold" to "7d"), null, emptyList(),
        )
        assertThat(store.listedPrefixes).containsExactly("s3://dl/data/sales") // schema dir
        assertThat(store.deleteRequested).containsExactly("s3://dl/data/sales/failed_create/ducklake-ghost.parquet")
        assertThat(res.rows.single()).containsExactly("false", "7d", "1", "0")
    }

    @Test
    fun catalogScopeSweepsRootAndUnionsAllSchemas() {
        val cat = catalog()
        val store = FakeBlobStore(
            listing = listOf(
                BlobEntry("s3://dl/data/sales/orders/ducklake-live.parquet", old), // referenced → keep
                BlobEntry("s3://dl/data/analytics/events/ducklake-live-e.parquet", old), // referenced (other schema) → keep
                BlobEntry("s3://dl/data/ghost_schema/ducklake-ghost.parquet", old), // dropped/failed residue → delete
            ),
        )
        val res = ops(cat, store).execute(
            null, handle("sales", "orders"), "remove_orphan_files",
            mapOf("scope" to "catalog", "retention_threshold" to "7d"), null, emptyList(),
        )
        assertThat(store.listedPrefixes).containsExactly("s3://dl/data") // warehouse root
        assertThat(store.deleteRequested).containsExactly("s3://dl/data/ghost_schema/ducklake-ghost.parquet")
        assertThat(res.rows.single()).containsExactly("false", "7d", "1", "0")
    }

    @Test
    fun catalogScopeKeepsDroppedTableAndScheduledFiles() {
        val dropped = DucklakeTableFilePathRef(
            98L,
            "archive",
            true,
            99L,
            "dropped_orders",
            true,
            "ducklake-dropped-owned.parquet",
            true,
        )
        val scheduled = DucklakeFilePathRef("scheduled/ducklake-pending.parquet", true)
        val cat = catalog(extraTableRefs = listOf(dropped), scheduledRefs = listOf(scheduled))
        val store = FakeBlobStore(
            listing = listOf(
                BlobEntry("s3://dl/data/archive/dropped_orders/ducklake-dropped-owned.parquet", old),
                BlobEntry("s3://dl/data/scheduled/ducklake-pending.parquet", old),
                BlobEntry("s3://dl/data/failed/ducklake-orphan.parquet", old),
            ),
        )

        ops(cat, store).execute(
            null, handle("sales", "orders"), "remove_orphan_files",
            mapOf("scope" to "catalog", "retention_threshold" to "7d"), null, emptyList(),
        )

        assertThat(store.deleteRequested).containsExactly("s3://dl/data/failed/ducklake-orphan.parquet")
    }

    @Test
    fun mixedOwnerPathsAndAllScopesUseAuthoritativeAliasRoot() {
        val extra = listOf(
            DucklakeTableFilePathRef(98L, "s3n://dl/data/sales/", false, 99L, "orders", true, "ducklake-dropped.parquet", true),
            DucklakeTableFilePathRef(10L, "sales", true, 20L, "orders", true, "s3n://dl/data/sales/orders/ducklake-absolute.db", false),
            DucklakeTableFilePathRef(10L, "sales", true, 20L, "s3a://dl/data/sales/orders", false, "ducklake-directory/", true),
        )
        val scheduled = listOf(
            DucklakeFilePathRef("sales/orders/ducklake-relative-scheduled.puffin", true),
            DucklakeFilePathRef("s3n://dl/data/sales/orders/ducklake-absolute-scheduled.parquet", false),
        )
        val live = listOf(
            "ducklake-live.parquet", "ducklake-dropped.parquet", "ducklake-absolute.db",
            "ducklake-directory/ducklake-member.parquet", "ducklake-relative-scheduled.puffin", "ducklake-absolute-scheduled.parquet",
        ).map { BlobEntry("s3://dl/data/sales/orders/$it", old) }
        val orphan = "s3://dl/data/sales/orders/ducklake-orphan.parquet"
        for (scheme in listOf("s3", "s3a", "s3n")) {
            for (scope in listOf("table", "schema", "catalog")) {
                for (dryRun in listOf(false, true)) {
                    val cat = catalog(extra, scheduled, "$scheme://dl/data")
                    val store = FakeBlobStore(live + BlobEntry(orphan, old) + BlobEntry("s3://dl/data/sales/orders/data.parquet", old))
                    val procedure = DuckLakeProcedureOps(cat, mapOf("storage.warehouse" to "s3://wrong-bucket/wrong-root")) { store }
                    val result = procedure.execute(
                        null, handle("sales", "orders"), "remove_orphan_files",
                        mapOf("scope" to scope, "dry_run" to dryRun.toString()), null, emptyList(),
                    )
                    assertThat(result.rows.single()).containsExactly(dryRun.toString(), "7d", "1", "0")
                    assertThat(store.deleteRequested).containsExactlyElementsOf(if (dryRun) emptyList() else listOf(orphan))
                    assertThat(store.listedPrefixes.single()).startsWith("$scheme://dl/data")
                }
            }
        }
    }

    @Test
    fun databaseIsAnAliasForSchemaScope() {
        val cat = catalog()
        val store = FakeBlobStore(listing = emptyList())
        ops(cat, store).execute(
            null, handle("sales", "orders"), "remove_orphan_files",
            mapOf("scope" to "database"), null, emptyList(),
        )
        assertThat(store.listedPrefixes).containsExactly("s3://dl/data/sales") // schema dir
    }

    @Test
    fun dryRunListsButDeletesNothing() {
        val cat = catalog()
        val store = FakeBlobStore(listing = listOf(BlobEntry("s3://dl/data/sales/orders/ducklake-o.parquet", old)))
        val res = ops(cat, store).execute(
            null, handle("sales", "orders"), "remove_orphan_files",
            mapOf("retention_threshold" to "7d", "dry_run" to "true"), null, emptyList(),
        )
        assertThat(store.deleteRequested).isEmpty()
        assertThat(res.rows.single()).containsExactly("true", "7d", "1", "0")
    }

    @Test
    fun retentionBelowFloorIsRejectedBeforeListing() {
        val store = FakeBlobStore()
        assertThatThrownBy {
            ops(catalog(), store).execute(null, handle("sales", "orders"), "remove_orphan_files", mapOf("retention_threshold" to "1h"), null, emptyList())
        }.isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("below the minimum")
        assertThat(store.listedPrefixes).isEmpty()
    }

    @Test
    fun invalidScopeIsRejected() {
        assertThatThrownBy {
            ops(catalog(), FakeBlobStore()).execute(null, handle("sales", "orders"), "remove_orphan_files", mapOf("scope" to "cluster"), null, emptyList())
        }.isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("Invalid scope")
    }

    @Test
    fun rejectsPartitionClause() {
        assertThatThrownBy {
            ops(catalog(), FakeBlobStore()).execute(null, handle("sales", "orders"), "remove_orphan_files", emptyMap(), null, listOf("p1"))
        }.isInstanceOf(DorisConnectorException::class.java).hasMessageContaining("PARTITION")
    }

    // ---- S3 dir-prefix normalization ----

    @Test
    fun dirUriAddsADirectoryBoundaryWithoutChangingTheRawKey() {
        assertThat(S3WarehouseBlobStore.dirUri("s3://b/data/t")).isEqualTo("s3://b/data/t/")
        assertThat(S3WarehouseBlobStore.dirUri("s3://b/data/t/")).isEqualTo("s3://b/data/t/")
        assertThat(S3WarehouseBlobStore.dirUri("s3://b/data/t///")).isEqualTo("s3://b/data/t///")
    }

    // ---- fixtures ----

    private fun ops(cat: FakeCatalog, store: FakeBlobStore) =
        DuckLakeProcedureOps(cat, mapOf("storage.warehouse" to "s3://dl/data")) { store }

    private fun handle(db: String, table: String) =
        DuckLakeTableHandle(db, table, schemaId = if (db == "sales") 10L else 11L, tableId = 20L, snapshotId = 5L)

    /** Two schemas (sales: orders+customers, analytics: events), each table with one referenced file. */
    private fun catalog(
        extraTableRefs: List<DucklakeTableFilePathRef> = emptyList(),
        scheduledRefs: List<DucklakeFilePathRef> = emptyList(),
        dataPath: String = "s3://dl/data",
    ) = FakeCatalog(
        schemas = mapOf(
            "sales" to sch(10L, "sales"),
            "analytics" to sch(11L, "analytics"),
        ),
        tablesBySchemaId = mapOf(
            10L to listOf(tbl(20L, 10L, "orders"), tbl(21L, 10L, "customers")),
            11L to listOf(tbl(30L, 11L, "events")),
        ),
        refsByTableId = mapOf(
            20L to listOf(DucklakeFilePathRef("ducklake-live.parquet", true)),
            21L to listOf(DucklakeFilePathRef("ducklake-live-c.parquet", true)),
            30L to listOf(DucklakeFilePathRef("ducklake-live-e.parquet", true)),
        ),
        extraTableRefs = extraTableRefs,
        scheduledRefs = scheduledRefs,
        dataPath = dataPath,
    )

    private fun sch(id: Long, name: String) = DucklakeSchema(id, UUID.randomUUID(), 1L, null, name, name, pathIsRelative = true)
    private fun tbl(id: Long, schemaId: Long, name: String) = DucklakeTable(id, UUID.randomUUID(), 1L, null, schemaId, name, name, pathIsRelative = true)

    private class FakeBlobStore(private val listing: List<BlobEntry> = emptyList()) : WarehouseBlobStore {
        val deleteRequested = mutableListOf<String>()
        val listedPrefixes = mutableListOf<String>()
        override fun delete(uris: Collection<String>): BlobDeleteResult {
            deleteRequested.addAll(uris)
            return BlobDeleteResult(uris.toSet(), emptyMap())
        }
        override fun list(prefixUri: String): List<BlobEntry> {
            listedPrefixes.add(prefixUri)
            // Return only entries under this directory prefix, rendered as s3:// like a real listing.
            // Do not reuse the selection's comparison helper here; the listing must stay an independent oracle.
            val (bucket, key) = S3WarehouseBlobStore.parseS3Uri(prefixUri)
            val dir = "s3://$bucket/" + if (key.endsWith('/')) key else "$key/"
            return listing.filter { it.uri.startsWith(dir) }
        }
    }

    private class FakeCatalog(
        private val schemas: Map<String, DucklakeSchema> = emptyMap(),
        private val tablesBySchemaId: Map<Long, List<DucklakeTable>> = emptyMap(),
        private val refsByTableId: Map<Long, List<DucklakeFilePathRef>> = emptyMap(),
        private val extraTableRefs: List<DucklakeTableFilePathRef> = emptyList(),
        private val scheduledRefs: List<DucklakeFilePathRef> = emptyList(),
        private val dataPath: String = "s3://dl/data",
    ) : DucklakeCatalog by throwingDelegate() {

        override fun getDataPath(): String = dataPath
        override fun getSchema(schemaName: String, snapshotId: Long): DucklakeSchema? = schemas[schemaName]
        override fun getTable(schemaName: String, tableName: String, snapshotId: Long): DucklakeTable? =
            tablesBySchemaId[schemas[schemaName]?.schemaId]?.firstOrNull { it.tableName == tableName }
        override fun listSchemas(snapshotId: Long): List<DucklakeSchema> = schemas.values.toList()
        override fun listTables(schemaId: Long, snapshotId: Long): List<DucklakeTable> = tablesBySchemaId[schemaId].orEmpty()
        override fun listReferencedFilePaths(tableId: Long): List<DucklakeFilePathRef> = refsByTableId[tableId].orEmpty()
        override fun listAllReferencedFiles(): DucklakeReferencedFiles {
            val tableRefs = tablesBySchemaId.flatMap { (schemaId, tables) ->
                val schema = schemas.values.first { it.schemaId == schemaId }
                tables.flatMap { table ->
                    refsByTableId[table.tableId].orEmpty().map { ref ->
                        DucklakeTableFilePathRef(
                            schemaId,
                            schema.path,
                            schema.pathIsRelative,
                            table.tableId,
                            table.path,
                            table.pathIsRelative,
                            ref.path,
                            ref.pathIsRelative,
                        )
                    }
                }
            }
            return DucklakeReferencedFiles(tableRefs + extraTableRefs, scheduledRefs)
        }

        companion object {
            private fun throwingDelegate(): DucklakeCatalog =
                Proxy.newProxyInstance(
                    DucklakeCatalog::class.java.classLoader,
                    arrayOf(DucklakeCatalog::class.java),
                ) { _, method, _ ->
                    throw NotImplementedError("FakeCatalog does not implement ${method.name}")
                } as DucklakeCatalog
        }
    }
}
