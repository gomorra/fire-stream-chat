# Gotchas

<!-- last-verified: 2026-07-18 -->

Hard-won, host-independent traps promoted from local session memory so that **cloud
agents** (which only see the git checkout, never `~/.claude/`) benefit too. Each entry:
the trap, the fix, and where it bit us. Machine-specific quirks (Gradle daemon
flakiness, emulator flags, signing paths) deliberately stay out — they live in the
local memory store and would mislead a cloud sandbox.

Add here when a lesson is (a) not derivable from the code, (b) independent of the
developer machine, and (c) likely to recur. Named, structural conventions belong in
[PATTERNS.md](PATTERNS.md) instead.

## Compose / UI

- **Kotlin block comments nest — never write a literal `/` + `*` inside a KDoc.**
  A wildcard mime type spelled out in a KDoc (`image` + slash + star) opens a
  *nested* comment that is never closed, and the file fails to compile with a
  confusing "Missing '}'" pointing dozens of lines away plus "Unclosed comment"
  at EOF. Line comments (`//`) are unaffected. Bit us writing the share-sheet
  mime-normalisation KDoc; say "a wildcard" in prose instead.

- **Composable param-count ceiling (~15).** ART rejects composables with too many
  explicit params with a `VerifyError` **on first render**, not at compile time.
  Collapse callbacks into an `@Immutable *Callbacks` data class (see
  `MessageBubbleCallbacks`). Bit us as a chat-open crash, fixed in `00b15da`.
- **…and the composable *body* counts too, not just its parameters.** The same
  `VerifyError` came back to `MessageBubble` on a build with the callbacks bundle
  already in place: what a dex method is rejected for is **register pressure**, and
  parameters are only part of the frame. `MessageBubble` had grown to a single
  ~900-line composable needing **296 registers**, and the crash named `v288` —
  above the 255 that an 8-bit register operand can address. Fixed by splitting the
  body into `MessageBubbleBody` and `MessageContextMenu`, which brought it to 250.
  Two things make this expensive to rediscover, so check the count rather than
  waiting for a device to refuse the class:
  - **Nothing under `app/src/test/` can see it.** Robolectric runs on the JVM and
    the JVM has no dex verifier, so every test passes while the app crashes.
  - **Release builds hide it.** R8 optimises the method under the ceiling, so a
    release APK works while every debug build crashes on chat open. "It works on
    my device" is not evidence unless the device is running a debug build.

  The check, on any built APK, no device required:
  ```bash
  unzip -o app/build/outputs/apk/firebase/debug/app-firebase-debug.apk 'classes*.dex' -d /tmp/dex
  $ANDROID_HOME/build-tools/35.0.0/dexdump -d /tmp/dex/classesN.dex |
      grep -A3 "name          : 'MessageBubble'" | grep registers
  ```
  Anything approaching 256 is a rebuild of this crash. Nothing else in the app is
  close: the next highest is `PollBubble` at 185.

  Scripting that sweep across all `classes*.dex` is the practical form of it — but
  **`dexdump`'s output is not valid UTF-8** (it prints raw string-pool bytes), so a
  reader that decodes strictly dies partway through with `UnicodeDecodeError` on a
  byte like `0xc0`. Decode with `errors="replace"`; the `registers:` lines are ASCII
  and survive intact.
- **Local-vs-remote image model: synchronous `remember`, not `produceState`.** For
  `AsyncImage` sources that prefer a local file over a URL, resolve with
  `remember(localUri) { File(it).takeIf { exists() && isFile && canRead() } }`.
  `produceState(initialValue = false)` renders one frame with the wrong source and made
  the cold-start spinner run to completion (1.6.4 fix, `ebd7b14`).
