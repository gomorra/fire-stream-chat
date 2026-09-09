# FireStream Patterns

A grep-able catalogue of named conventions used across the codebase. Every entry has a runnable file:line citation so a new agent can clone the pattern by reading one example.

When adding a new convention, append a section here in the same shape: **definition** → **when to use / not use** → **concrete example** → **the trap**.

> **For agents:** if a file's `AGENT-NOTE` block cites a pattern by name, look it up here first. Do not invent a variation.

---

## Chat-Manager slice-ownership

**Definition.** Every `Chat*Manager` in `ui/chat/` receives a shared `MutableStateFlow<ChatUiState>` and a `CoroutineScope`, owns one conceptual slice of the state, and mutates only that slice via `_uiState.update {}`. Managers never read or write each other's slices and never call each other. `ChatViewModel` is the only composition root.

**Use when.** Adding multi-step orchestration that's specific to the chat screen and isn't reusable enough to live in a domain use case.
**Don't use when.** A single repository call would do — go directly from the ViewModel to the repository (see *Use-case vs. direct-repo* below).

**Example.** `app/src/main/java/com/firestream/chat/ui/chat/ChatDictationManager.kt:23–50` — clean instance: takes the shared state holder, owns only the `dictation` slice, surfaces side-channels via a separate `MutableSharedFlow<DictationCommit>`. The constructor signature is the canonical shape.

**Trap.** Drifting into a second slice (e.g. writing `session.error` from a poll manager) compiles fine but breaks the invariant that lets `ChatViewModel` reason about state ownership. If you need to surface an error, expose a result and let `ChatViewModel` map it.

---

## ChatUiState slice composition

**Definition.** `ChatUiState` is a single data class composed of six nested slices: `MessagesState`, `ComposerState`, `OverlaysState`, `SessionState`, `DictationState`, `CommandsState`. New chat-screen fields go in the slice that matches their semantic.

**Use when.** Adding a new field to `ChatUiState`.
**Don't use when.** The field is global to the app — it belongs in a different ViewModel or DataStore.

**Example.** `app/src/main/java/com/firestream/chat/ui/chat/ChatViewModel.kt:62–69` — slice composition. Per-slice files at `ui/chat/Chat{Messages,Composer,Overlays,Session,Dictation,Commands}State.kt`.

**Trap.** Multi-field writes across slices must collapse into a single `_uiState.update { it.copy(a = it.a.copy(...), b = it.b.copy(...)) }`. Two consecutive `.update {}` calls emit an inconsistent intermediate state that observers will recompose against.

---

## AppError boundary wrapping

**Definition.** Every UiState's `error` is `AppError?` (never `String?`). At ViewModel/Manager boundaries that catch a `Throwable`, wrap with `AppError.from(e)`. For user-input validation, construct `AppError.Validation("display text")` directly. UI renders `uiState.error?.message`.

**Use when.** Catching a `Throwable` that should reach the UI, or surfacing a validation message.
**Don't use when.** The error is purely internal (logging only) — log it and recover, don't store it.

**Example.** `app/src/main/java/com/firestream/chat/ui/chat/ChatMessageActions.kt:21` — canonical `.onFailure { e -> _uiState.update { it.copy(session = it.session.copy(error = AppError.from(e))) } }` pattern, used 5× in that file alone. Validation example: `app/src/main/java/com/firestream/chat/ui/chatlist/ChatListViewModel.kt:209` — `AppError.Validation("You can pin up to 3 chats")`.

**Trap.** Storing a raw `Throwable.message` as `String?` works but loses the subtype information that lets screens branch on `Network` (offer Retry) vs. `NotFound` (navigate away). The `from()` factory in `domain/model/AppError.kt:46` already classifies network exceptions; bypassing it forces every screen to re-implement that mapping.

---

## Fake repositories vs. MockK

**Definition.** Tests that exercise `MessageRepository`, `ChatRepository`, or `UserRepository` should use the fakes in `app/src/test/java/com/firestream/chat/test/fakes/`. MockK stays the default for everything else.

