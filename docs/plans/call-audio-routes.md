# Call audio routes — Bluetooth / wired headset in calls, minSdk 31

Brief for replacing the binary speaker toggle in voice calls with a real audio-route
picker (earpiece, speaker, Bluetooth, wired headset), and for raising `minSdk` from 29 to
31 so the routing can use the one non-deprecated API. Written 2026-09-11 from a code read
of `main` at `4b0e5fa3`; re-verify line numbers before editing.

**Order: 1 → 2 → 3 → 4 → 5** (fully sequential, no checkpoint. Until 2026-09-20 a `‖` stopped the run after the minSdk step so the human could look at the fallout first; removed for the benchmark in `docs/plans/plan-runner-benchmark.md`, which measures how far a configuration gets unattended. Nothing is merged or pushed by the runner, so step 1 is reviewed at the end with the rest, with the judge's grade and the test-count delta in hand.)

## 0. Decisions (signed off 2026-09-11 — do not re-litigate)

| Question | Decision |
|---|---|
| Approach | **Option A** — routing inside the existing `CallService` via `AudioManager.setCommunicationDevice()`. No Telecom / core-telecom integration (that is option B, backlog, trigger: "calls need to survive an incoming cellular call" or "answer from a car kit"). |
| minSdk | **31.** Android 10 and 11 are dropped. No `startBluetoothSco()` fallback, no SCO state machine. |
| Bluetooth device names | **Generic "Bluetooth" label.** Product names need the `BLUETOOTH_CONNECT` runtime permission on 31+; not worth a permission prompt. Follow-up if wanted. |
| Auto-selection | A headset that connects mid-call **always wins** (matches the stock dialer). A disconnect falls back to **earpiece, never speaker**. |
| UI on a phone with no headset | Unchanged: one speaker toggle button. The route sheet only appears when a third route exists. |

Not in scope: ringtone routing for incoming calls, hold/interrupt by cellular calls,
volume keys, video calls, the PocketBase flavor beyond compiling (calls are flavor-neutral
in `app/src/main/`, so nothing to stub).

## 1. Current state (verified 2026-09-11)

- `CallService.toggleSpeaker()` (`CallService.kt:528`) flips `AudioManager.isSpeakerphoneOn`
  from a `CallUiControls.isSpeakerOn` boolean. That is the **only** routing call in the app.
  Nothing ever asks for a Bluetooth or wired route, so a connected headset is unreachable.
  Root cause of "only speaker and phone" — Android does not route `MODE_IN_COMMUNICATION`
  audio to a Bluetooth headset unless the app selects it.
- `requestAudioFocus()` (`:553`) sets `MODE_IN_COMMUNICATION` and saves `isSpeakerphoneOn`;
  `abandonAudioFocus()` (`:571`) restores both. Both run from `onIceConnectionChange`
  CONNECTED (`:473`) and `cleanup()` (`:645`).
- Proximity wake lock (`:579`) is acquired on CONNECTED regardless of route, so the screen
  also blanks while the phone is on speaker held near the face.
- `WebRtcPeerConnectionFactory` uses the default `PeerConnectionFactory` audio device module
  (no custom ADM) — it records with `VOICE_COMMUNICATION` and does no routing of its own.
- UI: `ConnectedContent` (`CallScreen.kt:215`) has 9 params; the speaker button is a
  `CallControlButton` with `Icons.Default.VolumeUp` (`:265`). `material-icons-extended` is a
  dependency (`app/build.gradle.kts:407`) so `Bluetooth` / `Headset` icons are available.
- `CallViewModel.toggleSpeaker()` → `CallService.sendAction(ACTION_TOGGLE_SPEAKER)` (intent,
  not a bound service). `CallStateHolder` (`@Singleton`) is the service→UI bridge.
- Tests: only `CallStateHolderTest` (`toggleSpeaker flips isSpeakerOn`, `updateControls`,
  `reset`). No `CallService` tests (Android `Service`, untestable on the JVM).
- Manifest already declares `MODIFY_AUDIO_SETTINGS` and legacy `BLUETOOTH`. Neither
  `getAvailableCommunicationDevices()` nor `setCommunicationDevice()` needs any permission.
- `minSdk = 29` in `app/build.gradle.kts:91` and `baselineprofile/build.gradle.kts:12`.
  Four `SDK_INT >= S` branches exist (`ReminderAlarmScheduler.kt:58`, `TimerAlarmScheduler.kt:76`,
  `SpeechRecognizerManager.kt:46`, `ExactAlarmBanner.kt:86`). One test covers the pre-S path:
  `TimerAlarmSchedulerTest` "pre-S devices skip the canScheduleExactAlarms check entirely" (`:100`).
- 26 Robolectric tests pin `@Config(sdk = [29], …)`; `docs/PATTERNS.md:125` quotes that
  annotation. `MessageBubbleScreenshotTest` records Roborazzi baselines in
  `app/src/test/snapshots/*.png` at sdk 29.

## 2. Design

### 2.1 Domain model (`domain/model/CallState.kt`)

```kotlin
enum class CallAudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET }

data class CallUiControls(
    val isMuted: Boolean = false,
    val audioRoute: CallAudioRoute = CallAudioRoute.EARPIECE,
    val availableRoutes: List<CallAudioRoute> = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
)
```

`isSpeakerOn` is removed, not kept as a derived property — every reader is rewritten.
`availableRoutes` is ordered for display: EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET.

### 2.2 Pure route policy (`data/call/CallAudioRoutePolicy.kt`)

One `object` with one function, no Android imports, fully unit-tested:

```kotlin
fun resolve(
    previousAvailable: Set<CallAudioRoute>,
    available: Set<CallAudioRoute>,
    current: CallAudioRoute?,          // what the OS reports as active, null before first pick
    userPick: CallAudioRoute?,         // last explicit tap, null = none
): CallAudioRoute
```

Rules, in priority order:
1. A **headset route that is in `available` but not in `previousAvailable`** wins (BT over wired
   if both appeared at once). This is the "plugged in mid-call" preemption and it also clears
   the user pick (the caller nulls `userPick` when the policy returns a route ≠ `userPick`).
2. Else if `userPick != null && userPick in available` → `userPick`.
3. Else if `current != null && current in available` → `current` (nothing changed, stay put).
4. Else auto preference: BLUETOOTH > WIRED_HEADSET > EARPIECE > SPEAKER. SPEAKER is only
   reached on devices without an earpiece (tablets). A headset disconnect therefore lands on
   EARPIECE, never on SPEAKER.

Device-type mapping (also pure, same file, `fun routeOf(type: Int): CallAudioRoute?`):
`TYPE_BUILTIN_EARPIECE`→EARPIECE, `TYPE_BUILTIN_SPEAKER`→SPEAKER,
`TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET`→BLUETOOTH,
`TYPE_WIRED_HEADSET` / `TYPE_WIRED_HEADPHONES` / `TYPE_USB_HEADSET` / `TYPE_USB_DEVICE`→WIRED_HEADSET,
anything else → null (ignored). `AudioDeviceInfo.TYPE_*` are plain `Int` constants, so the test
passes the ints and the function stays Android-free.

### 2.3 Android wrapper (`data/call/CallAudioRouter.kt`)

Thin class owned by `CallService`, constructed with `AudioManager` + main `Executor`:

- `start()` — registers an `AudioDeviceCallback` and an `OnCommunicationDeviceChangedListener`
  (both on the main executor), snapshots `availableCommunicationDevices`, runs the policy once
  and applies the result. Called from `requestAudioFocus()` **after** `mode = MODE_IN_COMMUNICATION`.
- `select(route)` — records `userPick`, finds the first `AudioDeviceInfo` mapping to that route
  in the *current* `availableCommunicationDevices` list, calls `setCommunicationDevice(it)`.
  If the route vanished between the tap and the call, re-run the policy instead.
- `stop()` — `clearCommunicationDevice()`, unregister both listeners. Called from
  `abandonAudioFocus()` before the mode is restored. Idempotent.
- Exposes `val state: StateFlow<RouteState>` where `RouteState(available: List<CallAudioRoute>, current: CallAudioRoute)`.
  `current` comes from `OnCommunicationDeviceChangedListener` (the OS's actual route), **not**
  from the requested route, so the UI never shows Bluetooth before SCO is actually up.
- On every `onAudioDevicesAdded/Removed` **re-query** `availableCommunicationDevices` rather than
  reading the callback's array — the callback reports all devices, communication-capable or not.

Non-goals for the router: no retry timers, no SCO polling, no `isSpeakerphoneOn` anywhere.

### 2.4 `CallService` wiring

- `ACTION_TOGGLE_SPEAKER` → `ACTION_SELECT_AUDIO_ROUTE` + `EXTRA_AUDIO_ROUTE` (enum `name`).
  `CallViewModel.toggleSpeaker()` → `selectAudioRoute(route: CallAudioRoute)`.
- `serviceScope.launch { router.state.collect { callStateHolder.updateAudioRoutes(it.available, it.current) } }`
  started in `requestAudioFocus()`, cancelled in `cleanup()`.
- `CallStateHolder.toggleSpeaker()` → `updateAudioRoutes(available, current)`; `reset()` unchanged.
- Proximity: the same collector calls `acquireProximityWakeLock()` when `current == EARPIECE`
  and `releaseProximityWakeLock()` otherwise. Remove the unconditional acquire at `:474`.
- Remove `previousSpeakerState`. Keep `previousAudioMode`.

### 2.5 UI (`ui/call/CallScreen.kt`, `CallViewModel.kt`)

Load the `app-ui-design` skill before touching the screen. Shape:

- `ConnectedContent` params: replace `isSpeakerOn` + `onToggleSpeaker` with `audioRoute`,
  `availableRoutes`, `onSelectRoute: (CallAudioRoute) -> Unit` (10 params, under the ceiling).
- The third `CallControlButton` becomes the **route button**. Icon follows `audioRoute`:
  EARPIECE→`VolumeUp` (not highlighted), SPEAKER→`VolumeUp` (highlighted),
  BLUETOOTH→`Bluetooth` (highlighted), WIRED_HEADSET→`Headset` (highlighted).
  `contentDescription` names the current route.
- Tap behaviour: if `availableRoutes.size <= 2` → toggle EARPIECE⇄SPEAKER directly (today's UX).
  Otherwise open a `ModalBottomSheet` listing `availableRoutes` with icon + label + a check on
  the current one; tapping a row selects and dismisses.
- New labels go into `strings.xml` (`call_route_earpiece`, `_speaker`, `_bluetooth`, `_wired`,
  `call_route_sheet_title`). The existing hard-coded "Mute"/"Hang up" strings are left alone.

### 2.6 minSdk 31 cleanup

- `minSdk = 31` in `app/build.gradle.kts` and `baselineprofile/build.gradle.kts`.
- Delete the four `>= S` / `< S` branches listed in §1; `canScheduleExactAlarms()` and
  `isOnDeviceRecognitionAvailable()` are called unconditionally. Delete the pre-S test in
  `TimerAlarmSchedulerTest` (`:100`), it tests removed behaviour.
- Robolectric refuses a configured `sdk` below the manifest `minSdk`, so bulk-edit the 26
  `@Config(sdk = [29]` pins to `sdk = [31]` (`sed -i 's/sdk = \[29\]/sdk = [31]/'` over `app/src/test`).
  Update the quoted annotation in `docs/PATTERNS.md:125`. First run downloads the API-31
  `android-all` jar (network; fine locally and in the cloud container).
- `MessageBubbleScreenshotTest` will re-render on API 31. If Roborazzi reports a diff, look at
  the diff image; if it is only font/antialias noise, regenerate the four PNGs in
  `app/src/test/snapshots/` and commit them with the bump. A real layout change is a bug.
- Legacy `BLUETOOTH` uses-permission stays (harmless, still documents intent).

## 3. Steps

Each step ends with the post-step gate from `CLAUDE.md` (tests → `assembleFirebaseDebug` →
commit). `/simplify` triggers only on step 3 (concurrency: listeners + collector + wake lock).

### Step 1 — minSdk 31 (`chore(build)!` — user-visible, gets a CHANGELOG `Removed` entry) — skills: changelog-release
Files: `app/build.gradle.kts`, `baselineprofile/build.gradle.kts`, the four branch files in §1,
`TimerAlarmSchedulerTest.kt`, 26 Robolectric tests, `docs/PATTERNS.md`, possibly the four
snapshot PNGs, `CHANGELOG.md`. Invoke `changelog-release` for the bump decision (recommendation:
minor is enough, this is not an API break; the skill decides).
Gate: full `:app:testFirebaseDebugUnitTest` — this is the step most likely to surface a
surprise, so run the whole suite, not a filter.

**Approach**
1. `app/build.gradle.kts:91` and `baselineprofile/build.gradle.kts:12` → `minSdk = 31`.
2. Delete the four version branches, all still at the lines §1 names: `SpeechRecognizerManager.kt:46`
   (`isOnDeviceAvailable` becomes the bare call), `ReminderAlarmScheduler.kt:58` and
   `TimerAlarmScheduler.kt:76` (`canExact = alarmManager.canScheduleExactAlarms()`),
   `ExactAlarmBanner.kt:86` (the settings intent runs unconditionally). Unused `android.os.Build`
   imports go with them.
3. Delete `TimerAlarmSchedulerTest` "pre-S devices skip the canScheduleExactAlarms check entirely"
   (`:100`) and its now-unused `ReflectionHelpers`/`Build` imports. No test is added: this step
   removes behaviour, it adds none.
4. Bulk `sed` `sdk = [29]` → `sdk = [31]` over `app/src/test`. Correction to §1: it is **39 files**,
   not 26 — the count grew with the outbox and image-editor work. Then `docs/PATTERNS.md:125`
   (the quoted annotation) and `CLAUDE.md:61` (`minSdk = 29` in Build & Run).
5. Gate: full `:app:testFirebaseDebugUnitTest`, then `assembleFirebaseDebug`. Roborazzi baselines
   are re-checked by that run; regenerate the four PNGs only if the diff is antialias noise.
6. `changelog-release` for the bump, then the `Removed` entry and the two commits.

Nothing in the code contradicts the step's spec. Two things it does not mention, both left alone
deliberately: `OutboxScheduler.runsExpedited(sdkInt, uploads)` (`:110`) is a *pure* helper taking
the SDK as a parameter, not a branch on `Build.VERSION` — with minSdk 31 its `sdkInt >= S` arm is
always true on a real device, but it and its test stay, and the dead arm goes to `TECH_DEBT.md`
rather than into this diff; and the `Build.VERSION_CODES.TIRAMISU`/`UPSIDE_DOWN_CAKE` branches
(MainActivity, CallService, ShareContentResolver, MessageBubble, SettingsScreen, ChatScreen,
WorkerForeground) are above 31 and unaffected.

**Shipped** `3b30b8f7` (2026-09-20) — tier: mid, tagged mid. skills: changelog-release, app-ui-design. Reviewer models: none.
Departures (for sign-off):
- §1 says 26 Robolectric tests pin `sdk = [29]`; it is 39 files. All converted, suite green at 1584 tests.
- The bump is **major, 2.0.0**, not the minor §3 recommended. `changelog-release` maps the `!` in `chore(build)!` to major, and the break is real for a user: an Android 10/11 phone can no longer install the APK at all. The `[UNRELEASED] [1.34.0]` section was promoted in place to `[UNRELEASED] [2.0.0] — 2026-09-20`. The entry itself is in the code commit `3b30b8f7`; only its `` (`3b30b8f7`) `` hash suffix — unknowable before the commit existed — was appended in the `docs(plan)` commit.
- `./gradlew :app:verifyRoborazziFirebaseDebug` fails on all four `MessageBubbleScreenshotTest` baselines — **pre-existing, not the SDK bump**: the recorded PNGs show an orange own-bubble, a colour `Color.kt` has not produced since the warm-neutral redesign (`SentBubble = 0xFFE0DDD6 // NOT orange`). Recorded once in `317d9f5a` and never re-recorded; no gate runs `verifyRoborazzi`, so it rotted unseen. The PNGs are **left untouched** — re-recording would bless four unreviewed images — and the finding is in `TECH_DEBT.md` with its revisit trigger. §2.6's "regenerate if it is antialias noise" did not apply.
- Beyond the step's file list: `CLAUDE.md:61` (`minSdk = 29` in Build & Run) and `TECH_DEBT.md` — one new entry (the stale Roborazzi baselines) plus a parenthetical correcting the existing WorkManager-latency entry, which described API 29/30 send behaviour that can no longer occur. `.claude/skills/app-ui-design/SKILL.md:49` also quotes the dead `sdk = [29]` pin but the session was denied write permission under `.claude/` — flagged on step 5.
- No test added: the step only removes behaviour. Neither `simplify` (74 changed lines) nor `code-review` (no `data/crypto`, `data/worker`, `di/`, no ViewModel) was triggered by the diff.

### Step 2 — model + policy + tests (`feat(call)`, no CHANGELOG yet — nothing visible)
Files: `domain/model/CallState.kt`, new `data/call/CallAudioRoutePolicy.kt`,
`CallStateHolder.kt` (`updateAudioRoutes`), `CallStateHolderTest.kt`,
new `CallAudioRoutePolicyTest.kt`. `CallService` / `CallScreen` must still compile: this step
changes `CallUiControls`, so do the minimal reader rewrites (`isSpeakerOn` → `audioRoute == SPEAKER`)
here and leave the real routing for step 3.
Tests (all pure, table-style):
- default phone: {EARPIECE, SPEAKER}, no pick → EARPIECE
- BT present at start → BLUETOOTH
- BT appears mid-call while user picked SPEAKER → BLUETOOTH (preemption)
- wired appears while on BT → WIRED_HEADSET (new headset preempts); BT + wired appear together → BLUETOOTH
- user picks EARPIECE with BT still connected, then a device-list re-emit with no change → EARPIECE (rule 1 must not re-fire)
- BT disconnects while active → EARPIECE, not SPEAKER
- user picked SPEAKER, unrelated device list churn → SPEAKER (pick honoured)
- tablet {SPEAKER} only → SPEAKER
- `routeOf` mapping for every listed type + an unknown type → null

**Approach**
1. `domain/model/CallState.kt` — add `enum class CallAudioRoute`, replace `CallUiControls.isSpeakerOn`
   with `audioRoute` + `availableRoutes` exactly as §2.1 writes them.
2. New `data/call/CallAudioRoutePolicy.kt` — `object` with `resolve(...)` (the four rules, in order)
   and `routeOf(type: Int)`. Departure from §2.2's "no Android imports": the file lives in `data/`,
   where Android is allowed, so `routeOf` matches on `AudioDeviceInfo.TYPE_*` rather than on copied
   magic ints — those are Java compile-time constants and inline, so no `android.jar` class is ever
   loaded on the JVM. The test passes the same constants. Falls back to literals if the gate says
   otherwise.
3. `CallStateHolder.kt` — `toggleSpeaker()` → `updateAudioRoutes(available: List<CallAudioRoute>, current: CallAudioRoute)`.
4. Minimal reader rewrites so the tree still compiles, behaviour unchanged: `CallService.toggleSpeaker()`
   (`:528`) flips EARPIECE⇄SPEAKER through `updateAudioRoutes` and keeps its `isSpeakerphoneOn` write
   until step 3 deletes it; `CallScreen.kt:97` passes `uiControls.audioRoute == SPEAKER`. `ConnectedContent`
   keeps its current signature — the route button and the sheet are step 4's, so no Compose is written here
   and `app-ui-design` is not loaded for a one-expression argument change.
5. Tests: new `CallAudioRoutePolicyTest.kt` with the nine cases listed above; `CallStateHolderTest.kt`
   loses `toggleSpeaker flips isSpeakerOn` and gains `updateAudioRoutes` coverage + a reset check.
6. Gate: `:app:testFirebaseDebugUnitTest` then `assembleFirebaseDebug`.

Nothing in the code contradicts the step's spec; §1's line numbers (`CallService.kt:528`,
`CallScreen.kt:215/265`) are still accurate after step 1.

**Shipped** `eb241341` (2026-09-20) — tier: mid, tagged mid. skills: app-ui-design. Reviewer models: none.
Departures (for sign-off):
- §2.2 says the policy file has no Android imports; `CallAudioRoutePolicy` imports `AudioDeviceInfo`
  and matches on the real `TYPE_*` constants instead of copied magic ints. The file is in `data/`,
  where Konsist allows Android, and those are Java compile-time constants, so they inline and no
  Android class loads — `CallAudioRoutePolicyTest` is plain JUnit, no Robolectric, and is green.
- §2.2 gives `resolve` no rule for an empty device list. It is total: `available` empty returns
  `current ?: EARPIECE` (never SPEAKER), documented in the KDoc and tested both ways.
- 14 policy tests, not the 9 listed. Added: a speaker that only enumerates late must **not** trigger
  rule 1 (the rule is scoped to headset routes — without that scoping a late `TYPE_BUILTIN_SPEAKER`
  would hijack the call); the two empty-list cases; unknown types split from the mapping table.
  `CallStateHolderTest` also gained a check that `updateAudioRoutes` leaves `isMuted` alone.
- `app-ui-design` loaded although no Compose was written: the step touches `ui/` only through
  `CallScreen.kt:98`, which now derives `isSpeakerOn` from `audioRoute`. Confirmed the change adds no
  param, no composable and no theme decision. The real screen work is step 4.
- Per the step's own "leave the real routing for step 3", `CallService.toggleSpeaker()` still writes
  `audioManager.isSpeakerphoneOn` and `ACTION_TOGGLE_SPEAKER` still exists — behaviour is unchanged by
  this commit. Annotated on step 3 as its deletions.
- No skill beyond the floor was triggered by the diff: ~357 changed lines, no `data/crypto`,
  `data/worker`, `di/`, no ViewModel. `FEATURE-MAP.md` for the two new files is step 5's, as planned.

### Step 3 — router + service wiring (`feat(call)`, no CHANGELOG yet) — skills: simplify, code-review; model: strong
Files: new `data/call/CallAudioRouter.kt`, `CallService.kt` (§2.4), `CallViewModel.kt`
(`selectAudioRoute`). Keep the router free of coroutines except the `MutableStateFlow`; the
listeners write to it on the main executor. No unit test for the router (Android `AudioManager`),
the policy test is the coverage. Run `/simplify` (concurrency trigger). Run `/code-review` on
the step-2+3 diff before commit: this touches coroutine scoping in a foreground service.
**(step-2)** The policy exists and is the contract to honour:
- Call `CallAudioRoutePolicy.routeOf(device.type)` directly — it takes the raw `AudioDeviceInfo.TYPE_*`
  int, so the router needs no mapping of its own.
- `previousAvailable` is `emptySet()` for the first `resolve` at call start — that is how a headset
  already connected wins through rule 1. After each run, set `previous = available`. When the result
  differs from `userPick`, null `userPick` (rule 1's stated side effect; the policy cannot do it).
- `resolve` is total: with `available` empty it returns `current ?: EARPIECE` rather than throwing.
  The router must still not call `setCommunicationDevice` for a route absent from the live list (§4).
- `CallAudioRoute`'s KDoc promises `availableRoutes` is display-ordered by `ordinal`; nothing sorts
  yet, so `RouteState.available` must be built `sortedBy { it.ordinal }`.
- Still present for this step to delete: `ACTION_TOGGLE_SPEAKER`, `CallService.toggleSpeaker()`
  (`:528`, now toggling EARPIECE⇄SPEAKER through `updateAudioRoutes` **and** still writing
  `isSpeakerphoneOn`), and `previousSpeakerState`. `CallStateHolder.updateAudioRoutes` already exists.

**Approach**
1. New `data/call/CallAudioRouter.kt` — `RouteState`, a `MutableStateFlow`, the two listeners, and
   `start()` / `select()` / `stop()`, each `synchronized` over the `previousAvailable` + `userPick`
   bookkeeping (they are read-modify-written from the main executor *and* from the WebRTC and
   service threads). `setCommunicationDevice` is always given a device re-queried in the same
   critical section (§4 trap 1); `current` comes only from `OnCommunicationDeviceChangedListener`.
2. `CallService.kt` — `ACTION_SELECT_AUDIO_ROUTE` + `EXTRA_AUDIO_ROUTE`, a `sendSelectRoute()`
   companion helper (today's `sendAction` carries no extras); router construction, `state`
   collector and proximity rule inside the renamed `startAudioSession()` /
   `stopAudioSession()`; delete `toggleSpeaker()`, `ACTION_TOGGLE_SPEAKER`, `previousSpeakerState`
   and the unconditional `acquireProximityWakeLock()` at ICE-CONNECTED.
3. `CallViewModel.kt` — `toggleSpeaker()` → `selectAudioRoute(route: CallAudioRoute)`.
4. `CallScreen.kt` — callsite only: `onToggleSpeaker` becomes a lambda that picks the other of
   EARPIECE/SPEAKER. `ConnectedContent` keeps its 9 params; the route button and sheet are step 4's.
5. Tests: new `CallAudioRouterTest.kt`. **Departure from the step's "no unit test for the router"**:
   `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:231`) plus MockK over
   `AudioManager`/`AudioDeviceInfo`/`Handler` makes the router testable as plain JUnit, no
   Robolectric — the bookkeeping this step adds (pick clearing, `previousAvailable` advance,
   idempotent start/stop) is exactly what the policy test cannot reach.
6. Gate: `:app:testFirebaseDebugUnitTest`, `assembleFirebaseDebug`, then `/simplify` +
   `/code-review` over the step-2+3 diff.

Two things the code contradicts, both handled here rather than escalated: `requestAudioFocus()` runs
on **both** ICE CONNECTED and COMPLETED, so today it overwrites `previousAudioMode` with
`MODE_IN_COMMUNICATION` (pre-existing leak of the call audio mode) and would start a second router
and a second collector — `startAudioSession()` becomes idempotent, and so does `CallAudioRouter.start()`.
And §2.3's "constructed with `AudioManager` + main `Executor`" cannot be literal:
`registerAudioDeviceCallback` takes a `Handler`, not an `Executor`, so the router takes the main
`Handler` and derives the executor from it — one parameter, and the two listeners are guaranteed the
same thread.

**Shipped** `81fab393` (2026-09-20) — tier: strong, tagged strong. skills: simplify, code-review, app-ui-design. Reviewer models: simplify: sonnet (reuse), opus (simplification), opus (efficiency), opus (altitude); code-review: opus (standards), opus (spec).
Departures (for sign-off):
- **New file not in the step's list: `data/call/ProximityLock.kt` + `ProximityLockTest.kt`.** §2.4 only
  asked the collector to call `acquire`/`releaseProximityWakeLock`. `/simplify`'s altitude agent
  showed why that is the wrong owner: those two methods are unsynchronized, and the collector runs on
  `serviceScope` (IO) while teardown arrives from the main and WebRTC threads, so the rule "proximity
  follows the playing route" was safe only by caller discipline and was untestable. The lock now owns
  its own monitor and latches on `shutdown()`. Annotated on step 5 for `FEATURE-MAP`.
- **Two bugs fixed that the step did not name.** (a) `requestAudioFocus()` ran on both ICE CONNECTED
  *and* COMPLETED, so the second run saved `MODE_IN_COMMUNICATION` as the mode to restore after the
  call and would now also have leaked a second router, a second listener pair and a second collector;
  `startAudioSession()` is idempotent, and it and `stopAudioSession()` are mutually exclusive under
  `audioSessionLock` so that hanging up as ICE connects cannot interleave into a half-started session.
  (b) The proximity wake lock's re-acquire guard tested nullness, so a call outliving the 1-hour
  `acquire()` timeout lost screen blanking permanently; it tests `isHeld` now, with a regression test.
  Neither is testable end-to-end on the JVM (Android `Service`), which is why (a) has no test of its own.
- **§2.3's `Executor` is a `Handler`.** `registerAudioDeviceCallback` has no `Executor` overload, so
  the router takes the main-looper `Handler` and derives the listener executor from it.
- **Unit tests for the router, which the step declined.** `unitTests.isReturnDefaultValues` plus MockK
  over `AudioManager`/`AudioDeviceInfo`/`Handler` makes it plain-JUnit testable with no Robolectric.
  14 router cases + 7 proximity cases, covering exactly what the policy test cannot see: which device
  object is handed to `setCommunicationDevice`, when a user pick is spent, start/stop idempotency.
- **`RouteState.current` and `CallStateHolder.updateAudioRoutes`'s `current` are nullable.** Both
  review axes caught the same defect: publishing `EARPIECE` as a placeholder claims the OS reported a
  route it has not, which on a tablet publishes a current route absent from `available`, and lets the
  policy's rule 3 pin a route the device never reached. Null now means "not reported yet"; the UI keeps
  the route it is showing, and only the proximity rule reads the null as EARPIECE — deliberately, so an
  ordinary call still blanks the screen from the first second the way it always did, even if the OS
  never calls back because the earpiece was already selected.
- **Declined, with the reason in `TECH_DEBT.md`:** the router holds its monitor across AudioService
  binder calls. Narrowing it re-opens the read-modify-write race it exists for; the real fix is moving
  the router onto the main looper, which is not this step.
- **Not applied from `/code-review`:** its suggestion to fold rule 1's pick-clearing into the policy by
  returning a `Resolution` — that changes §2.2's `resolve(...): CallAudioRoute` signature, a §2 design
  point, and the step-2 annotation explicitly assigns the side effect to the router. Also not applied:
  posting `startAudioSession()` to the main looper to keep binder calls off the WebRTC thread, which
  would let teardown overtake it.

### Step 4 — UI (`feat(call)`, **this** commit carries the CHANGELOG `Added` entry) — skills: app-ui-design
Files: `CallScreen.kt`, `strings.xml`, possibly `CallControlButton.kt` if the highlighted
state wants a shared helper. Load `app-ui-design` first. Compose test not required (UI-only,
no logic), but a Robolectric smoke test that the sheet lists three rows when
`availableRoutes` has three entries is cheap and welcome — follow `ChatListItemUiTest` shape.
CHANGELOG: "**Calls can use a Bluetooth or wired headset.** …" under `Added`, and the minSdk
line from step 1 stays under `Removed`.
**(step-1)** That section is now `## [UNRELEASED] [2.0.0] — 2026-09-20` — step 1 took the major
bump (see its Shipped departures), so this step appends to it and does **not** raise the version
again. Any Robolectric test this step adds pins `@Config(sdk = [31], …)`, not `[29]`.
**(step-2)** `ConnectedContent` still has its original 9 params; `CallScreen.kt:98` now feeds it
`isSpeakerOn = uiControls.audioRoute == CallAudioRoute.SPEAKER`. That derived line, the
`isSpeakerOn`/`onToggleSpeaker` params and the third `CallControlButton` are what this step replaces
with `audioRoute` / `availableRoutes` / `onSelectRoute`.
**(step-3)** The ViewModel method is now `selectAudioRoute(route: CallAudioRoute)` — `toggleSpeaker()`
is gone. `CallScreen.kt` hoists `val isSpeakerOn` above `ConnectedContent` and passes an
`onToggleSpeaker` lambda that calls `selectAudioRoute(EARPIECE or SPEAKER)`; both the val and the
lambda are what §2.5's route button replaces.
**(step-3 /code-review)** Two things the route button must survive, neither true before step 3:
- `availableRoutes` is now whatever the OS actually offers and **can be empty** (`CallUiControls`'
  two-route default only holds until the first `updateAudioRoutes`). `size <= 2` must therefore be
  the direct-toggle branch including at size 0 and 1 — do not index into the list.
- `audioRoute` lags a tap by up to ~1 s on Bluetooth (it is the OS's reported route, not the pick,
  §2.3). The button must render `audioRoute` and never latch the tapped route optimistically; the
  sheet's check mark moves when the audio does. `audioRoute` may also briefly be a route that is
  not in `availableRoutes`, so do not assert membership.

**Approach**
1. `app/src/main/res/values/strings.xml` — the five §2.5 labels plus a `call_route_button` content
   description per route (the button's description must name the current route, and the existing
   hard-coded "Mute"/"Hang up" stay as they are, per §2.5).
2. New `ui/call/CallAudioRouteSheet.kt` — `internal` `CallAudioRouteSheet(current, available,
   onSelect, onDismiss)` as a `ModalBottomSheet` in the `SnoozePickerSheet.kt` shape (title +
   rows), plus `internal fun routeIcon(route)` / `routeLabel(route)` shared with the button.
   Departure from the step's file list: a new file rather than more of `CallScreen.kt` — every
   other sheet in `ui/` is its own file, and `internal` is what makes the smoke test reachable.
3. `CallScreen.kt` — delete the hoisted `val isSpeakerOn` and the `onToggleSpeaker` lambda left by
   step 3; `ConnectedContent` swaps `isSpeakerOn`/`onToggleSpeaker` for `audioRoute`,
   `availableRoutes`, `onSelectRoute` (10 params, under the ~15 ceiling, so no `*Callbacks` class).
   The third `CallControlButton` becomes the route button; `available.size <= 2` toggles
   EARPIECE⇄SPEAKER directly without indexing the list, otherwise a `remember { mutableStateOf }`
   opens the sheet. Highlighted iff `audioRoute != EARPIECE`. Nothing latches the tap.
4. `CallControlButton.kt` — untouched unless the highlight wants a helper; it does not, the
   two-colour expression already lives at the call site for the mute button.
5. Tests: new `ui/call/CallAudioRouteSheetUiTest.kt`, `@Config(sdk = [31], …)` per the step-1
   annotation, in `ChatListItemUiTest` shape — the sheet lists one row per available route, the
   check mark sits on `current`, a row tap reports that route; plus `ConnectedContent`-level
   coverage that `size <= 2` toggles directly and `size == 3` opens the sheet.
6. Gate: `:app:testFirebaseDebugUnitTest` → `assembleFirebaseDebug`; `changelog-release` for the
   bump decision (expected: none — step 1 already took 2.0.0), then the `Added` entry.

Nothing in the code contradicts the step's spec. The `(step-3 /code-review)` annotations match what
is there: `availableRoutes` can be empty and `audioRoute` can sit outside it, and both branches
above are written not to index or assert membership.

**Shipped** `b1fbb029` (2026-09-20) — tier: mid, tagged mid. skills: app-ui-design, changelog-release. Reviewer models: none.
The CHANGELOG `Added` entry is in that same commit; only its `` (`b1fbb029`) `` hash suffix —
unknowable before the commit existed — was appended in this `docs(plan)` commit. No version bump:
`feat` is below the major the section already carries from step 1.
Departures (for sign-off):
- **New file not in the step's list: `ui/call/CallAudioRouteSheet.kt`** (the route button, the
  sheet, its row list, and the shared `routeIcon`/`routeLabel` mapping) plus
  `CallAudioRouteUiTest.kt`. Every other sheet in `ui/` is its own file, and `internal` is what
  makes the rows reachable from a test. `CallControlButton.kt` was left untouched — the
  highlight is a two-colour expression at the call site, exactly as the mute button already does it.
- **The route control is its own composable, not inlined in `ConnectedContent`.** The Approach
  said the tests would drive `ConnectedContent`; they cannot. It is `private` and runs an
  unbounded `LaunchedEffect { while (true) { …; delay(1000) } }` elapsed-time loop, which under
  `createComposeRule()` fights `mainClock` rather than testing anything. `CallAudioRouteButton`
  owns the sheet state and the `size <= 2` rule; the tests target it and `CallAudioRouteList`.
  `ConnectedContent` keeps the 10 params §2.5 asked for and just calls it.
- **Not in §2.5: the sheet closes itself when `availableRoutes` drops to two or fewer** — a
  headset unplugged with the sheet open would otherwise leave a two-row sheet sitting over the call.
- **Six strings, not the five §2.5 named.** The sixth, `call_route_button`, is the format string
  for the button's content description ("Call audio: Bluetooth"), which §2.5 requires to name the
  current route. §2.5 fixed the keys, not the copy; the labels are Phone / Speaker / Bluetooth /
  Headphones, and the sheet title is "Call audio".
- **Earpiece and speaker share `VolumeUp` in the sheet rows too**, not only on the button as §2.5
  specified. Rows are told apart by their label and the check mark; a second volume-ish icon would
  have been invented copy, not a distinction the user needs.
- **The three-route test composes a real `ModalBottomSheet` under Robolectric (`sdk = [31]`) and
  passes** — the first test in this repo to do so. Noted here because the previous absence of any
  such test reads as a limitation, and it is not one. The row list is still hoisted into
  `CallAudioRouteList` so row assertions do not depend on a dialog window.
- No skill beyond the floor was triggered by the diff: 477 changed lines, no `data/crypto`,
  `data/worker`, `di/`, no ViewModel (`CallViewModel.selectAudioRoute` already existed from step 3).
  `changelog-release` was run for the bump decision, which is "none".

### Step 5 — docs
- `docs/FEATURE-MAP.md` § Voice Call: add `CallAudioRouter.kt`, `CallAudioRoutePolicy.kt`,
  `CallAudioRoutePolicyTest.kt`; refresh `last-verified`.
  **(step-3)** Three more files than that list names: `ProximityLock.kt` (new, see the step-3
  Shipped departures), `CallAudioRouterTest.kt` and `ProximityLockTest.kt`. The existing
  `CallControlButton.kt | Mute / speaker control` row is stale from step 4 onwards.
  **(step-4)** Two more: `ui/call/CallAudioRouteSheet.kt` (the route button, the sheet, the
  shared icon/label mapping) and its `CallAudioRouteUiTest.kt`. The stale
  `CallControlButton.kt` row should now read "Mute / hang up / route control" — the speaker
  toggle it names no longer exists.
- `docs/BACKLOG.md` § Pending on-device verification: Bluetooth path is **untestable on the
  emulator** (no BT stack). Needs the phone + a headset: (a) headset connected before the
  call → audio on headset from first second, (b) connect mid-call → auto-switch, (c) disconnect
  mid-call → earpiece, screen-off proximity resumes, (d) sheet shows three rows, pick each,
  (e) after hangup, Spotify plays through A2DP at full quality (proves `clearCommunicationDevice`).
- `docs/BACKLOG.md` § Rich Media & Communication: option B (core-telecom) entry with its trigger,
  and the "show Bluetooth product name (needs `BLUETOOTH_CONNECT`)" nice-to-have.
- `docs/GOTCHAS.md`: only if step 1 hit something non-obvious (Robolectric vs minSdk, Roborazzi
  re-render). Otherwise nothing.
  **(step-1)** Nothing owed here: the Robolectric-sdk-≥-minSdk rule went into the existing
  `docs/PATTERNS.md` trap it belongs to, and the Roborazzi finding into `TECH_DEBT.md`.
- `TECH_DEBT.md`: nothing expected.
  **(step-3)** One entry landed with step 3: `CallAudioRouter` holds its monitor across AudioService
  binder calls (declined, with the main-looper fix as its revisit shape).
  **(step-1)** Already landed in `3b30b8f7`: one new entry for the stale Roborazzi baselines (see
  the step-1 Shipped departures), plus a parenthetical on the existing WorkManager-latency entry
  noting the now-unreachable arm of `OutboxScheduler.runsExpedited`. Nothing more owed unless
  steps 2–4 add their own.
- **(step-1)** `.claude/skills/app-ui-design/SKILL.md:49` still quotes `@Config(sdk = [29], …)` as
  the shape for a new Robolectric Compose test — that pin no longer boots. The step-1 session was
  denied write permission under `.claude/`, so it must be changed to `[31]` here, by hand or by a
  session that can write there. Left stale it will reintroduce a failing pin in the next UI test
  anyone writes.
- Local memory: update `project_shipped_plans_archive` / add a pointer; move this plan to
  `docs/plans/done/` once the device pass is recorded.

**Approach**
1. `docs/FEATURE-MAP.md` § Voice Call — add the eight files the four steps created
   (`CallAudioRoutePolicy.kt`, `CallAudioRouter.kt`, `ProximityLock.kt`,
   `ui/call/CallAudioRouteSheet.kt`, and the four tests
   `CallAudioRoutePolicyTest`, `CallAudioRouterTest`, `ProximityLockTest`,
   `CallAudioRouteUiTest`), rewrite the stale `CallControlButton.kt` row to
   "Mute / hang up / route control", and refresh `last-verified` to 2026-09-20.
   All eight paths verified present on disk before the edit.
2. `docs/BACKLOG.md` § Pending on-device verification — new item at the top of the section
   with the five checks (a)–(e) the step lists, stating the emulator has no BT stack.
3. `docs/BACKLOG.md` § Rich Media & Communication — a new "Call audio routing follow-ups"
   item carrying §0's two deferrals: option B (core-telecom) with its trigger, and the
   Bluetooth product name behind `BLUETOOTH_CONNECT`.
4. `.claude/skills/app-ui-design/SKILL.md:49` — `sdk = [29]` → `sdk = [31]`. The file is
   git-tracked; if the write is denied here too, that is the one thing this step cannot land
   and it is reported as a departure, not a block.
5. Beyond the step's list, three docs describe a control that no longer exists (found by
   grepping `speaker` / `bluetooth` / `headset` across `docs/`): `docs/SPEC.md:53`
   ("toggle speakerphone"), `docs/ARCHITECTURE.md` §12's `data/call` and `ui/call` package
   lines (four new files missing), and §5's *Call Architecture Details*, which never mentions
   who owns the audio session. All three are corrected here — a docs step is judged on whether
   the docs match the code. `docs/TESTING.md` says nothing about calls and `docs/BACKLOG.md`
   carried no Bluetooth-in-calls item to prune. Nothing in `docs/PATTERNS.md` /
   `docs/GOTCHAS.md` / the UI skill claims `ModalBottomSheet` is untestable under Robolectric,
   so step 4's note routes nowhere.
6. Nothing owed to `docs/GOTCHAS.md` or `TECH_DEBT.md` (both annotations above say so; steps
   2–4 added none). No local-memory write: a runner session has no memory store. The plan is
   **not** moved to `docs/plans/done/` — its own text gates that on the device pass, which is
   exactly what item 2 above schedules.
7. Gate: docs-only diff, no production code, so `:app:testFirebaseDebugUnitTest` +
   `assembleFirebaseDebug` are run once as the standing gate and nothing more; no test is added,
   no CHANGELOG entry (docs-only is on `changelog-release`'s skip list), commit prefix `docs`.

Nothing in the repo contradicts the step's spec.

**Shipped** `5032dd91` (2026-09-20) — tier: mid, tagged mid. skills: none. Reviewer models: none.
Departures (for sign-off):
- **`.claude/skills/app-ui-design/SKILL.md:49` is still stale** — the write was denied here exactly
  as it was for step 1, and per the runner's rules it was attempted once, not worked around. The
  change a human must make by hand is one line: `@Config(sdk = [29], application = …)` →
  `@Config(sdk = [31], application = …)`. Left as is, the next Robolectric Compose test anyone
  writes from that skill gets a pin below `minSdk` and will not boot.
- **Four files beyond the step's list**, all found by grepping `docs/` for the control that no
  longer exists: `docs/SPEC.md:53` said in-call controls were "mute and toggle speakerphone";
  `docs/ARCHITECTURE.md` §12's `data/call` and `ui/call` package lines were missing the four new
  files, and §5 *Call Architecture Details* never said who owns the audio session — it now names
  `startAudioSession()`/`stopAudioSession()`, the policy, and the proximity rule (method names
  verified against `CallService.kt:590/626`). `FEATURE-MAP`'s `CallService.kt` role line gained
  the audio session for the same reason. A docs step is judged on whether the docs match the code.
- **The on-device list is longer than the step's (a)–(e).** (d) now says the Bluetooth check mark
  lags the tap by up to ~1 s and that this is the SCO link coming up, **not** a bug to file — §4
  warns about exactly that non-bug. A new (f) covers the wired/USB-C headset, which none of (a)–(e)
  exercised, and records that an A2DP-only headphone correctly never appears as a route.
- **Nothing owed to `docs/GOTCHAS.md`, `docs/PATTERNS.md` or `TECH_DEBT.md`.** Checked rather than
  assumed: `docs/TESTING.md` says nothing about calls; `docs/BACKLOG.md` carried no
  Bluetooth-in-calls item to prune; and nothing in `PATTERNS.md`/`GOTCHAS.md`/the UI skill claims a
  `ModalBottomSheet` is untestable under Robolectric, so step 4's note about being the first such
  test corrects no written claim and routes nowhere.
- No CHANGELOG entry and no version bump (docs-only is on `changelog-release`'s skip list), no test
  added, and no skill triggered by the diff: 95 changed lines, no production file at all.
- The plan is **not** moved to `docs/plans/done/`. Its own step-5 text gates that on the device
  pass, which is what the new BACKLOG item schedules. Local memory was not touched: a runner
  session has no store, and every fact here went to a tracked doc.

## 4. Traps (read before step 3)

- `setCommunicationDevice()` returns `false` and does nothing if the device is not in
  `availableCommunicationDevices` *at that moment*. Always re-query right before selecting.
- Bluetooth SCO takes ~1 s to come up. `currentCommunicationDevice` (and the UI) lags the
  request; that is correct, do not "fix" it by setting the UI from the tap.
- An A2DP-only headphone (music profile, no hands-free) never appears in
  `availableCommunicationDevices`. That is expected, not a bug — the stock dialer can't use it either.
- `clearCommunicationDevice()` must run on every exit path (`cleanup()` covers hangup, remote
  hangup, timeout, ICE failure, `onDestroy`). Missing it leaves the next media app on the
  8 kHz SCO link.
- Listener executor must be the main executor. `serviceScope` is `Dispatchers.IO`; do not pass
  it as the executor.
- `AudioDeviceCallback` fires once on registration with the current list — the initial snapshot
  and that callback can both run the policy; the policy is idempotent (rule 3), so that is fine.
- Proximity wake lock follows the *actual* route from the listener, not the pick, otherwise the
  screen blanks during the SCO ramp while the audio is still on the earpiece.
