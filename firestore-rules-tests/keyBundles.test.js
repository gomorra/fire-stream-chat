// Checks the keyBundles and oneTimePreKeys rules in firestore.rules against the Firestore emulator.
// `npm test` starts the emulator and runs this file with `node --test`.

import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  serverTimestamp,
  setDoc,
  setLogLevel,
} from 'firebase/firestore';

const OWNER = 'owner-uid';
const OTHER = 'other-uid';

let env;
let asOwner;
let asOther;
let signedOut;

before(async () => {
  setLogLevel('error');
  env = await initializeTestEnvironment({
    projectId: 'demo-firestream-key-bundles',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
  asOwner = env.authenticatedContext(OWNER).firestore();
  asOther = env.authenticatedContext(OTHER).firestore();
  signedOut = env.unauthenticatedContext().firestore();
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

function bundleDoc(db, uid = OWNER) {
  return doc(db, 'keyBundles', uid);
}

function preKeyDoc(db, uid = OWNER, preKeyId = '1') {
  return doc(db, 'keyBundles', uid, 'oneTimePreKeys', preKeyId);
}

function validBundle(overrides = {}) {
  return {
    registrationId: 42,
    identityKey: 'aWQ=',
    signedPreKeyId: 1,
    signedPreKeyPublic: 'c3Br',
    signedPreKeySig: 'c2ln',
    kyberPreKeyId: 1,
    kyberPreKeyPublic: 'a'.repeat(2092),
    kyberPreKeySig: 'a3NpZw==',
    ...overrides,
  };
}

function validPreKey(preKeyId = 1, overrides = {}) {
  return {
    preKeyId,
    preKeyPublic: 'b3Rwaw==',
    registrationId: 42,
    createdAt: serverTimestamp(),
    ...overrides,
  };
}

describe('keyBundles/{uid}', () => {
  test('owner can create, update, get, and delete own bundle', async () => {
    await assertSucceeds(setDoc(bundleDoc(asOwner), validBundle()));
    await assertSucceeds(setDoc(bundleDoc(asOwner), validBundle({ registrationId: 43 })));
    await assertSucceeds(getDoc(bundleDoc(asOwner)));
    await assertSucceeds(deleteDoc(bundleDoc(asOwner)));
  });

  test('legacy preKeyId and preKeyPublic fields remain accepted', async () => {
    await assertSucceeds(
      setDoc(bundleDoc(asOwner), validBundle({ preKeyId: 1, preKeyPublic: 'bGVnYWN5' })),
    );
  });

  test('other authenticated user can get a bundle by uid, but cannot list or write it', async () => {
    await assertSucceeds(setDoc(bundleDoc(asOwner), validBundle()));
    await assertSucceeds(getDoc(bundleDoc(asOther)));
    await assertFails(getDocs(collection(asOther, 'keyBundles')));
    await assertFails(setDoc(bundleDoc(asOther), validBundle()));
    await assertFails(deleteDoc(bundleDoc(asOther)));
  });

  test('unauthenticated client cannot read or write bundles', async () => {
    await assertSucceeds(setDoc(bundleDoc(asOwner), validBundle()));
    await assertFails(getDoc(bundleDoc(signedOut)));
    await assertFails(setDoc(bundleDoc(signedOut), validBundle()));
  });

  test('rejects extra fields or oversized strings on bundle write', async () => {
    await assertFails(setDoc(bundleDoc(asOwner), validBundle({ extra: 'nope' })));
    await assertFails(setDoc(bundleDoc(asOwner), validBundle({ identityKey: 'x'.repeat(257) })));
  });
});

describe('keyBundles/{uid}/oneTimePreKeys/{preKeyId}', () => {
  test('owner can create, list, get, and delete own one-time pre-keys', async () => {
    await assertSucceeds(setDoc(preKeyDoc(asOwner, OWNER, '7'), validPreKey(7)));
    await assertSucceeds(getDoc(preKeyDoc(asOwner, OWNER, '7')));
    await assertSucceeds(getDocs(collection(asOwner, 'keyBundles', OWNER, 'oneTimePreKeys')));
    await assertSucceeds(deleteDoc(preKeyDoc(asOwner, OWNER, '7')));
  });

  test('other authenticated user cannot get, list, write, or delete another user pre-keys', async () => {
    await assertSucceeds(setDoc(preKeyDoc(asOwner, OWNER, '7'), validPreKey(7)));
    await assertFails(getDoc(preKeyDoc(asOther, OWNER, '7')));
    await assertFails(getDocs(collection(asOther, 'keyBundles', OWNER, 'oneTimePreKeys')));
    await assertFails(setDoc(preKeyDoc(asOther, OWNER, '8'), validPreKey(8)));
    await assertFails(deleteDoc(preKeyDoc(asOther, OWNER, '7')));
  });

  test('rejects mismatched doc id, leading zeros, forged timestamp, or extra fields', async () => {
    await assertFails(setDoc(preKeyDoc(asOwner, OWNER, '7'), validPreKey(8)));
    await assertFails(setDoc(preKeyDoc(asOwner, OWNER, '07'), validPreKey(7)));
    await assertFails(setDoc(preKeyDoc(asOwner, OWNER, 'abc'), validPreKey(1)));
    await assertFails(setDoc(preKeyDoc(asOwner, OWNER, '7'), validPreKey(7, { createdAt: new Date() })));
    await assertFails(setDoc(preKeyDoc(asOwner, OWNER, '7'), validPreKey(7, { rogue: true })));
  });
});
