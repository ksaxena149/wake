package com.wake.dtn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted metadata for a single bundle (request or response chunk).
 *
 * bundleId is deterministic: queryId for REQUEST, "$queryId:$chunkIndex" for RESPONSE.
 * This lets OnConflictStrategy.IGNORE act as a cheap dedup gate on insert.
 *
 * Payload bytes for response chunks are stored as files in context.filesDir;
 * payloadFilePath holds the name relative to that directory.
 * Nullable protocol-specific fields are clearly separated by type in comments.
 */
@Entity(tableName = "bundles")
data class BundleEntity(
    @PrimaryKey val bundleId: String,
    val bundleType: BundleType,
    val queryId: String,
    /** node_id for REQUEST, server_id for RESPONSE */
    val sourceId: String,
    /** Protocol timestamp from the bundle itself (epoch ms). */
    val timestampMs: Long,
    val ttlSeconds: Int,
    val hopCount: Int,
    /** Ed25519 signature — nullable until Phase 5 makes verification mandatory. */
    val signature: String?,

    // REQUEST-specific
    val queryString: String?,

    // RESPONSE-specific
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val contentType: String?,
    /** SHA-256 of the raw payload bytes, for file integrity checks before caching. */
    val payloadSha256: String?,
    /** Filename relative to context.filesDir — e.g. "{queryId}_{chunkIndex}.bundle" */
    val payloadFilePath: String?,

    // Local tracking
    /** Wall-clock time this node received/stored the bundle (epoch ms). Used for LRU eviction. */
    val receivedAtMs: Long,
    val isDelivered: Boolean = false,
    /** Size of the stored payload file in bytes. Zero for REQUEST bundles and missing files. */
    val payloadSizeBytes: Long = 0,
)
