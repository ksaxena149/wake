package com.wake.dtn.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SeenIdDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seenId: SeenIdEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM seen_ids WHERE bundleId = :bundleId)")
    suspend fun exists(bundleId: String): Boolean

    /** Removes entries older than cutoffMs to keep the table bounded on long-running nodes. */
    @Query("DELETE FROM seen_ids WHERE seenAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
