package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.ExpireSnapshotsResult
import dev.brikk.ducklake.catalog.DucklakeExpiryCleanupIncompleteException
import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import org.apache.doris.connector.spi.DorisConnectorException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Released-catalog maintenance: real PostgreSQL transactions through the Doris procedure. */
internal class DuckLakeExpiryMaintenanceTest {
    companion object {
        private lateinit var server: TestingDucklakePostgreSqlCatalogServer
        @BeforeAll @JvmStatic fun start() { server = TestingDucklakePostgreSqlCatalogServer() }
        @AfterAll @JvmStatic fun stop() { server.close() }
    }

    @TempDir lateinit var root: Path

    @Test
    fun existingProcedureDryRunAndUnboundedExpiryCompleteMultiplePages() {
        DuckLakeExpiryFixture(server, root, 1002).use { f ->
            val before = f.count("snapshot")
            assertThat(f.expireProcedure(true).rows.single()).containsExactly(
                "true", "catalog-wide (ignores the named table)", "3", "n/a", "1,2,3",
            )
            assertThat(f.count("snapshot")).isEqualTo(before)
            assertThat(f.count("files_scheduled_for_deletion")).isZero()
            assertThat(f.expireProcedure().rows.single()).containsExactly(
                "false", "catalog-wide (ignores the named table)", "3", "1002", "1,2,3",
            )
            assertThat(f.count("snapshot")).isEqualTo(2)
            assertThat(f.count("files_scheduled_for_deletion")).isEqualTo(1002)
            assertThat(f.count("data_file")).isEqualTo(2)
            assertThat(f.catalog.cleanUpExpiredObjects(100).cleanupComplete).isTrue()
            f.assertLiveFiles()
        }
    }

