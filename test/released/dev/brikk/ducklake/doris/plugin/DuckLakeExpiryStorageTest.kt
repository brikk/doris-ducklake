package dev.brikk.ducklake.doris.plugin

import dev.brikk.ducklake.catalog.TestingDucklakePostgreSqlCatalogServer
import io.minio.MakeBucketArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

internal class DuckLakeExpiryStorageTest {
    @TempDir lateinit var root: Path

    @Test
    fun existingCleanupProcedureToleratesDuplicateQueueTuplesAndAlreadyDeletedS3Paths() {
        TestingDucklakePostgreSqlCatalogServer().use { server -> DuckLakeExpiryFixture(server, root, 0).use { f ->
            OrphanTestMinio().use { storage ->
                val bucket = "trial-" + UUID.randomUUID().toString().replace("-", "")
                storage.client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                storage.putRaw(bucket, "warehouse/present.parquet", byteArrayOf(1, 2, 3))
                val live = "do not delete".toByteArray()
                storage.putRaw(bucket, "warehouse/live.parquet", live)
                f.sql("UPDATE ducklake_metadata SET value='s3://$bucket/warehouse/' WHERE key='data_path' AND scope IS NULL")
                f.sql("INSERT INTO ducklake_files_scheduled_for_deletion(data_file_id,path,path_is_relative,schedule_start) VALUES " +
                    "(700,'present.parquet',true,'2000-01-01'),(700,'present.parquet',true,'2000-01-01')," +
                    "(701,'s3://$bucket/warehouse/already-gone.parquet',false,'2000-01-01')")
                val store = S3WarehouseBlobStore(storage.client)
                val ops = DuckLakeProcedureOps(f.catalog, storage.properties) { store }
                val result = ops.execute(null, null, "cleanup_old_files", emptyMap(), null, emptyList())
                assertThat(result.rows.single()).containsExactly("false", "7d", "2", "0")
                assertThat(f.count("files_scheduled_for_deletion")).isZero()
                assertThatThrownBy {
                    storage.client.statObject(StatObjectArgs.builder().bucket(bucket).`object`("warehouse/present.parquet").build())
                }.isInstanceOf(ErrorResponseException::class.java)
                assertThat(storage.getRaw(bucket, "warehouse/live.parquet")).isEqualTo(live)
                assertThat(store.delete(listOf("s3://$bucket/warehouse/present.parquet")).failed).isEmpty()
                assertThat(ops.execute(null, null, "cleanup_old_files", emptyMap(), null, emptyList()).rows.single())
                    .containsExactly("false", "7d", "0", "0")
            }
        } }
    }
}
