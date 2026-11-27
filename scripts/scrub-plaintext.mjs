#!/usr/bin/env node
/**
 * One-time host-side plaintext scrub for FireStream Chat (Step 9 of docs/plans/e2e-encryption.md).
 *
 * Defaults to --dry-run. Refuses to mutate anything without --execute AND a passing backup
 * safety gate for every user in --users.
 *
 * Scope (strictly 1:1 chats only — group & broadcast chats and their media stay untouched):
 *   1. Deletes plaintext message docs under chats/{1:1 chatId}/messages/*, keeping encrypted
 *      docs (ciphertext != null && no plaintext content/metadata) and empty deleted tombstones.
 *   2. Rewrites plaintext chats/{1:1 chatId}.lastMessageContent to "Message" (leaving generic
 *      type-only placeholders untouched).
 *   3. Deletes non-.enc objects under media/{1:1 chatId}/* in Firebase Storage, leaving .enc
 *      blobs, group media, backups/, avatars/, and stickers/ untouched.
 *
 * Safety gate (evaluated in both --dry-run and --execute; hard-fails in --execute):
 *   - Every participant of every 1:1 chat with scrubbable plaintext/media must be in --users.
 *   - Every UID in --users must have a valid backups/{uid}/manifest.json with:
 *       * legacyMediaComplete === true
 *       * versioned dbObjectName (db_<ts>_<hex>.enc) existing in Storage with exact byte size
 *       * createdAt strictly newer than the latest plaintext message/edit timestamp in their 1:1 chats
 *       * every non-deleted legacy media message in their 1:1 chats present under
 *         backups/{uid}/legacy-media/{keyGen}/{messageId}.enc
 *
 * Credentials & bucket:
 *   - Credentials: --credentials <path>, GOOGLE_APPLICATION_CREDENTIALS, KEY_FILE, or ADC.
 *   - Bucket: --bucket <name>, FIREBASE_STORAGE_BUCKET, BUCKET, or app/google-services.json.
 *
 * Usage:
 *   node scripts/scrub-plaintext.mjs --users uid1,uid2
 *   node scripts/scrub-plaintext.mjs --execute --users uid1,uid2 --bucket <bucket>
 */

import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const DB_OBJECT_NAME_REGEX = /^db_[0-9]+(_[0-9a-f]{8})?\.enc$/;
export const MIN_KDF_ITERATIONS = 100_000;
export const MAX_KDF_ITERATIONS = 5_000_000;
export const SALT_BYTES = 32;
export const DEFAULT_FIRESTORE_BATCH_SIZE = 400;

export const LEGACY_MEDIA_MESSAGE_TYPES = new Set([
  'IMAGE',
  'VIDEO',
  'DOCUMENT',
  'GIF',
  'VOICE',
]);

/**
 * Structured message types that continue to write plaintext metadata/content on Firestore
 * even with always-on 1:1 E2EE (live poll voting, shared list diffs, calls, shared timers).
 * Scrubbing them would break active polls/timers/lists and falsely trip backup staleness.
 */
export const STRUCTURED_PLAINTEXT_TYPES = new Set([
  'POLL',
  'LIST',
  'CALL',
  'TIMER',
]);

export const KLIPY_MEDIA_HOSTS = new Set([
  'static.klipy.com',
  'static1.klipy.com',
  'static2.klipy.com',
]);

/**
 * Exact type-only preview strings written by FirestoreMessageSource.lastContentFor(type, "").
 * Leaving these untouched keeps the preview scrubber idempotent and avoids overwriting
 * previews for Phase-2+ encrypted media messages.
 */
export const SAFE_PREVIEW_PLACEHOLDERS = new Set([
  'Message',
  '📷 Photo',
  '🎥 Video',
  '📎 File',
  '🎤 Voice message',
  '📊 Poll',
  '📋 List',
  '📍 Location',
  '📞 Voice call',
  '⏱ Timer',
  'Sticker',
  '🎞️ GIF',
]);

const PROTECTED_STORAGE_PREFIXES = ['backups/', 'avatars/', 'stickers/'];

function hasNonEmptyString(value) {
  return typeof value === 'string' && value.length > 0;
}

function isValidUid(uid) {
  return (
    typeof uid === 'string' &&
    uid.length > 0 &&
    !uid.startsWith('-') &&
    !uid.includes('/') &&
    !uid.includes('..')
  );
}

function isEncryptedMediaUrl(url) {
  if (!hasNonEmptyString(url)) return false;
  try {
    const parsed = new URL(url);
    const decodedPath = decodeURIComponent(parsed.pathname);
    return decodedPath.endsWith('.enc');
  } catch {
    return url.endsWith('.enc');
  }
}

/**
 * Matches KlipyUrls.isMedia() in Kotlin: https URL with exact Klipy host, no userinfo,
 * default or 443 port, and no whitespace/control chars/backslashes.
 */
export function isKlipyUrl(url) {
  if (typeof url !== 'string' || url.length === 0) return false;
  if (/[\s\u0000-\u001f\u007f\\]/.test(url)) return false;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'https:') return false;
  if (parsed.username || parsed.password) return false;
  if (parsed.port && parsed.port !== '443') return false;
  return KLIPY_MEDIA_HOSTS.has(parsed.hostname.toLowerCase());
}

/**
 * Deterministic 16-char lowercase hex key-generation namespace matching
 * BackupCrypto.keyGeneration(salt) (first 8 bytes of SHA-256(salt) as hex).
 */