    @Test
    fun cleanupFailureLeavesExpiryAndFirstPageCommittedAndResumesThroughTheNewApi() {
        DuckLakeExpiryFixture(server, root, 1002).use { f ->
            f.sql("CREATE FUNCTION trial_gc_failure() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN IF OLD.data_file_id IN (1001,1002) THEN RAISE EXCEPTION ''trial cleanup page fault''; END IF; RETURN OLD; END'")
            f.sql("CREATE TRIGGER trial_gc_failure BEFORE DELETE ON ducklake_data_file " +
                "FOR EACH ROW EXECUTE FUNCTION trial_gc_failure()")
            val failure = runCatching { f.expireProcedure() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(DorisConnectorException::class.java)
                .hasMessageContaining("expiry committed").hasMessageContaining("Do not retry")
                .hasMessageContaining("cleanUpExpiredObjects")
            val cleanup = requireNotNull(failure?.cause) as DucklakeExpiryCleanupIncompleteException
            assertThat(cleanup.expiredSnapshotCount).isEqualTo(3)
            assertThat(cleanup.cause).hasMessageContaining("trial cleanup page fault")
            // A real page-two failure: phase one and page one survived, page two rolled back.
            assertThat(f.count("snapshot")).isEqualTo(2)
            val committedScheduled = f.count("files_scheduled_for_deletion")
            assertThat(committedScheduled).isEqualTo(1000)
            assertThat(f.count("data_file")).isEqualTo(4)
            f.sql("DROP TRIGGER trial_gc_failure ON ducklake_data_file")
            // The existing procedure does not silently retry cleanup when no ids remain expirable.
            assertThat(f.expireProcedure().rows.single()[2]).isEqualTo("0")
            assertThat(f.count("data_file")).isEqualTo(4)
            val resumed = f.catalog.cleanUpExpiredObjects(Int.MAX_VALUE)
            assertThat(resumed.expiredSnapshotCount).isZero()
            assertThat(resumed.scheduledFileCount).isEqualTo(2)
            assertThat(resumed.cleanupComplete).isTrue()
            assertThat(f.catalog.cleanUpExpiredObjects(10).scheduledFileCount).isZero()
            assertThat(f.count("files_scheduled_for_deletion")).isEqualTo(1002)
            f.assertLiveFiles()
            println("EXPIRY_PROGRESS reported=${cleanup.scheduledFileCount} committed=$committedScheduled resumed=2 latest=4")
            assertThat(cleanup.scheduledFileCount).describedAs("Exception must report committed, not rolled-back, scheduling")
                .isEqualTo(committedScheduled.toInt())
        }
    }

    @Test
    fun zeroBudgetAndResumptionNeverScheduleFilesStillVisibleToARetainedSnapshot() {
        DuckLakeExpiryFixture(server, root, 3).use { f ->
            assertThat(f.catalog.expireSnapshots(setOf(1L, 3L), 0).scheduledFileCount).isZero()
            assertThat(f.catalog.cleanUpExpiredObjects(10).scheduledFileCount).isZero()
            assertThat(f.catalog.getDataFiles(f.a, 2).map { it.dataFileId }).containsExactly(1L, 2L, 3L)
            assertThat(f.catalog.expireSnapshots(setOf(2L), 0).cleanupComplete).isFalse()
            assertThat(f.count("files_scheduled_for_deletion")).isZero()
            val first = f.catalog.cleanUpExpiredObjects(2)
            assertThat(first.scheduledFileCount).isEqualTo(2)
            assertThat(first.cleanupComplete).isFalse()
            val second = f.catalog.cleanUpExpiredObjects(2)
            assertThat(second.scheduledFileCount).isEqualTo(1)
            assertThat(second.cleanupComplete).isTrue()
            assertThat(f.catalog.cleanUpExpiredObjects(2).scheduledFileCount).isZero()
            f.assertLiveFiles()
        }
    }

    @Test
    fun tableScopedCleanupPagesTableIdsAndKeepsOtherTablesWhileCollectingDroppedMetadata() {
        DuckLakeExpiryFixture(server, root, 4).use { f ->
            f.sql("INSERT INTO ducklake_delete_file(delete_file_id,data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
                "SELECT 5000+data_file_id,data_file_id,table_id,2,'delete-'||data_file_id||'.parquet',true " +
                "FROM ducklake_data_file WHERE data_file_id<5")
            f.sql("INSERT INTO ducklake_table(table_id,table_name,schema_id,begin_snapshot,end_snapshot,path,path_is_relative) " +
                "VALUES (9999,'gone',0,2,3,'gone/',true)")
            f.sql("INSERT INTO ducklake_data_file(data_file_id,table_id,begin_snapshot,end_snapshot,path,path_is_relative,file_format) " +
                "VALUES (8000,9999,2,NULL,'gone.parquet',true,'parquet')," +
                "(8001,${f.b},2,3,'b-dead-1.parquet',true,'parquet'),(8002,${f.b},2,3,'b-dead-2.parquet',true,'parquet')")
            assertThat(f.catalog.expireSnapshots(setOf(1L, 2L, 3L), 0).cleanupComplete).isFalse()
            assertThat(f.catalog.listTablesWithExpiredFiles(null, 10)).containsExactly(f.a, f.b, 9999L)
            assertThat(f.catalog.listTablesWithExpiredFiles(f.a, 1)).containsExactly(f.b)
            assertThatThrownBy { f.catalog.listTablesWithExpiredFiles(null, 1001) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { f.catalog.listTablesWithExpiredFiles(null, 0) }.isInstanceOf(IllegalArgumentException::class.java)
            // Hard budget: an attached delete file must not cause an overshoot.
            val first = f.catalog.cleanUpExpiredObjects(f.a, 1)
            assertThat(first.scheduledFileCount).isEqualTo(1)
            assertThat(first.cleanupComplete).isFalse()
            assertThat(f.scalar("SELECT count(*) FROM ducklake_data_file WHERE table_id=${f.b} AND end_snapshot=3")).isEqualTo(2)
            assertThat(f.catalog.cleanUpExpiredObjects(f.a, Int.MAX_VALUE).scheduledFileCount).isEqualTo(7)
            assertThat(f.catalog.listTablesWithExpiredFiles(null, 10)).containsExactly(f.b, 9999L)
            assertThat(f.catalog.cleanUpExpiredObjects(9999, Int.MAX_VALUE).cleanupComplete).isTrue()
            assertThat(f.scalar("SELECT count(*) FROM ducklake_table WHERE table_id=9999")).isZero()
            assertThat(f.catalog.cleanUpExpiredObjects(Int.MAX_VALUE).scheduledFileCount).isEqualTo(2)
            assertThat(f.catalog.listTablesWithExpiredFiles(null, 10)).isEmpty()
            assertThat(f.count("files_scheduled_for_deletion")).isEqualTo(11)
            f.assertLiveFiles()
        }
    }

    @Test
    fun aOneFileBudgetMakesProgressWhenOneDataFileHasManyDeleteFiles() {
        DuckLakeExpiryFixture(server, root, 1).use { f ->
            f.sql("INSERT INTO ducklake_delete_file(delete_file_id,data_file_id,table_id,begin_snapshot,path,path_is_relative) " +
                "SELECT 5000+i,1,${f.a},2,'delete-'||i||'.parquet',true FROM generate_series(1,5) AS g(i)")
            val expired = f.catalog.expireSnapshots(setOf(1L, 2L, 3L), 1)
            assertThat(expired.expiredSnapshotCount).isEqualTo(3)
            assertThat(expired.scheduledFileCount).isEqualTo(1)
            assertThat(expired.cleanupComplete).isFalse()
            var scheduled = expired.scheduledFileCount
            var complete = false
            repeat(8) {
                if (!complete) {
                    val step = f.catalog.cleanUpExpiredObjects(1)
                    assertThat(step.scheduledFileCount).isBetween(0, 1)
                    scheduled += step.scheduledFileCount
                    complete = step.cleanupComplete
                }
            }
            assertThat(complete).isTrue()
            assertThat(scheduled).isEqualTo(6)
            assertThat(f.catalog.cleanUpExpiredObjects(1).scheduledFileCount).isZero()
            f.assertLiveFiles()
        }
    }

    @Test
    fun qualifiedCatalogInheritsNewMethodsAndTheTwoArgumentResultConstructorRemainsUsable() {
        assertThat(ExpireSnapshotsResult(1, 2).cleanupComplete).isTrue()
        val surface = DuckLakeQualifiedReadCatalog::class.java
        assertThat(surface.getMethod("cleanUpExpiredObjects", Int::class.javaPrimitiveType)).isNotNull()
        assertThat(surface.getMethod("cleanUpExpiredObjects", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)).isNotNull()
        assertThat(surface.getMethod("expireSnapshots", Set::class.java, Int::class.javaPrimitiveType)).isNotNull()
        assertThat(surface.getMethod("listTablesWithExpiredFiles", Long::class.javaObjectType, Int::class.javaPrimitiveType)).isNotNull()
        val result = ExpireSnapshotsResult(1, 2, false)
        val legacyCopy = ExpireSnapshotsResult::class.java.getMethod("copy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        assertThat(legacyCopy.invoke(result, 7, 8)).isEqualTo(ExpireSnapshotsResult(7, 8, false))
        val defaultCopy = ExpireSnapshotsResult::class.java.getMethod("copy\$default", ExpireSnapshotsResult::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java)
        assertThat(defaultCopy.invoke(null, result, 7, 8, 1, null)).isEqualTo(ExpireSnapshotsResult(1, 8, false))
        assertThat(defaultCopy.invoke(null, result, 7, 8, 2, null)).isEqualTo(ExpireSnapshotsResult(7, 2, false))
        assertThat(defaultCopy.invoke(null, result, 7, 8, 3, null)).isEqualTo(result)
    }
}
