import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  buildScrubPlan,
  evaluateSafetyGate,
  executeScrub,
  formatBytes,
  formatReport,
  isIndividualChat,
  isKlipyUrl,
  isPlaintextMessage,
  isScrubbableStorageObject,
  keyGenerationFromSaltB64,
  needsLegacyMediaBackup,
  needsPreviewScrub,
  parseArgs,
  validateManifest,
} from './scrub-plaintext.mjs';

const SALT_A = Buffer.alloc(32, 0xaa);
const SALT_A_B64 = SALT_A.toString('base64');
const KEYGEN_A = createHash('sha256').update(SALT_A).digest().subarray(0, 8).toString('hex');

const SALT_B = Buffer.alloc(32, 0xbb);
const SALT_B_B64 = SALT_B.toString('base64');
const KEYGEN_B = createHash('sha256').update(SALT_B).digest().subarray(0, 8).toString('hex');

function makeManifestJson(overrides = {}) {
  return JSON.stringify({
    version: 1,
    saltB64: SALT_A_B64,
    kdfIterations: 600_000,
    createdAt: 2_000_000,
    dbObjectName: 'db_2000000_deadbeef.enc',
    dbCipherSizeBytes: 4096,
    legacyMediaComplete: true,
    encryptedMetaB64: 'ZW5jcnlwdGVk',
    encryptedMetaIvB64: 'MTIzNDU2Nzg5MDEy',
    ...overrides,
  });
}

// --- CLI argument parsing ---

test('parseArgs defaults to dry-run and deduplicates users', () => {
  const parsed = parseArgs(['--users', 'uid1, uid2, uid1']);
  assert.equal(parsed.dryRun, true);
  assert.equal(parsed.execute, false);
  assert.deepEqual(parsed.users, ['uid1', 'uid2']);
});

test('parseArgs accepts --execute and --users= syntax', () => {
  const parsed = parseArgs(['--execute', '--users=uid1,uid2', '--bucket=my-bucket']);
  assert.equal(parsed.execute, true);
  assert.equal(parsed.dryRun, false);
  assert.deepEqual(parsed.users, ['uid1', 'uid2']);
  assert.equal(parsed.bucket, 'my-bucket');
});

test('parseArgs rejects conflicting --dry-run and --execute', () => {
  assert.throws(() => parseArgs(['--dry-run', '--execute', '--users', 'uid1']), /Cannot pass both/);
});

test('parseArgs rejects missing --users, flag-as-value, path-traversal UIDs, and unknown flags', () => {
  assert.throws(() => parseArgs(['--execute']), /--users/);
  assert.throws(() => parseArgs(['--users', '--execute']), /--users requires/);
  assert.throws(() => parseArgs(['--bucket', '--execute', '--users', 'uid1']), /--bucket requires/);
  assert.throws(() => parseArgs(['--credentials', '--execute', '--users', 'uid1']), /--credentials requires/);
  assert.throws(() => parseArgs(['--users', '../admin']), /Invalid UID/);
  assert.throws(() => parseArgs(['--users', 'uid1/sub']), /Invalid UID/);
  assert.throws(() => parseArgs(['--users', 'uid1', '--bogus']), /Unknown argument/);
});

// --- Key generation & manifest validation ---

test('keyGenerationFromSaltB64 matches BackupCrypto 16-char lowercase hex prefix of SHA-256(salt)', () => {
  assert.equal(keyGenerationFromSaltB64(SALT_A_B64), KEYGEN_A);
  assert.equal(KEYGEN_A.length, 16);
  assert.throws(() => keyGenerationFromSaltB64(Buffer.alloc(16).toString('base64')), /32-byte salt/);
  assert.throws(() => keyGenerationFromSaltB64('not!base64'), /not valid base64/);
});

test('validateManifest accepts valid v1 manifest with versioned dbObjectName', () => {
  const res = validateManifest(makeManifestJson());
  assert.equal(res.valid, true);
  assert.equal(res.manifest.dbObjectName, 'db_2000000_deadbeef.enc');
  assert.equal(res.manifest.keyGen, KEYGEN_A);
});

