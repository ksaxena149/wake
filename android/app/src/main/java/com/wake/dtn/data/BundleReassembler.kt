package com.wake.dtn.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Bytes reassembled from one or more response chunks for a single [queryId]. */
data class ReassembledBundle(
    val queryId: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    // ByteArray equality is reference-based by default; provide value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReassembledBundle) return false
        return queryId == other.queryId &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = queryId.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Detects when all chunks for a [queryId] have been stored and concatenates their payload bytes
 * in chunk-index order to produce a [ReassembledBundle].
 *
 * Each chunk's payload file is verified against its stored [BundleEntity.payloadSha256] before
 * concatenation. A mismatch throws [IOException] to signal content corruption.
 *
 * @param store  Source of truth for chunk metadata (Room).
 * @param filesDir  Directory where payload files live — must match the directory used by
 *                  [BundleStoreManager] that wrote the files.
 */
class BundleReassembler(
    private val store: BundleStoreManager,
    private val filesDir: File,
) {

    /**
     * Returns true when every chunk for [queryId] (indices 0 until totalChunks) is present in
     * Room, with no gaps and no duplicates.
     */
    suspend fun isComplete(queryId: String): Boolean {
        val chunks = store.getResponseChunks(queryId)
        if (chunks.isEmpty()) return false
        val expected = chunks[0].totalChunks
        if (chunks.size != expected) return false
        // DAO orders by chunkIndex ASC; verify every index is exactly i.
        return chunks.indices.all { i -> chunks[i].chunkIndex == i }
    }

    /**
     * Reads chunk payload files in order, verifies each chunk's SHA-256 (when recorded), and
     * concatenates them into a single [ReassembledBundle].
     *
     * Returns null when not all chunks are present yet (same condition as [isComplete] returning
     * false). Throws [IOException] if a payload file is missing, its path escapes [filesDir], or
     * its SHA-256 does not match.
     */
    suspend fun reassemble(queryId: String): ReassembledBundle? {
        val chunks = store.getResponseChunks(queryId)
        if (chunks.isEmpty()) return null
        val expected = chunks[0].totalChunks
        if (chunks.size != expected) return null
        if (chunks.indices.any { i -> chunks[i].chunkIndex != i }) return null

        val output = ByteArrayOutputStream()
        for (chunk in chunks) {
            val filePath = chunk.payloadFilePath
                ?: throw IOException("Chunk ${chunk.bundleId} has no payloadFilePath")
            val file = safeFile(filePath)
                ?: throw IOException("Rejected payload path outside filesDir: $filePath")
            if (!file.exists()) {
                throw IOException("Payload file missing for chunk ${chunk.bundleId}: $filePath")
            }
            val bytes = file.readBytes()
            chunk.payloadSha256?.let { expected ->
                val actual = sha256Hex(bytes)
                if (actual != expected) {
                    throw IOException(
                        "SHA-256 mismatch for ${chunk.bundleId}: expected=$expected actual=$actual"
                    )
                }
            }
            output.write(bytes)
        }

        return ReassembledBundle(
            queryId = queryId,
            contentType = chunks[0].contentType ?: "application/octet-stream",
            bytes = output.toByteArray(),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Resolves [relativePath] against [filesDir] and returns the [File] only if the canonical
     * path stays inside [filesDir]. Returns null for absolute paths, `../` traversal, or symlinks
     * that point outside [filesDir].
     */
    private fun safeFile(relativePath: String): File? {
        val resolved = File(filesDir, relativePath).canonicalFile
        return if (resolved.path.startsWith(filesDir.canonicalPath + File.separator) ||
            resolved == filesDir.canonicalFile
        ) {
            resolved
        } else {
            null
        }
    }
}
