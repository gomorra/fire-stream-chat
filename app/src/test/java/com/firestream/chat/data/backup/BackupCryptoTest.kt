package com.firestream.chat.data.backup

import com.firestream.chat.domain.model.UnsupportedBackupVersionException
import com.firestream.chat.domain.model.WrongPassphraseException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class BackupCryptoTest {

    @Test
    fun `deriveMasterKey is deterministic and sensitive to passphrase and salt`() {
        val salt1 = ByteArray(BackupCrypto.SALT_BYTES) { it.toByte() }
        val salt2 = ByteArray(BackupCrypto.SALT_BYTES) { (it + 1).toByte() }

        val k1a = BackupCrypto.deriveMasterKey("correct horse battery staple", salt1, iterations = 1_000)
        val k1b = BackupCrypto.deriveMasterKey("correct horse battery staple", salt1, iterations = 1_000)
        val k2 = BackupCrypto.deriveMasterKey("correct horse battery staplE", salt1, iterations = 1_000)
        val k3 = BackupCrypto.deriveMasterKey("correct horse battery staple", salt2, iterations = 1_000)

        assertEquals(BackupCrypto.KEY_BYTES, k1a.size)
        assertArrayEquals(k1a, k1b)
        assertFalse(k1a.contentEquals(k2))
        assertFalse(k1a.contentEquals(k3))
    }

    @Test
    fun `deriveMasterKey works with default 600_000 PBKDF2 iterations`() {
        val salt = ByteArray(BackupCrypto.SALT_BYTES) { (0xA5 xor it).toByte() }
        val key = BackupCrypto.deriveMasterKey("my-strong-passphrase-2026", salt)
        assertEquals(BackupCrypto.KEY_BYTES, key.size)
        assertFalse(key.all { it == 0.toByte() })
    }

    @Test
    fun `HKDF subkeys are pairwise isolated across db, manifest, and distinct message IDs`() {
        val master = ByteArray(BackupCrypto.KEY_BYTES) { (it * 7).toByte() }
        val dbKey = BackupCrypto.deriveDbKey(master)
        val manifestKey = BackupCrypto.deriveManifestKey(master)
        val mediaKey1 = BackupCrypto.deriveLegacyMediaKey(master, "msg-1")
        val mediaKey2 = BackupCrypto.deriveLegacyMediaKey(master, "msg-2")

        val all = listOf(master, dbKey, manifestKey, mediaKey1, mediaKey2)
            .map { Base64.getEncoder().encodeToString(it) }
        assertEquals(5, all.toSet().size)
        // Deterministic re-derivation
        assertArrayEquals(mediaKey1, BackupCrypto.deriveLegacyMediaKey(master, "msg-1"))
    }

    @Test
    fun `manifest create, serialize, parse, and decrypt round-trips inner metadata`() {
        val salt = BackupCrypto.randomSalt()
        val master = BackupCrypto.deriveMasterKey("super-secret-backup-pass", salt, iterations = 1_000)
        val inner = BackupManifestInner(
            dbIvB64 = "AAAAAAAAAAAAAAAA",
            dbSha256B64 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            schemaVersion = 36,
            messageCount = 1234,
            legacyMediaIds = listOf("m1", "m2", "m3"),
        )

        val manifest = BackupCrypto.createManifest(
            masterKey = master,
            salt = salt,
            kdfIterations = 1_000,
            createdAt = 1_700_000_000_000L,
            dbObjectName = "db_1700000000000.enc",
            dbCipherSizeBytes = 98_765L,
            legacyMediaComplete = true,
            inner = inner,
        )

        val json = BackupCrypto.serializeManifest(manifest)
        val parsed = BackupCrypto.parseManifest(json, minIterations = 1_000)
        assertEquals(manifest, parsed)

        val decrypted = BackupCrypto.decryptManifestInner(parsed, master)
        assertEquals(inner, decrypted)
    }

    @Test
    fun `wrong passphrase throws WrongPassphraseException on manifest decrypt`() {
        val salt = BackupCrypto.randomSalt()
        val rightKey = BackupCrypto.deriveMasterKey("right-passphrase-1234", salt, iterations = 1_000)
        val wrongKey = BackupCrypto.deriveMasterKey("wrong-passphrase-1234", salt, iterations = 1_000)
        val manifest = BackupCrypto.createManifest(
            masterKey = rightKey,
            salt = salt,
            kdfIterations = 1_000,
            createdAt = 1_700_000_000_000L,
            dbObjectName = "db_1700000000000.enc",
            dbCipherSizeBytes = 1024L,
            legacyMediaComplete = true,
            inner = BackupManifestInner("iv", "sha", 36, 10, emptyList()),
        )

        val result = runCatching { BackupCrypto.decryptManifestInner(manifest, wrongKey) }
        assertTrue(result.exceptionOrNull() is WrongPassphraseException)
    }

    @Test
    fun `tampering with outer manifest header fails GCM AAD authentication`() {
        val salt = BackupCrypto.randomSalt()
        val master = BackupCrypto.deriveMasterKey("aad-bound-passphrase-99", salt, iterations = 1_000)
        val manifest = BackupCrypto.createManifest(
            masterKey = master,
            salt = salt,
            kdfIterations = 1_000,
            createdAt = 1_700_000_000_000L,
            dbObjectName = "db_1700000000000.enc",
            dbCipherSizeBytes = 1024L,
            legacyMediaComplete = false,
            inner = BackupManifestInner("iv", "sha", 36, 10, emptyList()),
        )

        // Flip legacyMediaComplete, dbObjectName, or createdAt in outer header
        val tamperedComplete = manifest.copy(legacyMediaComplete = true)
        val tamperedDbName = manifest.copy(dbObjectName = "db_9999999999999.enc")
        val tamperedTimestamp = manifest.copy(createdAt = 1_800_000_000_000L)

        assertTrue(runCatching { BackupCrypto.decryptManifestInner(tamperedComplete, master) }.exceptionOrNull() is WrongPassphraseException)
        assertTrue(runCatching { BackupCrypto.decryptManifestInner(tamperedDbName, master) }.exceptionOrNull() is WrongPassphraseException)
        assertTrue(runCatching { BackupCrypto.decryptManifestInner(tamperedTimestamp, master) }.exceptionOrNull() is WrongPassphraseException)
    }

    @Test
    fun `parseManifest rejects path traversal, low iteration count, and unknown versions`() {
        val salt = BackupCrypto.randomSalt()
        val master = BackupCrypto.deriveMasterKey("valid-passphrase-1234", salt, iterations = 100_000)
        val valid = BackupCrypto.serializeManifest(
            BackupCrypto.createManifest(
                masterKey = master,
                salt = salt,
                kdfIterations = 100_000,
                createdAt = 1_700_000_000_000L,
                dbObjectName = "db_1700000000000.enc",
                dbCipherSizeBytes = 512L,
                legacyMediaComplete = true,
                inner = BackupManifestInner("iv", "sha", 36, 1, emptyList()),
            )
        )

        val badPath = JSONObject(valid).put("dbObjectName", "../other/db_1.enc").toString()
        assertTrue(runCatching { BackupCrypto.parseManifest(badPath) }.isFailure)

        val lowIter = JSONObject(valid).put("kdfIterations", 500).toString()
        assertTrue(runCatching { BackupCrypto.parseManifest(lowIter) }.isFailure)

        val hugeIter = JSONObject(valid).put("kdfIterations", BackupCrypto.MAX_KDF_ITERATIONS + 1).toString()
        assertTrue(runCatching { BackupCrypto.parseManifest(hugeIter) }.isFailure)

        val badVersion = JSONObject(valid).put("version", 99).toString()
        assertTrue(runCatching { BackupCrypto.parseManifest(badVersion) }.exceptionOrNull() is UnsupportedBackupVersionException)

        val fractionalLong = JSONObject(valid).put("createdAt", 1_700_000_000.5).toString()
        assertTrue(runCatching { BackupCrypto.parseManifest(fractionalLong) }.isFailure)
    }

    @Test
    fun `legacy media blob encrypt and decrypt round-trips and rejects wrong messageId or tampering`() {
        val tempDir = Files.createTempDirectory("backup-crypto-test").toFile()
        try {
            val master = ByteArray(BackupCrypto.KEY_BYTES) { (it + 3).toByte() }
            val plain = ByteArray(150_000) { (it * 31).toByte() } // multi-chunk (> 64 KiB)
            val src = File(tempDir, "orig.bin").apply { writeBytes(plain) }
            val enc = File(tempDir, "msg42.enc")
            val dec = File(tempDir, "restored.bin")

            BackupCrypto.encryptLegacyMediaFile(src, enc, master, "msg42")
            assertNotEquals(plain.size.toLong(), enc.length())

            // Round-trip succeeds with right key & messageId
            BackupCrypto.decryptLegacyMediaFile(enc, dec, master, "msg42")
            assertArrayEquals(plain, dec.readBytes())

            // Wrong messageId fails (HKDF domain isolation)
            val wrongIdOut = File(tempDir, "wrongId.bin")
            assertTrue(
                runCatching {
                    BackupCrypto.decryptLegacyMediaFile(enc, wrongIdOut, master, "msg43")
                }.exceptionOrNull() is SecurityException
            )
            assertFalse(wrongIdOut.exists())

            // Flipped ciphertext byte fails GCM authentication
            val tampered = File(tempDir, "tampered.enc").apply {
                val bytes = enc.readBytes()
                bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
                writeBytes(bytes)
            }
            val tamperedOut = File(tempDir, "tampered.bin")
            assertTrue(
                runCatching {
                    BackupCrypto.decryptLegacyMediaFile(tampered, tamperedOut, master, "msg42")
                }.exceptionOrNull() is SecurityException
            )
            assertFalse(tamperedOut.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