export function keyGenerationFromSaltB64(saltB64) {
  if (!hasNonEmptyString(saltB64)) {
    throw new Error('saltB64 must be a non-empty string');
  }
  if (!/^[A-Za-z0-9+/]+=*$/.test(saltB64) || saltB64.length % 4 !== 0) {
    throw new Error('saltB64 is not valid base64');
  }
  const salt = Buffer.from(saltB64, 'base64');
  if (salt.length !== SALT_BYTES) {
    throw new Error(`Expected ${SALT_BYTES}-byte salt, got ${salt.length}`);
  }
  return createHash('sha256').update(salt).digest().subarray(0, 8).toString('hex');
}

/**
 * Normalizes a numeric epoch-ms value or Firestore Timestamp-like object into epoch ms.
 */
export function toEpochMs(value) {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return value;
  }
  if (value && typeof value.toMillis === 'function') {
    const ms = value.toMillis();
    if (typeof ms === 'number' && Number.isFinite(ms) && ms > 0) return ms;
  }
  return 0;
}

/**
 * Parses and validates backups/{uid}/manifest.json against BackupCrypto's v1 schema.
 */
export function validateManifest(rawText) {
  if (typeof rawText !== 'string' || rawText.trim().length === 0) {
    return { valid: false, errors: ['manifest.json is empty or missing'] };
  }
  let parsed;
  try {
    parsed = JSON.parse(rawText);
  } catch (err) {
    return { valid: false, errors: [`manifest.json is not valid JSON: ${err.message}`] };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { valid: false, errors: ['manifest.json must be a JSON object'] };
  }

  const errors = [];
  if (parsed.version !== 1) {
    errors.push(`Unsupported manifest version: ${String(parsed.version)} (expected 1)`);
  }

  let keyGen = null;
  try {
    keyGen = keyGenerationFromSaltB64(parsed.saltB64);
  } catch (err) {
    errors.push(`Invalid saltB64: ${err.message}`);
  }

  if (
    typeof parsed.kdfIterations !== 'number' ||
    !Number.isInteger(parsed.kdfIterations) ||
    parsed.kdfIterations < MIN_KDF_ITERATIONS ||
    parsed.kdfIterations > MAX_KDF_ITERATIONS
  ) {
    errors.push(
      `Invalid kdfIterations: ${String(parsed.kdfIterations)} (expected ${MIN_KDF_ITERATIONS}..${MAX_KDF_ITERATIONS})`
    );
  }

  if (
    typeof parsed.createdAt !== 'number' ||
    !Number.isFinite(parsed.createdAt) ||
    parsed.createdAt <= 0
  ) {
    errors.push(`Invalid createdAt: ${String(parsed.createdAt)}`);
  }

  if (typeof parsed.dbObjectName !== 'string' || !DB_OBJECT_NAME_REGEX.test(parsed.dbObjectName)) {
    errors.push(
      `Invalid dbObjectName: ${String(parsed.dbObjectName)} (expected db_<ts>_<hex>.enc)`
    );
  }

  if (
    typeof parsed.dbCipherSizeBytes !== 'number' ||
    !Number.isInteger(parsed.dbCipherSizeBytes) ||
    parsed.dbCipherSizeBytes <= 0
  ) {
    errors.push(`Invalid dbCipherSizeBytes: ${String(parsed.dbCipherSizeBytes)}`);
  }

  if (typeof parsed.legacyMediaComplete !== 'boolean') {
    errors.push(`Invalid legacyMediaComplete: ${String(parsed.legacyMediaComplete)} (expected boolean)`);
  }

  if (!hasNonEmptyString(parsed.encryptedMetaB64)) {
    errors.push('Missing or empty encryptedMetaB64');
  }
  if (!hasNonEmptyString(parsed.encryptedMetaIvB64)) {
    errors.push('Missing or empty encryptedMetaIvB64');
  }

  if (errors.length > 0) {
    return { valid: false, errors };
  }

  return {
    valid: true,
    errors: [],
    manifest: {
      version: parsed.version,
      saltB64: parsed.saltB64,
      keyGen,
      kdfIterations: parsed.kdfIterations,
      createdAt: parsed.createdAt,
      dbObjectName: parsed.dbObjectName,
      dbCipherSizeBytes: parsed.dbCipherSizeBytes,
      legacyMediaComplete: parsed.legacyMediaComplete,
      encryptedMetaB64: parsed.encryptedMetaB64,
      encryptedMetaIvB64: parsed.encryptedMetaIvB64,
    },
  };
}

export function isIndividualChat(chatData) {
  return Boolean(chatData && chatData.type === 'INDIVIDUAL');
}

/**
 * True if a Firestore message document in a 1:1 chat carries plaintext content or
 * plaintext metadata and should be scrubbed.
 *
 * Preserves:
 *   - Always-plaintext structured messages (POLL, LIST, CALL, TIMER)
 *   - Phase-2+ encrypted docs (ciphertext != null && no plaintext content/metadata)
 *   - Empty deleted tombstones (deletedAt != null && empty/null content && no plaintext metadata)
 */
export function isPlaintextMessage(data) {
  if (!data || typeof data !== 'object') return false;
  if (STRUCTURED_PLAINTEXT_TYPES.has(data.type)) return false;

  const isTombstone = data.deletedAt !== undefined && data.deletedAt !== null;
  const hasNonEmptyContent = hasNonEmptyString(data.content);
  const hasPlainContentField = data.content !== undefined && data.content !== null;
  const hasPlaintextMetadata =
    hasNonEmptyString(data.mediaUrl) ||
    hasNonEmptyString(data.mediaThumbnailUrl) ||
    hasNonEmptyString(data.fileName) ||
    typeof data.latitude === 'number' ||
    typeof data.longitude === 'number' ||
    hasNonEmptyString(data.stickerId) ||
    hasNonEmptyString(data.stickerPackId);

  if (isTombstone && !hasNonEmptyContent && !hasPlaintextMetadata) {
    return false;
  }

  return hasPlainContentField || hasPlaintextMetadata;
}

