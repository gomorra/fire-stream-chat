// region: AGENT-NOTE
// Responsibility: High-level Signal Protocol manager — key generation, rotating
//   one-time pre-key pool replenishment, session setup, and 1:1 encrypt/decrypt.
//   All public methods execute on Dispatchers.IO.
// Owns: Per-peer session locks (`sessionLocks`), initial registration lock
//   (`initMutex`), and pre-key replenishment lock (`preKeyMutex`). Never holds
//   `sessionLocks` across pre-key replenishment network IO.
// Collaborators: SignalProtocolStoreImpl (SignalDatabase persistence),
//   KeySource (Firestore + claimPreKeyBundle Cloud Function), AuthSource.
// Don't put here: outbox retries or plaintext-vs-ciphertext policy
//   (MessageWriter / OutboxSender), Room message persistence (MessageRepositoryImpl).
// endregion

package com.firestream.chat.data.crypto

import android.util.Base64
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.KeySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.signal.libsignal.protocol.IdentityKey
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedMessage(
    val ciphertext: String,  // Base64-encoded Signal ciphertext
    val signalType: Int,     // CiphertextMessage.WHISPER_TYPE or PREKEY_TYPE
    /**
     * Base64 identity key of the peer this was encrypted for — set by [SignalManager.encrypt],
     * absent on a received message. A stored ciphertext is only reusable while the peer still
     * publishes this identity ([SignalManager.isCurrentIdentity]): after a re-registration the
     * session it was encrypted under no longer exists on their side.
     */
    val peerIdentity: String? = null,
)

/**
 * High-level Signal Protocol manager.
 *
 * Coordinates key generation, rotating one-time pre-key publication, session
 * establishment, and message encryption/decryption. All public methods execute
 * on [Dispatchers.IO].
 */
