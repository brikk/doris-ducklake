package dev.brikk.ducklake.doris.plugin

import io.minio.BucketExistsArgs
import io.minio.MinioClient
import io.minio.S3Escaper
import io.minio.Signer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.file.Files
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exclusively UUID-owned, ephemeral storage for F01; no retained services or host data mounts. */
internal class OrphanTestMinio : AutoCloseable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val name = "doris-f01-$token"
    private val password = UUID.randomUUID().toString()
    private val owner = "dev.brikk.ducklake.f01.owner"
    private var id: String? = null
    private var createdClient: MinioClient? = null
    val client: MinioClient get() = requireNotNull(createdClient)
    private val rawHttp = OkHttpClient()
    val properties: Map<String, String>

    @Suppress("TooGenericExceptionCaught")
    constructor() {
        try {
            id = docker("create", "--rm", "--pull=missing", "--name", name, "--label", "$owner=$token",
                "--publish", "127.0.0.1::9000", "--tmpfs", "/data:rw,size=64m",
                "--env", "MINIO_ROOT_USER=f01-owned", "--env", "MINIO_ROOT_PASSWORD=$password",
                "minio/minio:RELEASE.2025-01-20T14-49-07Z", "server", "/data")
            docker("start", requireNotNull(id))
            val port = docker("container", "inspect", "--format", "{{(index (index .NetworkSettings.Ports \"9000/tcp\") 0).HostPort}}", requireNotNull(id))
            val endpoint = "http://127.0.0.1:$port"
            properties = mapOf("s3.endpoint" to endpoint, "s3.access_key" to "f01-owned", "s3.secret_key" to password)
            createdClient = MinioClient.builder().endpoint(endpoint).credentials("f01-owned", password).build()
            awaitReady()
            println("F01 exclusively owned MinIO image: " + docker("container", "inspect", "--format", "{{.Image}}", requireNotNull(id)))
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            try {
                client.bucketExists(BucketExistsArgs.builder().bucket("f01-readiness").build())
                return
            } catch (failure: Exception) {
                check(System.nanoTime() < deadline) { "Owned MinIO did not become ready: ${failure.javaClass.simpleName}" }
                Thread.sleep(100)
            }
        }
    }

    override fun close() {
        val container = id ?: return
        try {
            val label = docker("container", "inspect", "--format", "{{index .Config.Labels \"$owner\"}}", container)
            check(label == token) { "F01 storage owner changed; refusing cleanup" }
            docker("stop", "--time", "10", container) // --rm removes only this owned container/tmpfs.
            id = null
        } finally {
            createdClient?.close()
            rawHttp.dispatcher.executorService.shutdown()
            rawHttp.connectionPool.evictAll()
        }
    }

    fun putRaw(bucket: String, key: String, bytes: ByteArray) {
        rawRequest(bucket, key, bytes)
    }

    fun getRaw(bucket: String, key: String): ByteArray = rawRequest(bucket, key, null)

    /** Deliberately bypass S3Escaper.encodePath: its slash folding is the SDK defect under test. */
    private fun rawRequest(bucket: String, key: String, content: ByteArray?): ByteArray {
        val endpoint = properties.getValue("s3.endpoint")
        val url = "$endpoint/$bucket/" + key.split('/').joinToString("/") { S3Escaper.encode(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(content ?: byteArrayOf()).joinToString("") { "%02x".format(it) }
        val date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val request = Request.Builder().url(url).header("Host", endpoint.removePrefix("http://"))
            .header("x-amz-date", date).header("x-amz-content-sha256", hash)
            .apply { if (content == null) get() else put(content.toRequestBody()) }.build()
        val signed = Signer.signV4S3(request, "us-east-1", "f01-owned", password, hash)
        rawHttp.newCall(signed).execute().use { response ->
            val bytes = requireNotNull(response.body).bytes()
            check(response.isSuccessful) {
                "Owned raw S3 fixture request failed: ${response.code}, path=${request.url.encodedPath}, body=${String(bytes)}"
            }
            return bytes
        }
    }

    private fun docker(vararg args: String): String {
        val output = Files.createTempFile("doris-f01-docker-", ".log")
        try {
            val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("F01 Docker ${args.first()} timed out")
            }
            check(process.exitValue() == 0) { "F01 Docker ${args.first()} failed" }
            return Files.readString(output).trim()
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
