# App improvements — the 2026-10-08 review as a plan

Status: **proposed**. The owner signs off §0 before the first run.

- Findings, evidence and IDs (SEC-1, UX-3, …) live in [`docs/reviews/2026-10-08-app-review.md`](../reviews/2026-10-08-app-review.md). Each step names the findings it fixes; read their entries before editing.
- Written against `main` at `9247d4b`. The review's line numbers are from `a16f93e`; re-verify every line number before editing.
- Run `scripts/run-plan.sh docs/plans/app-improvements.md --dry-run` first. Every phase ends at a checkpoint (`‖`), and §4 lists what the owner does at each one.
- Large items that need their own design are follow-up plans (§5), not steps here.

## 0. Decisions (proposed — the owner signs off, or edits a row, before the first run)

Steps implement the recommendation in each row. Once Step 1 ships, the rows are settled.

| ID | Question | Decision (recommended) | Steps |
|---|---|---|---|
| D-1 | What does `/code-review` run? | A project-owned correctness review in `.claude/skills/code-review/`, replacing the imported standards-and-spec skill. `ask-simplify.sh` is registered in `.claude/settings.json`. | 1 |
| D-2 | How far does this plan take directory privacy? | Push tokens move out of `users/` now. Hashed-number contact discovery is follow-up F-6. | 6 |
| D-3 | Do received links preview? | Off by default, with a Settings toggle. Links the user sends still preview. | 7 |
| D-4 | What happens to Settings toggles with no backing? | Wire Screen security and the Message and Group notification toggles. Replace Sound and Vibration with one row that opens the system notification settings. Hide Last seen until F-6. | 14 |
| D-5 | Is there a "Delete for me"? | Yes: a local hide, stored in a new column through a real migration. | 15 |
| D-6 | What happens to invite links? | Hide the invite-link and QR UI until joining exists. Joining is follow-up F-7. | 16 |
| D-7 | What happens to voice notes now? | The mic is labelled as dictation, dictation defaults to the device language, and SPEC says voice notes play but cannot be recorded. Recording is follow-up F-1. | 2, 14 |
| D-8 | Which orange is the light theme's `primary`? | `FireOrangeDark` (#C94E0E), for 4.5:1 contrast. FireOrange stays for large accents. The dark theme is unchanged. | 27 |
| D-9 | Keep the screenshot tests? | Keep Roborazzi, rebuilt as a component catalogue (light, dark, large font) that gates CI. | 26 |
| D-10 | First translation? | German, in follow-up F-4 after the strings are extracted. | F-4 |
| D-11 | What happens to the PocketBase flavor? | Frozen out of the default build: `-Ppocketbase` opts in, and a manual CI job builds it. The code stays. | 10 |
| D-12 | Who does the changelog bookkeeping? | `cut-release.sh` stamps hashes and computes the version bump at release time. Commits add entries under `## [Unreleased]`. | 37 |
| D-13 | Which skills stay? | Drop `to-spec`, `to-tickets`, `triage`, `wayfinder`, `implement-spec` (they need an issue tracker this repo lacks). Keep one of each overlapping pair: `grilling`, a project `handoff`, `implement`, `codebase-design`. The personal skills (`ask-matt`, `teach`, `retro`, `wait-what`, `to-questionnaire`) stay. | 36 |
| D-14 | How does work reach `main`? | Cloud sessions open a PR, and CI on the PR is the gate. Local sessions may push to `main` and fix a red run forward. `plan/*` branches merge after their last checkpoint. | 1 |
| D-15 | When does verification come before new work? | When more than 10 entries in `docs/VERIFY.md` are older than 30 days, the next session starts with verification triage. | 38 |
| D-16 | What is the release signing certificate's SHA-256? | The owner fills this in (`keytool -list -v -keystore <release.jks>`) before Step 8 runs. | 8 |
| D-17 | Is there a bug check beyond per-step `/code-review`? | Yes: a phase check right before each code phase's checkpoint (Steps 8c, 13c, 22c, 25c), run by the `phase-check` skill that Step 1 creates. It reviews only the seams between the phase's steps plus a short checklist, in one budget-capped session. It fixes confirmed high-severity findings with a test that pins them and logs the rest. The plan ends with a script (Step 38), not a model review. Decided by the owner, 2026-10-09. | 1, 8c, 13c, 22c, 25c, 38 |

Not reopened here: no dynamic color, no Gradle modules, no repository split, E2E stays opt-in (only its copy changes), and the `Chat*Manager` slice convention waits for F-3.

## 1. Rules for every step

These add to CLAUDE.md and the runner's step prompt.

- Cite the finding IDs a step fixes in its commit body.
- A step that touches a file listed under an earlier step re-reads that step's **Shipped** block first.
- From Step 9 on, a Room schema change ships with a `Migration`, a migration test and the new schema JSON. Never rely on the destructive fallback.
- From Step 13 on, new user-facing text goes into string resources. The ratchet test enforces it.
- A step commits Firestore rules and Cloud Functions changes; the owner deploys them at the next checkpoint. A step never runs `firebase deploy`.
- A symptom that only a device shows gets evidence before a fix (the rule Step 1 adds to CLAUDE.md).
- Until Step 37 ships, user-visible steps follow the current CHANGELOG rules.
- Each step's **Shipped** block lists the on-device checks it leaves; they also go to BACKLOG § Pending on-device verification (`docs/VERIFY.md` after Step 38).

## 2. Order

**Order: 1 → 2 ‖ 3 ‖ 4 → 5 → 6 ‖ 7 → 8 → 8c → 9 ‖ 10 → 11 → 12 → 13 → 13c ‖ 14 → 15 → 16 → 17 → 18 → 19 → 20 → 21 → 22 → 22c ‖ 23 → 24 → 25 → 25c ‖ 26 → 27 ‖ 28 → 29 → 30 → 31 → 32 → 33 ‖ 34 → 35 → 36 ‖ 37 → 38**

| Phase | Steps | Outcome |
|---|---|---|
| 0 · Gates tell the truth | 1–2 | `/code-review` hunts bugs again; the gate commands run as written; the repo stops tracking 6,860 junk files |
| 1 · Security | 3–8c | The open rules close; pushes carry no message text; push tokens leave the readable profile; the updater checks the APK's signer |
| 2 · Foundations | 9–13c | Schema changes stop wiping data; the gate runs one flavor; cancellation works; the APK shrinks from 107 MB to about 40 MB; string resources become the rule |
| 3 · Broken flows | 14–22c | Every Settings control works or is gone; destructive actions confirm or undo; group members can be added; permissions recover; notification taps keep the app; sign-in completes; the partner and title rules live in one place |
| 4 · Performance | 23–25c | Typing and startup stop fanning out; snapshots leave the main thread; bubbles skip recomposition |
| 5 · Design system | 26–31 | A screenshot gate; a complete theme; system bars that follow it; shared components; consistent avatars and tabs; Settings as a hub |
| 6 · Accessibility and i18n | 32–33 | Locale-aware dates; a chat that TalkBack reads well |
| 7 · CI and process | 34–38 | Hardened CI; dead code gone; a lean CLAUDE.md; release tooling that does the bookkeeping; a verification loop |

Each code phase ends with a phase check (D-17). Steps 8c, 13c, 22c and 25c look for the bugs between that phase's steps, which no per-step review can see, just before the owner's checkpoint. Phases 0, 5, 6 and 7 have none: the owner's checkpoint review is the check there.

Why this order: Step 1 comes first because every later step relies on `/code-review`. Security comes next because SEC-1 exposes user data now. Step 9 must precede every schema change (Step 15 adds one). Step 11 precedes the data steps so they build on the corrected helper. Step 13 precedes the UI phases so new text starts in resources. Step 26 records the screenshot baselines just before the theme changes them.

