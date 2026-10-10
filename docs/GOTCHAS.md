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

- **One SpanStyle per code point splits a multi-character emoji.** Compose breaks
  text shaping at every span boundary, so styling ❤, U+FE0F, the joiner and 🔥
  as four adjacent spans renders the heart on fire as a heart *plus* a flame —
  even when all four carry the same `fontSize`. Same for the family faces, a
  skin-toned hand, a flag and a keycap. Any regex that drives per-emoji styling
  has to match the whole sequence (base + modifiers, repeated across joiners),
  not one code point at a time; `EMOJI_REGEX` in `ui/chat/ChatUtils.kt` is the
  shape. A per-emoji size map keyed by char index has the same requirement from
  the other side: keyed at the sequence start, it only reaches the first code
  point and sizes the halves differently. Fixed in `d175db8e`. The base set
  matters too: Emoji 15.1 joins an *arrow* (🙂‍↕️, 🙂‍↔️), so text-default symbols
  (arrows, ▪ ◻, © ™) must count as a base when U+FE0F follows them, or the
  sequence splits into 🙂 plus a stray ↕️.

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
- **A full-screen overlay composed inside another screen does not take focus from it.**
  An overlay that is a sibling in the same composition — `ImagePreviewScreen` and the
  fullscreen viewers all sit next to `ChatScreen`'s Scaffold, not on the NavHost —
  covers the screen and changes nothing about focus: the composer underneath stays
  focused, the IME is re-shown over it when the app returns from a picker Activity,
  and every keystroke lands in a field the user cannot see. Opening such an overlay
  has to *take* focus, and `focusManager.clearFocus()` is not how — it leaves the
  focused field focused whenever the window itself is not (which is also what every
  Robolectric Compose test looks like, so the difference is testable). Request focus
  onto a target inside the overlay instead: the field it wants typed into, or a bare
  `Modifier.focusRequester(…).focusable()` box when it wants none. Bit us as a photo
  caption typed into the chat composer behind the send preview.
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
- **`Modifier.contentReceiver` hears nothing from the keyboard on a value-based `BasicTextField`.**
  That field (`value` / `onValueChange`, not `TextFieldState`) talks to the keyboard through
  `RecordingInputConnection`, whose `commitContent` returns false, and its `EditorInfo` names no
  content types. So a keyboard greys out its GIF and sticker tabs, or says the app does not take
  them. Wrap the field in `InterceptPlatformTextInput` instead. The interceptor wraps the
  connection the field makes: `EditorInfoCompat.setContentMimeTypes` on its `EditorInfo`, and
  `InputConnectionCompat.createWrapper` with an `OnCommitContentListener`
  (`ui/chat/KeyboardContentReceiver.kt`). Text input passes through untouched. In a Robolectric
  test the field starts its input session only after `dispatchWindowFocusChanged(true)` on the
  `AndroidComposeView`, and `onCreateInputConnection` on that view then returns the wrapped
  connection (`KeyboardContentReceiverTest`). `adb shell dumpsys input_method` shows the
  focused field's `contentMimeTypes` on a device.
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
  "pops away and comes back" (`docs/plans/image-editor.md`, Phase 6 items 8, 9,
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
- **Never await a backend write in front of an optimistic local insert.** A
  Firestore `update(...).await()` completes only on the server ack, so offline (or on
  a connection the phone still reports as live) it suspends for as long as the network
  is missing. `ChatMessageSender.sendMessage` awaited the typing-off write before
  calling the repository: with flight mode on, no text bubble appeared at all while a
  photo showed its clock at once, and swiping the app away cancelled the coroutine
  before the message ever reached Room — lost for good (fixed in `d199da08`, regression
  `ChatMessageSenderOfflineTest`). Best-effort remote writes on a send path run as a
  sibling `scope.launch`, never as a step before the insert; anything a send must do
  before its row exists has to be local.
- **List-shaped `StateFlow` observers must diff per id.** Never
  `forEach { reactTo(it) }` on each emission — keep a `Map<id, snapshot>` and react
  only to deltas, even when the side effect is idempotent (binder calls etc. are not
  free).
- **An RTDB write made without a connection is queued, and the queue flushes in order on
  the next connect.** `setValue()` never fails for lack of a socket — it waits. So a
  presence `online` written while disconnected, followed by `goOffline`'s `offline`, is
  replayed as online-then-offline the moment the socket returns, which on a backgrounded
  phone is typically when a push wakes the radio for a message sent to that user: the
  sender saw them flash "Online". Presence writes that only make sense over a live socket
  (the re-entry force-write in `RealtimePresenceSource.startPresence`) are gated on the
  last `.info/connected` value; the listener itself writes online on reconnect. Regression:
  `RealtimePresenceSourceTest.startPresence re-entry after a disconnect writes nothing until the reconnect`.
- **A waiter on shared in-flight work must not inherit the first caller's cancellation.**
  `CompletableDeferred.completeExceptionally` with a `CancellationException` makes every
  `await()` throw that cancellation, inside coroutines nobody cancelled. A `LaunchedEffect`
  then ends without a word, and a loop over a chat's downloads stops at that message. It shows
  as soon as a UI scope shares a flight with longer-lived callers: a sticker cell that scrolls
  away, and the chat-open scan waiting for the same download. `SingleFlight.run` calls
  `currentCoroutineContext().ensureActive()` when its `await()` is cancelled, and runs the
  block itself when the cancellation was not its own. A hand-written copy of the idiom needs
  the same check. Regression:
  `SingleFlightTest.a waiter runs the block itself when the first caller is cancelled`.
- **`catch (e: Exception)` around a suspend call also catches cancellation.** Most
  repositories wrap their calls in `try { … } catch (e: Exception) { Result.failure(e) }` or
  `resultOf`, so a coroutine cancelled while suspended in one gets a failure back and carries
  on. `CallService`'s ring timeout kept running after the call's cleanup cancelled it, and tore
  the call down a second time on another thread. Wrap a suspend call in `cancellableResultOf`
  instead, which lets the cancellation through. `CallRepositoryImpl` does. With any other
  repository, do not rely on `cancel()` to stop the code after its call: check that the work is
  still wanted before acting on the result. Regression:
  `CallRepositoryImplTest.a caller cancelled in any repository call stops instead of getting a failure`.
- **A Firestore listener on a parallel executor can deliver snapshots out of order.**
  `addSnapshotListener(executor, …)` hands every snapshot to the executor, and
  `Dispatchers.Default.asExecutor()` runs two of them on two threads. A flow that emits
  differences then sees a removal before the addition it follows. Map off the main thread on
  `Dispatchers.Default.limitedParallelism(1).asExecutor()`, which keeps the order
  (`FirestoreStickerPackSource`).
- **A snapshot filter that depends on the clock needs its own timer.** Filtering
  `typingUsers` by age inside the Firestore listener only re-runs when the document
  changes, so an entry whose typing-off write never landed (writer offline or killed)
  stayed "typing" until someone sent a message. `FirestoreChatSource.observeTypingUsers`
  re-emits when the oldest live entry ages out (`flatMapLatest` over a `delay`ing flow),
  on the same clock the filter uses. Regression: `FirestoreChatSourceTypingTest`.

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

- **Shared-storage files: `exists()` is not enough, and neither is `canRead()`.** MediaStore
  files from a prior install can pass `File.exists()` yet throw `EACCES` on open. Gate with
  `exists() && isFile && canRead()`, and still fall back when the open fails. `canRead()` can
  pass too, because `Android/media` and `Pictures/` go through the FUSE layer. A cache only
  this app reads belongs in `filesDir`, which skips that layer. The avatar cache lives there
  for this reason: on Android 17 its `Android/media` copies failed to load, and the URL
  fallback behind `canRead()` never ran (2026-10-07).
- **Firestore echoes your own write under its client-set id before the server has it.**
  Message ids are Room row ids, so `document(id).set()` fires the snapshot listener at once
  with that id and the payload's `status = SENT` while `metadata.hasPendingWrites()` is still
  true. A reconcile that trusts it flips SENDING → SENT with nothing on the backend. The
  acknowledgement changes no field, only metadata, so listen with `MetadataChanges.INCLUDE`
  or it never arrives; then gate own-message status on `!hasPendingWrites`
  (`RawMessage.hasPendingWrites`, checked in `MessageRepositoryImpl.reconcileRawMessage`).
  Regression: `MessageRepositorySnapshotTest.a pending echo of our own write leaves the
  local SENDING row alone`.
- **A listener sees your own Firestore `update()` before the `await()` returns.** The SDK
  applies the write to its cache and fires listeners at once, while `await()` waits for the
  server. `CallService`'s ring timeout awaited `endCall("timeout")` and only then logged the
  call, so its own "ended" echo reached the signalling listener first and was handled as the
  other side hanging up: every unanswered call was logged as `remote_hangup`. Record what you
  did before you write the status your own listener reacts to, or stop the listener first.
- **A retried Firestore write must not `set()` over a document that may already exist.**
  `firestore.rules` lets any participant `update` a message, so a blind re-`set()` wipes the
  recipient's `readBy` / `deliveredTo` / `reactions`. Retry with `waitForPendingWrites()`
  followed by a create-if-absent transaction — in that order: transaction reads go to the
  server and cannot see a first-attempt write the SDK has persisted but not flushed, so
  without the flush the transaction creates the doc and the replay overwrites it.
  (`FirestoreMessageSource.writeMessage`.)
- **A Firestore transaction is not latency-compensated — an `update()` is.** An `update()`
  lands in the SDK cache at once, so every snapshot after it already carries the new fields.
  A transaction's writes reach the cache only on commit, a server round trip later, and
  without a connection it fails instead of queueing. A listener that mirrors the document into
  Room therefore sees the *old* fields in between (any unrelated change to the doc — a typing
  indicator — fires it) and must not overwrite a newer local value with them. Hit when the chat
  preview became a newer-only transaction: `ChatDao.upsertRemote` keeps a strictly newer local
  preview. Regression: `ChatDaoUpsertRemoteTest.upsertRemote keeps a local preview newer than the snapshot's`.
- **A document missing from a Firestore query snapshot was not necessarily deleted.** A
  listener's first snapshot can come from the local cache, which holds whatever was read
  before and nothing after a reinstall. A mirror that deletes every local row the snapshot
  lacks wipes its table on such a snapshot. A snapshot that is from the server can still be
  older than a write this device made a moment later, when the collector is slow. So a
  mirror deletes only on a `DocumentChange.Type.REMOVED` change, which is sent for a document
  that was in the result and left it, and only a row with no local changes
  (`FirestoreStickerPackSource.observeOwnPacks`, `StickerDao.removeIfSynced`). Such a flow
  emits differences, so it needs `buffer(Channel.UNLIMITED)`: a `trySend` into a full default
  buffer drops a difference that never comes again.
- **A Firestore `get()` answers from the cache when the phone is offline.** It does not fail, and
  the answer looks like any other. Anything that records progress from the answer then records
  progress it never made. A sync cursor moved on a cache answer skips, for good, every message the
  server holds beyond it. Pass `Source.SERVER` to a `get()` whose answer moves a cursor or marks
  something as fetched, and let it throw offline (`FirestoreMessageSource.fetchFromServer`). A
  server answer can still have this phone's pending writes laid over it, so leave a document with
  `metadata.hasPendingWrites()` out of the cursor too. A listener has the same split:
  `snapshot.metadata.isFromCache`. Regression: `FirestoreMessageSourceTest.fetchMessages asks the
  server for the whole chat, oldest first`.
- **Room's `@Upsert` with a partial entity keeps the columns the object does not carry; a
  `REPLACE` insert does not.** `OnConflictStrategy.REPLACE` deletes the row and inserts the
  new one, so every column the new object lacks goes back to its default. `messages` is split
  for exactly this: the backend's columns are the embedded `MessageRecord`, which is also the
  partial entity a snapshot or sync upserts (`MessageDao.upsertRecord`), and the local columns
  — `localUri`, `isStarred`, the outbox bookkeeping — sit beside it on `MessageEntity`, out of
  the upsert's reach by construction. A partial entity needs every omitted `NOT NULL` column to
  carry a `@ColumnInfo(defaultValue = …)`, and a default is part of Room's schema hash, so
  adding one is a version bump. Regression: `MessageDaoOutboxColumnsTest`.

## Testing

- **`BreakIterator`'s grapheme data is the host JDK's, and CI's JDK is not yours.**
  `java.text.BreakIterator.getCharacterInstance()` segments by the Unicode data of
  the JVM that runs the test: on JDK 17, which `.github/workflows/ci.yml` pins,
  👨‍👩‍👧 is *five* clusters (three faces, two joiners), 🇩🇪 is two regional indicators
  and 👍🏽 is hand plus tone; on JDK 21 and on Android's ICU each is one. A test
  asserting "backspace eats one visible character" therefore passes locally on 21
  and fails in CI on 17 — which is exactly how it bit `ComposerEditTest` on the
  emoji-at-the-caret fix. Don't trust the platform for the emoji joins: re-apply
  ZWJ, emoji-modifier and regional-indicator pairing yourself
  (`ComposerValue.graphemeStartBefore`), which is a no-op where the host already
  joins them. To reproduce a CI-only failure of this shape, run the suite against
  the pinned JDK: `./gradlew :app:testFirebaseDebugUnitTest
  -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64`.
- **MockK `relaxed = true` returns a mock, not `null`, for nullable types.** A
  `Foo?`-returning stub silently defeats `?: return` guards; stub explicitly with
  `coEvery { fn(any()) } returns null` when the null path is the one under test.
- **`advanceUntilIdle()` stops once only `backgroundScope` work is left.** It leaves that
  work unrun. A component given `backgroundScope` as its scope then never gets going: a
  collector misses every emission, and a one-shot `launch` never starts. Both read exactly
  like a broken production diff. A coroutine that always finishes can take the `runTest`
  scope itself. One that may not finish would hang the test there
  (`UncompletedCoroutinesError`): a never-completing collector (`Chat*Manager`,
  `ChatMessageLoader`), or a call create the test never completes (`CallViewModelTest`).
  Give it a root scope that shares the test dispatcher, `CoroutineScope(coroutineContext +
  SupervisorJob())`, and cancel that scope in `@After`.
  See `ChatMessageLoaderReactionCueTest.startLoader()`.
- **`advanceUntilIdle()` runs every pending timeout.** It moves virtual time forward until
  nothing is scheduled, so a `withTimeoutOrNull` around a call the test has not completed yet
  times out before the test completes it. The code under test then takes its timeout path, and
  later assertions can still pass by accident. Use `runCurrent()` until the test has completed
  what the code waits on (`CallViewModelTest`).
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

- **A test tag inside a clickable parent is not in the merged semantics tree.**
  `clickable` and `combinedClickable` merge their children's semantics into one node. A child's
  content description is carried up, and its test tag is not. So `onNodeWithTag(tag)` on a child
  of a bubble's click target finds nothing, and `assertDoesNotExist()` on it passes for the wrong
  reason. Pass `useUnmergedTree = true` for a tag below a click target
  (`ui/chat/LottieStickerUiTest.kt`).

- **Robolectric records a network callback but never dispatches to it.**
  `ShadowConnectivityManager` keeps every callback passed to
  `registerDefaultNetworkCallback` in `shadowOf(cm).networkCallbacks`, and changing the
  shadow's networks fires nothing — a test that flips shadow state and waits will simply
  see the initial value forever. Drive the recorded callback yourself
  (`callback.onCapabilitiesChanged(network, caps)` / `onLost(network)`); build the
  capabilities with `ShadowNetworkCapabilities.newInstance()` +
  `shadowOf(caps).addCapability(…)`, which is also the only way to express a captive
  portal (INTERNET without VALIDATED). See `AndroidConnectivityObserverTest`.
- **The test JVM's code cache is 48 MB under C1-only, and the whole suite fills it.**
  `-XX:TieredStopAtLevel=1` (kept for the host-crash reasons in `app/build.gradle.kts`)
  drops the default `ReservedCodeCacheSize` from 240 MB to 48 MB. Around 1 440 tests
  including the Robolectric Compose screens, the worker logs `CodeCache is full. Compiler
  has been disabled` and whatever test runs next dies with
  `VirtualMachineError: Out of space in CodeCache for adapters` or a
  `NoClassDefFoundError: Could not initialize class …LazyListStateKt` — 1 to 35 failures
  in image-editor screen tests that pass alone, and that pass in a run of the four classes
  by themselves. Not a test bug: the fix is `-XX:ReservedCodeCacheSize=256m` on the unit-test
  worker, which is set. If the symptom returns at a larger suite, raise it again rather
  than bisecting tests. Seen 2026-09-12 (offline outbox step 8, two consecutive full runs).

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
- **A photo-picker `content://` grant does not outlive the process, and `cacheDir` can be
  purged.** A URI handed to a send is readable *now*, not later: a WorkManager attempt may run
  after a process death, when the grant is gone (`SecurityException` / `FileNotFoundException`)
  or the editor's `cacheDir/edits/` file has been reclaimed. `OutboxFiles.stage` copies every
  input that is not already a file the app keeps into `filesDir/outbox/<id>.<ext>` *before* the
  row is enqueued, and the row points at the copy. A staging failure fails the send at once
  (a FAILED bubble) rather than queue a row nothing can read. Regression: `OutboxFilesTest`,
  `MessageRepositoryMediaSendFailureTest`.
- **A heavily subsampled `BitmapFactory` decode can return a black bitmap.** It raises no
  error. It hits a large camera original shown small, which Coil's default decoder reaches
  by a power-of-two `inSampleSize`. Any request that shows a full-size photo small attaches
  `ScaledImageDecoder.Factory()`, which decodes through `ImageDecoder.setTargetSize`.
  Both the Shared Media grid and every avatar (`buildAvatarRequest`) do. Disabling hardware
  bitmaps does not fix it. Regression: `AvatarRequestTest`, `ScaledImageDecoderTest`.
- **WorkManager typed `setForeground` needs a manifest merge on Android 14+.** Declare
  `<service android:name="androidx.work.impl.foreground.SystemForegroundService"
  android:foregroundServiceType="dataSync" tools:node="merge"/>` or the worker 400s.
- **A `com.android.test` submodule can't use a versioned `alias()`** for an
  already-loaded plugin — AGP rejects it. Use bare `id("com.android.test")` without a
  version (see `:baselineprofile`).
- **Stop a service with `stopSelf(startId)`, not `stopSelf()`.** Pass the id of the latest
  start the service has handled; the system ignores the call while a newer start is queued
  (`CallService.stopIfIdle`). `stopSelf()` stops the service anyway, because the system
  queues each start on the main thread before `onStartCommand()` runs. The queued start
  then runs on the dying service, where `startForeground()` does nothing, so the state it
  publishes is never undone. If that start came from `startForegroundService()` after
  `stopForeground()`, the stop crashes the app instead: "did not then call
  `startForeground()`". No JVM test covers `CallService`.
- **WebRTC calls back on its signaling thread, and the factory owns that thread.**
  `PeerConnection.Observer` and `SdpObserver` run there, and `PeerConnectionFactory.dispose()`
  frees the factory's threads. Tearing a call down from one of those callbacks destroys the
  thread the callback is running on. Hop to the main thread first: `PeerSession` reports
  through an event channel, which `CallSession` collects there.
  `PeerConnection.close()` frees nothing: only `dispose()` releases the native connection and
  the observer it holds. `PeerConnectionFactory.builder()` makes an audio device module that
  nothing releases unless you pass your own and call `release()` after `dispose()`.
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
- **A sideloaded app starts without the access *Full screen notifications* on Android 14 and
  later.** The manifest's `USE_FULL_SCREEN_INTENT` does not grant it, and the installer cannot.
  It was off on a phone with Android 17 and on an emulator with Android 16. Without the access
  the system drops
  `setFullScreenIntent` without an error and shows only the notification, so a call does not
  wake the display. `FullScreenIntentAccess` reads the access and opens its settings page.
  On a test device, grant it with
  `adb shell appops set --uid com.firestream.chat USE_FULL_SCREEN_INTENT allow`.
- **Robolectric 4.14's `ShadowNotificationManager` has no switch for
  `canUseFullScreenIntent()`.** Put a mocked `NotificationManager` behind a `ContextWrapper`
  that overrides `getSystemService(String)`. Do not name the method in a MockK `verify` under
  an SDK below 34, where it does not exist: recording the call throws `NoSuchMethodError`.

## WebRTC / calls

Read from the classes of `stream-webrtc-android` 1.3.10 and its `-ui` artifact, and from the
Android 14 foreground-service rules. Only the first entry has run on a device, as a probe app on
the emulator. The checks for the rest are in [BACKLOG.md](BACKLOG.md) § *Pending on-device
verification*.

- **A `PeerConnectionFactory` without video codecs aborts the process on an offer with a video
  line.** WebRTC logs *No video codecs in common*, accepts the line, builds a receive stream from
  an empty codec list, and dies with `front() called on an empty vector`: a SIGABRT in
  `libjingle_peerconnection_so.so`, inside `setRemoteDescription`. No error comes back, so the app
  cannot end the call. Every FireStream build before video calls has such a factory, voice calls
  included. Never offer a video line on a guess. A caller offers one only to a callee whose user
  document carries `callVideoLine: true` (`CallRepository.createCall`), and that field is never
  written as `false` or removed. Libraries 1.3.0 and 1.3.10 behave the same.
- **A build before video calls applies the answer again on every snapshot of an answered call
  document, and ends the call when that fails.** Any write to `calls/{callId}` after the answer
  therefore hangs up a call such a build placed. `CallMediaPublisher` writes `media` only once
  both sides agreed on the video line, which such a build never does. Keep every new write to an
  answered call document behind the same rule.

- **Call `PeerConnection.getTransceivers()` once and keep what it returned.** Every call disposes
  the Java objects the call before handed out, and the one `addTransceiver` returned is among
  them. A transceiver kept from earlier then throws on its next use. `PeerSession` reads the list
  once, on the side that answers.
- **Pass no video constraint to `createOffer` when the video line is a transceiver.** A legacy
  `OfferToReceiveVideo = false` takes the receiving half off every video transceiver of the offer.
- **`RtpSender.setTrack(track, takeOwnership = true)` hands the track to the sender.** The sender
  then disposes it on the next `setTrack`. A track that several sessions share goes on with `false`.
- **A disposed track throws on every call, `removeSink` included.** Take every sink off a track,
  and the track off every sender, before its owner disposes it. `CallVideoSinks.close()` and
  `PeerSession.setCamera(null)` run before `WebRtcCallLocalMedia.dispose()` releases anything.
- **`VideoTextureViewRenderer` is single-use.** `onDetachedFromWindow` releases its EGL renderer,
  so a view that left its window draws nothing when it comes back. Make a new one.
- **A surface that arrives before `VideoTextureViewRenderer.init()` is dropped without a word.**
  Hand it over again after `init()`, through `onSurfaceTextureAvailable`. `CallVideoSinks` does,
  for a view made before the call has an EGL context.
- **`VideoTextureViewRenderer` sizes itself by the picture unless told to fill.** Offered a size
  that is not fixed, its default scaling type measures the view smaller than that size.
  `SCALE_ASPECT_FILL` takes the bounds and crops the picture.
- **A video view keeps the last picture it drew, and a track outlives the camera going off.**
  "Has video" is therefore not "has a track". `CallVideoSinks` counts a first frame per track and
  counts again after `awaitFrame`.
- **`CameraCapturer.stopCapture()` waits while the camera is still opening, and closes the device
  later, on the capture thread.** Never stop or dispose a camera on the main thread. Let the
  capture thread work its queue off before `SurfaceTextureHelper.dispose()`: that call quits the
  thread ahead of whatever is still queued, the close included (`LocalCamera.awaitCaptureThread`).
- **`startForeground` with the camera type throws on Android 14 and later.** It needs the `CAMERA`
  runtime permission and a visible app, on top of `FOREGROUND_SERVICE_CAMERA` and the type on the
  `<service>`. Without them it throws a `SecurityException`, or an `IllegalStateException` when no
  foreground start is allowed. `CallService` catches both, leaves the camera off and keeps the call.

## Build tooling

- **`Could not read workspace metadata from ~/.gradle/caches/<version>/transforms/<hash>/metadata.bin`
  is a truncated cache entry, not the diff.** A crash, or a disk that goes read-only, in the middle
  of a build leaves zero-byte `metadata.bin` files in Gradle's transform cache. Gradle 8.11 then
  fails on them in every later build and never rebuilds them. Unit tests can still pass while
  `assembleDebug` fails at `check…DuplicateClasses`. List them with
  `find ~/.gradle/caches/8.11.1/transforms -maxdepth 2 -name metadata.bin -size 0` and delete the
  directories they sit in. A session that must not touch the shared Gradle home builds with a
  private one instead: put `org.gradle.java.home` into `<dir>/gradle.properties` and run
  `./gradlew -g <dir> --no-daemon assembleDebug`, with `<dir>` under the ignored `build/`. It
  downloads everything once. Delete `<dir>` afterwards.
- **A `VirtualMachineError: Out of space in CodeCache` in a long Gradle run is the daemon, not the diff.**
  Running the full unit suite and `assembleFirebaseDebug` in *one* invocation on a cloud
  container (2026-09-18, ~12 min) ended with D8 failing on a third-party AAR and, on an
  earlier attempt, `compileFirebaseDebugJavaWithJavac` dying with
  `InternalError: NoSuchMethodException … MethodHandle.linkToStatic`. Both are the same
  thing: the daemon JVM had exhausted its CodeCache ("for adapters" / "for method handle
  intrinsic" in the `Caused by` chain), after which any further lambda or method-handle
  bootstrap fails with a misleading `NoSuchMethodError`. `./gradlew --stop`, then run the
  test task and the assemble task as two invocations — each was green on a fresh daemon.
  A permanent `-XX:ReservedCodeCacheSize=…` in `org.gradle.jvmargs` is the real fix if it
  recurs.

- **A pre-commit hook that reads `/dev/tty` hangs, or errors, on any headless commit.**
  `.claude/hooks/ask-simplify.sh` prompts interactively ("Run /simplify before
  committing? [y/N]") before letting a `git commit` through. That's fine in a terminal
  session, but a plan-runner step, a CI job, or any other session with no controlling
  terminal has nobody to answer it — the read either hangs forever or fails outright,
  and either way the commit never lands. The hook now guards on `PLAN_RUNNER=1` (set by
  `scripts/run-plan.sh`) and on `/dev/tty` being unreadable at all, letting the commit
  through untouched in both cases. Any *other* hook that reads `/dev/tty` needs the same
  guard before it can be trusted headless. See `docs/plans/done/plan-runner.md` §2.7.

- **A headless Claude session cannot write under `.claude/` — and an allow rule does not help.**
  In `claude -p`, every `Edit`/`Write` of a file below `.claude/` (which is why plans moved to
  `docs/plans/` on 2026-09-14), and every
  Bash command whose text names such a path as a write target or even a `cp` source, is refused
  with "requested permissions to edit … which is a sensitive file". Nobody can answer the prompt,
  so the call is silently denied and the session reasons around it. `--allowedTools
  "Edit(.claude/plans/**)"` does **not** override the guard (probed 2026-09-13 in a scratch repo).
  `Read` and `git *` are exempt, which is why a step session can `git mv` a plan out, edit it,
  and `git mv` it back — a detour, not a design. Anything a headless session must edit belongs at
  a normal path. Interactive sessions only see a permission prompt.

- **A headless session's compound shell commands are denied silently, even when every part is allowlisted.**
  `claude -p` with a prefix allowlist (`Bash(grep *)`, `Bash(find *)`, …) refused all six compound
  calls of the first real plan-runner step (2026-09-14): `for … do … done` loops, `for f in $(find …)`,
  `grep … <(git diff …)`, `find … -exec grep … \;`, and a plain `ls a; ls b; echo $LANG` chain. The
  session gets no message — `permission_denials` in the result object carries only the tool input —
  and spends the turn, then another working around it. Widening the allowlist to `Bash(for *)` or an
  interpreter would route around every deny rule, so the fix is on the prompt side: tell the session
  to issue one plain command per Bash call and to use the Grep and Glob tools for anything that
  looks across files (`scripts/plan-runner/step-prompt.md`). Interactive sessions never see this —
  they get a permission prompt instead.

- **`producer | grep -q` under `set -o pipefail` can fail on a match.** `grep -q` exits at the
  first match. A producer that is still writing then dies of `SIGPIPE`, and `pipefail` turns
  that into a failed pipeline. `pr_step_shipped` in `scripts/plan-runner/lib.sh` is this
  shape (`awk … | grep -qE '^\*\*Shipped\*\*'`), and `scripts/run-plan.sh` runs under
  `pipefail`. awk writes to a pipe in 4096-byte blocks, so a step section over 4 KB with its
  `**Shipped**` line in an early block is a race. On 2026-10-03 the driver reported "no
  **Shipped** line under step 3" for `docs/plans/stickers-and-gifs.md` while the line was
  committed under the heading, in an 8 KB section. The cause was inferred from the code, not
  reproduced. Until the check is `grep … >/dev/null`, which reads its whole input, put a long
  step's `**Shipped**` block at the end of the step's section: the match is then in awk's last
  write.

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

- **Reference numbers bundled with a skill can be older than the live page they came from.**
  The `claude-api` skill ships a cached copy of Anthropic's cost guide (`shared/cost-optimization.md`,
  dated 2026-06-24). On 2026-09-19 its advice ("start with Opus", Opus matching Fable at 60% of the
  cost, the re-run-failures dollar figures) had all been superseded on the live page by the
  Fable 5.1 measurements — a different headline recommendation, not just different decimals. The
  skill names its sources (`shared/live-sources.md`); before a model, effort or price decision
  rests on a bundled number, fetch the live page and quote that, with the date. This is what
  `scripts/plan-runner/benchmark.md` does.

- **Under `set -o pipefail`, never pipe into a reader that can stop early.**
  `writer | grep -q PATTERN` fails at random although the pattern matches. `grep -q` exits at
  its first match, the writer's next write dies of SIGPIPE, and pipefail reports the writer's
  141 as the pipeline's status. `grep -m1`, `head` and `sed q` do the same. The miss rate
  grows with what the writer still has to write, so a short fixture passes every time.
  Capture the text and match it from a here-string instead:
  `text=$(writer); grep -q PATTERN <<< "$text"`. Where only the output matters, `|| true`
  after the pipeline is enough. `grep PATTERN >/dev/null` is not a fix to rely on: GNU grep
  stops matching at the first match there too, and only its own draining of the pipe keeps
  the writer alive. `scripts/plan-runner/lib.sh` read a shipped plan step as not shipped
  about one time in thirteen this way (2026-10-03, `efab2741`).

- **A resumed `claude -p` session reports its cost cumulatively.**
  `total_cost_usd` in the result of `claude -p --resume <id>` is the session's total so far, not
  the invocation's. `--max-budget-usd` still caps each invocation. Summing every result therefore
  counts a resumed session twice: video-calls step 2 read 15.05 USD instead of 7.82. Count each
  result's increase over the same session's previous result, and give a resume what is left of its
  budget. `scripts/plan-runner/report.sh` and `lib.sh` (`pr_cost_delta`, `pr_budget_left`) do.
  A total below the previous one is a per-invocation figure from an older CLI. Seen on CLI 2.1.288:
  0.0409 → 0.0478 over one resume (2026-10-03).

- **A cloud session does not stop at the usage limit: it goes on on cloud credits.**
  A headless driver that must stay on the subscription reads the session's stream
  (`--output-format stream-json --verbose`) and stops the session itself. The signal is a
  `rate_limit_info` with `status: "rejected"`, or a `unifiedWindows` entry at `utilization` ≥ 1.
  Nothing in the CLI's output says that cloud credits are paying, and the stream's fields do not
  show which pool pays. `scripts/run-plan.sh` stops the session on a desktop too, where it would
  end at the limit by itself. Two cloud sessions, one of them a plan-runner step, were seen
  working while `rejected`, with overage rejected too (2026-10-03).
