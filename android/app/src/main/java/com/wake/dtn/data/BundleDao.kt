package com.wake.dtn.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface BundleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bundle: BundleEntity)

    @Query("SELECT * FROM bundles WHERE bundleId = :bundleId")
    suspend fun getById(bundleId: String): BundleEntity?

    @Query("SELECT * FROM bundles WHERE queryId = :queryId")
    suspend fun getByQueryId(queryId: String): List<BundleEntity>

    /** Returns all RESPONSE chunks for a query, ordered for reassembly. */
    @Query("SELECT * FROM bundles WHERE bundleType = 'RESPONSE' AND queryId = :queryId ORDER BY chunkIndex ASC")
    suspend fun getResponseChunks(queryId: String): List<BundleEntity>

    /** All bundles ordered oldest-received-first — primary input for LRU eviction. */
    @Query("SELECT * FROM bundles ORDER BY receivedAtMs ASC")
    suspend fun getAllByReceivedTime(): List<BundleEntity>

    /** Bundles whose TTL window has elapsed — primary input for TTL eviction. */
    @Query("SELECT * FROM bundles WHERE (receivedAtMs + ttlSeconds * 1000) < :nowMs")
    suspend fun getExpired(nowMs: Long): List<BundleEntity>

    /** Total bytes of stored payload files — used by LRU cap check. COALESCE handles empty table. */
    @Query("SELECT COALESCE(SUM(payloadSizeBytes), 0) FROM bundles")
    suspend fun getTotalPayloadBytes(): Long

    @Delete
    suspend fun delete(bundle: BundleEntity)

    @Update
    suspend fun update(bundle: BundleEntity)
}
