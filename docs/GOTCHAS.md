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

- **Composable param-count ceiling (~15).** ART rejects composables with too many
  explicit params with a `VerifyError` **on first render**, not at compile time.
  Collapse callbacks into an `@Immutable *Callbacks` data class (see
  `MessageBubbleCallbacks`). Bit us as a chat-open crash, fixed in `00b15da`.
- **Local-vs-remote image model: synchronous `remember`, not `produceState`.** For
  `AsyncImage` sources that prefer a local file over a URL, resolve with
  `remember(localUri) { File(it).takeIf { exists() && isFile && canRead() } }`.
  `produceState(initialValue = false)` renders one frame with the wrong source and made
  the cold-start spinner run to completion (1.6.4 fix, `ebd7b14`).
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
- **Shared-storage files: `exists()` is not enough.** MediaStore files from a prior
  install can pass `File.exists()` yet throw `EACCES` on open. Gate with
  `exists() && isFile && canRead()`.

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
