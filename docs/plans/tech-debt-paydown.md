# Tech debt — pay down what is due, and keep it from piling up

Status: **draft, 2026-10-08.** The §0 decisions are proposals. The owner signs them off, or
changes them, before the first run. Evidence for every number below is in
`docs/plans/tech-debt-audit-2026-10-08.md` (verified at `9247d4b`). Line numbers are from that
commit; every step re-verifies them before editing.

**Order: 1 → 2 → 3 → 4 → 5 → 6 → 7 ‖ 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15 ‖ 16 ‖ 17 → 18 → 19 ‖ 20**

Phase A (steps 1–7) installs the gates and touches almost no product code. It can run now, before
or between the in-flight plans. Phase B (8–16) pays what is due: the security, data-loss and
crash risks, the missed triggers and the live bugs. Step 16 is finished by hand in an interactive
session, because its rules tests need `npm` and the Firebase emulator, which the runner's
allowlist excludes on purpose. Phase C (17–20) is the deepening from the 2026-09-20 architecture
review whose triggers have fired. Each `‖` stops for the owner (§6).

## Why

`TECH_DEBT.md` works as a log and fails as a queue.

- **It only grows.** Since 2026-04-11, main's history shows 66 entries added and 9 closed. It held
  27 entries on 2026-09-07 and holds 57 today.
- **Triggers fire and nobody notices.** Each entry names when to act: "the next time X is touched",
  "before the next release". Nothing tells anyone when that happens. Fourteen entries' triggers
  have already fired with no action. The sticker fixes due "before the next release" missed three
  releases (v1.40.0 to v1.40.2). `MessageColumns` asked the next column change to drop it; two
  changes added columns through it instead. `ChatScreen.kt` passed its 1,800-line trigger in May
  and has 2,885 lines.
- **Fixed entries stay.** Seven entries describe problems that are already fixed, moot or
  misdiagnosed.
- **Gates are off or broken, silently.** Lint crashes and runs nowhere. The screenshot suite has
  never verified anything. The composable-parameter check misreads `->` and passes 17-parameter
  composables. The dex-register check that would have caught two chat-open crashes exists only as
  a recipe in `docs/GOTCHAS.md`. `MessageBubble` now sits at 251 of 255 registers.
- **Two hazards are systemic.** `resultOf`, the repository error wrapper with 84 callers, turns
  coroutine cancellation into a failure, and 75 raw catch-alls do the same. Every `AppDatabase`
  bump is destructive, so an update that bumps the schema drops unsent messages, stars and
  reminders. There have been six bumps since 2026-09-11.
- **The code grew 55 % in thirty days** (42.3k → 65.5k lines), mostly through plans that each
  parked their review leftovers in the register.

So this plan first makes debt visible at the moment it is due, then pays what is due.

## Do now, by hand (before the first run)

These need the owner: a deploy, the Firebase console, or a decision.

1. **Close the open users rule.** `firestore.rules:23–25` lets the users update rule pass a
   request with no signed-in user (`|| request.auth == null`). Nothing needs it: the presence mirror
   writes with the Admin SDK, which bypasses rules. Delete the clause and run
   `firebase deploy --only firestore:rules`. First check that the deployed rules match the repo.
2. **Export the Storage and Realtime Database rules** from the console into `storage.rules` and
   `database.rules.json`, and commit them (step 16 adds them to `firebase.json` and tests them).
3. **Sign off §0**, then change this file's status line to "approved".

## 0. Decisions (proposed 2026-10-08 — the owner signs them off before the first run)

