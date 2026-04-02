package com.wake.dtn.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wake.dtn.data.BundleReassembler
import com.wake.dtn.data.BundleStoreManager
import com.wake.dtn.data.BundleType
import com.wake.dtn.data.ReassembledBundle
import com.wake.dtn.data.WakeDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerSyncManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var db: WakeDatabase
    private lateinit var storeManager: BundleStoreManager
    private lateinit var syncManager: ServerSyncManager
    private lateinit var testFilesDir: File

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(context, WakeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        testFilesDir = File(context.filesDir, "test_sync_manager").also { it.mkdirs() }

        storeManager = BundleStoreManager(
            context = object : android.content.ContextWrapper(context) {
                override fun getFilesDir(): File = testFilesDir
            },
            dao = db.bundleDao(),
        )

        syncManager = ServerSyncManager(
            httpClient = WakeHttpClient(baseUrl = server.url("/").toString().trimEnd('/')),
            storeManager = storeManager,
            nodeId = "test-node-id",
            reassembler = BundleReassembler(storeManager, testFilesDir),
        )
    }

    @After
    fun tearDown() {
        // JUnit runs @After even when @Before threw — guard every lateinit.
        if (::db.isInitialized) runCatching { db.close() }
        if (::testFilesDir.isInitialized) testFilesDir.deleteRecursively()
        if (::server.isInitialized) runCatching { server.shutdown() }
    }

    // --- helpers ---

    private fun chunkJson(
        queryId: String = "qid-1",
        chunkIndex: Int = 0,
        totalChunks: Int = 1,
        // "aGVsbG8=" is base64("hello"); sha256 must match the decoded bytes.
        payloadB64: String = "aGVsbG8=",
        sha256: String = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    ) = """{"server_id":"wake-server-01","query_id":"$queryId","chunk_index":$chunkIndex,""" +
        """"total_chunks":$totalChunks,"content_type":"text/html","payload_b64":"$payloadB64",""" +
        """"sha256":"$sha256","signature":null}"""

    private fun chunksJson(vararg chunks: String) = "[${chunks.joinToString(",")}]"

    private fun pendingJson(vararg ids: String) =
        """{"pending_query_ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""

    // --- submitRequest ---

    @Test
    fun submitRequest_sendsPostWithCorrectBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        syncManager.submitRequest("qid-1", "water cycle")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue("path must end with /request", req.path!!.endsWith("/request"))
        val body = req.body.readUtf8()
        assertTrue("body must contain node_id", body.contains("\"node_id\""))
        assertTrue("body must contain query_id value", body.contains("\"qid-1\""))
        assertTrue("body must contain query_string value", body.contains("\"water cycle\""))
    }

    @Test
    fun submitRequest_doesNotStoreAnythingInDb() = runTest {
        // POST /request is fire-and-forget; chunks arrive later via pollAndFetch.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        syncManager.submitRequest("qid-1", "water cycle")
        assertTrue(db.bundleDao().getByQueryId("qid-1").isEmpty())
    }

    @Test
    fun submitRequest_noPayloadFileWritten() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        syncManager.submitRequest("qid-1", "water cycle")
        assertTrue("no bundle file should be written on submit", testFilesDir.listFiles().orEmpty().isEmpty())
    }

    // --- pollAndFetch ---

    @Test
    fun pollAndFetch_storesAllPendingBundles() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-a", "qid-b")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson(queryId = "qid-a"))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson(queryId = "qid-b"))))
        syncManager.pollAndFetch()
        val pendingPath = server.takeRequest().path!!
        assertTrue(
            "GET /pending must include node_id query param",
            pendingPath.contains("node_id=test-node-id"),
        )
        assertNotNull(db.bundleDao().getById("qid-a:0"))
        assertNotNull(db.bundleDao().getById("qid-b:0"))
    }

    @Test
    fun pollAndFetch_skipsFailingQueryId_continuesRest() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-fail", "qid-ok")))
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson(queryId = "qid-ok"))))
        // Must not throw despite the 404 on the first ID
        syncManager.pollAndFetch()
        val pendingPath = server.takeRequest().path!!
        assertTrue(
            "GET /pending must include node_id query param",
            pendingPath.contains("node_id=test-node-id"),
        )
        assertNull(db.bundleDao().getById("qid-fail:0"))
        assertNotNull(db.bundleDao().getById("qid-ok:0"))
    }

    @Test
    fun pollAndFetch_emptyPending_noDbWrites() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson()))
        syncManager.pollAndFetch()
        val pendingPath = server.takeRequest().path!!
        assertTrue(
            "GET /pending must include node_id query param",
            pendingPath.contains("node_id=test-node-id"),
        )
        // No /bundle/ requests should have been made
        assertEquals(1, server.requestCount) // only the /pending call
        assertTrue(db.bundleDao().getByQueryId("any").isEmpty())
    }

    @Test
    fun pollAndFetch_doesNotRefetchAlreadyFetchedQueryId() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-once")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson(queryId = "qid-once"))))
        syncManager.pollAndFetch()

        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-once")))
        syncManager.pollAndFetch()

        assertEquals(
            "First poll makes /pending + /bundle; second poll makes only /pending",
            3,
            server.requestCount,
        )
    }

    @Test
    fun pollAndFetch_emitsReassembledBundle_whenAllChunksArriveWithCorrectSha256() = runTest {
        // SHA-256 of the bytes decoded from "aGVsbG8=" (== "hello").
        val sha256OfHello = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-emit")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                chunksJson(chunkJson(queryId = "qid-emit", sha256 = sha256OfHello))
            )
        )

        // Subscribe before polling so the SharedFlow (replay=0) emission is not missed.
        // UnconfinedTestDispatcher runs the async block eagerly until it suspends at .first().
        val bundleDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            syncManager.reassembledBundles.first()
        }

        syncManager.pollAndFetch()

        val bundle: ReassembledBundle = bundleDeferred.await()
        assertEquals("qid-emit", bundle.queryId)
        assertEquals("text/html", bundle.contentType)
        assertArrayEquals("hello".toByteArray(), bundle.bytes)
    }
}
