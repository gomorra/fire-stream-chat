// region: AGENT-NOTE
// Responsibility: Publishes Signal public key bundles and rotating one-time
//   pre-key batches to Firestore, reads peer identity keys, counts the owner's
//   remote pre-keys, and claims peer pre-key bundles atomically through the
//   `claimPreKeyBundle` Cloud Function.
// Owns: `/keyBundles/{uid}` and `/keyBundles/{uid}/oneTimePreKeys/{preKeyId}`
//   wire format and `claimPreKeyBundle` response parsing.
// Collaborators: SignalManager (only caller), FirebaseFirestore,
//   FirebaseFunctions, `functions/index.js` (`claimPreKeyBundle`).
// Don't put here: key generation, session locks, or local SignalDatabase access
//   (all belong in SignalManager / SignalProtocolStoreImpl).
// endregion

package com.firestream.chat.data.remote.firebase

import android.util.Base64
import com.firestream.chat.data.remote.source.KeySource
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes and fetches Signal Protocol public key material from Firebase.
 *
 * Key bundle layout:
 *   `/keyBundles/{uid}`
 *     registrationId      : Int
 *     identityKey         : base64(IdentityKey.serialize())
 *     signedPreKeyId      : Int
 *     signedPreKeyPublic  : base64(ECPublicKey.serialize())
 *     signedPreKeySig     : base64(byte[])
 *     kyberPreKeyId       : Int
 *     kyberPreKeyPublic   : base64(KEMPublicKey.serialize())
 *     kyberPreKeySig      : base64(byte[])
 *
 *   `/keyBundles/{uid}/oneTimePreKeys/{preKeyId}`
 *     preKeyId            : Int
 *     preKeyPublic        : base64(ECPublicKey.serialize())
 *     registrationId      : Int
 *     createdAt           : serverTimestamp
 */
@Singleton
class FirebaseKeySource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : KeySource {

    override suspend fun publishKeys(
        uid: String,
        identityKey: IdentityKey,
        registrationId: Int,
        signedPreKey: SignedPreKeyRecord,
        preKeys: List<PreKeyRecord>,
        kyberPreKey: KyberPreKeyRecord,
    ) {
        val bundleRef = firestore.collection(COLLECTION_KEY_BUNDLES).document(uid)
        val data = hashMapOf<String, Any>(
            "registrationId" to registrationId,
            "identityKey" to identityKey.serialize().toB64(),
            "signedPreKeyId" to signedPreKey.id,
            "signedPreKeyPublic" to signedPreKey.keyPair.publicKey.serialize().toB64(),
            "signedPreKeySig" to signedPreKey.signature.toB64(),
            "kyberPreKeyId" to kyberPreKey.id,
            "kyberPreKeyPublic" to kyberPreKey.keyPair.publicKey.serialize().toB64(),
            "kyberPreKeySig" to kyberPreKey.signature.toB64(),
        )
        withTimeout(WRITE_TIMEOUT_MS) {
            bundleRef.set(data).await()
        }

        // Clear any pre-existing oneTimePreKeys from a prior install (even if the
        // 14-bit registrationId happened to collide) before uploading the new batch.
        try {
            deleteAllRemotePreKeys(uid)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }

        if (preKeys.isNotEmpty()) {
            uploadPreKeys(uid, registrationId, preKeys)
        }
    }

