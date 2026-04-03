package com.wake.dtn.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class WakeHttpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: WakeHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = WakeHttpClient(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) runCatching { server.shutdown() }
    }

    // --- helpers ---

    private fun chunkJson(
        queryId: String = "qid-1",
        chunkIndex: Int = 0,
        totalChunks: Int = 1,
        payloadB64: String = "aGVsbG8=",
        sha256: String = "abc123",
    ) = """{"server_id":"wake-server-01","query_id":"$queryId","chunk_index":$chunkIndex,""" +
        """"total_chunks":$totalChunks,"content_type":"text/html","payload_b64":"$payloadB64",""" +
        """"sha256":"$sha256","signature":null}"""

    private fun chunksJson(vararg chunks: String) = "[${chunks.joinToString(",")}]"

    // --- submitRequest ---

    @Test
    fun submitRequest_succeedsOn2xx() = runTest {
        // Server returns chunks as a JSON array (may be empty when none are ready yet).
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client.submitRequest("node-1", "qid-1", "water cycle")
        // No exception thrown — request was sent and server responded with 2xx.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun submitRequest_requestBodyHasCorrectSnakeCaseFields() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client.submitRequest("node-1", "qid-snake", "water cycle")
        val body = server.takeRequest().body.readUtf8()
        assertTrue("node_id missing", body.contains("\"node_id\""))
        assertTrue("query_id missing", body.contains("\"query_id\""))
        assertTrue("query_string missing", body.contains("\"query_string\""))
        assertTrue("ttl_seconds missing", body.contains("\"ttl_seconds\""))
        assertTrue("hop_count missing", body.contains("\"hop_count\""))
    }

    @Test
    fun submitRequest_timestampIsUnixSeconds() = runTest {
        val before = System.currentTimeMillis() / 1_000L
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        client.submitRequest("node-1", "qid-ts", "test")
        val after = System.currentTimeMillis() / 1_000L
        val body = server.takeRequest().body.readUtf8()
        val ts = Regex("\"timestamp\":(\\d+)").find(body)!!.groupValues[1].toLong()
        assertTrue("timestamp $ts not in [$before, $after]", ts in before..after)
    }

    @Test
    fun submitRequest_onHttpError_throwsIOException() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        try {
            client.submitRequest("node-1", "qid-err", "test")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue("message should include status code", e.message!!.contains("502"))
        }
    }

    // --- fetchPending ---

    @Test
    fun fetchPending_returnsQueryIds() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"pending_query_ids":["id1","id2"]}""")
        )
        val result = client.fetchPending("my-node-id")
        assertEquals(listOf("id1", "id2"), result)
        val path = server.takeRequest().path!!
        assertTrue("request must include node_id query param", path.contains("node_id=my-node-id"))
    }

    @Test
    fun fetchPending_returnsEmptyList() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"pending_query_ids":[]}""")
        )
        val result = client.fetchPending("my-node-id")
        assertTrue(result.isEmpty())
        val path = server.takeRequest().path!!
        assertTrue("request must include node_id query param", path.contains("node_id=my-node-id"))
    }

    // --- fetchBundle ---

    @Test
    fun fetchBundle_returnsChunks() = runTest {
        val body = chunksJson(
            chunkJson(chunkIndex = 0, totalChunks = 2, payloadB64 = "aGVsbG8="),
            chunkJson(chunkIndex = 1, totalChunks = 2, payloadB64 = "d29ybGQ="),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val result = client.fetchBundle("qid-1")
        assertEquals(2, result.size)
        assertEquals(0, result[0].chunkIndex)
        assertEquals(1, result[1].chunkIndex)
        assertEquals("aGVsbG8=", result[0].payloadB64)
        assertEquals("d29ybGQ=", result[1].payloadB64)
    }

    @Test
    fun fetchBundle_on404_throwsIOException() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        try {
            client.fetchBundle("nonexistent")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue("message should include status code", e.message!!.contains("404"))
        }
    }

    @Test
    fun fetchBundle_urlEncodesQueryIdInPath() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson())))
        client.fetchBundle("my query id")
        val path = server.takeRequest().path!!
        // HttpUrl.Builder.addPathSegment encodes spaces as %20
        assertTrue("path should encode spaces", path.contains("my%20query%20id"))
    }

    @Test
    fun fetchBundle_pathContainsBundleSegment() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chunksJson(chunkJson())))
        client.fetchBundle("qid-path-test")
        val path = server.takeRequest().path!!
        assertTrue("path should start with /bundle/", path.startsWith("/bundle/"))
        assertTrue("path should end with query ID", path.endsWith("qid-path-test"))
    }
}