/**
 * Matches MessageDao.getLegacyMediaMessages() + BackupManager's Klipy filter:
 * non-deleted legacy media message (including Phase-1 hybrid docs that have ciphertext
 * alongside an unencrypted mediaUrl) whose blob is expected under
 * backups/{uid}/legacy-media/{keyGen}/{messageId}.enc before scrubbing.
 */
export function needsLegacyMediaBackup(data) {
  if (!data || typeof data !== 'object') return false;
  if (data.deletedAt !== undefined && data.deletedAt !== null) return false;
  if (!LEGACY_MEDIA_MESSAGE_TYPES.has(data.type)) return false;
  if (!hasNonEmptyString(data.mediaUrl)) return false;
  if (isKlipyUrl(data.mediaUrl)) return false;
  if (isEncryptedMediaUrl(data.mediaUrl)) return false;
  return true;
}

/**
 * True if a 1:1 chat's lastMessageContent holds user plaintext and should be rewritten
 * to "Message". Leaves empty/null previews and FirestoreMessageSource's type-only
 * placeholders untouched.
 */
export function needsPreviewScrub(lastMessageContent) {
  if (typeof lastMessageContent !== 'string' || lastMessageContent.length === 0) {
    return false;
  }
  return !SAFE_PREVIEW_PLACEHOLDERS.has(lastMessageContent);
}

/**
 * True if a Firebase Storage object path is a legacy unencrypted 1:1 chat media blob
 * eligible for deletion. Never matches .enc files, group/broadcast chat media,
 * backups/, avatars/, or stickers/.
 */
export function isScrubbableStorageObject(objectName, individualChatIds) {
  if (!hasNonEmptyString(objectName)) return false;
  for (const prefix of PROTECTED_STORAGE_PREFIXES) {
    if (objectName.startsWith(prefix)) return false;
  }
  if (!objectName.startsWith('media/')) return false;
  if (objectName.endsWith('/')) return false;
  if (objectName.endsWith('.enc')) return false;

  const rest = objectName.slice('media/'.length);
  const slashIdx = rest.indexOf('/');
  if (slashIdx <= 0 || slashIdx === rest.length - 1) return false;

  const chatId = rest.slice(0, slashIdx);
  return individualChatIds.has(chatId);
}

function readOptionValue(argv, index, flag, description) {
  const next = argv[index + 1];
  if (next === undefined || next.startsWith('-')) {
    throw new Error(`${flag} requires ${description}`);
  }
  return next;
}

function parseUidList(raw, flagName) {
  const seen = new Set();
  const uids = [];
  for (const part of raw.split(',')) {
    const uid = part.trim();
    if (!uid) continue;
    if (!isValidUid(uid)) {
      throw new Error(`Invalid UID in ${flagName}: "${uid}"`);
    }
    if (!seen.has(uid)) {
      seen.add(uid);
      uids.push(uid);
    }
  }
  return uids;
}

/**
 * Parses CLI arguments. Defaults to dry-run; rejects conflicting or unknown flags.
 */
export function parseArgs(argv) {
  let execute = false;
  let dryRunFlag = false;
  let help = false;
  let usersRaw = null;
  let allowUnverifiedUsersRaw = null;
  let allowOrphanedChats = false;
  let bucket = null;
  let credentials = null;

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      help = true;
    } else if (arg === '--dry-run') {
      dryRunFlag = true;
    } else if (arg === '--execute') {
      execute = true;
    } else if (arg === '--users') {
      usersRaw = readOptionValue(argv, i++, '--users', 'a comma-separated list of UIDs');
    } else if (arg.startsWith('--users=')) {
      usersRaw = arg.slice('--users='.length);
    } else if (arg === '--allow-unverified-users') {
      allowUnverifiedUsersRaw = readOptionValue(
        argv,
        i++,
        '--allow-unverified-users',
        'a comma-separated list of UIDs'
      );
    } else if (arg.startsWith('--allow-unverified-users=')) {
      allowUnverifiedUsersRaw = arg.slice('--allow-unverified-users='.length);
    } else if (arg === '--allow-orphaned-chats') {
      allowOrphanedChats = true;
    } else if (arg === '--bucket') {
      bucket = readOptionValue(argv, i++, '--bucket', 'a storage bucket name');
    } else if (arg.startsWith('--bucket=')) {
      bucket = arg.slice('--bucket='.length);
    } else if (arg === '--credentials') {
      credentials = readOptionValue(argv, i++, '--credentials', 'a service account JSON path');
    } else if (arg.startsWith('--credentials=')) {
      credentials = arg.slice('--credentials='.length);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (help) {
    return {
      help: true,
      execute: false,
      dryRun: true,
      users: [],
      allowUnverifiedUsers: [],
      allowOrphanedChats: false,
      bucket,
      credentials,
    };
  }

  if (execute && dryRunFlag) {
    throw new Error('Cannot pass both --dry-run and --execute');
  }

  if (!hasNonEmptyString(usersRaw)) {
    throw new Error('--users <uid1,uid2> is required so every active user backup can be verified');
  }

  const users = parseUidList(usersRaw, '--users');
  if (users.length === 0) {
    throw new Error('--users must contain at least one non-empty UID');
  }

  const allowUnverifiedUsers =
    allowUnverifiedUsersRaw !== null
      ? parseUidList(allowUnverifiedUsersRaw, '--allow-unverified-users')
      : [];
  if (allowUnverifiedUsersRaw !== null && allowUnverifiedUsers.length === 0) {
    throw new Error('--allow-unverified-users must contain at least one non-empty UID');
  }

  const userSet = new Set(users);
  const overlap = allowUnverifiedUsers.filter((uid) => userSet.has(uid));
  if (overlap.length > 0) {
    throw new Error(
      `UID(s) cannot appear in both --users and --allow-unverified-users: ${overlap.join(', ')}`
    );
  }

  return {
    help: false,
    execute,
    dryRun: !execute,
    users,
    allowUnverifiedUsers,
    allowOrphanedChats,
    bucket,
    credentials,
  };
}

