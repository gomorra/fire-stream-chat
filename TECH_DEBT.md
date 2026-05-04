# Tech Debt

Known refactors and code smells that have been consciously deferred or declined. Each entry records what the issue is, why it's not being fixed right now, and what would trigger revisiting it. Add to this file rather than letting debt drift into tribal knowledge.

---

## Deferred — valuable but risky to touch without the right test coverage

### `MessageRepositoryImpl.observeMessages` — split `IncomingMessageProcessor`

**The smell.** `MessageRepositoryImpl` is still ~1000 lines after the April 2026 refactor pass, and most of the weight is in `observeMessages()` (see `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt:82`). Its inner `collectLatest` body is ~180 lines and mixes: blocked-sender filtering, reaction updates, self-message shortcut, Signal decryption with `NonCancellable` wrapper, Room upsert, auto-download routing, and shared-list cache sync.

**Why we haven't extracted it.**
- The `NonCancellable` block (around line 180) protects against a subtle Signal ratchet desync: if `collectLatest` cancels mid-decrypt after the ratchet has advanced but before the Room insert, a re-emitted snapshot will re-attempt decryption against an already-advanced ratchet and produce sporadic "unable to decrypt" errors. Any split has to preserve this non-cancellable atomicity, and unit tests can't fully verify it — it's an interaction between `collectLatest` cancellation, Signal's `SessionCipher` state, and Room persistence.
- The existing repo tests (`MessageRepositoryBlockTest`, `DeliveryTest`, `LocalUriTest`) cover the send path, not the observe-and-decrypt path.
- A clean extraction would also pull in the two `dagger.Lazy<ChatRepository>` / `dagger.Lazy<ListRepository>` hacks that exist solely to break a Hilt cycle caused by `observeMessages` calling `listRepository.fetchAndCacheList` as a side effect.

**When to revisit.** The next time someone is actively debugging E2E decryption on a real device with encryption re-enabled (recall `BuildConfig.DEBUG` disables Signal in debug builds), or when we gain integration tests that exercise the full snapshot → decrypt → Room pipeline. Don't take this on as a standalone "cleanup" task.

**Related:** finding #2 from the April 2026 audit at `/root/.claude/plans/graceful-mixing-plum.md`.

---

### `ChatScreen.kt` — split into `ChatTopBar` / `ChatInputBar` / `ChatAttachmentSheet` / `ChatMessageList`

**The smell.** `app/src/main/java/com/firestream/chat/ui/chat/ChatScreen.kt` is ~1400 lines with the `ChatScreen` composable itself spanning lines 145–1366 (~1220 lines). 135 imports. Everything — top bar, reply preview, reactions popup, search UI, attachment bottom sheet, input bar, message list — is inline in one function.

**Why we haven't split it.**
- The screen has dozens of interacting states (typing, editing, reply, reactions, mentions, pinning, search, media preview, voice recording, location picker, block state, upload progress, scroll position, animated emoji). An incorrect `remember {}` scope or forgotten `rememberUpdatedState` during extraction silently breaks interactions without any compile-time signal.
- The current test coverage (`ChatViewModelReadReceiptTest`, `ChatUtilsTest`, `MessageGroupingTest`) verifies state machines and pure logic, not Compose wiring. There's no golden-screenshot or Compose UI test that would catch a subtle regression from a botched split.
- The April 2026 pass already extracted the hairiest piece — the media/camera/permission launcher scaffolding — into `ui/components/ImagePicker.kt` via `rememberImagePicker()`. That accounted for most of the duplicated code across Chat/Profile/GroupSettings.

**When to revisit.** Allocate a focused session with ~20 minutes of manual smoke-testing afterward on a real device: send text, send image, record voice, reply, react, pin, search, share location, block/unblock. Do it when adding a major new feature to the chat screen (at which point the split pays back immediately), or when the file hits ~1800 lines and grep-driven navigation is actively slowing work down.

**Related:** finding #5 from the April 2026 audit.

---

### `testReleaseUnitTest` — 14 Compose/Robolectric tests fail to resolve the launcher activity

