// Checks the calls rules in firestore.rules against the Firestore emulator.
// `npm test` starts the emulator and runs this file with `node --test`.
//
// The writes under "the app" are the ones FirestoreCallSource.kt makes, in
// every state they can reach, including the orders older app versions used.
// They must keep passing. The writes under "the rules refuse" must fail.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  runTransaction,
  setDoc,
  setLogLevel,
  updateDoc,
} from 'firebase/firestore';

const CALLER = 'caller-uid';
const CALLEE = 'callee-uid';
const STRANGER = 'stranger-uid';
const CALL_ID = 'call-1';

// Writing a field's current value changes nothing, so the rules allow it. A
// test that expects a refusal must write a new value, such as OTHER_OFFER.
const OFFER = { sdp: 'v=0 offer', type: 'offer' };
const OTHER_OFFER = { sdp: 'v=0 other offer', type: 'offer' };
const ANSWER = { sdp: 'v=0 answer', type: 'answer' };
const CANDIDATE = {
  sdpMid: '0',
  sdpMLineIndex: 0,
  sdp: 'candidate:1 1 udp 2122260223 192.0.2.10 49152 typ host',
};
// The app writes an end reason as its EndReason name in lower case.
const END_REASONS = ['hangup', 'remote_hangup', 'timeout', 'error'];
const LISTS = ['callerCandidates', 'calleeCandidates'];

let env;
let asCaller;
let asCallee;
let asStranger;
let signedOut;

