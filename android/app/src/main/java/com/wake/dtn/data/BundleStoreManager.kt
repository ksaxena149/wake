package com.wake.dtn.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Single entry point for all bundle I/O: write metadata to Room, write payload bytes to
 * filesDir, and enforce eviction policies so the node never exceeds [maxStoreBytes].
 *
 * Two eviction passes run independently:
 *  - TTL: remove any bundle whose TTL window has elapsed (called periodically by WakeService).
 *  - LRU: after every [store], if total payload bytes exceed [maxStoreBytes], discard the
 *    oldest-received bundles until we are back under the cap.
 *
 * [maxStoreBytes] is a constructor parameter (default 500 MB) so tests can exercise the LRU
 * path with small payloads without writing gigabytes of test data.
 */
class BundleStoreManager(
    private val context: Context,
    private val dao: BundleDao,
    val maxStoreBytes: Long = MAX_STORE_BYTES,
) {

    private val filesDir: File get() = context.filesDir

    /**
     * Serializes the check-write-insert sequence so that two concurrent calls with the same
     * bundleId cannot both pass the existence check and produce a file whose bytes disagree
     * with the size recorded in the database.
     */
    private val storeMutex = Mutex()

    /**
     * Persist a bundle. If [payload] is provided and the entity has a [BundleEntity.payloadFilePath],
     * the bytes are written to [filesDir] and the size is recorded in [BundleEntity.payloadSizeBytes].
     *
     * Duplicate bundleIds are silently ignored. The [storeMutex] makes the existence-check,
     * file-write, and DB-insert atomic with respect to other concurrent [store] callers, so
     * the file on disk and the size in the database are always consistent.
     */
    suspend fun store(entity: BundleEntity, payload: ByteArray? = null) {
        storeMutex.withLock {
            if (dao.getById(entity.bundleId) != null) return

            val entityToInsert = if (payload != null && entity.payloadFilePath != null) {
                File(filesDir, entity.payloadFilePath).writeBytes(payload)
                entity.copy(payloadSizeBytes = payload.size.toLong())
            } else {
                entity
            }

            dao.insert(entityToInsert)
        }
        runLruEviction()
    }

    suspend fun getById(bundleId: String): BundleEntity? = dao.getById(bundleId)

    suspend fun getResponseChunks(queryId: String): List<BundleEntity> =
        dao.getResponseChunks(queryId)

    suspend fun markDelivered(bundleId: String) {
        dao.getById(bundleId)?.let { dao.update(it.copy(isDelivered = true)) }
    }

    /** Delete every bundle whose TTL window has elapsed. */
    suspend fun runTtlEviction(nowMs: Long = System.currentTimeMillis()) {
        dao.getExpired(nowMs).forEach { evict(it) }
    }

    /**
     * Delete oldest bundles (by [BundleEntity.receivedAtMs]) until total payload bytes are
     * back under [maxStoreBytes]. No-op if already under cap.
     */
    suspend fun runLruEviction() {
        val totalBytes = dao.getTotalPayloadBytes()
        if (totalBytes <= maxStoreBytes) return

        var bytesToFree = totalBytes - maxStoreBytes
        for (bundle in dao.getAllByReceivedTime()) {
            if (bytesToFree <= 0) break
            bytesToFree -= bundle.payloadSizeBytes
            evict(bundle)
        }
    }

    /** Remove a bundle's payload file (if any) and its DB row. */
    private suspend fun evict(entity: BundleEntity) {
        entity.payloadFilePath?.let { File(filesDir, it).delete() }
        dao.delete(entity)
    }

    companion object {
        const val MAX_STORE_BYTES = 500L * 1024 * 1024
    }
}
