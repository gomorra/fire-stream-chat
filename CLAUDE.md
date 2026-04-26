# FireStream Chat — Claude Code Instructions

## Project Overview

FireStream Chat is an Android messaging app built with Kotlin, Jetpack Compose, and Firebase. It uses the Signal Protocol (libsignal) for end-to-end encryption. Single-module app with Clean Architecture.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.firestream.chat.domain.usecase.chat.ArchiveChatUseCaseTest"

# Lint check
./gradlew lint

# Deploy Firebase Cloud Functions
cd functions && npm install && firebase deploy --only functions
```

- JVM target: 17
- `minSdk = 29`, `targetSdk = 35`, `compileSdk = 35`
- Core library desugaring enabled
- Package: `com.firestream.chat`
- Annotation processing: **KSP** (not KAPT)
- Version catalog: `gradle/libs.versions.toml`
- Gradle version: 8.11.1

## Model & Effort Guidelines

When creating implementation plans, include a model/effort recommendation per step using these rules:

| Task Characteristics | Model | Effort |
|---------------------|-------|--------|
| Foundation/architecture work, new patterns, complex state management | Opus | High |
| Extends existing patterns with moderate complexity | Opus | Medium |
| New features following established patterns, multiple files | Sonnet | High |
| Straightforward feature work, well-scoped changes | Sonnet | Medium |
| Simple bug fixes, typos, single-file changes | Sonnet | Low |

**Defaults:**
- **Planning** — always Opus, High effort (unless the plan is trivial/single-step)
- **Implementation** — Sonnet, Medium effort (override per the table above when warranted)

**Per-step model selection:** For each implementation step, automatically apply the model/effort from the table above based on the step's characteristics. The defaults (Sonnet/Medium) are the fallback only when no table row clearly applies. If the table suggests a different model/effort than the default, apply it automatically — but if the choice is ambiguous, ask the user before deviating.

**Decision factors:**
- **Opus** when: defining architecture others build on, complex permission/security logic, multiple interacting systems
- **Sonnet** when: following patterns already in the codebase, isolated features, UI components
- **High effort** when: many new files (>5), critical infrastructure, cross-cutting changes
- **Medium effort** when: extending existing patterns, moderate file count (2–5)

Plans must include a recommendation table per step:

| Step | Model | Effort | Rationale |
|------|-------|--------|-----------|

## Plan Execution Workflow

### Execution order

Plans must include an **Order** line that defines the build sequence:
- `→` = sequential (wait for previous step)
- `+` = parallel (run simultaneously)
- Example: `Order: 1 → 2 → 3+4 → 5` — steps 3 and 4 run in parallel after 2; step 5 waits for both

**Never infer parallelism.** Only parallelize steps the plan explicitly joins with `+`. When in doubt, sequential is safer.

### Per-step display

**At the start of each implementation step, display:**
> **Step X — Model: [Opus|Sonnet] / Effort: [High|Medium|Low]**

This must appear before any code changes are made for that step.

### Post-step code review

**After each significant phase or larger step, ALWAYS run these steps in order without waiting to be asked:**
1. `./gradlew test` — unit tests must pass
2. `./gradlew assembleDebug` — build must be clean
3. `/simplify` — code review of the pending diff. Invoke `Skill(skill: "simplify")` directly; no wrapper. Phase 2 reviewers inherit the parent model. Syntax caveat: `/model` and `/effort` are **separate** commands in Claude Code (no positional `/model sonnet medium`); both Opus and Sonnet cap effectively at `high`, with `xhigh` an accepted input label that resolves to the same ceiling.
   - **Default (from `opus xhigh`)** — `/effort medium` → `/model sonnet` → `Skill(skill: "simplify")` → `/model opus` → `/effort xhigh`. Three parallel Sonnet-medium reviewers — roughly a third the token cost of running the skill on main-context Opus.
   - **Triggered (from `opus xhigh`)** — `/effort medium` → `Skill(skill: "simplify")` → `/effort xhigh`. Three parallel Opus-medium reviewers — same order of token cost as main-context Opus xhigh but with three independent-context perspectives. Medium (not xhigh) because the reviewers pattern-match known issues (reuse / quality / efficiency), not open-ended problem-solving.
   - **Triggers:** (a) concurrency-/state-machine-heavy (coroutine scoping, flow chains, cancellation, lock ordering); (b) security-adjacent (Signal/crypto, permission checks, auth); (c) cross-cutting across many layers (DI + repo + multiple ViewModels + workers); (d) large (>~600 changed lines).
   - **Never reimplement the review** with a custom prompt — always `Skill(skill: "simplify")`. The 2026-04-23 incident was a main-context reimplementation, not a sub-agent one; removing the wrapper doesn't remove the risk.
   - **Never substitute `/simplify-review`** — that skill is manual-invocation only.
4. `git commit` — **commit immediately after a clean build; do not wait for user instruction**
5. **Write unit tests** when the step/phase introduces **non-trivial logic** (state machines, parsers, permission checks, complex mapping). Skip tests for pass-through ViewModels, simple CRUD repositories, and UI-only changes.
6. `./gradlew test` — unit tests must pass
7. `git commit` — **commit immediately after a clean build; do not wait for user instruction**
8. Update MEMORY.md — record what was done, key patterns established, remove stale entries

> **Note:** Do not use `/build` or `/step` skills — implement plans directly with the selected model.

### Token efficiency

- When a plan file exists with specific file paths, read those files directly instead of launching Explore agents. Only explore when the plan lacks sufficient detail.
- When starting a session for a planned step, reference the plan file path (e.g., "implement step 5.2 per `.claude/plans/...`") to avoid redundant exploration.

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

```
com.firestream.chat/
├── data/
│   ├── call/            # CallService, CallStateHolder, CallNotificationManager, WebRtcPeerConnectionFactory
│   ├── crypto/          # SignalManager, SignalProtocolStoreImpl
│   ├── local/
│   │   ├── dao/         # Room DAOs (Chat, Contact, List, Message, Signal, User)
│   │   ├── entity/      # Room entities (12 tables including 7 Signal tables)
│   │   ├── AppDatabase.kt
│   │   ├── Converters.kt
│   │   └── PreferencesDataStore.kt
│   ├── util/            # ImageCompressor, MediaFileManager, ProfileImageManager
│   ├── worker/          # MediaBackfillWorker
│   ├── remote/
│   │   ├── fcm/         # FCMService, ActiveChatTracker
│   │   └── firebase/    # FirebaseAuthSource, FirestoreChatSource, FirestoreMessageSource, etc.
│   ├── repository/      # *Impl classes (8 repositories + PollMapper)
│   └── share/           # SharedContentHolder, ShareContentResolver
├── di/                  # Hilt modules (App, Database, Crypto, Network, System)
├── domain/
│   ├── model/           # Chat, Message, User, Contact, Poll, CallState, CallLogEntry,
│   │                    # GroupPermissions, GroupRole, ListData, ListItem, ListDiff,
│   │                    # MediaAttachment, SharedContent, MessageStatus, MessageType, ChatType
│   ├── repository/      # Interfaces (Auth, Call, Chat, Contact, List, Message, Poll, User)
│   ├── usecase/         # Organized by feature (chat, list, message)
│   └── util/            # MentionParser
├── navigation/          # NavGraph.kt with Routes object (18 routes)
├── ui/                  # Feature packages, organized by screen
│   ├── auth/            # LoginScreen, OtpScreen, ProfileSetupScreen, AuthViewModel
│   ├── broadcast/       # CreateBroadcastScreen, CreateBroadcastViewModel
│   ├── call/            # CallActivity, CallScreen, CallViewModel (WebRTC UI)
│   ├── calls/           # CallsScreen, CallsViewModel (call log tab)
│   ├── chat/            # ChatScreen, ChatViewModel + 6 managers, MessageBubble, etc.
│   ├── chatlist/        # ChatListScreen, ChatListViewModel, ArchivedChatsScreen
│   ├── components/      # UserAvatar, ImagePicker, SkeletonLoading, TypingIndicator
│   ├── contacts/        # ContactsScreen, ContactsViewModel
│   ├── group/           # GroupSettingsScreen, CreateGroupScreen, QrCodeGenerator
│   ├── lists/           # ListsScreen, ListDetailScreen, SharedListsScreen + ViewModels
│   ├── main/            # MainScreen (HorizontalPager host), BottomNavBar
│   ├── profile/         # ProfileScreen, ProfileViewModel
│   ├── settings/        # SettingsScreen, SettingsViewModel
│   ├── share/           # SharePickerScreen, SharePickerViewModel
│   ├── starred/         # StarredMessagesScreen, StarredMessagesViewModel
│   └── theme/           # Color, Shape, Theme, Type
├── AppLifecycleObserver.kt # Process-level lifecycle — drives RTDB presence
├── FireStreamApp.kt     # @HiltAndroidApp
└── MainActivity.kt      # Single activity entry point
```

### Key Architectural Decisions

- **Single Activity** — `MainActivity` with Compose `NavHost` for all navigation.
- **Bottom navigation** — `MainScreen` (`ui/main/`) hosts a `HorizontalPager` with Chats, Calls, and Lists tabs. `BottomNavBar` lives in `MainScreen`'s Scaffold; individual tab screens (`ChatListScreen`, `CallsScreen`, `ListsScreen`) do **not** own the nav bar or swipe gesture.
- **Local-first** — Room database with Firebase sync. `fallbackToDestructiveMigration()` is enabled. **When adding, removing, or renaming columns/entities, always bump the `version` in `AppDatabase.kt`.** Without a version bump, Room's identity hash check crashes at runtime instead of triggering the destructive migration.
- **DataStore** — All preferences (theme, notifications, privacy). No SharedPreferences.
- **Signal Protocol** — E2E encryption with `SignalManager` coordinating key exchange via `FirebaseKeySource`. **Encryption is disabled in debug builds** (`BuildConfig.DEBUG` guard in `MessageRepositoryImpl`) — all messages are sent as plaintext via `sendPlainMessage` to avoid key-loss issues during development. Release builds use full Signal encryption.
- **Presence** — Online status uses Firebase Realtime Database (`RealtimePresenceSource`). `startPresence()` uses the `.info/connected` pattern to re-register `onDisconnect()` on every reconnect. `observeOnlineStatus()` lets `UserRepositoryImpl.observeUser()` combine RTDB presence directly into the user stream — no Cloud Function dependency for the live indicator. The Cloud Function `syncPresenceToFirestore` still mirrors RTDB to Firestore for abrupt-disconnect cases. See the KDoc on `RealtimePresenceSource` for the full state machine (foreground enter, background leave, abrupt termination, reconnect-while-tracking) and the RTDB schema.
- **Deep linking** — `MainActivity` accepts `chatId`/`senderId` extras from FCM notifications.

## Key Conventions

Each pattern below is a one-line pointer; for the rule's *example, trap, and when-not-to-use* see [`docs/PATTERNS.md`](docs/PATTERNS.md). Package layout (where ViewModels / repos / entities / sources / DI live) is in [`docs/ARCHITECTURE.md` §12](docs/ARCHITECTURE.md).

- **Chat\*Manager slice-ownership** — each manager owns one slice of `ChatUiState`, mutates only via `_uiState.update {}`, never calls another manager. → [PATTERNS.md#chat-manager-slice-ownership](docs/PATTERNS.md#chat-manager-slice-ownership)
- **ChatUiState slice composition** — five nested slices (`MessagesState`, `ComposerState`, `OverlaysState`, `SessionState`, `DictationState`); cross-slice writes must collapse into one `.update {}`. → [PATTERNS.md#chatuistate-slice-composition](docs/PATTERNS.md#chatuistate-slice-composition)
- **AppError boundary wrapping** — every `UiState.error` is `AppError?`; wrap Throwables via `AppError.from(e)`, validation via `AppError.Validation(...)`. → [PATTERNS.md#apperror-boundary-wrapping](docs/PATTERNS.md#apperror-boundary-wrapping)
- **Fake repositories vs. MockK** — use `test/fakes/Fake{Message,Chat,User}Repository` for those three; MockK stays default elsewhere. → [PATTERNS.md#fake-repositories-vs-mockk](docs/PATTERNS.md#fake-repositories-vs-mockk)
- **Use-case vs. direct repository** — use cases only for cross-repository orchestration or pure logic worth isolating; otherwise call the repo directly. → [PATTERNS.md#use-case-vs-direct-repository](docs/PATTERNS.md#use-case-vs-direct-repository)
- **DataStore writes need `@ApplicationScope`** — preference writes that must outlive `onCleared()` use `appScope`, not `viewModelScope`. → [PATTERNS.md#datastore-writes-need-applicationscope](docs/PATTERNS.md#datastore-writes-need-applicationscope)
- **Room version bump rule** — any column/table change bumps `@Database(version = …)` in `AppDatabase.kt` / `SignalDatabase.kt`. → [PATTERNS.md#room-version-bump-rule](docs/PATTERNS.md#room-version-bump-rule)
- **`reverseLayout` for chat lists** — chat-style `LazyColumn`s use `reverseLayout = true` + `messages.asReversed()`; never reintroduce IME-coupling via `snapshotFlow{ime}`. → [PATTERNS.md#reverselayout-for-chat-lists-not-ime-coupling](docs/PATTERNS.md#reverselayout-for-chat-lists-not-ime-coupling)

### Discovery & maintenance

- **Cross-cutting work** — for any feature spanning 4+ packages, [`docs/FEATURE-MAP.md`](docs/FEATURE-MAP.md) lists every file involved. Check there before grepping.
- **Maintaining FEATURE-MAP** — when you add, move, rename, or delete a file in `app/src/main/java/`, check whether it appears in `docs/FEATURE-MAP.md` and update if so. Refresh the `last-verified` HTML comment quarterly.
- **Plans** — in-flight plans live in `.claude/plans/`; shipped plans archive to `.claude/plans/done/` (or are deleted if `MEMORY.md` already captures the outcome).
- **Anchor headers** — managers, repository impls, Firestore sources, both Room databases, and `NavGraph.kt` open with a `// region: AGENT-NOTE` block above the package declaration. Cite the relevant pattern by name in the `Don't put here:` line. New anchor files should follow the same shape.

