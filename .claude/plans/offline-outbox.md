# Offline outbox — durable, idempotent message sending

Brief for making sends survive no network, leaving the chat, process death and reboot,
and for letting received media catch up on reconnect. Written 2026-09-11 from a code read
of `main` at `b98e993c`; re-verify line numbers before editing. Reviewed the same day —
the review's findings are folded in below and marked **(review)**.

## 0. Decisions (signed off 2026-09-11 — do not re-litigate)

| Question | Decision |
|---|---|
| Send trigger | **WorkManager only.** No in-process fast path. Measure compose→SENT latency before/after; add a fast path later only if it regresses noticeably. |
| Ordering | **Parallel per message.** No per-chat FIFO. Display order is by client `timestamp`; out-of-order *arrival* must be made harmless (§2.6). |
| Signal on retry | **Encrypt once, persist, reuse.** A retry never re-encrypts. |
| Rollout | **Duplicate fix first** (step 1 ships alone), outbox after. |
| PocketBase | **Out of scope.** It only has to keep compiling; interface changes get accept-and-ignore stubs. |

Not in scope: timer / poll / list / call sends (not retryable today, keep `add()`), user-initiated
data transfer jobs, per-chat queues, a new `QUEUED` status.

## 1. Current state (verified 2026-09-11)

- Text: `MessageRepositoryImpl.sendMessage` inserts a `SENDING` row, then awaits Firestore `add()`.
  Firestore's persistent cache is on by default, so an offline write sits in the SDK's local
  queue and flushes on reconnect — **but only the awaiting coroutine is lost, not the write.**
- Sends run on `viewModelScope` (`ChatViewModel.kt:181` → `ChatMessageSender`). Leaving the chat
  cancels the await; the SDK still holds the write.
- **Duplicate bug:** row stuck `SENDING` → `failStuckSendingMessagesForChat` flips it `FAILED` on
  re-entry → tap retry → `retrySendTextLike` calls `add()` again with a **new auto-id**
  (`FirestoreMessageSource.kt:169`, `:219`) → both writes land → recipient gets it twice.
- Media/voice: compress → `FirebaseStorageSource.uploadMedia` (`putFile`, not resumed across
  process death) → Firestore write. Any failure → `FAILED`, manual retry only.
- Block check (`MessageRepositoryImpl.isBlocked`, deliberately *not* fail-open) runs before the
  optimistic insert; offline cache miss throws → message silently dropped
  (`TECH_DEBT.md` "Offline send silently dropped on a block-check cache miss").
- `firestore.rules:54` — any participant may `update` a message. A blind `set()` over an
  already-landed message would reset `status`, `reactions`, `readBy`, `deliveredTo`.
- `functions/index.js:212` `sendPushNotification` is `onDocumentCreated` → one push per doc id.
- `SignalManager.encrypt` (`:66`) has **no per-address lock** — concurrent encrypts for one
  recipient race on session state (load-modify-store). Latent today, likely under parallel sends.
- Storage media object is `media/{chatId}/{messageId}.{ext}` — already keyed by message id.
- Receive: Firestore listeners resume by themselves; FCM stores-and-forwards. Gap: received
  media only downloads on chat entry (`downloadPendingMediaForChat`, `:1753`) or the daily
  `MediaBackfillWorker`.
- **(review)** A received message only enters Room through `reconcileRawMessage`, i.e. while
  the chat's listener is active. `FCMService` (`:80`, `:93`) only marks delivery, and
  `MediaBackfillWorker` scans Room. A message received while its chat is closed has **no local
  row at all**, so nothing can download it until the chat is opened.
- `AppDatabase` has one explicit migration (18→19); every other bump is destructive
  (`DatabaseModule.kt:34`). The step-4 bump wipes every pre-outbox row.

## 2. Design

### 2.1 The outbox is the messages table
Own rows with `status = 'SENDING'`. No new table, no new status. `SENDING` renders as the
existing clock icon and means "not yet acknowledged by the server" — queued or in flight alike.
`FAILED` means permanent error or given up (§2.5), and keeps the existing tap-to-retry.

### 2.2 One id everywhere
The client generates the message id once (UUID) at compose time. It is the Room primary key,
the Firestore document id and the Storage object name. No `tempId → remoteId` swap for the
firebase flavor (the swap code stays generic for PocketBase, which keeps server ids).