/**
 * Pure planner: determines exactly which 1:1 message docs, 1:1 chat previews, and
 * 1:1 legacy Storage objects would be touched, leaving group/broadcast chats intact.
 */
export function buildScrubPlan({ chats, messagesByChat, storageObjectsByChat }) {
  const individualChatIds = new Set();
  const skippedNonIndividualChats = [];
  const chatPlans = [];

  let totalMessagesToDelete = 0;
  let totalMessagesPreserved = 0;
  let totalPreviewsToScrub = 0;
  let totalStorageObjectsToDelete = 0;
  let totalStorageObjectsPreserved = 0;
  let totalBytesToFree = 0;

  for (const chat of chats) {
    if (!isIndividualChat(chat.data)) {
      skippedNonIndividualChats.push({
        id: chat.id,
        type: chat.data?.type ?? 'UNKNOWN',
      });
      continue;
    }
    individualChatIds.add(chat.id);
  }

  for (const chat of chats) {
    if (!individualChatIds.has(chat.id)) continue;

    const participants = Array.isArray(chat.data?.participants)
      ? chat.data.participants.filter((p) => typeof p === 'string' && p.length > 0)
      : [];

    const rawMessages = messagesByChat.get(chat.id) ?? [];
    const plaintextMessages = [];
    let preservedMessageCount = 0;
    let latestPlaintextTimestamp = 0;

    for (const msg of rawMessages) {
      if (isPlaintextMessage(msg.data)) {
        const ts = toEpochMs(msg.data?.timestamp);
        const editedAt = toEpochMs(msg.data?.editedAt);
        const effectiveTimestamp = Math.max(ts, editedAt);
        if (effectiveTimestamp > latestPlaintextTimestamp) {
          latestPlaintextTimestamp = effectiveTimestamp;
        }
        plaintextMessages.push({
          id: msg.id,
          chatId: chat.id,
          timestamp: ts,
          editedAt,
          effectiveTimestamp,
          needsLegacyMedia: needsLegacyMediaBackup(msg.data),
        });
      } else {
        preservedMessageCount++;
      }
    }

    const previewNeedsScrub = needsPreviewScrub(chat.data?.lastMessageContent);

    const rawStorage = storageObjectsByChat.get(chat.id) ?? [];
    const legacyStorageObjects = [];
    let preservedStorageCount = 0;
    let chatBytesToFree = 0;

    for (const obj of rawStorage) {
      if (isScrubbableStorageObject(obj.name, individualChatIds)) {
        const size = Number.isFinite(Number(obj.size)) && Number(obj.size) > 0 ? Number(obj.size) : 0;
        legacyStorageObjects.push({ name: obj.name, size });
        chatBytesToFree += size;
      } else {
        preservedStorageCount++;
      }
    }

    totalMessagesToDelete += plaintextMessages.length;
    totalMessagesPreserved += preservedMessageCount;
    if (previewNeedsScrub) totalPreviewsToScrub++;
    totalStorageObjectsToDelete += legacyStorageObjects.length;
    totalStorageObjectsPreserved += preservedStorageCount;
    totalBytesToFree += chatBytesToFree;

    chatPlans.push({
      chatId: chat.id,
      participants,
      plaintextMessages,
      preservedMessageCount,
      latestPlaintextTimestamp,
      previewNeedsScrub,
      currentPreview: chat.data?.lastMessageContent ?? null,
      legacyStorageObjects,
      preservedStorageCount,
      bytesToFree: chatBytesToFree,
    });
  }

  return {
    individualChatIds,
    chatPlans,
    skippedNonIndividualChats,
    totals: {
      individualChats: chatPlans.length,
      skippedNonIndividualChats: skippedNonIndividualChats.length,
      messagesToDelete: totalMessagesToDelete,
      messagesPreserved: totalMessagesPreserved,
      previewsToScrub: totalPreviewsToScrub,
      storageObjectsToDelete: totalStorageObjectsToDelete,
      storageObjectsPreserved: totalStorageObjectsPreserved,
      bytesToFree: totalBytesToFree,
    },
  };
}

/**
 * Evaluates the mandatory backup safety gate before any scrub mutation may run.
 */