## Navigation

Routes are string constants in `navigation/NavGraph.kt` (`Routes` object). Use helper functions for routes with arguments:

```kotlin
Routes.otp(verificationId, phoneNumber)
Routes.chat(chatId, recipientId)
Routes.messageInfo(messageId, chatId)
Routes.userProfile(userId)
```

Do **not** construct route strings manually.

## Firebase Cloud Functions

Three functions in `functions/index.js`. Runtime: Node.js 20.

- `sendPushNotification` — triggers on `chats/{chatId}/messages/{messageId}` creation; sends FCM push to all recipients and increments per-user unread counts.
- `sendCallPushNotification` — triggers on `calls/{callId}` creation with `status == "ringing"`; sends high-priority FCM to the callee.
- `syncPresenceToFirestore` — triggers on RTDB `/presence/{userId}` writes; mirrors `isOnline`/`lastSeen` to Firestore with a `lastSeen` transaction guard to reject reordered invocations.

## Testing

- **Unit tests**: `app/src/test/` — JUnit 4 + MockK + `kotlinx-coroutines-test`
- **UI tests**: Espresso + Compose UI Test (no instrumentation tests written yet)
- Test pattern: `@Before` setup with mocked repositories, `runTest` for coroutines, `coEvery`/`coVerify` for suspend functions.
- Existing test coverage: use case tests (Archive, Mute, Pin, Search, Star, Group, Broadcast, Call log), ViewModel tests (ChatList, Settings, GroupSettings, CreateBroadcast, Calls), repository tests (Presence, Delivery/receipts).