### 2.3 Write semantics — idempotent without clobbering
- **First attempt:** `document(id).set(data)`. Fast, latency-compensated, joins the SDK queue.
- **Any attempt after a prior one** (manual retry in step 1; `outboxAttempts > 0` from step 6):
  first `firestore.waitForPendingWrites().await()`, then a create-if-absent transaction —
  `runTransaction { if (!get(ref).exists()) set(ref, data) }`. Already there → treat as success.
  Transactions need the network; in the worker that is guaranteed by the constraint, and a
  failure is transient.
- **(review) Why the flush comes first.** Transaction reads go to the server and ignore the
  SDK's persisted-but-unflushed first-attempt `set()`. Without the flush the transaction creates
  the doc, the replay then overwrites it, and `readBy` / `deliveredTo` / `reactions` are lost —
  permanently if the recipient had already read it, because no later receipt repairs them.
  `waitForPendingWrites()` lands the replay first; the transaction then finds the doc. No
  residual race remains.
- **(review) A first-attempt `set().await()` never fails offline — it waits for the ack.**
  `NetworkType.CONNECTED` does not mean Firestore's stream is up (captive portal, flaky link),
  so an un-timed await would sit until WorkManager stops the worker. Wrap it in
  `withTimeout(SEND_ACK_TIMEOUT_MS)` (30 s) → `Result.retry()`. The SDK keeps the write; the
  next attempt takes the flush-then-transaction path above and finds it.

### 2.4 Encrypt once
First attempt encrypts (needs the network for the pre-key bundle anyway) and persists
`outboxCiphertext` + `outboxSignalType` on the row before writing. Later attempts reuse the
bytes. Cleared on `SENT`. `SignalManager` gets a per-address `Mutex` around the session-touching
body of `encrypt` and `decrypt`. **(review)** `replenishPreKeyIfNeeded` (a network publish) runs
*after* the lock is released, not inside it — otherwise every send to that contact waits on it.
The debug/release branch in `sendEncryptedOrPlain` becomes an injectable policy so the encrypted
path is unit-testable (release unit tests are disabled).

### 2.5 Worker, errors, give-up
- `OutboxWorker` (`@HiltWorker`, `data/worker/`), one unique work per message:
  `outbox-<messageId>`, `ExistingWorkPolicy.KEEP` on compose, `REPLACE` on manual retry.
- `NetworkType.CONNECTED`, `BackoffPolicy.EXPONENTIAL` from 10 s.
- **(review) Expedited only where it does not cost a notification.** On API 29/30 expedited work
  runs as a foreground service, so every text send would flash a notification. Rule at enqueue:
  `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` when `SDK_INT >= 31` (expedited job, no
  notification) **or** the message carries media (the upload wants the foreground anyway).
  Text on API 29/30 runs as ordinary work. Checklist item 6 measures whether that shows.
- `getForegroundInfo()` is **mandatory** for expedited requests. Media uploads call
  `setForeground` (dataSync — the manifest merge for `SystemForegroundService` already exists,
  `AndroidManifest.xml:141`). **(review)** On API 31+ `setForeground` from the background can throw
  `ForegroundServiceStartNotAllowedException`; wrap it exactly like
  `ApkDownloadWorker.tryPromoteForeground` (`:128`) and continue unpromoted — the upload then has
  WorkManager's 10-minute budget, which the Storage retry limit already fits inside.
