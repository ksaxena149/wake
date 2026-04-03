package com.wake.dtn.service

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.subtle.Ed25519Sign
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

    // Ed25519 key pair for signing test chunks.
    private lateinit var keyPair: Ed25519Sign.KeyPair
    private lateinit var signer: Ed25519Sign

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        keyPair = Ed25519Sign.KeyPair.newKeyPair()
        signer = Ed25519Sign(keyPair.privateKey)

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

        // Inject the key directly so tests don't need to enqueue a /pubkey mock response.
        syncManager = ServerSyncManager(
            httpClient = WakeHttpClient(baseUrl = server.url("/").toString().trimEnd('/')),
            storeManager = storeManager,
            nodeId = "test-node-id",
            reassembler = BundleReassembler(storeManager, testFilesDir),
            pubkeyProvider = { keyPair.publicKey },
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

    /** Build an unsigned chunk JSON (used for negative-path tests). */
    private fun chunkJson(
        queryId: String = "qid-1",
        chunkIndex: Int = 0,
        totalChunks: Int = 1,
        payloadB64: String = "aGVsbG8=",
        sha256: String = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        signature: String? = null,
    ) = """{"server_id":"wake-server-01","query_id":"$queryId","chunk_index":$chunkIndex,""" +
        """"total_chunks":$totalChunks,"content_type":"text/html","payload_b64":"$payloadB64",""" +
        """"sha256":"$sha256","signature":${if (signature != null) "\"$signature\"" else "null"}}"""

    /** Build a chunk JSON with a valid Ed25519 signature from [signer]. */
    private fun signedChunkJson(
        queryId: String = "qid-1",
        chunkIndex: Int = 0,
        totalChunks: Int = 1,
        payloadB64: String = "aGVsbG8=",
        sha256: String = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
    ): String {
        val dto = ResponseBundleDto(
            serverId = "wake-server-01",
            queryId = queryId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            contentType = "text/html",
            payloadB64 = payloadB64,
            sha256 = sha256,
        )
        val sig = Base64.encodeToString(signer.sign(BundleVerifier.canonicalBytes(dto)), Base64.NO_WRAP)
        return chunkJson(queryId, chunkIndex, totalChunks, payloadB64, sha256, signature = sig)
    }

    private fun chunksJson(vararg chunks: String) = "[${chunks.joinToString(",")}]"

    private fun pendingJson(vararg ids: String) =
        """{"pending_query_ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""

    // --- submitRequest ---

    @Test
    fun submitRequest_sendsPostWithCorrectBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
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
    fun submitRequest_emptyResponse_nothingStoredInDb() = runTest {
        // Empty chunk list from the server: nothing to store.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        syncManager.submitRequest("qid-1", "water cycle")
        assertTrue(db.bundleDao().getByQueryId("qid-1").isEmpty())
    }

    @Test
    fun submitRequest_storesImmediateChunksFromResponse() = runTest {
        // The server returns signed chunks immediately — store them without waiting for pollAndFetch.
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-imm"))))
        syncManager.submitRequest("qid-imm", "water cycle")
        assertNotNull("immediate chunk must be stored in DB", db.bundleDao().getById("qid-imm:0"))
        assertTrue("payload file must be written", testFilesDir.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun submitRequest_immediateChunks_skipsPollFetch() = runTest {
        // After submitRequest stores the chunks, a subsequent pollAndFetch must not re-fetch.
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-nopoll"))))
        syncManager.submitRequest("qid-nopoll", "water cycle")
        server.takeRequest() // consume the POST /request call

        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-nopoll")))
        syncManager.pollAndFetch()

        // Only the GET /pending call should happen — no GET /bundle because queryId is already fetched.
        assertEquals("only GET /pending should be made, not GET /bundle", 2, server.requestCount)
    }

    // --- pollAndFetch ---

    @Test
    fun pollAndFetch_storesAllPendingBundles() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-a", "qid-b")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-a"))))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-b"))))
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
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-ok"))))
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
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-once"))))
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
                chunksJson(signedChunkJson(queryId = "qid-emit", sha256 = sha256OfHello))
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

    // --- signature verification tests ---

    @Test
    fun pollAndFetch_doesNotStore_whenSignatureIsNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-nosig")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson(queryId = "qid-nosig"))))
        syncManager.pollAndFetch()
        assertNull(db.bundleDao().getById("qid-nosig:0"))
    }

    @Test
    fun pollAndFetch_doesNotStore_whenSignatureIsInvalid() = runTest {
        // Valid base64 but not a valid signature over this bundle's contents.
        val badSig = Base64.encodeToString(ByteArray(64) { 0x00 }, Base64.NO_WRAP)
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-badsig")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                chunksJson(chunkJson(queryId = "qid-badsig", signature = badSig))
            )
        )
        syncManager.pollAndFetch()
        assertNull(db.bundleDao().getById("qid-badsig:0"))
    }

    @Test
    fun pollAndFetch_doesNotStore_whenSignedWithWrongKey() = runTest {
        val wrongSigner = Ed25519Sign(Ed25519Sign.KeyPair.newKeyPair().privateKey)
        val dto = ResponseBundleDto(
            serverId = "wake-server-01",
            queryId = "qid-wrongkey",
            chunkIndex = 0,
            totalChunks = 1,
            contentType = "text/html",
            payloadB64 = "aGVsbG8=",
            sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        )
        val wrongSig = Base64.encodeToString(
            wrongSigner.sign(BundleVerifier.canonicalBytes(dto)),
            Base64.NO_WRAP
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-wrongkey")))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                chunksJson(chunkJson(queryId = "qid-wrongkey", signature = wrongSig))
            )
        )
        syncManager.pollAndFetch()
        assertNull(db.bundleDao().getById("qid-wrongkey:0"))
    }

    @Test
    fun pollAndFetch_permanentlySkips_afterSignatureRejection() = runTest {
        // First poll: unsigned chunk → BundleVerificationException → queryId added to fetchedQueryIds.
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-badsig-perm")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson(queryId = "qid-badsig-perm"))))
        syncManager.pollAndFetch()
        assertNull("bad-sig chunk must not be stored", db.bundleDao().getById("qid-badsig-perm:0"))

        // Second poll: same queryId reappears but must be silently skipped — no /bundle fetch.
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-badsig-perm")))
        syncManager.pollAndFetch()

        // Only 3 requests total: POST implicit /pending (x2) + GET /bundle (x1 on first poll only).
        assertEquals(
            "second poll must skip /bundle fetch for the permanently-failed queryId",
            3,
            server.requestCount,
        )
        assertNull("chunk must remain absent from DB", db.bundleDao().getById("qid-badsig-perm:0"))
    }

    @Test
    fun pollAndFetch_cachesPubkeyAcrossPolls() = runTest {
        var providerCallCount = 0
        val cachingManager = ServerSyncManager(
            httpClient = WakeHttpClient(baseUrl = server.url("/").toString().trimEnd('/')),
            storeManager = storeManager,
            nodeId = "test-node-id",
            reassembler = BundleReassembler(storeManager, testFilesDir),
            pubkeyProvider = {
                providerCallCount++
                keyPair.publicKey
            },
        )

        // First poll: 1 pending query, 1 signed chunk.
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-poll1")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-poll1"))))
        cachingManager.pollAndFetch()

        // Second poll: different query, same signer.
        server.enqueue(MockResponse().setResponseCode(200).setBody(pendingJson("qid-poll2")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(signedChunkJson(queryId = "qid-poll2"))))
        cachingManager.pollAndFetch()

        assertEquals("pubkeyProvider must be called exactly once", 1, providerCallCount)
        // Both bundles must have been stored (verifier reused correctly).
        assertNotNull(db.bundleDao().getById("qid-poll1:0"))
        assertNotNull(db.bundleDao().getById("qid-poll2:0"))
    }
}