### Change Safety

- **Every production code change must pass `./gradlew test` before being committed.** If a test fails, fix the root cause — do not skip or delete the test.
- **Bug fixes require a regression test.** Before fixing a bug, write (or extend) a test that reproduces the failure, then verify the fix makes it green. This prevents the same defect from recurring.
- **Modifications to tested code must keep tests in sync.** When changing logic that has existing test coverage, update the corresponding tests to reflect the new behavior.

## Tech Debt

Deferred and declined refactors are catalogued in `TECH_DEBT.md` at the repo root. Before proposing a larger cleanup or interface split, check if it's already been evaluated there — each entry records the reason and the trigger condition for revisiting. Add a new entry when you consciously decide not to fix something you noticed.

## Changelog

User-visible changes are tracked in `CHANGELOG.md` (Keep a Changelog format). Each section is headed by the SemVer `versionName` shipped on that merge day: `## [1.2.3] — 2026-04-24`.

**After each user-visible commit**, append an entry under `## [Unreleased]` in the matching section (`Added` / `Fixed` / `Changed` / `Removed`):
- Lead with a bold descriptive name, not the raw commit subject.
- One or two sentences explaining **what changed and why** — existing entries are editorial, not commit dumps.
- End with the commit hash in backticks: `` (`8bb2a2e`) ``. Group related commits by appending more hashes: `` (`a972533`, `e892f58`) ``.