export function evaluateSafetyGate({
  users,
  plan,
  backupsByUser,
  allowUnverifiedUsers = [],
  allowOrphanedChats = false,
}) {
  const failures = [];
  const uncoveredChats = [];
  const waivedChats = [];
  const userReports = [];

  if (!Array.isArray(users) || users.length === 0) {
    failures.push('No user UIDs supplied in --users');
    return { ok: false, failures, userReports, uncoveredChats, waivedChats };
  }

  const userSet = new Set(users);
  const waivedUserSet = new Set(Array.isArray(allowUnverifiedUsers) ? allowUnverifiedUsers : []);

  // 1. Every 1:1 chat with scrubbable data must have all participants covered by --users
  //    (unless explicitly waived via --allow-unverified-users or --allow-orphaned-chats).
  for (const chatPlan of plan.chatPlans) {
    const hasWork =
      chatPlan.plaintextMessages.length > 0 ||
      chatPlan.previewNeedsScrub ||
      chatPlan.legacyStorageObjects.length > 0;
    if (!hasWork) continue;

    if (chatPlan.participants.length === 0) {
      if (allowOrphanedChats) {
        waivedChats.push({ chatId: chatPlan.chatId, waivedUids: [], orphaned: true });
      } else {
        const msg = `1:1 chat ${chatPlan.chatId} has scrubbable data but no valid participants array`;
        uncoveredChats.push({ chatId: chatPlan.chatId, missingUids: [] });
        failures.push(msg);
      }
      continue;
    }

    const missingUids = chatPlan.participants.filter(
      (uid) => !userSet.has(uid) && !waivedUserSet.has(uid)
    );
    const waivedUids = chatPlan.participants.filter(
      (uid) => !userSet.has(uid) && waivedUserSet.has(uid)
    );
    if (missingUids.length > 0) {
      uncoveredChats.push({ chatId: chatPlan.chatId, missingUids });
      failures.push(
        `1:1 chat ${chatPlan.chatId} has scrubbable data for unverified participant(s): ${missingUids.join(', ')} (add them to --users)`
      );
    } else if (waivedUids.length > 0) {
      waivedChats.push({ chatId: chatPlan.chatId, waivedUids, orphaned: false });
    }
  }

  // 2. Verify each user's encrypted backup manifest, versioned db blob, freshness, and legacy media blobs.
  for (const uid of users) {
    const userErrors = [];
    const addUserError = (msg) => {
      userErrors.push(msg);
      failures.push(`[${uid}] ${msg}`);
    };
    const pushFailedReport = (latestTs, expectedIds) => {
      userReports.push({
        uid,
        ok: false,
        manifest: null,
        latestPlaintextTimestamp: latestTs,
        expectedLegacyMediaCount: expectedIds.size,
        verifiedLegacyMediaCount: 0,
        missingLegacyMediaIds: [...expectedIds],
        errors: userErrors,
      });
    };

    const backup = backupsByUser.get(uid) ?? null;

    let latestPlaintextTimestamp = 0;
    const expectedLegacyMediaIds = new Set();

    for (const chatPlan of plan.chatPlans) {
      if (!chatPlan.participants.includes(uid)) continue;
      if (chatPlan.latestPlaintextTimestamp > latestPlaintextTimestamp) {
        latestPlaintextTimestamp = chatPlan.latestPlaintextTimestamp;
      }
      for (const msg of chatPlan.plaintextMessages) {
        if (msg.needsLegacyMedia) {
          expectedLegacyMediaIds.add(msg.id);
        }
      }
    }

    if (!backup || backup.manifestRaw == null) {
      addUserError(`Missing backups/${uid}/manifest.json`);
      pushFailedReport(latestPlaintextTimestamp, expectedLegacyMediaIds);
      continue;
    }

    const validation = validateManifest(backup.manifestRaw);
    if (!validation.valid) {
      for (const err of validation.errors) {
        addUserError(err);
      }
      pushFailedReport(latestPlaintextTimestamp, expectedLegacyMediaIds);
      continue;
    }

    const { manifest } = validation;

    if (manifest.legacyMediaComplete !== true) {
      addUserError('manifest.legacyMediaComplete is false (backup did not finish all legacy media)');
    }

    const dbPath = `backups/${uid}/${manifest.dbObjectName}`;
    if (!backup.dbObjectExists) {
      addUserError(`Backup DB blob missing: ${dbPath}`);
    } else if (!Number.isFinite(backup.dbObjectSize) || backup.dbObjectSize <= 0) {
      addUserError(`Backup DB blob is empty: ${dbPath}`);
    } else if (backup.dbObjectSize !== manifest.dbCipherSizeBytes) {
      addUserError(
        `Backup DB blob size mismatch for ${dbPath}: Storage has ${backup.dbObjectSize} bytes, ` +
          `manifest declares ${manifest.dbCipherSizeBytes} bytes`
      );
    }

    if (latestPlaintextTimestamp > 0 && manifest.createdAt <= latestPlaintextTimestamp) {
      addUserError(
        `Backup is stale: manifest.createdAt (${manifest.createdAt} / ${new Date(manifest.createdAt).toISOString()}) ` +
          `is not newer than latest 1:1 plaintext message (${latestPlaintextTimestamp} / ${new Date(latestPlaintextTimestamp).toISOString()})`
      );
    }

    // Verify expected legacy-media/{keyGen}/{messageId}.enc blobs exist and are non-empty.
    const legacyPrefix = `backups/${uid}/legacy-media/${manifest.keyGen}/`;
    const presentLegacyIds = new Set();
    for (const obj of backup.legacyMediaObjects ?? []) {
      if (!obj || typeof obj.name !== 'string') continue;
      if (!obj.name.startsWith(legacyPrefix) || !obj.name.endsWith('.enc')) continue;
      const size = Number(obj.size);
      if (!Number.isFinite(size) || size <= 0) continue;
      const rel = obj.name.slice(legacyPrefix.length);
      if (rel.includes('/')) continue;
      const messageId = rel.slice(0, -'.enc'.length);
      if (messageId.length > 0) {
        presentLegacyIds.add(messageId);
      }
    }

    const missingLegacyMediaIds = [];
    for (const msgId of expectedLegacyMediaIds) {
      if (!presentLegacyIds.has(msgId)) {
        missingLegacyMediaIds.push(msgId);
      }
    }

    if (missingLegacyMediaIds.length > 0) {
      const sample = missingLegacyMediaIds.slice(0, 5).join(', ');
      const suffix = missingLegacyMediaIds.length > 5 ? ` (+${missingLegacyMediaIds.length - 5} more)` : '';
      addUserError(
        `Missing ${missingLegacyMediaIds.length} legacy media blob(s) under ${legacyPrefix}: ${sample}${suffix}`
      );
    }

    userReports.push({
      uid,
      ok: userErrors.length === 0,
      manifest,
      dbObjectSize: backup.dbObjectSize ?? 0,
      latestPlaintextTimestamp,
      expectedLegacyMediaCount: expectedLegacyMediaIds.size,
      verifiedLegacyMediaCount: expectedLegacyMediaIds.size - missingLegacyMediaIds.length,
      missingLegacyMediaIds,
      errors: userErrors,
    });
  }

  return {
    ok: failures.length === 0,
    failures,
    userReports,
    uncoveredChats,
    waivedChats,
  };
}