test('validateManifest rejects legacy unversioned db.enc, path traversal, wrong version, and bad KDF', () => {
  assert.equal(validateManifest(makeManifestJson({ dbObjectName: 'db.enc' })).valid, false);
  assert.equal(validateManifest(makeManifestJson({ dbObjectName: '../other/db_1_deadbeef.enc' })).valid, false);
  assert.equal(validateManifest(makeManifestJson({ version: 2 })).valid, false);
  assert.equal(validateManifest(makeManifestJson({ kdfIterations: 1000 })).valid, false);
  assert.equal(validateManifest(makeManifestJson({ dbCipherSizeBytes: 0 })).valid, false);
  assert.equal(validateManifest('not json').valid, false);
});

// --- Message, preview, Klipy, and Storage filtering ---

test('isPlaintextMessage distinguishes plaintext, encrypted, tombstones, and Phase-1 hybrids', () => {
  // Phase-2+ encrypted text / media / edited message — untouched
  assert.equal(isPlaintextMessage({ ciphertext: 'c1', signalType: 3, type: 'TEXT', timestamp: 1000 }), false);
  assert.equal(
    isPlaintextMessage({
      ciphertext: 'c1',
      signalType: 3,
      editCiphertext: 'e1',
      editSignalType: 2,
      editedAt: 2000,
      type: 'TEXT',
      timestamp: 1000,
    }),
    false
  );

  // Empty deleted tombstone — untouched (must not trip staleness gate on new deletes)
  assert.equal(
    isPlaintextMessage({ deletedAt: 5000, content: '', ciphertext: null, mediaUrl: null, mediaThumbnailUrl: null }),
    false
  );

  // Deleted tombstone that still leaked plaintext metadata — scrub
  assert.equal(
    isPlaintextMessage({ deletedAt: 5000, content: '', fileName: 'secret.pdf' }),
    true
  );

  // Plaintext text, uncaptioned media, document, location, sticker — scrub
  assert.equal(isPlaintextMessage({ content: 'hello', type: 'TEXT' }), true);
  assert.equal(isPlaintextMessage({ content: '', mediaUrl: 'https://storage/photo.jpg', type: 'IMAGE' }), true);
  assert.equal(isPlaintextMessage({ fileName: 'tax.pdf', type: 'DOCUMENT' }), true);
  assert.equal(isPlaintextMessage({ latitude: 52.52, longitude: 13.4, type: 'LOCATION' }), true);
  assert.equal(isPlaintextMessage({ stickerId: 'abc123', type: 'STICKER' }), true);

  // Phase-1 hybrid doc with ciphertext + plaintext mediaUrl or content — scrub
  assert.equal(
    isPlaintextMessage({ ciphertext: 'c1', mediaUrl: 'https://storage/old.jpg', type: 'IMAGE' }),
    true
  );
  assert.equal(
    isPlaintextMessage({ ciphertext: 'c1', content: 'leaked plaintext edit', type: 'TEXT' }),
    true
  );

  // Always-plaintext structured 1:1 messages — preserved (live polls, lists, calls, timers)
  assert.equal(isPlaintextMessage({ type: 'POLL', content: '📊 Poll', pollData: {} }), false);
  assert.equal(isPlaintextMessage({ type: 'LIST', content: 'Groceries', listId: 'l1' }), false);
  assert.equal(isPlaintextMessage({ type: 'CALL', content: 'ENDED', duration: 42 }), false);
  assert.equal(isPlaintextMessage({ type: 'TIMER', content: 'Pasta', timerDurationMs: 60000 }), false);
});

