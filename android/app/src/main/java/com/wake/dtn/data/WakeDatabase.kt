package com.wake.dtn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [BundleEntity::class, SeenIdEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class WakeDatabase : RoomDatabase() {

    abstract fun bundleDao(): BundleDao
    abstract fun seenIdDao(): SeenIdDao

    companion object {
        @Volatile private var instance: WakeDatabase? = null

        fun getInstance(context: Context): WakeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WakeDatabase::class.java,
                    "wake.db",
                ).build().also { instance = it }
            }
    }
}
