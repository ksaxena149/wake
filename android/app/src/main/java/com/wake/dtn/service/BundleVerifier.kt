package com.wake.dtn.service

import android.util.Base64
import android.util.Log
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/**
 * Verifies Ed25519 signatures on incoming [ResponseBundleDto] bundles using Google Tink.
 *
 * The server signs a deterministic UTF-8 JSON encoding of all bundle fields except
 * [ResponseBundleDto.signature], with keys sorted alphabetically (ASCII order) and no
 * whitespace — mirroring PyNaCl's `json.dumps(sort_keys=True, separators=(",", ":"))`.
 *
 * [verifyKeyBytes] is the server's 32-byte raw Ed25519 public key, obtained from GET /pubkey.
 */
class BundleVerifier(verifyKeyBytes: ByteArray) {

    private val verifier = Ed25519Verify(verifyKeyBytes)

    /**
     * Returns true iff [bundle]'s signature is a valid Ed25519 signature over its canonical bytes.
     * Returns false if the signature is absent, malformed, or invalid.
     */
    fun verify(bundle: ResponseBundleDto): Boolean {
        val sigB64 = bundle.signature ?: return false
        return try {
            val sigBytes = Base64.decode(sigB64, Base64.DEFAULT)
            verifier.verify(sigBytes, canonicalBytes(bundle))
            true
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Signature verification failed for queryId=${bundle.queryId} chunk=${bundle.chunkIndex}: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Malformed base64 signature for queryId=${bundle.queryId} chunk=${bundle.chunkIndex}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "BundleVerifier"

        /**
         * Reconstruct the exact bytes the server signed.
         *
         * The server calls:
         *   json.dumps(bundle.model_dump(exclude={"signature"}), sort_keys=True, separators=(",",":"))
         *
         * This produces 7 fields in alphabetical key order with no whitespace:
         *   chunk_index, content_type, payload_b64, query_id, server_id, sha256, total_chunks
         *
         * Integer fields (chunk_index, total_chunks) are JSON integers, not strings.
         */
        internal fun canonicalBytes(bundle: ResponseBundleDto): ByteArray {
            val json = buildString {
                append("{")
                append("\"chunk_index\":${bundle.chunkIndex},")
                append("\"content_type\":${jsonString(bundle.contentType)},")
                append("\"payload_b64\":${jsonString(bundle.payloadB64)},")
                append("\"query_id\":${jsonString(bundle.queryId)},")
                append("\"server_id\":${jsonString(bundle.serverId)},")
                append("\"sha256\":${jsonString(bundle.sha256)},")
                append("\"total_chunks\":${bundle.totalChunks}")
                append("}")
            }
            return json.toByteArray(Charsets.UTF_8)
        }

        /**
         * Emit a JSON string literal matching Python's json.dumps named escape sequences:
         * \\ \" \b \t \n \f \r. Other control characters (U+0000–U+001F) are absent from
         * all current bundle field values (UUIDs, base64, hex digests, MIME types), so full
         * \uXXXX escaping is not needed in practice.
         */
        private fun jsonString(value: String): String {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\t", "\\t")
                .replace("\n", "\\n")
                .replace("\u000C", "\\f")
                .replace("\r", "\\r")
            return "\"$escaped\""
        }
    }
}