test('needsLegacyMediaBackup covers Phase-1 hybrids and excludes Klipy URLs, stickers, .enc, and deleted messages', () => {
  assert.equal(
    needsLegacyMediaBackup({ type: 'IMAGE', mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/x/o/a.jpg' }),
    true
  );
  assert.equal(
    needsLegacyMediaBackup({ type: 'VIDEO', mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/x/o/v.mp4' }),
    true
  );
  // Phase-1 hybrid (encrypted caption + unencrypted Storage media) MUST be backed up before scrub
  assert.equal(
    needsLegacyMediaBackup({
      type: 'IMAGE',
      ciphertext: 'c1',
      mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/x/o/media%2Fdm1%2Fhybrid.jpg?alt=media',
    }),
    true
  );
  assert.equal(
    needsLegacyMediaBackup({ type: 'GIF', mediaUrl: 'https://static.klipy.com/gifs/abc.gif' }),
    false
  );
  assert.equal(
    needsLegacyMediaBackup({ type: 'STICKER', mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/x/o/s.webp' }),
    false
  );
  assert.equal(
    needsLegacyMediaBackup({ type: 'IMAGE', mediaUrl: 'https://x/a.jpg', deletedAt: 1000 }),
    false
  );
  assert.equal(
    needsLegacyMediaBackup({
      type: 'IMAGE',
      mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/x/o/media%2Fdm1%2Fa.enc?alt=media',
      ciphertext: 'c1',
    }),
    false
  );
});

test('isKlipyUrl enforces exact https host match', () => {
  assert.equal(isKlipyUrl('https://static.klipy.com/a.gif'), true);
  assert.equal(isKlipyUrl('https://static1.klipy.com/a.gif'), true);
  assert.equal(isKlipyUrl('https://static2.klipy.com:443/a.gif'), true);
  assert.equal(isKlipyUrl('http://static.klipy.com/a.gif'), false);
  assert.equal(isKlipyUrl('https://evil.static.klipy.com/a.gif'), false);
  assert.equal(isKlipyUrl('https://user@static.klipy.com/a.gif'), false);
});

test('needsPreviewScrub scrubs user plaintext previews but preserves safe placeholders and empty values', () => {
  assert.equal(needsPreviewScrub('Hey see you at 8'), true);
  assert.equal(needsPreviewScrub('📷 Sunset caption'), true);
  assert.equal(needsPreviewScrub('Message'), false);
  assert.equal(needsPreviewScrub('📷 Photo'), false);
  assert.equal(needsPreviewScrub('🎥 Video'), false);
  assert.equal(needsPreviewScrub('🎤 Voice message'), false);
  assert.equal(needsPreviewScrub(''), false);
  assert.equal(needsPreviewScrub(null), false);
});

test('isScrubbableStorageObject only matches non-.enc files in 1:1 chats', () => {
  const dmIds = new Set(['dm1']);
  assert.equal(isScrubbableStorageObject('media/dm1/m1.jpg', dmIds), true);
  assert.equal(isScrubbableStorageObject('media/dm1/m1_thumb.jpg', dmIds), true);
  assert.equal(isScrubbableStorageObject('media/dm1/m1.enc', dmIds), false);
  assert.equal(isScrubbableStorageObject('media/dm1/m1_thumb.enc', dmIds), false);
  // Group chat media MUST stay intact
  assert.equal(isScrubbableStorageObject('media/group1/g1.jpg', dmIds), false);
  // Protected buckets MUST stay intact
  assert.equal(isScrubbableStorageObject('backups/uid1/manifest.json', dmIds), false);
  assert.equal(isScrubbableStorageObject('backups/uid1/db_123_deadbeef.enc', dmIds), false);
  assert.equal(isScrubbableStorageObject('backups/uid1/legacy-media/0123456789abcdef/m1.enc', dmIds), false);
  assert.equal(isScrubbableStorageObject('avatars/uid1/profile.jpg', dmIds), false);
  assert.equal(isScrubbableStorageObject('stickers/abc.webp', dmIds), false);
});

// --- Plan builder & safety gate ---

function createFixture() {
  const chats = [
    {
      id: 'dm1',
      data: {
        type: 'INDIVIDUAL',
        participants: ['uid1', 'uid2'],
        lastMessageContent: 'Secret dinner plans',
      },
    },
    {
      id: 'grp1',
      data: {
        type: 'GROUP',
        participants: ['uid1', 'uid2', 'uid3'],
        lastMessageContent: 'Group plaintext stays intact',
      },
    },
  ];

  const messagesByChat = new Map([
    [
      'dm1',
      [
        { id: 'm_plain_text', data: { type: 'TEXT', content: 'hi', timestamp: 1_000_000 } },
        {
          id: 'm_plain_img',
          data: {
            type: 'IMAGE',
            content: '',
            mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/b/o/media%2Fdm1%2Fm_plain_img.jpg',
            timestamp: 1_500_000,
          },
        },
        { id: 'm_enc', data: { type: 'TEXT', ciphertext: 'abc', signalType: 3, timestamp: 2_500_000 } },
        { id: 'm_tombstone', data: { type: 'TEXT', content: '', deletedAt: 2_600_000, timestamp: 2_550_000 } },
      ],
    ],
    [
      'grp1',
      [{ id: 'g_plain', data: { type: 'TEXT', content: 'group hello', timestamp: 1_900_000 } }],
    ],
  ]);

  const storageObjectsByChat = new Map([
    [
      'dm1',
      [
        { name: 'media/dm1/m_plain_img.jpg', size: 120_000 },
        { name: 'media/dm1/m_enc.enc', size: 80_000 },
      ],
    ],
    ['grp1', [{ name: 'media/grp1/g_img.jpg', size: 200_000 }]],
  ]);

  const backupsByUser = new Map([
    [
      'uid1',
      {
        manifestRaw: makeManifestJson({ saltB64: SALT_A_B64, createdAt: 2_000_000 }),
        dbObjectExists: true,
        dbObjectSize: 4096,
        legacyMediaObjects: [
          { name: `backups/uid1/legacy-media/${KEYGEN_A}/m_plain_img.enc`, size: 120_064 },
        ],
      },
    ],
    [
      'uid2',
      {
        manifestRaw: makeManifestJson({
          saltB64: SALT_B_B64,
          createdAt: 2_100_000,
          dbObjectName: 'db_2100000_cafe1234.enc',
          dbCipherSizeBytes: 8192,
        }),
        dbObjectExists: true,
        dbObjectSize: 8192,
        legacyMediaObjects: [
          { name: `backups/uid2/legacy-media/${KEYGEN_B}/m_plain_img.enc`, size: 120_064 },
        ],
      },
    ],
  ]);

  return { chats, messagesByChat, storageObjectsByChat, backupsByUser };
}

test('buildScrubPlan targets only 1:1 plaintext, preserving group chats, encrypted docs, and .enc media', () => {
  const { chats, messagesByChat, storageObjectsByChat } = createFixture();
  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });

  assert.equal(isIndividualChat(chats[0].data), true);
  assert.equal(isIndividualChat(chats[1].data), false);
  assert.equal(plan.totals.individualChats, 1);
  assert.equal(plan.totals.skippedNonIndividualChats, 1);
  assert.equal(plan.totals.messagesToDelete, 2);
  assert.equal(plan.totals.messagesPreserved, 2);
  assert.equal(plan.totals.previewsToScrub, 1);
  assert.equal(plan.totals.storageObjectsToDelete, 1);
  assert.equal(plan.totals.storageObjectsPreserved, 1);
  assert.equal(plan.totals.bytesToFree, 120_000);
});

test('evaluateSafetyGate passes when all users have fresh complete backups with versioned DB and legacy-media/{keyGen}', () => {
  const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const result = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan, backupsByUser });

  assert.equal(result.ok, true);
  assert.equal(result.failures.length, 0);
  assert.equal(result.userReports.length, 2);
  assert.equal(result.userReports[0].verifiedLegacyMediaCount, 1);
  assert.equal(result.userReports[1].verifiedLegacyMediaCount, 1);
});

test('evaluateSafetyGate fails when any user backup is missing, incomplete, stale, size-mismatched, or missing legacy media', () => {
  const cases = [
    {
      name: 'missing manifest',
      mutate: (b) => b.delete('uid2'),
      pattern: /Missing backups\/uid2\/manifest\.json/,
    },
    {
      name: 'legacyMediaComplete is false',
      mutate: (b) => {
        b.get('uid1').manifestRaw = makeManifestJson({ legacyMediaComplete: false });
      },
      pattern: /legacyMediaComplete is false/,
    },
    {
      name: 'missing versioned db_<ts>_<hex>.enc blob',
      mutate: (b) => {
        b.get('uid1').dbObjectExists = false;
      },
      pattern: /Backup DB blob missing.*db_2000000_deadbeef\.enc/,
    },
    {
      name: 'empty db blob',
      mutate: (b) => {
        b.get('uid1').dbObjectSize = 0;
      },
      pattern: /Backup DB blob is empty/,
    },
    {
      name: 'db blob size mismatch vs manifest.dbCipherSizeBytes',
      mutate: (b) => {
        b.get('uid1').dbObjectSize = 1024;
      },
      pattern: /size mismatch/,
    },
    {
      name: 'stale manifest.createdAt <= latest plaintext timestamp',
      mutate: (b) => {
        b.get('uid1').manifestRaw = makeManifestJson({ createdAt: 1_500_000 });
      },
      pattern: /Backup is stale/,
    },
    {
      name: 'missing legacy-media/{keyGen}/{messageId}.enc blob',
      mutate: (b) => {
        b.get('uid2').legacyMediaObjects = [];
      },
      pattern: new RegExp(`legacy-media/${KEYGEN_B}/.*m_plain_img`),
    },
    {
      name: 'legacy-media blob under wrong keyGen namespace',
      mutate: (b) => {
        b.get('uid2').legacyMediaObjects = [
          { name: `backups/uid2/legacy-media/${KEYGEN_A}/m_plain_img.enc`, size: 120_064 },
        ];
      },
      pattern: new RegExp(`legacy-media/${KEYGEN_B}/`),
    },
  ];

  for (const tc of cases) {
    const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
    tc.mutate(backupsByUser);
    const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
    const res = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan, backupsByUser });
    assert.equal(res.ok, false, `Expected safety gate failure for: ${tc.name}`);
    assert.match(res.failures.join('\n'), tc.pattern, `Unexpected failure message for: ${tc.name}`);
  }
});