/**
 * Executes the scrub plan (or returns a zero-mutation summary in dry-run).
 * Hard-fails before any mutation if execute === true and safetyResult.ok === false.
 */
export async function executeScrub({
  plan,
  safetyResult,
  execute,
  batchSize = DEFAULT_FIRESTORE_BATCH_SIZE,
  verifyStorageDeletePermission = null,
  deleteMessageBatch,
  scrubChatPreview,
  deleteStorageObject,
  log = () => {},
}) {
  if (!execute) {
    return {
      executed: false,
      docsDeleted: 0,
      previewsScrubbed: 0,
      storageObjectsDeleted: 0,
      bytesFreed: 0,
    };
  }

  if (!safetyResult?.ok) {
    const reason = safetyResult?.failures?.join('; ') || 'Safety gate did not pass';
    throw new Error(`Refusing to execute scrub — safety gate failed: ${reason}`);
  }

  // Preflight Storage delete permission before mutating any Firestore document so a
  // Storage-read-only credential (such as the sticker-namer service account) cannot
  // leave a half-scrubbed state where Firestore docs are deleted and Storage fails 403.
  if (
    plan.totals.storageObjectsToDelete > 0 &&
    typeof verifyStorageDeletePermission === 'function'
  ) {
    await verifyStorageDeletePermission();
  }

  const effectiveBatchSize = Math.max(1, Math.min(batchSize, 499));
  let docsDeleted = 0;
  let previewsScrubbed = 0;
  let storageObjectsDeleted = 0;
  let bytesFreed = 0;

  for (const chatPlan of plan.chatPlans) {
    // 1. Delete plaintext message documents in batches.
    for (let i = 0; i < chatPlan.plaintextMessages.length; i += effectiveBatchSize) {
      const batch = chatPlan.plaintextMessages.slice(i, i + effectiveBatchSize);
      await deleteMessageBatch(chatPlan.chatId, batch.map((m) => m.id));
      docsDeleted += batch.length;
      log(`  [chat ${chatPlan.chatId}] Deleted ${batch.length} plaintext message(s)`);
    }

    // 2. Scrub plaintext lastMessageContent preview on 1:1 chat doc.
    if (chatPlan.previewNeedsScrub) {
      await scrubChatPreview(chatPlan.chatId, 'Message');
      previewsScrubbed++;
      log(`  [chat ${chatPlan.chatId}] Rewrote lastMessageContent -> "Message"`);
    }

    // 3. Delete legacy non-.enc Storage objects for this 1:1 chat.
    for (const obj of chatPlan.legacyStorageObjects) {
      await deleteStorageObject(obj.name);
      storageObjectsDeleted++;
      bytesFreed += obj.size;
      log(`  [storage] Deleted ${obj.name} (${formatBytes(obj.size)})`);
    }
  }

  return {
    executed: true,
    docsDeleted,
    previewsScrubbed,
    storageObjectsDeleted,
    bytesFreed,
  };
}

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function formatReport({ args, plan, safetyResult, execution }) {
  const lines = [];
  lines.push('=== FireStream Plaintext Scrub ===');
  lines.push(`Mode: ${args.execute ? 'EXECUTE' : 'DRY-RUN (no mutations)'}`);
  lines.push(`Verified users: ${args.users.join(', ')}`);
  if (Array.isArray(args.allowUnverifiedUsers) && args.allowUnverifiedUsers.length > 0) {
    lines.push(`Waived unverified users: ${args.allowUnverifiedUsers.join(', ')}`);
  }
  if (args.allowOrphanedChats) {
    lines.push('Waived orphaned 1:1 chats: enabled');
  }
  lines.push('');
  lines.push('--- Backup Safety Gate ---');
  for (const report of safetyResult.userReports) {
    const status = report.ok ? 'PASS' : 'FAIL';
    lines.push(`  [${status}] ${report.uid}`);
    if (report.manifest) {
      lines.push(
        `    manifest: v${report.manifest.version}, createdAt=${new Date(report.manifest.createdAt).toISOString()} (${report.manifest.createdAt})`
      );
      lines.push(
        `    db blob:  backups/${report.uid}/${report.manifest.dbObjectName} (${formatBytes(report.dbObjectSize ?? 0)})`
      );
      lines.push(
        `    legacy:   complete=${report.manifest.legacyMediaComplete}, keyGen=${report.manifest.keyGen}, ` +
          `verified=${report.verifiedLegacyMediaCount}/${report.expectedLegacyMediaCount}`
      );
    }
    if (report.latestPlaintextTimestamp > 0) {
      lines.push(
        `    latest 1:1 plaintext ts: ${new Date(report.latestPlaintextTimestamp).toISOString()} (${report.latestPlaintextTimestamp})`
      );
    }
    for (const err of report.errors) {
      lines.push(`    ERROR: ${err}`);
    }
  }
  for (const waived of safetyResult.waivedChats ?? []) {
    const detail = waived.orphaned
      ? 'orphaned chat (missing participants array — waived)'
      : `waived unverified participants: ${waived.waivedUids.join(', ')}`;
    lines.push(`  [WAIVED] 1:1 chat ${waived.chatId}: ${detail}`);
  }
  for (const uncovered of safetyResult.uncoveredChats) {
    const detail = uncovered.missingUids.length
      ? `unverified participants: ${uncovered.missingUids.join(', ')}`
      : 'missing participants array';
    lines.push(`  [FAIL] 1:1 chat ${uncovered.chatId}: ${detail}`);
  }
  lines.push(`Safety gate overall: ${safetyResult.ok ? 'PASSED' : 'FAILED'}`);
  lines.push('');
  lines.push('--- Scope & Plan Summary ---');
  lines.push(
    `  1:1 chats scanned:          ${plan.totals.individualChats} ` +
      `(non-1:1 group/broadcast chats untouched: ${plan.totals.skippedNonIndividualChats})`
  );
  lines.push(
    `  Plaintext docs to delete:   ${plan.totals.messagesToDelete} ` +
      `(encrypted/tombstone docs untouched: ${plan.totals.messagesPreserved})`
  );
  lines.push(`  1:1 chat previews to scrub: ${plan.totals.previewsToScrub}`);
  lines.push(
    `  Legacy Storage objects:     ${plan.totals.storageObjectsToDelete} (${formatBytes(plan.totals.bytesToFree)}) ` +
      `(.enc objects untouched: ${plan.totals.storageObjectsPreserved})`
  );

  if (execution?.executed) {
    lines.push('');
    lines.push('--- Execution Result ---');
    lines.push(`  Firestore message docs deleted: ${execution.docsDeleted}`);
    lines.push(`  Chat previews scrubbed:         ${execution.previewsScrubbed}`);
    lines.push(`  Storage objects deleted:        ${execution.storageObjectsDeleted}`);
    lines.push(
      `  Storage bytes freed:            ${formatBytes(execution.bytesFreed)} (${execution.bytesFreed} bytes)`
    );
  }
  return lines.join('\n');
}