**Use a fake when.** A test sets up multi-call sequences against one of the three covered repositories — fakes have stateful behaviour (a `MutableStateFlow` of messages per chat, recorded `markAsRead` calls, a `nextFailure` throwable) that's tedious to build with MockK.
**Use MockK when.** Mocking anything else (use cases, sources, managers, DataStore), or when a test only needs one or two stubbed return values.

**Example.** `app/src/test/java/com/firestream/chat/test/fakes/FakeMessageRepository.kt:15–30` shows the shape: `nextFailure` for fault injection, recorded call lists for verification, `emit(chatId, messages)` to drive flows.

**Trap.** Reaching for MockK on a test that walks `MessageRepository` through five steps produces a 30-line setup block that re-implements stateful behaviour. Migration is opportunistic — don't rewrite green MockK tests just to use fakes, but reach for the fake on new tests.

---

## Use-case vs. direct repository

**Definition.** Use cases live in `domain/usecase/<feature>/` and are reserved for **non-trivial cross-repository orchestration or pure logic that benefits from isolated unit tests**. Single-repository calls go directly from the ViewModel to the repository.

**Use a use case when.** The operation reads from one repository to write to another, applies non-trivial business rules, or has logic worth testing in isolation. Existing examples: `CheckGroupPermissionUseCase`, `SearchMessagesUseCase`, `SendListUpdateToChatsUseCase`.
**Skip the use case when.** The ViewModel can call `repository.method()` once and forward the result. Don't wrap repo methods just for symmetry.

**Example.** `app/src/main/java/com/firestream/chat/domain/usecase/list/SendListUpdateToChatsUseCase.kt` — multi-repository (List + Message) orchestration, worth isolating. Counterexample (intentionally direct): `ChatViewModel` calling `messageRepository.markAsRead(...)` — no use case needed.

**Trap.** Wrapping every repo method in a use case inflates the domain layer with thin pass-throughs that add no behaviour and force future agents to read four files instead of two.

---

## DataStore writes need @ApplicationScope

**Definition.** DataStore preference writes that must survive ViewModel disposal must be launched on `@ApplicationScope` (the application-lifetime `CoroutineScope`), not `viewModelScope`. `viewModelScope` is cancelled the moment the user navigates away, often before the DataStore edit lands.

**Use when.** Persisting state in a callback the user can trigger right before backing out of a screen — scroll positions, "last open chat", debounced filter writes.
**Don't use when.** Pure UI-state writes that don't need to survive disposal (in-memory `StateFlow` updates).

**Example.** `app/src/main/java/com/firestream/chat/ui/chat/ChatViewModel.kt:88–96` — `persistScrollPosition()` launches on `appScope` with the explanatory comment. `@ApplicationScope` is provided by `di/CoroutineScopeModule`.

**Trap.** Writing on `viewModelScope` looks correct in tests (the scope lives long enough) but loses writes in the field — the user pops back, `onCleared()` fires, the coroutine cancels, and the last preference change vanishes.

---

## Room version bump rule

**Definition.** Whenever an entity column or table is added, removed, or renamed, bump the `version` field on `@Database` in `app/src/main/java/com/firestream/chat/data/local/AppDatabase.kt:27` (currently 19) **and** `data/local/SignalDatabase.kt` (currently 1). `fallbackToDestructiveMigration()` is enabled, so the version bump triggers a clean rebuild on next launch — but without it, Room's identity-hash check crashes the app at startup instead of running the migration.

**Use when.** Touching anything in `data/local/entity/` that changes the schema.
**Don't use when.** Adding `@Ignore` annotations or non-schema additions like a `companion object` constant.

**Example.** `app/src/main/java/com/firestream/chat/data/local/AppDatabase.kt:41–52` — `MIGRATION_18_19` registered alongside the version bump from 18 → 19 when the Signal tables were split out.

**Trap.** Forgetting the bump means the app crashes at first launch with a schema-mismatch error. The crash is loud but easy to misdiagnose as unrelated to the entity change in the same commit.

---

## reverseLayout for chat lists, not IME-coupling

**Definition.** Chat-style `LazyColumn`s use `reverseLayout = true` with `messages.asReversed()` so the newest message anchors at the bottom and the IME (keyboard) opening doesn't desync the scroll position from the composer.