test('evaluateSafetyGate fails if a 1:1 chat with plaintext has a participant missing from --users', () => {
  const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const res = evaluateSafetyGate({ users: ['uid1'], plan, backupsByUser });

  assert.equal(res.ok, false);
  assert.match(res.failures.join('\n'), /unverified participant\(s\): uid2/);
});

test('evaluateSafetyGate catches plaintext edit newer than backup via editedAt', () => {
  const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
  messagesByChat.get('dm1')[0].data.editedAt = 2_050_000;
  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const res = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan, backupsByUser });

  assert.equal(res.ok, false);
  assert.match(res.failures.join('\n'), /\[uid1\] Backup is stale/);
});

test('evaluateSafetyGate enforces legacy-media backup on Phase-1 hybrid media and ignores newer CALL/TIMER/POLL/LIST docs', () => {
  const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
  // Newer structured docs after backup must NOT trip staleness or get scheduled for deletion
  messagesByChat.get('dm1').push(
    { id: 'm_call_new', data: { type: 'CALL', content: 'ENDED', timestamp: 3_000_000 } },
    { id: 'm_timer_new', data: { type: 'TIMER', content: 'Tea', timestamp: 3_100_000 } },
    { id: 'm_poll_new', data: { type: 'POLL', content: '📊 Poll', timestamp: 3_200_000 } },
    { id: 'm_list_new', data: { type: 'LIST', content: 'Packing', timestamp: 3_300_000 } },
  );
  const planWithStructured = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  assert.equal(planWithStructured.totals.messagesToDelete, 2);
  assert.equal(planWithStructured.totals.messagesPreserved, 6);
  assert.equal(evaluateSafetyGate({ users: ['uid1', 'uid2'], plan: planWithStructured, backupsByUser }).ok, true);

  // Adding a Phase-1 hybrid media doc (ciphertext + unencrypted mediaUrl) without a legacy-media backup MUST fail gate
  messagesByChat.get('dm1').push({
    id: 'm_hybrid_img',
    data: {
      type: 'IMAGE',
      ciphertext: 'c_caption',
      signalType: 3,
      mediaUrl: 'https://firebasestorage.googleapis.com/v0/b/b/o/media%2Fdm1%2Fm_hybrid_img.jpg?alt=media',
      timestamp: 1_600_000,
    },
  });
  const planWithHybrid = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const hybridGate = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan: planWithHybrid, backupsByUser });
  assert.equal(hybridGate.ok, false);
  assert.match(hybridGate.failures.join('\n'), /m_hybrid_img/);
});

