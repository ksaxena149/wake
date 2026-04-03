package com.wake.dtn.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeenIdDaoTest {

    private lateinit var db: WakeDatabase
    private lateinit var dao: SeenIdDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WakeDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.seenIdDao()
    }

    @After
    fun closeDb() {
        if (::db.isInitialized) runCatching { db.close() }
    }

    @Test
    fun existsReturnsFalseBeforeInsert() = runTest {
        assertFalse(dao.exists("bundle-unknown"))
    }

    @Test
    fun existsReturnsTrueAfterInsert() = runTest {
        dao.insert(SeenIdEntity(bundleId = "bundle-1", seenAtMs = 1_000L))
        assertTrue(dao.exists("bundle-1"))
    }

    @Test
    fun duplicateInsertDoesNotThrow() = runTest {
        val entry = SeenIdEntity(bundleId = "bundle-dup", seenAtMs = 1_000L)
        dao.insert(entry)
        dao.insert(entry.copy(seenAtMs = 9_999L)) // should be silently ignored
        assertTrue(dao.exists("bundle-dup"))
    }

    @Test
    fun deleteOlderThanRemovesStaleKeepsFresh() = runTest {
        dao.insert(SeenIdEntity(bundleId = "old-1", seenAtMs = 500L))
        dao.insert(SeenIdEntity(bundleId = "old-2", seenAtMs = 999L))
        dao.insert(SeenIdEntity(bundleId = "fresh", seenAtMs = 2_000L))

        dao.deleteOlderThan(cutoffMs = 1_000L)

        assertFalse(dao.exists("old-1"))
        assertFalse(dao.exists("old-2"))
        assertTrue(dao.exists("fresh"))
    }
}