**Use when.** Building any chat-style stream where the composer is anchored at the bottom and the user expects "scroll position pinned to newest".
**Don't use when.** Lists that scroll downward conventionally (chat list, settings, contacts).

**Example.** `app/src/main/java/com/firestream/chat/ui/chat/ChatScreen.kt:175` — implementation comment block + the `reverseLayout = true` LazyColumn it documents (visible via `grep -n reverseLayout` on the same file). Helper `toReversedIndex()` lives in the same file.

**Trap.** The previous approach combined a forward-layout list with `snapshotFlow { ime }.collect { scrollBy(...) }` — composer/list desync was guaranteed every time the IME opened. Don't reintroduce that pattern; flipping the layout is the fix.

---

## Compose UI tests run under Robolectric

**Definition.** Compose UI tests live in the *unit* source set (`app/src/test/`) and run on the JVM via `@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`. They execute as part of `./gradlew test`. This project has **no** `app/src/androidTest/` source set.

**Use when.** Asserting on rendered Compose output — a composable renders without throwing for every variant of its input, a click emits the right callback, conditional UI appears or hides. This is the default for UI coverage here.
**Don't use when.** The behaviour genuinely requires a device: real IME insets, `MediaStore` permission prompts, cross-app intents. Those need a new `androidTest` source set, which is not in the CI gate — prefer restructuring the test over starting one.

**Example.** `app/src/test/java/com/firestream/chat/ui/chatlist/ChatListItemUiTest.kt:31` — the canonical shape; its own KDoc names it "the Robolectric + createComposeRule pattern for future UI tests". `ui/chat/MessageBubbleSmokeTest.kt:32` is the render-every-variant smoke form that guards the composable param-count `VerifyError` ceiling.

**Trap.** Three, all non-obvious:
1. The annotations are load-bearing: `@Config(sdk = [29], application = android.app.Application::class)`. Drop the `application` override and Robolectric boots the Hilt application class — the test then fails in DI, nowhere near the composable you were testing.
2. The `androidTestImplementation` espresso and `compose-ui-test-junit4` deps *are* declared (`app/build.gradle.kts:495`). They are unused scaffolding; their presence is not evidence that instrumentation testing is the convention here.
3. Unit tests are debug-only on purpose (`app/build.gradle.kts:248`) — these tests depend on `ui-test-manifest`'s ComponentActivity, which is wired as `debugImplementation`, so a release unit-test variant cannot compile. Don't "fix" that by re-enabling it.

---

## Flavor-specific eager init via FlavorBootstrap

**Definition.** Per-flavor work that must run in `Application.onCreate` is bound into a Hilt `Set<FlavorBootstrap>` rather than branched on in shared code. `FireStreamApp` iterates `flavorBootstraps.forEach { it.start() }` with no flavor-specific imports; each flavor's DI module adds (or omits) a `@Binds @IntoSet` entry, and `@Multibinds` guarantees the set exists even when a flavor contributes nothing.

**Use when.** Adding eager init that should run for exactly one flavor — a flavor-specific lifecycle observer, WorkManager seeder, or trace listener.
**Don't use when.** The init is shared by all flavors. That goes directly in `FireStreamApp.onCreate` — routing it through the multibinding buys nothing and hides it.

**Example.** `app/src/main/java/com/firestream/chat/di/FlavorBootstrap.kt:11` is the whole interface (`fun start()`). `di/FlavorBootstrapModule.kt:16` declares `@Multibinds`; `app/src/pocketbase/java/com/firestream/chat/di/PocketBaseModule.kt:86` is the only current contributor, binding `PocketBaseLifecycleHook` — it connects/disconnects the SSE realtime stream on foreground/background, because PocketBase SSE does not auto-pause on backgrounding the way Firestore listeners do. The firebase flavor deliberately contributes nothing, which is why the empty-set case must work.

**Trap.** The temptation is to skip the indirection and write `if (BuildConfig.FLAVOR == "pocketbase")` in `FireStreamApp`. That compiles, but it drags flavor-specific types into `app/src/main/`, where the other flavor cannot resolve them — the shared source set must stay flavor-agnostic.