// --- Execution, dry-run, batching, and idempotency ---

test('executeScrub performs zero mutations in dry-run and hard-fails on red safety gate in execute mode', async () => {
  const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const passGate = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan, backupsByUser });

  let calls = 0;
  const dryRes = await executeScrub({
    plan,
    safetyResult: passGate,
    execute: false,
    deleteMessageBatch: async () => { calls++; },
    scrubChatPreview: async () => { calls++; },
    deleteStorageObject: async () => { calls++; },
  });
  assert.equal(dryRes.executed, false);
  assert.equal(calls, 0);

  backupsByUser.delete('uid2');
  const failGate = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan, backupsByUser });
  await assert.rejects(
    () =>
      executeScrub({
        plan,
        safetyResult: failGate,
        execute: true,
        deleteMessageBatch: async () => { calls++; },
        scrubChatPreview: async () => { calls++; },
        deleteStorageObject: async () => { calls++; },
      }),
    /safety gate failed/
  );
  assert.equal(calls, 0);
});

test('executeScrub batches deletes, scrubs previews and 1:1 legacy storage, and is idempotent on re-run', async () => {
  const { chats, messagesByChat, storageObjectsByChat, backupsByUser } = createFixture();
  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const safetyResult = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan, backupsByUser });

  const deletedBatches = [];
  const scrubbedPreviews = [];
  const deletedStorage = [];

  const result = await executeScrub({
    plan,
    safetyResult,
    execute: true,
    batchSize: 1, // force multiple batches to verify chunking
    deleteMessageBatch: async (chatId, ids) => {
      deletedBatches.push({ chatId, ids });
      const remaining = messagesByChat.get(chatId).filter((m) => !ids.includes(m.id));
      messagesByChat.set(chatId, remaining);
    },
    scrubChatPreview: async (chatId, preview) => {
      scrubbedPreviews.push({ chatId, preview });
      chats.find((c) => c.id === chatId).data.lastMessageContent = preview;
    },
    deleteStorageObject: async (name) => {
      deletedStorage.push(name);
      for (const [cid, list] of storageObjectsByChat.entries()) {
        storageObjectsByChat.set(cid, list.filter((o) => o.name !== name));
      }
    },
  });

  assert.equal(result.executed, true);
  assert.equal(result.docsDeleted, 2);
  assert.equal(result.previewsScrubbed, 1);
  assert.equal(result.storageObjectsDeleted, 1);
  assert.equal(result.bytesFreed, 120_000);
  assert.equal(deletedBatches.length, 2);
  assert.deepEqual(scrubbedPreviews, [{ chatId: 'dm1', preview: 'Message' }]);
  assert.deepEqual(deletedStorage, ['media/dm1/m_plain_img.jpg']);
  // Group chat and encrypted assets remained untouched
  assert.equal(messagesByChat.get('grp1').length, 1);
  assert.equal(storageObjectsByChat.get('grp1').length, 1);
  assert.equal(storageObjectsByChat.get('dm1').length, 1);
  assert.equal(storageObjectsByChat.get('dm1')[0].name, 'media/dm1/m_enc.enc');

  // Re-run is completely idempotent
  const secondPlan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });
  const secondGate = evaluateSafetyGate({ users: ['uid1', 'uid2'], plan: secondPlan, backupsByUser });
  assert.equal(secondGate.ok, true);
  let secondCalls = 0;
  const secondResult = await executeScrub({
    plan: secondPlan,
    safetyResult: secondGate,
    execute: true,
    deleteMessageBatch: async () => { secondCalls++; },
    scrubChatPreview: async () => { secondCalls++; },
    deleteStorageObject: async () => { secondCalls++; },
  });
  assert.equal(secondCalls, 0);
  assert.equal(secondResult.docsDeleted, 0);
  assert.equal(secondResult.previewsScrubbed, 0);
  assert.equal(secondResult.storageObjectsDeleted, 0);
  assert.equal(secondResult.bytesFreed, 0);

  // Report formatting smoke check
  const reportText = formatReport({
    args: { execute: true, users: ['uid1', 'uid2'] },
    plan,
    safetyResult,
    execution: result,
  });
  assert.match(reportText, /Safety gate overall: PASSED/);
  assert.match(reportText, /Firestore message docs deleted: 2/);
  assert.equal(formatBytes(0), '0 B');
  assert.equal(formatBytes(1536), '1.5 KB');
});
