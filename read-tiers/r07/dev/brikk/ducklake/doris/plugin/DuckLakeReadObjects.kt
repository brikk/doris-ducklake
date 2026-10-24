package dev.brikk.ducklake.doris.plugin

import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.StatObjectArgs
import okhttp3.OkHttpClient
import org.apache.parquet.io.InputFile
import org.apache.parquet.io.SeekableInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.apache.doris.connector.spi.ConnectorHttpSecurityHook

/** Read-only, bounded range access. No temporary payloads, listing, writes or deletes. */
internal class DuckLakeReadObjects(properties: Map<String, String>, hook: ConnectorHttpSecurityHook) : AutoCloseable {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            hook.beforeRequest(chain.request().url.toString())
            try { chain.proceed(chain.request()) } finally { hook.afterRequest() }
        }.build()
    private val client = MinioClient.builder().endpoint(properties.getValue("s3.endpoint"))
        .credentials(properties.getValue("s3.access_key"), properties.getValue("s3.secret_key"))
        .region(properties.getOrDefault("s3.region", "us-east-1")).httpClient(http).build()

    fun open(path: String, budget: DuckLakeReadBudget): ObjectFile {
        budget.step()
        val (bucket, key) = S3WarehouseBlobStore.parseS3Uri(path)
        val stat = client.statObject(StatObjectArgs.builder().bucket(bucket).`object`(key).build())
        budget.step()
        return ObjectFile(bucket, key, stat.size(), stat.etag(), budget)
    }

    inner class ObjectFile(
        private val bucket: String, private val key: String,
        val size: Long, private val etag: String, private val budget: DuckLakeReadBudget,
    ) : InputFile {
        override fun getLength(): Long = size
        fun bytes(offset: Long, count: Int): ByteArray {
            require(offset >= 0 && count >= 0 && offset <= size - count) { "Invalid object read range" }
            budget.transfer(count)
            if (count == 0) return byteArrayOf()
            return client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key)
                .offset(offset).length(count.toLong()).matchETag(etag).build()).use { input ->
                if (offset != 0L || count.toLong() != size) {
                    check(input.headers()["Content-Range"] == "bytes $offset-${offset + count - 1}/$size") {
                        "Object server did not honor the requested range"
                    }
                }
                input.readNBytes(count).also { check(it.size == count) { "Truncated object range" }; budget.step() }
            }
        }

        override fun newStream(): SeekableInputStream = object : SeekableInputStream() {
            private var position = 0L
            override fun getPos(): Long = position
            override fun seek(newPos: Long) { require(newPos in 0..size); position = newPos }
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                require(off >= 0 && len >= 0 && off <= b.size - len)
                if (len == 0) return 0
                if (position == size) return -1
                val count = minOf(len.toLong(), size - position, 1_048_576L).toInt()
                bytes(position, count).copyInto(b, off)
                position += count
                return count
            }
            override fun readFully(b: ByteArray) = readFully(b, 0, b.size)
            override fun readFully(b: ByteArray, off: Int, len: Int) {
                var n = 0
                while (n < len) {
                    val count = read(b, off + n, len - n)
                    if (count < 0) throw EOFException()
                    n += count
                }
            }
            override fun read(buffer: ByteBuffer): Int {
                if (!buffer.hasRemaining()) return 0
                val b = ByteArray(minOf(buffer.remaining(), 65_536))
                val count = read(b, 0, b.size)
                if (count > 0) buffer.put(b, 0, count)
                return count
            }
            override fun readFully(buffer: ByteBuffer) {
                while (buffer.hasRemaining()) {
                    if (read(buffer) < 0) throw EOFException()
                }
            }
        }
    }

    override fun close() {
        try { client.close() } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
