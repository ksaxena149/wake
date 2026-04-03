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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BundleReassemblerTest {

    private lateinit var db: WakeDatabase
    private lateinit var storeManager: BundleStoreManager
    private lateinit var reassembler: BundleReassembler
    private lateinit var testFilesDir: File
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, WakeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        testFilesDir = File(context.filesDir, "test_reassembler").also { it.mkdirs() }
        storeManager = BundleStoreManager(
            context = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = testFilesDir
            },
            dao = db.bundleDao(),
        )
        reassembler = BundleReassembler(storeManager, testFilesDir)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) runCatching { db.close() }
        if (::testFilesDir.isInitialized) testFilesDir.deleteRecursively()
    }

    // --- helpers ---

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private suspend fun storeChunk(
        queryId: String,
        chunkIndex: Int,
        totalChunks: Int,
        bytes: ByteArray,
        contentType: String = "text/html",
        sha256Override: String? = null,
    ) {
        val entity = BundleEntity(
            bundleId = "$queryId:$chunkIndex",
            bundleType = BundleType.RESPONSE,
            queryId = queryId,
            sourceId = "server",
            timestampMs = 1_000L,
            ttlSeconds = 3_600,
            hopCount = 0,
            signature = null,
            queryString = null,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            contentType = contentType,
            payloadSha256 = sha256Override ?: sha256Hex(bytes),
            payloadFilePath = "${queryId}_${chunkIndex}.bundle",
            receivedAtMs = 1_000L,
        )
        storeManager.store(entity, bytes)
    }

    // --- isComplete ---

    @Test
    fun isComplete_returnsFalse_whenNoChunks() = runTest {
        assertFalse(reassembler.isComplete("nonexistent-qid"))
    }

    @Test
    fun isComplete_returnsFalse_whenMissingChunk() = runTest {
        // Store chunks 0 and 2 for a 3-chunk response; chunk 1 is missing.
        storeChunk("q-missing", 0, 3, byteArrayOf(1, 2))
        storeChunk("q-missing", 2, 3, byteArrayOf(5, 6))
        assertFalse(reassembler.isComplete("q-missing"))
    }

    @Test
    fun isComplete_returnsTrue_whenAllPresent() = runTest {
        storeChunk("q-full", 0, 3, byteArrayOf(1))
        storeChunk("q-full", 1, 3, byteArrayOf(2))
        storeChunk("q-full", 2, 3, byteArrayOf(3))
        assertTrue(reassembler.isComplete("q-full"))
    }

    // --- reassemble ---

    @Test
    fun reassemble_returnsNull_whenIncomplete() = runTest {
        storeChunk("q-partial", 0, 3, byteArrayOf(1))
        assertNull(reassembler.reassemble("q-partial"))
    }

    @Test
    fun reassemble_singleChunk_returnsCorrectPayload() = runTest {
        val bytes = "hello world".toByteArray()
        storeChunk("q-single", 0, 1, bytes)
        val result = reassembler.reassemble("q-single")
        assertNotNull(result)
        assertArrayEquals(bytes, result!!.bytes)
        assertEquals("q-single", result.queryId)
        assertEquals("text/html", result.contentType)
    }

    @Test
    fun reassemble_concatenatesChunksInOrder() = runTest {
        val chunk0 = byteArrayOf(1, 2, 3)
        val chunk1 = byteArrayOf(4, 5, 6)
        val chunk2 = byteArrayOf(7, 8, 9)
        // Store out-of-insertion-order to confirm DAO ordering drives reassembly, not insert order.
        storeChunk("q-order", 2, 3, chunk2)
        storeChunk("q-order", 0, 3, chunk0)
        storeChunk("q-order", 1, 3, chunk1)
        val result = reassembler.reassemble("q-order")
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), result!!.bytes)
    }

    @Test
    fun reassemble_rejectsCorruptedChunk_throwsIOException() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        storeChunk("q-corrupt", 0, 1, bytes)
        // Overwrite the payload file with different content so SHA-256 will not match.
        File(testFilesDir, "q-corrupt_0.bundle").writeBytes(byteArrayOf(9, 9, 9))
        try {
            reassembler.reassemble("q-corrupt")
            fail("Expected IOException for SHA-256 mismatch")
        } catch (e: IOException) {
            assertTrue("Exception message must mention SHA-256", e.message!!.contains("SHA-256"))
        }
    }

    @Test
    fun reassemble_throwsIOException_whenPayloadFileIsMissing() = runTest {
        storeChunk("q-gone", 0, 1, byteArrayOf(1, 2, 3))
        // Delete the file after it was stored so the reassembler cannot find it.
        assertTrue(File(testFilesDir, "q-gone_0.bundle").delete())
        try {
            reassembler.reassemble("q-gone")
            fail("Expected IOException for missing payload file")
        } catch (e: IOException) {
            assertTrue("Exception message must mention 'missing'", e.message!!.contains("missing"))
        }
    }

    @Test
    fun reassemble_throwsIOException_whenPayloadFilePathIsNull() = runTest {
        // Insert a RESPONSE entity with no payloadFilePath directly into the DAO, bypassing
        // BundleStoreManager, to exercise the null-path guard in BundleReassembler.
        val entity = BundleEntity(
            bundleId = "q-nullpath:0",
            bundleType = BundleType.RESPONSE,
            queryId = "q-nullpath",
            sourceId = "server",
            timestampMs = 1_000L,
            ttlSeconds = 3_600,
            hopCount = 0,
            signature = null,
            queryString = null,
            chunkIndex = 0,
            totalChunks = 1,
            contentType = "text/html",
            payloadSha256 = null,
            payloadFilePath = null,
            receivedAtMs = 1_000L,
        )
        db.bundleDao().insert(entity)
        try {
            reassembler.reassemble("q-nullpath")
            fail("Expected IOException for null payloadFilePath")
        } catch (e: IOException) {
            assertTrue(
                "Exception message must mention payloadFilePath",
                e.message!!.contains("payloadFilePath"),
            )
        }
    }

    @Test
    fun reassemble_throwsIOException_forPathTraversalAttempt() = runTest {
        // Insert a RESPONSE entity with a path that escapes testFilesDir, simulating a
        // maliciously crafted DB row. BundleReassembler.safeFile must reject it.
        val entity = BundleEntity(
            bundleId = "q-traversal:0",
            bundleType = BundleType.RESPONSE,
            queryId = "q-traversal",
            sourceId = "server",
            timestampMs = 1_000L,
            ttlSeconds = 3_600,
            hopCount = 0,
            signature = null,
            queryString = null,
            chunkIndex = 0,
            totalChunks = 1,
            contentType = "text/html",
            payloadSha256 = null,
            payloadFilePath = "../../escape.bundle",
            receivedAtMs = 1_000L,
        )
        db.bundleDao().insert(entity)
        try {
            reassembler.reassemble("q-traversal")
            fail("Expected IOException for path traversal attempt")
        } catch (e: IOException) {
            assertTrue(
                "Exception message must mention rejected path",
                e.message!!.contains("Rejected"),
            )
        }
    }
}
