package com.wake.dtn.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BundleStoreManagerTest {

    private lateinit var db: WakeDatabase
    private lateinit var manager: BundleStoreManager
    private lateinit var testFilesDir: File
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, WakeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Isolate test files from real app data.
        testFilesDir = File(context.filesDir, "test_bundle_store").also { it.mkdirs() }

        // 100-byte cap makes LRU eviction easy to trigger in tests.
        manager = BundleStoreManager(
            context = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = testFilesDir
            },
            dao = db.bundleDao(),
            maxStoreBytes = 100L,
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) runCatching { db.close() }
        if (::testFilesDir.isInitialized) testFilesDir.deleteRecursively()
    }

    // --- helpers ---

    private fun responseEntity(
        queryId: String = "q1",
        chunkIndex: Int = 0,
        receivedAtMs: Long = 1_000L,
        ttlSeconds: Int = 3_600,
        payloadSizeBytes: Long = 0,
    ) = BundleEntity(
        bundleId = "$queryId:$chunkIndex",
        bundleType = BundleType.RESPONSE,
        queryId = queryId,
        sourceId = "server",
        timestampMs = 500L,
        ttlSeconds = ttlSeconds,
        hopCount = 1,
        signature = null,
        queryString = null,
        chunkIndex = chunkIndex,
        totalChunks = 1,
        contentType = "text/html",
        payloadSha256 = null,
        payloadFilePath = "${queryId}_${chunkIndex}.bundle",
        receivedAtMs = receivedAtMs,
        payloadSizeBytes = payloadSizeBytes,
    )

    private fun requestEntity(
        queryId: String = "q1",
        receivedAtMs: Long = 1_000L,
        ttlSeconds: Int = 3_600,
    ) = BundleEntity(
        bundleId = queryId,
        bundleType = BundleType.REQUEST,
        queryId = queryId,
        sourceId = "node-a",
        timestampMs = 400L,
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

    // --- store ---

    @Test
    fun store_insertsEntityIntoDb() = runTest {
        manager.store(responseEntity())
        assertNotNull(db.bundleDao().getById("q1:0"))
    }

    @Test
    fun store_writesPayloadFileToFilesDir() = runTest {
        val payload = "hello world".toByteArray()
        manager.store(responseEntity(), payload)

        val file = File(testFilesDir, "q1_0.bundle")
        assertTrue(file.exists())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun store_recordsPayloadSizeBytesInDb() = runTest {
        val payload = ByteArray(60) { it.toByte() }
        manager.store(responseEntity(), payload)

        val stored = db.bundleDao().getById("q1:0")!!
        assertEquals(60L, stored.payloadSizeBytes)
    }

    @Test
    fun store_duplicateIgnored_fileNotOverwritten() = runTest {
        val original = "original".toByteArray()
        manager.store(responseEntity(), original)
        manager.store(responseEntity(), "overwrite".toByteArray())

        assertArrayEquals(original, File(testFilesDir, "q1_0.bundle").readBytes())
        // DB row stays at original size
        assertEquals(original.size.toLong(), db.bundleDao().getById("q1:0")!!.payloadSizeBytes)
    }

    @Test
    fun store_noPayload_noFileCreated() = runTest {
        manager.store(responseEntity())
        assertFalse(File(testFilesDir, "q1_0.bundle").exists())
    }

    @Test
    fun store_noTempFilesRemainAfterSuccessfulStore() = runTest {
        manager.store(responseEntity(), ByteArray(10))
        val files = testFilesDir.listFiles() ?: emptyArray()
        assertTrue("expected exactly one file", files.size == 1)
        assertFalse("temp file must not survive", files[0].name.endsWith(".tmp"))
    }

    @Test
    fun store_requestBundle_noFileWritten() = runTest {
        manager.store(requestEntity())
        assertNotNull(db.bundleDao().getById("q1"))
        // filesDir should be empty — no payloadFilePath on REQUEST
        assertEquals(0, testFilesDir.listFiles()?.size ?: 0)
    }

    // --- getById / getResponseChunks ---

    @Test
    fun getById_returnsNullIfAbsent() = runTest {
        assertNull(manager.getById("nonexistent"))
    }

    @Test
    fun getResponseChunks_returnsChunksInOrder() = runTest {
        manager.store(responseEntity(chunkIndex = 1))
        manager.store(responseEntity(chunkIndex = 0))
        val chunks = manager.getResponseChunks("q1")
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[1].chunkIndex)
    }

    // --- markDelivered ---

    @Test
    fun markDelivered_setsIsDeliveredTrue() = runTest {
        manager.store(responseEntity())
        manager.markDelivered("q1:0")
        assertTrue(db.bundleDao().getById("q1:0")!!.isDelivered)
    }

    @Test
    fun markDelivered_noopForUnknownId() = runTest {
        // Must not throw
        manager.markDelivered("unknown-bundle")
    }

    // --- TTL eviction ---

    @Test
    fun runTtlEviction_removesExpiredBundleAndFile() = runTest {
        val payload = ByteArray(10)
        // receivedAtMs=1000, ttlSeconds=1 → expires at 2000 ms
        manager.store(responseEntity(receivedAtMs = 1_000L, ttlSeconds = 1), payload)

        manager.runTtlEviction(nowMs = 3_000L)

        assertNull(db.bundleDao().getById("q1:0"))
        assertFalse(File(testFilesDir, "q1_0.bundle").exists())
    }

    @Test
    fun runTtlEviction_keepsUnexpiredBundle() = runTest {
        // receivedAtMs=1000, ttlSeconds=3600 → expires at 3_601_000 ms
        manager.store(responseEntity(receivedAtMs = 1_000L, ttlSeconds = 3_600))

        manager.runTtlEviction(nowMs = 3_000L)

        assertNotNull(db.bundleDao().getById("q1:0"))
    }

    // --- LRU eviction ---

    @Test
    fun runLruEviction_noopWhenUnderCap() = runTest {
        // maxStoreBytes=100; 40 bytes is under cap
        manager.store(responseEntity(receivedAtMs = 1_000L), ByteArray(40))
        manager.runLruEviction()
        assertNotNull(db.bundleDao().getById("q1:0"))
    }

    @Test
    fun runLruEviction_evictsOldestWhenOverCap() = runTest {
        // maxStoreBytes=100. Store 60 bytes old + 60 bytes new = 120 bytes → over cap.
        // LRU should evict the oldest (q1:0, receivedAtMs=100) to bring total to 60.
        manager.store(responseEntity(queryId = "q1", chunkIndex = 0, receivedAtMs = 100L), ByteArray(60))
        manager.store(responseEntity(queryId = "q2", chunkIndex = 0, receivedAtMs = 200L), ByteArray(60))

        // Second store triggers runLruEviction internally
        assertNull(db.bundleDao().getById("q1:0"))
        assertFalse(File(testFilesDir, "q1_0.bundle").exists())
        assertNotNull(db.bundleDao().getById("q2:0"))
    }

    @Test
    fun runLruEviction_evictsMultipleBundlesIfNeeded() = runTest {
        // Three 40-byte bundles = 120 bytes. Cap = 100. Must evict the two oldest.
        manager.store(responseEntity(queryId = "q1", chunkIndex = 0, receivedAtMs = 100L), ByteArray(40))
        manager.store(responseEntity(queryId = "q2", chunkIndex = 0, receivedAtMs = 200L), ByteArray(40))
        manager.store(responseEntity(queryId = "q3", chunkIndex = 0, receivedAtMs = 300L), ByteArray(40))

        // After third store: 120 bytes stored, need to free 20+ bytes.
        // Oldest is q1 (40 bytes) — evicting it brings us to 80, which is under cap.
        assertNull(db.bundleDao().getById("q1:0"))
        assertNotNull(db.bundleDao().getById("q2:0"))
        assertNotNull(db.bundleDao().getById("q3:0"))
    }

    @Test
    fun runLruEviction_doesNotEvictZeroByteRows() = runTest {
        // Store a REQUEST bundle (no payload, payloadSizeBytes = 0) at the oldest timestamp.
        // Then push the store over the cap with a real payload bundle.
        // LRU must free space by evicting the payload bundle, not the zero-byte REQUEST row.
        manager.store(requestEntity(queryId = "req", receivedAtMs = 50L))
        manager.store(responseEntity(queryId = "q1", chunkIndex = 0, receivedAtMs = 200L), ByteArray(60))
        manager.store(responseEntity(queryId = "q2", chunkIndex = 0, receivedAtMs = 300L), ByteArray(60))

        // Cap = 100; 120 bytes stored. LRU should evict q1 (oldest payload), not the REQUEST row.
        assertNotNull(db.bundleDao().getById("req"))
        assertNull(db.bundleDao().getById("q1:0"))
        assertNotNull(db.bundleDao().getById("q2:0"))
    }

    // --- path traversal guard ---

    @Test(expected = IOException::class)
    fun store_rejectsPathTraversal() = runTest {
        val traversalEntity = responseEntity().copy(payloadFilePath = "../escape.txt")
        manager.store(traversalEntity, "should not write".toByteArray())
    }

    @Test(expected = IOException::class)
    fun store_rejectsAbsolutePath() = runTest {
        val absoluteEntity = responseEntity().copy(payloadFilePath = "/data/data/com.wake.dtn/evil.txt")
        manager.store(absoluteEntity, "should not write".toByteArray())
    }
}