    override suspend fun uploadPreKeys(
        uid: String,
        registrationId: Int,
        preKeys: List<PreKeyRecord>,
    ) {
        if (preKeys.isEmpty()) return
        val preKeysCollection = firestore.collection(COLLECTION_KEY_BUNDLES)
            .document(uid)
            .collection(SUBCOLLECTION_ONE_TIME_PRE_KEYS)

        preKeys.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { preKey ->
                val docRef = preKeysCollection.document(preKey.id.toString())
                batch.set(
                    docRef,
                    hashMapOf<String, Any>(
                        "preKeyId" to preKey.id,
                        "preKeyPublic" to preKey.keyPair.publicKey.serialize().toB64(),
                        "registrationId" to registrationId,
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            // Bounded so offline Firestore persistence never suspends callers indefinitely.
            withTimeout(WRITE_TIMEOUT_MS) {
                batch.commit().await()
            }
        }
    }

    override suspend fun countRemotePreKeys(uid: String, registrationId: Int): Int? {
        return withTimeout(CALL_TIMEOUT_SECONDS * 1000L) {
            firestore.collection(COLLECTION_KEY_BUNDLES)
                .document(uid)
                .collection(SUBCOLLECTION_ONE_TIME_PRE_KEYS)
                .whereEqualTo("registrationId", registrationId)
                .count()
                .get(AggregateSource.SERVER)
                .await()
                .count
                .toInt()
        }
    }

    override suspend fun fetchIdentityKey(uid: String): IdentityKey? {
        val doc = withTimeout(CALL_TIMEOUT_SECONDS * 1000L) {
            firestore.collection(COLLECTION_KEY_BUNDLES).document(uid).get().await()
        }
        if (!doc.exists()) return null
        val encoded = doc.getString("identityKey") ?: return null
        return runCatching { IdentityKey(encoded.fromB64()) }.getOrNull()
    }

    override suspend fun fetchPreKeyBundle(uid: String): PreKeyBundle? {
        val result = functions.getHttpsCallable(FUNCTION_CLAIM_PRE_KEY_BUNDLE)
            .withTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .call(mapOf("targetUid" to uid))
            .await()
        return parseClaimedBundle(result.getData())
    }

    private suspend fun deleteAllRemotePreKeys(uid: String) {
        val snapshot = withTimeout(WRITE_TIMEOUT_MS) {
            firestore.collection(COLLECTION_KEY_BUNDLES)
                .document(uid)
                .collection(SUBCOLLECTION_ONE_TIME_PRE_KEYS)
                .get(Source.SERVER)
                .await()
        }
        snapshot.documents.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            withTimeout(WRITE_TIMEOUT_MS) {
                batch.commit().await()
            }
        }
    }

    internal companion object {
        const val COLLECTION_KEY_BUNDLES = "keyBundles"
        const val SUBCOLLECTION_ONE_TIME_PRE_KEYS = "oneTimePreKeys"
        const val FUNCTION_CLAIM_PRE_KEY_BUNDLE = "claimPreKeyBundle"
        const val CALL_TIMEOUT_SECONDS = 15L
        private const val WRITE_TIMEOUT_MS = 15_000L
        private const val BATCH_LIMIT = 400

        fun parseClaimedBundle(data: Any?): PreKeyBundle? {
            val map = data as? Map<*, *> ?: return null
            if (map["found"] != true) return null
            return try {
                val registrationId = (map["registrationId"] as? Number)?.toInt() ?: return null
                val identityKey = IdentityKey((map["identityKey"] as? String)?.fromB64() ?: return null)
                val signedPreKeyId = (map["signedPreKeyId"] as? Number)?.toInt() ?: return null
                val signedPreKeyPublic = ECPublicKey((map["signedPreKeyPublic"] as? String)?.fromB64() ?: return null)
                val signedPreKeySig = (map["signedPreKeySig"] as? String)?.fromB64() ?: return null
                val kyberPreKeyId = (map["kyberPreKeyId"] as? Number)?.toInt() ?: return null
                val kyberPreKeyPublic = KEMPublicKey((map["kyberPreKeyPublic"] as? String)?.fromB64() ?: return null)
                val kyberPreKeySig = (map["kyberPreKeySig"] as? String)?.fromB64() ?: return null

                val rawPreKeyId = (map["preKeyId"] as? Number)?.toInt()
                val rawPreKeyPublic = (map["preKeyPublic"] as? String)?.fromB64()
                val hasOneTimePreKey = rawPreKeyId != null && rawPreKeyPublic != null
                val preKeyId = if (hasOneTimePreKey) rawPreKeyId!! else PreKeyBundle.NULL_PRE_KEY_ID
                val preKeyPublic = if (hasOneTimePreKey) ECPublicKey(rawPreKeyPublic!!) else null

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
                    kyberPreKeySig,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun ByteArray.toB64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
        private fun String.fromB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
    }
}