**Skip for:** doc-only, test-only, refactors with no user-visible effect, CI/tooling changes.

### Versioning

**Principle:** any commit that lands a CHANGELOG entry gets a version bump. The converse also holds — if the commit doesn't warrant a CHANGELOG entry (doc-only, test-only, CI/tooling, internal refactors with no release-visible effect), it doesn't bump. Bump severity follows the conventional-commit prefix:

| Prefix | Bump | Example |
|--------|------|---------|
| `feat:` | minor | `1.2.3` → `1.3.0` |
| `fix:` | patch | `1.2.3` → `1.2.4` |
| `refactor:` (when it lands a Refactored entry) | patch | `1.2.3` → `1.2.4` |
| `feat!:` or `BREAKING CHANGE:` | major | `1.2.3` → `2.0.0` |
| `chore:`, `docs:`, `test:`, `ci:` (no CHANGELOG entry) | none | — |

**Section placement.** If the top section below `[Unreleased]` is already dated today, append to it — and if this commit's bump is higher-severity than the section's current version, upgrade the header too (e.g. a `feat` landing on a `## [1.2.4] — today` section promotes it to `## [1.3.0] — today`). Otherwise insert a fresh `## [X.Y.Z] — YYYY-MM-DD` section.

**`versionCode` is auto-derived** from `git rev-list --count HEAD` at Gradle configure time — never edit it by hand. Same with `BuildConfig.GIT_SHA` / `COMMIT_TIMESTAMP`.

**At release time** (once store tagging begins): rename `## [Unreleased]` to the released version header and add a new empty `## [Unreleased]` block above.

`.github/workflows/changelog-check.yml` fails pushes and PRs that touch `app/src/**` or `functions/**` without updating `CHANGELOG.md`. Apply the `no-changelog` label to bypass for exempt PRs.

## Sensitive Files

These are gitignored and must not be committed:
- `google-services.json` — Firebase config
- `*.jks`, `*.keystore` — signing keys
- `firebase-debug.log`

## ProGuard/R8

Rules in `app/proguard-rules.pro`: keeps Signal Protocol classes (`org.signal.**`, `org.whispersystems.**`), Firebase classes, and Room database subclasses.

## Permissions

Declared in `AndroidManifest.xml`: INTERNET, READ_CONTACTS, RECORD_AUDIO, CAMERA, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, POST_NOTIFICATIONS.
