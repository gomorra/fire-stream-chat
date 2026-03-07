package com.firestream.chat.data.crypto

import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.local.entity.SignalIdentityEntity
import com.firestream.chat.data.local.entity.SignalKyberPreKeyEntity
import com.firestream.chat.data.local.entity.SignalPreKeyEntity
import com.firestream.chat.data.local.entity.SignalSenderKeyEntity
import com.firestream.chat.data.local.entity.SignalSessionEntity
import com.firestream.chat.data.local.entity.SignalSignedPreKeyEntity
import com.firestream.chat.data.local.entity.SignalTrustedIdentityEntity
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [SignalProtocolStore].
 *
 * All interface methods are synchronous (as required by the Signal SDK). They must be called
 * exclusively from a background thread — [SignalManager] guarantees this via Dispatchers.IO.
 *
 * Kyber pre-key reuse tracking is held in memory (resets on restart). The Kyber key is a
 * "last-resort" key so occasional reuse across restarts is acceptable in Phase 3.
 */
@Singleton
class SignalProtocolStoreImpl @Inject constructor(
    private val signalDao: SignalDao
) : SignalProtocolStore {

    // Kyber reuse guard: preKeyId -> set of seen base-key bytes (base64).
    // In-memory only; resets on process restart (acceptable for Phase 3 last-resort keys).
    private val kyberUsedBaseKeys = ConcurrentHashMap<Int, MutableSet<String>>()

    private fun newConcurrentStringSet(): MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    // ── IdentityKeyStore ──────────────────────────────────────────────────────

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val entity = signalDao.getIdentity()
            ?: error("Signal identity not initialised — call SignalManager.ensureInitialized() first")
        return IdentityKeyPair(entity.identityKeyPair)
    }

    override fun getLocalRegistrationId(): Int {
        val entity = signalDao.getIdentity()
            ?: error("Signal identity not initialised")
        return entity.registrationId
    }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val existing = signalDao.getTrustedIdentity(address.name)
        val change = when {
            existing == null -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            existing.identityKey.contentEquals(identityKey.serialize()) -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
            else -> IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
        if (change != IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED || existing == null) {
            signalDao.saveTrustedIdentity(
                SignalTrustedIdentityEntity(address.name, identityKey.serialize())
            )
        }
        return change
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        // TOFU: trust first-seen identity; reject any subsequent change
        val existing = signalDao.getTrustedIdentity(address.name) ?: return true
        return existing.identityKey.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        return signalDao.getTrustedIdentity(address.name)
            ?.let { IdentityKey(it.identityKey) }
    }

    // ── PreKeyStore ───────────────────────────────────────────────────────────

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        return signalDao.getPreKey(preKeyId)?.let { PreKeyRecord(it.record) }
            ?: throw InvalidKeyIdException("Pre-key $preKeyId not found")
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        signalDao.savePreKey(SignalPreKeyEntity(preKeyId, record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = signalDao.containsPreKey(preKeyId)

    override fun removePreKey(preKeyId: Int) = signalDao.deletePreKey(preKeyId)

    // ── SignedPreKeyStore ─────────────────────────────────────────────────────

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        return signalDao.getSignedPreKey(signedPreKeyId)?.let { SignedPreKeyRecord(it.record) }
            ?: throw InvalidKeyIdException("Signed pre-key $signedPreKeyId not found")
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return signalDao.getAllSignedPreKeys().map { SignedPreKeyRecord(it.record) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        signalDao.saveSignedPreKey(SignalSignedPreKeyEntity(signedPreKeyId, record.serialize()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        signalDao.containsSignedPreKey(signedPreKeyId)

    override fun removeSignedPreKey(signedPreKeyId: Int) =
        signalDao.deleteSignedPreKey(signedPreKeyId)

    // ── SessionStore ──────────────────────────────────────────────────────────

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val key = address.toKey()
        return signalDao.getSession(key)?.let { SessionRecord(it.record) } ?: SessionRecord()
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        return addresses.map { addr ->
            signalDao.getSession(addr.toKey())?.let { SessionRecord(it.record) }
                ?: throw NoSessionException("No session for ${addr.name}")
        }
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        return signalDao.getSessionsByName(name)
            .mapNotNull { it.address.substringAfterLast(':').toIntOrNull() }
            .filter { it != 1 } // device 1 is the primary device, not a "sub" device
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        signalDao.saveSession(SignalSessionEntity(address.toKey(), record.serialize()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        signalDao.containsSession(address.toKey())

    override fun deleteSession(address: SignalProtocolAddress) =
        signalDao.deleteSession(address.toKey())

    override fun deleteAllSessions(name: String) = signalDao.deleteAllSessionsByName(name)

    // ── KyberPreKeyStore ──────────────────────────────────────────────────────

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        return signalDao.getKyberPreKey(kyberPreKeyId)?.let { KyberPreKeyRecord(it.record) }
            ?: throw InvalidKeyIdException("Kyber pre-key $kyberPreKeyId not found")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return signalDao.getAllKyberPreKeys().map { KyberPreKeyRecord(it.record) }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        signalDao.saveKyberPreKey(SignalKyberPreKeyEntity(kyberPreKeyId, record.serialize()))
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean =
        signalDao.containsKyberPreKey(kyberPreKeyId)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, registrationId: Int, baseKey: ECPublicKey) {
        val baseKeyB64 = android.util.Base64.encodeToString(baseKey.serialize(), android.util.Base64.NO_WRAP)
        val seen = kyberUsedBaseKeys.getOrPut(kyberPreKeyId) { newConcurrentStringSet() }
        if (!seen.add(baseKeyB64)) {
            throw ReusedBaseKeyException("Kyber pre-key $kyberPreKeyId reused with same base key")
        }
    }

    // ── SenderKeyStore ────────────────────────────────────────────────────────

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        val key = "${sender.name}:${sender.deviceId}:$distributionId"
        signalDao.saveSenderKey(SignalSenderKeyEntity(key, record.serialize()))
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord? {
        // Returns null when no sender key exists (matches Signal's InMemorySenderKeyStore behaviour)
        val key = "${sender.name}:${sender.deviceId}:$distributionId"
        return signalDao.getSenderKey(key)?.let { SenderKeyRecord(it.record) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun isInitialized(): Boolean = signalDao.getIdentity() != null

    fun saveIdentityKeyPair(pair: IdentityKeyPair, registrationId: Int) {
        signalDao.saveIdentity(SignalIdentityEntity(identityKeyPair = pair.serialize(), registrationId = registrationId))
    }

    private fun SignalProtocolAddress.toKey() = "$name:$deviceId"
}
