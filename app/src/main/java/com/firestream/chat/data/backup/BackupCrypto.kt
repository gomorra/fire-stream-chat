// region: AGENT-NOTE
// Responsibility: Cryptographic primitives for passphrase-protected encrypted
//   backups: PBKDF2-HMAC-SHA256 master key derivation, RFC 5869 HKDF-SHA256
//   domain-separated subkeys (db, manifest, per-message legacy media),
//   authenticated manifest encryption/parsing, and legacy-media blob framing.
// Owns: BackupManifest / BackupManifestInner JSON wire format, KDF parameters
//   (600,000 PBKDF2 iterations, 32-byte salt), and legacy-media IV prefix framing.
// Collaborators: MediaCrypto (chunked AES-256-GCM for db.enc and legacy-media),
//   BackupManager, LegacyMediaBackupFetcher.
// Don't put here: Android Keystore wrapping (BackupKeyStore), Room snapshotting,
//   or Firebase Storage I/O (BackupSource).
// endregion

package com.firestream.chat.data.backup

import com.firestream.chat.data.crypto.MediaCrypto
import com.firestream.chat.domain.model.UnsupportedBackupVersionException
import com.firestream.chat.domain.model.WrongPassphraseException
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupManifest(
    val version: Int,
    val saltB64: String,
    val kdfIterations: Int,
    val createdAt: Long,
    val dbObjectName: String,
    val dbCipherSizeBytes: Long,
    val legacyMediaComplete: Boolean,
    val encryptedMetaB64: String,
    val encryptedMetaIvB64: String,
)

data class BackupManifestInner(
    val dbIvB64: String,
    val dbSha256B64: String,
    val schemaVersion: Int,
    val messageCount: Int,
    val legacyMediaIds: List<String>,
)

object BackupCrypto {
    const val MANIFEST_VERSION = 1
    const val MIN_PASSPHRASE_LENGTH = 12
    const val DEFAULT_KDF_ITERATIONS = 600_000
    const val MIN_KDF_ITERATIONS = 100_000
    const val MAX_KDF_ITERATIONS = 5_000_000
    const val SALT_BYTES = 32
    const val KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val LEGACY_BLOB_HEADER_VERSION: Byte = 1
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_HASH_LEN = 32