function printUsage() {
  console.log(`Usage:
  node scripts/scrub-plaintext.mjs --users <uid1,uid2> [--allow-unverified-users <uid3,...>] [--allow-orphaned-chats] [--dry-run | --execute] [--bucket <bucket>] [--credentials <key.json>]

Options:
  --users <uid1,uid2>               Comma-separated Firebase Auth UIDs whose backups must be verified (required).
  --allow-unverified-users <uids>   Comma-separated UIDs whose backup check is explicitly waived.
  --allow-orphaned-chats            Allow scrubbing 1:1 chats with an empty/missing participants array.
  --dry-run                         Report safety gate and scrub plan without modifying anything (default).
  --execute                         Execute the scrub (hard-fails if any safety gate check fails).
  --bucket <name>                   Firebase Storage bucket (or set FIREBASE_STORAGE_BUCKET / BUCKET).
  --credentials <path>              Service account JSON key path (or set GOOGLE_APPLICATION_CREDENTIALS / KEY_FILE).
  -h, --help                        Show this help message.
`);
}

function loadFirebaseAdmin() {
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [
    path.join(scriptDir, 'package.json'),
    path.resolve(scriptDir, '../functions/package.json'),
  ];
  for (const anchor of candidates) {
    try {
      const req = createRequire(anchor);
      const appMod = req('firebase-admin/app');
      const firestoreMod = req('firebase-admin/firestore');
      const storageMod = req('firebase-admin/storage');
      return { ...appMod, ...firestoreMod, ...storageMod };
    } catch {
      // Try next resolution root.
    }
  }
  throw new Error(
    'Could not load firebase-admin. Run "cd functions && npm install" first.'
  );
}

function resolveBucket(cliBucket) {
  if (hasNonEmptyString(cliBucket)) return cliBucket.trim();
  if (hasNonEmptyString(process.env.FIREBASE_STORAGE_BUCKET)) {
    return process.env.FIREBASE_STORAGE_BUCKET.trim();
  }
  if (hasNonEmptyString(process.env.BUCKET)) {
    return process.env.BUCKET.trim();
  }
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const googleServicesPath = path.resolve(scriptDir, '../app/google-services.json');
  if (existsSync(googleServicesPath)) {
    try {
      const parsed = JSON.parse(readFileSync(googleServicesPath, 'utf8'));
      const gsBucket = parsed?.project_info?.storage_bucket;
      if (hasNonEmptyString(gsBucket)) return gsBucket.trim();
    } catch {
      // Ignore malformed google-services.json and fall through to explicit error.
    }
  }
  throw new Error(
    'Storage bucket not specified. Pass --bucket <name> or set FIREBASE_STORAGE_BUCKET / BUCKET.'
  );
}

function resolveCredentialPath(cliCredentials) {
  if (hasNonEmptyString(cliCredentials)) return cliCredentials.trim();
  if (hasNonEmptyString(process.env.GOOGLE_APPLICATION_CREDENTIALS)) {
    return process.env.GOOGLE_APPLICATION_CREDENTIALS.trim();
  }
  if (hasNonEmptyString(process.env.KEY_FILE)) {
    return process.env.KEY_FILE.trim();
  }
  return null;
}