---

## MediaProcessingLimiter owns the concurrency bound, callers own ordering

**Definition.** Concurrent image decode/compress and video transcode are capped process-wide at 2 by the `@Singleton` `MediaProcessingLimiter`, which `ImageCompressor` and `VideoTranscoder` each wrap their work in. Batch callers are responsible only for *ordering* — sending sequentially so items land in the order the user picked them.

**Use when.** Adding any new path that decodes, compresses, or transcodes media. Inject the existing compressor/transcoder and the bound comes with it.
**Don't use when.** — there is no exemption. A new entry point does not get its own bound.

**Example.** `app/src/main/java/com/firestream/chat/data/util/MediaProcessingLimiter.kt:25` (the semaphore, with the reasoning for 2 permits in its companion KDoc); `data/util/ImageCompressor.kt:39` and `data/util/VideoTranscoder.kt:146` are the two call sites, both `processingLimiter.withPermit { … }`. Callers: `ui/chat/ChatMessageSender.kt`, `ui/share/SharePickerViewModel.kt`.

**Trap.** Adding a per-batch or per-ViewModel `Semaphore` "to be safe". Multi-select send and the share sheet can both be running, so two locally-bounded batches are jointly unbounded — each operation holds a full decoded bitmap, and it is the number running concurrently, not the number queued, that decides whether a large batch OOMs. The bound only works if it is the one shared singleton.

---

## Image edits rasterize per screen; overlay geometry is normalized

**Definition.** Each editor screen takes a source `Uri`, and on **Done** flattens its layer into a new full-size JPEG in `cacheDir/edits/` via `ImageEditRasterizer.rasterize`, handing that URI back. `PendingMedia` points at the newest file and keeps `originalUri` untouched, so the chain of files *is* the undo history — `editCursor` moves, the files stay, and the send pipeline receives a different URI without ever learning that editing exists. Everything an overlay tool positions (a stroke, a sticker, a text run) is stored **normalized to the image being edited**, `0..1`, and converted at the boundary through `ImageFitMapper`.

**Use when.** Adding any editor screen or tool under `ui/chat/imageedit/`. Convert Compose state into `ImageEditRasterizer.RasterOp` at the boundary — the ops carry plain floats and `Long` ARGB colours, never Compose types, because `ArchitectureTest` forbids `data → ui`.
**Don't use when.** The change is a *view* control rather than an edit. Layer visibility (the eye toggle) hides what you have added so you can check the photo underneath; it never changes what gets written, and pressing Done with the layer hidden still flattens it.

**Example.** `app/src/main/java/com/firestream/chat/data/util/ImageEditRasterizer.kt` (the ops, the 4096 px working ceiling, the cache lifecycle); `ui/chat/PendingMedia.kt:85` `landEdit` (append, truncate the abandoned tail, hand those files back for deletion) and `:111` `onSurvivingStep` (fall back when the OS reclaimed one); `ui/chat/imageedit/ImageFitMapper.kt`. Full reasoning: `.claude/plans/image-editor.md` §2.1, §2.3, §2.7.

**Trap.** Three, all of which look like tidiness until they bite.
- **Forgetting the abandoned files.** Redo means undo can no longer free the file it steps off, so the cache grows per edit *step* and stays grown for the whole preview session. `landEdit` returns them; hand them to `discard` or leak until the next app start.
- **Trusting the cursor.** `cacheDir` can be reclaimed at any moment and the rasterizer's byte budget evicts deliberately, so every read of a history entry goes through `onSurvivingStep`. Losing undo depth is fine; sending a URI that resolves to nothing is not.
- **Storing overlay geometry in screen pixels.** It survives neither a device rotation nor a screen-size change, and the drift is invisible in a preview and permanent in the JPEG.

---

## When to add a new pattern here

A convention belongs in this file when:
1. Two or more files already implement it.
2. An agent ignoring it would write code that compiles but breaks an invariant.
3. The rule isn't obvious from reading any single example file.

Patterns that don't meet all three live as inline comments at their site of use, not here.