- Errors are classified behind a flavor-neutral `SendErrorClassifier` (interface in
  `data/remote/source/`, firebase impl, pocketbase stub):
  transient = no network, ack timeout (§2.3), `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `ABORTED`,
  `RESOURCE_EXHAUSTED`, Storage retry-limit, `IOException` → `Result.retry()`;
  permanent = `PERMISSION_DENIED`, `NOT_FOUND`, `INVALID_ARGUMENT`, blocked, input file
  unreadable, `MediaLimitException` → `FAILED`.
- **(review) Give-up: 8 executed attempts → `FAILED`. No wall clock.** Attempts only run while
  connected, so a phone that is offline for three days still sends when it reconnects — the
  earlier "24 h since `timestamp`" rule would have failed that message without ever trying. The
  only reason for a wall clock was rows stuck since before the outbox existed, and the destructive
  step-4 migration removes those.
- An in-process per-id `Mutex` in the engine guards a `REPLACE` retry overlapping a running attempt.
- Cancellation (constraint lost, work replaced) leaves the row `SENDING`.
- **(review) Blocked recipient keeps today's banner when online.** Repository `send*` still call
  `isBlocked` before enqueue; a definite `true` throws `ERR_USER_BLOCKED` as today (row inserted,
  flipped `FAILED`, banner shown). A *fetch error* (offline cache miss) no longer refuses: the row
  is enqueued and the worker runs the authoritative check — blocked there is permanent → `FAILED`,
  and the message-info sheet shows "Not sent — you can't message this user".
- **(review) Delete while queued.** Cancelling the work is not enough: a first-attempt `set()`
  the SDK has persisted still replays, and a hard-deleted local row would then be re-inserted by
  its own echo. Rule: deleting a `SENDING` row soft-deletes it locally (row stays, so the echo hits
  `existing != null`), deletes the outbox file, and enqueues a `REPLACE` run. `OutboxSender` on a
  soft-deleted row takes a tombstone path: `waitForPendingWrites()`, then a transaction that
  writes the `deletedAt` fields **only if the doc exists** and never creates it. Attempt count 0
  and no pending writes → nothing to do, done.

### 2.6 Parallel-arrival guards
- Room `ChatDao.updateLastMessage` from send paths becomes newer-only
  (`WHERE lastMessageTimestamp IS NULL OR lastMessageTimestamp <= :timestamp`).
- `FirestoreMessageSource.writeBackChatPreview` becomes a fire-and-forget newer-only transaction
  on `chats/{id}`. **(review)** A transaction cannot be queued offline, so between step 5 and
  step 6 an offline send loses its remote preview write (the local Room mirror still updates).
  Accepted: the two steps ship back to back, and from step 6 the write runs inside the worker,
  which is online by constraint.
- Timestamps within a process are strictly increasing (`max(now, last + 1)`), so a batch never
  shares a millisecond.
- Push notifications may arrive out of order for overlapping sends — accepted.
- Signal: out-of-order arrival is handled by libsignal's skipped-message keys; verify on a
  release build with two devices (§4).

### 2.7 Durable inputs
A photo-picker `content://` grant does not outlive the process, and `cacheDir/edits/` can be
purged. Before enqueueing, copy any input that is not already an app-owned file under
`filesDir` into `filesDir/outbox/<messageId>.<ext>`; delete it on `SENT` or when a queued
message is deleted (which also cancels its unique work).

### 2.8 Echo handling (needed from step 1 on)
With client ids, the snapshot listener's local echo of our own write carries the row's id, so
the `existing != null` branch of `reconcileRawMessage` would flip `SENDING → SENT` before the
server acked. Fix: `RawMessage.hasPendingWrites` from `doc.metadata.hasPendingWrites()`, listener
registered with `MetadataChanges.INCLUDE`; for own messages, a pending echo never changes status,
and a non-pending echo moves a local `SENDING`/`FAILED` row to `SENT` (it *is* on the server —
this also heals today's "landed but shows FAILED" orphan). `hasPendingWrites` is a constructor
property of the `RawMessage` data class, so the ack flips equality and the unchanged-skip map in
`getMessages` (`:241`) re-reconciles the row instead of ignoring the metadata-only event.

## 3. Steps

**Order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8**

Every step follows CLAUDE.md's post-step workflow (tests → `:app:testFirebaseDebugUnitTest` →
`:app:assembleFirebaseDebug` → `/simplify` if triggered → commit → memory). Bug-fix steps write
the regression test before the fix. Steps 1, 4 and 6 touch the sync path / crypto / coroutine
scoping: run `/code-review` on each.

### Step 1 — Idempotent message ids (`fix:`, CHANGELOG *Fixed*)
- `MessageSource.sendMessage` / `sendPlainMessage`: add `messageId: String` and `ifAbsent: Boolean`.
  Firestore: `document(messageId).set()` or the §2.3 transaction. PocketBase: accept and ignore.
- `RawMessage.hasPendingWrites` (default `false`); `observeMessages` uses `MetadataChanges.INCLUDE`.
- `MessageRepositoryImpl`: `sendEncryptedOrPlain` threads `messageId`; `send*` pass the compose id;
  `retrySend*` pass `message.id` with `ifAbsent = true`; `reconcileRawMessage` own-message branch per §2.8.
