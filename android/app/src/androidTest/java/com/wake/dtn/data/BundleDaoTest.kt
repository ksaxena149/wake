package com.wake.dtn.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundleDaoTest {

    private lateinit var db: WakeDatabase
    private lateinit var dao: BundleDao

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WakeDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.bundleDao()
    }

    @After
    fun closeDb() = db.close()

    // --- helpers ---

    private fun requestBundle(
        queryId: String = "qid-1",
        bundleId: String = queryId,
        receivedAtMs: Long = 1_000L,
        ttlSeconds: Int = 3600,
    ) = BundleEntity(
        bundleId = bundleId,
        bundleType = BundleType.REQUEST,
        queryId = queryId,
        sourceId = "node-abc",
        timestampMs = 500L,
        ttlSeconds = ttlSeconds,
        hopCount = 0,
        signature = null,
        queryString = "water cycle",
        chunkIndex = 0,
        totalChunks = 1,
        contentType = null,
        payloadSha256 = null,
        payloadFilePath = null,
        receivedAtMs = receivedAtMs,
    )

    private fun responseChunk(
        queryId: String = "qid-1",
        chunkIndex: Int = 0,
        totalChunks: Int = 2,
        receivedAtMs: Long = 2_000L,
        ttlSeconds: Int = 3600,
    ) = BundleEntity(
        bundleId = "$queryId:$chunkIndex",
        bundleType = BundleType.RESPONSE,
        queryId = queryId,
        sourceId = "server-xyz",
        timestampMs = 1_500L,
        ttlSeconds = ttlSeconds,
        hopCount = 1,
        signature = null,
        queryString = null,
        chunkIndex = chunkIndex,
        totalChunks = totalChunks,
        contentType = "text/html",
        payloadSha256 = "abc123",
        payloadFilePath = "${queryId}_${chunkIndex}.bundle",
        receivedAtMs = receivedAtMs,
    )

    // --- tests ---

    @Test
    fun insertAndGetById() = runTest {
        val bundle = requestBundle()
        dao.insert(bundle)
        assertEquals(bundle, dao.getById(bundle.bundleId))
    }

    @Test
    fun insertDuplicateIsIgnored() = runTest {
        val original = requestBundle(queryId = "qid-dup")
        val duplicate = original.copy(hopCount = 99)
        dao.insert(original)
        dao.insert(duplicate)
        // original should survive; duplicate's hopCount change must not overwrite
        assertEquals(0, dao.getById("qid-dup")!!.hopCount)
    }

    @Test
    fun getByQueryIdReturnsBothChunks() = runTest {
        dao.insert(responseChunk(chunkIndex = 0))
        dao.insert(responseChunk(chunkIndex = 1))
        dao.insert(requestBundle(queryId = "other-qid"))
        val results = dao.getByQueryId("qid-1")
        assertEquals(2, results.size)
    }

    @Test
    fun getResponseChunksFiltersTypeAndOrders() = runTest {
        dao.insert(responseChunk(chunkIndex = 1))
        dao.insert(responseChunk(chunkIndex = 0))
        dao.insert(requestBundle()) // same queryId but REQUEST — must be excluded
        val chunks = dao.getResponseChunks("qid-1")
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[1].chunkIndex)
    }

    @Test
    fun getExpiredReturnsBundlesPastTtl() = runTest {
        // receivedAtMs=1000, ttlSeconds=1 → expires at 2000ms
        dao.insert(responseChunk(chunkIndex = 0, receivedAtMs = 1_000L, ttlSeconds = 1))
        // receivedAtMs=5000, ttlSeconds=3600 → not expired
        dao.insert(responseChunk(chunkIndex = 1, receivedAtMs = 5_000L, ttlSeconds = 3600))

        val expired = dao.getExpired(nowMs = 3_000L)
        assertEquals(1, expired.size)
        assertEquals("qid-1:0", expired[0].bundleId)
    }

    @Test
    fun deleteRemovesRow() = runTest {
        val bundle = requestBundle()
        dao.insert(bundle)
        dao.delete(bundle)
        assertNull(dao.getById(bundle.bundleId))
    }

    @Test
    fun updateReflectsDelivered() = runTest {
        val bundle = requestBundle()
        dao.insert(bundle)
        dao.update(bundle.copy(isDelivered = true))
        assertTrue(dao.getById(bundle.bundleId)!!.isDelivered)
    }

    @Test
    fun getAllByReceivedTimeIsAscending() = runTest {
        dao.insert(responseChunk(chunkIndex = 0, receivedAtMs = 300L))
        dao.insert(responseChunk(chunkIndex = 1, receivedAtMs = 100L))
        dao.insert(requestBundle(queryId = "other", bundleId = "other", receivedAtMs = 200L))
        val ordered = dao.getAllByReceivedTime()
        assertEquals(listOf(100L, 200L, 300L), ordered.map { it.receivedAtMs })
    }
}