- **`DateRangePicker` reports UTC midnights, not local days.** `selectedStartDateMillis` /
  `selectedEndDateMillis` are UTC-anchored, while everything you filter with them
  (`Message.timestamp`) is local wall-clock. Feed them straight into a range comparison
  and, in any zone east of UTC, a message sent late on the last selected day falls
  outside it. Re-anchor explicitly: read the UTC calendar's y/m/d, rebuild in the default
  zone, and take end-of-day as *start of the next day minus 1 ms* — `+24h` overshoots on
  a DST-shortened day. See `ui/search/SearchFilterBar.kt` (`utcDayToLocalStart` / `…End`).

- **Freeze list order in the presentation layer.** For UI lists that would reorder
  mid-interaction (e.g. emoji Recents), snapshot the order with `remember { list }` per
  open session and keep the underlying flow live. Don't add a `delay` debounce in the
  ViewModel (`08fe2b1`).
- **IME inset plumbing for bottom-anchored screens.** A screen with a bottom-anchored
  input needs both `android:windowSoftInputMode="adjustResize"` on the activity/manifest
  entry *and* `Modifier.consumeWindowInsets(padding)` placed between `.padding(padding)`
  and `.imePadding()`. Skip either half and insets get double-applied or the composer
  hides under the keyboard. Established in `a972533`. Exception: `ChatScreen` replaces
  the blanket `.imePadding()` with a measure-time `max(ime − navBars, emoji panel)`
  bottom region (`imeOrPanelHeight`) — same net inset, but it lets the keyboard slide
  over/off an always-mounted emoji panel. Note `WindowInsets.ime.getBottom()` reads
  *raw* insets (consumption only affects the padding-modifier family), hence the
  explicit `− navigationBars` subtraction there. Blanket `imePadding()` stays the rule
  for simple bottom-anchored screens (`ListDetailScreen`, `ImagePreviewScreen`).
- **A drag sends a target, never a step worked out from `rememberUpdatedState`.** A
  `pointerInput` block reads composition state through `rememberUpdatedState`, which
  refreshes only on recomposition, and more than one pointer event can arrive before the
  next one: every Compose-test `swipe` does it, and so does a phone whose frames have
  fallen behind the finger. A step computed against that snapshot but applied to the
  *current* state counts every earlier event again, and the object runs off to its clamp.
  The overlay editor shipped exactly this (`a56cd055`); send the absolute target (finger
  position less the grab offset) and let the state holder clamp it. Same family as the
  crop frame that could be dragged only once (`9169ce31`).
