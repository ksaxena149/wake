package com.wake.dtn.service

import android.util.Base64
import android.util.Log
import com.wake.dtn.data.BundleEntity
import com.wake.dtn.data.BundleStoreManager
import com.wake.dtn.data.BundleType

/**
 * Orchestrates WAKE server sync: submits request bundles, polls for pending results,
 * fetches response chunks, and stores them via [BundleStoreManager].
 */
class ServerSyncManager(
    private val httpClient: WakeHttpClient,
    private val storeManager: BundleStoreManager,
    val nodeId: String,
) {
    /**
     * POST a request bundle to the server. Fire-and-forget: the server queues the request
     * and response chunks are collected later via [pollAndFetch]. Throws [java.io.IOException]
     * on non-2xx responses.
     */
    suspend fun submitRequest(queryId: String, queryString: String) {
        httpClient.submitRequest(
            nodeId = nodeId,
            queryId = queryId,
            queryString = queryString,
        )
    }

    /**
     * Poll [/pending] and fetch + store all outstanding response bundles.
     * A failure for one query ID is logged and skipped; the rest of the loop continues.
     */
    suspend fun pollAndFetch() {
        val pending = httpClient.fetchPending(nodeId)
        if (pending.isEmpty()) return

        Log.d(TAG, "pollAndFetch: found ${pending.size} pending IDs")
        for (queryId in pending) {
            runCatching {
                storeChunks(httpClient.fetchBundle(queryId))
            }.onFailure { e ->
                Log.w(TAG, "Failed to fetch bundle for queryId=$queryId", e)
            }
        }
    }

    private suspend fun storeChunks(chunks: List<ResponseBundleDto>) {
        val nowMs = System.currentTimeMillis()
        for (chunk in chunks) {
            val payloadBytes = Base64.decode(chunk.payloadB64, Base64.DEFAULT)
            storeManager.store(chunk.toEntity(nowMs), payloadBytes)
        }
    }

    companion object {
        private const val TAG = "ServerSyncManager"
    }
}

private fun ResponseBundleDto.toEntity(receivedAtMs: Long) = BundleEntity(
    bundleId = "$queryId:$chunkIndex",
    bundleType = BundleType.RESPONSE,
    queryId = queryId,
    sourceId = serverId,
    timestampMs = receivedAtMs,
    // Server does not expose TTL in the response body; default used until Phase 4 routing.
    ttlSeconds = DEFAULT_RESPONSE_TTL_SECONDS,
    hopCount = 0,
    signature = signature,
    queryString = null,
    chunkIndex = chunkIndex,
    totalChunks = totalChunks,
    contentType = contentType,
    payloadSha256 = sha256,
    payloadFilePath = "${queryId}_${chunkIndex}.bundle",
    receivedAtMs = receivedAtMs,
)

private const val DEFAULT_RESPONSE_TTL_SECONDS = 3_600
