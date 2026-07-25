# Prominent, Customizable Timer Alarm

## Context

The `.timer` alarm is easy to miss. Two separate complaints were raised; the first is
already fixed:

1. ~~Tapping a "Timer ended" notification opened the chat but didn't jump to the timer~~
   — shipped in `09aabc0`: the notification intent now carries
   `MainActivity.EXTRA_MESSAGE_ID`, routing through the same deep-link jump the
   reminder and reaction notifications use (scroll + 1.5s pink frame).
2. The alarm itself is overhearable. **This plan covers that.**

### What the investigation established (don't re-derive)

- **The problem is duration, not volume.** The channel already sets
  `USAGE_ALARM` audio attributes (`TimerNotificationChannel.kt:35-38`), so the sound
  rides `STREAM_ALARM`: it plays at alarm volume, survives vibrate/silent ringer mode,
  and `CATEGORY_ALARM` gets it past Do Not Disturb (alarms are DND-allowed by default).
  A notification can't exceed the user's alarm slider anyway. What's wrong is that it
  fires **once**: the ringtone plays a single time and the vibration pattern
  `[0, 1000, 500, 1000, 500]` is a ~3-second one-shot with no repeat index.
- **No stale-channel problem.** `USAGE_ALARM` has been present since the feature's
  first commit (`66540e5`, 2026-05-04) and `CHANNEL_ID` has never changed, so every
  install is already on the alarm stream. Verified via `git log --follow`.
- **Channel immutability is the central constraint.** Sound and vibration live on the
  `NotificationChannel` and are frozen at creation —
  `TimerNotificationChannel.ensureCreated` even early-returns when the channel exists.
  Changing either requires a **new channel id**; per-timer variation requires one
  channel per variant. This is why Step 1 is a spike: `FLAG_INSISTENT` is a
  per-*notification* flag, so if it does what we need, the whole channel-migration
  problem disappears.
- PocketBase flavor needs no work: `PocketBaseMessageSource.kt:252-261` throws
  `NotImplementedError("PB v0: timers deferred")` for every timer operation.

## Settled design decisions

1. **All synced** (user decision). The sender picks both urgency and sound; both phones
   ring identically. Simplest mental model for a timer two people share.
2. **Sound travels as a symbolic enum, never a URI.** A `content://` ringtone URI is
   device-local — syncing one would resolve to nothing on the recipient's phone. Each
   device maps the enum to its own local sound.
3. **Extend `timerSilent`, don't parallel it.** The new fields supersede it, but
   `timerSilent` stays readable so existing messages and older clients still behave:
   absent style ⇒ derive from `timerSilent`.
4. **Volume ramping is deferred**, not declined. It requires the foreground-service /
   own-`MediaPlayer` route (Option 2 from the discussion), and given that the sound is
   already at alarm volume it's the least valuable piece. Revisit only if Step 1 + Step 3
   still leave the alarm missable on-device.

## Steps

### Step 1 — Spike: what does `FLAG_INSISTENT` actually do on the device?

Throwaway, on-device, **not committed as-is**. Set
`notification.flags = flags or Notification.FLAG_INSISTENT` in
`TimerAlarmReceiver.postAlarmNotification`, install, fire a short timer, observe:

- Does the **sound** loop until dismissed?
- Does the **vibration** also repeat? (AOSP passes the insistent flag through to the
  vibration waveform's repeat index, which would make the existing 3s pattern a
  continuous buzz — but this is exactly the kind of thing OEMs vary on, so it must be
  confirmed rather than assumed.)

Outcome decides Step 3's shape:
- **Both loop** → no channel migration at all. Keep `timer_alarms`.
- **Sound only** → Step 3 also creates `timer_alarms_v2` with a long pattern array and
  deletes the old channel id.

Also check while there: is `USE_FULL_SCREEN_INTENT` actually granted? It's declared
(`AndroidManifest.xml:16`), but on Android 14+ it is no longer auto-granted for
sideloaded apps unless the system classifies the app as an alarm/calling app — so the
current lock-screen takeover may already be silently degraded.

### Step 2 — Data foundation: synced alarm style + sound

- New domain enums: `TimerAlarmStyle { SILENT, NORMAL, INSISTENT }` and
  `TimerAlarmSound { DEFAULT_ALARM, … }` (final member list decided with the Step 4 UI).
- Plumb both through, mirroring the existing `timerSilent` path exactly:
  - `domain/model/Message.kt:56`
  - `data/local/entity/MessageEntity.kt:56,93,132` — **bump `AppDatabase` version 23 → 24**
  - `data/remote/source/RawMessage.kt:52`
  - `FirestoreMessageSource.kt:470-473` (write map) and `:578-583` (read map)
  - `MessageRepositoryImpl.kt` — six mapping sites: `216`, `296`, `1364`, `1382`,
    `1539`, `1592`; plus the `sendTimerMessage` signature
