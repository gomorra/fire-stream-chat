// Checks storage.rules against the Firebase Storage + Firestore emulators.
// `npm test` starts both emulators and runs this file with `node --test`.

import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, test } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, setDoc, setLogLevel } from 'firebase/firestore';
import {
  deleteObject,
  getBytes,
  listAll,
  ref,
  uploadBytes,
  uploadString,
} from 'firebase/storage';

const OWNER = 'owner-uid';
const MEMBER = 'member-uid';
const OUTSIDER = 'outsider-uid';
const CHAT_ID = 'chat-1';
const KEY_GEN = '0123456789abcdef';
const SHA256 = 'a'.repeat(64);
const SMALL_BYTES = new Uint8Array([1, 2, 3, 4]);

let env;
let ownerStorage;
let memberStorage;
let outsiderStorage;
let signedOutStorage;

before(async () => {
  setLogLevel('error');
  env = await initializeTestEnvironment({
    projectId: 'demo-firestream',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
    storage: {
      rules: readFileSync(new URL('../storage.rules', import.meta.url), 'utf8'),
    },
  });
  ownerStorage = env.authenticatedContext(OWNER).storage();
  memberStorage = env.authenticatedContext(MEMBER).storage();
  outsiderStorage = env.authenticatedContext(OUTSIDER).storage();
  signedOutStorage = env.unauthenticatedContext().storage();
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await env.withSecurityRulesDisabled(async (ctx) => {
    await setDoc(doc(ctx.firestore(), 'chats', CHAT_ID), {
      type: 'INDIVIDUAL',
      participants: [OWNER, MEMBER],
    });
  });
});

describe('backups/{uid}/*', () => {
  test('owner can write, read, and delete manifest.json with application/json contentType', async () => {
    const manifestRef = ref(ownerStorage, `backups/${OWNER}/manifest.json`);
    await assertSucceeds(
      uploadString(manifestRef, '{"version":1}', 'raw', { contentType: 'application/json' }),
    );
    await assertSucceeds(getBytes(manifestRef));
    await assertSucceeds(deleteObject(manifestRef));
  });

  test('manifest.json rejects non-JSON contentType and other users', async () => {
    const ownerManifest = ref(ownerStorage, `backups/${OWNER}/manifest.json`);
    await assertFails(
      uploadBytes(ownerManifest, SMALL_BYTES, { contentType: 'application/octet-stream' }),
    );
    await assertSucceeds(
      uploadString(ownerManifest, '{"version":1}', 'raw', { contentType: 'application/json' }),
    );
    await assertFails(getBytes(ref(outsiderStorage, `backups/${OWNER}/manifest.json`)));
    await assertFails(deleteObject(ref(outsiderStorage, `backups/${OWNER}/manifest.json`)));
    await assertFails(getBytes(ref(signedOutStorage, `backups/${OWNER}/manifest.json`)));
  });

  test('owner can write, read, and delete versioned db_<ts>_<hex>.enc blobs; invalid names and non-owners rejected', async () => {
    const validDb = ref(ownerStorage, `backups/${OWNER}/db_1750000000000_deadbeef.enc`);
    await assertSucceeds(
      uploadBytes(validDb, SMALL_BYTES, { contentType: 'application/octet-stream' }),
    );
    await assertSucceeds(getBytes(validDb));

    // Non-owner read/write/delete rejected
    await assertFails(getBytes(ref(outsiderStorage, `backups/${OWNER}/db_1750000000000_deadbeef.enc`)));
    await assertFails(deleteObject(ref(outsiderStorage, `backups/${OWNER}/db_1750000000000_deadbeef.enc`)));

    // Bad filename or wrong content-type rejected
    await assertFails(
      uploadBytes(ref(ownerStorage, `backups/${OWNER}/arbitrary.bin`), SMALL_BYTES, {
        contentType: 'application/octet-stream',
      }),
    );
    await assertFails(
      uploadBytes(ref(ownerStorage, `backups/${OWNER}/db_1750000000000_deadbeef.enc`), SMALL_BYTES, {
        contentType: 'text/plain',
      }),
    );

    await assertSucceeds(deleteObject(validDb));
  });

  test('owner can write, read, and delete legacy-media/{keyGen}/{id}.enc; invalid keyGen or non-owner rejected', async () => {
    const validLegacy = ref(ownerStorage, `backups/${OWNER}/legacy-media/${KEY_GEN}/msg-1.enc`);
    await assertSucceeds(
      uploadBytes(validLegacy, SMALL_BYTES, { contentType: 'application/octet-stream' }),
    );
    await assertSucceeds(getBytes(validLegacy));

    await assertFails(
      getBytes(ref(outsiderStorage, `backups/${OWNER}/legacy-media/${KEY_GEN}/msg-1.enc`)),
    );
    await assertFails(
      uploadBytes(ref(ownerStorage, `backups/${OWNER}/legacy-media/short/msg-1.enc`), SMALL_BYTES, {
        contentType: 'application/octet-stream',
      }),
    );
    await assertFails(
      uploadBytes(ref(ownerStorage, `backups/${OWNER}/legacy-media/${KEY_GEN}/msg-1.jpg`), SMALL_BYTES, {
        contentType: 'application/octet-stream',
      }),
    );
    await assertSucceeds(deleteObject(validLegacy));
  });

  test('listing backups/{uid} is denied even for the owner', async () => {
    await assertFails(listAll(ref(ownerStorage, `backups/${OWNER}`)));
  });
});