- Broadcast fan-out (`sendBroadcastMessage`, `:1300`) gets one id per target chat.
- Tests first: `MessageRepositoryRetryTest` — retry writes the same `messageId` as the original
  send, with `ifAbsent = true`. `MessageRepositorySnapshotTest` — pending own echo keeps `SENDING`;
  non-pending own echo turns a `FAILED` row `SENT`.
- `docs/GOTCHAS.md`: Firestore's local echo carries client-set ids — gate status on `hasPendingWrites`.
- Interim behaviour until step 6: a manual retry while offline lands back at `FAILED` within
  `RETRY_ACK_TIMEOUT_MS` (30 s). It cannot be faster: `waitForPendingWrites()` does not fail
  offline, it waits for an ack that is not coming, so the re-attempt is bounded by a timeout
  that surfaces as `IOException` → `AppError.Network` (found by `/code-review` on this step).
- **(review) Legacy rows.** A `FAILED` row from before this step whose message actually landed
  under an old auto-id will still duplicate on retry — its local id never existed remotely, so the
  transaction creates it. Unavoidable; note it in the CHANGELOG entry and in checklist item 4.

### Step 2 — Block check can no longer drop a message (`fix:`, CHANGELOG *Fixed*)
Move the `isBlocked` call in the five 1:1 send methods to *after* the optimistic insert, inside
`failSendOnError`. Keeps the deliberate "refuse rather than deliver" semantics (no fail-open);
offline the row lands `FAILED` with retry instead of vanishing. From step 6 on, a fetch error
enqueues instead of failing and the worker re-checks (§2.5 "Blocked recipient").
- Test first: `MessageRepositoryBlockTest` — `userSource.isUserBlocked` throws → row inserted and `FAILED`.
- Remove the TECH_DEBT entry.

### Step 3 — Extract `OutboxSender` (`refactor:`, no CHANGELOG, no bump)
- New `data/outbox/OutboxSender.kt` (AGENT-NOTE header) owning `send(messageId)`: reads the row and
  resumes — skip compression if the app-owned file and dimensions are present, skip upload if
  `mediaUrl` is set (persist `mediaUrl`/`mediaThumbnailUrl` right after upload), then the write.
  Covers TEXT, IMAGE, VIDEO, DOCUMENT, VOICE, LOCATION. Owns `_uploadProgress` (repository delegates).
- Repository `send*` = validate + insert + `outboxSender.send(id)`; `retryFailedMessage` = status
  flip + `outboxSender.send(id)`. The four `retrySend*` variants are deleted. Still called from the
  caller's scope — behaviour unchanged.
- Move pipeline tests (`MessageRepositoryRetryTest`, `…MediaSendFailureTest`, `…HdPrecedenceTest`,
  `…LocalUriTest`) to `OutboxSenderTest` where they test the pipeline; keep repository-contract tests.
- `/simplify` trigger: large + cross-cutting.
- **Shipped shape (read before step 4).** `send(messageId, recipientId, isRetry = false, sourceMimeType = null)`:
  the row stores neither the 1:1 recipient nor a document's picked mime type, and `isRetry` drives both
  `ifAbsent` and preview rebind-vs-overwrite. `sendEncryptedOrPlain` moved into `OutboxSender`;
  `forwardMessage` and the broadcast fan-out borrow it. LOCATION stays plaintext by passing
  `recipientId = ""`. The block check stayed in the repository. A first video send reads the
  container header twice (pre-insert guard + sender) — accepted, a retry has only the row.

### Step 4 — Encrypt once + Signal session lock (`fix:`, Room 25 → 26)
- `MessageEntity`: `outboxCiphertext: String?`, `outboxSignalType: Int?`, `outboxAttempts: Int = 0`.
  Bump `AppDatabase` version; update `docs/SCHEMA-ROOM.md`.
- `OutboxSender`: encrypt only when `outboxCiphertext == null`, persist before writing, clear on `SENT`;
  `ifAbsent = outboxAttempts > 0`, increment before each write.
- `SignalManager`: per-address `Mutex` for `encrypt` and `decrypt`; pre-key replenishment runs
  after the lock is released (§2.4).
- Injectable encryption policy replacing the inline `BuildConfig.DEBUG` check.
- **(step-3 review)** Make that policy a `MessageWriter` (encrypt-or-plain decision + write) injected into
  both `OutboxSender` and the repository: the row-less forward / broadcast callers keep a one-call API
  while `OutboxSender` splits encrypt from write. The policy takes the message type, replacing
  LOCATION's `recipientId = ""` trick.
