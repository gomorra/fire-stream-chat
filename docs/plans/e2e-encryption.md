# End-to-End Encryption: Full 1:1 Media, Encrypted Backup, and Plaintext Scrub

Status: approved by the owner on 2026-11-25. Running interactively inside Antigravity on branch `feat/e2e-encryption` (one phase per context window, pausing at each `‖` hardware checkpoint).

Order: 1 → 2 → 3 ‖ 4 → 5 → 6 ‖ 7 → 8 ‖ 9

### Antigravity execution & review protocol

- **Build & test gate per step:** `./gradlew :app:testFirebaseDebugUnitTest` + `./gradlew assembleFirebaseDebug`, plus `./gradlew test` whenever shared backend interfaces (`KeySource`, `MessageSource`, `StorageSource`) change or at phase boundaries. Run `cd functions && npm test` and `cd firestore-rules-tests && npm test` whenever touching Cloud Functions or Firestore rules.
- **Post-step review:** Run two parallel fresh-context review subagents before committing — (1) **Correctness & Crypto Reviewer** (ratchet races, lock ordering, partial failures, migration safety) and (2) **Standards & Spec Reviewer** (`.agents/skills/code-review` against this plan and `docs/PATTERNS.md`).
- **Firebase rules:** Delegate `firestore.rules` edits to `firestore-rules-author` and audit `firestore.rules` / `storage.rules` with `firebase-security-rules-auditor`.
- **State tracking:** Record a concise `**Shipped**` note under each completed step before moving on; use `/compact` (or a fresh chat) after each `‖` checkpoint.

## Context

1:1 Signal encryption exists in release builds of the `firebase` flavor behind a Settings toggle that defaults to off, but it is not safe to enable today for four reasons:

1. `AppDatabase` still uses `fallbackToDestructiveMigration()`. Because Signal ratchets are forward-only, any future Room schema bump wipes decrypted local history and turns re-synced messages into `[Encrypted message — unable to decrypt]`.
2. Three known Signal defects remain open from `docs/plans/offline-outbox.md` ("Before end-to-end encryption is switched on"): a single fixed-id one-time pre-key, an unlocked race across the three receive paths, and a stale-bundle race on peer re-registration.
3. Only the text body (`content`) is encrypted. Images, videos, video thumbnails, voice notes, documents, GIFs, file metadata, locations, sticker references, and message edits all travel in plaintext.
4. Once history is encrypted, a stolen or wiped phone cannot recover messages from Firestore. Restorability requires a per-user encrypted backup in Firebase Storage, which also makes a one-time scrub of legacy plaintext safe.

## Decisions

Agreed with the owner on 2026-11-25:

| Question | Decision |
|---|---|
| Scope | Option 1: full 1:1 E2EE (text, media, thumbnails, documents, voice, GIFs, locations, edits, hidden sticker refs), encrypted backup in Firebase Storage, and a one-time scrub of legacy plaintext. Group Sender Keys are deferred to a follow-up plan. |
| Stickers | Hide `stickerId`, `stickerPackId`, and `mediaUrl` inside the 1:1 Signal envelope so Firestore does not see which sticker was sent. Keep the shared `stickers/<sha256>` library bucket, "View pack", and `tools/sticker-namer` working as today. |
| Backup protection | User passphrase (PBKDF2-HMAC-SHA256 + AES-256-GCM) stored under `backups/{uid}/` in Firebase Storage. |
| Legacy history migration | Per-phone encrypted backup first (Room DB + legacy unencrypted media under `backups/{uid}/legacy-media/`). Once both active phones confirm a complete backup, run a one-time scrub of old plaintext messages and legacy media from Firestore and Storage. |

Set by this plan, not asked:

