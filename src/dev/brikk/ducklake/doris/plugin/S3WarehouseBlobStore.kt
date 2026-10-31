package dev.brikk.ducklake.doris.plugin

import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.RemoveObjectArgs

import org.apache.doris.connector.spi.DorisConnectorException

/**
 * [WarehouseBlobStore] backed by the MinIO Java client, which speaks the S3 API to MinIO, AWS S3,
 * Alibaba OSS, Tencent COS, and other S3-compatible stores. Built from the catalog's `s3.*`
 * properties — the same credentials the BE parquet reader already uses to reach the warehouse — so
 * the connector deletes blobs itself from the FE without assuming any local-filesystem access.
 *
 * Only S3-compatible schemes are supported for now (`s3://`, `s3a://`, `s3n://`); a non-S3 URI is
 * reported as a per-object failure rather than throwing, so one stray path can't abort a cleanup.
 * HDFS/Azure warehouses would need their own [WarehouseBlobStore] impls (future work).
 *
 * Real list/delete and released-catalog ownership are tested together against disposable MinIO;
 * pure selection/parser tests also cover raw key identity. The pinned SDK rewrites repeated slash
 * and dot-segment object paths, so such deletions fail closed rather than target another key.
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
                // Deliberately broad: MinIO's removeObject throws ~9 unrelated checked exceptions
                // (IOException/XmlParserException/ServerException/InsufficientDataException/…).
                // Any is a per-object failure so cleanup makes partial progress and the file keeps
                // its schedule row for a later retry. (@Suppress TooGenericExceptionCaught.)
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
            // Listing failures are fatal to orphan detection: proceeding would treat a partial/empty
            // listing as "no live files" and could delete real data. Fail loud.
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
                continue // common-prefix placeholder; recursive(true) returns objects, but guard anyway
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

        /**
         * Build an S3 blob store from the catalog `s3.*` properties (endpoint/region/keys). Fails
         * loud if endpoint or credentials are missing — cleanup can't run without them, and a silent
         * no-op would leave scheduled files leaking. Mirrors the keys the write path already emits
         * ([DuckLakeScanPlanProvider.isStorageProperty]).
         */
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

        /**
         * Append a missing `/`, so the S3 prefix has directory
         * semantics: listing under `s3://b/data/t/` must NOT also match a sibling `s3://b/data/t2/…`.
         * Existing slashes belong to the raw object key and must not be collapsed.
         */
        internal fun dirUri(uri: String): String = if (uri.endsWith('/')) uri else "$uri/"

        /**
         * Comparison identity for this store's accepted S3 aliases. Change ONLY the scheme:
         * bucket/key case, percent sequences, Unicode, spaces, `+`, repeated slashes and dot
         * segments are literal object identity, not filesystem/URL normalization instructions.
         * Non-S3 paths remain opaque. Listing/deletion keep their original URI spelling.
         */
        internal fun objectIdentity(uri: String): String {
            val separator = uri.indexOf("://")
            return if (separator > 0 && uri.substring(0, separator).lowercase() in S3_SCHEMES) {
                "s3" + uri.substring(separator)
            } else {
                uri
            }
        }

        /**
         * Split an absolute `s3://bucket/key...` URI into `(bucket, key)`. Accepts `s3`/`s3a`/`s3n`
         * schemes without decoding or rewriting the key. Invalid/unsupported URIs throw
         * [IllegalArgumentException]; deletion reports those as per-object failures.
         */
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