- **(step-3 review, decide here)** Add `outboxRecipientId: String?` in the same 25 → 26 bump, written at
  insert. Step 6's worker has only the message id but needs the recipient for encryption and the
  authoritative block check; `send` then drops its `recipientId` parameter. The alternative — derive it
  from the `ChatEntity` participants — can disagree with the nav argument when the chat is not cached.
- **(step-3 review)** Derive `ifAbsent` and the preview rebind-vs-overwrite choice from one
  `outboxAttempts > 0` value; the `isRetry` parameter goes away.
- Tests: second attempt reuses stored ciphertext and never calls `encrypt`; concurrent `encrypt`
  calls for one recipient do not interleave; a `PREKEY_TYPE` decrypt releases the lock before
  `publishKeys` is called.

### Step 5 — Parallel-arrival guards (`fix:`)
§2.6: newer-only `ChatDao` update for send paths, newer-only preview transaction, strictly
increasing timestamps. Tests: DAO test (pattern of `MessageDaoOrphanRecoveryTest`) for the
newer-only update; repository test for monotonic timestamps in a batch.
**(step-3 review)** Once send paths use the newer-only update, delete
`OutboxSender.rebindLastMessageIfMatches` and its retry branch — a first attempt and a retry then
update the preview the same way.

### Step 6 — `OutboxWorker` (`feat:`, CHANGELOG *Added*, minor bump)
- `data/worker/OutboxWorker.kt`, `data/outbox/OutboxScheduler.kt` (`enqueue`, `retryNow`, `requeueAll`),
  `SendErrorClassifier` + firebase impl + pocketbase stub + DI bindings, give-up policy (§2.5),
  expedited rule (§2.5), foreground info + notification channel for media with the
  `tryPromoteForeground` wrapper, ack timeout (§2.3), `waitForPendingWrites` before every
  if-absent attempt.
- Repository `send*` / `retryFailedMessage`: insert (or flip) → block check (definite `true` fails
  as today, fetch error proceeds) → copy inputs (§2.7) → `scheduler.enqueue`. Return right after
  enqueue; `ChatMessageSender.isSending` stays as a double-submit guard only.
- `FireStreamApp.recoverOrphanedSends` → `scheduler.requeueAll()` over own `SENDING` rows.
  **Remove** the `failStuckSendingMessagesForChat` call in `getMessages` (it would fail live queued rows).
- `deleteMessage` on a `SENDING` row → the tombstone path in §2.5 (soft-delete, outbox file gone,
  `REPLACE` run). `OutboxSender.send` branches on `deletedAt != null` before anything else.
- **(step-3 review)** `requeueAll` filters to the types `OutboxSender.send` supports, from one shared set:
  TIMER rows are row-backed `SENDING` sends too, and `send` throws `IllegalStateException` for them —
  neither transient nor permanent in the classifier.
- **(step-3 review)** `forwardMessage` inserts a `SENDING` row but writes directly (no `failSendOnError`),
  so `requeueAll` would hand a stuck forward to a pipeline it never ran through — a forwarded VIDEO
  without a thumbnail or local file throws. Route forward through insert → `outboxSender.send(id)`;
  the `mediaUrl` guard already skips the upload.
- **(step-3 review)** Decide `localUri` retention at SENT by ownership, not by type. Today DOCUMENT drops
  it; once §2.7 stages inputs in `filesDir/outbox/` and deletes them on SENT, VOICE would keep a
  dangling path. Carry a document's mime type in the staged file's extension so its retry stops
  uploading as `application/octet-stream` (pre-existing since before step 3).
- `androidx.work:work-testing` is **not** in the catalog — add it. Tests with
  `TestListenableWorkerBuilder`: success → `SENT`; transient → `retry`; ack timeout → `retry`;
  permanent → `FAILED`; give-up after 8 attempts; `requeueAll` enqueues only own `SENDING` rows;
  `getMessages` no longer flips `SENDING`; second attempt flushes pending writes before the
  transaction (source mocked, call order verified); deleted-while-queued row never creates the doc
  and writes the tombstone when it exists; blocked fetch error enqueues, definite block fails.
