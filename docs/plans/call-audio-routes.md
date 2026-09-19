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

### Step 3 — router + service wiring (`feat(call)`, no CHANGELOG yet) — skills: simplify, code-review; model: strong
Files: new `data/call/CallAudioRouter.kt`, `CallService.kt` (§2.4), `CallViewModel.kt`
(`selectAudioRoute`). Keep the router free of coroutines except the `MutableStateFlow`; the
listeners write to it on the main executor. No unit test for the router (Android `AudioManager`),
the policy test is the coverage. Run `/simplify` (concurrency trigger). Run `/code-review` on
the step-2+3 diff before commit: this touches coroutine scoping in a foreground service.

### Step 4 — UI (`feat(call)`, **this** commit carries the CHANGELOG `Added` entry) — skills: app-ui-design
Files: `CallScreen.kt`, `strings.xml`, possibly `CallControlButton.kt` if the highlighted
state wants a shared helper. Load `app-ui-design` first. Compose test not required (UI-only,
no logic), but a Robolectric smoke test that the sheet lists three rows when
`availableRoutes` has three entries is cheap and welcome — follow `ChatListItemUiTest` shape.
CHANGELOG: "**Calls can use a Bluetooth or wired headset.** …" under `Added`, and the minSdk
line from step 1 stays under `Removed`.

### Step 5 — docs
- `docs/FEATURE-MAP.md` § Voice Call: add `CallAudioRouter.kt`, `CallAudioRoutePolicy.kt`,
  `CallAudioRoutePolicyTest.kt`; refresh `last-verified`.
- `docs/BACKLOG.md` § Pending on-device verification: Bluetooth path is **untestable on the
  emulator** (no BT stack). Needs the phone + a headset: (a) headset connected before the
  call → audio on headset from first second, (b) connect mid-call → auto-switch, (c) disconnect
  mid-call → earpiece, screen-off proximity resumes, (d) sheet shows three rows, pick each,
  (e) after hangup, Spotify plays through A2DP at full quality (proves `clearCommunicationDevice`).
- `docs/BACKLOG.md` § Rich Media & Communication: option B (core-telecom) entry with its trigger,
  and the "show Bluetooth product name (needs `BLUETOOTH_CONNECT`)" nice-to-have.
- `docs/GOTCHAS.md`: only if step 1 hit something non-obvious (Robolectric vs minSdk, Roborazzi
  re-render). Otherwise nothing.
- `TECH_DEBT.md`: nothing expected.
- Local memory: update `project_shipped_plans_archive` / add a pointer; move this plan to
  `docs/plans/done/` once the device pass is recorded.

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
