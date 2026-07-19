# Message Snooze / Reminders

## Context

Long-pressing a chat bubble gains a **Snooze** action: pick a time (chat-style presets or custom date/time), and at that time a local notification fires with the sender + message preview. Tapping it opens the chat, scrolls to that message, and highlights it ~1.5 s. User-confirmed scope: bell icon on snoozed bubbles + "Cancel reminder" menu swap, notification action buttons ("+1 hour", "Done"), a "Scheduled reminders" overview screen, (bonus) tap-to-scroll for regular FCM message notifications, and — **as final, separately testable steps** — TextClassifier smart-time detection and a `.remind` dot-command. Reminders are **local-only** (like the star feature), survive reboot, and live entirely in `app/src/main/` — no flavor code, no Firestore.

Nearly everything clones existing machinery: the `.timer` pipeline (`data/timer/`: `TimerAlarmScheduler`, `TimerAlarmReceiver`, `TimerNotificationChannel`, `BootCompletedReceiver`+`BootRestoreLogic`), the star feature (local-only Room flag + `ui/starred/` overview screen), the command framework (`domain/command/ChatCommand`, `ui/chat/command/TimerCommand`, `di/CommandModule` `@IntoSet`), and `ChatScreen`'s existing `jumpToSourceMessage(id)` scroll+highlight (lines ~308–337). Manifest already has `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`.

> On execution start: copy this plan into repo `.claude/plans/` and commit (CLAUDE.md convention — home-dir plans are invisible to cloud sessions).

## Execution model (user-requested)

- **One sub-agent per step** (general-purpose `Agent`), launched by the orchestrating session with the per-step `model` listed below (tiers per CLAUDE.md: strongest = Fable/Opus, mid = Sonnet). Report each agent's model at launch and in the results summary.
- Each sub-agent implements its step and runs `./gradlew test` + `./gradlew assembleDebug` before reporting. The orchestrator then reviews the diff, runs `/simplify` where triggered (steps 2, 4, 5, 9 — trigger (c) cross-cutting / (a) concurrency), and makes **one commit per step** so each feature increment is user-testable in isolation.
- Steps joined with `+` in the Order line may run as parallel sub-agents (disjoint files); everything else is strictly sequential.

## Key design decisions (settled)

- **Picker**: `ModalBottomSheet` (`SnoozePickerSheet.kt`); visibility is screen-local `remember { mutableStateOf<Message?>(null) }` in `ChatScreen`, mirroring `reactionTargetMessage`. Presets: *In 1 hour · This evening 18:00 · Tomorrow morning 09:00 · Pick date & time* (Material3 DatePicker+TimePicker); the *Detected time* preset is added later in Step 7. Past presets roll to next day.
- **Storage**: standalone Room table `reminders` (PK `messageId`, one pending reminder per message) with **snapshot columns** (message text, sender name, chatId, recipientId, fireAtMs, createdAtMs) so the notification renders even if the message is deleted. `AppDatabase` version **22 → 23** (destructive-migration convention, no named migration for additive table).
- **Scheduling**: clone `TimerAlarmScheduler` → `ReminderAlarmScheduler` (exact alarm + `INEXACT_FALLBACK` reusing the existing `ExactAlarmBanner` flow). One `ReminderAlarmReceiver` handles three actions: `FIRED` (post notification, delete row via `goAsync()` + `@ApplicationScope`), `SNOOZE_1H` (reschedule now+1h, update row), `DONE` (cancel + delete). Receiver must not assume process state.
- **Notification**: new channel `message_reminders` — `IMPORTANCE_HIGH`, **standard notification sound** (`USAGE_NOTIFICATION`), `CATEGORY_REMINDER`, no full-screen intent (deliberately not alarm-grade like timer). Tag `"message_reminder"` + id `messageId.hashCode()` — tag disjoint from timer's `"timer_alarm"`, so no collision.
- **Deep link**: new `MainActivity.EXTRA_MESSAGE_ID` + CHAT route query param `targetMessageId` (extend `Routes.chat(...)` helper — never hand-build routes). `ChatViewModel` reads it from `SavedStateHandle`; `ChatScreen` consumes it exactly once (`rememberSaveable` consumed-flag), calls existing `jumpToSourceMessage`, suppresses the `PersistedScrollState` restore when a target is present, and falls back to a snackbar via `withTimeoutOrNull(3000)` if the id never appears (deleted message / remote lag). Jump-to-old-message is low-risk: `MessageDao.getMessagesByChatId` is unbounded (whole chat loads from Room).
- **Fired-while-viewing-chat**: still post the notification (simplest; user never silently loses a reminder). **Timezone changes**: `fireAtMs` is absolute epoch; no re-arm in V1 (documented). **Reboot**: re-arm future reminders; overdue ones post immediately with "(overdue)" prefix.
- **Smart detection (Step 7)**: `interface DateTimeDetector` in `domain/reminder/` (pure); `AndroidDateTimeDetector` in `data/reminder/` via `TextClassificationManager`/`generateLinks` filtered to date-time entities, on `Dispatchers.Default`, `runCatching → null` no-op fallback. Faked in tests.
- **`.remind` command (Step 8)**: clone the `TimerCommand`/`TimerSetCommand` shape — `RemindCommand` registered via `@Binds @IntoSet` in `di/CommandModule.kt`, mounting a `RemindWidget` that reuses the snooze presets. **Target message = the current reply-target if one is selected, else the newest message in the chat.** Selecting a preset creates the reminder exactly like the context-menu path (`ChatMessageActions.snoozeMessage`); no new payload send needed unless the framework requires a `CommandPayload` (follow whatever `TimerSetCommand` does — if it must send a payload, add `CommandPayload.Remind` and dispatch in `ChatViewModel` to `snoozeMessage` locally without sending a message).
- **Bell-icon state**: `MessagesState` gains `pendingReminderIds: Set<String>`; the manager that owns `MessagesState` (`ChatMessageLoader`) combines `ReminderDao.observeMessageIdsForChat(chatId)` into its message flow — single `_uiState.update {}` (slice-ownership pattern).