## 3. Steps

### Step 1 — Review and gate tooling tell the truth — skills: writing-for-agents; model: strong

Findings: MD-1, MD-2, MD-3, MD-4, MD-7, MD-10, DOC-7, PROC-8 (hook). Decisions D-1, D-14.

- Replace the symlink `.claude/skills/code-review` with a project-owned `.claude/skills/code-review/SKILL.md`. It reviews the diff from a base it computes itself: the step's start commit when given, else `git merge-base main HEAD`. It never asks for a base. It runs parallel reviewers for logic and edge cases, coroutine scoping and cancellation, security and privacy (crypto included), and data and sync invariants (outbox, Room migrations, Firestore rules). Each finding needs a concrete failure path and a verification pass before it is reported. The caller fixes what it can and stops on what needs the owner. Remove the imported skill from `skills-lock.json`.
- `scripts/plan-runner/selfcheck.sh` asserts that `.claude/skills/code-review` is a real directory in this repo, not a symlink.
- Create `.claude/skills/phase-check/SKILL.md` (D-17): one session that reviews a finished phase for what per-step `/code-review` cannot see, the bugs between steps. A check step lists its phase's steps and 3–6 checklist items; the skill does the rest, cheaply:
  1. **Scope from git.** The base is the parent of the commit named in the first listed step's Shipped line. Each step's files come from `git show --name-only --format= <its Shipped commit>`. Seam files are the files two or more listed steps changed, plus files that call a function another listed step changed.
  2. **Read little.** Read the listed steps' Approach and Shipped blocks, `git diff --stat <base>..HEAD`, and the diff of the seam files only. Open a whole file only to confirm a finding. Never re-review what per-step `/code-review` covered.
  3. **Check** the step's checklist plus a standing list: state across rotation and process death, offline behaviour, cancellation in new suspend code, listener ordering, new strings in resources, dark mode and font scale 2.0 for new UI.
  4. **Report confirmed findings only**, each with a concrete failure path. Fix a high-severity finding in the same session when it fits one small commit, with a test that pins the cross-step behaviour. A bigger fix, or one that needs the owner, stops with `needs_decision`. Each medium or low finding becomes one line in BACKLOG or TECH_DEBT.
  5. **No sub-agents.** Stop early when there are no seam files and the checklist holds.
  6. **Record** a `**Phase check**` block above the Shipped line: the seams examined, each finding and its outcome, or "clean". With no fix, the Shipped line names the HEAD the check reviewed; the runner accepts any commit on the branch there.
- CLAUDE.md: name one local gate, `./gradlew :app:testFirebaseDebugUnitTest :app:assembleFirebaseDebug`, and say CI runs `./gradlew test assembleDebug`. Use the gate in the post-step items. Correct the `assembleDebug` comment. Replace the missing `ArchiveChatUseCaseTest` example with `CheckGroupPermissionUseCaseTest`. Mark `lint` as outside the gate (it crashes; GOTCHAS). Describe `/code-review` as the new skill does. Say `/simplify` runs four reviewers. Review tools then name three tools: `/simplify` (quality), `/code-review` (correctness of one step's diff) and the phase check (bugs between steps; a plan puts one before each code phase's checkpoint).
- CLAUDE.md: add a "Branches and PRs" rule per D-14. Replace post-step item 6 with routing to tracked docs, because cloud sessions have no memory store. Add under Change Safety: "A bug you cannot reproduce in a test or an emulator needs evidence first: logcat, a recording or a diagnostic build (`diagnosing-bugs` skill). Until the owner confirms, the CHANGELOG describes the change, not the symptom as gone."
- Register `ask-simplify.sh` as a `PreToolUse` hook on Bash in `.claude/settings.json`. Check its headless guard first; if the guard is missing, delete the CLAUDE.md claim instead and say so in the Shipped block.
- `block-heredoc-commit.sh`: point its message at a new CLAUDE.md commit rule ("commit with chained `-m` flags; no HEREDOC, no pipe"), and drop the local memory path and the model name in its example trailer.
- `.github/workflows/ci.yml:3-5`: the comment matches D-14.

Tests: `scripts/plan-runner/selfcheck.sh` passes; `bash -n` on each edited hook. Run the new gate command once.
Done when: `/code-review` describes a bug hunt, `phase-check` exists, and every command in CLAUDE.md runs as written. Keep this step to accuracy; Step 36 restructures CLAUDE.md.

### Step 2 — Repo hygiene and stale records — model: mid; effort: low

Findings: DOC-1, DOC-2, DOC-3, DOC-4, DOC-6, ARCH-9 (stale entries), the review's §11. Decision D-7 (SPEC part).

- `git rm -r --cached functions/node_modules .kotlin`; both are already in `.gitignore`. Make sure `functions/package-lock.json` stays tracked.
- Move to `docs/plans/done/`: `call-audio-routes.md`, `file-handling.md`, `image-editor.md`, `offline-outbox.md`, `send-addressing.md`, `send-addressing-brief.md`. Move `docs/plans/architecture-review-2026-09-20.html` to `docs/reviews/`. Move `handoff-benchmark-readout.md` next to `scripts/plan-runner/benchmark.md`, or delete it if `benchmark.md` already holds its results. Fix every link to a moved file (`grep -rn` each name).
- TECH_DEBT: delete the stale entries the review's §11 lists. Rewrite "`ChatScreen.kt` — split into …" to point at the 2026-09-20 review's cut #10 and follow-up F-2.
- BACKLOG: correct "Baseline profile generation" (a stale profile ships, PERF-8) and "CI/CD remaining work" (releases build firebase only by default). Note under "Screen security (3.6)" that a toggle exists and does nothing until Step 14.
- SPEC: E2E is opt-in and covers 1:1 text only; voice notes play but cannot be recorded; group typing shows dots; invite links can be created but not joined.
- ARCHITECTURE.md: add the 4 missing routes and 5 missing packages; mark the "Add member → Contacts" line (`:344`) as current broken behaviour that Step 16 fixes. SCHEMA-ROOM.md: `signal_identity`.
- `app/build.gradle.kts:301`: replace the local memory path with a pointer to the GOTCHAS entry on the composable parameter ceiling. `MainScreen` KDoc: three tabs, not two.
- `README.md`: about 30 lines covering what the app is, the two flavors, and where to start (CLAUDE.md, `docs/README.md`). Point to the docs; don't copy them.

Tests: the gate. `grep` finds no link to a moved file.
Done when: `git ls-files | wc -l` drops by about 6,860.
Note: the untrack crosses the runner's 600-line tripwire. Scope `/simplify` to the diff without `functions/node_modules` and `.kotlin`, and say so in the Shipped block.

### Step 3 — Firestore rules close the open doors — skills: code-review; model: strong

Findings: SEC-1, L-2, SEC-11, SEC-12.

- Audit first, in the Approach block: list every client write path to `users`, `chats`, `chats/*/messages`, `calls` and `inviteLinks`. Cover text, media, polls, timers, list events, call-log entries and receipts, from `app/src/firebase/java/com/firestream/chat/data/remote/firebase/` and `functions/index.js`. Name the rule that admits each one.
- `users/{uid}` update: `isOwner(uid)` only. Delete `|| request.auth == null` and its comment; the presence function uses the Admin SDK.
- Message create: require `request.resource.data.senderId == request.auth.uid`. If a client path legitimately writes another sender (a call-log entry, a system message), constrain that case explicitly and record it in `docs/SCHEMA-FIRESTORE.md`.
- Call candidates: read and create only for the parent call's `callerId` and `calleeId`, through a `get()` on `calls/{callId}`.
- `inviteLinks`: `get` only, no `list`. Create and delete only by the chat's creator or an admin; if the document lacks the chat id, add it in the client here.
- Header comment in `firestore.rules`: the owner deploys rules; a step never does.

