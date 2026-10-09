# FireStream Chat — Claude Code Instructions

## Project Overview

FireStream Chat is an Android messaging app built with Kotlin, Jetpack Compose, and Firebase. It uses the Signal Protocol (libsignal) for end-to-end encryption. Single-module app with Clean Architecture.

## Build & Run

```bash
# Build debug APK (defaults to firebase flavor)
./gradlew assembleDebug

# Per-flavor builds — firebase is default; pocketbase is the self-host variant
./gradlew assembleFirebaseDebug
./gradlew assemblePocketbaseDebug

# Run unit tests (per-flavor; the bare `test` task covers all flavors)
./gradlew test
./gradlew :app:testFirebaseDebugUnitTest
./gradlew :app:testPocketbaseDebugUnitTest

# Run a specific test class
./gradlew test --tests "com.firestream.chat.domain.usecase.chat.ArchiveChatUseCaseTest"

# Lint check
./gradlew lint

# Deploy Firebase Cloud Functions (firebase flavor only)
cd functions && npm install && firebase deploy --only functions

# Run the PocketBase backend (pocketbase flavor only — see pocketbase/README.md)
cd pocketbase && ./pocketbase serve --http=0.0.0.0:8090
```

> **Cloud sessions (Claude Code on the web): run the gate. Gradle works there.**
> The full suite runs in a cloud container, and all dependency hosts resolve.
>
> A fresh container lacks four things. `.claude/hooks/session-start.sh` supplies all
> four. If you hit one, the hook did not run: check `CLAUDE_CODE_REMOTE` and run the
> hook by hand. Don't work around it.
> 1. **No Android SDK.** Every Android task fails with `SDK location not found`.
>    `scripts/install-android-sdk.sh` installs it and writes `sdk.dir` to `local.properties`.
> 2. **No `google-services.json`.** The plugin applies to the whole module, so every
>    variant fails at `processGoogleServices`. The hook writes a build-only placeholder.
> 3. **No UTF-8 locale.** Without `LANG`, the Kotlin compiler cannot write class files
>    for test names that contain an em dash. It reports only `Internal compiler error`.
>    `.claude/settings.json` sets `LANG`/`LC_ALL`. The Gradle daemon inherits them from
>    the shell that started it, so exporting them in a hook or a single command is not
>    enough. If you do, run `./gradlew --stop` first.
> 4. **No way past Maven Central's rate limit.** Through the session's proxy, Maven
>    Central sometimes answers `429 Too Many Requests`. Gradle stops at a 429 instead of
>    trying the next repository, and Robolectric fails a test with "Failed to fetch maven
>    artifact". The hook writes `~/.gradle/init.d/maven-central-mirror.gradle`, which puts
>    Google's mirror of Maven Central first for both.
>
> Use the firebase flavor (`:app:testFirebaseDebugUnitTest`, `assembleFirebaseDebug`).
> Bare `test` also builds pocketbase, which is not maintained yet.
>
> The whole suite passes in cloud containers too (since 2026-10-03:
> `ApkDownloaderTest`'s unresolvable-host case no longer does a real lookup, which the
> sandbox proxy answered with `403`). Every failure is real.

- JVM target: 17
- `minSdk = 31`, `targetSdk = 35`, `compileSdk = 35`
- Core library desugaring enabled
- Package: `com.firestream.chat`
- Annotation processing: **KSP** (not KAPT)
- Version catalog: `gradle/libs.versions.toml`
- Gradle version: 8.11.1

## Model Guidelines

Plans do not include per-step model/effort tables. Principles:

- **Planning, and anything security-adjacent, concurrency-heavy, or architecture-defining** belongs on the strongest available model tier (currently Opus at `xhigh` for max and `high` for strong; mid is Opus at `medium`).
- **Everything else** runs on the current mid tier. The test and build gate enforces quality on small steps. The stronger tier is always safe to use.
- **Spawned `Agent` calls:** choose each call's `model` by the same rule. Security- or concurrency-shaped review work gets the stronger tier.
- **Report the model per sub-agent.** When you launch an `Agent` and when you summarize its results, state its model: the `model` override, or the agent definition's default. A `fork` always inherits the parent's model; say so. For parallel agents, list the model for each one.

