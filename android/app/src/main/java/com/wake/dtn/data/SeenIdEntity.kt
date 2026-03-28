package com.wake.dtn.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Epidemic-routing dedup table. Tracks every bundleId that has passed through
 * this node, regardless of whether the bundle was stored in the bundles table.
 *
 * Kept intentionally minimal: existence check is O(1) by primary key, and
 * deleteOlderThan allows periodic cleanup so the table never grows unbounded.
 */
@Entity(tableName = "seen_ids")
data class SeenIdEntity(
    @PrimaryKey val bundleId: String,
    val seenAtMs: Long,
)
