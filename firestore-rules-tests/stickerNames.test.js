// Checks the stickerNames rules in firestore.rules against the Firestore emulator.
// `npm test` starts the emulator and runs this file with `node --test`.
//
// The writes under "the app" are the ones FirestoreStickerNameSource.kt makes.
// They must keep passing. Everything under "the rules refuse" must fail.

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
  query,
  runTransaction,
  serverTimestamp,
  setDoc,
  setLogLevel,
  updateDoc,
  where,
} from 'firebase/firestore';

const USER = 'user-uid';
const OTHER = 'other-uid';
// A sticker id is the SHA-256 of the sticker's bytes, in lower-case hex.
const STICKER_ID = 'a'.repeat(64);

let env;
let asUser;
let asOther;
let signedOut;

before(async () => {
  // Every refused write logs a warning, and most tests here expect one.
  setLogLevel('error');
  env = await initializeTestEnvironment({
    // A project of its own. `node --test` runs the test files side by side
    // against one emulator, and each clears its project before every test.
    projectId: 'demo-firestream-sticker-names',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
  asUser = env.authenticatedContext(USER).firestore();
  asOther = env.authenticatedContext(OTHER).firestore();
  signedOut = env.unauthenticatedContext().firestore();
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

function nameDoc(db, id = STICKER_ID) {
  return doc(db, 'stickerNames', id);
}

/** A request exactly as FirestoreStickerNameSource.request writes it. */
function newRequest(fields = {}) {
  return { status: 'pending', format: 'WEBP', requestedAt: serverTimestamp(), ...fields };
}

/** What the naming service writes with the Admin SDK, which the rules do not bind. */
async function answered(fields) {
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(nameDoc(context.firestore()), { format: 'WEBP', requestedAt: new Date(), ...fields });
  });
}

const ANSWER = {
  status: 'done',
  nameEn: 'Sleepy bear',
  nameDe: 'Müder Bär',
  words: ['tired', 'Schlaf'],
  text: '',
  emojis: ['😴'],
  model: 'gemma4:26b',
  namedAt: new Date(),
};

describe('the app', () => {
  test('asks for a name by creating a request', async () => {
    await assertSucceeds(setDoc(nameDoc(asUser), newRequest()));
  });

  test('asks inside a transaction that writes only when there is no document', async () => {
    await assertSucceeds(
      runTransaction(asUser, async (tx) => {
        const ref = nameDoc(asUser);
        if (!(await tx.get(ref)).exists()) tx.set(ref, newRequest());
      }),
    );
    // A second phone finds the request and writes nothing.
    await assertSucceeds(
      runTransaction(asOther, async (tx) => {
        const ref = nameDoc(asOther);
        if (!(await tx.get(ref)).exists()) tx.set(ref, newRequest());
      }),
    );
  });

  test('gets a document that is not there', async () => {
    await assertSucceeds(getDoc(nameDoc(asUser)));
  });

  test('gets a request someone else made, and its answer', async () => {
    await setDoc(nameDoc(asUser), newRequest());
    await assertSucceeds(getDoc(nameDoc(asOther)));
    await answered(ANSWER);
    await assertSucceeds(getDoc(nameDoc(asOther)));
  });
});

describe('the rules refuse', () => {
  test('a get without a sign-in', async () => {
    await assertFails(getDoc(nameDoc(signedOut)));
  });

  test('a list of the collection', async () => {
    await setDoc(nameDoc(asUser), newRequest());
    await assertFails(getDocs(collection(asUser, 'stickerNames')));
  });

  test('a query, whatever it filters by', async () => {
    await setDoc(nameDoc(asUser), newRequest());
    await assertFails(getDocs(query(collection(asUser, 'stickerNames'), where('status', '==', 'pending'))));
  });

  test('a request without a sign-in', async () => {
    await assertFails(setDoc(nameDoc(signedOut), newRequest()));
  });

  for (const [what, id] of [
    ['too short', 'a'.repeat(63)],
    ['too long', 'a'.repeat(65)],
    ['in upper case', 'A'.repeat(64)],
    ['not hex', 'g'.repeat(64)],
    ['a word', 'my-sticker'],
  ]) {
    test(`a request whose id is ${what}`, async () => {
      await assertFails(setDoc(nameDoc(asUser, id), newRequest()));
    });
  }

  test('a request with a status other than pending', async () => {
    await assertFails(setDoc(nameDoc(asUser), newRequest({ status: 'done' })));
    await assertFails(setDoc(nameDoc(asUser), newRequest({ status: 'skipped' })));
  });

  test('a request that carries an answer', async () => {
    await assertFails(setDoc(nameDoc(asUser), newRequest({ nameEn: 'Sleepy bear' })));
    await assertFails(setDoc(nameDoc(asUser), newRequest({ words: ['tired'] })));
  });

  test('a request that carries who asked', async () => {
    await assertFails(setDoc(nameDoc(asUser), newRequest({ requestedBy: USER })));
  });

  for (const field of ['status', 'format', 'requestedAt']) {
    test(`a request without ${field}`, async () => {
      const request = newRequest();
      delete request[field];
      await assertFails(setDoc(nameDoc(asUser), request));
    });
  }

  test('a request with a time of the phone', async () => {
    await assertFails(setDoc(nameDoc(asUser), newRequest({ requestedAt: new Date() })));
    await assertFails(setDoc(nameDoc(asUser), newRequest({ requestedAt: Date.now() })));
  });

  test('a request whose format is no short string', async () => {
    await assertFails(setDoc(nameDoc(asUser), newRequest({ format: 7 })));
    await assertFails(setDoc(nameDoc(asUser), newRequest({ format: 'x'.repeat(17) })));
  });

  test('a second request over the first', async () => {
    await setDoc(nameDoc(asUser), newRequest());
    await assertFails(setDoc(nameDoc(asUser), newRequest()));
    await assertFails(setDoc(nameDoc(asOther), newRequest()));
  });

  test('a request over an answer, which would ask again', async () => {
    await answered(ANSWER);
    await assertFails(setDoc(nameDoc(asUser), newRequest()));
  });

  test('an answer written by a client', async () => {
    await setDoc(nameDoc(asUser), newRequest());
    await assertFails(updateDoc(nameDoc(asUser), ANSWER));
    await assertFails(updateDoc(nameDoc(asUser), { status: 'skipped', reason: 'no' }));
    await assertFails(setDoc(nameDoc(asUser), ANSWER, { merge: true }));
  });

  test('a change to an answer', async () => {
    await answered(ANSWER);
    await assertFails(updateDoc(nameDoc(asUser), { nameEn: 'Something else' }));
  });

  test('a delete, of a request and of an answer', async () => {
    await setDoc(nameDoc(asUser), newRequest());
    await assertFails(deleteDoc(nameDoc(asUser)));
    await answered(ANSWER);
    await assertFails(deleteDoc(nameDoc(asUser)));
  });
});
