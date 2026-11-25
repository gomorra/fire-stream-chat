const assert = require("node:assert/strict");
const { describe, test } = require("node:test");
const {
    parseTargetUid,
    buildClaimedBundleResponse,
    claimBundleInTransaction,
} = require("../preKeyBundle");

const BASE_BUNDLE = {
    registrationId: 42,
    identityKey: "id-b64",
    signedPreKeyId: 1,
    signedPreKeyPublic: "spk-b64",
    signedPreKeySig: "spk-sig-b64",
    kyberPreKeyId: 1,
    kyberPreKeyPublic: "kpk-b64",
    kyberPreKeySig: "kpk-sig-b64",
};

describe("parseTargetUid", () => {
    test("accepts a normal uid and trims surrounding whitespace", () => {
        assert.equal(parseTargetUid({ targetUid: "  user-123 " }), "user-123");
    });

    test("rejects missing, empty, slash-containing, and overlong uids", () => {
        assert.equal(parseTargetUid(null), null);
        assert.equal(parseTargetUid({}), null);
        assert.equal(parseTargetUid({ targetUid: "" }), null);
        assert.equal(parseTargetUid({ targetUid: "   " }), null);
        assert.equal(parseTargetUid({ targetUid: "a/b" }), null);
        assert.equal(parseTargetUid({ targetUid: "x".repeat(129) }), null);
        assert.equal(parseTargetUid({ targetUid: 123 }), null);
    });
});

describe("buildClaimedBundleResponse", () => {
    test("returns found:false when bundle is absent", () => {
        assert.deepEqual(buildClaimedBundleResponse(null, null), { found: false });
    });

    test("prefers popped subcollection pre-key over legacy top-level pre-key", () => {
        const legacyBundle = { ...BASE_BUNDLE, preKeyId: 1, preKeyPublic: "legacy-pk" };
        const popped = { preKeyId: 7, preKeyPublic: "fresh-pk", registrationId: 42 };
        const result = buildClaimedBundleResponse(legacyBundle, popped);
        assert.equal(result.found, true);
        assert.equal(result.preKeyId, 7);
        assert.equal(result.preKeyPublic, "fresh-pk");
    });

    test("falls back to legacy top-level pre-key when subcollection is empty", () => {
        const legacyBundle = { ...BASE_BUNDLE, preKeyId: 1, preKeyPublic: "legacy-pk" };
        const result = buildClaimedBundleResponse(legacyBundle, null);
        assert.equal(result.found, true);
        assert.equal(result.preKeyId, 1);
        assert.equal(result.preKeyPublic, "legacy-pk");
    });

    test("returns null preKeyId and preKeyPublic when pool is empty on v2 bundles", () => {
        const result = buildClaimedBundleResponse(BASE_BUNDLE, null);
        assert.equal(result.found, true);
        assert.equal(result.preKeyId, null);
        assert.equal(result.preKeyPublic, null);
    });
});

describe("claimBundleInTransaction", () => {
    function makeFakeDb(bundleData, preKeys, blockedCallers = new Set()) {
        const remaining = [...preKeys];
        const deletedIds = [];

        const bundleRef = {
            kind: "bundle",
            collection(name) {
                assert.equal(name, "oneTimePreKeys");
                return {
                    where(field, op, value) {
                        assert.equal(field, "registrationId");
                        assert.equal(op, "==");
                        return {
                            limit(n) {
                                return { kind: "preKeyQuery", registrationId: value, limit: n };
                            },
                        };
                    },
                };
            },
        };

        const db = {
            deletedIds,
            collection(name) {
                if (name === "users") {
                    return {
                        doc() {
                            return {
                                collection(sub) {
                                    assert.equal(sub, "blockedUsers");
                                    return {
                                        doc(callerUid) {
                                            return {
                                                async get() {
                                                    return { exists: blockedCallers.has(callerUid) };
                                                },
                                            };
                                        },
                                    };
                                },
                            };
                        },
                    };
                }
                assert.equal(name, "keyBundles");
                return {
                    doc() {
                        return bundleRef;
                    },
                };
            },
            async runTransaction(fn) {
                const tx = {
                    async get(refOrQuery) {
                        if (refOrQuery.kind === "bundle") {
                            return { exists: bundleData != null, data: () => bundleData };
                        }
                        const matching = remaining
                            .filter((pk) => pk.registrationId === refOrQuery.registrationId)
                            .slice(0, refOrQuery.limit)
                            .map((pk) => ({
                                ref: { id: pk.preKeyId },
                                data: () => pk,
                            }));
                        return { empty: matching.length === 0, docs: matching };
                    },
                    delete(ref) {
                        deletedIds.push(ref.id);
                        const idx = remaining.findIndex((pk) => pk.preKeyId === ref.id);
                        if (idx !== -1) remaining.splice(idx, 1);
                    },
                };
                return fn(tx);
            },
        };
        return db;
    }

    test("pops only pre-keys matching current registrationId and deletes them", async () => {
        const db = makeFakeDb(BASE_BUNDLE, [
            { preKeyId: 1, preKeyPublic: "stale-pk", registrationId: 999 },
            { preKeyId: 5, preKeyPublic: "good-pk-5", registrationId: 42 },
            { preKeyId: 6, preKeyPublic: "good-pk-6", registrationId: 42 },
        ]);

        const first = await claimBundleInTransaction(db, "bob");
        assert.equal(first.preKeyId, 5);
        assert.equal(first.preKeyPublic, "good-pk-5");

        const second = await claimBundleInTransaction(db, "bob");
        assert.equal(second.preKeyId, 6);
        assert.equal(second.preKeyPublic, "good-pk-6");

        // Pool now empty for registrationId 42: returns PQXDH-only bundle (stale 999 ignored).
        const third = await claimBundleInTransaction(db, "bob");
        assert.equal(third.found, true);
        assert.equal(third.preKeyId, null);
        assert.equal(third.preKeyPublic, null);
        assert.deepEqual(db.deletedIds, [5, 6]);
    });

    test("returns found:false when target user has no bundle", async () => {
        const db = makeFakeDb(null, []);
        assert.deepEqual(await claimBundleInTransaction(db, "missing"), { found: false });
    });

    test("rejects blocked caller without popping any one-time pre-keys", async () => {
        const db = makeFakeDb(
            BASE_BUNDLE,
            [{ preKeyId: 5, preKeyPublic: "good-pk-5", registrationId: 42 }],
            new Set(["mallory"]),
        );
        const result = await claimBundleInTransaction(db, "bob", "mallory");
        assert.deepEqual(result, { found: false, blocked: true });
        assert.deepEqual(db.deletedIds, []);
    });
});
