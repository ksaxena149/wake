package com.wake.dtn.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Epidemic-routing dedup table. Tracks every bundle id that has passed through
 * this node, regardless of whether the bundle was stored in the bundles table.
 *
 * [bundleId] matches server `seen_bundle_ids.bundle_id`: request bundles use
 * `query_id`; each response chunk uses `"$queryId:$chunkIndex"`.
 *
 * Column names align with the server SQLite schema (`bundle_id`, `seen_at`).
 */
@Entity(tableName = "seen_ids")
data class SeenIdEntity(
    @PrimaryKey @ColumnInfo(name = "bundle_id") val bundleId: String,
    @ColumnInfo(name = "seen_at") val seenAtMs: Long,
)
