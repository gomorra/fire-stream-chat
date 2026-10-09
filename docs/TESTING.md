# Testing Strategy

Normative testing requirements for every feature. The **mandatory** gate — what must pass before a commit — is in [`CLAUDE.md`](../CLAUDE.md#change-safety); this file holds the per-feature detail and the longer-term goals.

Mechanics of *how* Compose UI tests are wired here (Robolectric, not instrumentation) are a pattern: [`PATTERNS.md#compose-ui-tests-run-under-robolectric`](PATTERNS.md#compose-ui-tests-run-under-robolectric).

Tests are the primary feedback loop during implementation, not a step appended at the end.

---

## Per-feature testing requirements

### 1. Unit tests (JUnit + MockK + `kotlinx-coroutines-test`)

- Every new use case gets at least: success case, error/edge case, boundary conditions
- Every new repository method tested against mocked data sources
- ViewModel tests verifying state transitions and error handling
- Crypto tests: encryption round-trip (encrypt → decrypt == original plaintext)
- Test files live alongside source in `app/src/test/`
- Prefer the fakes in `test/fakes/` for the Message/Chat/User repositories; MockK elsewhere
- WorkManager workers: build the `@HiltWorker` with `TestListenableWorkerBuilder` (`androidx.work:work-testing`) and a `WorkerFactory` that calls its constructor with mocks, then `doWork()` under Robolectric — no `WorkManagerTestInitHelper` is needed. `OutboxWorkerTest` is the shape; the enqueue side (`OutboxSchedulerTest`) mocks `WorkManager` and inspects the captured request's `workSpec`

### 2. Compose UI tests (Robolectric + `createComposeRule`)

- Every new screen: renders correctly, handles empty/loading/error states
- User interaction flows: tap, swipe, long-press trigger the expected behaviour
- Navigation: correct route transitions with the expected arguments
- These live in `app/src/test/` alongside the unit tests and run under `./gradlew test` — **not** in `app/src/androidTest/`, which does not exist. Reserve a future `androidTest` source set for genuinely device-dependent behaviour (real IME insets, `MediaStore` permissions, cross-app intents).

### 3. Integration tests

> **Status: only the calls rules are covered.** The rules for `calls/{callId}` and its ICE candidate lists run against the Firestore emulator (see *Firestore rules tests* below). The rules of every other collection, the message flow and the offline cases have no integration tests yet. The sync-path coverage gap is recorded in [`TECH_DEBT.md`](../TECH_DEBT.md).

- Firestore security rules tested for each new collection/document pattern
- End-to-end message flow: send → encrypt → store → receive → decrypt → display
- Offline → online transitions: queued messages send correctly after reconnect

#### Firestore rules tests

`firestore-rules-tests/` is a small npm package. It is separate from the Gradle build, and `./gradlew test` does not run it.

- Run it with `cd firestore-rules-tests && npm ci && npm test`. It needs Java 21 or later for the emulator and Node 20 or later. The first run downloads the emulator.
- `npm test` starts the Firestore emulator for the project `demo-firestream` and runs `node --test` against it. A `demo-` project needs no Firebase login.
- The tests load `firestore.rules` from the repo root. They check the file on disk, not the rules that are deployed.
- `.github/workflows/firestore-rules.yml` runs them on Java 21 and Node 22. It runs for each push to main or pull request that changes `firestore.rules`, `firebase.json` or the package.
- `calls.test.js` has two groups. "the app" holds every write `FirestoreCallSource` makes, in each state the call can be in, including the orders older app versions used. These must pass. "the rules refuse" holds the abuses, which must fail.
- The calls rules list every field a call document may hold. A new field, or a write in a new state, needs the rule changed and a test here. Deploy the rules (`firebase deploy --only firestore:rules`) before the app version that writes it ships, or its writes are refused.

---

## Continuous feedback loop

- Run `./gradlew test` after every implementation step — never batch test runs
- UI tests need no device; they are Robolectric tests inside `./gradlew test`. `connectedAndroidTest` only becomes relevant if a device-dependent `androidTest` source set is ever added
- Full build verification: `./gradlew assembleDebug` must pass
- If a test fails, fix the root cause before moving on — never skip ahead, never delete the test

---

## Coverage targets

> **Aspirational.** No coverage tooling (JaCoCo/Kover) is wired into the build, so these are not measured or enforced today. They express intent for where testing effort belongs — adding the tooling is itself unclaimed work ([`BACKLOG.md`](BACKLOG.md)).

| Layer | Target |
|-------|--------|
| Domain (use cases, models) | 90%+ |
| Data (repositories, data sources) | 80%+ |
| UI (ViewModels) | 80%+ |
| UI (Compose screens) | Key user flows covered |
| Crypto (Signal Protocol) | 95%+ (security-critical) |

---

## Security-specific testing

Applies to crypto, auth, permission, and group-encryption work.

- Fuzz testing on decryption paths (malformed ciphertext, wrong keys)
- Session establishment edge cases (simultaneous first messages, re-registration)
- Key rotation and re-keying scenarios
- Group encryption: member add/remove and key distribution

Note that **encryption is disabled in debug builds** (`BuildConfig.DEBUG` build gate in `MessageWriter`), and unit tests run debug-only. The gate is a `MessageWriter` constructor value, so `MessageWriterTest` and `OutboxSenderTest` build an encrypting writer to exercise the Signal branch of the send path with `SignalManager` mocked. Real libsignal behaviour is driven through `SignalManager` directly: `SignalManagerTest` runs two parties on in-memory `signal.db`s under Robolectric (which supplies `android.util.Base64`; `libsignal-client` ships the host natives).