describe('media/{chatId}/{fileName}', () => {
  test('chat participants can upload and get media, outsiders cannot, and client delete is denied', async () => {
    const ownerMedia = ref(ownerStorage, `media/${CHAT_ID}/m1.enc`);
    await assertSucceeds(
      uploadBytes(ownerMedia, SMALL_BYTES, { contentType: 'application/octet-stream' }),
    );
    await assertSucceeds(getBytes(ref(memberStorage, `media/${CHAT_ID}/m1.enc`)));
    await assertFails(getBytes(ref(outsiderStorage, `media/${CHAT_ID}/m1.enc`)));
    await assertFails(
      uploadBytes(ref(outsiderStorage, `media/${CHAT_ID}/m2.enc`), SMALL_BYTES, {
        contentType: 'application/octet-stream',
      }),
    );
    await assertFails(deleteObject(ownerMedia));
    await assertFails(listAll(ref(ownerStorage, `media/${CHAT_ID}`)));
  });
});

describe('stickers/{fileName}', () => {
  test('signed-in user can create immutable sha256 webp/tgs sticker and get it, but cannot overwrite, delete, or list', async () => {
    const stickerPath = `stickers/${SHA256}.webp`;
    const stickerRef = ref(ownerStorage, stickerPath);
    await assertSucceeds(uploadBytes(stickerRef, SMALL_BYTES, { contentType: 'image/webp' }));
    await assertSucceeds(getBytes(ref(outsiderStorage, stickerPath)));

    // Overwrite, delete, list, bad name, and wrong MIME all denied
    await assertFails(uploadBytes(stickerRef, SMALL_BYTES, { contentType: 'image/webp' }));
    await assertFails(deleteObject(stickerRef));
    await assertFails(listAll(ref(ownerStorage, 'stickers')));
    await assertFails(
      uploadBytes(ref(ownerStorage, 'stickers/not-a-sha256.webp'), SMALL_BYTES, {
        contentType: 'image/webp',
      }),
    );
    await assertFails(
      uploadBytes(ref(ownerStorage, `stickers/${'b'.repeat(64)}.webp`), SMALL_BYTES, {
        contentType: 'image/png',
      }),
    );
  });
});

describe('avatars/*', () => {
  test('user avatar allows owner write/delete on profile.jpg and signed-in get', async () => {
    const avatarRef = ref(ownerStorage, `avatars/${OWNER}/profile.jpg`);
    await assertSucceeds(uploadBytes(avatarRef, SMALL_BYTES, { contentType: 'image/jpeg' }));
    await assertSucceeds(getBytes(ref(outsiderStorage, `avatars/${OWNER}/profile.jpg`)));
    await assertFails(
      uploadBytes(ref(outsiderStorage, `avatars/${OWNER}/profile.jpg`), SMALL_BYTES, {
        contentType: 'image/jpeg',
      }),
    );
    await assertFails(
      uploadBytes(ref(ownerStorage, `avatars/${OWNER}/other.jpg`), SMALL_BYTES, {
        contentType: 'image/jpeg',
      }),
    );
    await assertSucceeds(deleteObject(avatarRef));
  });

  test('group avatar restricts writes to chat participants and denies client delete', async () => {
    const groupAvatarRef = ref(ownerStorage, `avatars/groups/${CHAT_ID}/group.jpg`);
    await assertSucceeds(uploadBytes(groupAvatarRef, SMALL_BYTES, { contentType: 'image/jpeg' }));
    await assertSucceeds(getBytes(ref(outsiderStorage, `avatars/groups/${CHAT_ID}/group.jpg`)));
    await assertFails(
      uploadBytes(ref(outsiderStorage, `avatars/groups/${CHAT_ID}/group.jpg`), SMALL_BYTES, {
        contentType: 'image/jpeg',
      }),
    );
    await assertFails(deleteObject(groupAvatarRef));
  });
});
