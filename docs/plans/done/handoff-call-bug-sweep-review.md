# Handover: the call bug sweep, reviewed

Status: the sweep and its review merged to `main` in pull request #78 and shipped in v1.40.2. The owner settled both open decisions, listed at the end of this file. Only the phone checks are left: nothing has run on a phone yet. Updated 2026-10-08.

## What is left

1. Run the phone checks in `docs/BACKLOG.md`, "Calls — teardown, ringing, answering and the call log (2026-10-08)".

## Where everything is

Each of these is the source of truth. This handover does not repeat them.

- Commits: `git log --oneline 66eee5d..700783c`, merged in pull request #78. There is one commit per area, and each message says what it fixes and why:
  - `aec1caa` ringing
  - `6646712` the call log and Calls-tab names
  - `53a2958` call state and the call screen
  - `eeb706a` `CallService` threading and WebRTC releases
  - `12d14a3` Calls-tab names that survive a contacts reload (from the review)
  - `9cdf267` outgoing-call setup in `CallViewModel` (from the review)
  - `219499e` `CallService` stops only when no newer start is queued (from the review)
  - `5c3dd0f2` the Calls tab says "Declined" the way the chat does (the owner's decision, after the merge)
  - The other commits are docs and CHANGELOG hashes.
- User-visible summary: the call entries under `[UNRELEASED] [1.40.2]` in `CHANGELOG.md`.
- Problems left unfixed, with reasons: `TECH_DEBT.md`, "Calls — known problems left unfixed".
- Checks only a phone can do: `docs/BACKLOG.md`, "Calls — teardown, ringing, answering and the call log (2026-10-08)".
- Traps found on the way: `docs/GOTCHAS.md`. They cover the Firestore own-write echo, `catch (e: Exception)` catching cancellation, WebRTC's factory owning its signaling thread, `stopSelf()` without a start id, and `advanceUntilIdle()` leaving `backgroundScope` work unrun.
- Files: `docs/FEATURE-MAP.md`, "Voice Call".

## Spec

These are the requirements the fixes must meet. The user asked to inspect the call feature for bugs and fix them.

1. A finished call's `Ended` state counts as "not in a call". Dictation works after a call.
2. Every call starts with default `CallUiControls`, because its new audio track is enabled. Controls survive the state changes within one call.
3. `toggleMute` and `updateAudioRoutes` never lose each other's write. The audio track gets exactly the mute value the UI shows.
4. A received call counts as answered only if it connected (duration > 0). A call the caller cancelled while it rang is missed. The chat bubble and the Calls tab use the same rule. Own-call labels are unchanged.
5. Calls-tab names update when contacts load after the call log, and when a non-contact's profile arrives.
6. Placing a call never opens on the previous call's `Ended`, including while the permission prompt is up. `Ended` makes the screen finish itself after 1.5 s.
7. No second call is placed over an ongoing one.
8. Call setup survives the following:
   - A rotation must not cancel the call's creation.
   - A user who left during setup must not leave the callee ringing.
   - A failed `createCall` ends on a visible state.
   - A refused foreground-service start must not crash.
9. The in-app Answer button goes through the microphone permission check. Without `RECORD_AUDIO`, `startForeground(MICROPHONE)` throws on Android 14+.
10. Recents does not replay the intent that placed or answered a call. A finished call screen leaves Recents.
11. Incoming calls ring on the ringtone stream and vibrate until answered, declined or ended. The silent channel is replaced, because a channel's sound is frozen when it is created.
12. Teardown never runs on the WebRTC signaling thread, and never on two threads at once.
13. The `PeerConnection` is disposed, not only closed. The factory's audio device module is released.
14. The end-of-call writes outlive the service's scope: `endCall`, `declineCall`, and `logCallMessage` with its chat preview.
15. The call timer does not restart on COMPLETED or after an ICE reconnect.
16. An unanswered outgoing call is logged as "timeout", not "remote_hangup".
17. A second start while a call is active does not reuse its connection. A call is answered once, and not after it stopped ringing.
18. A late ICE CONNECTED does not overwrite `Ended`.

Out of scope: everything in the TECH_DEBT entry named above.

## How the work was verified

- Every fix outside `CallService` has a regression test, written first and seen failing against the old code.
- `CallService` has no JVM seam. No test covers its threading, its dispose order, its end-of-call writes or how it stops; review and the phone checks cover them.
- The full gate passed on the final tree: `:app:testFirebaseDebugUnitTest` (2245 tests, 0 failures) and `assembleFirebaseDebug`.
- Each code commit from the review was checked on its own tree with the call, calls and architecture test packages.
- Nothing has run on a phone.

## Review

- Four reviewers read the sweep: the `code-review` skill's Standards and Spec axes, and two adversarial correctness passes, one on `CallService` and one on call setup, call state and the call log. All but Standards ran at the strong tier.
- They confirmed these defects, now fixed:
  - Callers who are not contacts went back to "Unknown" in the Calls tab on any contacts reload or pull to refresh (`12d14a3`).
  - Call setup: Back after a rotation still rang the callee, the setup held the destroyed activity, a double tap placed two calls, a failed setup replaced a call that was ringing meanwhile, and a rotation at the microphone prompt lost the call (`9cdf267`).
  - `stopSelf()` without a start id let the next call's start run on a dying service, which never rang and left the app "in a call" (`219499e`).
- They checked these and found them sound: `CallService`'s main-thread confinement and its filter for stale callbacks, the busy guards, the dispose order (in the `stream-webrtc-android` 1.3.0 bytecode), the audio device module's release, the end-of-call writes on the application scope, every exit that stops the ring, and that nothing reads `Ended.callId`.
- The review's own fixes went through `code-review` and `simplify`. Their Spec pass caught a regression in the first version: Back without a rotation could still ring the callee, because `onCleared` comes only with `onDestroy`. `9cdf267` includes the fix.
- Findings not fixed are in the TECH_DEBT entry, each with its reason. The largest is that call state has no `Placing` value.

## Environment notes for a cloud session

- **Maven Central rate limit.** Through the proxy, Maven Central answers 429 at times; the sweep's session and the review's session both hit it. `.claude/hooks/session-start.sh` writes `~/.gradle/init.d/maven-central-mirror.gradle`, which puts Google's mirror of Maven Central first for Gradle and for the `android-all-instrumented` jars Robolectric downloads while tests run. If a 429 still appears, check that file exists.
- **Signal's Maven repo.** The environment's network policy denies `build-artifacts.signal.org` (403). It only matters if a jar from it is missing from the Gradle cache. Allow it under the environment's Network access settings if one is.
- **Gradle daemon heap.** The daemon (`-Xmx4g`) filled its old generation after about eight builds and slowed to a crawl. Run `./gradlew --stop` and start again.
- **Issue tracker.** `docs/agents/issue-tracker.md` does not exist. The `code-review` skill mentions it; this work has no issue.

## Decisions

The owner settled both on 2026-10-08.

- Incoming calls keep ringing with the default ringtone. The silent channel came with the first `CallService` commit, `4b51c28f`, which gives no reason for it.
- A declined call reads "Declined" in the Calls tab as it does in the chat, for both people (`5c3dd0f2`). `CallLogType` replaced `CallDirection` as the one rule both screens label a call from.
