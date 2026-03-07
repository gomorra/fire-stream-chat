# FireStream Chat — Claude Code Instructions

## Project Overview

FireStream Chat is an Android messaging app built with Kotlin, Jetpack Compose, and Firebase. It uses the Signal Protocol for end-to-end encryption.

## Architecture

Clean Architecture with three layers:

- **Domain** (`domain/`) — models, repository interfaces, use cases. No Android dependencies.
- **Data** (`data/`) — repository implementations, Room DB (`data/local/`), Firebase sources (`data/remote/`).
- **UI** (`ui/`) — Compose screens and ViewModels, organized by feature (auth, chat, chatlist, contacts).

Supporting packages:
- `di/` — Hilt modules (AppModule, FirebaseModule, CryptoModule, DatabaseModule, NetworkModule)
- `navigation/` — single `NavGraph.kt` with all routes defined in `Routes` object

## Tech Stack

| Concern | Library |
|---|---|
| UI | Jetpack Compose + Material3 |
| Navigation | Navigation Compose |
| DI | Hilt |
| Local DB | Room |
| Backend | Firebase Auth, Firestore, Storage, Messaging, Functions |
| Push notifications | FCM (`FCMService`) |
| Encryption | libsignal-android / libsignal-client |
| Image loading | Coil |
| Build | Gradle Kotlin DSL (`.kts`) + KSP |

## Build Configuration

- `minSdk = 29`, `targetSdk = 35`, `compileSdk = 35`
- JVM target: 17
- Core library desugaring enabled
- Package: `com.firestream.chat`
- Release builds: minification + resource shrinking enabled

## Key Conventions

- **Use cases** live in `domain/usecase/<feature>/` and encapsulate a single operation.
- **ViewModels** are in the `ui/<feature>/` directory alongside their screens.
- **Repository interfaces** are in `domain/repository/`; implementations are in `data/repository/`.
- **Room entities** are in `data/local/entity/`; DAOs are in `data/local/dao/`.
- **Firebase sources** are in `data/remote/firebase/`.
- Annotation processing uses KSP (not KAPT).

## Navigation

Routes are string constants in `navigation/NavGraph.kt` (`Routes` object). Use the helper functions (`Routes.otp(...)`, `Routes.chat(...)`) when navigating with arguments — do not construct route strings manually.

## Permissions

Declared in `AndroidManifest.xml`: INTERNET, READ_CONTACTS, RECORD_AUDIO, CAMERA, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, POST_NOTIFICATIONS.

## Testing

- Unit tests: JUnit + MockK + `kotlinx-coroutines-test`
- UI/instrumentation: Espresso + Compose UI Test
