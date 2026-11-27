# FireStream Chat — Agent Instructions

FireStream Chat is a single-module Android messaging app (Kotlin, Jetpack Compose, Clean Architecture, Hilt, Room, Signal Protocol via libsignal) with `firebase` (primary) and `pocketbase` (self-host) flavors.

## Build & Test Gate

- **Inner loop:** iterate with targeted tests: `./gradlew :app:testFirebaseDebugUnitTest --tests "com.firestream.chat.path.MyTest"`
- **Pre-commit gate:** `./gradlew :app:testFirebaseDebugUnitTest assembleFirebaseDebug` (run `./gradlew test` whenever shared backend interfaces change or at phase boundaries; bare `test` covers both flavors).
- **Cloud Functions:** `cd functions && npm test` (and `node --check index.js`) when touching `functions/`.
- **Firestore & Storage rules:** `cd firestore-rules-tests && npm test` when touching `firestore.rules`, `storage.rules`, or `firebase.json` (the runner auto-selects JDK 21+ when default Java is 17).
- **Host scripts:** `node --test scripts/scrub-plaintext.test.mjs` when touching `scripts/scrub-plaintext.mjs`.
- **Android Lint:** `./gradlew lint` is excluded from the gate due to AGP/AndroidX detector skew — see [docs/GOTCHAS.md](docs/GOTCHAS.md) before touching lint config.
- **CI:** pushes to `main` are direct; `.github/workflows/ci.yml`, `firestore-rules.yml`, `functions.yml`, `scripts.yml`, and `changelog-check.yml` run as tripwires — fix forward immediately on red.

## Navigation Pointers

Read the relevant reference before grepping or guessing:

- **Cross-cutting features (4+ packages):** [docs/FEATURE-MAP.md](docs/FEATURE-MAP.md) — update it when adding, moving, renaming, or deleting files in `app/src/main/java/`.
- **Named conventions & traps:** [docs/PATTERNS.md](docs/PATTERNS.md) — check when a file's `// region: AGENT-NOTE` cites a pattern; never invent a variation.
- **Hard-won platform/test traps:** [docs/GOTCHAS.md](docs/GOTCHAS.md) — check before debugging Compose, Robolectric, Room, coroutine, Media3, or Gradle anomalies.
- **Architecture & package layout:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (§12 holds the full package tree).
- **Data schemas & backend:** [docs/SCHEMA-ROOM.md](docs/SCHEMA-ROOM.md), [docs/SCHEMA-FIRESTORE.md](docs/SCHEMA-FIRESTORE.md), [docs/CLOUD-FUNCTIONS.md](docs/CLOUD-FUNCTIONS.md), [docs/DOMAIN-MODELS.md](docs/DOMAIN-MODELS.md).
- **Testing requirements:** [docs/TESTING.md](docs/TESTING.md) (Compose UI tests live in `app/src/test/` under Robolectric; no `androidTest/`).
- **Deferred refactors & baselines:** [TECH_DEBT.md](TECH_DEBT.md) — check before proposing cleanups or changing allowlists.
- **Open backlog & pending device checks:** [docs/BACKLOG.md](docs/BACKLOG.md).

## Non-Negotiable Invariants

- **Change safety:** fix failing tests at the root cause; write a reproducing regression test *before* fixing any bug; commit code and its tests together.
- **Executable architecture rules:** `app/src/test/java/com/firestream/chat/architecture/ArchitectureTest.kt` (Konsist) enforces layer boundaries, `SendTarget` addressing, `Chat*Manager` isolation, encrypted-media URL guards, and `JSONObject` null-safety. Record any intentional allowlist or baseline addition in [TECH_DEBT.md](TECH_DEBT.md).
- **Room migrations:** every schema change to `AppDatabase` (v35+) or `SignalDatabase` (v1+) bumps `@Database(version = …)`, registers an explicit `Migration(n, n + 1)` in `ALL_MIGRATIONS`, commits the exported `app/schemas/` JSON, and adds a test in `AppDatabaseMigrationTest`. Unbounded `fallbackToDestructiveMigration()` is forbidden.
- **Navigation routes:** use `Routes` constants and helper functions in `navigation/NavGraph.kt`; build routes through those helpers rather than string literals.
- **DataStore only:** store preferences in DataStore, never `SharedPreferences`.
- **1:1 E2EE:** always on in `firebase` release builds (`BuildConfig.SUPPORTS_SIGNAL && !BuildConfig.DEBUG`); debug builds send plaintext.
- **Anchor headers:** managers, repository impls, Firestore sources, both Room databases, and `NavGraph.kt` open with a `// region: AGENT-NOTE` block above `package`.

## Review & Release Workflow

- **Risk-tiered review:** run the `bug-hunt` skill on high-risk steps (crypto, concurrency, sync, storage/rules, multi-process, destructive scripts). Run `code-review` (standards + spec) once per phase or before merging a feature branch.
- **Changelog & releases:** append user-visible changes under the top `## [UNRELEASED] [X.Y.Z] — YYYY-MM-DD` header in [CHANGELOG.md](CHANGELOG.md). Use the `changelog-release` skill for version bumps and cutting releases (`scripts/cut-release.sh`). Never edit `versionCode` or `versionName` by hand (both derive from git).
- **Where learnings go** (record at the moment you learn it; never leave machine-independent facts in local-only memory):
  - Host-independent trap → [docs/GOTCHAS.md](docs/GOTCHAS.md)
  - Reusable convention → [docs/PATTERNS.md](docs/PATTERNS.md)
  - Shipped but unverified on hardware → [docs/BACKLOG.md](docs/BACKLOG.md) § *Pending on-device verification*
  - Consciously deferred cleanup → [TECH_DEBT.md](TECH_DEBT.md)
  - Plans → `docs/plans/` (archive shipped plans to `docs/plans/done/`)

## Sensitive Files (Gitignored — Never Commit)

`google-services.json`, `*.jks`, `*.keystore`, `firebase-debug.log`, `firestore-debug.log`.
