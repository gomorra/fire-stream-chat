// region: AGENT-NOTE
// Responsibility: Streaming chunked AES-256-GCM cipher shared by 1:1 E2EE media
//   blobs (media/{chatId}/{storageId}.enc) and passphrase-protected backups
//   (backups/{uid}/db.enc and legacy-media/{messageId}.enc).
// Owns: 64 KiB chunk framing ([4-byte big-endian chunkCipherLen][ciphertext+16B GCM tag]),
//   per-chunk nonce derivation (baseNonce XOR bigEndian64(chunkIndex)), AAD binding
//   (version=1 || chunkIndex || isFinalByte), and streaming SHA-256 computation/verification.
// Collaborators: OutboxFiles / OutboxSender (encrypt before upload),
//   MediaFileManager (decrypt + verify on download), BackupManager (Phase 3).
// Don't put here: Signal ratchet crypto (SignalManager), PBKDF2/HKDF backup
//   key derivation (BackupCrypto), or network/Storage I/O.
// endregion

package com.firestream.chat.data.crypto

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MediaCipherInfo(
    val keyB64: String,
    val ivB64: String,
    val sha256B64: String,
    val ciphertextSize: Long,
)

data class MediaEncryptionParams(
    val key: String,
    val iv: String,
    val sha256: String,
) {
    companion object {
        /**
         * Returns [MediaEncryptionParams] when all three fields are present,
         * `null` when all three are absent (unencrypted media), or throws
         * [SecurityException] if encryption metadata is partially missing.
         */
        fun resolve(key: String?, iv: String?, sha256: String?): MediaEncryptionParams? {
            if (key == null && iv == null && sha256 == null) return null
            if (key == null || iv == null || sha256 == null) {
                throw SecurityException("Incomplete media encryption metadata (key=${key != null}, iv=${iv != null}, sha256=${sha256 != null})")
            }
            return MediaEncryptionParams(key, iv, sha256)
        }
    }
}

