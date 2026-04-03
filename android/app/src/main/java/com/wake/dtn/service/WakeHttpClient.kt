package com.wake.dtn.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
internal data class RequestBundleDto(
    @SerialName("node_id") val nodeId: String,
    @SerialName("query_id") val queryId: String,
    @SerialName("query_string") val queryString: String,
    val timestamp: Long,
    @SerialName("ttl_seconds") val ttlSeconds: Int,
    @SerialName("hop_count") val hopCount: Int,
    val signature: String? = null,
)

@Serializable
data class ResponseBundleDto(
    @SerialName("server_id") val serverId: String,
    @SerialName("query_id") val queryId: String,
    @SerialName("chunk_index") val chunkIndex: Int,
    @SerialName("total_chunks") val totalChunks: Int,
    @SerialName("content_type") val contentType: String,
    @SerialName("payload_b64") val payloadB64: String,
    val sha256: String,
    val signature: String? = null,
)

@Serializable
private data class PendingResponseDto(
    @SerialName("pending_query_ids") val pendingQueryIds: List<String>,
)

@Serializable
private data class PubkeyResponseDto(
    @SerialName("pubkey_b64") val pubkeyB64: String,
)

/**
 * Thin OkHttp wrapper for the three WAKE server endpoints.
 *
 * Every call blocks on [Dispatchers.IO]. A non-2xx HTTP status throws [IOException]
 * with the code and body included, so callers can log useful detail.
 *
 * [baseUrl] must not have a trailing slash (e.g. "http://10.0.2.2:8000").
 */
class WakeHttpClient(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val baseHttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * POST a request bundle to the server and return the signed response chunks the server
     * sends back immediately. The server always processes the request synchronously and returns
     * all chunks in the response body, so callers can store them right away without waiting for
     * a later [fetchBundle] poll. Throws [IOException] on non-2xx.
     */
    suspend fun submitRequest(
        nodeId: String,
        queryId: String,
        queryString: String,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS,
    ): List<ResponseBundleDto> {
        val dto = RequestBundleDto(
            nodeId = nodeId,
            queryId = queryId,
            queryString = queryString,
            // Server expects Unix seconds, not milliseconds.
            timestamp = System.currentTimeMillis() / 1_000L,
            ttlSeconds = ttlSeconds,
            hopCount = 0,
        )
        val request = Request.Builder()
            .url(baseHttpUrl.newBuilder().addPathSegment("request").build())
            .post(json.encodeToString(dto).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return json.decodeFromString(executeAndGetBody(request))
    }

    /** GET /pending?node_id={nodeId} and return the list of query IDs the server has ready for this node. */
    suspend fun fetchPending(nodeId: String): List<String> {
        val request = Request.Builder()
            .url(
                baseHttpUrl.newBuilder()
                    .addPathSegment("pending")
                    .addQueryParameter("node_id", nodeId)
                    .build()
            )
            .get()
            .build()
        return json.decodeFromString<PendingResponseDto>(executeAndGetBody(request)).pendingQueryIds
    }

    /** GET /pubkey and return the server's 32-byte Ed25519 verify key as raw bytes. */
    suspend fun fetchPubkey(): ByteArray {
        val request = Request.Builder()
            .url(baseHttpUrl.newBuilder().addPathSegment("pubkey").build())
            .get()
            .build()
        val body = executeAndGetBody(request)
        val dto = json.decodeFromString<PubkeyResponseDto>(body)
        return android.util.Base64.decode(dto.pubkeyB64, android.util.Base64.DEFAULT).also {
            require(it.size == 32) { "Unexpected Ed25519 key length: ${it.size}" }
        }
    }

    /** GET /bundle/{queryId} and return all stored response chunks for that query. */
    suspend fun fetchBundle(queryId: String): List<ResponseBundleDto> {
        val url = baseHttpUrl.newBuilder()
            .addPathSegment("bundle")
            .addPathSegment(queryId)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return json.decodeFromString(executeAndGetBody(request))
    }

    private suspend fun executeAndGetBody(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $body")
            }
            body
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_TTL_SECONDS = 3_600
    }
}
