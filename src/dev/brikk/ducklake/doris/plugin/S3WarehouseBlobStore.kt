package dev.brikk.ducklake.doris.plugin

import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs

import org.apache.doris.connector.spi.DorisConnectorException

/**
 * [WarehouseBlobStore] over the MinIO client (S3 API: MinIO, AWS, OSS, COS, …), built from the same
 * `s3.*` properties the BE reader uses. A non-S3 URI is a per-object failure rather than an abort, so
 * one stray path can't stop a cleanup. The SDK rewrites repeated-slash and dot-segment keys, so those
 * are refused rather than risk deleting a different object.
 */
internal class S3WarehouseBlobStore(private val client: MinioClient) : WarehouseBlobStore {

    @Suppress("TooGenericExceptionCaught")
    override fun delete(uris: Collection<String>): BlobDeleteResult {
        val deleted = LinkedHashSet<String>()
        val failed = LinkedHashMap<String, String>()
        for (uri in uris) {
            try {
                val (bucket, key) = parseS3Uri(uri)
                require(!key.contains("//") && key.split('/').none { it == "." || it == ".." }) {
                    "S3 client cannot delete this key without rewriting its path; refusing"
                }
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
                deleted.add(uri)
            } catch (e: IllegalArgumentException) {
                failed[uri] = e.message ?: "invalid S3 URI"
            } catch (e: Exception) {
                // removeObject declares nine unrelated checked exceptions; each is a per-object failure
                // and the file keeps its schedule row for a later retry.
                failed[uri] = e.message ?: e.javaClass.simpleName
            }
        }
        return BlobDeleteResult(deleted, failed)
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    override fun list(prefixUri: String): List<BlobEntry> {
        val (bucket, keyPrefix) = parseS3Uri(dirUri(prefixUri))
        val results = try {
            client.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(keyPrefix)
                    .recursive(true)
                    .build(),
            )
        } catch (e: Exception) {
            // A partial listing would look like "no live files" and could delete real data.
            throw DorisConnectorException("failed to list warehouse objects under $prefixUri: ${e.message}", e)
        }
        val entries = ArrayList<BlobEntry>()
        for (result in results) {
            val item = try {
                result.get()
            } catch (e: Exception) {
                throw DorisConnectorException("failed to read a listed object under $prefixUri: ${e.message}", e)
            }
            if (item.isDir) {
                continue
            }
            val modified = item.lastModified()?.toInstant()
                ?: throw DorisConnectorException(
                    "listed object '${item.objectName()}' has no last-modified time; refusing to " +
                        "age-gate orphan deletion without it",
                )
            entries.add(BlobEntry("s3://$bucket/${item.objectName()}", modified))
        }
        return entries
    }

    internal companion object {
        private val S3_SCHEMES = setOf("s3", "s3a", "s3n")

        fun fromProperties(properties: Map<String, String>): S3WarehouseBlobStore {
            val builder = MinioClient.builder()
                .endpoint(requireProp(properties, "s3.endpoint"))
                .credentials(requireProp(properties, "s3.access_key"), requireProp(properties, "s3.secret_key"))
            properties["s3.region"]?.takeIf { it.isNotBlank() }?.let { builder.region(it) }
            return S3WarehouseBlobStore(builder.build())
        }

        private fun requireProp(properties: Map<String, String>, key: String): String =
            properties[key]?.takeIf { it.isNotBlank() }
                ?: throw DorisConnectorException("cleanup_old_files needs '$key' set on the catalog")

        /** Directory semantics for the prefix: `s3://b/data/t/` must not also match `s3://b/data/t2/`. */
        internal fun dirUri(uri: String): String = if (uri.endsWith('/')) uri else "$uri/"

        /**
         * Comparison identity across the accepted S3 scheme aliases. Only the scheme is normalized:
         * case, percent sequences, repeated slashes and dot segments are part of the object's identity.
         */
        internal fun objectIdentity(uri: String): String {
            val separator = uri.indexOf("://")
            return if (separator > 0 && uri.substring(0, separator).lowercase() in S3_SCHEMES) {
                "s3" + uri.substring(separator)
            } else {
                uri
            }
        }

        /** `(bucket, key)` without decoding or rewriting the key; throws [IllegalArgumentException] otherwise. */
        internal fun parseS3Uri(uri: String): Pair<String, String> {
            val schemeSep = uri.indexOf("://")
            require(schemeSep > 0) { "not an absolute URI: $uri" }
            val scheme = uri.substring(0, schemeSep).lowercase()
            require(scheme in S3_SCHEMES) { "unsupported scheme '$scheme' (S3 only): $uri" }
            val rest = uri.substring(schemeSep + 3)
            val slash = rest.indexOf('/')
            require(slash > 0) { "missing bucket or key: $uri" }
            val bucket = rest.substring(0, slash)
            val key = rest.substring(slash + 1)
            require(bucket.isNotEmpty()) { "empty bucket: $uri" }
            require(key.isNotEmpty()) { "empty object key: $uri" }
            return bucket to key
        }
    }
}