    private val DB_OBJECT_NAME_REGEX = Regex("^db_[0-9]+(_[0-9a-f]{8})?\\.enc$")
    private val secureRandom = SecureRandom()

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)

    /** 8-char lowercase hex suffix for collision-free versioned `db_<ts>_<hex>.enc` names. */
    fun randomHexSuffix(): String {
        val bytes = ByteArray(4).also(secureRandom::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Deterministic 16-char lowercase hex key-generation namespace derived from
     * [salt]. Namespacing `legacy-media/{keyGen}/{messageId}.enc` keeps
     * passphrase rotation atomic: new blobs never overwrite the prior
     * generation before `manifest.json` commits.
     */
    fun keyGeneration(salt: ByteArray): String {
        require(salt.size == SALT_BYTES) { "Expected $SALT_BYTES-byte salt, got ${salt.size}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(salt)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun deriveMasterKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int = DEFAULT_KDF_ITERATIONS,
    ): ByteArray {
        require(salt.size == SALT_BYTES) { "Expected $SALT_BYTES-byte salt, got ${salt.size}" }
        require(iterations > 0) { "Iterations must be positive" }
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun hkdfSha256(
        ikm: ByteArray,
        info: ByteArray,
        length: Int = KEY_BYTES,
        salt: ByteArray = ByteArray(HMAC_HASH_LEN),
    ): ByteArray {
        require(length in 1..(255 * HMAC_HASH_LEN)) { "Invalid HKDF output length: $length" }
        val actualSalt = if (salt.isEmpty()) ByteArray(HMAC_HASH_LEN) else salt
        val prk = hmacSha256(actualSalt, ikm)

        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val toCopy = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    fun deriveDbKey(masterKey: ByteArray): ByteArray {
        require(masterKey.size == KEY_BYTES) { "Expected $KEY_BYTES-byte master key" }
        return hkdfSha256(masterKey, "firestream-backup-v1-db".toByteArray(StandardCharsets.UTF_8))
    }

    fun deriveManifestKey(masterKey: ByteArray): ByteArray {
        require(masterKey.size == KEY_BYTES) { "Expected $KEY_BYTES-byte master key" }
        return hkdfSha256(masterKey, "firestream-backup-v1-manifest".toByteArray(StandardCharsets.UTF_8))
    }

    fun deriveLegacyMediaKey(masterKey: ByteArray, messageId: String): ByteArray {
        require(masterKey.size == KEY_BYTES) { "Expected $KEY_BYTES-byte master key" }
        require(messageId.isNotEmpty()) { "messageId must not be empty" }
        return hkdfSha256(
            masterKey,
            "firestream-backup-v1-legacy-media:$messageId".toByteArray(StandardCharsets.UTF_8),
        )
    }

    /**
     * Encrypts [inner] with `deriveManifestKey(masterKey)` binding the outer
     * manifest header fields as GCM AAD, and returns the complete [BackupManifest].
     */
    fun createManifest(
        masterKey: ByteArray,
        salt: ByteArray,
        kdfIterations: Int,
        createdAt: Long,
        dbObjectName: String,
        dbCipherSizeBytes: Long,
        legacyMediaComplete: Boolean,
        inner: BackupManifestInner,
    ): BackupManifest {
        require(DB_OBJECT_NAME_REGEX.matches(dbObjectName)) { "Invalid dbObjectName: $dbObjectName" }
        val encoder = Base64.getEncoder()
        val saltB64 = encoder.encodeToString(salt)
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val innerJson = encodeInnerJson(inner).toByteArray(StandardCharsets.UTF_8)
        val aad = manifestAad(
            version = MANIFEST_VERSION,
            saltB64 = saltB64,
            kdfIterations = kdfIterations,
            createdAt = createdAt,
            dbObjectName = dbObjectName,
            dbCipherSizeBytes = dbCipherSizeBytes,
            legacyMediaComplete = legacyMediaComplete,
        )
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveManifestKey(masterKey), "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(innerJson)
        return BackupManifest(
            version = MANIFEST_VERSION,
            saltB64 = saltB64,
            kdfIterations = kdfIterations,
            createdAt = createdAt,
            dbObjectName = dbObjectName,
            dbCipherSizeBytes = dbCipherSizeBytes,
            legacyMediaComplete = legacyMediaComplete,
            encryptedMetaB64 = encoder.encodeToString(encrypted),
            encryptedMetaIvB64 = encoder.encodeToString(iv),
        )
    }

    /**
     * Decrypts and authenticates [manifest]'s inner metadata using [masterKey].
     * Throws [WrongPassphraseException] if GCM authentication fails.
     */
    fun decryptManifestInner(manifest: BackupManifest, masterKey: ByteArray): BackupManifestInner {
        val decoder = Base64.getDecoder()
        val encrypted = try {
            decoder.decode(manifest.encryptedMetaB64)
        } catch (e: IllegalArgumentException) {
            throw WrongPassphraseException("Corrupted backup manifest metadata", e)
        }
        val iv = try {
            decoder.decode(manifest.encryptedMetaIvB64)
        } catch (e: IllegalArgumentException) {
            throw WrongPassphraseException("Corrupted backup manifest IV", e)
        }
        if (iv.size != GCM_IV_BYTES) {
            throw WrongPassphraseException("Invalid backup manifest IV length")
        }
        val aad = manifestAad(
            version = manifest.version,
            saltB64 = manifest.saltB64,
            kdfIterations = manifest.kdfIterations,
            createdAt = manifest.createdAt,
            dbObjectName = manifest.dbObjectName,
            dbCipherSizeBytes = manifest.dbCipherSizeBytes,
            legacyMediaComplete = manifest.legacyMediaComplete,
        )
        val plainBytes = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveManifestKey(masterKey), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(encrypted)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException("Incorrect backup passphrase or tampered manifest", e)
        }
        return parseInnerJson(String(plainBytes, StandardCharsets.UTF_8))
    }

    fun serializeManifest(manifest: BackupManifest): String = JSONObject().apply {
        put("version", manifest.version)
        put("saltB64", manifest.saltB64)
        put("kdfIterations", manifest.kdfIterations)
        put("createdAt", manifest.createdAt)
        put("dbObjectName", manifest.dbObjectName)
        put("dbCipherSizeBytes", manifest.dbCipherSizeBytes)
        put("legacyMediaComplete", manifest.legacyMediaComplete)
        put("encryptedMetaB64", manifest.encryptedMetaB64)
        put("encryptedMetaIvB64", manifest.encryptedMetaIvB64)
    }.toString()

    fun parseManifest(
        json: String,
        minIterations: Int = MIN_KDF_ITERATIONS,
    ): BackupManifest {
        val obj = JSONObject(json)
        val version = obj.requireInt("version")
        if (version > MANIFEST_VERSION) {
            throw UnsupportedBackupVersionException(
                "This backup requires a newer version of FireStream (manifest v$version)."
            )
        }
        require(version == MANIFEST_VERSION) { "Unsupported backup manifest version: $version" }
        val saltB64 = obj.requireString("saltB64")
        val salt = try {
            Base64.getDecoder().decode(saltB64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid saltB64", e)
        }
        require(salt.size == SALT_BYTES) { "Invalid salt length: ${salt.size}" }
        val kdfIterations = obj.requireInt("kdfIterations")
        require(kdfIterations in minIterations..MAX_KDF_ITERATIONS) {
            "Backup KDF iterations ($kdfIterations) outside allowed range ($minIterations..$MAX_KDF_ITERATIONS)"
        }
        val createdAt = obj.requireLong("createdAt")
        val dbObjectName = obj.requireString("dbObjectName")
        require(DB_OBJECT_NAME_REGEX.matches(dbObjectName)) {
            "Invalid dbObjectName in manifest: $dbObjectName"
        }
        val dbCipherSizeBytes = obj.requireLong("dbCipherSizeBytes")
        require(dbCipherSizeBytes > 0L) { "Invalid dbCipherSizeBytes: $dbCipherSizeBytes" }
        val legacyMediaComplete = obj.requireBoolean("legacyMediaComplete")
        val encryptedMetaB64 = obj.requireString("encryptedMetaB64")
        val encryptedMetaIvB64 = obj.requireString("encryptedMetaIvB64")
        return BackupManifest(
            version = version,
            saltB64 = saltB64,
            kdfIterations = kdfIterations,
            createdAt = createdAt,
            dbObjectName = dbObjectName,
            dbCipherSizeBytes = dbCipherSizeBytes,
            legacyMediaComplete = legacyMediaComplete,
            encryptedMetaB64 = encryptedMetaB64,
            encryptedMetaIvB64 = encryptedMetaIvB64,
        )
    }

    /**
     * Encrypts a legacy plaintext media file into [output] with self-contained
     * framing `[version=1][12-byte random baseIv][MediaCrypto chunked stream]`
     * keyed by `deriveLegacyMediaKey(masterKey, messageId)`.
     */
    fun encryptLegacyMediaFile(
        input: File,
        output: File,
        masterKey: ByteArray,
        messageId: String,
    ) {
        val key = deriveLegacyMediaKey(masterKey, messageId)
        val baseIv = MediaCrypto.randomNonce()
        input.inputStream().use { i ->
            output.outputStream().use { o ->
                o.write(LEGACY_BLOB_HEADER_VERSION.toInt())
                o.write(baseIv)
                MediaCrypto.encryptStream(i, o, keyBytes = key, ivBytes = baseIv)
            }
        }
    }

    /**
     * Atomically decrypts a self-contained legacy media backup blob into
     * [outputFile] via a sibling `.dec.part` temp file, verifying every chunk's
     * GCM tag.
     */
    fun decryptLegacyMediaFile(
        encryptedFile: File,
        outputFile: File,
        masterKey: ByteArray,
        messageId: String,
    ) {
        val key = deriveLegacyMediaKey(masterKey, messageId)
        val parent = outputFile.parentFile ?: throw IOException("No parent directory for $outputFile")
        parent.mkdirs()
        val partial = File.createTempFile("${outputFile.name}.", ".dec.part", parent)
        try {
            encryptedFile.inputStream().use { input ->
                val version = input.read()
                if (version != LEGACY_BLOB_HEADER_VERSION.toInt()) {
                    throw SecurityException("Unsupported legacy backup media version: $version")
                }
                val baseIv = ByteArray(MediaCrypto.NONCE_BYTES)
                var offset = 0
                while (offset < baseIv.size) {
                    val n = input.read(baseIv, offset, baseIv.size - offset)
                    if (n == -1) throw EOFException("Truncated legacy backup media IV")
                    offset += n
                }
                partial.outputStream().use { output ->
                    MediaCrypto.decryptStreamRaw(input, output, keyBytes = key, ivBytes = baseIv, expectedSha256 = null)
                }
            }
            if (!partial.renameTo(outputFile)) {
                throw IOException("Cannot rename decrypted legacy media file to $outputFile")
            }
        } finally {
            partial.delete()
        }
    }

    private fun encodeInnerJson(inner: BackupManifestInner): String = JSONObject().apply {
        put("dbIvB64", inner.dbIvB64)
        put("dbSha256B64", inner.dbSha256B64)
        put("schemaVersion", inner.schemaVersion)
        put("messageCount", inner.messageCount)
        put("legacyMediaIds", JSONArray(inner.legacyMediaIds))
    }.toString()

    private fun parseInnerJson(json: String): BackupManifestInner {
        val obj = JSONObject(json)
        val dbIvB64 = obj.requireString("dbIvB64")
        val dbSha256B64 = obj.requireString("dbSha256B64")
        val schemaVersion = obj.requireInt("schemaVersion")
        val messageCount = obj.requireInt("messageCount")
        val idsArray = obj.opt("legacyMediaIds") as? JSONArray
            ?: throw IllegalArgumentException("Missing legacyMediaIds array")
        val ids = buildList(idsArray.length()) {
            for (i in 0 until idsArray.length()) {
                val item = idsArray.opt(i) as? String
                    ?: throw IllegalArgumentException("Non-string entry in legacyMediaIds at $i")
                add(item)
            }
        }
        return BackupManifestInner(
            dbIvB64 = dbIvB64,
            dbSha256B64 = dbSha256B64,
            schemaVersion = schemaVersion,
            messageCount = messageCount,
            legacyMediaIds = ids,
        )
    }

    private fun manifestAad(
        version: Int,
        saltB64: String,
        kdfIterations: Int,
        createdAt: Long,
        dbObjectName: String,
        dbCipherSizeBytes: Long,
        legacyMediaComplete: Boolean,
    ): ByteArray =
        "$version|$saltB64|$kdfIterations|$createdAt|$dbObjectName|$dbCipherSizeBytes|$legacyMediaComplete"
            .toByteArray(StandardCharsets.UTF_8)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    private fun JSONObject.requireString(key: String): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("Missing or invalid string '$key'")
        require(value.isNotEmpty()) { "Empty string '$key'" }
        return value
    }

    private fun JSONObject.requireInt(key: String): Int {
        val num = opt(key) as? Number ?: throw IllegalArgumentException("Missing or invalid int '$key'")
        val d = num.toDouble()
        require(d.isFinite() && d == num.toInt().toDouble()) { "Non-integer '$key': $num" }
        return num.toInt()
    }

    private fun JSONObject.requireLong(key: String): Long {
        val num = opt(key) as? Number ?: throw IllegalArgumentException("Missing or invalid long '$key'")
        if (num is Int || num is Long) return num.toLong()
        val d = num.toDouble()
        require(d.isFinite() && d == num.toLong().toDouble()) { "Non-integer '$key': $num" }
        return num.toLong()
    }

    private fun JSONObject.requireBoolean(key: String): Boolean =
        opt(key) as? Boolean ?: throw IllegalArgumentException("Missing or invalid boolean '$key'")
}
