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

**Why we haven't fixed it.** Root cause is the `BuildConfig.DEBUG` guard in `MessageRepositoryImpl` (documented in `CLAUDE.md`: encryption disabled in debug to avoid key-loss during development). The test was written against the debug path. A proper fix either (a) mocks both paths and runs the assertion against whichever matches `BuildConfig.DEBUG`, or (b) injects the Signal path behind a test-only seam. Neither is a drive-by change, and the test provides no signal the debug suite doesn't already.

**When to revisit.** Same trigger as the Robolectric item above: when `testReleaseUnitTest` becomes a CI gate. Until then, keep running the debug suite as the default.

---

## Declined — not worth the churn

### Split `ChatRepository` (30 methods) and `MessageRepository` (25 methods) into smaller interfaces

**The smell.** Both interfaces are god-interfaces mixing concerns (chat CRUD + group admin + invite links + organization + broadcasts for `ChatRepository`; sending + media + reactions + receipts + search + starring + pinning for `MessageRepository`). An SRP purist would split them into 5 sub-interfaces each (see the April 2026 audit for a proposed decomposition).

**Why we're not doing it.** On its own, splitting the interfaces doesn't fix the underlying problem — it just shifts each ViewModel from injecting one fat interface to injecting 3–5 narrower ones. `ChatViewModel` would gain dependencies, not lose them. The only way to make the split worthwhile is to pair it with a broader rethink: promoting the `ui/chat/Chat*Manager.kt` classes into `domain/usecase/` and routing ViewModels exclusively through those, so the fine-grained repository interfaces only exist inside the use-case layer. That's a 2–3 session effort with significant churn to every ViewModel, and the clarity gain is marginal in a codebase where the current pattern is already well-understood and tested.

**When to revisit.** Only if/when we commit to a broader "use cases everywhere" architectural shift. Half-doing it (interface split without use-case promotion) is pure churn with no payoff. If the sprawl starts actively hurting onboarding or code navigation, do both halves together or not at all.

**Related:** finding #7 from the April 2026 audit; the updated use-case policy in `CLAUDE.md` already documents the pragmatic "managers as escape hatch" pattern.

---

## How to use this file

- **Add entries** when you consciously decide not to fix something you noticed. Record the file paths, the reason, and the trigger condition.
- **Remove entries** when the underlying issue goes away (e.g. the file gets rewritten, the dependency is no longer needed, the risk goes away with new tests).
- **Don't let entries age silently.** If an item has been here for more than ~6 months with no movement, either act on it or update the reasoning.
