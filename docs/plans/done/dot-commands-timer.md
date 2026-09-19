# `.command` Framework + Timer (first command)

## Goal

Introduce a chat-composer command grammar triggered by a leading `.` (e.g. `.timer.set`), and ship the **timer** as the first command. Timer is the proof-of-fit for the framework — building both together ensures the abstraction is shaped by a real command, not a hypothetical one.

User-visible flow:
1. In a chat composer, type `.` at message start → vertical palette appears above the composer with available commands (`.timer`, `.torch`, …).
2. Tap `.timer` → palette becomes the timer's subcommand list (`set`, `send`).
3. Tap `.set` → an hh:mm:ss wheel-picker widget mounts above the composer. User dials a duration and optionally types a caption. Send.
4. Both devices schedule an exact-fire `AlarmManager` job against the server-stamped fire time. When it fires, an alarm-style notification rings using the system default alarm sound.
5. The TIMER message bubble shows a live countdown that flips to COMPLETED when the alarm fires, or CANCELLED if the sender cancels it.

## Locked-in design decisions (from refinement conversation)

- **Own-the-timer (Option A).** We do **not** integrate with the user's clock app. There is no public Android API to read other apps' system timers; we manage the timer ourselves and *mimic* system-alarm look/feel (full-screen intent, default alarm sound, alarm vibration). `.timer.send` therefore means "send the most recent timer I started inside FireStream" — but `.timer.send` is **deferred to V2**; V1 ships only `.timer.set`.
- **No live control sync in V1.** Both devices schedule alarms independently from one server-stamped `startedAtServerTs + durationMs`. No start/stop/pause sync wire. *Cancellation does propagate* (sender cancels → recipient's scheduled alarm cancels) — see Step 6 rationale.
- **Concurrent timers per chat allowed.** Each timer is its own message bubble with its own scheduler entry. The alarm notification labels itself with the timer's caption so the user can tell which one is ringing.
- **Alarm dismiss does not change bubble state.** Bubble state is RUNNING / COMPLETED / CANCELLED. Whether the user dismissed the system notification is system-level UX we don't track.
- **Trigger only at message start.** `.` mid-message is just punctuation, never a command trigger. Avoids decimal/sentence-end collisions.
- **Caption support.** Composer text typed alongside the command becomes the timer bubble's caption.
- **Alarm sound.** `RingtoneManager.getDefaultUri(TYPE_ALARM)` — respects the system-wide default alarm sound. We can't read another app's per-app preferences (e.g. AOSP DeskClock's internal sound choice).
- **`.timer.config`, `.torch`, snooze, per-chat sound override, silent-timer, pomodoro** — all parking-lot. Nothing from the parking lot pulled into V1.

## Architecture overview

```
domain/command/
  ChatCommand           # interface: id, parent, children, composeWidget()
  CommandRegistry       # provides root commands + lookup by path
  CommandPath           # value type wrapping List<String> (e.g. ["timer","set"])

ui/chat/
  state/CommandsState           # 6th slice on ChatUiState
  manager/ChatCommandsManager   # palette open/close, path nav, filter
  composer/CommandPalette       # vertical scrollable list overlay
  composer/CommandChip          # visually-distinct chip for the .command portion
  widget/TimerPickerWidget      # hh:mm:ss wheel picker, mounts above composer

data/timer/
  TimerAlarmScheduler           # AlarmManager wrapper (setExactAndAllowWhileIdle)
  TimerAlarmReceiver            # BroadcastReceiver fired by AlarmManager → notification + state update
  BootCompletedReceiver         # restores scheduled alarms from Room after device reboot

domain/model/
  MessageType.TIMER             # new enum value
  Message                       # gains timerDurationMs / timerStartedAtMs / timerState fields
```

Wire flow:

```
sender:                                        recipient:
  TimerPickerWidget.onSend(duration, caption)
    → MessageRepository.sendTimerMessage(...)
        → Firestore write w/ serverTimestamp()
        → Room insert (state=RUNNING)
        → TimerAlarmScheduler.schedule(messageId, fireAtMs)
                                                   ← MessageObserver receives new TIMER msg
                                                       → TimerAlarmScheduler.schedule(messageId, fireAtMs)
                                                       → MessageBubble renders live countdown

  long-press bubble → cancel
    → MessageRepository.cancelTimer(messageId)
        → Firestore update state=CANCELLED
        → TimerAlarmScheduler.cancel(messageId)
                                                   ← MessageObserver sees state change
                                                       → TimerAlarmScheduler.cancel(messageId)

  fire time:
    AlarmManager → TimerAlarmReceiver
      → write state=COMPLETED to Firestore + Room
      → post alarm-style notification (full-screen intent)
```

## Steps

| Step | Task | Model | Effort | Rationale |
|------|------|-------|--------|-----------|
| 1 | CommandRegistry foundation + `CommandsState` slice + `ChatCommandsManager` | Opus | High | New pattern others build on; sets the abstraction shape for every future `.command` |
| 2 | Composer DSL parsing + `CommandPalette` UI + `CommandChip` rendering | Opus | Medium | State machine with edge cases (filter, backspace, cancel, chip rendering); high blast radius on composer behavior |
| 3 | Timer domain + Room schema bump + repository methods | Sonnet | Medium | Extends existing Message patterns; touches Room (version bump required), Firestore mapping, repo interface |
| 4 | `TimerPickerWidget` (hh:mm:ss wheel) + composer integration | Sonnet | Medium | UI component following existing widget patterns; isolated to composer scope |
| 5 | `TimerAlarmScheduler` + alarm notification + boot-restore + permission flow | Opus | High | Security-adjacent (exact alarm permission, boot receiver), cross-cutting (DI + manifest + notification channel + permission UX) |
| 6 | TIMER message bubble + bidirectional alarm fire + cancellation propagation | Sonnet | High | Multiple touchpoints (MessageBubble branch, recipient-side observer, cancellation reactor, live countdown); follows existing bubble patterns but interacts with the scheduler from step 5 |

**Order:** 1 → 2 → 3 → 4+5 → 6

Steps 4 and 5 run in parallel — once the domain model exists (3), the picker widget (4) and the alarm scheduler (5) are independent. Step 6 joins them and adds the recipient-side reactor.

---

## Step 1 — CommandRegistry foundation

**Goal:** define the abstraction every `.command` plugs into, plus the state slice + manager that drives the palette.

### New files

- `domain/command/ChatCommand.kt`
  ```kotlin
  interface ChatCommand {
      val id: String                          // "timer", "set", "send"
      val displayName: String                 // ".timer", ".set"
      val children: List<ChatCommand>         // empty for leaves

      /** Called when this command is the active leaf and should mount its widget. */
      val widget: ChatCommandWidget?
  }

  /** Pluggable widget mounted above the composer when a leaf command is active. */
  interface ChatCommandWidget {
      @Composable
      fun Render(
          chatId: String,
          composerText: String,             // current caption text
          onSend: (CommandPayload) -> Unit, // submit the command (closes widget, sends message)
          onCancel: () -> Unit,              // dismiss widget without sending
      )
  }

  sealed interface CommandPayload {
      data class Timer(val durationMs: Long, val caption: String?) : CommandPayload
  }
  ```

- `domain/command/CommandRegistry.kt`
  ```kotlin
  @Singleton
  class CommandRegistry @Inject constructor(
      private val commands: Set<@JvmSuppressWildcards ChatCommand>,
  ) {
      val roots: List<ChatCommand> = commands.sortedBy { it.id }

      fun resolve(path: CommandPath): ChatCommand? { /* walk roots → children */ }
      fun childrenOf(path: CommandPath): List<ChatCommand> { /* roots if empty path */ }
  }
  ```
  DI uses Hilt multibinding (`@IntoSet`) so each command (timer, future torch, etc.) self-registers in its own Hilt module.

- `domain/command/CommandPath.kt` — value type wrapping `List<String>` with helpers (`append`, `parent`, `displayString → ".timer.set"`).

- `ui/chat/state/CommandsState.kt`
  ```kotlin
  data class CommandsState(
      val isPaletteOpen: Boolean = false,
      val currentPath: CommandPath = CommandPath.ROOT,
      val candidates: List<ChatCommand> = emptyList(),
      val activeWidget: ChatCommandWidget? = null,  // non-null = widget mounted above composer
      val filter: String = "",
  )
  ```

- `ui/chat/manager/ChatCommandsManager.kt` — owns the slice; methods: `openPalette()`, `closePalette()`, `navigateInto(commandId)`, `navigateBack()`, `mountWidget(widget)`, `dismissWidget()`, `updateFilter(text)`. Follows the existing Chat\*Manager pattern (slice ownership, never call other managers, mutate via `_uiState.update {}`).

### Modified files

- `ui/chat/state/ChatUiState.kt` — add `commands: CommandsState = CommandsState()` as a 6th slice. Update the `// region: AGENT-NOTE` block accordingly.
- `ui/chat/ChatViewModel.kt` — instantiate `ChatCommandsManager`, expose its actions.
- `di/AppModule.kt` — provide an empty `Set<ChatCommand>` multibinding hook so the timer module in step 3 can contribute without a separate setup.

### Tests

- `ChatCommandsManagerTest` — palette open/close, navigation in/back, filter narrows candidates, widget mount/dismiss state transitions. **Required** (state machine = non-trivial).
- `CommandRegistryTest` — resolve known/unknown paths, root listing, deep navigation. **Required**.

### Done criteria

- `./gradlew test` green (new tests pass).
- `./gradlew assembleDebug` clean.
- No `ChatCommand` implementations exist yet — that's fine; the registry returns an empty list and `ChatCommandsManager` works with zero commands. Step 3 contributes the first one.

---

## Step 2 — Composer DSL parsing + palette UI

**Goal:** make the `.` trigger work end-to-end with the empty registry from step 1. Visible result: typing `.` opens an empty palette ("No commands available"); commands appear after step 3 lands.

### New files

- `ui/chat/composer/CommandPalette.kt`
  - Vertical `LazyColumn` overlay positioned **above** the composer, max height ~40% screen.
  - Each row: command displayName, optional description.
  - Tap row → `manager.navigateInto(command.id)`. If the command has no children, `manager.mountWidget(command.widget)` and close palette.
  - Empty state: "No commands available" / "No matches".
  - Enter/exit animation: slide-up + fade (300ms, matches NavHost transitions).

- `ui/chat/composer/CommandChip.kt`
  - Renders the `.command.subcommand` portion of composer text as a Material3 `AssistChip`-style pill, separate from the regular text input.
  - The composer becomes a horizontal `Row { CommandChip; TextField }` when a command is active.

- `ui/chat/composer/CommandComposerParser.kt` (pure function)
  ```kotlin
  /**
   * Parses the composer text, returning the active command path and the residual caption text.
   * Returns null if no command trigger is active (text doesn't start with '.').
   */
  fun parseCommandText(text: String): ParsedCommand?
  data class ParsedCommand(val path: CommandPath, val caption: String, val cursorAfterDot: Boolean)
  ```
  - Triggers only on `text.startsWith(".")`.
  - `cursorAfterDot` = true when text ends in `.` and palette should open (e.g. `.timer.` → palette of timer's children).

### Modified files

- `ui/chat/composer/MessageComposer.kt` (or wherever the composer composable lives — confirm exact path during impl)
  - Hook a `LaunchedEffect(composerText)` that calls `manager.onTextChanged(text)` (parses + opens/closes palette accordingly).
  - When `commands.activeWidget != null`, render the widget above the composer and hide the palette.
  - Render `CommandPalette` above the composer when `commands.isPaletteOpen`.
  - `BackHandler` while palette is open → `manager.navigateBack()` (or close if at root).

### Behavior contract

| User action | Effect |
|-------------|--------|
| Type `.` at message start | Palette opens with root commands (`registry.roots`) |
| Type `.tim` | Filter narrows palette to commands matching `tim` |
| Tap `.timer` row | Append `timer` to path → palette shows children (`set`, `send`); composer text becomes `.timer.<filter>` with chip rendering |
| Tap `.set` (leaf) | Mount `timer.set` widget; palette closes; composer text becomes `.timer.set` chip + empty caption field |
| Backspace through trailing chip | Pop one path segment; if path empty after pop, close palette + clear chip |
| Tap outside palette | Close palette but **keep** the composer text as-is (user can resume) |
| Press Send while widget mounted | Widget's `onSend` invoked; widget controls payload (e.g. timer collects duration from picker) |
| Press Send with palette open but no leaf selected | No-op (or send raw text? — go with no-op; surfaces nothing destructive) |

### Tests

- `CommandComposerParserTest` — covers all the parser branches (start-of-message, mid-text decimal, trailing dot, multi-segment path, mixed with caption text). **Required** (pure parsing logic).
- `ChatCommandsManagerIntegrationTest` (extends step 1's test) — composer text changes drive palette state transitions correctly.

### Done criteria

- Type `.` in composer → empty palette appears (registry has no commands yet).
- Backspace through `.` → palette closes.
- Type ordinary text starting with non-`.` → palette stays closed regardless of dots later in the text.
- All tests pass; `./gradlew assembleDebug` clean.

---

## Step 3 — Timer domain + Room schema bump + repository methods

**Goal:** persist timer state, register the timer command with the registry, but **no UI yet** — that's steps 4 and 6.

### Domain model changes

- `domain/model/MessageType.kt` — add `TIMER`.
- `domain/model/Message.kt` — add three nullable fields:
  ```kotlin
  val timerDurationMs: Long? = null,
  val timerStartedAtMs: Long? = null,    // server timestamp at message creation
  val timerState: TimerState? = null,    // RUNNING / COMPLETED / CANCELLED
  ```
- `domain/model/TimerState.kt` — new enum.

### Persistence changes

- `data/local/entity/MessageEntity.kt` — three matching nullable columns. Update `Converters.kt` if needed for the enum.
- `data/local/AppDatabase.kt` — **bump `@Database(version = N)` to N+1.** `fallbackToDestructiveMigration()` is enabled so no migration code needed, but the version bump is mandatory (per Room version bump rule in MEMORY.md — without it, identity-hash check crashes at runtime).
- `data/remote/firebase/FirestoreMessageSource.kt` — read/write `timerDurationMs`, `timerStartedAtMs`, `timerState` on the Firestore message doc. `timerStartedAtMs` writes via `FieldValue.serverTimestamp()` to get monotonic cross-device fire time.
- `data/repository/MessageRepositoryImpl.kt`:
  - `sendTimerMessage(chatId: String, durationMs: Long, caption: String?): Result<Unit>` — creates a TIMER message with `timerState = RUNNING`. Caption goes in the existing `content` field. Bypasses Signal encryption (TIMER messages are interactive control, plaintext like CALL — see Key Notes below).
  - `cancelTimer(messageId: String): Result<Unit>` — flips `timerState` to CANCELLED.
  - `markTimerCompleted(messageId: String): Result<Unit>` — flips to COMPLETED. Called by `TimerAlarmReceiver` in step 5 when the alarm fires.
- `domain/repository/MessageRepository.kt` — interface methods for the three above.

### CommandRegistry contribution

- New file `ui/chat/command/TimerCommand.kt` (UI layer because the widget is Compose):
  ```kotlin
  class TimerCommand @Inject constructor(
      private val setSubcommand: TimerSetCommand,
  ) : ChatCommand {
      override val id = "timer"
      override val displayName = ".timer"
      override val children = listOf(setSubcommand)
      override val widget = null  // not a leaf
  }

  class TimerSetCommand @Inject constructor(
      private val widgetImpl: TimerSetWidget,  // Step 4 creates this; stub for now
  ) : ChatCommand {
      override val id = "set"
      override val displayName = ".set"
      override val children = emptyList()
      override val widget: ChatCommandWidget get() = widgetImpl
  }
  ```
- New Hilt module `di/CommandModule.kt` — `@IntoSet` binds `TimerCommand` to the registry's multibound set.

### Tests

- `MessageRepositoryImplTest` — extend with `sendTimerMessage` / `cancelTimer` / `markTimerCompleted` happy paths using existing fakes pattern. **Required**.
- `MessageEntity` ↔ `Message` mapping test for the new fields. **Required**.

### Done criteria

- `./gradlew test` green.
- `./gradlew assembleDebug` clean — Room schema bumped, no identity-hash crash on first launch.
- Manual: type `.` in composer → palette shows `.timer`. Tap → palette shows `.set`. Tap `.set` → currently mounts a placeholder widget (Step 4 swaps in the real picker).

---

## Step 4 — TimerPickerWidget (hh:mm:ss wheel) + composer integration

**Goal:** the picker widget that mounts when `.timer.set` is selected. Send button collects the duration, widget calls `onSend`, message gets sent via the repository from step 3. **Alarm scheduling is step 5** — for now sending just persists the message in RUNNING state.

### New files

- `ui/chat/widget/TimerPickerWidget.kt`
  ```kotlin
  @Singleton
  class TimerSetWidget @Inject constructor(
      private val viewModelFactory: TimerSetWidgetViewModel.Factory,
  ) : ChatCommandWidget {
      @Composable
      override fun Render(chatId, composerText, onSend, onCancel) { ... }
  }
  ```
  - Layout: three vertical wheel pickers (HH / MM / SS) side-by-side, each implemented as a `LazyColumn` with snap-to-center using `rememberLazyListState()` + `LaunchedEffect` to snap on settle. (Material3 has no built-in wheel picker; this matches existing custom-picker patterns elsewhere in the project — confirm or fall back to three `OutlinedTextField`s with numeric IME if wheel UX proves janky.)
  - HH: 0–23, MM: 0–59, SS: 0–59. Cap at 23:59:59.
  - Bottom row: `Cancel` (outlined) → `onCancel()`; `Send` (filled) → `onSend(CommandPayload.Timer(durationMs, composerText.takeIf { it.isNotBlank() }))`. **Send is disabled when total duration is 0.**
  - Header: "Set timer" + small live preview ("01:23:45").

- `ui/chat/widget/TimerSetWidgetViewModel.kt` — holds the picker state (HH/MM/SS as `Int`), exposes `durationMs: StateFlow<Long>`. AssistedInject pattern (no chatId in the widget VM today, but keep the factory shape so per-widget state can be scoped per-chat later).

### Modified files

- `ui/chat/composer/MessageComposer.kt` — when `commands.activeWidget != null`, render the widget above the composer and route `onSend` to:
  ```kotlin
  viewModel.onCommandSubmit(payload)  // new ViewModel method
  ```
- `ui/chat/ChatViewModel.kt` — add `onCommandSubmit(payload: CommandPayload)`:
  ```kotlin
  when (payload) {
      is CommandPayload.Timer -> messageRepository.sendTimerMessage(chatId, payload.durationMs, payload.caption)
  }
  // Then close widget + clear composer
  commandsManager.dismissWidget()
  composerManager.clearText()
  ```

### Tests

- `TimerSetWidgetViewModelTest` — duration math (HH/MM/SS → ms), zero-duration disables send, max cap at 23:59:59. **Required** (non-trivial logic).

### Done criteria

- Type `.timer.set` in composer → wheel picker mounts above composer. Dial 00:00:30, type a caption, press send. Message lands in chat as a placeholder TIMER bubble (full bubble UI is step 6 — for now any visible representation is fine, even just text "Timer 30s").
- `./gradlew test` green; `./gradlew assembleDebug` clean.

---

## Step 5 — TimerAlarmScheduler + system alarm UX (parallel with Step 4)

**Goal:** when the message lands (sender-side from step 4, recipient-side from step 6), schedule an exact `AlarmManager` job. When it fires, post an alarm-style notification using the system default alarm sound.

### New files

- `data/timer/TimerAlarmScheduler.kt` (`@Singleton`)
  ```kotlin
  fun schedule(messageId: String, fireAtMs: Long, caption: String?, chatId: String, otherUserId: String)
  fun cancel(messageId: String)
  ```
  - Uses `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, fireAtMs, pendingIntent)`.
  - PendingIntent extras: `messageId`, `chatId`, `caption`, `otherUserId`. Request code = stable hash of `messageId` so cancel can find it.
  - **Permission gate:** on Android 12+ check `AlarmManager.canScheduleExactAlarms()`. If false, fall back to `setAndAllowWhileIdle` (inexact, may fire late) and surface a one-shot in-app banner pointing the user to system settings to grant the permission. Don't pop a system dialog without context.

- `data/timer/TimerAlarmReceiver.kt` (`BroadcastReceiver`, registered in manifest)
  - Reads extras → calls `MessageRepository.markTimerCompleted(messageId)` (via Hilt `@AndroidEntryPoint`).
  - Posts a high-priority notification on the new `timer_alarms` channel (see below). Notification content: caption (or "Timer ended"), full-screen intent → `MainActivity` deep-link to the chat (reuse existing `chatId`/`senderId` extras pattern from FCM).
  - Sound: `RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)`.
  - Vibration: standard alarm pattern `longArrayOf(0, 1000, 500, 1000, 500)`, repeat at index 0.

- `data/timer/BootCompletedReceiver.kt` (`BroadcastReceiver`, listens for `ACTION_BOOT_COMPLETED`)
  - On boot, query Room for all messages where `timerState = RUNNING` AND `timerStartedAtMs + timerDurationMs > now`.
  - For each, call `TimerAlarmScheduler.schedule(...)` to re-register.
  - For any whose fire time has already passed during the off-period, call `markTimerCompleted` directly (with no notification — the moment passed).

- `data/timer/TimerNotificationChannel.kt`
  - Creates the `timer_alarms` channel: `IMPORTANCE_HIGH`, `setSound(defaultAlarm, alarmAudioAttributes)`, vibrate enabled, `setBypassDnd(true)` if user has granted Do Not Disturb access (skip if not — don't request just for this).
  - Channel created at app startup in `FireStreamApp.onCreate()`.

### Modified files

- `app/src/main/AndroidManifest.xml`:
  - Permissions: `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />`, `<uses-permission android:name="android.permission.USE_EXACT_ALARM" />` (Android 13+ — auto-granted but required), `<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />`.
  - Receivers: `TimerAlarmReceiver` (no intent filter, fired by PendingIntent) and `BootCompletedReceiver` (`<intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>`, `android:exported="true"`).
- `FireStreamApp.kt` — call `TimerNotificationChannel.ensureCreated(this)` in `onCreate()`.
- `di/SystemModule.kt` — provide `AlarmManager` from `Context.getSystemService(...)`.

### Tests

- `TimerAlarmSchedulerTest` — schedule/cancel happy path with mocked `AlarmManager`; permission-denied fallback path. **Required** (security-adjacent + has fallback branch).
- Boot-restore logic test (pure Kotlin, mock the repository): given a list of TIMER messages with various fire times, returns correct schedule/markCompleted actions. **Required**.

### Done criteria

- `./gradlew test` green; `./gradlew assembleDebug` clean.
- Manual: send a `.timer.set 00:00:10` message. ~10s later, alarm-style notification fires with the default alarm sound. Tapping notification opens the chat.
- Manual: kill the app process during a running timer → alarm still fires (AlarmManager survives process death).
- Manual (release-build only, deferred): reboot the device with a future timer pending → alarm still fires after boot.

---

## Step 6 — TIMER message bubble + bidirectional alarm fire + cancellation propagation

**Goal:** the visible message bubble that shows the live countdown, and the wiring that makes the recipient also schedule an alarm and react to cancellation.

### New files

- `ui/chat/TimerMessageBubble.kt`
  - Composable receiving the `Message` (TIMER type) plus `currentUserId`.
  - Live countdown via `produceState(initialValue = computeRemainingMs(message), key1 = message.timerState, key2 = message.timerStartedAtMs) { while (true) { value = computeRemainingMs(message); if (value <= 0) break; delay(1000) } }`.
  - Visual states:
    - **RUNNING:** large `mm:ss` (or `hh:mm:ss` if duration ≥ 1h) countdown, alarm-clock icon, caption below.
    - **COMPLETED:** muted background, "Timer ended" + original duration display, caption below.
    - **CANCELLED:** struck-through duration, "Cancelled" label.
  - Long-press while RUNNING → context menu with `Cancel timer` action (visible to **both** sender and recipient — once we propagate cancellation, either side can cancel).

### Modified files

- `ui/chat/MessageBubble.kt` — add `MessageType.TIMER` branch dispatching to `TimerMessageBubble`.
- `ui/chat/ChatViewModel.kt` (or `ChatMessagesManager`) — observe incoming messages; when a TIMER message arrives or its state changes:
  ```kotlin
  newOrUpdatedMessages
      .filter { it.type == MessageType.TIMER }
      .collect { msg ->
          when (msg.timerState) {
              TimerState.RUNNING -> {
                  val fireAt = (msg.timerStartedAtMs ?: return@collect) + (msg.timerDurationMs ?: return@collect)
                  if (fireAt > System.currentTimeMillis()) {
                      timerAlarmScheduler.schedule(msg.id, fireAt, msg.content, msg.chatId, otherUserId)
                  }
              }
              TimerState.CANCELLED, TimerState.COMPLETED -> {
                  timerAlarmScheduler.cancel(msg.id)
              }
              null -> Unit
          }
      }
  ```
  This single observer handles **all four** wires:
  - Sender's local insert (post-send) → schedule on sender's device.
  - Recipient's Firestore sync arrival → schedule on recipient's device.
  - Sender cancels → state flips → both observers cancel their local schedule.
  - Alarm fires (anywhere) → `markTimerCompleted` flips state → both observers cancel any remaining schedule (recipient already fired or hadn't yet — idempotent cancel is safe).

### Cancellation rationale (re-stated for the implementer)

In V1 we explicitly rejected start/stop/pause sync. Cancellation is *not* live control — it's a state change to the canonical message in Firestore, and reacting to a message-state change is just message observation, which we already do for everything else. So cancellation propagates "for free" through the observer above.

### Tests

- `TimerCountdownTest` — `computeRemainingMs(message)` math: clock skew, paused (n/a in V1 but stub the function), past-fire-time clamp. **Required**.
- ViewModel observer test — TIMER message state transitions invoke scheduler correctly. **Required** (cross-cutting behavior).
- Bubble snapshot/rendering test — skip (UI-only).

### Done criteria

- `./gradlew test` green; `./gradlew assembleDebug` clean.
- Manual two-device test (or one device + emulator):
  - Send `.timer.set 00:00:30` from device A.
  - Device B receives the message, bubble shows live countdown ticking down in sync with A.
  - At fire time, both devices ring with the alarm-style notification.
  - Cancel from A (long-press → cancel) → both bubbles flip to CANCELLED, B's pending alarm doesn't fire.
- `/simplify` triggered by: cross-cutting (DI + repo + observer + scheduler + bubble + manager) + likely >600 changed lines across the 6 steps. Run with all three Phase-2 Agents pinned to `model: "opus"`.

---

## Out of scope (parking lot)

These are explicitly **not** in V1; revisit after dogfooding tells us which one users actually ask for:

- `.timer.send` — "send my currently-running timer." Requires a notion of "the active local timer" decoupled from a specific message.
- `.timer.config` — per-chat sound override (highest-value config; only one that genuinely needs per-chat scoping), silent timer, snooze, auto-cancel-on-read.
- `.timer.pomo` (pomodoro) — repeating 25/5 cycles. Probably its own command rather than a config flag.
- Bidirectional live control (start/pause/resume sync) — requires conflict resolution on ~200ms-window collisions; LWW would likely suffice but adds complexity.
- Multi-device dedupe per user (phone + tablet both fire the alarm).
- Other commands: `.torch`, `.note`, `.reminder`, `.gif`. Framework supports them; just need a `ChatCommand` impl + Hilt `@IntoSet` binding.
- Mid-typing filter convenience (e.g. `.ti` filters palette to timer) — already specified in step 2's behavior contract; if it proves janky in practice, can be removed without API change.
- Trailing text after a leaf command (`.timer.send Pizza's ready` pre-fills caption from composer text after the chip) — currently caption comes only from composer text typed *after* the widget mounts. Easy follow-up.

## Key notes for the implementer

- **TIMER messages bypass Signal encryption**, same as CALL messages today. They're interactive control state; encrypting them complicates the cancellation/observer path significantly. Document this in the bubble header per existing CALL pattern. Promote to Signal-encrypted later if threat model tightens.
- **Server timestamp is load-bearing.** Never compute fire time from `System.currentTimeMillis()` on the sender's device — clock skew between devices will desync the alarms. Use Firestore `serverTimestamp()` and read it back from the document.
- **Room version bump (Step 3) is mandatory.** Forgetting this crashes at runtime via the identity-hash check, before destructive migration kicks in. Per MEMORY.md.
- **`ChatCommandsManager` follows slice-ownership.** It owns `CommandsState` and ONLY `CommandsState`. It must not call into `ChatComposerManager`, `ChatMessagesManager`, etc. Cross-slice state changes (e.g. "command sent → clear composer text") collapse into a single `_uiState.update {}` block in `ChatViewModel`, not in the manager itself. Per `docs/PATTERNS.md#chat-manager-slice-ownership`.
- **Anchor header.** Both `ChatCommandsManager` and `TimerAlarmScheduler` are new significant files — start each with a `// region: AGENT-NOTE` block citing the relevant pattern by name.
- **CHANGELOG entry.** This is a `feat:` (minor bump). Add a single entry under `## [Unreleased]` summarizing the user-visible result ("type `.` in any chat to access commands; `.timer.set` lets you set a synchronized timer that rings on both devices") rather than enumerating each step's commit. Group all step commits' hashes in the trailing backticks.
- **`docs/FEATURE-MAP.md`.** This feature spans 4+ packages (domain/command, ui/chat/composer, ui/chat/widget, data/timer, AndroidManifest). Add a "Dot Commands & Timer" entry to FEATURE-MAP.md as part of step 6.
- **Permission UX.** `SCHEDULE_EXACT_ALARM` on Android 12+ is a special-app-access permission, not a runtime permission. Don't surface a permission dialog inline with the timer flow — instead, on first denial show an in-app banner ("Timers may fire late — tap to enable exact alarms in system settings") that deep-links to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.

## Verification checklist (whole feature)

- [ ] Type `.` at message start → palette opens. Type `.` mid-message → no palette.
- [ ] Type `.t` → palette filters to commands starting with `t`.
- [ ] Tap `.timer` → subcommand palette appears with `.set`.
- [ ] Tap `.set` → wheel picker mounts; cancel button dismisses cleanly.
- [ ] Set 00:00:30, send → message bubble shows in chat with live countdown on **both** devices.
- [ ] Caption typed in composer renders below the timer in the bubble.
- [ ] At fire time, both devices ring with the system default alarm sound + alarm-style notification + full-screen intent.
- [ ] Tapping the alarm notification deep-links into the right chat.
- [ ] Long-press a running timer bubble → Cancel → both bubbles flip to CANCELLED, recipient's pending alarm doesn't fire.
- [ ] Run two concurrent timers in the same chat → both fire independently with their own captions in their respective notifications.
- [ ] Kill app process during running timer → alarm still fires.
- [ ] On Android 12+ device with exact-alarm permission denied → in-app banner appears; timer still fires (may be ≤15min late per `setAndAllowWhileIdle` Doze rules).
- [ ] `./gradlew test` green; `./gradlew assembleDebug` clean; `/simplify` produces no critical findings.
