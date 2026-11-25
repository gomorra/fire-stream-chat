package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.KeySource
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 4 stub. Stays a stub through v0 — `BuildConfig.SUPPORTS_SIGNAL=false`
 * keeps this code path unreachable in the pocketbase flavor.
 */
@Singleton
class PocketBaseKeySource @Inject constructor() : KeySource {
    override suspend fun publishKeys(
        uid: String,
        identityKey: IdentityKey,
        registrationId: Int,
        signedPreKey: SignedPreKeyRecord,
        preKeys: List<PreKeyRecord>,
        kyberPreKey: KyberPreKeyRecord,
    ): Unit = throw NotImplementedError("PB v0 stub — Signal is gated off in this flavor")

    override suspend fun uploadPreKeys(
        uid: String,
        registrationId: Int,
        preKeys: List<PreKeyRecord>,
    ): Unit = throw NotImplementedError("PB v0 stub — Signal is gated off in this flavor")

    override suspend fun countRemotePreKeys(
        uid: String,
        registrationId: Int,
    ): Int? = throw NotImplementedError("PB v0 stub — Signal is gated off in this flavor")

    override suspend fun fetchIdentityKey(uid: String): IdentityKey? =
        throw NotImplementedError("PB v0 stub — Signal is gated off in this flavor")

    override suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle? =
        throw NotImplementedError("PB v0 stub — Signal is gated off in this flavor")
}