- **Back-compat both directions**: absent/unknown style ⇒ `timerSilent` decides
  (`SILENT` / `NORMAL`); unknown sound name ⇒ `DEFAULT_ALARM`. An older client writing
  only `timerSilent` must still ring correctly, and a newer client's enum name must not
  crash an older one.
- Tests: mapping round-trip, legacy-fallback (`timerSilent = true`, no style), and
  unknown-enum-name degradation.

### Step 3 — Alarm delivery honours style + sound

- `TimerAlarmScheduler`: carry style + sound in the intent extras alongside the existing
  `EXTRA_SILENT` (keep it, or replace and derive — decided in Step 2).
- `TimerAlarmReceiver`: apply `FLAG_INSISTENT` for `INSISTENT`, pick the channel for the
  chosen sound, and **add a "Dismiss" action** — mandatory, see R2.
- Channel work per Step 1's outcome (one channel per sound option, since sound is
  frozen per channel; created up-front in `FireStreamApp`).
- **Fix the boot-restore losses** (`BootCompletedReceiver.kt:102-107`): the re-arm passes
  `otherUserId = null` and no `silent`, so today a reboot makes a silent timer ring and
  strips the deep link entirely — which would defeat the fix shipped in `09aabc0`.
  `TimerBootAction.Schedule` must carry `otherUserId`, style, and sound; `BootRestoreLogic`
  and its test extend accordingly.
- **Re-alert**: if the notification is still not dismissed after 60s, re-post it once or
  twice via a follow-up alarm, then stop. Cheap given `TimerAlarmScheduler` exists, and it
  converts "I missed the one buzz" into "it came back".

### Step 4 — Widget UI: pick style + sound when setting a timer

Runs in parallel with Step 3 — it only needs Step 2's enums.

- Replace the silent `Switch` in `TimerPickerWidget.kt:158-159` with a style selector plus
  a sound row. Invoke the `app-ui-design` skill; keep the picker compact, it mounts inside
  the composer.
- `TimerSetWidgetState.kt:41-44` (`silent` → style + sound, update `reset()`),
  `CommandPayload.Timer` (`TimerPickerWidget.kt:94`), `ChatViewModel.sendTimerMessage`
  (`ChatViewModel.kt:337`).
- `TimerMessageBubble` already shows a `NotificationsOff` icon for silent timers
  (`:145-153`, `:169-177`) — extend that to indicate an insistent timer too.
- Widget-state tests (existing `TimerSetWidgetState` test pattern).

### Step 5 — Docs + closeout

`CHANGELOG.md` (feat → minor bump), `docs/FEATURE-MAP.md` if files were added,
`docs/GOTCHAS.md` for anything machine-independent the spike turns up (channel
immutability and the insistent-vibration finding both qualify), plan archived to
`.claude/plans/done/`.

## Order

`1 → 2 → 3+4 → 5`

Step 1 gates everything: its outcome decides whether Step 3 needs a channel migration.
Steps 3 and 4 both depend only on Step 2 and run in parallel. Step 5 waits for both.

## Risks

- **R1 — Channel immutability.** If Step 1 says a new channel is needed, the old
  `timer_alarms` id must be *deleted*, or existing installs keep the old settings and
  nothing appears to change. Test by upgrading over an existing install, not a clean one.
- **R2 — Insistent ringing with no off switch.** A looping alarm the user can't
  obviously stop is worse than one they miss. The Dismiss action in Step 3 is a hard
  requirement, and `setAutoCancel(true)` must stay so a tap also silences it.
- **R3 — Boot restore drops context** (pre-existing, see Step 3). Silent timers ring
  after a reboot and the notification can't deep-link. Needs a regression test in
  `BootRestoreLogicTest`.
- **R4 — Cross-version sync.** Two phones on different app versions must not break each
  other: unknown enum name ⇒ default, absent field ⇒ `timerSilent`. Covered by Step 2's
  tests.
- **R5 — Room 23 → 24 wipes the local cache** via `fallbackToDestructiveMigration()`.
  Expected and normal here, but worth stating: messages re-sync from Firestore.
- **R6 — `USE_FULL_SCREEN_INTENT` may not be granted** on Android 14+ sideloaded
  installs (Step 1 checks). If it isn't, the lock-screen takeover needs a settings
  deep-link prompt — scope that separately rather than folding it in.

## Verification

- **Step 1** — on-device: does it loop, and does the vibration loop with it?
- **Step 3** — fire a timer of each style; confirm insistent keeps ringing until
  dismissed and normal still fires once. Reboot with a running silent timer and confirm
  it stays silent and still deep-links. Confirm the re-alert fires and then stops.
- **Step 4** — set each style + sound from the widget, confirm it rings that way on
  *both* devices (the point of the synced model).
- **Full pass** — vibrate-only ringer, and DND: the alarm should still sound in both
  (the `USAGE_ALARM` / `CATEGORY_ALARM` behaviour documented above).
- Every step: `./gradlew test assembleDebug` before commit, per CLAUDE.md.