object MediaCrypto {
    const val CHUNK_SIZE = 64 * 1024 // 64 KiB plaintext per chunk
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private const val FORMAT_VERSION: Byte = 1
    private const val HEADER_BYTES = 4
    private const val MAX_CHUNK_CIPHERTEXT_BYTES = CHUNK_SIZE + GCM_TAG_BYTES
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
    fun randomNonce(): ByteArray = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)

    /**
     * Encrypts [input] into [output] in constant memory (64 KiB chunks),
     * computing the SHA-256 digest of the exact ciphertext bytes written.
     */
    fun encryptFile(
        input: File,
        output: File,
        keyBytes: ByteArray = randomKey(),
        ivBytes: ByteArray = randomNonce(),
    ): MediaCipherInfo = input.inputStream().use { i ->
        output.outputStream().use { o ->
            encryptStream(i, o, keyBytes, ivBytes)
        }
    }

    /**
     * Streams plaintext from [input], writes framed chunked AES-256-GCM
     * ciphertext to [output], and returns the Base64 key, base IV, SHA-256
     * digest of the written ciphertext, and total ciphertext byte count.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        keyBytes: ByteArray = randomKey(),
        ivBytes: ByteArray = randomNonce(),
    ): MediaCipherInfo {
        require(keyBytes.size == KEY_BYTES) { "Expected $KEY_BYTES-byte AES-256 key, got ${keyBytes.size}" }
        require(ivBytes.size == NONCE_BYTES) { "Expected $NONCE_BYTES-byte GCM nonce, got ${ivBytes.size}" }

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val digest = MessageDigest.getInstance("SHA-256")
        val digestOut = DigestOutputStream(output, digest)
        val headerBuf = ByteBuffer.allocate(HEADER_BYTES)

        val pushback = PushbackInputStream(input, 1)
        val plainBuf = ByteArray(CHUNK_SIZE)
        var chunkIndex = 0L
        var totalCipherBytes = 0L

        while (true) {
            val read = readChunk(pushback, plainBuf)
            val nextByte = pushback.read()
            val isFinal = nextByte == -1
            if (!isFinal) {
                pushback.unread(nextByte)
            }

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keySpec,
                GCMParameterSpec(GCM_TAG_BITS, nonceForChunk(ivBytes, chunkIndex)),
            )
            cipher.updateAAD(aadForChunk(chunkIndex, isFinal))
            val encrypted = cipher.doFinal(plainBuf, 0, read)

            headerBuf.clear()
            headerBuf.putInt(encrypted.size)
            digestOut.write(headerBuf.array(), 0, HEADER_BYTES)
            digestOut.write(encrypted)
            totalCipherBytes += HEADER_BYTES + encrypted.size
            chunkIndex++

            if (isFinal) break
        }
        digestOut.flush()

        val encoder = Base64.getEncoder()
        return MediaCipherInfo(
            keyB64 = encoder.encodeToString(keyBytes),
            ivB64 = encoder.encodeToString(ivBytes),
            sha256B64 = encoder.encodeToString(digest.digest()),
            ciphertextSize = totalCipherBytes,
        )
    }

    /**
     * Decrypts [encryptedFile] to [outputFile] via a sibling `.dec.part` temp
     * file, verifying every chunk's GCM tag and (when non-null) [expectedSha256B64].
     * Never leaves a half-written [outputFile] on failure.
     */
    fun decryptFile(
        encryptedFile: File,
        outputFile: File,
        keyB64: String,
        ivB64: String,
        expectedSha256B64: String?,
    ) {
        val parent = outputFile.parentFile ?: throw IOException("No parent directory for $outputFile")
        parent.mkdirs()
        val partial = File.createTempFile("${outputFile.name}.", ".dec.part", parent)
        try {
            encryptedFile.inputStream().use { input ->
                partial.outputStream().use { output ->
                    decryptStream(input, output, keyB64, ivB64, expectedSha256B64)
                }
            }
            if (!partial.renameTo(outputFile)) {
                throw IOException("Cannot rename decrypted file to $outputFile")
            }
        } finally {
            partial.delete()
        }
    }

    /**
     * Streams framed chunked AES-256-GCM ciphertext from [input], verifying
     * every chunk's GCM authentication tag (binding version, chunk index, and
     * final-chunk flag) and optionally verifying [expectedSha256B64] over the
     * full ciphertext stream.
     *
     * Throws [SecurityException] or [GeneralSecurityException] on tampering,
     * wrong key/IV, truncation, chunk reordering, or SHA-256 mismatch.
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        keyB64: String,
        ivB64: String,
        expectedSha256B64: String?,
    ) {
        val decoder = Base64.getDecoder()
        val keyBytes = try {
            decoder.decode(keyB64)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid Base64 key", e)
        }
        val ivBytes = try {
            decoder.decode(ivB64)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid Base64 IV", e)
        }
        val expectedHash = expectedSha256B64?.let {
            try {
                decoder.decode(it)
            } catch (e: IllegalArgumentException) {
                throw SecurityException("Invalid Base64 SHA-256", e)
            }
        }
        decryptStreamRaw(input, output, keyBytes, ivBytes, expectedHash)
    }

    fun decryptStreamRaw(
        input: InputStream,
        output: OutputStream,
        keyBytes: ByteArray,
        ivBytes: ByteArray,
        expectedSha256: ByteArray?,
    ) {
        if (keyBytes.size != KEY_BYTES) {
            throw SecurityException("Expected $KEY_BYTES-byte AES-256 key, got ${keyBytes.size}")
        }
        if (ivBytes.size != NONCE_BYTES) {
            throw SecurityException("Expected $NONCE_BYTES-byte GCM nonce, got ${ivBytes.size}")
        }

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val digest = MessageDigest.getInstance("SHA-256")
        val digestIn = DigestInputStream(input, digest)
        val pushback = PushbackInputStream(digestIn, 1)
        val header = ByteArray(HEADER_BYTES)

        var chunkIndex = 0L
        var sawFinal = false

        while (true) {
            val first = pushback.read()
            if (first == -1) break
            pushback.unread(first)

            readFullyOrThrow(pushback, header, HEADER_BYTES)
            val chunkLen = ByteBuffer.wrap(header).int
            if (chunkLen < GCM_TAG_BYTES || chunkLen > MAX_CHUNK_CIPHERTEXT_BYTES) {
                throw SecurityException("Invalid encrypted chunk length: $chunkLen")
            }

            val cipherChunk = ByteArray(chunkLen)
            readFullyOrThrow(pushback, cipherChunk, chunkLen)

            val peek = pushback.read()
            val isFinal = peek == -1
            if (!isFinal) {
                pushback.unread(peek)
            }

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keySpec,
                GCMParameterSpec(GCM_TAG_BITS, nonceForChunk(ivBytes, chunkIndex)),
            )
            cipher.updateAAD(aadForChunk(chunkIndex, isFinal))
            val plain = try {
                cipher.doFinal(cipherChunk)
            } catch (e: GeneralSecurityException) {
                throw SecurityException("Encrypted media authentication failed at chunk $chunkIndex", e)
            }
            if (plain.isNotEmpty()) {
                output.write(plain)
            }
            chunkIndex++
            if (isFinal) {
                sawFinal = true
                break
            }
        }

        if (!sawFinal) {
            throw SecurityException("Truncated encrypted stream: missing final chunk")
        }

        if (expectedSha256 != null) {
            val actualHash = digest.digest()
            if (!MessageDigest.isEqual(actualHash, expectedSha256)) {
                throw SecurityException("Ciphertext SHA-256 mismatch")
            }
        }
        output.flush()
    }

    internal fun nonceForChunk(baseNonce: ByteArray, chunkIndex: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            nonce[4 + i] = (nonce[4 + i].toInt() xor ((chunkIndex ushr shift) and 0xFFL).toInt()).toByte()
        }
        return nonce
    }

    internal fun aadForChunk(chunkIndex: Long, isFinal: Boolean): ByteArray =
        ByteBuffer.allocate(10)
            .put(FORMAT_VERSION)
            .putLong(chunkIndex)
            .put(if (isFinal) 1.toByte() else 0.toByte())
            .array()

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n == -1) break
            total += n
        }
        return total
    }

    private fun readFullyOrThrow(input: InputStream, buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n == -1) throw EOFException("Unexpected end of encrypted stream")
            offset += n
        }
    }
}
