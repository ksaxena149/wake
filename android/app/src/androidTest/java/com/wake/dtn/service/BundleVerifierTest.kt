package com.wake.dtn.service

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundleVerifierTest {

    companion object {
        private lateinit var keyPair: Ed25519Sign.KeyPair
        private lateinit var signer: Ed25519Sign
        private lateinit var verifier: BundleVerifier
        private lateinit var wrongKeyVerifier: BundleVerifier

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            keyPair = Ed25519Sign.KeyPair.newKeyPair()
            signer = Ed25519Sign(keyPair.privateKey)
            verifier = BundleVerifier(keyPair.publicKey)
            val otherPair = Ed25519Sign.KeyPair.newKeyPair()
            wrongKeyVerifier = BundleVerifier(otherPair.publicKey)
        }

        private fun makeDto(
            serverId: String = "wake-server-01",
            queryId: String = "qid-1",
            chunkIndex: Int = 0,
            totalChunks: Int = 1,
            contentType: String = "text/html",
            payloadB64: String = "aGVsbG8=",
            sha256: String = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            signature: String? = null,
        ) = ResponseBundleDto(
            serverId = serverId,
            queryId = queryId,
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            contentType = contentType,
            payloadB64 = payloadB64,
            sha256 = sha256,
            signature = signature,
        )

        private fun sign(dto: ResponseBundleDto): String {
            val canonical = BundleVerifier.canonicalBytes(dto)
            return Base64.encodeToString(signer.sign(canonical), Base64.NO_WRAP)
        }
    }

    // --- verify() ---

    @Test
    fun verify_returnsTrue_forValidSignature() {
        val dto = makeDto()
        val signed = dto.copy(signature = sign(dto))
        assertTrue(verifier.verify(signed))
    }

    @Test
    fun verify_returnsFalse_forWrongKey() {
        val dto = makeDto()
        val signed = dto.copy(signature = sign(dto))
        assertFalse(wrongKeyVerifier.verify(signed))
    }

    @Test
    fun verify_returnsFalse_whenSignatureIsNull() {
        val dto = makeDto(signature = null)
        assertFalse(verifier.verify(dto))
    }

    @Test
    fun verify_returnsFalse_forTamperedPayloadB64() {
        val dto = makeDto()
        val sig = sign(dto)
        val tampered = dto.copy(payloadB64 = "dGFtcGVyZWQ=", signature = sig)
        assertFalse(verifier.verify(tampered))
    }

    @Test
    fun verify_returnsFalse_forTamperedQueryId() {
        val dto = makeDto()
        val sig = sign(dto)
        val tampered = dto.copy(queryId = "evil-qid", signature = sig)
        assertFalse(verifier.verify(tampered))
    }

    @Test
    fun verify_returnsFalse_forTamperedChunkIndex() {
        val dto = makeDto(totalChunks = 2)
        val sig = sign(dto)
        val tampered = dto.copy(chunkIndex = 1, signature = sig)
        assertFalse(verifier.verify(tampered))
    }

    @Test
    fun verify_returnsFalse_forMalformedBase64Signature() {
        val dto = makeDto(signature = "not!!valid@@base64")
        assertFalse(verifier.verify(dto))
    }

    // --- canonicalBytes() ---

    @Test
    fun canonicalBytes_producesCorrectJsonShape() {
        val dto = makeDto(
            serverId = "wake-server-01",
            queryId = "qid-1",
            chunkIndex = 0,
            totalChunks = 1,
            contentType = "text/html",
            payloadB64 = "aGVsbG8=",
            sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        )
        val expected =
            """{"chunk_index":0,"content_type":"text/html","payload_b64":"aGVsbG8=","query_id":"qid-1","server_id":"wake-server-01","sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","total_chunks":1}"""
        val actual = BundleVerifier.canonicalBytes(dto).toString(Charsets.UTF_8)
        org.junit.Assert.assertEquals(expected, actual)
    }

    @Test
    fun canonicalBytes_excludesSignatureField() {
        val dto = makeDto(signature = "somesig")
        val json = BundleVerifier.canonicalBytes(dto).toString(Charsets.UTF_8)
        assertFalse("canonical bytes must not include signature field", json.contains("\"signature\""))
    }

    @Test
    fun canonicalBytes_escapesSpecialCharactersLikeJsonDumps() {
        // Inject named control chars into a string field to verify Python-compatible escaping.
        val dto = makeDto(serverId = "id\twith\ttabs\nand\nnewlines\rand\r\ncarriages")
        val json = BundleVerifier.canonicalBytes(dto).toString(Charsets.UTF_8)
        assertTrue("tab must be escaped as \\t",      json.contains("\\t"))
        assertTrue("newline must be escaped as \\n",  json.contains("\\n"))
        assertTrue("CR must be escaped as \\r",       json.contains("\\r"))
        assertFalse("raw tab must not appear",        json.contains("\t"))
        assertFalse("raw newline must not appear",    json.contains("\n"))
        assertFalse("raw CR must not appear",         json.contains("\r"))
    }

    @Test
    fun canonicalBytes_integerFieldsAreNotQuoted() {
        val dto = makeDto(chunkIndex = 0, totalChunks = 1)
        val json = BundleVerifier.canonicalBytes(dto).toString(Charsets.UTF_8)
        assertTrue("chunk_index must be an integer", json.contains("\"chunk_index\":0"))
        assertTrue("total_chunks must be an integer", json.contains("\"total_chunks\":1"))
    }
}
