package com.firestream.chat.data.crypto

import android.util.Base64
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseKeySource
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
    private val keySource: FirebaseKeySource,
    private val authSource: FirebaseAuthSource
) {
    private val initMutex = Mutex()
    private val deviceId = 1

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

            // Build session if we don't have one yet
            if (!store.containsSession(address)) {
                val bundle = keySource.fetchPreKeyBundle(recipientId)
                    ?: error("No key bundle found for $recipientId — they may not have set up encryption")
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
                CiphertextMessage.PREKEY_TYPE ->
                    cipher.decrypt(PreKeySignalMessage(bytes))
                CiphertextMessage.WHISPER_TYPE ->
                    cipher.decrypt(SignalMessage(bytes))
                else -> error("Unknown Signal message type: ${message.signalType}")
            }
            String(plaintext, Charsets.UTF_8)
        }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun generateAndPublishKeys() {
        val uid = authSource.currentUserId
            ?: error("Cannot initialize Signal: user not authenticated")

        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)

        // Signed pre-key (ID = 1)
        val signedPreKeyId = 1
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            signedPreKeyId,
            System.currentTimeMillis(),
            signedPreKeyPair,
            signedPreKeySignature
        )

        // One-time pre-key (ID = 1; we publish one for now)
        val preKeyId = 1
        val preKeyPair = ECKeyPair.generate()
        val preKeyRecord = PreKeyRecord(preKeyId, preKeyPair)

        // Kyber last-resort pre-key (ID = 1)
        val kyberPreKeyId = 1
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
