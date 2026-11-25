// region: AGENT-NOTE
// Responsibility: Persists the user's derived 256-bit backup master key (plus
//   salt and KDF iterations) in a private DataStore, wrapped with an Android
//   Keystore AES-256-GCM key so scheduled BackupWorker runs and post-restore
//   lazy legacy-media decryption work without re-prompting for the passphrase.
//   Supports a staged `pending` key during passphrase rotation so the active
//   key stays intact for lazy decryption until the new backup commits.
// Owns: Android Keystore alias `firestream_backup_wrap_key_v1` and DataStore
//   `fire_stream_backup_keystore`.
// Collaborators: BackupManager (save/load/rotation), LegacyMediaBackupFetcher
//   (load active key), AuthRepositoryImpl (via BackupManager.clearForSignOut).
// Don't put here: PBKDF2/HKDF derivation (BackupCrypto) or remote Storage I/O.
// endregion

package com.firestream.chat.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupKeyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fire_stream_backup_keystore"
)

data class StoredBackupKey(
    val masterKey: ByteArray,
    val salt: ByteArray,
    val kdfIterations: Int,
) {
    val saltB64: String get() = Base64.getEncoder().encodeToString(salt)
    val keyGeneration: String get() = BackupCrypto.keyGeneration(salt)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredBackupKey) return false
        return masterKey.contentEquals(other.masterKey) &&
            salt.contentEquals(other.salt) &&
            kdfIterations == other.kdfIterations
    }

    override fun hashCode(): Int {
        var result = masterKey.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + kdfIterations
        return result
    }
}

interface BackupKeyWrapper {
    fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun unwrap(ciphertext: ByteArray, iv: ByteArray): ByteArray
}

@Singleton
class AndroidKeystoreKeyWrapper @Inject constructor() : BackupKeyWrapper {
    override fun wrap(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        return ciphertext to cipher.iv
    }

    override fun unwrap(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val key = loadExistingKey()
            ?: throw GeneralSecurityException("Backup wrap key missing from AndroidKeyStore")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        loadExistingKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun loadExistingKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "firestream_backup_wrap_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

@Singleton
class BackupKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyWrapper: BackupKeyWrapper,
) {
    private val wrappedKeyPref = stringPreferencesKey("wrapped_master_key_b64")
    private val wrapIvPref = stringPreferencesKey("wrap_iv_b64")
    private val saltPref = stringPreferencesKey("salt_b64")
    private val iterationsPref = intPreferencesKey("kdf_iterations")

    private val pendingWrappedKeyPref = stringPreferencesKey("pending_wrapped_master_key_b64")
    private val pendingWrapIvPref = stringPreferencesKey("pending_wrap_iv_b64")
    private val pendingSaltPref = stringPreferencesKey("pending_salt_b64")
    private val pendingIterationsPref = intPreferencesKey("pending_kdf_iterations")

    val isConfiguredFlow: Flow<Boolean> = context.backupKeyDataStore.data
        .map { prefs ->
            prefs[wrappedKeyPref] != null &&
                prefs[wrapIvPref] != null &&
                prefs[saltPref] != null &&
                prefs[iterationsPref] != null
        }
        .distinctUntilChanged()

    /** Saves [stored] as the active backup key and clears any pending rotation key. */
    suspend fun save(stored: StoredBackupKey) {
        val (wrapped, iv) = keyWrapper.wrap(stored.masterKey)
        val encoder = Base64.getEncoder()
        context.backupKeyDataStore.edit { prefs ->
            prefs[wrappedKeyPref] = encoder.encodeToString(wrapped)
            prefs[wrapIvPref] = encoder.encodeToString(iv)
            prefs[saltPref] = encoder.encodeToString(stored.salt)
            prefs[iterationsPref] = stored.kdfIterations
            prefs.remove(pendingWrappedKeyPref)
            prefs.remove(pendingWrapIvPref)
            prefs.remove(pendingSaltPref)
            prefs.remove(pendingIterationsPref)
        }
    }

    /**
     * Stages a new passphrase key for atomic rotation without disturbing the
     * active key used to lazily decrypt existing remote legacy media blobs.
     */
    suspend fun savePendingRotation(stored: StoredBackupKey) {
        val (wrapped, iv) = keyWrapper.wrap(stored.masterKey)
        val encoder = Base64.getEncoder()
        context.backupKeyDataStore.edit { prefs ->
            prefs[pendingWrappedKeyPref] = encoder.encodeToString(wrapped)
            prefs[pendingWrapIvPref] = encoder.encodeToString(iv)
            prefs[pendingSaltPref] = encoder.encodeToString(stored.salt)
            prefs[pendingIterationsPref] = stored.kdfIterations
        }
    }

    suspend fun load(): StoredBackupKey? {
        val prefs = context.backupKeyDataStore.data.first()
        return unwrapFrom(prefs, wrappedKeyPref, wrapIvPref, saltPref, iterationsPref)
    }

    suspend fun loadPendingRotation(): StoredBackupKey? {
        val prefs = context.backupKeyDataStore.data.first()
        return unwrapFrom(prefs, pendingWrappedKeyPref, pendingWrapIvPref, pendingSaltPref, pendingIterationsPref)
    }

    suspend fun clear() {
        context.backupKeyDataStore.edit { it.clear() }
    }

    private fun unwrapFrom(
        prefs: Preferences,
        keyPref: Preferences.Key<String>,
        ivPref: Preferences.Key<String>,
        saltKey: Preferences.Key<String>,
        iterKey: Preferences.Key<Int>,
    ): StoredBackupKey? {
        val wrappedB64 = prefs[keyPref] ?: return null
        val ivB64 = prefs[ivPref] ?: return null
        val saltB64 = prefs[saltKey] ?: return null
        val iterations = prefs[iterKey] ?: return null
        return try {
            val decoder = Base64.getDecoder()
            val masterKey = keyWrapper.unwrap(decoder.decode(wrappedB64), decoder.decode(ivB64))
            val salt = decoder.decode(saltB64)
            if (masterKey.size != BackupCrypto.KEY_BYTES || salt.size != BackupCrypto.SALT_BYTES) {
                null
            } else {
                StoredBackupKey(masterKey = masterKey, salt = salt, kdfIterations = iterations)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unwrap stored backup key", e)
            null
        }
    }

    companion object {
        private const val TAG = "BackupKeyStore"
    }
}