@Singleton
class SignalManager internal constructor(
    private val store: SignalProtocolStoreImpl,
    private val keySource: KeySource,
    private val authSource: AuthSource,
    private val preKeyBatchSize: Int,
    private val preKeyLowWatermark: Int,
    private val replenishTimeoutMs: Long = DEFAULT_REPLENISH_TIMEOUT_MS,
) {
    @Inject
    constructor(
        store: SignalProtocolStoreImpl,
        keySource: KeySource,
        authSource: AuthSource,
    ) : this(
        store = store,
        keySource = keySource,
        authSource = authSource,
        preKeyBatchSize = DEFAULT_PREKEY_BATCH_SIZE,
        preKeyLowWatermark = DEFAULT_PREKEY_LOW_WATERMARK,
    )

    private val initMutex = Mutex()

    // One lock per peer, shared by encrypt and decrypt: both load that peer's
    // session record, advance its ratchet and store it back, so two interleaved
    // calls would lose one of the updates. Steady-state encrypts fetch the peer's
    // identity outside the lock; only initial session setup and stale-identity
    // re-verification do network IO under it.
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    // Replenishment generates, persists and uploads one-time pre-key batches.
    // Serialized so two concurrent PREKEY decrypts or startup calls do not
    // allocate colliding IDs or upload duplicate batches.
    private val preKeyMutex = Mutex()

    private val deviceId = 1
    private val signedPreKeyId = 1
    private val kyberPreKeyId = 1

    /**
     * Generates and publishes keys if this device has never been registered, or
     * retries publishing an unconfirmed bundle (including v1→v2 upgrades that
     * rewrite `/keyBundles/{uid}` without legacy top-level `preKeyId`). Then
     * checks both local and remote pre-key counts and replenishes best-effort.
     */
    suspend fun ensureInitialized(): Unit = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (!store.isInitialized()) {
                generateLocalKeys()
            }
            if (!store.isBundlePublished()) {
                publishInitialBundle()
            }
        }
        try {
            withTimeout(replenishTimeoutMs) {
                replenishPreKeysIfNeeded(checkRemote = true)
            }
        } catch (t: Throwable) {
            if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
        }
    }

    /**
     * Encrypts [plaintext] for [recipientId].
     *
     * Uses a cheap identity-key read on steady-state sends so existing sessions
     * never consume a peer's one-time pre-keys. Claims a full pre-key bundle only
     * when establishing a new session (or rebuilding one after re-registration).
     */
    suspend fun encrypt(recipientId: String, plaintext: String): EncryptedMessage =
        withContext(Dispatchers.IO) {
            // Read the remote identity outside the lock so steady-state sends do
            // not hold the per-peer mutex across normal network IO.
            val remoteIdentity = keySource.fetchIdentityKey(recipientId)
                ?: error("No key bundle found for $recipientId — they may not have set up encryption")

            sessionLock(recipientId).withLock {
                val address = SignalProtocolAddress(recipientId, deviceId)
                val storedIdentity = store.getIdentity(address)
                var effectiveIdentity = remoteIdentity

                if (storedIdentity != null && storedIdentity != remoteIdentity) {
                    // Stale-bundle race guard: another encrypt may have just rebuilt
                    // the session on a newer identity while our unlocked read held an
                    // older one. Re-fetch once under the lock before tearing down.
                    val freshIdentity = keySource.fetchIdentityKey(recipientId)
                        ?: error("No key bundle found for $recipientId — they may not have set up encryption")
                    effectiveIdentity = freshIdentity
                    if (freshIdentity != storedIdentity) {
                        store.deleteAllSessions(recipientId)
                        store.deleteTrustedIdentity(recipientId)
                    }
                }

                if (!store.containsSession(address)) {
                    val bundle = keySource.fetchPreKeyBundle(recipientId)
                        ?: error("No key bundle found for $recipientId — they may not have set up encryption")
                    val currentTrusted = store.getIdentity(address)
                    if (currentTrusted != null && currentTrusted != bundle.identityKey) {
                        store.deleteAllSessions(recipientId)
                        store.deleteTrustedIdentity(recipientId)
                    }
                    SessionBuilder(store, address).process(bundle)
                    effectiveIdentity = bundle.identityKey
                }

                val cipher = SessionCipher(store, address)
                val ciphertextMessage = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
                EncryptedMessage(
                    ciphertext = Base64.encodeToString(ciphertextMessage.serialize(), Base64.NO_WRAP),
                    signalType = ciphertextMessage.type,
                    peerIdentity = encodeIdentity(effectiveIdentity),
                )
            }
        }

    /**
     * Whether [recipientId] still publishes [peerIdentity], the identity a stored
     * ciphertext was encrypted for. Reads only the identity key so it never
     * consumes a one-time pre-key.
     */
    suspend fun isCurrentIdentity(recipientId: String, peerIdentity: String): Boolean =
        withContext(Dispatchers.IO) {
            val identityKey = keySource.fetchIdentityKey(recipientId) ?: return@withContext false
            encodeIdentity(identityKey) == peerIdentity
        }

    /**
     * Decrypts an [EncryptedMessage] from [senderId].
     * Returns the plaintext string, or throws on failure.
     */
    suspend fun decrypt(senderId: String, message: EncryptedMessage): String =
        withContext(Dispatchers.IO) {
            val bytes = Base64.decode(message.ciphertext, Base64.NO_WRAP)

            val plaintext = sessionLock(senderId).withLock {
                val cipher = SessionCipher(store, SignalProtocolAddress(senderId, deviceId))
                when (message.signalType) {
                    CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(bytes))
                    CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
                    else -> error("Unknown Signal message type: ${message.signalType}")
                }
            }
            if (message.signalType == CiphertextMessage.PREKEY_TYPE) {
                // Replenish outside the session lock so sends to this peer never
                // wait on pre-key network IO. Bounded by timeout so offline commits
                // never hang decryption; next ensureInitialized() retries pending keys.
                try {
                    withTimeout(replenishTimeoutMs) {
                        replenishPreKeysIfNeeded(checkRemote = false)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
                }
            }
            String(plaintext, Charsets.UTF_8)
        }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun sessionLock(userId: String): Mutex = sessionLocks.computeIfAbsent(userId) { Mutex() }

    private fun encodeIdentity(identityKey: IdentityKey): String =
        Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)

    /**
     * Ensures at least [preKeyLowWatermark] one-time pre-keys are available and
     * uploads every locally persisted pre-key whose public half has not yet been
     * acknowledged by [KeySource].
     *
     * When [checkRemote] is true (startup), also queries the backend pool count
     * so remote exhaustion (e.g. abandoned claims) cannot strand Firestore at 0
     * keys while local keys sit unused in `signal.db`.
     */
    internal suspend fun replenishPreKeysIfNeeded(checkRemote: Boolean = false) = preKeyMutex.withLock {
        if (!store.isInitialized() || !store.isBundlePublished()) return@withLock
        val uid = authSource.currentUserId ?: return@withLock
        val registrationId = store.getLocalRegistrationId()

        val localCount = store.countPreKeys()
        val pendingCount = store.getUnuploadedPreKeys().size
        val remoteCount = if (checkRemote) {
            try {
                keySource.countRemotePreKeys(uid, registrationId)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
        } else {
            null
        }
        // Account for locally generated keys still waiting to upload so offline
        // retries flush the existing pending batch instead of generating duplicates.
        val projectedRemoteCount = remoteCount?.let { it + pendingCount }
        val effectiveCount = if (projectedRemoteCount != null) minOf(localCount, projectedRemoteCount) else localCount

        if (effectiveCount < preKeyLowWatermark) {
            val needed = (preKeyBatchSize - effectiveCount).coerceAtLeast(1)
            val startId = maxOf(store.getNextPreKeyId(), (store.getMaxPreKeyId() ?: 0) + 1)
            val newRecords = generatePreKeyBatch(startId, needed)
            store.storePreKeys(newRecords, uploaded = false)
            store.updateNextPreKeyId(startId + needed)
        }

        val pending = store.getUnuploadedPreKeys()
        if (pending.isNotEmpty()) {
            keySource.uploadPreKeys(uid, registrationId, pending)
            store.markPreKeysUploaded(pending.map { it.id })
        }
    }

    private fun generateLocalKeys() {
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)

        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            signedPreKeyId,
            System.currentTimeMillis(),
            signedPreKeyPair,
            signedPreKeySignature,
        )

        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey
            .calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberPreKeyRecord = KyberPreKeyRecord(
            kyberPreKeyId,
            System.currentTimeMillis(),
            kyberKeyPair,
            kyberSignature,
        )

        val preKeys = generatePreKeyBatch(startId = 1, count = preKeyBatchSize)

        // Single SQLite transaction: identity is never visible without its pre-keys.
        store.saveInitialKeys(
            pair = identityKeyPair,
            registrationId = registrationId,
            signedPreKey = signedPreKeyRecord,
            kyberPreKey = kyberPreKeyRecord,
            preKeys = preKeys,
            nextPreKeyId = 1 + preKeyBatchSize,
        )
    }

    private suspend fun publishInitialBundle() = preKeyMutex.withLock {
        val uid = authSource.currentUserId
            ?: error("Cannot initialize Signal: user not authenticated")
        val identityKeyPair = store.getIdentityKeyPair()
        val registrationId = store.getLocalRegistrationId()
        val signedPreKeyRecord = store.loadSignedPreKey(signedPreKeyId)
        val kyberPreKeyRecord = store.loadKyberPreKey(kyberPreKeyId)
        val pendingPreKeys = store.getUnuploadedPreKeys()

        keySource.publishKeys(
            uid = uid,
            identityKey = identityKeyPair.publicKey,
            registrationId = registrationId,
            signedPreKey = signedPreKeyRecord,
            preKeys = pendingPreKeys,
            kyberPreKey = kyberPreKeyRecord,
        )
        store.markBundlePublished()
        store.markPreKeysUploaded(pendingPreKeys.map { it.id })
    }

    private fun generatePreKeyBatch(startId: Int, count: Int): List<PreKeyRecord> =
        (0 until count).map { offset ->
            PreKeyRecord(startId + offset, ECKeyPair.generate())
        }

    companion object {
        const val DEFAULT_PREKEY_BATCH_SIZE = 50
        const val DEFAULT_PREKEY_LOW_WATERMARK = 20
        const val DEFAULT_REPLENISH_TIMEOUT_MS = 10_000L
    }
}