## Steps

### Step 1 — Data foundation — **Sonnet** *(infrastructure commit, not user-testable)*
**Create:** `domain/model/Reminder.kt` (id, messageId, chatId, recipientId, fireAtMs, messageSnapshot, senderNameSnapshot, createdAtMs); `domain/repository/ReminderRepository.kt` (`observePending`, `observePendingIdsForChat`, `getPending`, `schedule`, `cancel`, `reschedule`); `data/local/entity/ReminderEntity.kt`; `data/local/dao/ReminderDao.kt` (`observeAll` ordered by fireAtMs, `observeMessageIdsForChat`, `getAll`, `upsert`, `deleteByMessageId`, `updateFireAt`); `data/reminder/ReminderRepositoryImpl.kt` (entity↔domain mapping; injects DAO + scheduler stub-interface so schedule/cancel/reschedule arm the alarm and write Room together; `// region: AGENT-NOTE` header).
**Modify:** `data/local/AppDatabase.kt` (**version 22→23**, entity + `reminderDao()`); `di/DatabaseModule.kt` (provide DAO); the module binding `MessageRepository` (add `@Binds ReminderRepository`).
**Tests:** none (simple CRUD/pass-through).

### Step 2 — Alarm + notification + boot pipeline (clone `data/timer/`) — **Opus** *(concurrency/receiver correctness; infrastructure commit)*
**Create in `data/reminder/`:** `ReminderAlarmScheduler.kt`, `ReminderNotificationChannel.kt`, `ReminderAlarmReceiver.kt` (3 actions as decided above; content `PendingIntent` → `MainActivity` with `EXTRA_CHAT_ID`/`EXTRA_SENDER_ID`/`EXTRA_MESSAGE_ID`; action buttons via `PendingIntent.getBroadcast` back to itself), `ReminderActionLogic.kt` (pure +1 h math), `ReminderBootRestoreLogic.kt` (pure: `Schedule` future vs `PostOverdue` past).
**Modify:** `data/timer/BootCompletedReceiver.kt` (extend the existing boot handler — avoids a second exported boot receiver); `FireStreamApp.kt` (~line 63: `ReminderNotificationChannel.ensureCreated`); `AndroidManifest.xml` (register `ReminderAlarmReceiver`, `exported="false"`, after the timer receiver ~line 114).
**Tests:** `ReminderBootRestoreLogicTest.kt`, `ReminderActionLogicTest.kt`.
**Gate extras:** `/simplify` (trigger a+c).

### Step 3 — Preset computation (pure logic) — **Sonnet** *(parallel with Step 2; infrastructure commit)*
**Create:** `domain/reminder/SnoozePreset.kt` (Kind: IN_1_HOUR/THIS_EVENING/TOMORROW_MORNING/CUSTOM — plus DETECTED variant now, used from Step 7); `domain/reminder/SnoozePresets.kt` (pure `compute(nowMs, zoneId, detectedFireAtMs? = null)`; roll past presets to next day; drop past detected).
**Tests:** `SnoozePresetsTest.kt` (fixed clock: evening today vs rolled, detected prepend, detected-past dropped).

