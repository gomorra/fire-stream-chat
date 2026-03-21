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

**At the start of each implementation step, display:**
> **Step X — Model: [Opus|Sonnet] / Effort: [High|Medium|Low]**

This must appear before any code changes are made for that step.

**After each sub-feature, ALWAYS run these steps in order without waiting to be asked:**
1. `/simplify` — review changed code for quality
2. `./gradlew test` — unit tests must pass
3. `./gradlew assembleDebug` — build must be clean
4. `git commit` — **commit immediately after a clean build; do not wait for user instruction**
5. Update MEMORY.md — record what was done, key patterns established, remove stale entries

**Parallel vs. sequential:** Run sub-features in parallel only when they touch different files. If two steps modify the same files, run them sequentially. When in doubt, sequential is safer.

**Token efficiency:**
- When a plan file exists with specific file paths, read those files directly instead of launching Explore agents. Only explore when the plan lacks sufficient detail.
- When starting a session for a planned step, reference the plan file path (e.g., "implement step 5.2 per `.claude/plans/...`") to avoid redundant exploration.

**Optional:** After completing an entire phase, run `/simplify` on the full phase diff as a final quality pass.

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
├── ui/                  # Feature packages (auth, chat, chatlist, contacts, profile, settings, starred)
│   └── theme/           # Color, Shape, Theme, Type
├── FireStreamApp.kt     # @HiltAndroidApp
└── MainActivity.kt      # Single activity entry point
```

### Key Architectural Decisions

- **Single Activity** — `MainActivity` with Compose `NavHost` for all navigation.
- **Local-first** — Room database with Firebase sync. `fallbackToDestructiveMigration()` is enabled.
- **DataStore** — All preferences (theme, notifications, privacy). No SharedPreferences.
- **Signal Protocol** — E2E encryption with `SignalManager` coordinating key exchange via `FirebaseKeySource`. **Encryption is disabled in debug builds** (`BuildConfig.DEBUG` guard in `MessageRepositoryImpl`) — all messages are sent as plaintext via `sendPlainMessage` to avoid key-loss issues during development. Release builds use full Signal encryption.
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

Single function in `functions/index.js`: `sendPushNotification` — triggers on `chats/{chatId}/messages/{messageId}` creation, sends FCM push to the recipient. Runtime: Node.js 20.

## Testing

- **Unit tests**: `app/src/test/` — JUnit 4 + MockK + `kotlinx-coroutines-test`
- **UI tests**: Espresso + Compose UI Test (no instrumentation tests written yet)
- Test pattern: `@Before` setup with mocked repositories, `runTest` for coroutines, `coEvery`/`coVerify` for suspend functions.
- Existing test coverage: use case tests (Archive, Mute, Pin, Search, Star) and ViewModel tests (ChatList, Settings).

## Sensitive Files

These are gitignored and must not be committed:
- `google-services.json` — Firebase config
- `*.jks`, `*.keystore` — signing keys
- `firebase-debug.log`

## ProGuard/R8

Rules in `app/proguard-rules.pro`: keeps Signal Protocol classes (`org.signal.**`, `org.whispersystems.**`), Firebase classes, and Room database subclasses.

## Permissions

Declared in `AndroidManifest.xml`: INTERNET, READ_CONTACTS, RECORD_AUDIO, CAMERA, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, POST_NOTIFICATIONS.
