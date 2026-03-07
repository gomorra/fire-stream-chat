package com.firestream.chat.data.remote.firebase

import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes and fetches Signal Protocol public key material from Firestore.
 *
 * Key bundle layout:  /keyBundles/{uid}
 *   registrationId      : Int
 *   identityKey         : base64(IdentityKey.serialize())
 *   signedPreKeyId      : Int
 *   signedPreKeyPublic  : base64(ECPublicKey.serialize())
 *   signedPreKeySig     : base64(byte[])
 *   preKeyId            : Int
 *   preKeyPublic        : base64(ECPublicKey.serialize())
 *   kyberPreKeyId       : Int
 *   kyberPreKeyPublic   : base64(KEMPublicKey.serialize())
 *   kyberPreKeySig      : base64(byte[])
 */
@Singleton
class FirebaseKeySource @Inject constructor(
    private val firestore: FirebaseFirestore
) {

    suspend fun publishKeys(
        uid: String,
        identityKey: IdentityKey,
        registrationId: Int,
        signedPreKey: SignedPreKeyRecord,
        preKey: PreKeyRecord,
        kyberPreKey: KyberPreKeyRecord
    ) {
        val data = hashMapOf(
            "registrationId" to registrationId,
            "identityKey" to signedPreKey.keyPair.publicKey.serialize().toB64(), // use identity key
            "signedPreKeyId" to signedPreKey.id,
            "signedPreKeyPublic" to signedPreKey.keyPair.publicKey.serialize().toB64(),
            "signedPreKeySig" to signedPreKey.signature.toB64(),
            "preKeyId" to preKey.id,
            "preKeyPublic" to preKey.keyPair.publicKey.serialize().toB64(),
            "kyberPreKeyId" to kyberPreKey.id,
            "kyberPreKeyPublic" to kyberPreKey.keyPair.publicKey.serialize().toB64(),
            "kyberPreKeySig" to kyberPreKey.signature.toB64()
        )
        // Store the identity key correctly
        data["identityKey"] = identityKey.serialize().toB64()

        firestore.collection("keyBundles").document(uid).set(data).await()
    }

    suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle? {
        val doc = firestore.collection("keyBundles").document(uid).get().await()
        if (!doc.exists()) return null
        val data = doc.data ?: return null

        return try {
            val registrationId = (data["registrationId"] as? Long)?.toInt() ?: return null
            val identityKey = IdentityKey((data["identityKey"] as? String)?.fromB64() ?: return null)
            val signedPreKeyId = (data["signedPreKeyId"] as? Long)?.toInt() ?: return null
            val signedPreKeyPublic = ECPublicKey((data["signedPreKeyPublic"] as? String)?.fromB64() ?: return null)
            val signedPreKeySig = (data["signedPreKeySig"] as? String)?.fromB64() ?: return null
            val preKeyId = (data["preKeyId"] as? Long)?.toInt() ?: return null
            val preKeyPublic = ECPublicKey((data["preKeyPublic"] as? String)?.fromB64() ?: return null)
            val kyberPreKeyId = (data["kyberPreKeyId"] as? Long)?.toInt() ?: return null
            val kyberPreKeyPublic = KEMPublicKey((data["kyberPreKeyPublic"] as? String)?.fromB64() ?: return null)
            val kyberPreKeySig = (data["kyberPreKeySig"] as? String)?.fromB64() ?: return null

            PreKeyBundle(
                registrationId,
                /* deviceId = */ 1,
                preKeyId,
                preKeyPublic,
                signedPreKeyId,
                signedPreKeyPublic,
                signedPreKeySig,
                identityKey,
                kyberPreKeyId,
                kyberPreKeyPublic,
                kyberPreKeySig
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun ByteArray.toB64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