**The smell.** `./gradlew testReleaseUnitTest` reports 14 failures across `MessageBubbleSmokeTest` (6), `MessageBubbleScreenshotTest` (4), and `ChatListItemUiTest` (4). Every failure is the same Robolectric error: `Unable to resolve activity for Intent { act=android.intent.action.MAIN cat=[LAUNCHER] cmp=com.firestream.chat/androidx.activity.ComponentActivity }` (Robolectric PR [#4736](https://github.com/robolectric/robolectric/pull/4736)). The debug suite (`testDebugUnitTest`) is green — only release is affected.

**Why we haven't fixed it.**
- The tests use `ActivityScenarioRule` / `createAndroidComposeRule<ComponentActivity>()`, which Robolectric resolves by launching the manifest-declared launcher activity. R8/minification in the release variant strips or rewrites the launcher entry Robolectric is hunting for; the debug variant leaves it intact.
- The usual workarounds (a dedicated test-manifest that declares `ComponentActivity` as `LAUNCHER`, or switching to `createComposeRule()` for the pure-composable smoke tests) touch every affected test and need a variant-scoped manifest change — not a one-liner.
- These are UI-smoke/screenshot tests; the production code they cover is exercised by the debug suite too, so release-suite breakage isn't hiding a production regression.

**When to revisit.** When CI starts gating on `testReleaseUnitTest` (currently the debug suite is the gate), or when anyone actually relies on Robolectric screenshot tests for release-variant output. Quick fix: add an `app/src/testRelease/AndroidManifest.xml` stub declaring `androidx.activity.ComponentActivity` as `MAIN/LAUNCHER`, or migrate the affected tests to `createComposeRule()` (no Activity required).

---

### `MessageRepositoryBlockTest › sendMessage succeeds when recipient is not blocked` — release-only failure

**The smell.** `MessageRepositoryBlockTest.kt:81` fails in `testReleaseUnitTest` only. The test stubs `messageSource.sendPlainMessage(...)` and asserts `result.isSuccess`, but release builds route through the Signal-encrypted send path instead of `sendPlainMessage` — the unmocked encrypted branch returns `Result.failure`, so the assertion trips.

**Why we haven't fixed it.** Root cause is the `BuildConfig.DEBUG` guard in `MessageRepositoryImpl` (documented in `CLAUDE.md`: encryption disabled in debug to avoid key-loss during development). The test was written against the debug path. A proper fix either (a) mocks both paths and runs the assertion against whichever matches `BuildConfig.DEBUG`, or (b) injects the Signal path behind a test-only seam, or (c) since the 2026-04-26 release-mode E2E opt-out shipped, stubs `preferencesDataStore.e2eEncryptionEnabledFlow` to return `false` so the release build also hits `sendPlainMessage`. None is a drive-by change, and the test provides no signal the debug suite doesn't already.

**When to revisit.** Same trigger as the Robolectric item above: when `testReleaseUnitTest` becomes a CI gate. Until then, keep running the debug suite as the default.

---

### Sync-path regression coverage — Firebase emulator tests + per-list-mutex tripwire

**The smell.** Two related list-sync bugs shipped in 2026-04-23/24 — `e3c2c9c` (new items colliding on `order` after deletes) and `eed7519` (receiver's live updates clobbered by a race between `observeList`'s metadata listener, its items listener, and `ensureListSyncRunning`'s `observeMyLists` sync). Both were caught by dogfooding, not by tests. The race-condition class in particular can't be reliably reproduced in `runTest` with mocked DAOs: I tried adding a unit test for `eed7519`, found it passed even with the mutex reverted (false negative), and pulled it. The per-list mutex fix is logically correct but has no executable regression guard.

**Why we haven't fixed it.** The gap is two pieces, and neither is a drive-by:
- No Firebase emulator harness. All existing tests stub `FirestoreListSource` / `FirestoreMessageSource` / etc. — they can't surface query-rule regressions, cross-client convergence bugs, or timing-dependent races. Adding an emulator-backed test task means `firebase emulators:start` wiring in Gradle + fakes for `FirebaseAuth` (the emulator supports it) + a separate test source set that runs off CI's default path. Probably a one-evening setup; low ongoing maintenance.
- No white-box tripwire asserting that `listDao.insert` callers in `ListRepositoryImpl.observeList`'s two listeners and `ensureListSyncRunning` hold `mutexFor(listId)`. A future refactor that accidentally strips one of the three `mutexFor(...).withLock { ... }` blocks would re-introduce `eed7519` silently. Five-minute test, lasts forever.

**When to revisit.** Planned-for-soon, not deferred indefinitely — the user flagged a longer development horizon on 2026-04-24 and asked what coverage was in place. The trigger is the next free half-day: scaffold the emulator task first (`sender + receiver` repository instances against one emulator, asserting Room convergence on list add/toggle/clear, chat send/receive, and shared-list fan-out — ~5–10 tests total, not a full suite), then the tripwire test. Skip property-based / stress tests; they'll only produce the same false-negatives my pulled test did.

**Do not** try to add a plain-unit-test regression for `eed7519` without the emulator — the synchronous DAO mocks collapse the interleaving the bug depends on. Verified experimentally: both `observeMyLists` and `observeList` metadata mutexes can be reverted and the test still passes.

---

## Declined — not worth the churn

### Split `ChatRepository` (30 methods) and `MessageRepository` (25 methods) into smaller interfaces

**The smell.** Both interfaces are god-interfaces mixing concerns (chat CRUD + group admin + invite links + organization + broadcasts for `ChatRepository`; sending + media + reactions + receipts + search + starring + pinning for `MessageRepository`). An SRP purist would split them into 5 sub-interfaces each (see the April 2026 audit for a proposed decomposition).

**Why we're not doing it.** On its own, splitting the interfaces doesn't fix the underlying problem — it just shifts each ViewModel from injecting one fat interface to injecting 3–5 narrower ones. `ChatViewModel` would gain dependencies, not lose them. The only way to make the split worthwhile is to pair it with a broader rethink: promoting the `ui/chat/Chat*Manager.kt` classes into `domain/usecase/` and routing ViewModels exclusively through those, so the fine-grained repository interfaces only exist inside the use-case layer. That's a 2–3 session effort with significant churn to every ViewModel, and the clarity gain is marginal in a codebase where the current pattern is already well-understood and tested.

**When to revisit.** Only if/when we commit to a broader "use cases everywhere" architectural shift. Half-doing it (interface split without use-case promotion) is pure churn with no payoff. If the sprawl starts actively hurting onboarding or code navigation, do both halves together or not at all.

**Related:** finding #7 from the April 2026 audit; the updated use-case policy in `CLAUDE.md` already documents the pragmatic "managers as escape hatch" pattern.

---

## PocketBase v0 walking-skeleton — follow-ups

The `pocketbase` flavor that landed 2026-04-28 is intentionally a thin slice. The plan that built it is at `.claude/plans/we-researched-together-that-woolly-curry.md`. These are the conscious gaps to revisit when the variant gets real users.

### PocketBase push: automate FCM access-token refresh

**The smell.** `pocketbase/pb_hooks/push_on_message.pb.js` reads `FCM_ACCESS_TOKEN` from the environment and skips push if it's missing or expired. The standard FCM HTTP v1 OAuth2 JWT-bearer flow needs RS256 to sign the assertion, but PocketBase v0.22's Goja `$security.createJWT` only supports HS256/384/512 — there's no RSA signer, no Node modules, and no shell-out. Operator currently rotates manually (`gcloud auth print-access-token`, 1 h TTL).

**Why we haven't fixed it.** Real fixes mean a sidecar process (Node.js with `google-auth`, ~30 LOC) or a Go plugin (recompiles PocketBase). Both are valuable but expand the deployment surface beyond a single binary, which is part of what made PocketBase attractive in the first place. For the walking skeleton the env-var pattern is honest and unblocked enough.

**When to revisit.** When the PB variant ships to anyone whose ops aren't comfortable with a 50-minute `cron` rotating an env var, or when push reliability becomes a release-blocker.

---

### PocketBase: re-enable Signal encryption (`BuildConfig.SUPPORTS_SIGNAL = false`)

**The smell.** The pocketbase flavor's `MessageRepositoryImpl` always sends plaintext (`sendPlainMessage`) — `BuildConfig.SUPPORTS_SIGNAL` is gated false in the flavor source set so the Signal pre-key publish/fetch paths never execute. Receive-side decryption is reachable but never feeds non-null `ciphertext` (PB schema has no `ciphertext`/`signalType` columns).

**Why we haven't fixed it.** Adding Signal needs three coordinated changes: (1) two new columns on `messages` (`ciphertext`, `signal_type`) plus a migration; (2) new collections for pre-keys + signed pre-keys + sessions, mirroring the Firestore key paths; (3) PB-side ACL rules that match Firebase's "only the recipient can read their pre-keys" guarantee. None of those are mechanical translations — listRule expressions need careful audit.

**When to revisit.** Before anyone uses the PB variant for non-test traffic. Encryption is the headline product feature; shipping a self-host backend without it would diverge the variants in a way no user wants.

---

### PocketBase: deferred `MessageSource` methods throw `NotImplementedError`

**The smell.** ~20 methods on `PocketBaseMessageSource` throw `NotImplementedError` in v0: reactions (`addReaction`, `removeReaction`), polls (`updatePollVote`, `closePoll`), lists (`sendListMessage`, `applyListDiff`), edits/deletes (`editMessage`, `deleteMessage`), forwards, pin/unpin, ephemeral receipts (`markRead`, `markDelivered` — no-op for v0 since the schema has a single `status` field), timers (`sendTimerMessage`, `updateTimerState`, `pauseTimer`, `resumeTimer`), and the Signal-only `sendMessage(ciphertext, ...)`. The corresponding Firebase impls cover all of these.

**Why we haven't fixed it.** Each requires a schema change to `messages` (or, for polls, a new `polls` collection with realtime ACLs) plus a JS hook to enforce sender-only writes on edit/delete. The walking skeleton's success criterion was 1:1 text + presence + push — bolting on the rest before someone needs the variant for real chat is overbuilding.

**When to revisit.** Per-feature, as the variant gets used. The `NotImplementedError`s are a forcing function: the UI surfaces them as snackbars, so anyone hitting one will know exactly which method to implement.

---

### PocketBase: deferred `ChatSource` group/typing/unread surface

**The smell.** Group operations (`createGroup`, `addMember`, `removeMember`, `updateAdmins`, `joinViaInvite`), typing indicators (`setTyping`, `observeTyping`), and unread counters (`incrementUnread`, `markChatRead`) on `PocketBaseChatSource` are no-ops or throw. `addToArrayField` / `removeFromArrayField` only handle the `participants` array and throw `NotImplementedError` for `admins` / `pending_members` — those columns don't exist in v0.

**Why we haven't fixed it.** Same shape as the message-source gaps: needs schema columns + JS hooks + listRule audit. Typing indicators specifically need ephemeral signaling that doesn't exist in PB collections — would likely use a separate `typing` collection with a 5 s TTL cron sweeper, or piggyback on PB's realtime broadcast topics if those land in v0.23.

**When to revisit.** When group chat becomes a requirement for a PB-flavor user. 1:1 unread counts can probably be added in isolation first (single column on `chats`).

---

### PocketBase: array-field RMW could lose updates without optimistic concurrency

**The smell.** `PocketBaseChatSource.addToArrayField` / `removeFromArrayField` do read-modify-write on the JSON `participants` column under a per-chat `Mutex`. The Mutex serialises writes from a single client, but two clients writing concurrently still race — last-write-wins on the underlying PB `PATCH`.

**Why we haven't fixed it.** PocketBase has no native `arrayUnion` / `arrayRemove` and no optimistic-concurrency token (`updated` is a timestamp, but PB doesn't accept it as a write-condition). Real fixes need either (a) a custom JS hook exposing `POST /api/chats/:id/participants/add` with server-side merge, or (b) moving participants into a `chat_members` join collection and dropping the array entirely.

**When to revisit.** When concurrent group-membership edits happen in practice (e.g., two admins adding members at the same time). For the walking skeleton's 1:1 use case, races are theoretical.

---

### PocketBase: presence sweeper scans the whole `presence` collection every 30 s

**The smell.** `pocketbase/pb_hooks/presence_sweeper.pb.js` calls `findRecordsByFilter("presence", "is_online = true && last_heartbeat < {:cutoff}", ...)` every 30 s with a 100-row page. For a few hundred users this is fine — for tens of thousands it'd scan the table on each tick. PocketBase auto-indexes single-column lookups but compound conditions like this one fall back to a sequential scan unless you add a manual `(is_online, last_heartbeat)` index.

**Why we haven't fixed it.** Walking-skeleton scale doesn't warrant index tuning. The upper limit is also bounded by the `100` page size — a saturating sweep would just defer the rest by one tick (30 s), still well inside the 60 s freshness window.

**When to revisit.** When the PB instance has > ~5 k users actively online or when sweep latency exceeds the 30 s tick. Add `CREATE INDEX presence_sweep_idx ON presence(is_online, last_heartbeat)` via `pb_migrations/`.

---

### APK self-updater: parallel chunked downloads

**The smell.** `ApkDownloader` uses a single HTTP stream for the ~96 MB release APK. On high-bandwidth, high-latency links 2–4 parallel `Range:` requests would shave ~30 % off transfer time.

**Why we haven't fixed it.** Mobile connections rarely benefit — physical-layer head-of-line blocking caps gain across parallel TCP streams. GitHub's release-asset CDN also throttles per-IP rather than per-connection, which further caps the win. The implementation cost is real: ~200 lines, breaks streaming SHA-256 (chunks arrive out of order so the file must be fully reassembled before hashing), and the new race conditions (one chunk fails, retry just that chunk) are easy to get wrong.

**When to revisit.** Only if user reports of slow downloads persist after the foreground-worker + resumable fix lands and median download time stays > 3 minutes on Wi-Fi. The better first move is fronting GitHub Releases with CloudFlare R2 / Bunny so throughput is fixed at the source.

---

### APK self-updater: install hand-off when app is backgrounded

**The smell.** When `ApkDownloadWorker` reaches `SUCCEEDED` while the app is not foreground, `SettingsViewModel.handleDownloadProgress` calls `apkInstaller.install(...)` which uses `context.startActivity(intent)` with `FLAG_ACTIVITY_NEW_TASK`. On API 29+ this can be silently suppressed as a background-activity-launch — the user sees the notification disappear but no installer prompt.

**Why we haven't fixed it.** The simple fix (replace auto-install with a "Tap to install" `PendingIntent` on the foreground notification's terminal state) requires another notification path + UI tweak; in practice the user is usually in Settings while watching the dialog, so the BAL-restriction case is uncommon.

**When to revisit.** When users report that downloads complete silently with no install prompt. Surface the install via a tap-to-install notification on `WorkInfo.SUCCEEDED`.

---

### Timer alarms: dual schedule path (sender VM + step-6 observer)

**The smell.** `ChatViewModel.sendTimerCommand` calls `TimerAlarmScheduler.schedule()` on send success (`app/src/main/java/com/firestream/chat/ui/chat/ChatViewModel.kt:284`). Step 6 of the dot-commands-timer plan adds a message observer that also schedules whenever a TIMER message arrives — including the local optimistic insert from the same send. Both paths converge to the same alarm thanks to `FLAG_UPDATE_CURRENT`, so it's idempotent, but every send burns two AlarmManager binder transactions when one would do.

**Why we haven't fixed it.** The sender-side schedule predates the observer — without it, step 5 wasn't testable end-to-end on a single device. Once step 6's observer lands and is verified to fire on the local insert too, the sender path is dead code.

**When to revisit.** As part of step 6: rip out the `timerAlarmScheduler.schedule()` block in `sendTimerCommand` and let the observer be the single owner. The banner-flip on `INEXACT_FALLBACK` moves to the observer alongside.

---

## How to use this file

- **Add entries** when you consciously decide not to fix something you noticed. Record the file paths, the reason, and the trigger condition.
- **Remove entries** when the underlying issue goes away (e.g. the file gets rewritten, the dependency is no longer needed, the risk goes away with new tests).
- **Don't let entries age silently.** If an item has been here for more than ~6 months with no movement, either act on it or update the reasoning.