### Step 4 — Deep-link plumbing + FCM bonus — **Opus** *(cross-cutting nav/architecture, risk R1)* — **user-testable: FCM notification tap scrolls to the message**
**Modify:**
- `navigation/NavGraph.kt`: `Routes.CHAT` += `&targetMessageId={targetMessageId}` (line ~108); `Routes.chat(...)` helper gains `targetMessageId: String? = null`; CHAT composable gains nullable `navArgument`; `FireStreamNavGraph` gains `initialTargetMessageId` threaded into the pending-action → `Routes.chat(...)` call sites (~336–340).
- `MainActivity.kt`: `EXTRA_MESSAGE_ID = "messageId"`; read in `onCreate`; **add `onNewIntent` override** (`setIntent` + re-drive the deep link via state observed by `FireStreamNavGraph`) — see risk R1.
- `ui/chat/ChatViewModel.kt`: `targetMessageId` from `SavedStateHandle`.
- `ui/chat/ChatScreen.kt`: consumed-flag `LaunchedEffect` → `jumpToSourceMessage`; suppress persisted-scroll restore when target present (extend the initial-scroll `when`, ~383–400).
- `data/remote/fcm/FCMService.kt`: forward the already-parsed `messageId` (~line 72) into the notification intent (~218–226).
**Tests:** none (navigation wiring; verified end-to-end).
**Gate extras:** `/simplify` (trigger c).

### Step 5 — Chat UI: menu, bell, picker sheet — **Sonnet** (invoke `app-ui-design` skill for the sheet) — **user-testable: full snooze flow end-to-end**
**Modify:**
- `ui/chat/MessageBubble.kt`: add `onSnooze`/`onCancelReminder` to **`MessageBubbleCallbacks`** and `hasReminder` to **`MessageBubbleState`** (holder fields only — **never** a new top-level `MessageBubble` param, risk R2); DropdownMenu button Snooze ⇄ Cancel reminder (mirror the `onCancelTimer` conditional, ~998); small bell `Icon` on bubble when `hasReminder`.
- `ui/chat/ChatMessagesState.kt`: `pendingReminderIds: Set<String> = emptySet()`.
- `ChatMessageLoader` (owns `MessagesState`): combine reminder-ids flow, single `.update {}`.
- `ui/chat/ChatMessageActions.kt`: `snoozeMessage(message, fireAtMs)` (build `Reminder` with snapshots; `AppError.from(e)` on failure), `cancelReminder(messageId)`; inject `ReminderRepository`.
- `ui/chat/ChatViewModel.kt`: pass-throughs.
- `ui/chat/ChatScreen.kt`: `snoozeTargetMessage` remember state; wire callbacks/state from `pendingReminderIds`; host the sheet.
**Create:** `ui/chat/SnoozePickerSheet.kt` — presets from `SnoozePresets.compute` + Material3 date/time pickers (no detection yet).
**Tests:** pass-throughs → skip.
**Gate extras:** `/simplify` (trigger c).

### Step 6 — Scheduled reminders overview screen — **Sonnet** — **user-testable**
**Create (clone `ui/starred/`):** `ui/reminders/ScheduledRemindersViewModel.kt` (observe `observePending()`, `cancel()`), `ui/reminders/ScheduledRemindersScreen.kt` (fire-time overline + snapshot preview; swipe-to-cancel; tap → chat with `targetMessageId`).
**Modify:** `ui/settings/SettingsScreen.kt` (row below "Starred Messages", ~line 222); `navigation/NavGraph.kt` (`Routes.SCHEDULED_REMINDERS` + composable + wiring).
**Tests:** pass-through VM → skip (matches Starred).

### Step 7 — Smart time detection — **Sonnet** — **user-testable: "tomorrow at 5pm" message offers a Detected preset**
**Create:** `domain/reminder/DateTimeDetector.kt` (pure `suspend fun detect(text, nowMs): Long?`); `data/reminder/AndroidDateTimeDetector.kt` (`TextClassificationManager`/`generateLinks`, date-time entities only, `Dispatchers.Default`, `runCatching → null`).
**Modify:** DI module (`@Binds DateTimeDetector`); `ui/chat/ChatMessageActions.kt` (+ `suspend detectSnoozeTime(text)`); `ChatViewModel` pass-through; `SnoozePickerSheet.kt` (on open, launch detection and prepend "Detected: …" preset when non-null — `SnoozePresets.compute` already accepts `detectedFireAtMs`).
**Tests:** detection orchestration with a `FakeDateTimeDetector` if non-trivial; the Android impl itself is untestable in unit tests (graceful-null contract documented).

