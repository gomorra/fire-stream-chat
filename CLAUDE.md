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

**After each phase or larger step, ALWAYS run these steps in order without waiting to be asked:**
1. `./gradlew test` — unit tests must pass
2. `./gradlew assembleDebug` — build must be clean
3. `/simplify` — spawn a **Sonnet sub-agent** (`Agent` tool with `model: "sonnet"`) to review changed code for quality and fix issues.
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

```
com.firestream.chat/
├── data/
│   ├── crypto/          # SignalManager, SignalProtocolStoreImpl
│   ├── local/
│   │   ├── dao/         # Room DAOs (Chat, Contact, Message, Signal, User)
│   │   ├── entity/      # Room entities (11 tables including 7 Signal tables)
│   │   ├── AppDatabase.kt
│   │   ├── Converters.kt
│   │   └── PreferencesDataStore.kt
│   ├── remote/
│   │   ├── fcm/         # FCMService
│   │   └── firebase/    # FirebaseAuthSource, FirestoreMessageSource, etc.
│   └── repository/      # *Impl classes
├── di/                  # Hilt modules (App, Database, Crypto, Network)
├── domain/
│   ├── model/           # Chat, Contact, Message, MediaAttachment, MessageStatus, User
│   ├── repository/      # Interfaces (Auth, Chat, Contact, Message, User)
│   └── usecase/         # Organized by feature (auth, chat, contact, message)
├── navigation/          # NavGraph.kt with Routes object
├── ui/                  # Feature packages, organized by screen
│   ├── auth/            # LoginScreen, OtpScreen, ProfileSetupScreen
│   ├── calls/           # CallsScreen, CallsViewModel (call log + WebRTC entry points)
│   ├── chat/            # ChatScreen, ChatViewModel, MessageBubble, VoiceMessagePlayer, etc.
│   ├── chatlist/        # ChatListScreen, ChatListViewModel, ArchivedChatsScreen
│   ├── contacts/        # ContactsScreen
│   ├── group/           # GroupSettingsScreen, CreateGroupScreen, GroupSettingsViewModel
│   ├── broadcast/       # CreateBroadcastScreen, CreateBroadcastViewModel
│   ├── main/            # MainScreen (HorizontalPager host), BottomNavBar, TabSwipeModifier
│   ├── profile/         # ProfileScreen
│   ├── settings/        # SettingsScreen, SettingsViewModel
│   ├── share/           # SharePickerScreen
│   ├── starred/         # StarredMessagesScreen
│   └── theme/           # Color, Shape, Theme, Type
├── FireStreamApp.kt     # @HiltAndroidApp
└── MainActivity.kt      # Single activity entry point
```

### Key Architectural Decisions

- **Single Activity** — `MainActivity` with Compose `NavHost` for all navigation.
- **Bottom navigation** — `MainScreen` (`ui/main/`) hosts a `HorizontalPager` with Chats and Calls tabs. `BottomNavBar` lives in `MainScreen`'s Scaffold; individual tab screens (`ChatListScreen`, `CallsScreen`) do **not** own the nav bar or swipe gesture.
- **Local-first** — Room database with Firebase sync. `fallbackToDestructiveMigration()` is enabled.
- **DataStore** — All preferences (theme, notifications, privacy). No SharedPreferences.
- **Signal Protocol** — E2E encryption with `SignalManager` coordinating key exchange via `FirebaseKeySource`. **Encryption is disabled in debug builds** (`BuildConfig.DEBUG` guard in `MessageRepositoryImpl`) — all messages are sent as plaintext via `sendPlainMessage` to avoid key-loss issues during development. Release builds use full Signal encryption.
- **Presence** — Online status uses Firebase Realtime Database (`RealtimePresenceSource`). `startPresence()` uses the `.info/connected` pattern to re-register `onDisconnect()` on every reconnect. `observeOnlineStatus()` lets `UserRepositoryImpl.observeUser()` combine RTDB presence directly into the user stream — no Cloud Function dependency for the live indicator. The Cloud Function `syncPresenceToFirestore` still mirrors RTDB to Firestore for abrupt-disconnect cases.
- **Deep linking** — `MainActivity` accepts `chatId`/`senderId` extras from FCM notifications.

## Key Conventions

- **Use cases** live in `domain/usecase/<feature>/` and encapsulate a single operation.
- **ViewModels** live alongside their screens in `ui/<feature>/`.
- **Repository interfaces** are in `domain/repository/`; implementations in `data/repository/`.
- **Room entities** are in `data/local/entity/`; DAOs in `data/local/dao/`.
- **Firebase sources** are in `data/remote/firebase/`.
- **DI bindings**: `AppModule` binds repository interfaces to implementations via `@Binds`. Firebase instances provided in `FirebaseModule`. Room DAOs provided in `DatabaseModule`.

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

## Sensitive Files

These are gitignored and must not be committed:
- `google-services.json` — Firebase config
- `*.jks`, `*.keystore` — signing keys
- `firebase-debug.log`

## ProGuard/R8

Rules in `app/proguard-rules.pro`: keeps Signal Protocol classes (`org.signal.**`, `org.whispersystems.**`), Firebase classes, and Room database subclasses.

## Permissions

Declared in `AndroidManifest.xml`: INTERNET, READ_CONTACTS, RECORD_AUDIO, CAMERA, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, POST_NOTIFICATIONS.
