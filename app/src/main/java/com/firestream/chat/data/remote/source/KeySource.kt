package com.firestream.chat.data.remote.source

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Backend-neutral Signal Protocol prekey-distribution boundary. Stub on the
 * pocketbase flavor in v0 (`BuildConfig.SUPPORTS_SIGNAL=false` keeps Signal off
 * there); follow-up plan replaces the stub with a real PB impl.
 */
interface KeySource {

    suspend fun publishKeys(
        uid: String,
        identityKey: IdentityKey,
        registrationId: Int,
        signedPreKey: SignedPreKeyRecord,
        preKeys: List<PreKeyRecord>,
        kyberPreKey: KyberPreKeyRecord,
    )

    suspend fun uploadPreKeys(
        uid: String,
        registrationId: Int,
        preKeys: List<PreKeyRecord>,
    )

    /**
     * Returns the number of unused one-time pre-keys currently stored on the
     * backend for [uid] and [registrationId], or `null` if unavailable.
     */
    suspend fun countRemotePreKeys(
        uid: String,
        registrationId: Int,
    ): Int?

    /**
     * Fetches the peer's public identity key without consuming a one-time
     * pre-key. Used for steady-state re-registration checks.
     */
    suspend fun fetchIdentityKey(uid: String): IdentityKey?

    /**
     * Atomically claims one one-time pre-key (if any remain) and returns the
     * peer's full [PreKeyBundle] for establishing a new X3DH/PQXDH session.
     */
    suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle?
}