Tests: the harness arrives in Step 4. Until then the Approach block's audit table is the evidence.
Done when: every client write path is listed with the rule that admits it.
Trap: a rule that denies a write the app makes fails quietly (a send stuck in the outbox, a receipt that never lands). List the owner's smoke test in the Shipped block.

### Step 4 — Rules and Cloud Functions get tests — model: strong

Findings: TEST-1, CI-2 (functions half), SEC-10.

- An emulator suite with `@firebase/rules-unit-testing` under `functions/test/rules/` pins Step 3's rules. Per collection, it has allowed and denied cases for the owner, a participant, a non-participant and an unauthenticated principal, using the write shapes from Step 3's audit.
- If the owner committed `storage.rules` and `database.rules.json` at checkpoint B, register them in `firebase.json` and test the basics: chat media readable only by participants, presence writable only by its user.
- Unit tests for the four functions' pure parts (payload building, the block check), with `firebase-admin` mocked.
- CI: a `rules-and-functions` job (Java and Node, `firebase emulators:exec --only firestore …`) on PRs that touch `firestore.rules`, `storage.rules`, `database.rules.json`, `functions/**` or `firebase.json`.

Tests: the suite. It must fail if `|| request.auth == null` returns.
Done when: the suite is green locally and in CI.
Trap: the emulator downloads a JAR on first use. If the container cannot fetch it, stop with `blocked` and say so; the owner can run it once locally.

### Step 5 — Field-level rules for chats, messages and sticker packs — skills: code-review; model: strong

Findings: SEC-2, SEC-4, SEC-12. Write each case in Step 4's suite before changing the rule.