### Step 8 — `.remind` dot-command — **Opus** *(command-framework cross-cutting)* — **user-testable**
**Create:** `ui/chat/command/RemindCommand.kt` (clone `TimerCommand`/`TimerSetCommand` shape; id `"remind"`; mounts widget); `ui/chat/widget/RemindWidget*.kt` (reuses `SnoozePresets` + detected time of the target message; visual style per existing `TimerPickerWidget`).
**Modify:** `di/CommandModule.kt` (`@Binds @IntoSet`); `ChatViewModel`/`ChatCommandsManager` only as the framework requires (target resolution: reply-target if selected, else newest message; selecting a time calls `snoozeMessage` — local action, no message sent; only add a `CommandPayload.Remind` if the framework structurally requires a payload).
**Tests:** target-resolution logic (reply-target vs newest) as a pure/VM test if extracted.
**Gate extras:** `/simplify` (trigger c).

### Step 9 — Docs + closeout — **orchestrator handles directly** (no sub-agent)
`CHANGELOG.md` (feat → **minor bump**, `## [UNRELEASED] [X.Y.Z] — <date>` header, Added entry + commit hashes grouped); `docs/FEATURE-MAP.md` (new "Message reminders" section + touched cross-cutting files, refresh `last-verified`); MEMORY.md update.
*(Note: CHANGELOG must be updated per push given `.github/workflows/changelog-check.yml` — if steps are pushed individually, each step's commit needs the CHANGELOG entry appended incrementally rather than deferred to Step 9; decide at execution time based on whether pushes happen per-step or at the end.)*

## Order

`Order: 1 → 2+3 → 4 → 5 → 6 → 7 → 8 → 9`

2 and 3 only depend on Step 1 and touch disjoint files (parallel sub-agents). 4 and 5 both edit NavGraph/ChatScreen/ChatViewModel → strictly sequential; 5 consumes 4's plumbing; 6 consumes 4's route param; 7 consumes 5's sheet; 8 consumes 3/5/7.

## Process gates (each step, per CLAUDE.md)

Sub-agent runs `./gradlew test` → `./gradlew assembleDebug`; orchestrator reviews diff → `/simplify` **only at steps 2, 4, 5, 8** via `Skill(skill: "simplify")` → **one `git commit` per step** (user-testable increments) → listed tests written in-step → `./gradlew test` → commit → MEMORY.md at closeout.

Konsist must stay green: `domain/reminder/` has zero `android.*` imports; `data/reminder/` never imports `ui/`.

## Risks

- **R1 — deep-link delivery**: `MainActivity` is `standard` launchMode (the manifest's `singleTask` at line 86 is `CallActivity`), no `onNewIntent` today — a notification tap while the app is foregrounded is silently dropped without the Step 4 override. Verify cold-start, warm-foreground, and background cases.
- **R2 — ART VerifyError ceiling**: `MessageBubble` params are capped at 10 (documented, docs/GOTCHAS.md); all additions go inside the existing holder data classes. Verify chat actually opens on device/emulator.
- **R3 — notification id collision**: solved by the dedicated `"message_reminder"` tag; note in receiver KDoc.
- **Room bump 22→23** or first launch crashes on the identity-hash check.

## Verification (per user-testable step, and full pass at the end)

Run `/verify` on device/emulator (mind `ANDROID_SERIAL` if phone+emulator attached):
1. *(after Step 4)* Regular FCM message notification tap → chat opens, scrolls to and highlights that message; also works while app is foregrounded in another chat (R1).
2. *(after Step 5)* Long-press → Snooze → "In 1 hour" shows bell icon; menu flips to "Cancel reminder". Short custom time (~1 min) → background the app → notification shows sender + text; tap → scroll + highlight ~1.5 s. "+1 hour" and "Done" actions behave. Reboot with a pending reminder → re-armed / overdue posts.
3. *(after Step 6)* Settings → Scheduled reminders → item tap jumps to message; swipe cancels.
4. *(after Step 7)* Message containing "tomorrow at 5pm" → picker shows a Detected preset.
5. *(after Step 8)* `.remind` in composer (with and without a reply-target selected) → widget shows presets → selection creates the reminder (bell appears).