- Docs: `docs/FEATURE-MAP.md` new "Offline outbox" section; `docs/ARCHITECTURE.md` §7 state diagram;
  `docs/PATTERNS.md` "Sends are idempotent by client id and drained by `OutboxWorker`" + CLAUDE.md
  Key Conventions pointer; `docs/GOTCHAS.md` picker URIs don't outlive the process; `TECH_DEBT.md`
  remove "Durable offline-send outbox" (its plan pointer is dead) and rewrite "Sends still await two
  backend round trips"; `docs/BACKLOG.md` 6.3 → *Pending on-device verification* with §4's checklist.
- `/simplify` triggers: concurrency + cross-cutting. `/code-review`.

### Step 7 — "Waiting for network" hint (`feat:`, use the `app-ui-design` skill)
- `domain/…/ConnectivityObserver` (interface, `StateFlow<Boolean>`), `data/util/AndroidConnectivityObserver`
  (`registerDefaultNetworkCallback`, requires `NET_CAPABILITY_VALIDATED` so captive portals count as
  offline), DI binding. No `UI_ALLOWED_DATA_IMPORTS` change — UI depends on the domain interface.
- Chat top bar subtitle shows "Waiting for network…" while offline; `MessageInfoScreen` "Sending /
  In progress…" → "Waiting to send". Display only — nothing in the send path reads it.
- Test: the owning manager's slice with a fake observer.

### Step 8 — Received media downloads on reconnect without opening the chat (`feat:`)
**(review) The original step could not meet checklist item 7.** It enqueued a retry only on a
download *failure*, but a message received while its chat is closed never reaches Room (§1), so
there is no download, no failure and nothing to retry. Two halves are needed:

1. **Reconcile on push.** `FCMService` already receives `chatId` + `messageId` (`:71`–`:72`) and
   is the one thing that runs on reconnect for a closed chat (FCM stores and forwards). Add
   `MessageRepository.reconcileFromPush(chatId, messageId)`: `MessageSource.fetchMessage` (new,
   single-doc `get()`; PocketBase stub), then the existing `reconcileRawMessage` — it already
   applies the blocked-sender filter's input, decrypts under the step-4 lock, inserts the row and
   calls `tryAutoDownload`. Skip when the chat is active (the listener owns it). Must not throw
   out of `onMessageReceived`; best-effort with a log line.
2. **Retry the download.** On a `tryAutoDownload` / `savePendingMediaForChat` failure, enqueue
   unique one-time `MediaBackfillWorker` work (`media-download-retry`, `KEEP`) with `CONNECTED` —
   `UNMETERED` when auto-download is `WIFI_ONLY`; the worker already honours the preference
   (`MediaBackfillWorker.kt:32`).

Tests: push reconcile inserts the row and triggers the download (source + file manager mocked);
active chat is skipped; download failure enqueues (scheduler mocked). `docs/FEATURE-MAP.md` push
section gains the new repository entry point.

If half 1 turns out larger than a step should be (the decrypt-on-push path meets the Signal lock
for the first time), ship half 2 alone, drop checklist item 7 to *Pending* in BACKLOG, and open
"reconcile on push" as its own plan — do not ship half 2 under this step's title.

## 4. On-device verification (goes into BACKLOG in step 6)

Firestore transactions, `MetadataChanges`, WorkManager scheduling and foreground info cannot be
exercised under Robolectric. Test on hardware, **upgrading over an existing install**:
1. Airplane mode → send text, image, video, voice, location → clock icons → airplane off → all tick, recipient gets exactly one of each.
2. Same, but leave the chat / swipe the app away / reboot before reconnecting.
3. Send offline, reconnect, kill the app mid-upload of a video → resumes, no duplicate, no re-transcode.
4. Retry after a lost ack (send, kill the app the instant it goes online) → one message, reactions/receipts intact. Have the recipient react to and read it *before* the sender comes back — this is the case `waitForPendingWrites` exists for. Also confirm the known legacy duplicate (step 1) only affects rows that predate the upgrade.
4a. Delete a queued message while offline, reconnect → it never appears on the recipient, and the sender's row stays deleted.
4b. Send to a user who blocked you: online → banner as before; offline → clock, then "Not sent" once connected.
5. Release build, two devices, E2E on: burst of mixed media + text offline → all decrypt on the recipient.
6. Compose→SENT latency online, before vs after step 6 (logcat timestamps). Include an API 29/30 device or emulator, where text runs as non-expedited work — and confirm no notification flashes for a text send there.
7. Received image while offline → reconnect without opening the chat → downloaded.
8. Captive-portal Wi-Fi shows "Waiting for network…".
