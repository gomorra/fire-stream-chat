package com.firestream.chat.data.crypto

import android.util.Base64
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.KeySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedMessage(
    val ciphertext: String,  // Base64-encoded Signal ciphertext
    val signalType: Int      // CiphertextMessage.WHISPER_TYPE or PREKEY_TYPE
)

/**
 * High-level Signal Protocol manager.
 *
 * Coordinates key generation, Firestore publication, session establishment, and
 * message encryption/decryption. All public methods execute on [Dispatchers.IO].
 */
@Singleton
class SignalManager @Inject constructor(
    private val store: SignalProtocolStoreImpl,
    private val keySource: KeySource,
    private val authSource: AuthSource
) {
    private val initMutex = Mutex()
    private val deviceId = 1
    private val preKeyId = 1
    private val signedPreKeyId = 1
    private val kyberPreKeyId = 1

    /**
     * Generates and publishes keys if this device has never been registered.
     * Safe to call multiple times — no-op after first successful init.
     */
    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (store.isInitialized()) return@withContext
        initMutex.withLock {
            if (store.isInitialized()) return@withLock
            generateAndPublishKeys()
        }
    }

    /**
     * Encrypts [plaintext] for [recipientId].
     * Establishes an X3DH session on first message if needed.
     */
    suspend fun encrypt(recipientId: String, plaintext: String): EncryptedMessage =
        withContext(Dispatchers.IO) {
            val address = SignalProtocolAddress(recipientId, deviceId)

            // Always fetch the current bundle so we can detect if the remote party re-registered
            // (e.g. after clearing app data or reinstalling). If their identity key changed we
            // must throw away the stale session and establish a fresh one — otherwise we would
            // send WHISPER_TYPE ciphertext that the recipient can no longer decrypt.
            val bundle = keySource.fetchPreKeyBundle(recipientId)
                ?: error("No key bundle found for $recipientId — they may not have set up encryption")

            val storedIdentity = store.getIdentity(address)
            val remoteIdentityChanged = storedIdentity != null && storedIdentity != bundle.identityKey
            if (remoteIdentityChanged) {
                // Remote party re-registered: clear stale session and trust record so that
                // isTrustedIdentity() falls back to TOFU and allows the new identity.
                store.deleteAllSessions(recipientId)
                store.deleteTrustedIdentity(recipientId)
            }

            if (!store.containsSession(address)) {
                SessionBuilder(store, address).process(bundle)
            }

            val cipher = SessionCipher(store, address)
            val ciphertextMessage = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            EncryptedMessage(
                ciphertext = Base64.encodeToString(ciphertextMessage.serialize(), Base64.NO_WRAP),
                signalType = ciphertextMessage.type
            )
        }

    /**
     * Decrypts an [EncryptedMessage] from [senderId].
     * Returns the plaintext string, or throws on failure.
     */
    suspend fun decrypt(senderId: String, message: EncryptedMessage): String =
        withContext(Dispatchers.IO) {
            val address = SignalProtocolAddress(senderId, deviceId)
            val cipher = SessionCipher(store, address)
            val bytes = Base64.decode(message.ciphertext, Base64.NO_WRAP)

            val plaintext = when (message.signalType) {
                CiphertextMessage.PREKEY_TYPE -> {
                    val result = cipher.decrypt(PreKeySignalMessage(bytes))
                    // The Signal library consumed our one-time pre-key during decryption.
                    // Replenish it so the next new contact can establish a fresh session.
                    // Best-effort: a replenishment failure must not mask the successful decryption.
                    runCatching { replenishPreKeyIfNeeded() }
                    result
                }
                CiphertextMessage.WHISPER_TYPE ->
                    cipher.decrypt(SignalMessage(bytes))
                else -> error("Unknown Signal message type: ${message.signalType}")
            }
            String(plaintext, Charsets.UTF_8)
        }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Generates a fresh one-time pre-key and publishes the updated bundle to Firestore.
     * Called after a [PreKeySignalMessage] is successfully decrypted to ensure the next
     * new contact always finds a valid pre-key in our published bundle.
     */
    private suspend fun replenishPreKeyIfNeeded() {
        if (store.containsPreKey(preKeyId)) return  // still available, nothing to do
        val uid = authSource.currentUserId ?: return
        val identityKeyPair = store.getIdentityKeyPair()
        val newPreKeyPair = ECKeyPair.generate()
        val newPreKeyRecord = PreKeyRecord(preKeyId, newPreKeyPair)
        val signedPreKeyRecord = store.loadSignedPreKey(signedPreKeyId)
        val kyberPreKeyRecord = store.loadKyberPreKey(kyberPreKeyId)
        // Publish to Firestore first. If this throws, the local store is not updated, so
        // containsPreKey() stays false and the next PREKEY decryption will retry.
        keySource.publishKeys(
            uid = uid,
            identityKey = identityKeyPair.publicKey,
            registrationId = store.getLocalRegistrationId(),
            signedPreKey = signedPreKeyRecord,
            preKey = newPreKeyRecord,
            kyberPreKey = kyberPreKeyRecord
        )
        store.storePreKey(preKeyId, newPreKeyRecord)
    }

    private suspend fun generateAndPublishKeys() {
        val uid = authSource.currentUserId
            ?: error("Cannot initialize Signal: user not authenticated")

        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)

        // Signed pre-key
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            signedPreKeyId,
            System.currentTimeMillis(),
            signedPreKeyPair,
            signedPreKeySignature
        )

        // One-time pre-key
        val preKeyPair = ECKeyPair.generate()
        val preKeyRecord = PreKeyRecord(preKeyId, preKeyPair)

        // Kyber last-resort pre-key
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey
            .calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberPreKeyRecord = KyberPreKeyRecord(
            kyberPreKeyId,
            System.currentTimeMillis(),
            kyberKeyPair,
            kyberSignature
        )

        // Persist locally first
        store.saveIdentityKeyPair(identityKeyPair, registrationId)
        store.storeSignedPreKey(signedPreKeyId, signedPreKeyRecord)
        store.storePreKey(preKeyId, preKeyRecord)
        store.storeKyberPreKey(kyberPreKeyId, kyberPreKeyRecord)

        // Publish public keys to Firestore
        keySource.publishKeys(
            uid = uid,
            identityKey = identityKeyPair.publicKey,
            registrationId = registrationId,
            signedPreKey = signedPreKeyRecord,
            preKey = preKeyRecord,
            kyberPreKey = kyberPreKeyRecord
        )
    }
}