async function fetchUserBackupState(bucket, uid) {
  const manifestFile = bucket.file(`backups/${uid}/manifest.json`);
  const [manifestExists] = await manifestFile.exists();
  if (!manifestExists) {
    return {
      manifestRaw: null,
      dbObjectExists: false,
      dbObjectSize: 0,
      legacyMediaObjects: [],
    };
  }

  const [manifestBuf] = await manifestFile.download();
  const manifestRaw = manifestBuf.toString('utf8');
  const parsed = validateManifest(manifestRaw);
  if (!parsed.valid) {
    return {
      manifestRaw,
      dbObjectExists: false,
      dbObjectSize: 0,
      legacyMediaObjects: [],
    };
  }

  const { dbObjectName, keyGen } = parsed.manifest;
  const dbFile = bucket.file(`backups/${uid}/${dbObjectName}`);
  const [dbObjectExists] = await dbFile.exists();
  let dbObjectSize = 0;
  if (dbObjectExists) {
    const [meta] = await dbFile.getMetadata();
    dbObjectSize = Number(meta?.size ?? 0);
  }

  const legacyPrefix = `backups/${uid}/legacy-media/${keyGen}/`;
  const [legacyFiles] = await bucket.getFiles({ prefix: legacyPrefix, autoPaginate: true });
  const legacyMediaObjects = legacyFiles.map((f) => ({
    name: f.name,
    size: Number(f.metadata?.size ?? 0),
  }));

  return {
    manifestRaw,
    dbObjectExists,
    dbObjectSize,
    legacyMediaObjects,
  };
}

async function main(argv) {
  let args;
  try {
    args = parseArgs(argv);
  } catch (err) {
    console.error(`Error: ${err.message}\n`);
    printUsage();
    process.exit(1);
  }

  if (args.help) {
    printUsage();
    process.exit(0);
  }

  const bucketName = resolveBucket(args.bucket);
  const credentialPath = resolveCredentialPath(args.credentials);
  const admin = loadFirebaseAdmin();

  const credential = credentialPath
    ? admin.cert(JSON.parse(readFileSync(credentialPath, 'utf8')))
    : admin.applicationDefault();

  const app = admin.initializeApp({
    credential,
    storageBucket: bucketName,
  });

  const db = admin.getFirestore(app);
  const bucket = admin.getStorage(app).bucket(bucketName);

  // 1. Scan all chats; only 1:1 (INDIVIDUAL) chats are inspected for messages & Storage media.
  const chatsSnap = await db.collection('chats').get();
  const chats = chatsSnap.docs.map((doc) => ({ id: doc.id, data: doc.data() }));

  const messagesByChat = new Map();
  const storageObjectsByChat = new Map();

  for (const chat of chats) {
    if (!isIndividualChat(chat.data)) continue;

    const msgsSnap = await db.collection('chats').doc(chat.id).collection('messages').get();
    messagesByChat.set(
      chat.id,
      msgsSnap.docs.map((doc) => ({ id: doc.id, data: doc.data() }))
    );

    const [files] = await bucket.getFiles({ prefix: `media/${chat.id}/`, autoPaginate: true });
    storageObjectsByChat.set(
      chat.id,
      files.map((f) => ({ name: f.name, size: Number(f.metadata?.size ?? 0) }))
    );
  }

  const plan = buildScrubPlan({ chats, messagesByChat, storageObjectsByChat });

  // 2. Fetch backup manifests, versioned DB blobs, and legacy-media/{keyGen}/ listings per user.
  const backupsByUser = new Map();
  for (const uid of args.users) {
    backupsByUser.set(uid, await fetchUserBackupState(bucket, uid));
  }

  const safetyResult = evaluateSafetyGate({
    users: args.users,
    plan,
    backupsByUser,
    allowUnverifiedUsers: args.allowUnverifiedUsers,
    allowOrphanedChats: args.allowOrphanedChats,
  });

  if (!safetyResult.ok) {
    console.log(formatReport({ args, plan, safetyResult, execution: null }));
    if (args.execute) {
      console.error('\nABORTED: Safety gate failed. Zero Firestore docs or Storage objects were modified.');
    } else {
      console.error('\nDRY-RUN COMPLETE: Safety gate failed — fix the issues above before running --execute.');
    }
    process.exit(2);
  }

  const execution = await executeScrub({
    plan,
    safetyResult,
    execute: args.execute,
    verifyStorageDeletePermission: async () => {
      const [perms] = await bucket.iam.testPermissions(['storage.objects.delete']);
      if (!perms || perms['storage.objects.delete'] !== true) {
        throw new Error(
          `Active credentials lack "storage.objects.delete" permission on bucket "${bucketName}". ` +
            'Aborting before modifying Firestore — pass a full Firebase Admin SDK credential via --credentials.'
        );
      }
    },
    deleteMessageBatch: async (chatId, messageIds) => {
      const batch = db.batch();
      const col = db.collection('chats').doc(chatId).collection('messages');
      for (const id of messageIds) {
        batch.delete(col.doc(id));
      }
      await batch.commit();
    },
    scrubChatPreview: async (chatId, preview) => {
      await db.collection('chats').doc(chatId).update({ lastMessageContent: preview });
    },
    deleteStorageObject: async (objectName) => {
      await bucket.file(objectName).delete({ ignoreNotFound: true });
    },
    log: (line) => console.log(line),
  });

  console.log(formatReport({ args, plan, safetyResult, execution }));
  process.exit(0);
}

const isDirectRun =
  process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isDirectRun) {
  main(process.argv.slice(2)).catch((err) => {
    console.error(`Fatal error: ${err?.stack ?? err}`);
    process.exit(1);
  });
}