- Chats: members update only the fields every member writes (take the list from Step 3's audit: last-message preview, unread counters, typing, …). Admin-only: `admins`, `owner`, `permissions`, `requireApproval`, `pendingMembers`, `name`, `avatarUrl`, `description`, `inviteLink`. `participants` changes only by an admin, except a member removing themselves. Mirror what `CheckGroupPermissionUseCase` enforces in the client.
- Messages: `senderId` is immutable. Content, edit and tombstone fields change only by the sender. Any participant changes only their own entries in `reactions`, `readBy` and `deliveredTo` (`diff().affectedKeys()`).
- Sticker packs: the fix TECH_DEBT prescribes in "A sticker pack's id can be claimed by whoever writes its manifest first". Update that entry.

Tests: one allowed and one denied case per field group and role.
Done when: the suite covers every write path from Step 3's audit, and all cases pass.
Trap: recipients write receipts and unread counters into documents they did not create. Get those cases right, or receipts stop syncing.

### Step 6 — Pushes carry no message text; push tokens leave the profile — skills: code-review; model: strong

Findings: SEC-5, SEC-3 (token half), BLD-2 (Node), I18N-5, I18N-6. Decision D-2.

- `functions/index.js`: the message push drops `messageContent`. It keeps ids, the message type and display names.
- `FCMService`: build the notification text from the message fetched by `reconcileFromPush` and local names. If the fetch fails, fall back to a type-only text ("New message", "Photo"). One label table in `MessageTypeLabel.kt` replaces the copies in `ChatMessageActions.kt:200-214` and `FCMService.kt:233-242`. Notification strings move to resources.
- Push tokens: the client writes `fcmTokens/{uid}` (`token`, `updatedAt`). Rules allow the owner to write and nobody to read. The functions read it and fall back to `users/{uid}.fcmToken` for a transition. The client deletes the old field on its next token write. Record removing the fallback in TECH_DEBT with its trigger.
- `firebase.json` and `functions/package.json`: the newest Node runtime Firebase supports (check the list; at least `nodejs22`).

Tests: Robolectric tests of notification building for text, media, encrypted and fetch-failure cases. Rules cases for `fcmTokens`. A functions test asserting no payload carries message text.
Done when: no function puts message text into a payload.
Trap: a data-only push must still post its notification inside the background execution window. Fetch with a short timeout before falling back.

### Step 7 — Client privacy: logs, link previews, WebView, encryption copy — skills: code-review; model: strong

Findings: SEC-7, SEC-13, SEC-14, UX-15. Decision D-3.

- `app/proguard-rules.pro`: `-assumenosideeffects class android.util.Log { public static int d(...); public static int v(...); }`. `WebPagePreviewCapture` and `ShareContentResolver` log hosts, not full URLs or URIs.
- A preference "Preview links in received messages" (default off) under Settings → Privacy. `ChatMessageLoader.fetchLinkPreviewsFor` checks it; links the user sends still preview.
- `WebPagePreviewCapture`: set `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` to false explicitly. Refuse loopback, link-local and private-range hosts before both the OkHttp fetch and the WebView load.
- E2E copy (`SettingsScreen.kt:305` and the enable dialog) states the scope: 1:1 text messages, not edits, media, voice notes, files, locations or groups. A 1:1 chat with E2E on shows a small lock in the top bar and a one-time notice at the top of the chat.

Tests: the preference gate in the loader; the host blocklist as a pure function; a Robolectric test of the lock indicator.
Done when: with default settings, a received link causes no network request.

### Step 8 — The updater checks who signed the APK — skills: code-review; model: strong

Findings: SEC-9. Decision D-16.

- `app/build.gradle.kts`: `BuildConfig.RELEASE_CERT_SHA256` from D-16. Debug builds use the debug certificate's digest, so the updater flow stays testable.
- Before `ApkInstaller.install`, read the downloaded archive's signing certificates (`PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`) and check the package name. Refuse the install and delete the file when nothing matches. Show the refusal as an update error.
- `docs/RELEASING.md`: how to read the digest, and that rotating the key means updating it.

Tests: the digest comparison as a pure function; a seam test of the refusal path.
Done when: an APK signed with another key is refused before the system installer opens.
Trap: if D-16 is still empty, stop with `needs_decision`. Never ship a placeholder digest; it would block every update.

### Step 8c — Phase check: security — skills: phase-check; model: strong; budget: 8

Checks Steps 3, 4, 5, 6, 7 and 8. Decision D-17.

- Every client write path in Step 3's audit table passes the rules suite. That includes the receipts and unread counters recipients write, and the `fcmTokens` write from Step 6.
- No function payload carries message text, and `FCMService` still posts a notification when its fetch fails.
- The functions read `fcmTokens/{uid}` first and fall back to `users/{uid}.fcmToken`.
- Release log stripping (Step 7) removes only `Log.d` and `Log.v`.
- The updater's signer check (Step 8) refuses a foreign APK and still lets the debug-signed test flow through.

Done when: the **Phase check** block is committed, and every fix carries its test.

### Step 9 — Room migrations stop wiping data — skills: code-review; model: strong

Findings: L-1, PERF-7, PROC-8 (schema export).

- `exportSchema = true` on `AppDatabase` and `SignalDatabase`, with the KSP argument `room.schemaLocation` set to `app/schemas/`. Commit both current schemas.
- `di/DatabaseModule.kt`: replace `fallbackToDestructiveMigration()` with `fallbackToDestructiveMigrationFrom(...)` listing every version below the current one, so old installs still reset once. Keep `fallbackToDestructiveMigrationOnDowngrade()`. `SignalDatabase` gets the same treatment; a Signal schema change must never wipe identity keys.
- The first real migration, `MIGRATION_31_32`, adds `Index(["chatId","timestamp"])` and `Index(["type","timestamp"])` on `messages`. Split `searchMessages`' `:chatId IS NULL OR …` into two queries so chat-scoped search can use the index.
- A migration test with `MigrationTestHelper` under Robolectric: create a version 31 database holding a queued SENDING row, a starred message and a `localUri`, migrate, and assert all three survive. The test source set needs the schemas as assets (`android.sourceSets["test"].assets.srcDir("$projectDir/schemas")`).
- CI fails when a committed schema JSON for an existing version changes.
- Rewrite the "Room version bump rule" in CLAUDE.md and `docs/PATTERNS.md`: bump the version, add a `Migration`, add a migration test, commit the new schema JSON. Update `docs/SCHEMA-ROOM.md`.

Tests: the migration test; the existing DAO tests stay green.
Done when: an install over the previous release keeps queued messages, stars and local files (owner checks at checkpoint D).

### Step 10 — PocketBase out of the default build — model: mid

Findings: ARCH-12. Decision D-11. If D-11 is declined, delete this step from the Order line.

- In the `androidComponents { beforeVariants … }` hook, disable the `pocketbase` variants unless the project property `pocketbase` is set (`-Ppocketbase`).
- CI's main job then builds firebase only. Add a `workflow_dispatch` job, plus a weekly schedule, that runs `-Ppocketbase test assembleDebug`.
- Update CLAUDE.md's build commands, `pocketbase/README.md` and TECH_DEBT's PocketBase section (the condition for unfreezing).

Tests: without the property, `./gradlew tasks --all` lists no pocketbase task; with it, `assemblePocketbaseDebug` configures.
Done when: `./gradlew test` runs one flavor's unit tests.

### Step 11 — Repositories let cancellation through — skills: code-review; model: max

Findings: ARCH-1, PERF-11, PERF-12.

- `data/util/ResultExt.kt`: `resultOf` rethrows `CancellationException`; correct its KDoc. Move hand-rolled `try { … } catch (e: Exception) { Result.failure(e) }` in suspend code onto `resultOf`. The remaining catches in suspend code call `rethrowIfCancellation()` first; they cluster in `CallRepositoryImpl`, `UserRepositoryImpl`, `AuthRepositoryImpl` and `ListRepositoryImpl`.
- `MediaBackfillWorker.kt:75`: stop the loop on cancellation (`ensureActive()`).
- `MediaFileManager` and `ProfileImageManager` use the existing `SingleFlight`, so one caller's cancellation no longer cancels the others. Bound auto-downloads with a small permit count.
- Konsist: forbid an empty `catch (_: Exception) { }` in `data/`, and a `catch (e: Exception)` in a suspend function without `rethrowIfCancellation`. Baseline any case left over in TECH_DEBT.
- CLAUDE.md Key Conventions: "A `catch` in suspend code rethrows `CancellationException`." Update the GOTCHAS entry and the TECH_DEBT "`SingleFlight` exists but …" entry.

Tests: `resultOf` rethrows on cancellation; cancelling a repository caller cancels its work; the worker stops after cancellation; a cancelled single-flight caller leaves the other waiters running.
Done when: Konsist proves no catch in suspend code swallows cancellation.
Trap: `withTimeout` throws a `CancellationException` subtype. A source that relies on catching it must convert it to `IOException` first, as `FirestoreMessageSource.kt:67-70` does. ViewModel `onFailure` paths stop seeing cancellation; check their tests.

### Step 12 — Native libraries are stripped; CI builds and checks the release APK — model: strong

Findings: PERF-1, CI-1, PERF-14, BLD-5 (`firebase-functions`).

- `app/build.gradle.kts`: pin `ndkVersion`. Install that NDK in `release-apk.yml`, in CI and in `scripts/install-android-sdk.sh`. Remove the unused `firebase-functions` dependency. Narrow `-keep com.google.firebase.**` only if R8 still builds and the app runs; otherwise record why in TECH_DEBT.
- `scripts/check-release-apk.sh`: `lib/arm64-v8a/libsignal_jni.so` is present, no `.so` carries `.debug_info` (`readelf -S`), the dex holds `org.signal` classes, and the APK is under 50 MB.
- CI: a `release-build` job builds `assembleFirebaseRelease` with the debug signing config (no secrets) and runs the script. `release-apk.yml` runs the same script before publishing.
- `NetworkModule.kt:34-40`: correct the comment about the APK size; keep generous timeouts.

Tests: the script against the CI-built APK. Note one deliberate failing run (an unstripped library) in the Shipped block.
Done when: the CI-built release APK is about 40 MB and passes the check.
Trap: debug builds exclude `libsignal_jni.so` through `onVariants`; keep that. The release check is what proves release still contains it.

### Step 13 — i18n and accessibility guardrails; plurals — model: mid

Findings: I18N-1 (guardrails), I18N-4, A11Y-12.

- `ui/common/UiText.kt`: a sealed type (`Res(id, args)`, `Plural(id, count, args)`, `Raw(text)`) with `asString()` for Compose and for `Context`. `AppError.toUiText()` lives in `ui/`, so `domain/` stays Android-free under the Konsist rule.
- A Konsist ratchet counts string literals passed to `Text(`, `text =`, `contentDescription =`, `label =`, `placeholder =`, `title =` and snackbar calls in `ui/`. It fails when the count exceeds a baseline stored in the test. Steps that remove literals lower the baseline.
- Test helpers: `str(R.string.x, …)` for Robolectric tests, and semantics assertions (`assertHasClickLabel`, `assertIsHeading`, `assertIsToggleable`).
- `res/values/plurals.xml` for counts and selections. Fix `ProfileScreen.kt:384`, `SharePickerScreen.kt:347`, `ChatPickerPanel.kt:100`, `CreateGroupScreen.kt:76`, `CreateBroadcastScreen.kt:76`. Delete the 12 unused strings in `strings.xml`.
- CLAUDE.md Key Conventions and `docs/PATTERNS.md`: "New user-facing text goes in string resources; strings built outside Compose travel as `UiText`."

Tests: `UiText` resolution; the ratchet; plural cases for 0, 1 and 2.
Done when: the ratchet runs in the gate with today's count as its baseline.

### Step 13c — Phase check: foundations — skills: phase-check; model: strong; budget: 8

Checks Steps 9, 10, 11, 12 and 13. Decision D-17.

- The migration test holds a queued SENDING row, a star and a `localUri`. For both databases, the destructive fallback covers only versions below the current one.
- If Step 10 ran: CI's main job runs the whole firebase test suite, and the manual pocketbase job still compiles.
- After Step 11, no ViewModel shows an error for a call cancelled by leaving its screen, and no loop or worker ignores cancellation.
- The release APK check runs in both `ci.yml` and `release-apk.yml`, and debug builds still exclude `libsignal_jni.so`.
- The ratchet's baseline equals the literal count at HEAD.

Done when: the **Phase check** block is committed, and every fix carries its test.

### Step 14 — Settings say what they do — skills: app-ui-design; model: mid

Findings: UX-1, UX-6 (interim), UX-22 (dead rows), ARCH-11 (Settings writes). Decisions D-4, D-7.

- Screen security: `MainActivity` and `CallActivity` observe the preference and set or clear `FLAG_SECURE`.
- Message and Group notifications: `FCMService` still syncs, but posts no notification for a disabled kind. The mention-only rule keeps working.
- Sound and Vibration become one "Notification settings" row that opens the system's app notification settings. Delete both preferences.
- Hide the Last seen row; keep its preference key for F-6.
- Remove Storage Used, Terms, Privacy and Support until they do something.
- Dictation defaults to the device language when it is one the recognizer offers. The mic's content description says "Dictate".
- Every Settings preference write goes through `@ApplicationScope` (PATTERNS "DataStore writes need @ApplicationScope").

Tests: `SettingsViewModel` writes through the application scope; `FCMService` gating (Robolectric); `FLAG_SECURE` set when the preference is on.
Done when: every Settings control changes behaviour or is gone.

### Step 15 — Destructive actions confirm or undo — skills: app-ui-design, code-review; model: mid

Findings: UX-3, UX-7, UX-23 (invite revoke), UX-24. Decision D-5.

- Create `ui/components/ConfirmDialog.kt` (title, message, confirm label, destructive flag) if it doesn't exist; Step 29 migrates the other copies to it.
- Own messages get a dialog with "Delete for everyone", "Delete for me" and "Cancel". Incoming messages get "Delete for me". "Delete for me" sets a `hiddenForMe` column, added by a real migration per §1. Chat queries, search and the chat-list preview filter it out.
- Archive: an Undo snackbar; an "Archived (n)" row at the top of the chat list when n > 0; correct copy at `ArchivedChatsScreen.kt:74`; an empty state when every chat is archived.
- Reminders: swipe-delete gets Undo and a snackbar host. Revoking an invite link asks first.

Tests: ViewModel tests for "Delete for me" filtering and for undo; the migration test for `hiddenForMe`; Robolectric for the archived row and the dialog.
Done when: nothing in a chat, the chat list or the reminders list is destroyed without a confirmation or an undo.

### Step 16 — Group members can be added; invite links per D-6 — skills: app-ui-design; model: mid

Findings: UX-2, L-3. Decision D-6.

- "Add member" opens a multi-select contact picker that excludes current members. Reuse `CreateGroupScreen`'s selection list, or `ChatPickerPanel` if it fits PATTERNS "One chat picker, three hosts". It calls `addGroupMember` for each pick, gated by `CheckGroupPermissionUseCase`.
- Hide the invite-link and QR UI in Group Settings. Keep the repository code for F-7.
- Correct `docs/ARCHITECTURE.md` and SPEC.

Tests: the add-member flow in `GroupSettingsViewModel` with the fake chat repository; permission gating.
Done when: an admin adds a contact, and the new member appears for every participant (Step 5's rules allow it).

### Step 17 — Permissions ask with a reason and recover — skills: app-ui-design; model: mid

Findings: UX-4, UX-14.

- `ui/components/PermissionRequest.kt`: show a rationale when `shouldShowRequestPermissionRationale` is true, request, and offer "Open settings" after a permanent denial. Use it for camera, mic (dictation and calls), location and notifications.
- Location requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` together and accepts either.
- The notification permission is requested on the first chat-list visit after sign-in, not at cold start. Settings shows "Notifications are blocked" with a fix-it action when they are.
- Gallery uses the Photo Picker with no permission. Remove the `READ_MEDIA_*` request path and the unused `READ_CONTACTS`; first check nothing else needs them.
- A first mic denial no longer jumps to system settings.

Tests: the permission state machine as a pure function; Robolectric for the location request carrying both permissions.
Done when: denying any permission once leaves a clear way to grant it later.

### Step 18 — Notification taps keep the app; screens survive process death — skills: code-review; model: strong

Findings: UX-8, ARCH-2 (group timer), ARCH-3 (Message Info), UX-16 (Message Info).

- Message and update notifications use `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`, so a warm tap reaches `onNewIntent`. `onNewIntent` also handles `openSettings` and `focusUpdate`.
- `MainActivity.deepLinkFromIntent` accepts a chat id without a sender id. When the hint is empty, the chat screen resolves the partner from the chat row. This is the narrow fix for group timer taps; F-3 deletes the argument.
- Message Info loads its message by id in its own ViewModel (the route already declares `messageId` and `chatId`). Delete the `remember` holder in `NavGraph.kt`.

Tests: intent parsing for a group timer; the Message Info ViewModel loads by id; `LaunchRestoreNavigationTest` stays green.
Done when: a warm tap on a message notification keeps the back stack and the unsent text, and a group timer's notification opens its chat.

### Step 19 — Phone sign-in finishes the job — skills: app-ui-design, code-review; model: strong

Findings: UX-5, UX-16 (sign-in fields), TEST-2 (`AuthViewModel`).

- `FirebasePhoneAuth`: sign in with the credential from `onVerificationCompleted`, and keep the `ForceResendingToken`.
- OTP screen: Resend with a 60-second countdown that uses the token; "Wrong number?" back to Login; one-time-code autofill; auto-submit at six digits.
- Login: default the country code from the network or SIM country, else the locale; normalise to E.164; `ImeAction` on each field.
- Map Firebase auth exceptions to plain copy through `AppError`: invalid code, too many attempts, network, quota.
- `rememberSaveable` for the Login, OTP and Profile Setup fields. The Profile Setup avatar circle opens the picker, or stops looking tappable.

Tests: a new `AuthViewModel` test for the auto-verify path, the resend countdown and error mapping; the E.164 normaliser.
Done when: a user whose code auto-verifies lands signed in without typing it.

### Step 20 — Chat interaction fixes — skills: app-ui-design; model: mid

Findings: UX-11, UX-13, UX-19 (clamp), UX-20, UX-21, UX-23 (pinned banner).

- Reaction chips: a tap toggles your reaction; a long press opens a "who reacted" sheet.
- Poll and list bubbles open the standard long-press menu, filtered by type.
- Group typing renders `TypingRow` (it exists and is unused) with who is typing.
- Send preview: Back with edits or a caption asks "Discard edits?".
- The emoji panel's height is capped at about 45% of the screen height.
- Pinned banner: the `PushPin` icon, a 48 dp unpin target, and the jump flash used elsewhere.

Tests: one test per behaviour (ViewModel or Robolectric).
Done when: all six behaviours have tests.

### Step 21 — Shell and chat menus — skills: app-ui-design; model: mid

Findings: UX-17, UX-18.

- `MainScreen`: Back on Calls or Lists returns to Chats. The overflow icon is `MoreVert`. Settings and search are reachable from every tab's top bar.
- Chat ⋮ menu: Mute or Unmute (Unmute is direct, no dialog), Contact or Group info, Block (1:1 only), Shared Media, Shared Lists.
- Opt in to predictive back (`android:enableOnBackInvokedCallback="true"`) and check that the custom `BackHandler`s still behave; list them in the Shipped block for the owner's device pass.

Tests: Robolectric for Back on the secondary tabs; menu items per chat type.
Done when: Back never leaves the app from a secondary tab.

### Step 22 — Partner and title become behaviour on `Chat` — skills: code-review; model: strong

Findings: the 2026-09-20 review's #2 (the live blank-name bug), TECH_DEBT "Who is the other participant…" and "What is this chat called has three divergent copies".

- `domain/model/Chat.kt`: `partner(currentUserId): String?` (the one other participant of an INDIVIDUAL chat, `singleOrNull` semantics) and `title(resolve: (String) -> String?)` (one precedence, blank-safe). Delete the `// Phase N` comments.
- Replace the hand-rolled copies in `ui/components/ChatTargets.kt`, `GlobalSearchLabels.kt`, `ChatListItem.kt`, `ChatListScreen.kt`, `ChatListViewModel.kt`, `ArchivedChatsScreen.kt`, `CallsViewModel.kt`, `ListShareSheet.kt`, `ListsViewModel.kt`, `ListDetailViewModel.kt`, `ChatUtils.kt`, `ChatTimerReactor.kt` and `data/timer/BootRestoreLogic.kt`. `CallsViewModel` stops naming an arbitrary group member, and `ChatListItem` never renders a blank title.
- Update both TECH_DEBT entries.

Tests: one pure table test on `Chat` (group, self-chat, unsynced participants, blank name); the blank-name case added to `ChatListItemUiTest`.
Done when: `grep` finds no partner or title logic outside `Chat`.

### Step 22c — Phase check: broken flows — skills: phase-check; model: mid; budget: 8

Checks Steps 14 to 22. Decision D-17.

- Every new dialog, sheet and screen state survives rotation and process death: the delete dialog, the discard prompt, the permission rationale, the OTP countdown, the archived row.
- The new `BackHandler`s, predictive back and `onNewIntent` agree. A notification tap while a dialog is open lands in the chat.
- Messages hidden with "Delete for me" stay hidden in search, the chat-list preview, starred and reminders.
- The ratchet count did not rise, and the new UI works in dark mode and at font scale 2.0.
- No hand-rolled partner or title logic remains, including any that Steps 15–21 added before Step 22 moved it onto `Chat`.

Done when: the **Phase check** block is committed, and every fix carries its test.

### Step 23 — Typing and startup sync stop fanning out — skills: code-review; model: strong

Findings: PERF-2, PERF-3 (guard), ARCH-4.

- `ChatMessageSender.onTyping`: write `true` at most every 3 seconds while typing (readers treat an entry as live for 10 seconds). Keep the 4-second stop debounce.
- `ChatRepositoryImpl`: share the chat sync inside the repository (`shareIn(appScope, SharingStarted.WhileSubscribed(5_000))`), so all collectors share one listener and one Room upsert. Skip the Room write when the mapped rows are unchanged. Move the avatar pass out of `collectLatest` cancellation, or key it by URL.
- `syncAllChatMessages` skips a chat whose remote `lastMessageTimestamp` is not newer than Room's newest message for it. Pull-to-refresh uses the same guard. Errors surface instead of disappearing into `catch (_: Exception) { }`.

Tests: the typing throttle in virtual time; two collectors share one upstream (a fake source counts subscriptions); the guard skips unchanged chats.
Done when: a 40-character sentence produces a handful of typing writes, and a cold start with nothing new reads no messages (owner measures at checkpoint G).

### Step 24 — Snapshots off the main thread; presence offline in the background — skills: code-review; model: strong

Findings: PERF-4, PERF-6 (quick win).

- Every listener in `app/src/firebase/java/com/firestream/chat/data/remote/firebase/Firestore*Source.kt` passes a single-thread executor, as `FirestoreStickerPackSource` does, so emission order stays per listener.
- The reconcile in `MessageRepositoryImpl` runs on `Dispatchers.Default`, not only its Room half.
- `AppLifecycleObserver`: call `FirebaseDatabase.goOffline()` after the offline write in `onStop`, and `goOnline()` in `onStart`. `onDisconnect` still covers abrupt exits.
- Read GOTCHAS' listener-ordering entries before starting.

Tests: an executor seam in the sources that have tests; the lifecycle observer calls `goOffline` and `goOnline` (fake).
Done when: no snapshot callback maps documents on the main thread.

### Step 25 — Bubbles skip recomposition — skills: app-ui-design, code-review; model: mid

Findings: PERF-5, PERF-15, PERF-17.

- `app/compose-stability.conf` lists `com.firestream.chat.domain.model.*`, wired through `composeCompiler { stabilityConfigurationFiles }`.
- `MessageRepositoryImpl.getMessages` reuses the previous `Message` instance when its entity is unchanged (a cache from id to entity and message).
- The message list gets `contentType = { _, m -> m.type }`. The chat and calls lists get keys and content types where missing.
- A presence flip recomposes one chat-list row, not all of them. The typing dots animate in `graphicsLayer` instead of recomposing every frame.

Tests: the repository returns identical instances for unchanged rows; the mapping cache's eviction.
Done when: a read-receipt burst recomposes only the bubbles it changes (owner checks with Layout Inspector at checkpoint G).

### Step 25c — Phase check: performance — skills: phase-check; model: strong; budget: 8

Checks Steps 23, 24 and 25. Decision D-17.

- Each listener still emits in order after Step 24's executor change, and GOTCHAS' listener-ordering cases still hold.
- The shared chat sync stops when its last collector leaves, restarts when one returns, and survives `goOffline` and `goOnline`.
- The `Message` instance cache drops an entry whenever a column a bubble shows changes: status, reactions, edits, `localUri`, `hiddenForMe`.
- The typing throttle still sends the stop write, and Step 5's rules accept both typing writes.

Done when: the **Phase check** block is committed, and every fix carries its test.

### Step 26 — The screenshot catalogue gates CI — model: mid

Findings: VIS-20, TEST-3. Decision D-9.

- Replace the stale Roborazzi tests with a catalogue under `app/src/test/java/com/firestream/chat/catalogue/`. It covers text, media, poll, list and sticker bubbles (own and other), a chat-list row, settings rows, dialogs, top bars and empty states, each in light, dark and font scale 1.5.
- Record the baselines and add `verifyRoborazziFirebaseDebug` to CI. Document re-recording in `docs/TESTING.md`. Delete the TECH_DEBT entry.

Tests: the catalogue.
Done when: CI fails on an unrecorded visual change.

### Step 27 — The theme is complete — skills: app-ui-design; model: mid

Findings: VIS-1, VIS-2, VIS-4, A11Y-1, A11Y-2, VIS-18 (dead tokens). Decision D-8.

- `Type.kt`: all 15 styles in Plus Jakarta Sans with explicit line heights.
- `Theme.kt`: set every Material 3 role in both schemes from existing tokens: the `surfaceContainer*` ladder, `surfaceDim`/`surfaceBright`, `tertiary` and its container, `outline`/`outlineVariant`, `inverse*`, `scrim`, `errorContainer`. Light `primary` is `FireOrangeDark`. Light `secondaryContainer` becomes a light warm neutral. The jump-to-message frame uses `primary`.
- The bottom-nav indicator reaches 3:1 against the selected icon in light mode.
- Read receipts: Read gets its own icon, not only a color. Bubble metadata alpha is 0.8.
- Delete the six dead tokens in `Color.kt` and fix the two wrong comments.
- Re-record the catalogue; the owner reviews the diffs at checkpoint H.

Tests: every `Typography` style uses the family; WCAG ratios for the key pairs (onPrimary on primary, primary on background, metadata on bubble) meet 4.5:1 for text and 3:1 for icons.
Done when: no Material component renders in Roboto or on baseline purple.

### Step 28 — System bars follow the in-app theme — skills: app-ui-design; model: mid

Findings: VIS-3.

- One effect keyed on the resolved theme sets status and navigation bar icon appearance through `WindowInsetsControllerCompat`. Delete the `statusBarColor` write. `enableEdgeToEdge` gets a `SystemBarStyle` for the resolved theme.
- `res/values-night/themes.xml`, so the window background matches dark mode.
- Media viewers force light bar icons while shown.
- One helper resolves the "System" theme option for `MainActivity` and `CallActivity`.

Tests: Robolectric checks `isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars` for the light, dark and system settings.
Done when: switching the in-app theme updates both bars at once (owner checks on Android 15).

### Step 29 — Shared components replace the copies — skills: app-ui-design, simplify; model: mid

Findings: VIS-9, VIS-10, VIS-11, VIS-12, VIS-13, A11Y-3, A11Y-4.

- In `ui/components/`: `fsTopAppBarColors()`, `SectionHeader` (with `heading()` semantics), `SettingsRow` and `ToggleRow` (one `toggleable` node named by its label), `SingleChoiceDialog` (radio buttons in a `selectableGroup`), `ConfirmDialog` (from Step 15) and an action sheet for "choose an action".
- Semantic colors (online, danger, warning) become theme tokens.
- Migrate every copy: 18 top bars, 3 section-header styles, 5 single-choice dialogs, about 10 confirm dialogs, 28 tonal-pill menus. Dialog titles use sentence case.

Tests: catalogue entries for each component; semantics assertions for heading, toggleable and selectable.
Done when: `grep` finds no hand-rolled copy of those patterns.

### Step 30 — Avatars, the Lists tab, bubbles and touch targets — skills: app-ui-design; model: mid

Findings: VIS-5, VIS-6, VIS-7, VIS-8, VIS-14, A11Y-7, A11Y-9 (badge).

- `UserAvatar(name = …)` shows initials when there is no photo and replaces the five hand-rolled discs. `size` drives the layout, so call sites drop their duplicate `Modifier.size`. `MemberRow` passes `avatarUrl`.
- Lists tab: the shared top-bar colors, `contentWindowInsets = WindowInsets(0)`, a solid `primary` FAB, a skeleton that matches its rows.
- Poll and list bubbles use `BubbleTailShape` and the 280 dp maximum width through shared helpers in `MessageBubble.kt`.
- Skeleton contrast rises to visible. Touch targets reach 48 dp: the retry icon, the picker search clear, the preview remove, and the edit badges (one 28 dp visual size). The unread badge grows with the font scale.

Tests: catalogue updates; Robolectric for `MemberRow` showing a photo.
Done when: the Lists FAB stays put while swiping between tabs (owner checks at checkpoint I).

### Step 31 — Settings becomes a hub; every screen can report an error — skills: app-ui-design, code-review; model: mid

Findings: UX-22, ARCH-11, the review's "no error channel" list.

- Settings becomes a hub with sub-pages (Account, Privacy, Chats, Notifications, Storage and data, Stickers, About), built from Step 29's rows. Starred, Reminders and Archived get entries in the chat-list overflow.
- Settings, Profile, Archived, Reminders, Starred and Shared lists get a `SnackbarHost`.
- One-shot events use `Channel(Channel.BUFFERED).receiveAsFlow()`, including `ChatViewModel`'s snackbar flow, so "saved to Downloads" is no longer dropped. Add the PATTERNS entry.
- The three `String?` errors (`ProfileViewModel.kt:30,32`, `GroupSettingsViewModel.kt:34`) become `AppError`.
- Settings' strings move to resources; lower the ratchet baseline.

Tests: navigation between sub-pages; a snackbar event survives a collector gap; the error mapping.
Done when: no Settings page is longer than about one and a half phone screens.

### Step 32 — Dates and times follow the locale — model: mid

Findings: I18N-3, PERF-16.

- `ui/common/DateTimeFormats.kt`: time of day honours `DateFormat.is24HourFormat(context)`; dates use localized best patterns (`DateFormat.getBestDateTimePattern(locale, "MMMd")`); "Today" and "Yesterday" come from resources or `DateUtils`; durations use a resource-based formatter.
- Replace all 23 fixed-pattern `SimpleDateFormat` sites. Formatters are built once per locale and setting, never per row.

Tests: `Locale.US` 12-hour, `Locale.GERMANY` 24-hour, the system 24-hour override, and the Today/Yesterday boundaries.
Done when: no fixed-pattern `SimpleDateFormat` remains in `ui/`.

### Step 33 — A chat that TalkBack reads well — skills: app-ui-design; model: mid

Findings: A11Y-5, A11Y-6, A11Y-8, A11Y-9 (links), A11Y-10, A11Y-11.

- A bubble merges into one description: sender, text or media kind, time, status, reactions. It drops the click role where a tap does nothing. Long press gets `onLongClickLabel = "Message options"`, and Reply and React become `customActions`.
- The typing indicator gets text ("Anna is typing") and a polite live region. The chat-list row keeps the last message in its description while someone types.
- Label the three meaningful images (`SearchResults.kt:416`, `ImagePreviewScreen.kt:912/925`, `CallsScreen.kt:230`).
- Links in bubbles follow the chat font size.
- Lottie stickers and GIFs show their first frame and stop when the system animator scale is 0.
- The swipe drag and `BubbleTailShape` respect `LayoutDirection.Rtl`.

Tests: Step 13's semantics assertions; RTL swipe direction.
Done when: a TalkBack pass reads each message once, with its status (owner checks at checkpoint I).

### Step 34 — CI hardening — model: mid

Findings: CI-2, CI-3, BLD-1, BLD-4, BLD-5, TEST-4.

- Upload test reports when a job fails.
- Pin write-scoped third-party actions by commit SHA. Add `.github/dependabot.yml` for Gradle, npm (`functions/`) and Actions: weekly, grouped.
- `app/build.gradle.kts`: `providers.exec` replaces `Project.exec`. The C1-only test JVM flags apply only when a local property opts in; `ReservedCodeCacheSize` stays.
- Remove the unused `compose-ui-test` version and the androidTest and espresso dependencies.
- `ImagePreviewScreenZoomTest` and `SignalManagerTest:246` use a test clock.

Tests: CI green; the two timing tests pass five runs in a row.
Done when: Gradle reports no `Project.exec` deprecation. Try `--configuration-cache` once and record the result in the Shipped block.

### Step 35 — Dead code goes — model: mid

Findings: ARCH-10.

- Delete `ui/chat/CommandChip.kt` and `domain/model/MediaAttachment.kt`. Delete each of the 11 repository members that still has no production caller after Steps 16 and 18, with its fake overrides and PocketBase stubs.
- Remove the `// Phase N` comments and the commented-out route in `NavGraph.kt`. Correct the `EmojiHandlerPanel` KDoc.
- Update `docs/FEATURE-MAP.md` and `docs/ARCHITECTURE.md`.

Tests: the gate.
Done when: nothing references a deleted member.

### Step 36 — CLAUDE.md follows the target outline; skills pruned — skills: writing-for-agents; model: strong

Findings: MD-5, MD-6, MD-8, MD-9, PROC-7, PROC-9. Decision D-13.

- Restructure CLAUDE.md to the outline in §6. Move the plan-runner options to `scripts/plan-runner/README.md`, the plan format to a new `docs/plans/README.md`, the cloud setup detail to the session-start hook's comments and GOTCHAS, and the changelog detail to the `changelog-release` skill.
- The plan format in `docs/plans/README.md` includes the phase check (D-17): before each code phase's checkpoint, a `### Step Nc — Phase check: <phase>` step with `skills: phase-check`, a `budget:` tag, the steps it checks and 3–6 checklist items. A phase made only of docs or owner-reviewed visuals needs none.
- Key Conventions: one clause and a link each.
- Route GOTCHAS by area: Compose → `app-ui-design`; coroutines, Firestore and Room → their GOTCHAS sections; a Service, notification, WebRTC or FCM change → GOTCHAS § Platform; tests → § Testing.
- Add: "Search the long docs (CHANGELOG, FEATURE-MAP, TECH_DEBT, BACKLOG, GOTCHAS) with Grep; don't read them whole."
- A project `handoff` skill writes `docs/plans/handoff-<topic>.md`; drop `claude-handoff`. Prune the skills D-13 drops (symlinks and lock entries).
- Apply CLAUDE.md's own writing rules: no dates inside rules, no chained clauses.

Done when: CLAUDE.md is at most about 18 KB, and the Shipped block lists every moved rule with its new home.

### Step 37 — Release tooling does the bookkeeping — skills: code-review; model: strong

Findings: PROC-1, PROC-2, DOC-5 (CHANGELOG). Decision D-12.

- `scripts/cut-release.sh`: fetch tags (and unshallow when shallow); compute the next version from commit prefixes since the last tag (`feat` → minor; `fix`, `refactor`, `perf` → patch; major only with `--major`); stamp each `## [Unreleased]` entry with the hash of the commit that added it (`git log -S`), keeping hand-written hashes; rename the section to the version and date.
- `scripts/check-changelog-header.sh` checks the new header form.
- Update the `changelog-release` skill and CLAUDE.md's Changelog section: a user-visible commit adds an entry under `## [Unreleased]`, and nothing else.
- Archive the sections before 1.30 to `docs/changelog/1.0-1.29.md`. `copyChangelogAsset` concatenates both files, so the in-app changelog still shows everything. Fix the duplicate `## [1.0.0]` header.

Tests: a shell test in a temporary git repo: entries get stamped, the bump is computed, hand-written hashes survive.
Done when: a user-visible change needs one commit, not two.

### Step 38 — The verification loop and doc checks — model: mid

Findings: PROC-3, PROC-9, PROC-8 (link checks), ARCH-9 (trigger reminder). Decision D-15.

- Move BACKLOG's "Pending on-device verification" to `docs/VERIFY.md`, unchanged, with at most three must-pass checks marked per entry.
- `cut-release.sh` puts the VERIFY entries added since the last tag into the GitHub release notes as a checklist.
- CLAUDE.md states the D-15 cap.
- A CI script fails on a FEATURE-MAP path that doesn't exist or a CHANGELOG hash that doesn't resolve; both pass today.
- A non-blocking pre-commit hook lists the TECH_DEBT entries whose backticked paths appear in the staged diff.
- `scripts/plan-runner/plan-done-check.sh` (D-17) runs before a plan branch merges and costs no model tokens: the gate, `check-release-apk.sh`, the changelog header check, the FEATURE-MAP and CHANGELOG link check, and a list of the VERIFY entries the branch added. `scripts/plan-runner/README.md` names it as the last step before a merge.

Done when: BACKLOG holds only unshipped work, the release notes carry a device checklist, and `plan-done-check.sh` passes on this plan's branch.

## 4. Owner actions at checkpoints

| After | What the owner does |
|---|---|
| Step 2 | Read the new `/code-review` skill and the CLAUDE.md edits. Fill in D-16. |
| Step 3 | `firebase deploy --only firestore:rules`. Smoke test: sign in, send text and a photo, react, check receipts both ways, place a call, create and revoke an invite link. Export the Storage and Realtime Database rules from the console into `storage.rules` and `database.rules.json` and commit them (SEC-10). |
| Step 6 | `firebase deploy --only firestore:rules,functions`. Check notifications with the app in the foreground, the background and killed, for text, a photo, an encrypted message and a reaction. |
| Step 9 | Read Step 8c's **Phase check** block. Install the build over the previous release while holding a queued unsent message, a starred message and a downloaded photo. All three survive. |
| Step 13c | Read the **Phase check** block. Optionally run `/code-review ultra` on Step 11. Install the CI-built release APK (about 40 MB) and send an encrypted message. |
| Step 22c | Read the **Phase check** block. Device pass over Steps 14–22, using the checks in their Shipped blocks. |
| Step 25c | Read the **Phase check** block. In the Firebase console, compare reads per cold start and writes per typed sentence with the numbers before Step 23. Optionally take a Perfetto trace of opening a large chat. |
| Step 27 | Review the screenshot diffs in light, dark and large font. |
| Step 33 | TalkBack pass through the chat list, a chat and Settings. Switch the phone to German and check dates and times. Check the Lists FAB while swiping between tabs. |
| Step 36 | Read the new CLAUDE.md. |
| Step 38 | Run `scripts/plan-runner/plan-done-check.sh`, then merge `plan/app-improvements`. If the phase checks found nothing in two phases running, consider dropping them from future plans; `report.sh` shows what each one cost. |

## 5. Follow-up plans

Each becomes its own plan file when the owner picks it up. They are listed in the recommended order.

- **F-1 Voice notes** (UX-6, L): hold to record, slide to cancel, lock, a waveform, upload through the outbox.
- **F-2 ChatScreen track** (L, strictly sequential because every item edits `ChatScreen.kt`): a composer text holder (2026-09-20 #4) carrying per-chat drafts (UX-9); the zoomable pager (#3); the media-pick cut (#10); an unread divider and a count on the scroll button (UX-10); a selection mode and a compact long-press menu with quick reactions (UX-12); a keyboard-visibility test helper for the recurring keyboard bugs (PROC-4).
- **F-3 Architecture tracks** (L): delete the partner argument and move to typed routes (ARCH-2, ARCH-3); the data track ARCH-6 → #9 with #11 → ARCH-5 → #7 → #8 → #12; the ChatViewModel track #5, #6 (after the slice-convention decision) and ARCH-8; calls ARCH-7 with TECH_DEBT's `CallSession` entry. Every data-track item edits `MessageRepositoryImpl.kt`, so they never run in parallel.
- **F-4 Translation** (I18N-1, I18N-5, I18N-7, I18N-8, PROC-4, L): extract strings one package per step, lowering the ratchet each time; stop writing English into Firestore; German emoji keywords from CLDR; `values-de` with per-app language settings and a German smoke test; one shared grapheme helper with a test corpus for the recurring emoji-splitting bugs.
- **F-5 Toolchain** (BLD-2, BLD-3, L): AGP, Gradle, Kotlin with KSP2, Room, Hilt, WorkManager, Navigation, MockK and Robolectric; then a lint baseline, `abortOnError = true`, Compose lint rules and lint in CI. Coil 3 is its own step.
- **F-6 Directory privacy and account deletion** (SEC-3, SEC-16, UX-1 Last seen, M–L): hashed-number discovery through a Cloud Function; `users/` readable only by id or by contacts; Last seen honoured on the server; account deletion with server-side erasure.
- **F-7 Invite-link joining** (L-3, M): a deep link, a join screen, a join authorised by a Cloud Function, and the approval queue.
- **F-8 Encryption depth** (SEC-6, SEC-7 edits, SEC-8, L, max tier): safety numbers and a key-change notice (BACKLOG 3.4); encrypted edits; SQLCipher for the Signal database.
- **F-9 Sync and startup efficiency, measured first** (PERF-3, PERF-6, PERF-8, PERF-9, PERF-10, PERF-13, M–L): incremental sync on an `updatedAt` field; batched receipts; lifecycle-scoped ViewModel flows; startup work off the main thread; the OkHttp cache's scope; a regenerated baseline profile that covers the chat screen.
- **F-10 Emulator smoke test** (PROC-3, M–L): a CI job with a Firebase test phone number that launches, signs in, sends text and a photo, and rotates. It also unblocks baseline-profile generation.
- **F-11 Adaptive layouts** (UX-19, L): list-detail on tablets and foldables.

## 6. Target outline for CLAUDE.md (Step 36)

1. Overview — unchanged.
2. Gate and commands — one gate command and what CI runs; flavor and deploy commands move to their READMEs.
3. Cloud sessions — the symptoms of a session-start hook that didn't run, and that the checkout is shallow.
4. Branches, commits and PRs — D-14, chained `-m` commits, fixing `main` forward.
5. After each step — tests, the gate, review skills, commit, where learnings go.
6. Review tools — `/code-review` (correctness of one step), `/simplify` (quality, four reviewers) and the phase check (bugs between steps).
7. Model tiers — without "currently…"; the per-sub-agent model report rule moves to `step-prompt.md`.
8. Plans — the format lives in `docs/plans/README.md`, including a phase check before each code phase's checkpoint; never assume steps run in parallel; archive after the last step; handoffs.
9. Architecture — the Room migration rule stated once; presence reduced to a pointer to its KDoc.
10. Key conventions — one clause and a link each, including the cancellation and string-resource rules.
11. Before editing — which skill or GOTCHAS section to read per area; Grep the long docs.
12. Testing and change safety — including the device-only bug rule.
13. Changelog — three lines; the rest lives in the skill and the release script.
14. Writing docs — unchanged.
15. Sensitive files — unchanged.