Name tiers, not versions. Don't hard-code model version numbers in this file.

## Plan Execution Workflow

### Execution order

Plans must include an **Order** line that defines the build sequence:
- `→` = sequential (wait for previous step)
- `+` = parallel (run simultaneously)
- `‖` = checkpoint (stop after the step to its left and wait for the human — sign-off on departures, `/code-review ultra` if wanted)
- Example: `Order: 1 → 2 → 3+4 ‖ 5` — steps 3 and 4 run in parallel after 2; the run pauses for the human before step 5

Step headings may carry tags after the title: `skills: code-review, simplify` (mandatory skills for that step), `model: max | strong | mid` (tier; untagged = mid), `effort: low | medium | high | xhigh | max` (only where the tier's default — xhigh for max, high for strong, medium for mid — is wrong for the step: a large mechanical step, or a small subtle one), `budget: <USD>`. See *Plan runner* below.

**Never infer parallelism.** Only parallelize steps the plan explicitly joins with `+`. When in doubt, sequential is safer.

### Review tools

Two tools, non-overlapping scopes — pick by what you are looking for:

- **`/simplify`** — quality only: reuse, simplification, efficiency, altitude. It applies its own fixes. Trigger-gated inside the post-step workflow below, and offered by the pre-commit `ask-simplify.sh` hook.
- **`/code-review`** — correctness bugs, which `/simplify` explicitly does not hunt. Judgment-gated in post-step item 4 like `/simplify` and mandatory where a plan's `skills:` tag names it; always before cutting a release, and on any diff touching Signal/crypto, coroutine scoping, or the sync path. `/code-review ultra` is user-triggered and billed — Claude cannot launch it, so never write it into an auto-run step.

Do not reimplement either with a custom review prompt.

### Post-step code review

**After each significant phase or larger step, ALWAYS run these steps in order without waiting to be asked:**
1. **Write unit tests** when the step/phase introduced **non-trivial logic** (state machines, parsers, permission checks, complex mapping). Skip tests for pass-through ViewModels, simple CRUD repositories, and UI-only changes. Bug fixes always get a regression test, written *before* the fix — see Change Safety below.
2. `./gradlew test` — unit tests must pass
3. `./gradlew assembleDebug` — build must be clean
4. **Review skills (floor, intent, re-decision).** The skills in a step's `skills:` tag always run. Beyond those:
   - *Before touching code*, decide which other skills the step needs, with one reason each. "None" is a fine answer for a small step.
   - *After the gate is green*, decide again against the real diff. You may add skills freely. You may drop only a skill you added yourself, and only with a reason.
   - `/simplify` is worth it when the diff is (a) concurrency- or state-machine-heavy (coroutine scoping, flow chains, cancellation, lock ordering), (b) security-adjacent (Signal/crypto, permission checks, auth), (c) cross-cutting (DI + repo + several ViewModels + workers), or (d) large (over ~600 changed lines).
   - `/code-review` is worth it for (a), (b), the sync path, and any bug fix in those areas.
   - Invoke them with `Skill(skill: "simplify")` / `Skill(skill: "code-review")`. `/simplify` spawns three parallel reviewers; choose each one's `model` by judgment, never above the step's tier.
   - **If `/simplify` changed anything, re-run steps 2–3.** Its fixes are production code and must not be committed unverified.
5. `git commit` — **commit immediately once green; do not wait for user instruction.** One commit, carrying the code *and* its tests. Never split logic into one commit and its tests into the next: every commit must stand on its own as green.
6. Update MEMORY.md — record what was done, key patterns established, remove stale entries. Interactive sessions only: a runner step session has no memory store and routes facts to the tracked docs instead (`docs/GOTCHAS.md`, `docs/PATTERNS.md`, `docs/BACKLOG.md`, `TECH_DEBT.md`).

### Token efficiency

- When a plan file exists with specific file paths, read those files directly instead of launching Explore agents. Only explore when the plan lacks sufficient detail.
- When starting a session for a planned step, reference the plan file path (e.g., "implement step 5.2 per `docs/plans/...`") to avoid redundant exploration.

### Plan runner

Multi-step plans run unattended from a terminal, not from inside a Claude session:

```bash
scripts/run-plan.sh <plan-path> [--from N] [--to N] [--dry-run] [--cap <tier>] [--budget <USD>] [--variant <name>] [--base <ref>] [--sync-from <ref>|none]
```

- **One session per step.** Each step gets a fresh headless session in a dedicated worktree on `plan/<name>`.
- **The plan file is the state.** A step is done when a `**Shipped**` block sits under its heading. The step session writes it in a `docs(plan):` commit after its green code commit.
- **Stops.** The driver stops on `needs_decision` (resume the printed session to answer), on `blocked`, and at every `‖` checkpoint. It never pushes.
- **Plan edits during a run.** Commit them on main. For a cloud run, push them and pass `--sync-from origin/main`. Before each step the driver merges the plan file, and only that, into the branch as a `docs(plan): sync` commit. That is the one commit it makes. A conflict stops with exit 5 and prints the commit that records a hand merge.
- **Pause.** Create `docs/plans/.runs/<run-id>.pause` to stop the run before its next step, or during a usage-limit wait (exit 5). Run again to continue.
- **Usage limit.** The driver reads every session's stream while it runs. At the usage limit it stops the session, because a cloud session would go on on cloud credits. It waits for the reset and resumes the same session. A reset over six hours away, or a seventh wait in one step, blocks the step (exit 3). Run again after the reset to resume it.
- **Ctrl-C** stops the running session and exits 130.
- **Partly hand-finished plans** need `**Shipped**` lines for their done steps, or `--from N`. Always `--dry-run` first.
- **Retries.** A step that is still invalid after its one nudge, or gives up on the gate, re-runs once at the next effort level. The failed attempt is kept on a `plan-attempts/` branch. A fresh review session repairs a missed skill.
- **`--variant NAME`** runs under `scripts/plan-runner/variants/NAME.env` on its own branch, worktree and log. Pass it on every re-run.
- **`--base REF`** sets where a new plan branch starts.
- **`scripts/plan-runner/report.sh <run-id>…`** prints cost, turns, nudges, usage-limit waits and grades per step.

Contract and design: `docs/plans/done/plan-runner.md` (§5 and §6 cover everything since the first real step). Result schemas, prompt templates, variants, self-check and the stubbed end-to-end check live in `scripts/plan-runner/`.

## Architecture

Clean Architecture with three layers:

- **Domain** (`domain/`) — models, repository interfaces, use cases. No Android dependencies.
- **Data** (`data/`) — repository implementations, Room DB (`data/local/`), Firebase sources (`data/remote/`), Signal crypto (`data/crypto/`).
- **UI** (`ui/`) — Compose screens and ViewModels, organized by feature.

### Package Layout

Reference docs in `docs/`:
- `ARCHITECTURE.md` — clean-architecture overview, feature flows, navigation, package layout
- `SPEC.md` — product feature list
- `SCHEMA-ROOM.md` — Room entities and DAOs
- `SCHEMA-FIRESTORE.md` — Firestore collections and RTDB paths
- `DOMAIN-MODELS.md` — domain model shapes
- `CLOUD-FUNCTIONS.md` — Firebase Cloud Functions
- `TESTING.md` — per-feature testing requirements, coverage intent, security-specific testing
- `BACKLOG.md` — open, unshipped features and ideas (shipped history is `CHANGELOG.md`)

The full annotated package tree (every subpackage, one-line contents) lives in [`docs/ARCHITECTURE.md` §12 "Package Layout"](docs/ARCHITECTURE.md#12-package-layout).

### Key Architectural Decisions

- **Single Activity** — `MainActivity` with Compose `NavHost` for all navigation.
- **Bottom navigation** — `MainScreen` (`ui/main/`) hosts a `HorizontalPager` with Chats, Calls, and Lists tabs. `BottomNavBar` lives in `MainScreen`'s Scaffold; individual tab screens (`ChatListScreen`, `CallsScreen`, `ListsScreen`) do **not** own the nav bar or swipe gesture.
- **Local-first** — Room database with Firebase sync. `fallbackToDestructiveMigration()` is enabled. **When adding, removing, or renaming columns/entities, always bump the `version` in `AppDatabase.kt`.** Without a version bump, Room's identity hash check crashes at runtime instead of triggering the destructive migration.
- **DataStore** — All preferences (theme, notifications, privacy). No SharedPreferences.
- **Signal Protocol** — E2E encryption with `SignalManager` coordinating key exchange via `FirebaseKeySource`. **Encryption is disabled in debug builds** (`BuildConfig.DEBUG` build gate in `MessageWriter`) — all messages are sent as plaintext via `sendPlainMessage` to avoid key-loss issues during development. Release builds (firebase flavor) encrypt only when the user has switched on the E2E toggle in Settings, which defaults to off (`PreferencesDataStore.e2eEncryptionEnabledFlow`); the pocketbase flavor never encrypts (`BuildConfig.SUPPORTS_SIGNAL = false`).
- **Presence** — Online status uses Firebase Realtime Database (`RealtimePresenceSource`). `startPresence()` uses the `.info/connected` pattern to re-register `onDisconnect()` on every reconnect. `observeOnlineStatus()` lets `UserRepositoryImpl.observeUser()` combine RTDB presence directly into the user stream — no Cloud Function dependency for the live indicator. The Cloud Function `syncPresenceToFirestore` still mirrors RTDB to Firestore for abrupt-disconnect cases. See the KDoc on `RealtimePresenceSource` for the full state machine (foreground enter, background leave, abrupt termination, reconnect-while-tracking) and the RTDB schema.
- **Deep linking** — `MainActivity` accepts `chatId`/`senderId` extras from FCM notifications.

## Key Conventions

Each pattern below is a one-line pointer; for the rule's *example, trap, and when-not-to-use* see [`docs/PATTERNS.md`](docs/PATTERNS.md). Package layout (where ViewModels / repos / entities / sources / DI live) is in [`docs/ARCHITECTURE.md` §12](docs/ARCHITECTURE.md).

- **Chat\*Manager slice-ownership** — each manager owns one slice of `ChatUiState`, mutates only via `_uiState.update {}`, never calls another manager. → [PATTERNS.md#chat-manager-slice-ownership](docs/PATTERNS.md#chat-manager-slice-ownership)
- **ChatUiState slice composition** — six nested slices (`MessagesState`, `ComposerState`, `OverlaysState`, `SessionState`, `DictationState`, `CommandsState`); cross-slice writes must collapse into one `.update {}`. → [PATTERNS.md#chatuistate-slice-composition](docs/PATTERNS.md#chatuistate-slice-composition)
- **AppError boundary wrapping** — every `UiState.error` is `AppError?`; wrap Throwables via `AppError.from(e)`, validation via `AppError.Validation(...)`. → [PATTERNS.md#apperror-boundary-wrapping](docs/PATTERNS.md#apperror-boundary-wrapping)
- **Fake repositories vs. MockK** — use `test/fakes/Fake{Message,Chat,User}Repository` for those three; MockK stays default elsewhere. → [PATTERNS.md#fake-repositories-vs-mockk](docs/PATTERNS.md#fake-repositories-vs-mockk)
- **Use-case vs. direct repository** — use cases only for cross-repository orchestration or pure logic worth isolating; otherwise call the repo directly. → [PATTERNS.md#use-case-vs-direct-repository](docs/PATTERNS.md#use-case-vs-direct-repository)
- **DataStore writes need `@ApplicationScope`** — preference writes that must outlive `onCleared()` use `appScope`, not `viewModelScope`. → [PATTERNS.md#datastore-writes-need-applicationscope](docs/PATTERNS.md#datastore-writes-need-applicationscope)
- **Room version bump rule** — any column/table change bumps `@Database(version = …)` in `AppDatabase.kt` / `SignalDatabase.kt`. → [PATTERNS.md#room-version-bump-rule](docs/PATTERNS.md#room-version-bump-rule)
- **`reverseLayout` for chat lists** — chat-style `LazyColumn`s use `reverseLayout = true` + `messages.asReversed()`; never reintroduce IME-coupling via `snapshotFlow{ime}`. → [PATTERNS.md#reverselayout-for-chat-lists-not-ime-coupling](docs/PATTERNS.md#reverselayout-for-chat-lists-not-ime-coupling)
- **Compose UI tests run under Robolectric** — Compose UI tests live in the *unit* source set (`app/src/test/`), not `androidTest/`. → [PATTERNS.md#compose-ui-tests-run-under-robolectric](docs/PATTERNS.md#compose-ui-tests-run-under-robolectric)
- **`FlavorBootstrap` for flavor-specific eager init** — per-flavor `Application.onCreate` work binds into a Hilt `Set<FlavorBootstrap>`; never branch on `BuildConfig.FLAVOR` in `app/src/main/`. → [PATTERNS.md#flavor-specific-eager-init-via-flavorbootstrap](docs/PATTERNS.md#flavor-specific-eager-init-via-flavorbootstrap)
- **Image edits rasterize per screen** — each editor screen flattens its layer to a new JPEG in `cacheDir/edits/`; the chain of files is the undo history and overlay geometry is normalized to the image. → [PATTERNS.md#image-edits-rasterize-per-screen-overlay-geometry-is-normalized](docs/PATTERNS.md#image-edits-rasterize-per-screen-overlay-geometry-is-normalized)
- **`MediaProcessingLimiter` owns the concurrency bound** — decode/compress/transcode is capped process-wide at 2; batch callers own *ordering* only, never their own semaphore. → [PATTERNS.md#mediaprocessinglimiter-owns-the-concurrency-bound-callers-own-ordering](docs/PATTERNS.md#mediaprocessinglimiter-owns-the-concurrency-bound-callers-own-ordering)
- **One chat picker, three hosts** — every "send this to a chat" surface renders `ChatPickerPanel`. The picker hands over chats and the host sends by chat id; `Chat.partnerIdHint` is for navigation only. → [PATTERNS.md#one-chat-picker-three-hosts](docs/PATTERNS.md#one-chat-picker-three-hosts)
- **The repository decides who a send is for** — a send takes a chat id, never a recipient. `SendTarget.forChat` resolves the target from the chat's Room row and refuses on doubt. `ArchitectureTest` fences where a `SendTarget` is built. → [PATTERNS.md#the-repository-decides-who-a-send-is-for](docs/PATTERNS.md#the-repository-decides-who-a-send-is-for)
- **Sends are idempotent by client id and drained by `OutboxWorker`** — a retryable send is a `SENDING` row plus one unique work per message id. The repository stops at `OutboxScheduler.enqueue`. Only a `MessageRecord` upsert, a column update or the worker writes a queued row. Nothing flips `SENDING` to `FAILED` because it looks old. → [PATTERNS.md#sends-are-idempotent-by-client-id-and-drained-by-outboxworker](docs/PATTERNS.md#sends-are-idempotent-by-client-id-and-drained-by-outboxworker)

### Discovery & maintenance

- **Cross-cutting work** — for any feature spanning 4+ packages, [`docs/FEATURE-MAP.md`](docs/FEATURE-MAP.md) lists every file involved. Check there before grepping.
- **Maintaining FEATURE-MAP** — when you add, move, rename, or delete a file in `app/src/main/java/`, check whether it appears in `docs/FEATURE-MAP.md` and update if so. Refresh the `last-verified` HTML comment quarterly.
- **Gotchas** — hard-won, host-independent traps (Compose VerifyError ceiling, MockK relaxed-nullable, Media3 pins/looper contract, …) are catalogued in [`docs/GOTCHAS.md`](docs/GOTCHAS.md). Check it before debugging something that smells platform-shaped; add entries there (not to local session memory) when the lesson is machine-independent — cloud sessions only see what's in git.
- **Local memory vs. tracked docs** — the Claude Code auto-memory store (`~/.claude/projects/<slug>/memory/`) is invisible to cloud sessions and to every other machine, and this repo is public, so it is never synced into git. Route each fact instead, at the moment you write it:
  - host-independent trap → [`docs/GOTCHAS.md`](docs/GOTCHAS.md)
  - named, reusable convention → [`docs/PATTERNS.md`](docs/PATTERNS.md) + a one-line pointer in Key Conventions above
  - shipped but not yet checked on hardware → [`docs/BACKLOG.md`](docs/BACKLOG.md) § *Pending on-device verification*
  - host-specific (JDK path, emulator flag, device serial, keystore location) → local memory **only** — committing it would mislead a cloud sandbox.

  The `.claude/hooks/promote-memory.sh` PostToolUse hook raises this question automatically on any write into the store; it is a reminder, not a gate.
- **Plans** — in-flight plans live in `docs/plans/`; shipped plans archive to `docs/plans/done/` (or are deleted if `MEMORY.md` already captures the outcome). Keep plans at this repo path, not `~/.claude/plans/` — the home directory is invisible to cloud sessions, so a plan a cloud agent must execute has to be committed here first.
- **Anchor headers** — managers, repository impls, Firestore sources, both Room databases, and `NavGraph.kt` open with a `// region: AGENT-NOTE` block above the package declaration. Cite the relevant pattern by name in the `Don't put here:` line. New anchor files should follow the same shape.

### Writing docs

Applies to this file, `docs/`, KDoc and `// region: AGENT-NOTE` blocks. Every session reads them, and their style carries into what gets written next.

- **Describe the current state.** Don't record what an earlier version said or how a rule came about; git keeps that history.
- **One rule per sentence.** Put the rule first and the reason after it. Split a sentence that chains several clauses with dashes or semicolons.
- **Keep provenance out of rules.** Commit hashes and dates go at the end of an entry, or nowhere. `CHANGELOG.md` entries still end with their hash.
- **Plain words.** Name the file, class or command. Avoid shorthand only the author knows.
- **Rewrite on touch.** Tidy a section when you edit it for another reason. Don't do repo-wide style passes.

## Navigation

Routes are string constants in `navigation/NavGraph.kt` (`Routes` object). Use helper functions for routes with arguments:

```kotlin
Routes.otp(verificationId, phoneNumber)
Routes.chat(chatId, partnerIdHint)
Routes.messageInfo(messageId, chatId)
Routes.userProfile(userId)
```

Do **not** construct route strings manually.

## Firebase Cloud Functions

Five functions in `functions/index.js` (Node.js 20) — push notifications for messages/reactions/calls, RTDB→Firestore presence mirroring, and the relay login for calls. Details: `docs/CLOUD-FUNCTIONS.md`.

## Testing

- **Everything lives in `app/src/test/`** — JUnit 4 + MockK + `kotlinx-coroutines-test`, all running on the JVM under `./gradlew test`. There is no `app/src/androidTest/` source set.
- **Compose UI tests run under Robolectric**, in that same unit source set — not as instrumentation tests. `@RunWith(RobolectricTestRunner::class)` + `createComposeRule()`; see `ui/chatlist/ChatListItemUiTest.kt` for the canonical shape. → [PATTERNS.md#compose-ui-tests-run-under-robolectric](docs/PATTERNS.md#compose-ui-tests-run-under-robolectric)
- **Instrumentation tests: none written.** The `androidTestImplementation` espresso / `compose-ui-test-junit4` deps and a `testInstrumentationRunner` are declared in `app/build.gradle.kts` but unused. Prefer a Robolectric test in `app/src/test/`; a real `androidTest` needs a device and is **not** in the CI gate.
- **Unit tests are debug-only** — the release unit-test component is disabled outright, because the Robolectric Compose tests need `ui-test-manifest`'s ComponentActivity (wired as `debugImplementation`). The rationale is in the `androidComponents` block of `app/build.gradle.kts`.
- Test pattern: `@Before` setup with mocked repositories, `runTest` for coroutines, `coEvery`/`coVerify` for suspend functions. Prefer the fakes in `test/fakes/` for the Message/Chat/User repos.
- **Don't keep a coverage list here** — it rots. `find app/src/test -name '*Test.kt'` is the source of truth (heaviest in `ui/chat` and `data/repository`).
- **Per-feature requirements** — what a given feature owes in tests (unit / Compose / integration), coverage intent, and security-specific testing live in [`docs/TESTING.md`](docs/TESTING.md). The non-negotiable gate is Change Safety below.
- **Architecture rules are executable** — `app/src/test/java/com/firestream/chat/architecture/ArchitectureTest.kt` (Konsist) enforces domain purity, the data⇏ui/navigation direction, the UI→data system-boundary allowlist, and Chat\*Manager isolation across all production source sets. If a rule fails on code you intend to keep, the decision belongs in `TECH_DEBT.md` (new baseline or allowlist entry) — never weaken a rule silently.

### Change Safety

- **Every production code change must pass `./gradlew test` before being committed.** If a test fails, fix the root cause — do not skip or delete the test.
- **CI gate** — `.github/workflows/ci.yml` runs `./gradlew test assembleDebug` on every push and PR to main. Since this repo pushes directly to main, a red run means the commit already landed: fix forward immediately.
- **Bug fixes require a regression test.** Before fixing a bug, write (or extend) a test that reproduces the failure, then verify the fix makes it green. This prevents the same defect from recurring.
- **Modifications to tested code must keep tests in sync.** When changing logic that has existing test coverage, update the corresponding tests to reflect the new behavior.

## Tech Debt

Deferred and declined refactors are catalogued in `TECH_DEBT.md` at the repo root. Before proposing a larger cleanup or interface split, check if it's already been evaluated there — each entry records the reason and the trigger condition for revisiting. Add a new entry when you consciously decide not to fix something you noticed.

## Changelog

User-visible changes are tracked in `CHANGELOG.md` (Keep a Changelog format). The working (unreleased) section uses a combined header — version and date live in the header itself, decided per-commit as entries land: `## [UNRELEASED] [1.2.3] — 2026-04-24`. Once released, the `[UNRELEASED] ` prefix is dropped, leaving the plain `## [1.2.3] — 2026-04-24` form.

**After each user-visible commit**, append an entry under the top `## [UNRELEASED] [X.Y.Z] — YYYY-MM-DD` section in the matching subsection (`Added` / `Fixed` / `Changed` / `Removed`):
- Lead with a bold descriptive name, not the raw commit subject.
- One or two sentences explaining **what changed and why** — existing entries are editorial, not commit dumps.
- End with the commit hash in backticks: `` (`8bb2a2e`) ``. Group related commits by appending more hashes: `` (`a972533`, `e892f58`) ``.

**Skip for:** doc-only, test-only, refactors with no user-visible effect, CI/tooling changes.

**Version bumps, CHANGELOG section placement, and cutting a release** (tagging, `versionName` derivation, the CI gates) live in the `changelog-release` skill — invoke it when a commit needs a bump decision or when cutting a release. Rule of thumb: any commit that lands a CHANGELOG entry gets a version bump, severity following the conventional-commit prefix (`feat:` minor, `fix:`/`refactor:` patch, `feat!:` major — but a `!` on a build or platform-floor change such as a `minSdk` raise is minor; major is for a break in the app's own data or protocol).

**`versionCode` is auto-derived** from `git rev-list --count HEAD` at Gradle configure time — never edit it by hand. Same with `versionName` (from `git describe --tags`), `BuildConfig.GIT_SHA`, and `COMMIT_TIMESTAMP`.

## Sensitive Files

These are gitignored and must not be committed:
- `google-services.json` — Firebase config
- `*.jks`, `*.keystore` — signing keys
- `firebase-debug.log`