- **A touch target on the screen edge is unreachable by finger — and the emulator hides it.**
  A handle drawn at the edge (a crop corner on a photo fitted edge to edge) sits in the
  system back-gesture strip, or the home strip at the bottom; the system takes a touch that
  *starts* there before the app sees it, and half the target is past the glass anyway. A
  mouse in the emulator never triggers a gesture, so everything works there. Keep the whole
  target clear of `WindowInsets.systemGestures` (union `safeDrawing`, for cutouts and a
  landscape nav bar), and move only the *content* in — keep the gesture layer full-size, or
  the margin becomes a dead strip exactly where the finger lands.
  `Modifier.systemGestureExclusion` is not the fix: it cannot exclude the home strip and is
  capped at 200 dp per edge. Worked example: `CropGeometry.reachableInsets` — which keeps
  only a third of full clearance by choice (full clearance cost about a quarter of the
  photo's width), relying instead on each grip's invisible grab area reaching 48 dp *into*
  the frame, well past the strip. An inward-only hit area needs the drag to keep the
  finger-to-grip gap, measured at touch-*down*: `detectDragGestures` calls back only after
  touch slop, so record the down position yourself (a non-consuming `Initial`-pass
  `awaitFirstDown`). Accumulating `dragAmount` instead loses the slop, and the grip lags.
- **Two overlays showing the same content must not cross-fade over a third.**
  Closing the fullscreen viewer in the same frame as opening the send preview over
  it — both black, both drawing the photo at `Fit` — looked like it should be
  seamless. It is not: with the viewer at alpha 1−t and the preview at t, the chat
  list beneath both shows through at t(1−t), a quarter at the midpoint, so the photo
  visibly dips and comes back. Keep the lower overlay composed until the upper one
  has *settled* (`ui/components/OnEnterSettled.kt` watches
  `AnimatedVisibilityScope.transition.currentState`), then close it under an opaque
  cover. The same hand-off had two more traps stacked on it. **A new Coil model is
  a new cache key, so its first frame is blank:** the preview rendered a
  byte-identical *copy* of the viewer's file, which shares nothing with it under
  Coil's default `path:lastModified` key. Give the source request an explicit
  `memoryCacheKey` and the destination request a `placeholderMemoryCacheKey` naming
  it — Coil draws the cached bitmap from frame one and fades the fresh decode in over
  it, keeping the placeholder opaque because it came from the cache. And **a
  progress scrim published before a fast operation flashes:** gate `Preparing` on
  the slow path (a download), not on the operation as a whole. Bit us as Phase 6's
  "pops away and comes back" (`.claude/plans/image-editor.md`, Phase 6 items 8, 9,
  14).

## Coroutines / lifecycle

- **`delay`-then-act inside `viewModelScope` dies silently on navigation.** Any
  debounced side effect (send, persist) must use the injected `@ApplicationScope`
  scope and flush in `onCleared()`. See PATTERNS.md
  ["DataStore writes need @ApplicationScope"](PATTERNS.md#datastore-writes-need-applicationscope)
  for the persistence variant.
- **`BroadcastReceiver.goAsync()` work needs IO dispatcher + timeout.** Wrap in
  `withContext(Dispatchers.IO) { withTimeoutOrNull(8_000L) { ... } }` to stay under the
  ~10s receiver budget; do fast local work first, slow remote work second.
- **List-shaped `StateFlow` observers must diff per id.** Never
  `forEach { reactTo(it) }` on each emission — keep a `Map<id, snapshot>` and react
  only to deltas, even when the side effect is idempotent (binder calls etc. are not
  free).

## Room / data

- **Initial `null` from a Room flow means "not loaded yet", not "gone".** Detail
  ViewModels must gate `isDeleted` / `isAccessDenied` flags on a prior non-null
  emission, or every screen-open flashes the deleted state.
- **A soft-deleted row is invisible to a text search and visible to a filter-only one.**
  `softDeleteMessage` blanks `content` but leaves `mediaUrl` intact, so `content LIKE
  '%q%'` can never match a tombstone — which quietly hides the fact that a query with *no*
  content predicate (a browse by type, say) will return it and render its thumbnail. Any
  new query over `messages` that can run without a content predicate needs
  `AND deletedAt IS NULL` explicitly. Regression test:
  `MessageDaoSearchFilterTest.photos browse excludes a soft-deleted image`.
- **Optional filters: nullable/zero-valued params, not `@RawQuery`.** Room keeps verifying
  a query written as `(:type IS NULL OR type = :type)` / `(:flag = 0 OR …)`; dropping to
  `@RawQuery` to build predicates dynamically gives that up. Put a `(:query = '' OR
  content LIKE …)` short-circuit first so the no-query path doesn't scan every row's
  content against `'%%'`. See `MessageDao.searchMessages`, whose one query serves both the in-chat and the global scope via `(:chatId IS NULL OR chatId = :chatId)`.

- **Shared-storage files: `exists()` is not enough.** MediaStore files from a prior
  install can pass `File.exists()` yet throw `EACCES` on open. Gate with
  `exists() && isFile && canRead()`.
- **Firestore echoes your own write under its client-set id before the server has it.**
  Message ids are Room row ids, so `document(id).set()` fires the snapshot listener at once
  with that id and the payload's `status = SENT` while `metadata.hasPendingWrites()` is still
  true. A reconcile that trusts it flips SENDING → SENT with nothing on the backend. The
  acknowledgement changes no field, only metadata, so listen with `MetadataChanges.INCLUDE`
  or it never arrives; then gate own-message status on `!hasPendingWrites`
  (`RawMessage.hasPendingWrites`, checked in `MessageRepositoryImpl.reconcileRawMessage`).
  Regression: `MessageRepositorySnapshotTest.a pending echo of our own write leaves the
  local SENDING row alone`.
- **A retried Firestore write must not `set()` over a document that may already exist.**
  `firestore.rules` lets any participant `update` a message, so a blind re-`set()` wipes the
  recipient's `readBy` / `deliveredTo` / `reactions`. Retry with `waitForPendingWrites()`
  followed by a create-if-absent transaction — in that order: transaction reads go to the
  server and cannot see a first-attempt write the SDK has persisted but not flushed, so
  without the flush the transaction creates the doc and the replay overwrites it.
  (`FirestoreMessageSource.writeMessage`.)
- **An unsent message row's outbox columns exist only on `MessageEntity`.**
  `outboxRecipientId`, `outboxCiphertext`, `outboxSignalType` and `outboxAttempts` are not on
  the domain `Message`, so `insertMessage` / `replaceMessage` of a
  `MessageEntity.fromDomain(row.toDomain().copy(…))` resets them. On a SENDING or FAILED row
  that loses the peer (`OutboxSender` then refuses the send rather than guess plaintext), the
  attempt count (a re-attempt would blind-`set()` over a message that already landed) and the
  ciphertext (the message is encrypted a second time). Change an unsent row with a column
  `UPDATE` — `updateSendProgress`, `setPinned`, … — and let only `OutboxSender`'s SENT replace
  clear them. Regression: `MessageDaoOutboxColumnsTest`.

## Testing

- **MockK `relaxed = true` returns a mock, not `null`, for nullable types.** A
  `Foo?`-returning stub silently defeats `?: return` guards; stub explicitly with
  `coEvery { fn(any()) } returns null` when the null path is the one under test.
- **`backgroundScope` + `advanceUntilIdle()` does not deliver flow emissions.** When
  testing a component that owns a never-completing collector (`Chat*Manager`,
  `ChatMessageLoader`), passing `backgroundScope` as its scope makes every emission
  vanish — the collector appears to run but the state under test stays at its initial
  value, which reads exactly like a broken production diff. Passing the `runTest`
  scope itself instead hangs the test on the collector (`UncompletedCoroutinesError`).
  Use a root scope sharing the test dispatcher —
  `CoroutineScope(coroutineContext + SupervisorJob())` — and cancel it in `@After`.
  See `ChatMessageLoaderReactionCueTest.startLoader()`.
- **A paused `mainClock` never sees a bare state write.** With
  `composeTestRule.mainClock.autoAdvance = false`, setting a `mutableStateOf` from the
  test thread and then calling `advanceTimeBy` / `advanceTimeByFrame` runs frames the
  recomposer does nothing in: the write's apply notification is only sent by
  `waitForIdle()`, and `waitForIdle()` on a paused clock sends it but yields no frame
  to recompose in. Each half on its own composes nothing, so an assertion that
  something has *not* happened yet passes for the wrong reason. The order is write →
  `waitForIdle()` → advance the clock (`runOnIdle { … }` alone is not enough), and an
  `AnimatedVisibility` must be composed hidden and *then* shown — one composed visible
  from the start skips its enter animation. See `ui/components/OnEnterSettledTest.kt`.

## Platform / dependencies

- **Media3 is pinned to 1.9.0.** 1.10.x raises the minimum `compileSdk` to 36; this
  project is `compileSdk 35` on AGP 8.7.3 (AAR-metadata errors otherwise). Revisit on
  the next AGP/compileSdk upgrade.
- **Media3 `Transformer` must be built, started, and cancelled on a Looper thread.**
  `VideoTranscoder.transcode` runs build+start under `Dispatchers.Main` inside
  `suspendCancellableCoroutine`, and `invokeOnCancellation` posts `transformer.cancel()`
  back to the main looper. Don't "optimize" it onto `Dispatchers.IO` — it throws.
  Listener callbacks arrive on the starting looper. Transcoding is device-only; the JVM
  suite covers only the pure dimension math (`VideoTranscoderLogicTest`).
- **WorkManager typed `setForeground` needs a manifest merge on Android 14+.** Declare
  `<service android:name="androidx.work.impl.foreground.SystemForegroundService"
  android:foregroundServiceType="dataSync" tools:node="merge"/>` or the worker 400s.
- **A `com.android.test` submodule can't use a versioned `alias()`** for an
  already-loaded plugin — AGP rejects it. Use bare `id("com.android.test")` without a
  version (see `:baselineprofile`).
- **A `NotificationChannel`'s sound and vibration are frozen at creation.** Editing the
  code that builds one changes nothing on a device where it already exists —
  `createNotificationChannel` silently ignores sound/vibration/importance changes to a
  live channel. The only ways to change them are a **new channel id** or deleting and
  recreating (and Android *remembers deleted channels*: recreating the same id restores
  the user's old settings, so deletion isn't an escape hatch either). Two consequences:
  per-notification variation in sound has to be one-channel-per-variant (see
  `TimerNotificationChannel`, one per `TimerAlarmSound`), and **verifying a channel change
  requires upgrading over an existing install, never a clean one** — a fresh install
  always looks correct.
- **Notification sound follows the channel's `AudioAttributes`, not the ringer mode.**
  `USAGE_ALARM` routes to `STREAM_ALARM`, which vibrate/silent mode deliberately does not
  zero — that's why alarm clocks still sound on a silenced phone. Pair it with
  `CATEGORY_ALARM` to also pass Do Not Disturb (alarms are DND-allowed by default). This
  is a property of the *channel*, so it's subject to the freeze above.
- **`FLAG_INSISTENT` loops the sound until the notification is cancelled, with no
  platform timeout.** Nothing stops it on its own — unattended, it rings until the battery
  dies. Always pair it with `setTimeoutAfter(...)` as a backstop plus an explicit dismiss
  affordance; a Dismiss action alone only helps when somebody is present.

## Build tooling

- **A bare `Internal compiler error` from Kotlin can mean the locale, not the code.**
  Several test names in this repo contain an em dash (e.g.
  `ListDetailViewModelCoalesceTest` → `cooldown resets on each edit — a new bubble…`),
  and Kotlin writes one `.class` file per such name. On a machine where `LANG` is unset
  the JVM's `sun.jnu.encoding` falls back to `ANSI_X3.4-1968` (ASCII), the compiler
  cannot encode that path, and the build fails with nothing but
  `Internal compiler error. See log for more details` — the real
  `InvalidPathException: Malformed input or input contains unmappable characters` is
  only visible with `-e:` lines or in the daemon log. Check
  `java -XshowSettings:properties -version 2>&1 | grep encoding` before suspecting the
  diff. Fix is `LANG=C.UTF-8` in the *environment*: the Gradle daemon inherits its
  locale from the shell that starts it, so exporting it for one command needs a
  `./gradlew --stop` first. This repo sets it in `.claude/settings.json`; a plain
  terminal on a minimal container needs it in the shell profile. Confirmed 2026-09-09.

- **`./gradlew lint` crashes on this AGP/AndroidX combination — it is not your diff.**
  Two AndroidX detectors (`NonNullableMutableLiveDataDetector` on
  `AppLifecycleObserver.kt`, `RememberInCompositionDetector` on `ArchitectureTest.kt`)
  die with `IncompatibleClassChangeError`, which fails the whole `lint` task. It
  reproduces on a clean checkout of `main` with no local changes, so a crash naming a
  file you never touched is version skew between the lint jars and the detector APIs, not
  a regression. Confirmed 2026-09-08. **`lint` is deliberately not in the gate** — the
  project gate is `./gradlew test assembleDebug` (CLAUDE.md, `.github/workflows/ci.yml`),
  so don't add `lint` to CI or block a commit on it until the toolchain is bumped.
