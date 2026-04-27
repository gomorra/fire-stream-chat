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
        preKey: PreKeyRecord,
        kyberPreKey: KyberPreKeyRecord
    )

    suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle?
}
