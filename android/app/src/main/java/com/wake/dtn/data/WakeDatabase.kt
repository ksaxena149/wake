package com.wake.dtn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BundleEntity::class, SeenIdEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class WakeDatabase : RoomDatabase() {

    abstract fun bundleDao(): BundleDao
    abstract fun seenIdDao(): SeenIdDao

    companion object {
        @Volatile private var instance: WakeDatabase? = null

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE bundles ADD COLUMN payloadSizeBytes INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** v1 used camelCase columns; align with server `bundle_id` / `seen_at`. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS seen_ids_new (
                        bundle_id TEXT NOT NULL,
                        seen_at INTEGER NOT NULL,
                        PRIMARY KEY(bundle_id)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO seen_ids_new (bundle_id, seen_at)
                    SELECT bundleId, seenAtMs FROM seen_ids
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE seen_ids")
                db.execSQL("ALTER TABLE seen_ids_new RENAME TO seen_ids")
            }
        }

        fun getInstance(context: Context): WakeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WakeDatabase::class.java,
                    "wake.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