| # | Question | Proposal |
|---|---|---|
| 1 | What belongs in `TECH_DEBT.md` | Code that works against its own design: a smell, a risk, a wrong behaviour, a missing test. A fix that adds a capability the product lacks is a feature, and moves to `docs/BACKLOG.md` (paging, image thumbnails, PocketBase parity, HD-edit resolution). A register that holds features can never be emptied. The audit's §A lists every move. |
| 2 | How an entry is tracked | One `**Tracking:**` line under each heading: id, added date, optional reviewed date, severity, trigger kinds, and the paths it watches (§2.1). |
| 3 | What reads the tracking lines | `scripts/debt.sh` (§2.2). CI fails on a malformed register or on a watched path that no longer exists. The post-step workflow, the runner's step prompt and the release procedure list the entries that are due (§2.3). The vendored `code-review` skill is not edited: `skills-lock.json` pulls it from mattpocock/skills. |
| 4 | What "due" obliges | A due entry is folded into the change when it is small and inside the change's files. Otherwise the change sets the entry's `reviewed` date and says in one sentence why it waits. A due entry is never left silent. The commit that fixes an entry deletes it. |
| 5 | Releases | The release procedure lists every `release`-triggered, `bug` and `security` entry, every stale entry, and the on-device checks older than 30 days. Each is fixed or re-dated by the owner before `scripts/cut-release.sh`. |
| 6 | Screenshot suite | Re-record the four `MessageBubbleScreenshotTest` images and verify them in CI. The owner looks at the four PNGs at checkpoint A. The alternative is to delete the suite and the Roborazzi plugin. |
| 7 | Gates | Lint with a baseline; a dex-register ceiling of 240 on every debug build; a Konsist composable-parameter rule; pinned Room schema hashes (§2.4). A gate that cannot run fails; it never skips. |
| 8 | Ratchets | Counts that may only go down live in `ArchitectureTest` with today's values (§2.5). Raising one is allowed in the commit that needs it, with the reason in its body. |
| 9 | `MessageColumns` (audit §A #7) | **Decline** and keep the shim. A column costs one interface line; the real per-column cost is the mapper chain (review #12). The alternative is a 36-file mechanical removal before video-calls step 2. |
| 10 | Migrations | From `AppDatabase` version 31 (and `SignalDatabase`'s current version) on, every bump ships a migration and its test. Destructive fallback stays only for older versions and for downgrades. |
| 11 | Cancellation in `resultOf` | A cancellation of the *calling* coroutine propagates. Any other `CancellationException` (an awaited job someone else cancelled, a `withTimeout`) stays a failure, as today (§2.6). |
| 12 | Dependencies | Dependabot opens one grouped Gradle pull request and one GitHub Actions pull request a month. AGP, Kotlin, KSP and the Compose compiler are excluded: they move in a toolchain plan once a quarter. The first one is written after checkpoint A, so it runs behind the new gates (it also replaces step 4's lint stop-gap). |
| 13 | Debt budget | When the register holds more than 40 open entries, or a `bug` or `security` entry is older than 30 days, the next plan the owner starts is a paydown plan, unless the owner re-dates those entries. `scripts/debt.sh release` prints the verdict. |
| 14 | Every feature plan ends with a leftovers step | A plan's last step fixes its review leftovers that are small and tracks the rest with watch paths. A plan's §1 lists the entries that watch its files (`scripts/debt.sh paths`) and decides each. |
| 15 | Review candidate #6 (fold the send managers) | Out of scope. It changes the slice-ownership pattern, which needs a grilling session first. It is recorded as audit B31. |

## 1. Current state (verified 2026-10-08 at `9247d4b`)

The audit file holds the evidence and a verdict for each of the 57 entries. In short:

- **Fourteen triggers fired with no action**: audit §A #4, #7, #8, #10, #11, #12, #14, #15, #18,
  #21, #26, #40, #47, #48.
- **Seven entries are fixed or moot**: #5, #13, #37, #38, #39, #42, #50. #50 is worse than stale:
  its proposed fix would crash the pocketbase flavor. #17 is merged into #4.
- **Security**: the users rule above; any chat participant may rewrite any chat field and any
  message; invite links are open to create and delete and joining looks refused; ICE candidates
  are readable by any signed-in user.
- **Hazards**: `resultOf` and 75 raw catch-alls swallow cancellation (159 sites in 30 files);
  destructive migrations; `MessageBubble` at 251 dex registers; three receive paths can decrypt
  the same message, which blocks turning E2E on by default.
- **Gates**: lint crashes (version skew between AGP 8.7.3 and the Compose BOM's lint checks; the
  build disables a check id that does not exist); screenshots never verified; the parameter check
  misparses; no dex check; Room exports no schema; `ask-simplify.sh` is documented as a hook but
  never registered; the runner's code-review tripwire skips `data/outbox` and `data/call`.
- **Housekeeping**: four finished plans not archived (`offline-outbox`, `call-audio-routes`,
  `send-addressing` and its brief); a handoff and a March mockup in the repo root; 126 on-device
  checks pending, the oldest from April.

### 1.1 In-flight plans

This plan and the two in-flight plans edit some of the same files. The constraints:

| In-flight step | Needs from this plan first | Why |
|---|---|---|
| video-calls 1 (`PeerSession`) | Phase A; step 8 recommended | Step 8 fixes `CallRepositoryImpl`'s catch-alls before step 1 adds callers. The video-calls text predates the 2026-10-08 call sweep: `audioSessionLock` is gone and its `ChatScreen` line numbers moved. `CallSession` (calls entry, bullet a) and dropping `CallAudioRouter`'s lock belong in its step 1. |
| video-calls 2 (call kind, a `messages` bump) | step 7; step 9 recommended | Its bump must ship a migration (§0 #10). Add the call push TTL there. `CallNotificationManager`'s baseline goes there. |
| video-calls 4 (video call screen) | step 10 | It relabels call bubbles, and `MessageBubble` is at 251 of 255 registers. Decide the OUTGOING label there (calls entry, bullet k). |
| video-calls 4a (dock) | — | Must not copy `FCMService`'s `CLEAR_TASK` (audit B22). |
| video-calls 7 (group call messages) | step 11 | Group call logs make the Calls tab's "call back" ring an arbitrary member until the partner is decided on `Chat`. |
| stickers 10 (Klipy) | step 12 | Both edit `MediaFileManager`, `MediaBackfillWorker` and the media send path. |
| stickers 11 (GIFs tab) | — | Step 20 (composer) runs after it. |

Never run this plan's steps that edit `MessageRepositoryImpl.kt` (8, 12, 19) in parallel with
each other or with stickers step 10. Step 3 writes these constraints into the two plans.

## 2. Design

### 2.1 The tracking line

Every entry carries one line directly under its heading, before **The smell.**:

```
**Tracking:** id chat-title; added 2026-09-08; severity risk; trigger touch; watch ui/chatlist/ChatListItem.kt ui/search/GlobalSearchLabels.kt
```

- Fields are separated by `;`, as in plan heading tags. Order is free.
- `id`: kebab-case, unique, stable. Failure messages and `ArchitectureTest` baselines cite it.
- `added`, and optional `reviewed`: ISO dates. `reviewed` resets the age clock and is set by
  whoever re-decides an entry that is due.
- `severity`: `bug` (wrong behaviour today), `security`, `risk` (latent data loss, crash, ANR,
  wrong result), `quality`.
- `trigger`: one or more of `touch` (any change to a watched path), `release` (every release until
  fixed), `event` (an outside signal named in **When to revisit**).
- `watch`: required for `touch`, recommended for the rest. Space-separated paths or globs, in
  backticks or bare. A path that starts with `ui/`, `data/`, `domain/`, `di/` or `navigation/`
  resolves under `app/src/{main,firebase,pocketbase}/java/com/firestream/chat/`. Any other path
  is repo-relative (`firestore.rules`, `functions/index.js`, `scripts/run-plan.sh`).
- `plan`: optional, `plan tech-debt-paydown#11`, when a plan step is scheduled to fix it.

### 2.2 `scripts/debt.sh`

A bash script in the style of `scripts/plan-runner/lib.sh`: pure functions plus a thin command
layer, tested by `scripts/debt/selfcheck.sh` against `scripts/debt/fixtures/`.

| Command | Output | Exit |
|---|---|---|
| `check` | `TECH_DEBT.md:<line>: <problem>` for a missing or second tracking line, a bad field, a duplicate id, a future date, or a watched path that matches no file | 1 on any problem |
| `touched <rev-range>` | `id · severity · title` of each entry whose watched paths meet `git diff --name-only <rev-range>` | 0 |
| `paths <path…>` | the same for the given paths (used when writing a plan) | 0 |
| `release` | `release`-triggered entries, every `bug` and `security` entry, and the §0 #13 budget verdict | 0 |
| `stale [days]` | entries whose latest of `added` and `reviewed` is older than *days* (default 180, the file's own six-month rule) | 0 |
| `stats` | counts by section, severity and trigger; the oldest entry; how many are scheduled in a plan | 0 |

`.github/workflows/debt-register.yml` runs `selfcheck.sh` and `debt.sh check` on every push and
pull request (no path filter, a few seconds), and prints `debt.sh touched` as `::notice::` lines on
a pull request. `stale` never fails CI: time alone must not turn a build red.

### 2.3 Where "due" is surfaced

| Moment | What runs | Who acts |
|---|---|---|
| Writing a plan | `debt.sh paths <files the plan will touch>`; the plan's §1 decides each entry | the planner |
| After each step's green gate | `debt.sh touched <base>..HEAD` (CLAUDE.md post-step workflow, runner step prompt) | the step session: fold in, or set `reviewed` and say why; the `**Shipped**` departures line names what it left |
| Every push and pull request | `debt.sh check`; `touched` as notices | CI |
| Before tagging a release | `debt.sh release`, `debt.sh stale`, pending on-device checks older than 30 days (`changelog-release` skill) | the owner |
| Last step of a feature plan | the plan's leftovers (§0 #14) | the step session |

### 2.4 Gates

| Gate | Where it runs | Threshold and baseline |
|---|---|---|
| Lint | CI (`:app:lintFirebaseDebug`) | `lint-baseline.xml` holds today's findings; `abortOnError = true`; new findings fail. |
| Dex registers | A Gradle task that finalizes every `assemble*Debug`, so the runner gate, CI and local builds all run it | No app method (`com/firestream/`) above 240 registers. Baseline file `app/dex-register-baseline.txt`: `MessageBubble 251`, removed by step 10. |
| Composable parameters | `ArchitectureTest` (Konsist), replacing the Gradle walker | At most 10 parameters. Baseline: the five composables over it today, each tied to an entry. |
| Screenshots | CI verifies (§0 #6) | The four re-recorded images. |
| Room schemas | `RoomSchemaTest` (JVM) in the unit suite | The exported schema of each database's current version has the identity hash pinned in the test. A changed entity without a version bump fails. |
| Debt register | CI (`debt.sh check`) | §2.2 |

### 2.5 Ratchets

All in `app/src/test/java/com/firestream/chat/architecture/ArchitectureTest.kt`. Each starts
green at today's value, fails when a count rises, and fails with "lower the ceiling to N" when it
drops below its ceiling, so the ceiling follows every improvement. Each failure message names the
register entry and the safe alternative.

| Ratchet | Today | Lowered by |
|---|---|---|
| File length: 800 lines unless listed | 8 listed files: `ChatScreen.kt` 2885, `MessageRepositoryImpl.kt` 1693, `MessageBubble.kt` 1575, `SettingsScreen.kt` 1276, `AdjustImageScreen.kt` 1113, `ImagePreviewScreen.kt` 1078, `ImageEditRasterizer.kt` 908, `ChatViewModel.kt` 820. `EmojiSearchData.kt` (static data) is exempt. | the steps that shrink a listed file (17–20) |
| Cancellation swallowers per package (`runCatching`, `resultOf`, catch-alls with no cancellation guard) | ≈227 by the audit's text heuristic | step 8 |
| `dagger.Lazy<…Repository>` injections | 5 (`MessageRepositoryImpl.kt:206–207`, `ListRepositoryImpl.kt:59–61`) | step 19 |
| `ui/` imports of Firebase, Room, WorkManager, OkHttp, WebRTC, libsignal, `data.local.entity` or `data.local.dao` | 1 (`SettingsViewModel`'s `androidx.work`) | step 12 |
| `CoroutineScope(` outside `di/` and Android services | 7 sites | step 13 |
| "Other participant" lookups outside `domain/model/Chat.kt` | added by step 11 at 0 | — |

The send rule also tightens: it matches `recipientId` and `recipientIds`, with the broadcast
fan-out allowlisted by name like the `SendTarget` rule (audit B26).

### 2.6 Cancellation

`resultOf` (`data/util/ResultExt.kt`) catches `Exception`. When the exception is a
`CancellationException`, it calls `currentCoroutineContext().ensureActive()`: if the calling
coroutine is cancelled, that rethrows and the cancellation propagates. Otherwise the exception
came from somewhere else (a deferred someone else cancelled, a `withTimeout` that fired) and stays
a `Result.failure`, as today. `Error`s still propagate. A type check alone is wrong: a waiter on a
shared download would rethrow the first caller's cancellation as its own (audit B8, example 7).
`rethrowIfCancellation()` in suspend code gets the same check. Code that is not suspending keeps
plain `runCatching`.

### 2.7 Migrations

Both databases export their schemas to `app/schemas/`, committed. `RoomSchemaTest` pins the
identity hash of each current version. `fallbackToDestructiveMigration()` becomes
`fallbackToDestructiveMigrationFrom(<every version below the first exported one>)` plus
`fallbackToDestructiveMigrationOnDowngrade()`. A migration test under Robolectric
(`androidx.room:room-testing`, `MigrationTestHelper`) runs every migration from the first exported
version to the current one. A bump therefore ships three things in one commit: the migration, the
new schema file and the new pinned hash.

## 3. Steps

### Step 1 — Clean the register (`docs(tech-debt):`) — skills: none; budget: 40

Apply the audit's §A and §B to `TECH_DEBT.md`. No tracking lines yet (step 2).

- **Delete** §A #5, #13, #17, #37, #38, #39, #42, #50. Fix the two comments that cite deleted
  entries: `.github/workflows/ci.yml:77–78` (#5) and `.claude/hooks/session-start.sh:34–36` (#50:
  the placeholder is needed because both flavors use Firebase, not because of a plugin quirk).
- **Rewrite** the entries marked rewrite, using the audit's evidence column. #4 becomes review
  #10's cut (media pick out; the rest stays one file) and absorbs #17. #27 maps each bullet to a
  step here or a video-calls step.
- **Move to BACKLOG** §A #2 (with B16) into "Performance & pagination (6.4)"; #20 with #49 into
  the image editor ideas; #30–35 as one item "PocketBase flavor parity"; #40 into "Rich Media".
  Each moved item keeps its reasoning in two or three sentences.
- **Decline** #3, #7 (if §0 #9 stands) and #36: move them under *Declined* with their reason.
- **Add** every §B entry that is still open. Skip B1 if the owner has closed the rule.
- For each entry a later step fixes, end **When to revisit** with
  "Scheduled: step N of `docs/plans/tech-debt-paydown.md`."

Done when every §A verdict and §B addition is applied. Documentation only: the gate is skipped.

### Step 2 — Every entry watches its files (`chore(tooling):`) — skills: none

- Add a tracking line (§2.1) to every entry. For §A entries, take `added`, `severity`, `trigger`
  and `watch` from the audit. For §B entries, `added` is the day step 1 added them, and `trigger`
  and `watch` come from their evidence column. Coin each `id`. Add `plan tech-debt-paydown#N` to
  scheduled entries.
- Write `scripts/debt.sh` (§2.2), `scripts/debt/selfcheck.sh` and its fixtures, and
  `.github/workflows/debt-register.yml`.
- Rewrite `TECH_DEBT.md`'s "How to use this file": the grammar, §0 #4, and that the fixing commit
  deletes the entry. Update CLAUDE.md's *Tech Debt* section to match.

Tests: the self-check covers parsing, each `check` failure, shorthand path resolution, globs,
`touched` on a fixture repository, `release`, `stale` and `stats`.

Done when `scripts/debt.sh check` passes on the real register and the workflow runs both checks.

### Step 3 — Due debt surfaces where work happens (`docs:`) — skills: none

- **CLAUDE.md**, post-step workflow: after the gate, run `scripts/debt.sh touched <base>..HEAD`
  and act on §0 #4. *Plan Execution Workflow*: §0 #14 (the leftovers step, `debt.sh paths` in a
  plan's §1). *Testing*: the first change to a class in audit B25 adds its first test.
- **`scripts/plan-runner/step-prompt.md`**, "Carrying insight forward": the same rule, and the
  `**Shipped**` departures line names each due entry left as it was.
- **`scripts/plan-runner/lib.sh`**: add `data/outbox` and `data/call` to `PR_TRIPWIRE_DIRS`, and
  update the self-check fixtures.
- **`.claude/skills/changelog-release/SKILL.md`**, *Cutting a release*: §0 #5 before
  `cut-release.sh`, next to the backlog sweep.
- **`docs/agents/issue-tracker.md`**: there is no issue tracker; specs are the plans in
  `docs/plans/`. The vendored `code-review` and `triage` skills read this file.
- **CLAUDE.md**, *Review tools*: say what is true of `ask-simplify.sh`. It is not registered in
  `.claude/settings.json`, and a user who wants the prompt registers it in their local settings.
- **Archive** `offline-outbox.md`, `call-audio-routes.md`, `send-addressing.md` and
  `send-addressing-brief.md` to `docs/plans/done/`, and the root `handoff-benchmark-readout.md`
  too. Delete the root `emoji_picker_mockup.html`. `image-editor.md` and `file-handling.md` stay
  until their on-device checks are done, as their status lines say.
- **`docs/plans/video-calls.md`** and **`docs/plans/stickers-and-gifs.md`**: one
  `**(tech-debt)**` line in each step that §1.1 names, stating the constraint.

Done when `scripts/plan-runner/selfcheck.sh` passes and every link to a moved plan still resolves.

### Step 4 — Lint runs and gates CI (`chore(build):`) — skills: none

- Make lint finish. First try `android.experimental.lint.version` in `gradle.properties` (a lint
  newer than AGP 8.7.3's). If that fails, disable the two crashing checks by their real ids,
  `RememberInComposition` and `NullSafeMutableLiveData`. Either way, delete the wrong id
  `NonNullableMutableLiveData` and leave a comment that the toolchain plan removes the stop-gap.
- `lint { baseline = file("lint-baseline.xml"); abortOnError = true }`; generate the baseline.
- `.github/workflows/ci.yml`: `./gradlew test assembleDebug :app:lintFirebaseDebug` in one run.
- `.github/dependabot.yml` per §0 #12.
- Delete the unused catalog entry `compose-ui-test` (`gradle/libs.versions.toml:79`).
- CLAUDE.md: the lint command is `./gradlew :app:lintFirebaseDebug`.

Done when lint completes, and a lint error introduced on purpose fails it locally (not committed).

### Step 5 — Compose crash gates (`chore(build):`) — skills: none

- **Dex registers** (§2.4): a Gradle task, `checkDexRegisters`, finalizes every `assemble*Debug`.
  It finds `dexdump` under the SDK's newest build-tools, reads every `classes*.dex` in the APK,
  and fails on any `com/firestream/` method above 240 registers that the baseline does not allow.
  It names the method, its count and the GOTCHAS entry. A missing `dexdump` fails the task. A
  whole APK takes about a second. Update the GOTCHAS entry: the check now runs on every debug
  build, and its "next highest" figures are the audit's.
- **Composable parameters**: replace `checkComposableParamCount` (`app/build.gradle.kts:297–392`)
  with a Konsist rule in `ArchitectureTest`, baselined per §2.4. Drop the comment that cites local
  memory.
- **Screenshots**: re-record the four baselines, make CI verify them, and fix the test KDoc's task
  names.

Tests: the Konsist rule; the register parser of the dex task against a small fixture dump.

Closes §A #21 and B10.

### Step 6 — Ratchets, and the dead code the audit found (`refactor:`) — skills: none

- Add the ratchets of §2.5 and the tightened send rule.
- Replace `MessageRepositoryImpl.kt`'s stale size ceiling (`:39–40`) with a pointer to the file
  ceiling list (B27).
- Delete the dead code of audit B21, including `OutboxScheduler.kt:110`'s dead arm and its test.

Closes B21, B26, B27 and the dead-code half of §A #3.

### Step 7 — Room schemas are exported and pinned; migrations from now on (`chore(data):`) — skills: code-review; model: strong

Implement §2.7 for `AppDatabase` (version 31) and `SignalDatabase`.

- `exportSchema = true` and the KSP argument `room.schemaLocation`. Watch the KSP workaround at
  `app/build.gradle.kts:280–289`. Commit the generated JSON.
- `RoomSchemaTest` with the pinned hashes. Its failure says what a bump owes.
- The destructive fallback narrowed per §2.7, and the migration test harness, which has nothing
  to run until step 9.
- CLAUDE.md (*Local-first*), PATTERNS "Room version bump rule" and `docs/SCHEMA-ROOM.md`: a bump
  ships a migration, its test, the new schema file and the new pinned hash.

Done when changing an entity without a bump fails the unit suite (checked locally, reverted).

### Step 8 — Cancellation stays cancellation (`fix(data):`) — skills: code-review, simplify; model: strong

- `resultOf` and `rethrowIfCancellation` per §2.6, with their KDoc rewritten.
- The 25 hand-written `try { Result.success(…) } catch (e: Exception)` repository methods use
  `resultOf` (`CallRepositoryImpl` 10, `UserRepositoryImpl` 7, `AuthRepositoryImpl` 4, poll 3,
  `ContactRepositoryImpl` 1), and so does `AppUpdate`'s `runCatching`.
- The raw catch-alls in suspending code that the ratchet counts, worst first:
  `ChatRepositoryImpl.kt:68`, `UserRepositoryImpl.kt:73`, `ContactRepositoryImpl.kt:65`,
  `ListRepositoryImpl.kt:110/181/197/421` (the last three also log what they swallow),
  `MediaBackfillWorker.kt:75`, and the PocketBase sources that turn cancellation into `null`
  (`PocketBaseChatSource.kt:100/115`, `PocketBaseUserSource.kt:51`, `UserRepositoryImpl.kt:179`).
- Lower the ratchet.

Tests: `resultOf` — a cancelled caller propagates; an awaited deferred cancelled elsewhere is a
failure; a `withTimeout` that fires is a failure; an `Exception` is a failure; an `Error`
propagates. Regression tests: a cancelled chat-list sync pass stops; a cancelled
`CallRepositoryImpl.getCallById` does not end the call as an error; a cancelled backfill stops.

Trap: a batch loop that kept running after its scope died (`ChatMessageSender.kt:111–118`,
`SharePickerViewModel.kt:195–210`) now stops. Check that nothing waits on a flag only the failure
branch cleared.

Closes B8 and §A #27 bullet b.

### Step 9 — The outbox's two facts get two columns: the first migrated bump (`fix(outbox):`) — skills: code-review; model: strong

- A one-way `outboxWritten` column answers "may an earlier write have landed?".
  `outboxAttempts` becomes the budget alone. A manual retry resets the budget and keeps
  `outboxWritten`. A run that is stopped, not failed, spends nothing. `MessageDao.requeueForRetry`
  loses its `MIN(attempts, 1)` trick.
- The next `AppDatabase` version (31 → 32 today) with a migration, its test and the pinned hash
  (§2.7).
- CHANGELOG *Fixed*: unsent messages survive an app update that changes the database.

Tests: the migration keeps a queued row with its outbox columns; a retried message gets the full
budget; a stopped run spends nothing; the create-if-absent write follows `outboxWritten`.

Closes §A #8 and B7.

### Step 10 — MessageBubble gets headroom under the register ceiling (`refactor(chat):`) — skills: app-ui-design

Split `MessageBubble`'s body, as the 2026-09-10 split did, until it measures 220 registers or
fewer, and remove its baseline line. The screenshot verification proves the pixels did not move.
Run before video-calls step 4 and stickers step 11.

Closes B9.

### Step 11 — "Who is the partner" and "what is this chat called" move onto `Chat` (`refactor(chat):`) — skills: code-review; model: strong

Review candidate #2. Run before video-calls step 7.

- `domain/model/Chat.kt` gains `partnerId(currentUserId): String?`, meaning the one other
  participant of an `INDIVIDUAL` chat, else `null`. It also gains `title(resolve)`, with one
  precedence: for `INDIVIDUAL`, the partner's resolved name, then a non-blank chat name, then
  "Chat"; for groups and broadcasts, a non-blank chat name, then "Chat". A blank name counts as
  missing.
- Every site in audit §A #18 and #41 calls them, `BootRestoreLogic` included. The step's
  `**Approach**` lists each site and what it means to get. `ui/components/ChatTargets.kt` keeps
  only what is Compose-specific.
- The "other participant" ratchet starts at 0.

Tests: one table on `Chat` (group, self-chat, unsynced participants, blank name); the blank-name
case in `ChatListItemUiTest`; a group entry in `CallsViewModel` offers no "call back".

Closes §A #18 and #41.

### Step 12 — Media download decides once (`fix(media):`) — skills: code-review; model: strong

Review candidate #9. Run before stickers step 10.

- One owner of "make this row's media local", beside `MediaFileManager`'s file work: the
  preference gate, one Wi-Fi check, the download, the `localUri` write, the hand-off to the retry
  run. The repository's receive path and `MediaBackfillWorker` call it per row. Stickers follow
  one rule in both.
- The three request builders move onto `MediaBackfillScheduler` (`schedulePeriodic()`,
  `runNow()`), injected into `SettingsViewModel`. `MediaBackfillWorker` leaves the UI allowlist,
  and the `ui/` import ratchet drops to 0.
- `MediaFileManager` and `ProfileImageManager` use `SingleFlight`, with the waiter-cancellation
  check `docs/GOTCHAS.md` requires.
- An avatar downloads to a temporary file and is renamed into place.
- A filter chip's backfill fetches only the kinds that chip shows.

Tests: one decision table (preference × network × kind); a cancelled waiter does not cancel the
other waiters; an interrupted avatar download leaves no file; the Photos chip fetches images only.

Closes §A #16, #47, #48, B15 and B17.

### Step 13 — List sync: an injected scope, one lock per list, and sign-out (`fix(lists):`) — skills: code-review; model: strong

- `ListRepositoryImpl` gets its background scope injected instead of building one
  (`ListRepositoryImpl.kt:64`). Tests pin it, which makes `ListRepositoryImplRaceTest`
  deterministic.
- The per-list locks move onto `KeyedMutex`. `ListRepositoryMutexTripwireTest` uses
  `KeyedMutex.isTracked` instead of reflecting into a private map.
- `fetchAndCacheList` and `getSharedListsForChat` take the per-list lock, and the tripwire covers
  them.
- The sync loop follows the signed-in user across sign-out and sign-in.
- Lower the `CoroutineScope(` ratchet.

Tests: the race test passes twenty runs in a row; the tripwire fails with either new lock
removed; a second account's lists sync after sign-in.

Closes §A #57, the list half of #9, B13 and B14.

### Step 14 — Stickers: the overdue release fixes (`fix(stickers):`) — skills: code-review; model: strong

The register's sticker leftovers (audit §A #26), due since v1.40.0:

- `StickerMaker` waits for the segmentation model outside the `MediaProcessingLimiter` permit.
- `StickerLibrarySync` restarts its listener after an error, with a backoff.
- `saveSticker` converts outside `importLock`, as its comment says.
- An installed pack records the format of the file's bytes, not the manifest's claim.
- `LottieThumbnails` decodes under the limiter with a time bound, if that is small. Otherwise the
  step rewrites the entry for this item alone.

Tests: one regression test per item. Check stickers step 10's files first; never interleave the
two.

### Step 15 — Small overdue fixes in the chat screen, dictation and commands (`fix(chat):`) — skills: app-ui-design

- A failed timer offers no retry, and the retry KDoc lists the real types (§A #44).
- The gallery opens the system photo picker without asking for media permission (B18).
- Your own queued voice note plays from its local file (B19). `VoiceMessagePlayer` prepares
  asynchronously, with a loading state and an error listener (§A #23).
- Robolectric tests for `SpeechRecognizerManager`'s teardown (§A #12). If `ShadowSpeechRecognizer`
  cannot express the second case, record that in the entry and drop it.
- The command widget slot leaves `domain/command/ChatCommand.kt`, `.remind` stops downcasting,
  and the domain baseline in `ArchitectureTest` is deleted (§A #10, B23).

Tests: one per item. CHANGELOG *Fixed* for the user-visible ones.

### Step 16 — Firestore rules get tests, then get tightened (`fix(rules):`) — skills: code-review; model: max; budget: 45

Finished by hand in an interactive session on the strongest tier. The runner stops at checkpoint
B1 before it. A step the runner never launched passes the next checkpoint when the runner starts
again. A runner session that reaches this step stops at once with `blocked` / `environment`: the
tests need `npm` and the emulator, and the runner's allowlist admits no interpreter
(`scripts/run-plan.sh`, `ALLOWED_TOOLS`).

- A rules test harness: `@firebase/rules-unit-testing` against the Firestore emulator, under
  `functions/`, with `npm test`. A new workflow runs it when `firestore.rules`, `storage.rules`,
  `database.rules.json` or `functions/` change (`ci.yml` ignores `functions/`).
- Inventory every client write to `users`, `chats`, `messages`, `inviteLinks` and `calls` from
  the Firestore sources, and encode each as an allowed case first.
- Then tighten. Users: owner only. Chats: a participant may change only the fields a member
  writes; only admins change `participants` and `admins`. Messages: `senderId` must be the
  writer; only the sender changes content, type and `senderId`; other participants change only
  the fields they write today (reactions, receipts, poll votes, list diffs, timer state), via
  `affectedKeys().hasOnly(…)`. Invite links: only admins create and delete them, and a valid link
  lets a non-participant add exactly themselves. Call candidates: only the call's two parties.
- `functions/index.js`: unread counts rise after the block check (B5).
- Add `storage.rules` and `database.rules.json` to `firebase.json` if the owner committed them,
  with a test for the sticker objects' create-only rule.
- Every tightened rule has a test that fails on the old rule.

The deploy is the owner's, at checkpoint B2 (§6).

Closes B1 (if still open), B2, B3, B5, B6 and §A #27 bullet d's client half.

### Step 17 — Media picking leaves ChatScreen (`refactor(chat):`) — skills: app-ui-design, code-review; model: strong

Review candidate #10.

- One media-pick module owns the nine launchers, the permission chains, the camera and video
  URIs, the attachment sheet, `MAX_GALLERY_PICK` and mime sniffing. Its one output is a
  `List<PendingMedia>` or a document `Uri`. `rememberImagePicker` becomes its single-image case
  for Profile and Group Settings.
- One mime helper in `data/util` normalises at the top of the send path, with tests for every
  producer (§A #14).
- Lower `ChatScreen.kt`'s ceiling.

Tests: a Robolectric test of the module's contract (a pick gives a batch; the cap holds;
documents give a `Uri`).

Closes §A #4 and #14.

### Step 18 — One zoomable pager (`refactor(chat):`) — skills: app-ui-design; model: strong

Review candidate #3. One `ZoomablePager(pageCount, onEmpty, content)` owns the pager state, the
per-page crop (one ownership model), zoom locking the scroll, empty dismissing, back, hiding the
keyboard and its own snackbar host. `FullscreenImageViewer`, the search gallery and
`ImagePreviewScreen` call it. `ZoomableBox` and `ZoomCropSurface` stay as they are.

Tests: a Robolectric pager test (zoom locks paging; a page change resets the crop; an empty list
dismisses).

Closes §A #15.

### Step 19 — One reconcile, no cycle-breakers (`refactor(data):`) — skills: code-review, simplify; model: max; budget: 45

Review candidate #7.

- `syncChatMessages` calls the one reconcile body per message, which gives it the auto-download,
  list hook and pending-echo guard it lacks. The `NonCancellable` decrypt→upsert stays one block.
- A message is decrypted once across the three receive paths (B12).
- Remove the three `dagger.Lazy` that break no cycle. Break the Message↔List cycle: the list
  repository learns about shared lists from its own rows, and the share bubble goes through
  `SendListUpdateToChatsUseCase`. Lower the `Lazy` ratchet.

Tests: one reconcile suite for all three paths; two paths racing on one message decrypt it once;
the existing push and sync decrypt tests stay green. The on-device check is an E2E release build
on two phones (§6).

Closes §A #1 and B12.

### Step 20 — One composer text module (`refactor(chat):`) — skills: app-ui-design; model: strong

Review candidate #4. Runs after stickers step 11.

A holder owns text, cursor, composition, emoji sizes and the dictation anchor, and nulls the
composition on every programmatic write. The chat input and `CaptionBar` are its two adapters.
`ComposerValue`, `applyDictationCommit` and `adjustEmojiIndices` become its internals. If the
view-model slice `ComposerState` still holds no text, rename it, so "the composer's state" means
one thing.

Tests: sequences (an IME edit, then an emoji, then dictation, then send); `adjustEmojiIndices`
covered for the first time.

Closes B28.

## 4. Keeping it down

What this plan leaves in place, against each way debt grew:

| How it grew | What stops it now |
|---|---|
| Triggers fired unnoticed | Watched paths, surfaced after every step, on every pull request and at every release (§2.3) |
| Fixed entries stayed | `debt.sh check` fails when a watched path disappears; the fixing commit deletes the entry |
| Features filed as debt | §0 #1; they live in BACKLOG |
| A release trigger missed three releases | The release procedure lists every release-triggered entry |
| Gates switched off or broken without anyone noticing | Every gate runs in CI or in the unit suite; a gate that cannot run fails |
| Gotchas known only as prose | The dex ceiling and the Room bump rule are gates. New GOTCHAS entries answer "can this be a check?" |
| Hot files grow without a decision | File ceilings |
| A systemic hazard spreads by copy-paste | The cancellation ratchet and a correct `resultOf` |
| Plans end with a "leftovers" bundle | The leftovers step (§0 #14) |
| The runner's tripwire was narrower than the policy | `data/outbox` and `data/call` added |
| The toolchain drifted for ten months | Dependabot monthly; a toolchain plan each quarter |
| Risky classes stayed untested | The first change to one adds its first test |
| On-device checks piled up | The release lists the ones older than 30 days |

Two cadences keep it honest without new tooling. Each quarter, the owner runs the
`improve-codebase-architecture` skill (the 2026-09-20 review was its last run) and writes the
toolchain plan. At each release, the budget verdict of §0 #13 decides whether the next plan pays
down debt.

## 5. Not in this plan

- **The toolchain upgrade** (AGP, Kotlin, KSP2, Room, Hilt, compileSdk 36, which also unblocks
  Media3 1.10): its own plan after checkpoint A.
- **Calls**: `CallSession`, the redundant `CallAudioRouter` lock, the push TTL, `CallState.Placing`
  and the labels go to video-calls steps 1, 2 and 4 (step 3 annotates them). The `Connecting`
  timeout, the refused service start and the ring-timeout race stay in the register, unplanned.
- **Review candidates #5, #6, #8 and #12**: recorded as audit B29, B31, B30 and §A #7.
- **A Kotlin sync harness on the emulator** (§A #6): step 16's harness covers rules, not
  repositories.
- **Entries kept with an event trigger**: the `getChats()` local accessor, link-preview
  persistence, the unknown chat type, the broadcast fan-out, the sticker file sweep, backup limits,
  pack ids and object hashes, the overlay `Path` cache, the file-handling and plan-runner
  leftovers, and the PocketBase crash on unimplemented paths.

## 6. Checkpoints and the owner's part

- **Before step 1**: "Do now, by hand".
- **Checkpoint A (after step 7)**: look at the four re-recorded screenshots; read the lint
  baseline's size and the ratchet numbers; merge the branch so the in-flight plans run behind the
  gates; write the toolchain plan.
- **Checkpoint B1 (after step 15)**: a device pass on steps 8–15 (an app update over a queued
  message; a group chat's title; voice notes; the gallery; sticker making; lists after sign-out).
  Then run step 16 in an interactive session.
- **Checkpoint B2 (after step 16)**: the rules pass on a device against the emulator or a test
  project: send, react, receipts, polls, lists, timers, group admin actions, joining by invite
  link, a call. Then deploy the rules and functions, and restart the runner.
- **Checkpoint C (after step 19)**: an E2E release build on two phones (step 19); the picker,
  camera and attachment sheet (step 17); zoom and paging in the viewer and the preview (step 18).
- Before step 20, check that stickers step 11 has shipped.

## Run

```bash
scripts/run-plan.sh docs/plans/tech-debt-paydown.md --dry-run
scripts/run-plan.sh docs/plans/tech-debt-paydown.md --to 7
```

`--to 7` runs Phase A and stops at checkpoint A. The runner's allowlist already admits
`scripts/debt.sh` and `scripts/debt/selfcheck.sh`, which steps 2 and 3 create and every later
step runs. The driver reads its allowlist from the main checkout, so merge this plan's commit to
main before the first run.
