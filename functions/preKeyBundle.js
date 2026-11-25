// Pure helper for the `claimPreKeyBundle` callable Cloud Function.
// Separated from index.js so `npm test` can exercise every branch without
// bootstrapping firebase-admin.

/**
 * Validates the targetUid parameter from a callable request.
 * Returns the trimmed UID or null if invalid.
 */
function parseTargetUid(data) {
    if (!data || typeof data.targetUid !== "string") return null;
    const uid = data.targetUid.trim();
    if (!uid || uid.includes("/") || uid.length > 128) return null;
    return uid;
}

/**
 * Builds the response object returned by `claimPreKeyBundle`.
 *
 * When a one-time pre-key document was popped from the subcollection, it takes
 * precedence. When the pool is temporarily empty, falls back to legacy top-level
 * `preKeyId`/`preKeyPublic` fields (for peers still running an older build), or
 * returns `null` so the sender establishes a PQXDH session with signed + Kyber
 * pre-keys alone.
 */
function buildClaimedBundleResponse(bundleData, poppedPreKeyData) {
    if (!bundleData) return { found: false };

    let preKeyId = null;
    let preKeyPublic = null;
    if (poppedPreKeyData && typeof poppedPreKeyData.preKeyId === "number" && typeof poppedPreKeyData.preKeyPublic === "string") {
        preKeyId = poppedPreKeyData.preKeyId;
        preKeyPublic = poppedPreKeyData.preKeyPublic;
    } else if (typeof bundleData.preKeyId === "number" && typeof bundleData.preKeyPublic === "string") {
        preKeyId = bundleData.preKeyId;
        preKeyPublic = bundleData.preKeyPublic;
    }

    return {
        found: true,
        registrationId: bundleData.registrationId,
        identityKey: bundleData.identityKey,
        signedPreKeyId: bundleData.signedPreKeyId,
        signedPreKeyPublic: bundleData.signedPreKeyPublic,
        signedPreKeySig: bundleData.signedPreKeySig,
        kyberPreKeyId: bundleData.kyberPreKeyId,
        kyberPreKeyPublic: bundleData.kyberPreKeyPublic,
        kyberPreKeySig: bundleData.kyberPreKeySig,
        preKeyId,
        preKeyPublic,
    };
}

/**
 * Atomically reads `/keyBundles/{targetUid}` and pops one matching one-time
 * pre-key from `/keyBundles/{targetUid}/oneTimePreKeys` inside a Firestore
 * transaction. Rejects callers who appear on the target's `blockedUsers` list
 * so a blocked user cannot drain the target's one-time pre-key pool.
 */
async function claimBundleInTransaction(db, targetUid, callerUid = null) {
    if (callerUid && callerUid !== targetUid) {
        const blockSnap = await db
            .collection("users")
            .doc(targetUid)
            .collection("blockedUsers")
            .doc(callerUid)
            .get();
        if (blockSnap.exists) {
            return { found: false, blocked: true };
        }
    }

    const bundleRef = db.collection("keyBundles").doc(targetUid);
    const preKeysRef = bundleRef.collection("oneTimePreKeys");

    return db.runTransaction(async (tx) => {
        const bundleSnap = await tx.get(bundleRef);
        if (!bundleSnap.exists) return { found: false };
        const bundleData = bundleSnap.data();
        if (!bundleData || typeof bundleData.registrationId !== "number") {
            return { found: false };
        }

        // Single-field equality filter (no composite index required) guarantees
        // stale pre-keys left by a prior registration under the same UID are
        // never handed out.
        const preKeyQuery = preKeysRef
            .where("registrationId", "==", bundleData.registrationId)
            .limit(1);
        const preKeySnap = await tx.get(preKeyQuery);

        let poppedPreKeyData = null;
        if (!preKeySnap.empty) {
            const preKeyDoc = preKeySnap.docs[0];
            poppedPreKeyData = preKeyDoc.data();
            tx.delete(preKeyDoc.ref);
        }

        return buildClaimedBundleResponse(bundleData, poppedPreKeyData);
    });
}

module.exports = {
    parseTargetUid,
    buildClaimedBundleResponse,
    claimBundleInTransaction,
};
