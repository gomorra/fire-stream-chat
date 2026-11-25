package com.firestream.chat.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.random.Random

class MediaCryptoTest {

    @Test
    fun `empty file encrypts and decrypts cleanly`() {
        assertRoundTrip(ByteArray(0))
    }

    @Test
    fun `single chunk and exact boundary sizes round-trip cleanly`() {
        assertRoundTrip("hello encrypted world".toByteArray())
        assertRoundTrip(Random(1).nextBytes(MediaCrypto.CHUNK_SIZE))
        assertRoundTrip(Random(2).nextBytes(MediaCrypto.CHUNK_SIZE * 2))
    }

    @Test
    fun `multi-chunk file over 200 KiB round-trips cleanly`() {
        assertRoundTrip(Random(42).nextBytes(210 * 1024 + 137))
    }

    @Test
    fun `wrong key fails authentication`() {
        val plain = Random(7).nextBytes(100_000)
        val (cipher, info) = encryptBytes(plain)
        val wrongKey = Base64.getEncoder().encodeToString(MediaCrypto.randomKey())

        assertThrows(SecurityException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(cipher),
                ByteArrayOutputStream(),
                wrongKey,
                info.ivB64,
                info.sha256B64,
            )
        }
    }

    @Test
    fun `flipped ciphertext byte fails GCM authentication`() {
        val plain = Random(9).nextBytes(140_000)
        val (cipher, info) = encryptBytes(plain)
        cipher[50] = (cipher[50].toInt() xor 0x01).toByte()

        assertThrows(SecurityException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(cipher),
                ByteArrayOutputStream(),
                info.keyB64,
                info.ivB64,
                expectedSha256B64 = null,
            )
        }
    }

    @Test
    fun `truncated stream fails on missing final chunk or mid-chunk EOF`() {
        val plain = Random(11).nextBytes(MediaCrypto.CHUNK_SIZE + 1000)
        val (cipher, info) = encryptBytes(plain)

        // Truncate right after chunk 0 (attacker drops chunk 1)
        val firstChunkLen = ByteBuffer.wrap(cipher, 0, 4).int
        val onlyFirstChunk = cipher.copyOfRange(0, 4 + firstChunkLen)
        assertThrows(SecurityException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(onlyFirstChunk),
                ByteArrayOutputStream(),
                info.keyB64,
                info.ivB64,
                expectedSha256B64 = null,
            )
        }

        // Truncate mid-chunk
        val midChunk = cipher.copyOfRange(0, 100)
        assertThrows(EOFException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(midChunk),
                ByteArrayOutputStream(),
                info.keyB64,
                info.ivB64,
                expectedSha256B64 = null,
            )
        }

        // Completely empty ciphertext stream
        assertThrows(SecurityException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(ByteArray(0)),
                ByteArrayOutputStream(),
                info.keyB64,
                info.ivB64,
                expectedSha256B64 = null,
            )
        }
    }

    @Test
    fun `swapped chunks fail authentication`() {
        val plain = Random(13).nextBytes(MediaCrypto.CHUNK_SIZE * 2 + 500)
        val (cipher, info) = encryptBytes(plain)

        val len0 = ByteBuffer.wrap(cipher, 0, 4).int
        val chunk0 = cipher.copyOfRange(0, 4 + len0)
        val offset1 = 4 + len0
        val len1 = ByteBuffer.wrap(cipher, offset1, 4).int
        val chunk1 = cipher.copyOfRange(offset1, offset1 + 4 + len1)
        val rest = cipher.copyOfRange(offset1 + 4 + len1, cipher.size)

        val swapped = chunk1 + chunk0 + rest
        assertThrows(SecurityException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(swapped),
                ByteArrayOutputStream(),
                info.keyB64,
                info.ivB64,
                expectedSha256B64 = null,
            )
        }
    }

    @Test
    fun `SHA-256 mismatch throws SecurityException and decryptFile leaves no target file`() {
        val plainFile = File.createTempFile("mc-plain-", ".bin").apply {
            writeBytes(Random(17).nextBytes(80_000))
        }
        val encFile = File.createTempFile("mc-enc-", ".enc")
        val outFile = File(encFile.parentFile, "mc-out-${System.nanoTime()}.bin")
        try {
            val info = MediaCrypto.encryptFile(plainFile, encFile)
            val wrongHash = Base64.getEncoder().encodeToString(ByteArray(32) { 0x5A })

            assertThrows(SecurityException::class.java) {
                MediaCrypto.decryptFile(encFile, outFile, info.keyB64, info.ivB64, wrongHash)
            }
            assertFalse("Failed decrypt must not leave output file behind", outFile.exists())

            // Valid hash succeeds
            MediaCrypto.decryptFile(encFile, outFile, info.keyB64, info.ivB64, info.sha256B64)
            assertTrue(outFile.exists())
            assertArrayEquals(plainFile.readBytes(), outFile.readBytes())
        } finally {
            plainFile.delete()
            encFile.delete()
            outFile.delete()
        }
    }

    @Test
    fun `appended trailing bytes fail final-chunk authentication`() {
        val plain = Random(23).nextBytes(50_000)
        val (cipher, info) = encryptBytes(plain)
        val withTrailing = cipher + byteArrayOf(0x00, 0x00, 0x00, 0x10) + ByteArray(16)

        assertThrows(SecurityException::class.java) {
            MediaCrypto.decryptStream(
                ByteArrayInputStream(withTrailing),
                ByteArrayOutputStream(),
                info.keyB64,
                info.ivB64,
                expectedSha256B64 = null,
            )
        }
    }

    @Test
    fun `MediaEncryptionParams resolve rejects partial metadata`() {
        assertEquals(null, MediaEncryptionParams.resolve(null, null, null))
        assertThrows(SecurityException::class.java) {
            MediaEncryptionParams.resolve("key", null, "sha")
        }
        assertThrows(SecurityException::class.java) {
            MediaEncryptionParams.resolve("key", "iv", null)
        }
    }

    private fun encryptBytes(plain: ByteArray): Pair<ByteArray, MediaCipherInfo> {
        val out = ByteArrayOutputStream()
        val info = MediaCrypto.encryptStream(ByteArrayInputStream(plain), out)
        val cipher = out.toByteArray()
        assertEquals(cipher.size.toLong(), info.ciphertextSize)
        return cipher to info
    }

    private fun assertRoundTrip(plain: ByteArray) {
        val (cipher, info) = encryptBytes(plain)
        val decrypted = ByteArrayOutputStream()
        MediaCrypto.decryptStream(
            ByteArrayInputStream(cipher),
            decrypted,
            info.keyB64,
            info.ivB64,
            info.sha256B64,
        )
        assertArrayEquals(plain, decrypted.toByteArray())
    }
}