- **One media cipher everywhere.** Both encrypted chat media and encrypted backups use the same chunked AES-256-GCM stream cipher (`MediaCrypto`, 64 KiB chunks) so large videos and DB snapshots stream in constant memory and fail fast on tampering or truncation.
- **No double-upload for new encrypted media.** Phase 2+ media already lives encrypted in `media/{chatId}/{messageId}.enc`, and its per-file AES key lives in Room (`messages.mediaKey`). Backups store only the encrypted Room DB plus `legacy-media/` blobs for pre-Phase-2 messages (`mediaKey == null`). On restore, legacy media downloads from `backups/{uid}/legacy-media/` and new media downloads from `media/{chatId}/` on demand.
- **Signal keys are not backed up.** Restoring a backup onto a new phone registers a fresh Signal identity on that device; `SignalManager` already detects peer identity changes and rebuilds sessions cleanly.
- **Backward-compatible wire format.** Old plaintext messages and old unencrypted media remain readable alongside new encrypted messages until scrubbed. Debug builds stay plaintext unless explicitly toggled for testing in unit tests (`MessageWriter`'s injectable `buildEncrypts` flag).
- **`firebase` flavor only.** PocketBase sources follow interface changes with stubs and keep compiling.

Not in this plan:

- Group Sender Keys E2EE (follow-up plan on top of the exact same `MessageEnvelope` and `MediaCrypto`).
- Multi-device linked sessions / web client decryption.
- Safety-number verification screen (`docs/BACKLOG.md`).
- Per-message encrypted sticker blobs (breaks `tools/sticker-namer` and shared pack browsing).

## What exists today (verified 2026-11-25)

- `AppDatabase` (`app/src/main/java/com/firestream/chat/data/local/AppDatabase.kt`) is at version 35 with `exportSchema = false`. `DatabaseModule.kt:36` registers only `MIGRATION_18_19` and calls `fallbackToDestructiveMigration()` on both `AppDatabase` and `SignalDatabase` (version 1).
- `SignalManager.kt` uses a single fixed `preKeyId = 1` (`:66`), replenished only after a successful `PREKEY_TYPE` decrypt (`:174`). `SignalManager.encrypt` (`:93`) fetches the peer's bundle outside `sessionLock(recipientId)`.
- `FirebaseKeySource.kt` writes one `/keyBundles/{uid}` document with a single `preKeyId`/`preKeyPublic`. `firestore.rules:33` allows `read` to any auth user and `write` to `isOwner(uid)`.
- `MessageRepositoryImpl.kt` has two separate decrypt loops (`reconcileRawMessage` at `:479` for listener + `reconcileFromPush`, and `syncChatMessages` at `:1470`) with no per-message lock.
- `FCMService.kt:83-145` launches `reconcileFromPush` and `showNotification` in separate coroutines, so encrypted pushes show no decrypted text.
- `OutboxSender.kt` uploads raw media via `FirebaseStorageSource.uploadMedia` to `media/{chatId}/{messageId}.{ext}` and encrypts only `row.content` via `MessageWriter.encode` (`LOCATION` is excluded in `PLAINTEXT_TYPES`).
- `MessageRepositoryImpl.editMessage` (`:651`) writes `newContent` to Firestore in plaintext.
- Several UI surfaces fall back directly to remote `mediaUrl` / `mediaThumbnailUrl`: `MessageBubble.kt`, `SharedMediaTile.kt`, `ChatScreen.kt`, `ChatMediaGallery.kt`, `ProfileScreen.kt`, `FullscreenVideoPlayer.kt`, `VoiceMessagePlayer.kt`, `SearchResults.kt`, and `ChatViewModel.readableSentImage`.
- `functions/index.js` already uses `onCall` (`getTurnCredentials`, `:24`) and omits `messageContent` from message push payloads (`:236`).
- `firebase.json` declares `firestore` and `functions`; Storage rules are currently managed in the Firebase console (not yet tracked in repo).

## The model

1. **Room is the permanent local archive.** Every schema change from v35 onward ships an explicit tested `Migration(n, n+1)`. Neither `AppDatabase` nor `SignalDatabase` may destructively reset a v35+ / v1+ install.
2. **Pre-keys are a rotating pool claimed atomically.** Each phone stores a batch of 50 one-time pre-keys locally before publishing their public halves under `/keyBundles/{uid}/oneTimePreKeys/{id}`. A callable Cloud Function (`claimPreKeyBundle`) atomically pops one pre-key per session setup and falls back to signed-pre-key + Kyber PQXDH if the pool is temporarily empty.
3. **One locked receive path.** Listener, chat-list sync, and push reconcile all call `reconcileRawMessage` under a per-message `KeyedMutex<String>`. Once a message is decrypted in Room, no second caller can advance the ratchet against it or overwrite it with an error placeholder.
4. **One versioned encrypted envelope (`v: 1`).** For 1:1 encrypted messages, `ciphertext` holds a JSON envelope carrying the body, mentions, emoji sizes, reply target, location coords, sticker ref, and AES-GCM media/thumbnail descriptors. Firestore holds only routing and delivery state (`senderId`, `timestamp`, `type`, `ciphertext`, `signalType`, `editCiphertext`, `editSignalType`, `status`, receipts, reactions, pin/delete/edit timestamps).
5. **Chunked AES-256-GCM media (`MediaCrypto`).** Every uploaded 1:1 media file and video thumbnail is encrypted with a fresh 32-byte key and 12-byte base nonce into 64 KiB authenticated chunks (`media/{chatId}/{messageId}.enc`, `application/octet-stream`). UI components never feed encrypted remote URLs directly to Coil or MediaPlayer.
6. **Passphrase-protected Storage backup (`backups/{uid}/`).** A PBKDF2-HMAC-SHA256 (600,000 iterations) key derived from the user's passphrase encrypts a `VACUUM INTO` snapshot of `fire_stream_chat.db` (`db.enc`) plus any pre-Phase-2 media (`legacy-media/{messageId}.enc`). Restoring on a new phone recovers all text, metadata, and media keys, then downloads media lazily on demand.
7. **Server scrub is gated on verified backups.** A host-side Admin SDK script (`scripts/scrub-plaintext.mjs`) refuses to run `--execute` until every active user UID has a complete backup manifest in Firebase Storage, then removes legacy plaintext Firestore docs and non-`.enc` objects under `media/`.

---

## Phase 1 — Foundation (Safe DB + Rock-Solid Signal)

### Step 1 — Real Room migrations and schema export

Prevent any future Room version bump from wiping decrypted messages or Signal keys.

- Enable Room schema export in `app/build.gradle.kts` (`ksp { arg("room.schemaLocation", "$projectDir/schemas") }`) and set `exportSchema = true` on `AppDatabase` (v35) and `SignalDatabase` (v1). Commit the exported baseline schema JSONs (`app/schemas/`).
- Add `androidx.room:room-testing` to `gradle/libs.versions.toml` and `app/build.gradle.kts` (`testImplementation`).
- In `DatabaseModule.kt`:
  - Replace `.fallbackToDestructiveMigration()` on `AppDatabase` with `.fallbackToDestructiveMigrationFrom(*(1..34).toList().toIntArray())` so v35+ installs can never be wiped silently.
  - Remove `.fallbackToDestructiveMigration()` from `SignalDatabase` completely.
- Add `AppDatabaseMigrationTest` verifying schema load at v35 (and establishing the harness for every subsequent migration in this plan).
- Update `CLAUDE.md` and `docs/PATTERNS.md` § *Room version bump rule* to require an explicit `Migration` + test for every v35+ bump.

**Shipped** (`90997900`): `androidx.room` plugin exports `AppDatabase/35.json` and `SignalDatabase/1.json`, `DatabaseModule` restricts destructive fallback to legacy `AppDatabase` `1..34` and removes it from `SignalDatabase`, and `AppDatabaseMigrationTest` verifies baseline schemas plus runtime refusal on unmigrated versions.

### Step 2 — Rotating pre-key pool and stale-bundle race fix *(rules via `firestore-rules-author` + `firebase-security-rules-auditor`)*

Close both `SignalManager` protocol defects before turning encryption on.

- **Local pre-key pool (`SignalDatabase` v1 → v2, explicit `MIGRATION_1_2`):**
  - Add a tiny `signal_meta(key TEXT PRIMARY KEY, intValue INTEGER)` table (or `nextPreKeyId` column on `signal_identity`) to persist the monotonically increasing next pre-key ID so deleted IDs are never reused.
  - Add `SignalDao.countPreKeys()` and `SignalDao.getAllPreKeyIds()`.
- **Firestore & Cloud Function (`functions/index.js`, `firestore.rules`, `FirebaseKeySource.kt`):**
  - Store one-time pre-keys in `/keyBundles/{uid}/oneTimePreKeys/{preKeyId}` (`preKeyPublic: base64`, `createdAt`). Update `firestore.rules` so only `isOwner(uid)` can read/write `/keyBundles/{uid}/oneTimePreKeys/{preKeyId}` (senders never list or delete them directly).
  - Add callable function `claimPreKeyBundle` in `functions/index.js`: inside a Firestore transaction, reads `/keyBundles/{targetUid}` and pops one document from `oneTimePreKeys` (oldest/first doc, deleting it atomically). If the subcollection is empty, returns the bundle with `preKeyId: null, preKeyPublic: null` (Signal PQXDH still establishes a session via signed pre-key + Kyber last-resort key). Add unit tests in `functions/test/`.
  - Keep `FirebaseKeySource.publishKeys` backward-compatible for the top-level bundle fields (`registrationId`, `identityKey`, `signedPreKey*`, `kyberPreKey*`) while batch-writing newly generated pre-keys to `oneTimePreKeys`.
- **`SignalManager` updates:**
  - Generate an initial batch of 50 one-time pre-keys (`PREKEY_BATCH_SIZE = 50`), **store all 50 in `SignalDatabase` first**, then publish to Firestore.
  - Replenish whenever local remaining pre-keys fall below `PREKEY_LOW_WATERMARK = 20` — checked both on `ensureInitialized()` (every app start) and after every `PREKEY_TYPE` decrypt (still outside `sessionLock`, serialized by `preKeyMutex`).
  - Fix stale-bundle race in `SignalManager.encrypt`: inside `sessionLock(recipientId)`, if `storedIdentity != null && storedIdentity != bundle.identityKey`, re-fetch the bundle once under the lock before calling `deleteAllSessions` + `deleteTrustedIdentity` so a stale in-flight encrypt cannot tear down a freshly built session.
- **Tests:** Real-libsignal tests in `SignalManagerTest` covering (a) two concurrent new contacts claiming distinct pre-keys, (b) session establishment when one-time pre-key pool is empty (signed + Kyber fallback), (c) failed replenish publish leaving remaining local pre-keys intact and retried on next init, (d) stale-bundle re-fetch guard. Update `PocketBaseKeySource` stub if `KeySource` signature changes.

**Shipped** (`1c9eb76d`): `SignalDatabase` v1→v2 (`MIGRATION_1_2`, atomic `saveInitialKeys`), 50-key rotating pool with local+remote count sync and offline timeouts, `claimPreKeyBundle` callable Cloud Function with blocked-caller guard (`20/20` tests), owner-only `oneTimePreKeys` Firestore rules (`101/101` emulator tests), and locked identity re-fetch guard in `SignalManager.encrypt`.

### Step 3 — Unified locked receive path and decrypted push notifications

Close the double-decrypt race across listener, chat-list sync, and push reconcile, and show decrypted text in local notifications.

- **Single receive entry point (`MessageRepositoryImpl`):**
  - Add a `private val receiveLocks = KeyedMutex<String>()` keyed by `raw.id`.
  - Route `syncChatMessages` through `reconcileRawMessage` (passing an `autoDownload: Boolean` flag so chat-list sync keeps its non-downloading behavior while sharing the exact same dedup/decrypt/upsert logic).
  - Wrap `reconcileRawMessage`'s check → decrypt → upsert inside `receiveLocks.withLock(raw.id)` and `withContext(NonCancellable)`.
  - Never overwrite a row whose `content` is already decrypted with `"[Encrypted message — unable to decrypt]"`.
- **Notification preview (`FCMService.kt`):**
  - When a push arrives for an inactive chat and `messageId != null`, run `withTimeoutOrNull(4_000L) { messageRepository.reconcileFromPush(chatId, messageId) }` before calling `showNotification`, then read the reconciled message from Room (`messageRepository.getMessageById(messageId)` or return `Message?` from `reconcileFromPush`) to populate the notification text when `data["messageContent"]` is absent. Fall back to generic type text (`"New message"`, `"📷 Photo"`) on timeout or error.
- **Tests:** Regression tests in `MessageRepositorySyncDecryptTest` proving concurrent calls from listener + sync + push for the same encrypted `raw.id` call `signalManager.decrypt` **exactly once**, and tests for `FCMService` using the reconciled plaintext when push payload omits `messageContent`.

**Shipped** (`d06b046d`): unified all three receive paths under `MessageRepositoryImpl.receiveLocks` (`KeyedMutex`), prevented decrypt-error overwrites of already-decrypted rows, added `PushReconcileResult` (`Reconciled`/`Suppressed`/`Unavailable`), and updated `FCMService` to await bounded reconcile inside `onMessageReceived` for decrypted notification previews with blocked/deleted suppression.

### ‖ Checkpoint 1 — Hardware smoke test (1:1 text E2EE)

Deploy `functions` (`claimPreKeyBundle`) and `firestore.rules`, build a **release** APK of the `firebase` flavor onto two devices, flip Settings → End-to-End Encryption on, and run the Step 4 hardware checks from `docs/BACKLOG.md` (lines 803–819):
- Burst of messages to a new contact, airplane-mode retry, retry after recipient reinstall, cold-start notification tap (verifying decrypted notification text and no `[unable to decrypt]`), and chat-list preview privacy.

---

## Phase 2 — Full 1:1 Encrypted Envelope, Encrypted Media & Encrypted Edits

### Step 4 — Versioned encrypted envelope and Room schema migration

Move message metadata and media descriptors inside the Signal payload and add Room columns with a tested migration.

- **Envelope codec (`data/crypto/MessageEnvelope.kt`):**
  - Define `MessageEnvelope` (`v = 1`, `kind = MSG | EDIT`, `body`, `mentions`, `emojiSizes`, `replyToId`, `location`, `sticker`, `media`, `thumb`) serialized as compact JSON via `org.json.JSONObject` (already on classpath, no new dependency).
  - Decoder is backward-compatible: if a decrypted Signal string is not valid `{"v":1,...}` envelope JSON, treat the raw string as `MessageEnvelope(body = raw)` so pre-Step-4 encrypted text messages still decrypt cleanly.
- **Room schema (`AppDatabase` v35 → v36, explicit `MIGRATION_35_36`):**
  - Add nullable columns to `MessageRecord` / `messages`: `mediaKey`, `mediaIv`, `mediaSha256`, `thumbKey`, `thumbIv`, `thumbSha256` (all Base64 strings), plus local column `localThumbUri` on `MessageEntity` (not in `MessageRecord`, so snapshot upserts never wipe it).
  - Add matching properties to `Message` domain model (`isEncryptedMedia = mediaKey != null`).
  - Add `AppDatabaseMigrationTest` verifying `MIGRATION_35_36` preserves existing rows and defaults all new columns to `NULL`.
- **Wire format (`MessageWriter.kt`, `FirestoreMessageSource.kt`, `RawMessage.kt`):**
  - Remove `MessageType.LOCATION` from `PLAINTEXT_TYPES` in `MessageWriter`.
  - When `MessageWriter.encode` encrypts a 1:1 message, serialize the full `MessageEnvelope` into `signalManager.encrypt(peer, envelopeJson)`.
  - In `FirestoreMessageSource.sendMessage(...)` (the encrypted write path), **omit** plaintext `content`, `mediaUrl`, `mediaThumbnailUrl`, `fileName`, `fileSize`, `mimeType`, `latitude`, `longitude`, `stickerId`, `stickerPackId`, `mentions`, `emojiSizes`, `replyToId`, `mediaWidth`, `mediaHeight`, `duration`, `isHd`. Write only routing/delivery fields + `ciphertext` + `signalType` + `type` + `senderId` + `timestamp` + `isForwarded`.
  - In `MessageRepositoryImpl.reconcileRawMessage`, merge decrypted `MessageEnvelope` fields into the reconstructed `Message` before persisting to Room.
- **Tests:** Round-trip unit tests for `MessageEnvelope` (every message type + legacy plain-string fallback), `MessageWriterTest` verifying zero plaintext metadata reaches `MessageSource.sendMessage`, and `Migration(35, 36)` test.

### Step 5 — Streaming AES-256-GCM media encryption

Encrypt all 1:1 media files and video thumbnails before uploading to Firebase Storage, and decrypt + verify on download.

- **Cipher (`data/crypto/MediaCrypto.kt`):**
  - Chunked AES-256-GCM with 64 KiB plaintext chunks:
    - Generates a fresh 32-byte key (`SecureRandom`) and 12-byte base nonce.
    - Chunk `i` uses nonce `baseNonce XOR bigEndian64(i)` and AAD `(version=1 || chunkIndex || isFinalByte)` so chunks cannot be reordered, dropped, or truncated.
    - `encryptFile(input: File, output: File): MediaCipherInfo(keyB64, ivB64, sha256B64, ciphertextSize)` streams in constant memory while computing the SHA-256 digest of the ciphertext.
    - `decryptStream(input: InputStream, output: OutputStream, keyB64: String, ivB64: String, expectedSha256B64: String)` verifies the SHA-256 digest and every chunk's GCM tag; throws on mismatch and never leaves a half-written target file.
- **Upload path (`OutboxSender.kt`, `FirebaseStorageSource.kt`, `StorageSource.kt`):**
  - Add `uploadEncryptedMedia(chatId, storageId, file, onProgress)` uploading to `media/{chatId}/{storageId}.enc` with `contentType = "application/octet-stream"`.
  - In `OutboxSender.prepareMedia`, when `messageWriter.willEncrypt(row, target)` is true:
    - Encrypt the encoded image/video/document/GIF/voice file to a temp file in `cacheDir`, upload `.enc`, delete the temp file, and persist `mediaUrl`, `mediaKey`, `mediaIv`, `mediaSha256` on the row in one step (so retries reuse the uploaded encrypted blob and its key).
    - For `VIDEO`, encrypt the extracted JPEG thumbnail with its own fresh key, upload `{messageId}_thumb.enc`, copy the plaintext thumbnail to `filesDir/thumbs/{messageId}.jpg` (`localThumbUri`), and persist `mediaThumbnailUrl`, `thumbKey`, `thumbIv`, `thumbSha256`.
  - **Forwards:** When forwarding an encrypted media message (`source.mediaKey != null`) to another chat, clear `mediaUrl`/`mediaKey`/`thumbKey` on the new row and stage the decrypted local file so the forwarded copy gets its own fresh blob and key (never sharing keys across chats).
- **Download path (`MediaFileManager.kt`, `MessageRepositoryImpl.kt`):**
  - When `message.mediaKey != null`, `MediaFileManager.downloadFor(...)` downloads the `.enc` blob to a `.part` temp file, runs `MediaCrypto.decryptStream` (verifying `mediaSha256`), and writes the decrypted bytes to the final local location (using `message.mimeType` / `message.type` for the local extension, since the remote URL ends in `.enc`).
  - Add thumbnail download-and-decrypt (`downloadEncryptedThumbnail(message)` → `filesDir/thumbs/{messageId}.jpg`) triggered alongside auto-download and on chat open when `thumbKey != null && localThumbUri == null`.
- **Tests:** `MediaCryptoTest` (empty file, single chunk, multi-chunk > 200 KiB, wrong key, flipped byte, truncated stream, swapped chunks), `OutboxSenderTest` (encrypted upload + retry reuses key/url without re-encrypting, video thumbnail encryption, forward re-encryption), and `MediaFileManagerTest` (encrypted download + hash mismatch failure).

### Step 6 — Encrypted message edits and local-only UI playback

Close the last plaintext leak (edits) and make sure no UI surface tries to decode raw encrypted Storage URLs.

- **Encrypted edits (`MessageRepositoryImpl.kt`, `FirestoreMessageSource.kt`, `RawMessage.kt`):**
  - Add `editCiphertext: String?` and `editSignalType: Int?` to `RawMessage` and `MessageSource.editEncryptedMessage(chatId, messageId, ciphertext, signalType, editedAt)`.
  - In `MessageRepositoryImpl.editMessage`: resolve `SendTarget.forChat`; if `messageWriter.willEncrypt(...)` is true, encrypt `MessageEnvelope(kind = EDIT, body = newContent, emojiSizes = emojiSizes)` and call `editEncryptedMessage` (writing `editCiphertext`, `editSignalType`, `editedAt`, and clearing any stale plaintext `content`).
  - In `reconcileRawMessage`: when `raw.editCiphertext != null && raw.editSignalType != null && raw.editedAt != existing?.editedAt`:
    - If `existing == null` (recipient came online after both send and edit), decrypt `raw.ciphertext` first to recover media/metadata, then decrypt `raw.editCiphertext` to apply the edited `body` and `emojiSizes`.
    - If `existing != null`, decrypt only `raw.editCiphertext` and apply `body`, `emojiSizes`, and `editedAt` without touching the original `ciphertext`.
- **UI audit (no direct remote URL loading for encrypted media):**
  - Introduce a helper on `Message` (e.g. `displayMediaSource: String?` and `displayThumbSource: String?`) that returns `localUri` (or `localThumbUri`) when `isEncryptedMedia`, and `localUri ?: mediaUrl` only for legacy unencrypted / Klipy media.
  - Update `MessageBubble.kt`, `SharedMediaTile.kt`, `ChatScreen.kt`, `ChatMediaGallery.kt`, `ProfileScreen.kt`, `FullscreenVideoPlayer.kt`, `VoiceMessagePlayer.kt`, `SearchResults.kt`, and `ChatViewModel.readableSentImage` to use the helper and trigger on-demand download-and-decrypt (with a loading spinner) if an encrypted media row's `localUri` is null (e.g., right after a backup restore).
- **Tests:** `MessageRepositoryEditEncryptTest` (edit on existing row, edit arriving before original was synced, multiple sequential edits) and UI/ViewModel tests confirming encrypted media never hands raw `.enc` URLs to image/video loaders.

### ‖ Checkpoint 2 — Hardware smoke test (Full 1:1 E2EE)

Build a release APK on both devices with E2EE enabled and verify:
- Text, photo, HD photo, video + thumbnail, voice note, document, GIF, location, sticker, forward, and edit all send, render, and play cleanly.
- Inspect Firestore console (`chats/{chatId}/messages/{id}`): zero plaintext content, captions, URLs, filenames, coordinates, or sticker IDs.
- Inspect Firebase Storage console (`media/{chatId}/`): new files are `.enc` with `application/octet-stream` and cannot be opened as images/videos.

---

## Phase 3 — Passphrase-Protected Encrypted Backup & Restore (Firebase Storage)

### Step 7 — Backup crypto, snapshot packager, and Storage rules *(audit via `firebase-security-rules-auditor`)*

Build the core backup and restore engine, completely decoupled from UI so it can be unit-tested thoroughly.

- **Firebase Storage rules (`storage.rules` + `firebase.json`):**
  - Add `storage.rules` to the repo and wire it into `firebase.json` (auditing existing `avatars/`, `media/`, and `stickers/` rules first so nothing regresses).
  - Lock `backups/{uid}/{allPaths=**}` strictly to `allow read, write: if request.auth != null && request.auth.uid == uid;`.
- **Backup key derivation (`data/backup/BackupCrypto.kt`):**
  - Derive a 256-bit master backup key from `(passphrase, 32-byte random salt)` via `PBKDF2WithHmacSHA256` with `600_000` iterations.
  - Derive subkeys via HKDF-SHA256: `dbKey` for `db.enc`, `manifestKey` for encrypted manifest payload, and `legacyMediaKey(messageId)` for each legacy media blob.
  - Store an unencrypted minimal outer header in `backups/{uid}/manifest.json` (`version`, `saltB64`, `kdfIterations`, `createdAt`, `dbCipherSizeBytes`, `legacyMediaComplete`, `encryptedMetaB64`, `encryptedMetaIvB64`). The encrypted inner metadata (`encryptedMetaB64`) acts as both an instant wrong-passphrase check and holds the list of backed-up legacy media IDs, message count, and schema version—leaking nothing sensitive in plaintext.
- **Database snapshot & restore (`data/backup/BackupManager.kt`, `FirebaseBackupSource.kt`):**
  - **Backup flow:**
    1. Run `messageRepository.syncAllChatMessages(...)` and download any not-yet-local legacy media (`mediaKey == null && mediaUrl != null && localUri == null`, excluding Klipy URLs and stickers) so the phone has a complete local copy.
    2. Snapshot `AppDatabase` cleanly via `PRAGMA wal_checkpoint(FULL)` + `VACUUM INTO '<cache>/backup_snapshot.db'` (never copy a live WAL file directly; never include `signal.db`).
    3. Gzip-compress and encrypt the snapshot with `MediaCrypto` + `dbKey` → upload to `backups/{uid}/db.enc.tmp`, then promote (or write versioned `db_<timestamp>.enc` + update `manifest.json` last so an interrupted upload never corrupts the previous good backup, then delete the superseded `db_*.enc`).
    4. For every legacy media row (`mediaKey == null && localUri != null`, existing local file, not already in the manifest's `legacyMediaIds`), encrypt the file with `legacyMediaKey(messageId)` and upload to `backups/{uid}/legacy-media/{messageId}.enc` (incremental—only uploaded once).
    5. Upload `backups/{uid}/manifest.json` last (commit point).
  - **Restore flow:**
    1. Fetch `backups/{uid}/manifest.json`, derive key from passphrase, decrypt `encryptedMetaB64` (fails fast with `WrongPassphraseException` if wrong).
    2. Download & decrypt `db.enc` → gunzip to temp file → verify SQLite integrity (`PRAGMA integrity_check`) and schema version (`<= AppDatabase.VERSION`).
    3. Close Room cleanly, swap the restored file into `fire_stream_chat.db` (deleting any stale `-wal`/`-shm`), and reopen (or restart app process cleanly). Room automatically runs any forward `Migration`s if the backup came from an older app version.
    4. Persist the derived `backupKey` in Android Keystore–wrapped DataStore (`BackupKeyStore`) so lazy on-demand decryption of `backups/{uid}/legacy-media/{messageId}.enc` works transparently when scrolling old chats.
    5. Integrate legacy-backup media fallback into `MediaFileManager`: if a row has `localUri == null && mediaKey == null` and a local `backupKey` exists (or after legacy Storage is scrubbed), download & decrypt from `backups/{uid}/legacy-media/{messageId}.enc`.
- **Tests:** `BackupCryptoTest` (KDF, wrong passphrase rejection, subkey isolation), `BackupManagerTest` (full backup → wipe DB → restore round-trip preserving messages, `mediaKey`s, stars, chats, `message_sync_state`, and legacy media; interrupted upload leaves prior manifest intact).

### Step 8 — Backup & restore UI and scheduled backups

Wire the backup engine into Settings, WorkManager, and post-login onboarding.

- **Settings → Chat Backup (`ui/settings/BackupScreen.kt`, `BackupViewModel.kt`, `NavGraph.kt`):**
  - Entry in `SettingsScreen` showing last backup time and status ("Backed up ✓" / "Not set up").
  - Passphrase setup / change dialog with confirmation field, minimum-length check (≥ 12 chars), and clear warning that a lost passphrase cannot be recovered.
  - "Back up now" button with determinate stage progress (syncing chats → downloading legacy media → encrypting DB → uploading legacy media → finishing).
  - Auto-backup frequency selector (`Off` / `Daily` / `Weekly`, Wi-Fi + charging constraints) backed by `BackupWorker` (`@HiltWorker`, unique periodic work), using the Keystore-wrapped `backupKey` stored when the passphrase was set.
- **Fresh-install restore prompt (`ui/auth/` / `MainActivity` navigation):**
  - Right after sign-in, when Room `messages` is empty, check `backupSource.fetchManifest(uid)`.
  - If a backup exists, present `RestoreBackupScreen`: shows backup date & size, passphrase input, "Restore" (with progress + clean restart into `MainScreen`), and "Skip" (with confirmation dialog warning that skipping starts empty).
- **Tests:** `BackupViewModelTest` (validation, progress states, wrong passphrase error handling) and Robolectric UI test for `BackupScreen` and `RestoreBackupScreen`.

### ‖ Checkpoint 3 — Hardware backup & restore drill

Deploy `storage.rules`, install the release build on **both** phones, and run:
1. On both Phone A and Phone B: set a backup passphrase, tap **Back up now**, and confirm both show `All legacy media backed up ✓`.
2. On a test install (or after clearing app data on one phone once verified): sign in → see the restore prompt → enter passphrase → verify every chat, message, star, legacy photo/video, and Phase 2 encrypted photo/video restores cleanly.

---

## Phase 4 — One-Time Plaintext Scrub & Default-On E2EE

### Step 9 — One-time plaintext scrub script and E2EE default-on

Safely erase all legacy plaintext from Firestore and Firebase Storage once both phones have verified encrypted backups, and make E2EE on by default.

- **Host-side scrub tool (`scripts/scrub-plaintext.mjs`):**
  - Uses Firebase Admin SDK (same credential setup as `tools/sticker-namer/` or `GOOGLE_APPLICATION_CREDENTIALS`).
  - Defaults to **`--dry-run`**; requires explicit `--execute --users <uid1,uid2>` to mutate anything.
  - **Mandatory safety gate (even in `--dry-run`, reported clearly; hard-fails in `--execute`):**
    1. Fetches `backups/{uid}/manifest.json` for every UID passed in `--users`.
    2. Verifies each manifest exists, has `legacyMediaComplete === true`, and `createdAt` is newer than the latest plaintext message timestamp in Firestore (plus confirms `db.enc` exists and is non-empty).
    3. Refuses to scrub if any user's backup is missing or stale.
  - **What `--execute` does (idempotent, batched, logged):**
    1. Walks all 1:1 `chats/{chatId}/messages/*`: deletes every document that carries plaintext `content` (or plaintext `mediaUrl`/`fileName`/`latitude` without `ciphertext`), leaving all encrypted documents (`ciphertext != null && content == null`) untouched.
    2. Rewrites `chats/{chatId}.lastMessageContent` to `"Message"` wherever it holds plaintext on a 1:1 chat.
    3. Lists `media/{chatId}/*` in Firebase Storage: deletes every object that does **not** end in `.enc`, leaving all encrypted `.enc` blobs, `backups/`, `avatars/`, and `stickers/` untouched.
    4. Prints a summary report (docs deleted, chat previews scrubbed, Storage objects deleted, bytes freed).
- **Default E2EE on (`PreferencesDataStore.kt`, `SettingsScreen.kt`):**
  - Flip `e2eEncryptionEnabledFlow` default from `false` to `true` (so any release install encrypts 1:1 chats automatically; keep the Settings toggle with the existing disable warning dialog).
- **Docs update:** Update `CLAUDE.md`, `docs/ARCHITECTURE.md`, `docs/SCHEMA-FIRESTORE.md`, `docs/SCHEMA-ROOM.md`, and `docs/BACKLOG.md` to reflect the shipped state.
- **Tests:** Unit tests for the scrub script's safety gate and filtering logic (`scripts/scrub-plaintext.test.mjs`, pure logic extracted so it runs via `node --test` without live Firebase credentials).

---

## Risks & Rollback Notes

- **Never run Step 9 `--execute` until Checkpoint 3 succeeds on BOTH phones** and you have verified that a real restore from `backups/{uid}/` succeeds.
- **Passphrase loss:** By design, Firebase cannot recover a forgotten backup passphrase. Both users should save their passphrase in a password manager before running Step 9.
- **Room migration discipline:** After Step 1, every PR that modifies any Room entity **must** add a `Migration` and test—the build/test gate should catch missing migrations immediately.
- **Storage rules audit:** Before deploying `storage.rules` in Step 7, copy the exact currently deployed rules from the Firebase console so `avatars/`, `media/`, and `stickers/` permissions are preserved verbatim alongside the new `backups/{uid}/` rule.
