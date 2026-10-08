# Handover: review the call bug sweep

Status: the fixes are committed and pushed on branch `ccr-80d563e8-ahtbkb`, on top of `66eee5d` (main). No review of the fixes has finished. Written 2026-10-08.

## What the next session does

1. Run the `code-review` skill. The fixed point is `66eee5d`, so the diff is `git diff 66eee5d...HEAD` on this branch. The spec is the "Spec" section below; there is no issue.
2. Add an adversarial correctness pass on `CallService` at the strong tier. CLAUDE.md asks for one on any diff that touches coroutine scoping, and the `code-review` skill only checks standards and spec.
3. Fix each confirmed finding test-first (CLAUDE.md, Change Safety), run the gate, and push to the same branch.

## Where everything is

Each of these is the source of truth. This handover does not repeat them.

- Commits: `git log --oneline 66eee5d..ccr-80d563e8-ahtbkb`. There is one commit per area, and each message says what it fixes and why:
  - `aec1caa` ringing
  - `6646712` the call log and Calls-tab names
  - `53a2958` call state and the call screen
  - `eeb706a` `CallService` threading and WebRTC releases
  - `c9da7f2` docs
  - `20b304f` CHANGELOG hashes
- User-visible summary: the nine call entries under `[UNRELEASED] [1.40.2]` in `CHANGELOG.md`.
- Findings left unfixed, with reasons: `TECH_DEBT.md`, "Calls — leftovers from the 2026-10-08 bug sweep".
- Checks only a phone can do: `docs/BACKLOG.md`, "Calls — teardown, ringing, answering and the call log (2026-10-08)".
- Traps found on the way: `docs/GOTCHAS.md`. They cover the Firestore own-write echo, `catch (e: Exception)` catching cancellation, and WebRTC's factory owning its signaling thread.
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

- Every fix in `aec1caa`, `6646712` and `53a2958` has a regression test, written first and seen failing against the old code.
- The full gate passed on the final tree: `:app:testFirebaseDebugUnitTest` (2233 tests, 0 failures) and `assembleFirebaseDebug`.
- Those three commits were each checked on their own tree, running the call, chat and architecture test packages.
- `eeb706a` has no JVM seam. No test covers `CallService`'s threading, its dispose order or its end-of-call writes, so the review matters most there.
- Nothing has run on a phone.

## Where to look hardest

1. **Threading.** `CallService` runs everything on the main thread: `serviceScope` is `Dispatchers.Main.immediate`, and WebRTC callbacks hop over through `onMain(callId)`. Check that nothing touches its fields or the `PeerConnection` off the main thread. `onIceCandidate` still launches from the signaling thread, with the `callId` and `isCaller` it was created with.
2. **Busy guards.** They return without `startForeground()` after a `startForegroundService()`. This relies on the platform skipping the start-foreground timeout for a service that is already in the foreground ("Service already foreground; no new timeout" in `ActiveServices`).
3. **Dispose order.** `localAudioTrack.dispose()` runs before `peerConnection.dispose()`. Each `RtpSender` holds its own track wrapper; this was checked in the bytecode of `stream-webrtc-android` 1.3.0.
4. **The audio device module.** `WebRtcPeerConnectionFactory` releases it after `factory.dispose()`. That is safe whether or not the native factory keeps its own reference.
5. **Setup in `CallActivity`.** It runs on `@ApplicationScope` and reads `isFinishing` of the activity that started it. A failed create publishes `Ended(callId = "", ERROR)`, and nothing reads `Ended.callId`.
6. **Resetting controls.** `CallStateHolder.updateState` resets the controls on a not-ongoing to ongoing change. It reads `_callState.value` non-atomically, which is safe only while every caller is on the main thread.
7. **Ringing.** It uses `FLAG_INSISTENT` on a foreground service's notification. The ring is expected to stop when that notification is replaced by the low-importance ongoing one, or removed.
8. **The call-log rule.** `CallDirection.of` also changes how calls already in the history read. Every received 0-second call now shows as missed.

## Review history

- Before the fixes, an independent strong-tier review found 13 issues. The fixed ones are in the commits; the rest are in the TECH_DEBT entry.
- After the commits, three reviews were started: the `code-review` skill's Standards and Spec agents, and a second correctness pass. All three were stopped before they reported, at the owner's request, so none of their findings exist.

## Environment notes for a cloud session

- **Maven Central rate limit.** Maven Central answered 429 through the proxy for most of an hour, and Gradle stops at a 429 instead of trying the next repository. This session put Google's mirror first with an init script outside the repo, `~/.gradle/init.d/maven-central-mirror.gradle`. It adds `https://maven-central.storage-download.googleapis.com/maven2/` to both `pluginManagement` and `dependencyResolutionManagement` in `beforeSettings`. A fresh container does not have it.
- **Robolectric downloads.** Robolectric fetches its `android-all-instrumented` jars from Maven Central at runtime. A 429 there fails the first Robolectric test of a run with "Failed to fetch maven artifact", which is not a code failure.
- **Signal's Maven repo.** The environment's network policy denies `build-artifacts.signal.org` (403). It only matters if a jar from it is missing from the Gradle cache. Allow it under the environment's Network access settings if one is.
- **Gradle daemon heap.** The daemon (`-Xmx4g`) filled its old generation after about eight builds and slowed to a crawl. Run `./gradlew --stop` and start again.
- **Issue tracker.** `docs/agents/issue-tracker.md` does not exist. The `code-review` skill mentions it, but this review has no issue.

## Suggested skills

- `code-review`: the review itself.
- `tdd`: any fix the review turns up.
- `diagnosing-bugs`: a finding that needs reproducing first.
- `changelog-release`: a follow-up fix that adds a CHANGELOG entry. It goes in the same `[UNRELEASED] [1.40.2]` section.
- `simplify`: an optional quality pass. The `CallService` diff is concurrency-heavy, which is one of CLAUDE.md's triggers for it.

## Open decisions for the owner

- Incoming calls now ring with the default ringtone, which changes behaviour. If silent calls were deliberate, revert `aec1caa`.
- A pull request from `ccr-80d563e8-ahtbkb` to `main` is not open yet. CI runs only on pull requests and pushes to `main`.
