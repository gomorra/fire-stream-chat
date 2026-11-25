package com.firestream.chat.data.remote.firebase

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.PreKeyBundle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
class FirebaseKeySourceTest {

    private fun validMap(includeOneTimePreKey: Boolean): Map<String, Any?> {
        val identity = IdentityKeyPair.generate()
        val spk = ECKeyPair.generate()
        val spkSig = identity.privateKey.calculateSignature(spk.publicKey.serialize())
        val kyber = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identity.privateKey.calculateSignature(kyber.publicKey.serialize())
        val otpk = ECKeyPair.generate()

        fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)

        return mapOf(
            "found" to true,
            "registrationId" to 77,
            "identityKey" to identity.publicKey.serialize().b64(),
            "signedPreKeyId" to 1,
            "signedPreKeyPublic" to spk.publicKey.serialize().b64(),
            "signedPreKeySig" to spkSig.b64(),
            "kyberPreKeyId" to 1,
            "kyberPreKeyPublic" to kyber.publicKey.serialize().b64(),
            "kyberPreKeySig" to kyberSig.b64(),
            "preKeyId" to if (includeOneTimePreKey) 19 else null,
            "preKeyPublic" to if (includeOneTimePreKey) otpk.publicKey.serialize().b64() else null,
        )
    }

    @Test
    fun `parseClaimedBundle parses bundle with one-time pre-key`() {
        val bundle = FirebaseKeySource.parseClaimedBundle(validMap(includeOneTimePreKey = true))
        assertNotNull(bundle)
        assertEquals(77, bundle!!.registrationId)
        assertEquals(19, bundle.preKeyId)
        assertNotNull(bundle.preKey)
    }

    @Test
    fun `parseClaimedBundle falls back to NULL_PRE_KEY_ID when one-time pre-key is null`() {
        val bundle = FirebaseKeySource.parseClaimedBundle(validMap(includeOneTimePreKey = false))
        assertNotNull(bundle)
        assertEquals(PreKeyBundle.NULL_PRE_KEY_ID, bundle!!.preKeyId)
        assertNull(bundle.preKey)
    }

    @Test
    fun `parseClaimedBundle returns null when found is false or payload is malformed`() {
        assertNull(FirebaseKeySource.parseClaimedBundle(mapOf("found" to false)))
        assertNull(FirebaseKeySource.parseClaimedBundle(null))
        assertNull(FirebaseKeySource.parseClaimedBundle("not-a-map"))
        assertNull(FirebaseKeySource.parseClaimedBundle(mapOf("found" to true, "registrationId" to 1)))
    }
}