before(async () => {
  // Every refused write logs a warning, and most tests here expect one.
  setLogLevel('error');
  env = await initializeTestEnvironment({
    projectId: process.env.GCLOUD_PROJECT ?? 'demo-firestream',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
  asCaller = env.authenticatedContext(CALLER).firestore();
  asCallee = env.authenticatedContext(CALLEE).firestore();
  asStranger = env.authenticatedContext(STRANGER).firestore();
  signedOut = env.unauthenticatedContext().firestore();
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

function callDoc(db) {
  return doc(db, 'calls', CALL_ID);
}

function candidateList(db, list) {
  return collection(db, 'calls', CALL_ID, list);
}

/** The call document exactly as FirestoreCallSource.createCallDocument writes it. */
function newCall(fields = {}) {
  return {
    callerId: CALLER,
    calleeId: CALLEE,
    status: 'ringing',
    createdAt: Date.now(),
    endedAt: null,
    endReason: null,
    offer: null,
    answer: null,
    ...fields,
  };
}

function ended(reason) {
  return { status: 'ended', endReason: reason, endedAt: Date.now() };
}

function declined() {
  return { status: 'declined', endReason: 'declined', endedAt: Date.now() };
}

/** The call document in each status the app can find it in. */
const STATES = {
  ringing: () => ({ offer: OFFER }),
  answered: () => ({ status: 'answered', offer: OFFER, answer: ANSWER }),
  declined: () => ({ offer: OFFER, ...declined() }),
  ended: () => ({ offer: OFFER, ...ended('hangup') }),
};

/** Stores the call with the rules off, so a test can start from any state. */
async function seedCall(fields = {}) {
  await env.withSecurityRulesDisabled((context) =>
    setDoc(doc(context.firestore(), 'calls', CALL_ID), newCall(fields)),
  );
}

/** Stores one candidate with the rules off and returns its id. */
async function seedCandidate(list) {
  let id;
  await env.withSecurityRulesDisabled(async (context) => {
    const ref = await addDoc(collection(context.firestore(), 'calls', CALL_ID, list), CANDIDATE);
    id = ref.id;
  });
  return id;
}

/** Resolves with the first snapshot from the server, as the app's snapshot listeners get it. */
function listen(target) {
  return new Promise((resolve, reject) => {
    const unsubscribe = onSnapshot(
      target,
      { includeMetadataChanges: true },
      (snapshot) => {
        if (snapshot.metadata.fromCache) return;
        unsubscribe();
        resolve(snapshot);
      },
      reject,
    );
  });
}

describe('the app', () => {
  test('the caller creates a ringing call with the fields the app writes', async () => {
    await assertSucceeds(setDoc(callDoc(asCaller), newCall()));
  });

  test('the caller creates the call in a transaction', async () => {
    await assertSucceeds(
      runTransaction(asCaller, async (transaction) => {
        transaction.set(callDoc(asCaller), newCall());
      }),
    );
  });

  test('the caller writes the offer while the call rings', async () => {
    await seedCall();
    await assertSucceeds(updateDoc(callDoc(asCaller), { offer: OFFER }));
  });

  test('the callee answers with the answer and "answered" in one write', async () => {
    await seedCall(STATES.ringing());
    await assertSucceeds(updateDoc(callDoc(asCallee), { answer: ANSWER, status: 'answered' }));
  });

  test('the callee answers with the status alone', async () => {
    await seedCall(STATES.ringing());
    await assertSucceeds(updateDoc(callDoc(asCallee), { status: 'answered' }));
  });

  test('an older callee writes the answer first, then "answered"', async () => {
    await seedCall(STATES.ringing());
    await assertSucceeds(updateDoc(callDoc(asCallee), { answer: ANSWER }));
    await assertSucceeds(updateDoc(callDoc(asCallee), { status: 'answered' }));
  });

  test('an older callee writes "answered" first, then the answer', async () => {
    await seedCall(STATES.ringing());
    await assertSucceeds(updateDoc(callDoc(asCallee), { status: 'answered' }));
    await assertSucceeds(updateDoc(callDoc(asCallee), { answer: ANSWER }));
  });

  test('the callee declines a ringing call', async () => {
    await seedCall(STATES.ringing());
    await assertSucceeds(updateDoc(callDoc(asCallee), declined()));
  });

  for (const [side, db] of [['caller', () => asCaller], ['callee', () => asCallee]]) {
    for (const [status, state] of Object.entries(STATES)) {
      test(`the ${side} ends a call that is ${status}, with each end reason`, async () => {
        for (const reason of END_REASONS) {
          await seedCall(state());
          await assertSucceeds(updateDoc(callDoc(db()), ended(reason)));
        }
      });
    }
  }

  test('both sides end the call, one after the other', async () => {
    await seedCall(STATES.answered());
    await assertSucceeds(updateDoc(callDoc(asCaller), ended('hangup')));
    await assertSucceeds(updateDoc(callDoc(asCallee), ended('hangup')));
  });

  test('the caller and the callee read the call and listen to it', async () => {
    await seedCall(STATES.ringing());
    for (const db of [asCaller, asCallee]) {
      await assertSucceeds(getDoc(callDoc(db)));
      await assertSucceeds(listen(callDoc(db)));
    }
  });

  test('the caller adds candidates to callerCandidates', async () => {
    await seedCall(STATES.ringing());
    await assertSucceeds(addDoc(candidateList(asCaller, 'callerCandidates'), CANDIDATE));
    await assertSucceeds(addDoc(candidateList(asCaller, 'callerCandidates'), CANDIDATE));
  });

  test('the callee adds candidates to calleeCandidates', async () => {
    await seedCall(STATES.answered());
    await assertSucceeds(addDoc(candidateList(asCallee, 'calleeCandidates'), CANDIDATE));
  });

  test('a late candidate still lands after the call ended', async () => {
    await seedCall(STATES.ended());
    await assertSucceeds(addDoc(candidateList(asCaller, 'callerCandidates'), CANDIDATE));
    await assertSucceeds(addDoc(candidateList(asCallee, 'calleeCandidates'), CANDIDATE));
  });

  test('the caller and the callee read and listen to both candidate lists', async () => {
    await seedCall(STATES.answered());
    for (const list of LISTS) {
      await seedCandidate(list);
      for (const db of [asCaller, asCallee]) {
        const read = await assertSucceeds(getDocs(candidateList(db, list)));
        assert.equal(read.size, 1);
        const heard = await assertSucceeds(listen(candidateList(db, list)));
        assert.equal(heard.size, 1);
      }
    }
  });

  test('a whole call, from ringing to both hanging up', async () => {
    await assertSucceeds(setDoc(callDoc(asCaller), newCall()));
    await assertSucceeds(updateDoc(callDoc(asCaller), { offer: OFFER }));
    await assertSucceeds(addDoc(candidateList(asCaller, 'callerCandidates'), CANDIDATE));

    const ringing = await assertSucceeds(getDoc(callDoc(asCallee)));
    assert.deepEqual(ringing.get('offer'), OFFER);
    await assertSucceeds(updateDoc(callDoc(asCallee), { answer: ANSWER, status: 'answered' }));
    await assertSucceeds(addDoc(candidateList(asCallee, 'calleeCandidates'), CANDIDATE));

    const callerHears = await assertSucceeds(listen(candidateList(asCaller, 'calleeCandidates')));
    assert.equal(callerHears.size, 1);
    const calleeHears = await assertSucceeds(listen(candidateList(asCallee, 'callerCandidates')));
    assert.equal(calleeHears.size, 1);

    await assertSucceeds(updateDoc(callDoc(asCaller), ended('hangup')));
    await assertSucceeds(updateDoc(callDoc(asCallee), ended('hangup')));
    const last = await assertSucceeds(getDoc(callDoc(asCaller)));
    assert.equal(last.get('status'), 'ended');
  });
});

describe('the rules refuse', () => {
  test('a third user or a signed-out user reading the call', async () => {
    await seedCall(STATES.ringing());
    for (const db of [asStranger, signedOut]) {
      await assertFails(getDoc(callDoc(db)));
      await assertFails(listen(callDoc(db)));
    }
  });

  test('a third user writing the call', async () => {
    await seedCall(STATES.ringing());
    await assertFails(updateDoc(callDoc(asStranger), ended('hangup')));
    await assertFails(updateDoc(callDoc(asStranger), { answer: ANSWER, status: 'answered' }));
    await assertFails(updateDoc(callDoc(asStranger), { offer: OFFER }));
  });

  test('a third user or a signed-out user reading a candidate list or one candidate', async () => {
    await seedCall(STATES.answered());
    for (const list of LISTS) {
      const id = await seedCandidate(list);
      for (const db of [asStranger, signedOut]) {
        await assertFails(getDocs(candidateList(db, list)));
        await assertFails(listen(candidateList(db, list)));
        await assertFails(getDoc(doc(candidateList(db, list), id)));
      }
    }
  });

  test('a third user adding a candidate to either list', async () => {
    await seedCall(STATES.answered());
    for (const list of LISTS) {
      await assertFails(addDoc(candidateList(asStranger, list), CANDIDATE));
    }
  });

  test('the caller adding to calleeCandidates', async () => {
    await seedCall(STATES.answered());
    await assertFails(addDoc(candidateList(asCaller, 'calleeCandidates'), CANDIDATE));
  });

  test('the callee adding to callerCandidates', async () => {
    await seedCall(STATES.answered());
    await assertFails(addDoc(candidateList(asCallee, 'callerCandidates'), CANDIDATE));
  });

  test('a candidate for a call that does not exist', async () => {
    await assertFails(addDoc(candidateList(asCaller, 'callerCandidates'), CANDIDATE));
  });

  test('anyone changing or deleting a candidate', async () => {
    await seedCall(STATES.answered());
    for (const list of LISTS) {
      const id = await seedCandidate(list);
      for (const db of [asCaller, asCallee, asStranger]) {
        const candidate = doc(candidateList(db, list), id);
        await assertFails(updateDoc(candidate, { sdp: 'candidate:2' }));
        await assertFails(setDoc(candidate, CANDIDATE));
        await assertFails(deleteDoc(candidate));
      }
    }
  });

  test('a candidate of the wrong shape', async () => {
    await seedCall(STATES.ringing());
    const { sdp, ...withoutSdp } = CANDIDATE;
    const wrongShapes = [
      { ...CANDIDATE, ip: '192.0.2.10' },
      withoutSdp,
      { ...CANDIDATE, sdpMLineIndex: '0' },
      { ...CANDIDATE, sdpMLineIndex: 0.5 },
      { ...CANDIDATE, sdpMid: 0 },
      { ...CANDIDATE, sdpMid: null },
      { ...CANDIDATE, sdp: { candidate: sdp } },
    ];
    for (const candidate of wrongShapes) {
      await assertFails(addDoc(candidateList(asCaller, 'callerCandidates'), candidate));
    }
  });

  for (const [key, value] of [['callerId', STRANGER], ['calleeId', STRANGER], ['createdAt', 1]]) {
    test(`either side rewriting ${key}`, async () => {
      for (const db of [asCaller, asCallee]) {
        await seedCall(STATES.ringing());
        await assertFails(updateDoc(callDoc(db), { [key]: value }));
        await assertFails(updateDoc(callDoc(db), { [key]: value, ...ended('hangup') }));
      }
    });
  }

  test('either side swapping callerId and calleeId', async () => {
    await seedCall(STATES.ringing());
    for (const db of [asCaller, asCallee]) {
      await assertFails(updateDoc(callDoc(db), { callerId: CALLEE, calleeId: CALLER }));
    }
  });

  test('either side adding a field the app does not write', async () => {
    await seedCall(STATES.answered());
    for (const db of [asCaller, asCallee]) {
      await assertFails(updateDoc(callDoc(db), { extra: true }));
    }
  });

  test('the callee writing the offer', async () => {
    await seedCall();
    await assertFails(updateDoc(callDoc(asCallee), { offer: OFFER }));
    await seedCall(STATES.ringing());
    await assertFails(updateDoc(callDoc(asCallee), { offer: OTHER_OFFER }));
    await assertFails(updateDoc(callDoc(asCallee), { offer: OTHER_OFFER, answer: ANSWER, status: 'answered' }));
  });

  test('the caller writing the answer', async () => {
    await seedCall(STATES.ringing());
    await assertFails(updateDoc(callDoc(asCaller), { answer: ANSWER }));
    await assertFails(updateDoc(callDoc(asCaller), { answer: ANSWER, status: 'answered' }));
  });

  test('the caller answering or declining', async () => {
    await seedCall(STATES.ringing());
    await assertFails(updateDoc(callDoc(asCaller), { status: 'answered' }));
    await assertFails(updateDoc(callDoc(asCaller), declined()));
  });

  test('the caller writing to an answered call other than to end it', async () => {
    await seedCall(STATES.answered());
    await assertFails(updateDoc(callDoc(asCaller), { offer: OTHER_OFFER }));
  });

  test('an answered call going back to ringing', async () => {
    await seedCall(STATES.answered());
    for (const db of [asCaller, asCallee]) {
      await assertFails(updateDoc(callDoc(db), { status: 'ringing' }));
    }
  });

  test('an answered call being declined', async () => {
    await seedCall(STATES.answered());
    await assertFails(updateDoc(callDoc(asCallee), declined()));
  });

  test('a declined call being answered or ringing again', async () => {
    await seedCall(STATES.declined());
    await assertFails(updateDoc(callDoc(asCallee), { status: 'answered' }));
    await assertFails(updateDoc(callDoc(asCallee), { answer: ANSWER, status: 'answered' }));
    for (const db of [asCaller, asCallee]) {
      await assertFails(updateDoc(callDoc(db), { status: 'ringing' }));
    }
  });

  test('an ended call being answered, ringing again or declined', async () => {
    await seedCall(STATES.ended());
    await assertFails(updateDoc(callDoc(asCallee), { status: 'answered' }));
    await assertFails(updateDoc(callDoc(asCallee), { answer: ANSWER, status: 'answered' }));
    await assertFails(updateDoc(callDoc(asCallee), declined()));
    for (const db of [asCaller, asCallee]) {
      await assertFails(updateDoc(callDoc(db), { status: 'ringing' }));
    }
  });

  test('a status other than ringing, answered, declined or ended', async () => {
    for (const status of ['connected', 'missed', 'Ended', '', null, 1]) {
      for (const db of [asCaller, asCallee]) {
        await seedCall(STATES.ringing());
        await assertFails(updateDoc(callDoc(db), { status }));
        await assertFails(updateDoc(callDoc(db), { status, endReason: 'hangup', endedAt: Date.now() }));
      }
    }
  });

  test('the end fields written without ending the call', async () => {
    await seedCall(STATES.ringing());
    await assertFails(updateDoc(callDoc(asCaller), { endReason: 'hangup', endedAt: Date.now() }));
    await seedCall(STATES.answered());
    await assertFails(updateDoc(callDoc(asCallee), { endedAt: Date.now() }));
  });

  test('the offer or the answer written with the end of the call', async () => {
    await seedCall();
    await assertFails(updateDoc(callDoc(asCaller), { offer: OFFER, ...ended('hangup') }));
    await assertFails(updateDoc(callDoc(asCallee), { answer: ANSWER, ...declined() }));
  });

  test('anyone deleting the call', async () => {
    await seedCall(STATES.ended());
    for (const db of [asCaller, asCallee, asStranger]) {
      await assertFails(deleteDoc(callDoc(db)));
    }
  });

  test('a create by anyone but the caller it names', async () => {
    await assertFails(setDoc(callDoc(asStranger), newCall()));
    await assertFails(setDoc(callDoc(asCallee), newCall()));
    await assertFails(setDoc(callDoc(signedOut), newCall()));
  });

  test('a create that is not ringing', async () => {
    for (const status of ['answered', 'declined', 'ended', 'connected']) {
      await assertFails(setDoc(callDoc(asCaller), newCall({ status })));
    }
  });

  test('a create where the caller calls itself', async () => {
    await assertFails(setDoc(callDoc(asCaller), newCall({ calleeId: CALLER })));
  });

  test('a create whose calleeId is missing or not a string', async () => {
    const { calleeId, ...withoutCallee } = newCall();
    await assertFails(setDoc(callDoc(asCaller), withoutCallee));
    for (const value of [null, 42, [calleeId]]) {
      await assertFails(setDoc(callDoc(asCaller), newCall({ calleeId: value })));
    }
  });

  test('a create with a field the app does not write', async () => {
    await assertFails(setDoc(callDoc(asCaller), newCall({ extra: true })));
  });

  test('a create that already holds an answer', async () => {
    await assertFails(setDoc(callDoc(asCaller), newCall({ answer: ANSWER })));
  });
});
