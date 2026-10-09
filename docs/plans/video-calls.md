# Video calls

Status: approved, steps 1 to 5a shipped. The prototype verdict is in and written into steps 4, 4a and 9.

> **Steps 1 to 5a are shipped on `plan/video-calls`.** Steps 1 to 5 were built on the `CallService` from before main's call rework. Step 5a merged main and moved the video work onto main's structure: each call runs in a `CallSession` behind `CallHost`, a `PeerSession` is its connection, and `firestore.rules` lists the fields a call may hold, with tests in `firestore-rules-tests/`. Steps 6 to 9 build on that. Their notes marked `(step-5a)` say what changed under them.

## Context

The app has 1:1 voice calls: WebRTC, signalled through Firestore, woken by a push. `CallService` owns
one audio-only `PeerConnection` and `CallScreen` shows an avatar, a timer and three buttons. Group
chats have no call button.

The owner wants video calls, first between two people and then in groups.

## Decisions (agreed with the owner, 2026-10-03)

| Question | Decision |
|---|---|
| Scope | 1:1 video first (steps 1–5), then group calls (steps 6–9) |
| Voice and video | One kind of call. Any call can turn the camera on, and each side controls only its own camera. The phone icon starts a call with the camera off, the camera icon with it on |
| Group calls | A mesh: every phone connects directly to every other. At most four people, the caller included. No media server |
| Relay | Video is built on what exists: direct connections, with today's free public relay as the fallback. A Cloudflare relay (step 5) runs only if calls fail to connect or stutter |
| Call screen | The prototype's A · Stage. Groups use B's grid. An incoming video call gets B's two answer buttons. A swipe up docks the call over its chat as C's card |
| Where it runs | On the owner's machine, not in a cloud container. Step 3 needs the emulator, and steps 4, 4a and 9 read `prototype/video-call`, which is never pushed |

Set by this plan, not asked:

- **firebase flavor only.** `PocketBaseCallSignalingSource` stays a stub and only follows signature changes.
- **An older app keeps working as a voice partner.** The camera button is then disabled.
- **The camera runs only while the call is on screen:** the stage, its picture-in-picture window,
  or the docked card.
- **An answer from the notification or the lock screen starts with the camera off.**
- **The docked card shows only in the call's own chat.** Anywhere else in the app the call is
  reached through its notification, as today.
- **A docked call has no picture-in-picture.** Leaving the app from the chat pauses the camera, and
  the call goes on with sound.
- **A group of more than four members** gets a picker for up to three people to ring.

Not in this plan: screen sharing, more than four people, adding someone to a running 1:1 call,
background blur, recording, calls on the pocketbase flavor, authenticated signalling,
picture-in-picture from the docked card, a call card outside the call's chat.

## What exists today (verified 2026-10-03)

- `data/call/CallService.kt` holds one `CallSession` at a time, and its `WebRtcCallMedia` one
  `PeerConnection`. Offer and answer both set
  `OfferToReceiveVideo = false`. `onTrack` is empty.
- `data/call/WebRtcPeerConnectionFactory.kt` builds the factory with no video codecs and no `EglBase`.
  Its ICE servers are Google STUN and the public relay `openrelay.metered.ca` with fixed credentials.
- `FirestoreCallSource` writes `calls/{callId}`: `callerId`, `calleeId`, `status`, `offer`, `answer`,
  and the subcollections `callerCandidates` and `calleeCandidates`.
- `firestore.rules` lets only the caller and the callee read and write a call, and lists the
  fields a call may hold. Each side adds ICE candidates to its own list only.
- `CallState` names one remote user per state. `CallUiControls` holds mute and the audio route.
- `ChatScreen.kt:1042` shows the phone icon for 1:1 chats only. The same intent is built again at
  `ChatScreen.kt:1460` and `CallsScreen.kt:451`.
- The manifest declares `CAMERA`. `CallService` is `microphone|shortService`. `CallActivity` has
  `showWhenLocked`, its own task (`taskAffinity=".call"`), and no picture-in-picture.
- `sendCallPushNotification` (`functions/index.js`) sends `callId`, `callerId`, name and avatar.
- A finished call is a `CALL` message: `content` is the end reason, `duration` the seconds.
  `CallsViewModel` builds the call log from those messages.
- `io.getstream:stream-webrtc-android:1.3.0` already contains `Camera2Capturer`, `EglBase`,
  `DefaultVideoEncoderFactory`, `SurfaceViewRenderer` and `EglRenderer`. The TextureView renderer is
  in a separate artifact, `stream-webrtc-android-ui`, which is not a dependency.
- `firebase-functions` is a client dependency, but nothing in the app calls a function yet.
- `AppDatabase` is at version 29.

## The model

- **One call screen for every call.** A voice call is a call with both cameras off.
- **Two activities draw the call.** `CallActivity` draws the stage, full screen. The main activity
  draws the docked card inside the call's chat. Both read `CallStateHolder` and take their tiles
  from `CallVideoSinks`.
- **Every call sets up one video line**, at the start, in both directions. Turning the camera on
  attaches the camera track to that line (`RtpSender.setTrack`). No new offer is needed.
- **`video` on the call document says how the call was started.** It sets the ring text, the default
  audio route and the call log entry. It is not the live camera state.
- **Live state is per person:** `media.<uid>.camera` and `media.<uid>.mic` on the call document,
  written by that person. The other side shows the avatar while `camera` is false, and shows video
  only after the first frame has arrived.
- **Video is available when both sides agreed to send and receive on the video line.** `PeerSession`
  reads this from the negotiated direction. The caller offers the line only to a callee whose user
  document carries `callVideoLine`, because an older app crashes on an offer with a video line. An
  older caller offers none.
- **`CallSession` owns one call:** its states, its ring, its timers, the status of the call
  document, the end reason and the call's chat message. `CallService` is its Android host,
  through `CallHost`: the foreground state, the notification, the audio session and the
  permissions. The service holds no call logic.
- **`PeerSession` owns one connection to one remote person.** `CallLocalMedia` holds the call's
  local media and opens the sessions. A 1:1 call has one session. A group call has up to three.
- **A call's state lives on the main thread.** The session, the service and the camera rule
  (`CameraSwitch`) run there and hold no lock. `PeerSession` keeps its own locks, because WebRTC
  calls it on the signalling thread, and reports through events that are collected on the main
  thread. The camera's device calls run on one worker.
- **Placing is a state.** `CallState.Placing` covers an outgoing call from the permission prompt
  until the service takes it over, and carries how the call was started.
- **`PeerSignaling` is what a session needs for one pair:** send and observe the offer, the answer
  and the candidates. 1:1 implements it over the existing call document, groups over a link document.
  A session does not know the Firestore layout.
- **Local media is shared.** One microphone track and one camera track go into every session.
- **The screen never sees a WebRTC type.** `CallVideoSinks` in `data/call/` hands out a ready `View`
  per participant id and rebinds it as tracks come and go. The same participant can have a view in
  each activity.
- **Audio follows video.** While any video shows, the speaker is the default unless a headset is
  connected or the user picked a route, and the proximity lock is off.

Firestore, fields added to a 1:1 call:

```
calls/{callId}
├── video: Boolean                        # how the call was started
└── media: { <uid>: { camera, mic } }     # live state, each user writes their own
```

Firestore, a group call (step 6):

```
calls/{callId}
├── kind: "group", chatId, createdBy, createdAt, video
├── status: "live" | "ended", endedAt
├── invited: [uid]                        # at most 4, the creator included
├── members/{uid}                         # sessionId, joinedAt, seenAt, leftAt, camera, mic
└── links/{lowUid_highUid}                # sessions: { low, high }, offer, answer
    ├── offerCandidates/{id}
    └── answerCandidates/{id}
```

In each pair the person with the smaller uid makes the offer. A member row is live while its `seenAt`
is younger than 60 seconds; a joined phone refreshes it every 20 seconds. No server watches a call,
so this is what removes a phone that died.

## Open risks, each with the step that settles it

1. **Which renderer.** Rounded, overlapping and animated video tiles do not work with a
   `SurfaceView`. Step 3 adds `stream-webrtc-android-ui` for its `VideoTextureViewRenderer` and
   checks two overlapping tiles on the emulator. If the artifact does not fit, a small `TextureView`
   around `EglRenderer` replaces it.
2. **The camera foreground type on Android 14 and later.** `startForeground` with the camera type
   throws without the runtime permission, and it is only allowed while the app is visible. Step 3
   adds the type only at the moment the camera turns on, from the visible call screen.
3. **The emulator's camera** through `Camera2Capturer` is untried. Step 3 finds out. If it fails,
   the emulator joins with its camera off and the phone sends.
4. **The free public relay may not carry video.** Step 1 logs for every call whether it runs direct
   or relayed. The checkpoint after step 4a decides whether step 5 runs.
5. **Three video encoders at once** in a four-person call may be too much for a phone. Step 7 lowers
   resolution and bitrate by group size. The checkpoint after step 8 tries it on the owner's phones.
6. **Two plans bump `AppDatabase`.** `docs/plans/stickers-and-gifs.md` also goes from 29 to 30.
   Whichever runs second takes the next number.
7. **Signalling is not authenticated end to end.** Media is encrypted between the phones, relay
   included. The key fingerprints travel through Firestore, so whoever can rewrite a call document
   could sit in the middle. Not settled here. Step 4 records it in `docs/BACKLOG.md`.
8. **Two activities draw one call.** A video `View` belongs to the activity that made it, and the
   camera must not blink while the stage hands over to the docked card. Step 4a settles both.

## Prototype first (interactive, throwaway branch, not a runner step)

**Question:** what does a call with video look like and how is it driven, for two, three and four people?
Built per the `prototype` skill's UI branch, before step 1. Work stops afterwards until the owner picks.

- **Where:** branch `prototype/video-call` in its own worktree `.claude/worktrees/prototype-video-call`,
  cut from main. Copy `local.properties` and `google-services.json` in. Never `./gradlew --stop` from
  it. It is never merged and never pushed. `prototype/sticker-gif-picker` is the model.
- **Files**, all in `app/src/main/java/com/firestream/chat/ui/call/prototype/`:
  `VideoCallPrototypeActivity.kt` (set up like `CallActivity`: edge to edge, `FireStreamTheme`,
  lock-screen flags, picture-in-picture; reads the extras `variant` and `people`),
  `VideoCallPrototype.kt` (switcher, scenario state, stand-in people and video),
  `VariantAStage.kt`, `VariantBSplit.kt`, `VariantCDocked.kt`.
- **Reached two ways.** A new `app/src/debug/AndroidManifest.xml` declares the activity, exported,
  with its own launcher icon **Video call prototype**, so no login is needed and a release build has
  no entry. A camera icon beside the phone icon in `ChatScreen`'s top bar, `BuildConfig.DEBUG` only,
  in 1:1 and group chats, opens it with that chat's name. The icon is there to judge the entry point.
- **Everything is simulated.** No camera, no microphone, no `CallService`, no Firestore, no network.
  A video tile is a Canvas drawing: a tinted moving backdrop, a head-and-shoulders shape, a name, and
  a ring driven by a made-up audio level.
- **Switcher:** a floating pill under the status bar (the bottom is what is being judged):
  `◀ A · Stage ▶`. A second row drives the scenario and prints it. Phase: outgoing ring, incoming
  ring, connecting, connected, ended. People: 2, 3, 4. Toggles: started as voice or as video, my
  camera, their camera, their mic, weak network. State in `rememberSaveable`.
- **Both cameras off is the voice call.** Each variant must show it, because the new screen replaces
  today's `CallScreen`.
- **A · Stage** (the WhatsApp and Signal convention). The remote video fills the screen. The self
  view is a rounded tile that can be dragged and snaps to a corner; a tap swaps the two. The controls
  are a floating dock that hides after four seconds. Always dark. Ringing shows the own preview behind
  the caller's name. Three and four people split the stage, and the self tile keeps floating. Leaving
  the screen enters real Android picture-in-picture.
- **B · Split.** No floating tile. Everyone gets an equal tile, the self view included: halves for
  two, one over two for three, 2×2 for four. A tap enlarges a tile. The controls are a bottom sheet
  that never hides: mic, camera, hang up, flip, more. Pulled up, it lists audio routes and people.
  Ringing is split too and offers *answer with video* and *answer without*. Follows the app theme.
- **C · Docked over the chat.** The call is a card at the top of the chat, and the thread and
  composer below stay usable (stand-in bubbles). The card has three sizes by drag: a slim strip
  (avatars, timer, hang up), the card, and full screen. A group is one large active speaker and a row
  of the others. An incoming call is a card over the dimmed chat.
- **Run:** `env ANDROID_SERIAL=emulator-5554 ./gradlew :app:installFirebaseDebug`, then the launcher
  icon, or
  `adb shell am start -n com.firestream.chat/.ui.call.prototype.VideoCallPrototypeActivity --es variant A --ei people 2`.
  Screenshots of each variant (two people connected, four people, incoming ring) go to the owner.
- **Not owed:** tests, CHANGELOG, docs, review skills. It must only compile (`assembleFirebaseDebug`).
  Load the `app-ui-design` skill so the variants look like this app.
- **Capture:** one commit on the throwaway branch. The verdict is below.
- **Built:** the commit is the tip of `prototype/video-call`. The header of
  `VideoCallPrototypeActivity.kt` lists every intent extra (`phase`, `theircam`, `weak`, `theme`,
  `bare` and more), so any state can be opened without a tap. Comparison boards of all three
  variants are in `docs/plans/video-calls-prototype/` on that branch.
- **Screenshots without a device:**
  `./gradlew :app:testFirebaseDebugUnitTest --tests '*VideoCallPrototypeShots*' -Proborazzi.test.record=true`
  writes every variant and scenario to `app/build/prototype-shots/`.
- **Not checked on a device:** the camera icon in the chat top bar, C's composer with the real
  keyboard, and the lock-screen flags. The owner's pass covers them.

**Verdict.** A · Stage is the base for every phase of a call.

- From B: the grid for three and four people, and the two answer buttons of an incoming video call.
- From C: the card and the strip, for a call docked over its chat. A swipe up on the stage docks it.
- Not built: B's bottom sheet, C's active-speaker layout, C's incoming card, C's drag from the card
  to full screen, and the ring around whoever speaks. Nothing in the plan reads audio levels.

The stage stays in `CallActivity`, so the lock screen and picture-in-picture work as planned. The
docked card is drawn by the main activity, inside the call's chat. Step 4 builds the stage, step 4a
the dock, step 9 the grid.

## Steps

Order: 1 → 2 → 3 → 4 → 4a ‖ 5 ‖ 5a ‖ 6 → 7 → 8 ‖ 9

Every step follows CLAUDE.md's post-step workflow (tests, `./gradlew test`, `./gradlew assembleDebug`,
review skills, one commit, docs). UI steps load the `app-ui-design` skill. User-visible steps get a
CHANGELOG entry and a bump through the `changelog-release` skill.

### Step 1 — `PeerSession`: one connection per remote person, no behaviour change — skills: code-review; model: strong

Callbacks arrive on the WebRTC signalling thread while teardown arrives from the main thread.

**Approach**

1. `data/call/PeerSignaling.kt`: the interface (send offer, answer, candidate; observe the remote
   offer, answer, candidates) and `OneToOneSignaling` over `CallRepository`. The remote offer stays
   a single fetch of the call document, and the answer is still written together with
   `status = "answered"`, as today.
2. `data/call/PeerSession.kt`: the connection, the SDP observers, the candidate queue and the
   duplicate filter move here from `CallService`. Events go through a channel, so the owner handles
   them on its own scope and never on the signalling thread. No `PeerConnection` method is called
   while the session's lock is held.
3. `CallService`: a map of sessions keyed by the remote user id, one collector per session's events.
   Intents, foreground state, notification, ring timeout, call status, audio session and the call
   message stay.
4. The direct-or-relayed line comes from `onSelectedCandidatePairChanged`. The classification is a
   pure function (`IcePath`), tested on its own.
5. Tests: `PeerSessionTest` (MockK `PeerConnection`, fake `PeerSignaling`), `OneToOneSignalingTest`,
   `IcePathTest`.
6. The code contradicts the spec in one place. Today `CallService` closes the connection from inside
   its own callbacks (`onIceConnectionChange` `FAILED`, `onCreateFailure`, `onSetFailure` all reach
   `cleanup()`). The event channel removes that, which is the trap this step names.
7. Skills: `code-review` (tagged), and `simplify` because the diff is concurrency-heavy and will
   pass 600 lines.

**Shipped** `da97e8c3` (2026-10-03) — tier: strong, tagged strong. skills: code-review, simplify. Reviewer models: code-review: opus, opus; simplify: sonnet, opus, sonnet, opus.
Departures (for sign-off):
- MockK mocks `PeerConnection` on the JVM, so no interface was put in front of it.
- A third file, `data/call/IcePath.kt`, holds the direct-or-relayed classification so it can be tested without a connection. The line is logged under the tag `CallService`.
- "No behaviour change" does not hold on four error paths. A local description that cannot be set, a factory that returns no connection, and a remote description of an unknown type now end the call with `ERROR`. Before, the first two left the call hanging and the third crashed the app. A local track the connection rejects also ends the call instead of throwing on the main thread.
- The caller applies the answer once. Before, every snapshot of an answered call document applied it again.
- `CallService.onCallAnswered` moves to `Connecting` only from `OutgoingRinging`, through the new `CallStateHolder.compareAndSetState`. The session and the service both watch the call document, so the service must not write `Connecting` over a `Connected`.
- Remote candidates are observed from the start of a session and held. Before, the listener started after the remote description was set.
- `Connected` is reported once for ICE `CONNECTED` and `COMPLETED`. Before, `COMPLETED` set the call's start time a second time.
- The session closes the connection and does not dispose it, as before. The teardown order is unchanged: track, connections, factory.
- `docs/ARCHITECTURE.md` was updated too, because it named `CallService` as the owner of the connection.
- /code-review found that a connect event can be handled while `cleanup()` runs on another thread, which would start the audio session after it was stopped. `onSessionConnected` checks again after the start and undoes it. The same race can still write `Connected` over `Ended`, as it could before this step. See the note in step 7.
- Not done: the names `"answered"`, `callerCandidates` and `calleeCandidates` are constants in `OneToOneSignaling` and literals in `CallRepositoryImpl`, `FirestoreCallSource` and `CallService`.
- Nothing ran on a device. The checks are in `docs/BACKLOG.md` § *Pending on-device verification*.

- `data/call/PeerSignaling.kt`: the pair boundary from the model. `OneToOneSignaling` implements it
  over `CallRepository`, mapping caller and callee to the two candidate subcollections.
- `data/call/PeerSession.kt`, with an AGENT-NOTE header. It takes the factory, a `PeerSignaling`, the
  local tracks, whether it offers, and a scope. It owns the `PeerConnection`, the SDP observers, the
  rule that candidates wait for the remote description, and the duplicate filter. It reports
  connected, disconnected, failed and remote-track events.
- `CallService` keeps the intents, the foreground state, the notification, the ring timeout, the
  status of the call document, the audio session and the call message. It holds a map of sessions
  with one entry.
- On connect, the session logs whether the chosen path is direct or relayed.
- Nothing changes in Firestore, in `CallState` or on screen.
- Traps: `close()` is idempotent and is never called from inside a callback of the same connection.
  The lock order written at `audioSessionLock` stays.
- Tests: `PeerSessionTest` with a MockK `PeerConnection` and a fake `PeerSignaling`. Offer flow,
  answer flow, early candidates held and then applied, duplicates dropped, `close()` twice, a failure
  event on a failed description and on ICE `FAILED`. If MockK cannot mock a WebRTC class on the JVM,
  put a thin interface in front of it and say so in the Shipped block.
- Docs: `docs/FEATURE-MAP.md` voice-call table.

### Step 2 — The call's kind in signalling, push and the call log — skills: code-review; model: strong

The message sync path and a Room column change here.

**Approach**

1. Domain first: `CallSignalingData.video`, `CallState.*.video`, `Message.isVideoCall`,
   `CallLogEntry.video`, and the two `CallRepository` signatures.
2. Signalling: `CallSignalingSource.createCallDocument`, `FirestoreCallSource` (write, and read with
   a missing field as false), the pocketbase stub, `CallRepositoryImpl`.
3. The `CALL` message: `MessageColumns` / `MessageRecord.isVideoCall`, `RawMessage.isVideoCall`,
   `MessageSource.sendCallMessage`, the Firestore field `video` in `FirestoreMessageSource`,
   `RawMessage.toMessage` in `MessageRepositoryImpl`, and `AppDatabase` 29 → 30.
4. The ring: `CallActivity.EXTRA_VIDEO` and `CallService.EXTRA_VIDEO`, `FCMService`, the function's
   payload, and the notification title. The service keeps the call's kind in one field. When the
   call document says video and the service did not know, the service takes it on the main thread:
   `CallStateHolder.markVideo` flips the current state and the ring notification is posted again.
5. `CallsViewModel.buildEntries` fills `CallLogEntry.video`.
6. Tests: `CallsViewModelTest`, a new `MessageEntityCallMappingTest`, `FirestoreMessageSourceTest`
   (write and read), a new `FirestoreCallSourceTest`, `CallStateHolderTest` for `markVideo`, and a
   new Robolectric `CallNotificationManagerTest` for the two titles.
7. Nothing in the code contradicts the spec. The three hand-built outgoing intents stay as they are;
   `CallActivity` reads `EXTRA_VIDEO` with false as the default, and step 4 replaces the intents.
8. Skills: `code-review` (tagged), and `simplify` because the diff reaches 600 lines and the
   service gains a cross-thread field.

**Shipped** `05093267` (2026-10-04) — tier: strong, tagged strong. skills: code-review, simplify. Reviewer models: code-review: opus, opus; simplify: sonnet, opus, sonnet, opus.
Departures (for sign-off):
- No CHANGELOG entry and no version bump. Nothing starts a call as video yet, so nothing a user sees changes.
- The ring's title is *Incoming Video Call* or *Incoming Voice Call*, in the title case the voice ring already had.
- The upgrade wipes the local message database (`AppDatabase` 29 → 30). Messages sync back from Firestore.
- `CallState.Live` is new: the ringing, connecting and connected states implement it (`callId`, `video`, `withVideo()`). /simplify asked for it in place of four identical branches in `CallStateHolder.markVideo`.
- The three hand-built outgoing intents are unchanged. `CallActivity` reads `EXTRA_VIDEO` with false as the default.
- The ring notification now sets `setOnlyAlertOnce`, because the service posts it a second time when it learns the kind from the call document.
- /code-review found three races in the first version of that late path: a ring posted after the call ended, a kind leaking into the next call, and a kind lost when the call connects at the same moment. The path now runs on the main thread, cancels a ring that lost the race, and marks the state again after `Connected`. Step 7 has a note to delete all of it.
- `TECH_DEBT.md`: the `MessageColumns` entry said to drop the interface with the next backend column. `isVideoCall` was added the old way and the entry records that.
- Not done, from /code-review: one name for the concept (`video`, `isVideoCall`, `callVideo`), and a type for the caller's id, name, avatar and kind that travel together.
- Not done, from /simplify: `CallState` as the only store of the kind. The service still needs it after the state is `Ended`, for the call message.
- `docs/DOMAIN-MODELS.md` and `docs/CLOUD-FUNCTIONS.md` had stale call sections (`CallLogEntry`'s fields, the push type). They were rewritten.
- `node --check functions/index.js` was refused by the session's permissions. The change there is one payload property and was checked by reading.
- No test covers `CallService`'s late path or `FCMService`. Neither class has a test today.
- Nothing ran on a device. The checks are in `docs/BACKLOG.md` § *Pending on-device verification*.

- `CallSignalingData.video`. `CallSignalingSource.createCallDocument(callerId, calleeId, video)` and
  `CallRepository.createCall(calleeId, video)`. `FirestoreCallSource` writes and reads `video`; a
  missing field is false. The pocketbase stub follows the signature.
- `CallState`'s ringing, connecting and connected states gain `video`.
  `CallService.startOutgoing` / `startIncoming` and `CallActivity` gain an `EXTRA_VIDEO`.
  Nothing sets it to true yet.
- `functions/index.js`: `sendCallPushNotification` adds `video` to the payload.
  `FCMService.handleIncomingCall` passes it on. The service takes `video` from the call document when
  that arrives, so a function that is not deployed yet only delays the label.
- `CallNotificationManager`: *Incoming video call* or *Incoming voice call*.
- The `CALL` message: `Message.isVideoCall`, the column on `MessageRecord` and `MessageColumns`, the
  Firestore field `video`, and `sendCallMessage` / `logCallMessage` take it. Bump `AppDatabase` by one.
- `CallLogEntry.video`, filled in `CallsViewModel.buildEntries`.
- Tests: `CallsViewModelTest`, the entity mapper, `FirestoreMessageSourceTest` for the field.
- `firestore.rules`: the create rule lists a call's fields, so `video` joins that list, with a test
  in `firestore-rules-tests/`. The owner deploys the rules before an app version that writes
  `video` ships, or every call from it is refused.
- Docs: `SCHEMA-FIRESTORE.md`, `SCHEMA-ROOM.md`, `DOMAIN-MODELS.md`, `CLOUD-FUNCTIONS.md`.

### Step 3 — Camera and the video line — skills: code-review; model: max

**Approach**

1. Dependencies first: `webrtc` to the newest 1.3.x that Gradle resolves, plus
   `stream-webrtc-android-ui`. Then `WebRtcPeerConnectionFactory` (`EglBase`, the video codec
   factories, a video source and track, the dispose order) and the new `LocalCamera`.
2. `PeerSession`: a video transceiver when offering, the offered one set to `SEND_RECV` when
   answering, `setCamera`, `videoAvailable`, and an event when the video line is negotiated. The
   camera track is attached only to a line both sides agreed on, under a lock the signalling
   thread never takes.
3. The new `CallVideoSinks`, then the state: `CallParticipant`, `CallStateHolder.participants`,
   the four new fields of `CallUiControls`.
4. Signalling: `CallSignalingData.media`, `setMedia` through `CallSignalingSource`,
   `FirestoreCallSource`, the pocketbase stub and `CallRepository`.
5. Audio: `CallAudioRoutePolicy.resolve(preferSpeaker)`, `CallAudioRouter`, `ProximityLock`.
6. `CallService` last: the three actions, the foreground type, the camera following the screen,
   the media writes through one collector, the remote media and first frames into `participants`,
   and the status of the call document handled once per change. Then the manifest and the docs.
7. Tests: `PeerSessionTest`, new `CallVideoSinksTest` and `LocalCameraTest`,
   `CallAudioRoutePolicyTest`, `CallAudioRouterTest`, `ProximityLockTest`, `CallStateHolderTest`,
   `FirestoreCallSourceTest`.
8. The code contradicts the spec in three places. None of the three touches a decision or the
   model. A fourth finding does, see **Decision taken** below.
   - The camera track does not join `localTracks`. The video line is a transceiver without a
     track, and `setCamera` attaches the track later, so the step-1 note about `addTrack` covers
     the microphone only.
   - `CallStateHolder.reset()` has no caller, so the controls of one call are still set when the
     next one starts. The service has to reset them at the start of every call, or `cameraOn`
     would carry over as mute does today.
   - This session cannot make the two emulator checks. No device is attached, the runner's
     allowlist has no `emulator` command, and no screen shows a video tile before step 4. The
     checks (overlapping tiles, the emulator's camera) go to `docs/BACKLOG.md`. The renderer stays
     behind `CallVideoSinks.createView`, which returns a plain `View`.
9. Skills: `code-review` (tagged), and `simplify` because the diff is concurrency-heavy and will
   pass 600 lines.
10. The decision below, built last. `AuthSource` writes and reads `callVideoLine` on
    `users/{uid}`. `AuthRepository.announceCallVideoLine()` runs when the app starts and after a
    sign-in. `CallRepository.createCall` reads the callee's field before it creates the call
    document and returns it with the call id, so nothing waits between the ring and the offer.
    `CallService` passes it to `PeerSession(offerVideoLine)`. The `media` writes move into a new
    `CallMediaPublisher`, a plain class, so the rule that an app without video never sees them has
    a test. Tests: `CallRepositoryImplTest`, `CallMediaPublisherTest`, `FirebaseAuthSourceTest`,
    `AuthRepositoryImplCallVideoLineTest`, and new rows in `PeerSessionTest`.

**Decision taken** (2026-10-04)

Answer: a capability on the user document. The caller offers the video line only to a callee that
can take it.

- A current app writes `callVideoLine: true` to `users/{uid}` when it starts.
- The caller reads the callee's document before the offer. It adds the video line only when the
  field is true.
- A missing field offers no video line. So does a read that fails or takes too long.
- `PeerSession` is told whether to offer the video line. Without it the offer is the one a
  released app gets today.
- A call started as video goes out without the line too when the callee lacks the field. It runs
  as a voice call with the camera button disabled.
- The side that answers needs no check. A released app that calls offers no video line.
- The check sits in the 1:1 call path, where a released app can be the callee.
- Known limit: a phone that goes back to an older build keeps the field, and a call to it crashes
  it until it updates.
- Tests: the offer without a video line, and the read's four outcomes (true, missing, failed, too
  slow).
- Docs: the field in `SCHEMA-FIRESTORE.md`, the crash below in `GOTCHAS.md`.

Why: a released app that is called crashes on an offer with a video line.

- Its factory has no video codecs (`WebRtcPeerConnectionFactory` at `v1.38.0`). WebRTC logs
  *No video codecs in common* and accepts the line. It then builds a receive stream for the
  caller's video stream from an empty codec list and aborts the process: `front() called on an
  empty vector`, SIGABRT in `libjingle_peerconnection_so.so`, inside `setRemoteDescription`. No
  error comes back, so the released `CallService` cannot end the call. The app dies when the
  callee answers, voice calls included.
- Checked on the emulator with a probe app: library 1.3.0, a factory without video codecs and the
  released constraints, answering this step's offer. Offer and answer only, no media.

  | Case | Result |
  |---|---|
  | This build calls a released app, offer with the video line | The released side aborts |
  | This build calls a released app, offer without a video line | Negotiates as today |
  | A released app calls this build | Negotiates, no video line |
  | Both sides current | The video line is agreed as `SEND_RECV` on both sides |

- Libraries 1.3.0 and 1.3.10 carry the same WebRTC source stamp, 2024-04-15.
- A released app that places the call is safe. It applies the answer again on every snapshot of
  an answered call document, and ends the call when that fails. The `media` writes of this step
  change the document after the answer. The service therefore writes `media` only once the session
  reports the video line as agreed, which never happens with an older app. `CallMediaPublisher`
  holds that rule, and `CallMediaPublisherTest` is its regression test.

Not chosen:

- Both sides need the current app. An updated phone would crash every phone that has not updated.
- The video line only on calls started as video. Such a call still carries the line.
- A second offer after the call connects. `PeerSignaling` and `PeerSession` carry one offer and
  one answer per call.

**Shipped** `70b59566` (2026-10-04) — tier: max, tagged max. skills: code-review, simplify, changelog-release. Reviewer models: code-review: opus, opus; simplify: sonnet, opus, sonnet, opus.
Departures (for sign-off):
- The decision as built: the caller's read sits in `CallRepository.createCall`, before the call document exists, and not in `CallService`. Creating the document rings the callee, who answers by fetching the offer once, so nothing may wait between the ring and the offer. `createCall` returns `OutgoingCall(callId, videoLine)` and waits at most three seconds. `CallActivity` passes `videoLine` to `CallService.startOutgoing`.
- The app writes `callVideoLine` at every process start (`FireStreamApp`, a start by a push included) and when an existing user signs in. A new user document carries the field from its creation. The field is never written as false.
- A new class, `CallMediaPublisher`, holds the `media` writes and the rule that a call without an agreed video line is never written to. `CallMediaPublisherTest` is the regression test the decision owed.
- **The build gate ran under a private Gradle home.** `~/.gradle/caches/8.11.1/transforms` holds about 110 zero-byte `metadata.bin` files, all written at 12:52 on 2026-10-04. `./gradlew assembleDebug` fails on them at `checkFirebaseDebugDuplicateClasses`, and will for every step until those directories are deleted. This session did not touch the shared cache. It built both flavors with `./gradlew -g <worktree>/build/gradle-home-step3 --no-daemon assembleDebug` and deleted that directory afterwards. Both unit test suites ran on the shared Gradle home. `docs/GOTCHAS.md` § *Build tooling* has the entry.
- CHANGELOG: one `Fixed` entry under a new `[UNRELEASED] [1.39.0]`, because the call's controls now start fresh with every call and a mute no longer shows on the next one. `main` has a `[1.38.0]` section this branch lacks, so the merge will conflict there. `scripts/check-changelog-header.sh` was refused by the session's permissions; `v1.39.0` is not a tag.
- `stream-webrtc-android` went from 1.3.0 to 1.3.10, with `stream-webrtc-android-ui` at the same version.
- The camera track does not join `localTracks`. The video line is a transceiver without a track, and `setCamera` attaches the track once both sides agreed.
- `CallStateHolder.beginCall` gives every call fresh controls. `reset()` still has no caller.
- The two emulator checks (overlapping tiles, the emulator's camera) were not made. No screen shows a tile before step 4. They are in `docs/BACKLOG.md`, and step 4 has a note.
- /code-review fixes: the release of a call's media guards each step, silences the microphone first, and leaks the factory when a step failed. An action without a call stops the service by start id. A camera that fails to open sets the foreground type back. The sign-in no longer waits for the announcement.
- Not done, from /code-review: tests for `CallService`'s camera switch, its status handling and `onRemoteMedia` (step 7 has a note), the speaker default of a video call that runs without a video line, and the rotation during the capability read (both noted in step 4).
- Not done, from /simplify: screen visibility as tokens on `CallStateHolder` (noted in step 4a), `CallVideoSinks` as the owner of the EGL context (noted in step 4), skipping the first `media` write of the default state, and dropping `PeerSession.videoAvailable`, which only tests read and the plan names.
- `TECH_DEBT.md` has two new entries: `mediaLock` is taken on the main thread, and the capability is written on every process start.
- Nothing ran on a device. The checks are in `docs/BACKLOG.md` § *Pending on-device verification*.

- `libs.versions.toml`: `stream-webrtc-android` to the current 1.3.x, and `stream-webrtc-android-ui`
  at the same version.
- `WebRtcPeerConnectionFactory`: one `EglBase` per factory, `DefaultVideoEncoderFactory` and
  `DefaultVideoDecoderFactory` on its context, a video source and track. Dispose in this order:
  capturer, texture helper, source, tracks, connections, factory, `EglBase`.
- `data/call/LocalCamera.kt`: `Camera2Enumerator`, front camera first, `start`, `stop`, `flip`,
  1280×720 at 30 fps. `stop` releases the camera so the system's camera indicator goes out. It needs
  only the factory, not a connection, so a preview can run while ringing. Never on the main thread.
- `PeerSession`: when offering, add a video transceiver with `SEND_RECV`. When answering, set the
  offered one to `SEND_RECV`. `setCamera(track?)` attaches or detaches the track. `videoAvailable` is
  true when the negotiated direction is `SEND_RECV`. A remote video track is reported as an event.
  The two `OfferToReceiveVideo = false` constraints go.
- `data/call/CallVideoSinks.kt`, a singleton: `createView(context, participantId)` returns a
  `VideoTextureViewRenderer` on the call's EGL context, bound to that participant's track and
  rebound when it changes. `LOCAL` is the self view, mirrored for the front camera. It reports who
  has drawn a first frame. It drops every track before a connection is disposed. A participant can
  have more than one view at a time, and each view is released on its own.
- State: `CallParticipant` (id, name, avatar, `cameraOn`, `micOn`, `connected`, `hasFrame`) in
  `domain/model/CallState.kt`. `CallStateHolder.participants` holds the remote people, one in a 1:1
  call. `CallUiControls` gains `cameraOn`, `frontCamera`, `videoAvailable`, `cameraPaused`.
- Signalling: `CallSignalingSource.setMedia(callId, uid, camera, mic)` and `CallSignalingData.media`.
  The service writes on connect and on every toggle, mute included, and reads the other side's.
- `CallService`: `ACTION_SET_CAMERA`, `ACTION_FLIP_CAMERA`, `ACTION_SET_SCREEN_VISIBLE`. The service
  never asks for a permission. The foreground type is `MICROPHONE | CAMERA` while the camera is on
  and the permission is held, and it is set again when the camera turns on or off. A refused type
  change leaves the camera off and the call running. When the screen is not visible the camera
  pauses and `media.camera` goes false; it resumes with the screen.
- Manifest: `FOREGROUND_SERVICE_CAMERA`, and `foregroundServiceType="microphone|camera|shortService"`.
- Audio: `CallAudioRoutePolicy.resolve` gains `preferSpeaker`. With it, no headset and no user pick,
  the answer is `SPEAKER`. `ProximityLock` takes no lock while any video shows.
- **(step-1)** `PeerSession.createLocalDescription` builds both the offer and the answer, with the
  constraints in `audioOnlyConstraints()`. A remote video track arrives as the existing
  `PeerSessionEvent.RemoteTrack`, which `CallService.onSessionEvent` ignores today.
- **(step-1)** `CallService.cleanup()` disposes the local audio track before it closes the
  sessions, which matches the dispose order above. `PeerSession.start()` turns the resulting
  `IllegalStateException` from `addTrack` into a `Failed` event; keep that when the camera track
  joins `localTracks`.
- **(step-1 /simplify)** `CallService.observeCallDocument` runs its `when` on every snapshot of the
  call document. The `media` writes of this step make an answered call emit many snapshots.
  `onCallAnswered` is safe against that. Check the `"declined"` and `"ended"` branches, or collect
  the status through `distinctUntilChanged()` and the media separately.
- **(step-2)** `CallService.observeCallDocument` already reads the document on every snapshot for
  the call's kind (`onCallDocumentSaysVideo`). It returns at once when the kind is known, so the
  `media` snapshots cost nothing there.
- **(step-2)** The ringing, connecting and connected states implement `CallState.Live` (`callId`,
  `video`, `withVideo()`). State that every live call carries belongs on that interface.
- Tests: `PeerSessionTest` (directions, `setCamera`, availability with an old peer on either side),
  `CallVideoSinksTest` (bind, rebind, the drop-before-dispose order), new rows in
  `CallAudioRoutePolicyTest`, `ProximityLockTest`, `CallStateHolderTest`.
- Docs: `SCHEMA-FIRESTORE.md`, `ARCHITECTURE.md`, and `GOTCHAS.md` for each trap this step pays for.

### Step 4 — The call screen with video, for two people (UI) — skills: app-ui-design

The layout is the prototype's A · Stage: `VariantAStage.kt` on `prototype/video-call`. The answer
buttons are B's: `SplitAnswerRow` in `VariantBSplit.kt`. Rewrite them properly; do not copy them in.

**Approach**

1. `ui/call/CallScreen.kt` becomes a thin stateful wrapper around a stateless `CallStage`, which
   takes a `CallStageState`, an `@Immutable CallScreenCallbacks` and the `videoTile` slot. The
   pieces sit beside it: `CallStageTiles.kt` (tile, avatar, the floating self view and its corner
   arithmetic) and `CallStageControls.kt` (dock, top bar, answer row).
2. `CallControlButton` gains `enabled`. `CallAudioRouteButton` takes its colours, so it can sit
   in the dark dock.
3. A new `ui/call/OutgoingCallPlacer.kt`, a singleton on the application scope. It holds the wait
   for `createCall`, so a recreated activity finds the call still being placed, and the stage
   shows *Calling…* from the first frame. A hang-up during the wait ends the call it created.
4. `CallActivity`: always the dark theme, `outgoingIntent`, the `CAMERA` request (with the
   microphone for a video call, and on the first camera tap), the lock state, visibility
   reports, picture-in-picture and the *minimise* arrow. `CallViewModel` injects `CallVideoSinks`
   for the tile views.
5. `CallService`: the speaker default needs an agreed video line (the step-3 note), and the two
   ongoing notifications name the call's kind.
6. Entry and log: the camera icon in `ChatScreen`, the three intents through `outgoingIntent`,
   `CallsScreen` rows and the `CALL` bubble.
7. Tests: `CallStageUiTest` (Robolectric, every row of the step's list), `SelfTileCornersTest`,
   `OutgoingCallPlacerTest`, and the notification titles in `CallNotificationManagerTest`.
8. Nothing in the code contradicts a decision or the model. Two things the spec does not say:
   the back button minimises a live call like the arrow does, and the dock of a call that is
   still being placed has only the hang-up button, because no call exists to switch anything on.
9. This session has no device, so the two emulator checks of risks 1 and 3 stay in
   `docs/BACKLOG.md`.
10. Skills: `app-ui-design` (tagged), `changelog-release` for the bump, and `simplify` because the
    diff will pass 600 lines.

**Shipped** `a8b69da8` (2026-10-04) — tier: mid, tagged mid. skills: app-ui-design, changelog-release, simplify. Reviewer models: simplify: sonnet, opus, sonnet, opus. CHANGELOG entry: `a8b69da8`.
Departures (for sign-off):
- **Nothing ran on a device or an emulator.** No video view, no camera and no picture-in-picture window has been seen. The two emulator checks of risks 1 and 3 are still open. The Robolectric tests draw the stage with a box in place of a video tile. `docs/BACKLOG.md` § *The call screen with video* lists thirteen checks.
- The register counts of the new composables were not read from the APK. The session's permissions allow no pipe, and the `dexdump` recipe needs one. It is check 13 in the backlog.
- A new class, `ui/call/OutgoingCallPlacer`, places the call. It holds the wait for `createCall` on the application scope and publishes `placing`, which the stage draws as *Calling…* with only the hang-up button. A hang-up during the wait ends the call document that is created after it. A call that cannot be created shows *Call failed* and closes. Before, it closed nothing and showed nothing.
- The back button minimises a live call, as the arrow does. Before, it closed the activity. An incoming ring keeps the old behaviour.
- The *minimise* arrow sends a call without video to the background (`moveTaskToBack`). Step 4a replaces that with the dock.
- `CallActivity` closes at once when it is opened without a call and without one being placed, for example from a notification that outlived its call.
- The stage closes only after an end it watched. `CallStateHolder` keeps `Ended` until the next call starts, and the activity would otherwise close under the permission dialog of the next call.
- A tile is asked for from the moment a camera is on, and the avatar covers it until the first frame has arrived. The spec named only the two end states.
- The decision the step-3 note asked for: a call started as video to an app without video starts on the earpiece. `CallService.followVideoWithAudio` reads `videoAvailable` for it.
- The two notifications of a running call are titled *Video Call* or *Voice Call*. A kind that the service learns late from the call document does not update a notification that is already posted.
- The refusal of the camera is explained in a toast, once per activity. A second tap after a permanent refusal does nothing and says nothing.
- New strings are literals, like the rest of the call screen. The route sheet's strings stay resources.
- A video call's bubble shows the camera icon also when the call was missed or declined, in the error colour. A missed voice call keeps the missed-call icon.
- `docs/BACKLOG.md`: the item *Video calls, 1-to-1 (4.2)* is deleted, and risk 7 is a new item, *Call signalling is not authenticated end to end*.
- CHANGELOG: one `Added` entry in the existing `[UNRELEASED] [1.39.0]` section. `scripts/check-changelog-header.sh` was not run, as in step 3.
- Not done, from /simplify: `CallControlColors.themed()` and the default size of `CallAudioRouteButton` have no production caller until the docked card of step 4a. The live states still name the other person in three ways, so `CallStageState.person` has four branches. A `CallState.Placing` on `CallStateHolder` in place of the placer's own flow is noted in step 9.
- No test covers `CallActivity`: the permission paths, the preview, the visibility reports and picture-in-picture.

- One `CallScreen` for every call. Video tiles come in through a slot,
  `videoTile: @Composable (participantId) -> Unit`, so a Robolectric test can pass a plain box.
  Callbacks collapse into an `@Immutable CallScreenCallbacks`.
- The stage is always dark, whatever the app theme.
- Connected: the other person fills the screen. The self view is a rounded tile that floats above,
  follows a drag and snaps to the nearest corner. A tap on it swaps the two. It shows only while
  the own camera is on.
- A tile shows the avatar while that person's camera is off or no frame has arrived, and a *muted*
  mark from `micOn`. With both cameras off the stage is the voice call: avatar, name, timer.
- Controls are a floating dock: camera, flip (only while the own camera is on), mic, the existing
  `CallAudioRouteButton`, hang up. With `videoAvailable` false the camera button is disabled and one
  line says the other side needs the latest app.
- While any video shows, the dock and the top bar hide after four seconds, and a tap on the stage
  brings them back. A voice call keeps them. The corners the self tile snaps to move up while the
  dock shows.
- The top bar has a *minimise* arrow, the name and the timer. The arrow leaves the stage, into
  picture-in-picture while video shows.
- Outgoing ring and connecting: the own preview fills the screen behind the name when the call
  started as video. A voice ring shows a quiet glow instead. The dock is already there.
- Incoming ring: *Decline*, *Voice only* and *With video* for a video call, *Decline* and *Answer*
  for a voice call. The preview shows behind the caller's name only if `CAMERA` is already granted;
  the ring never asks for it. *With video* asks if needed and answers with the camera off when it
  is refused.
- On a locked phone a video call offers only *Answer*, without the preview, and starts with the
  camera off.
- `CallActivity`: asks for `CAMERA` when a call starts as video and when the camera button is first
  tapped. A refusal leaves the call running with the camera off and says why once. It reports its
  visibility to the service; picture-in-picture counts as visible.
- Picture-in-picture: `supportsPictureInPicture` and `configChanges` in the manifest, auto-enter
  while any video shows, and only the remote tile is drawn inside the small window.
- Entry: a camera icon beside the phone icon in `ChatScreen`'s top bar for 1:1 chats.
  `CallActivity.outgoingIntent(…, video)` replaces the three hand-built intents.
- Call log and bubble: `CallsScreen` rows and the `CALL` bubble show a camera icon and say
  *video call* for video entries. A row calls back with the kind it was.
- **(step-2)** The kind is there to read: `CallLogEntry.video`, `Message.isVideoCall` and
  `CallState.Live.video`. `CallActivity.EXTRA_VIDEO` starts a call as video, and nothing sets it
  yet. The ring's title is *Incoming Video Call* or *Incoming Voice Call*; the two ongoing-call
  notifications still say *Voice Call* for every call.
- **(step-3)** `CallRepository.createCall` returns `OutgoingCall(callId, videoLine)`, and
  `CallService.startOutgoing(…, video, videoLine)` takes both by name. `videoLine` comes from
  `createCall` and never from a guess: an app without video crashes on an offer with a video
  line. `CallActivity.outgoingIntent` carries only how the call was started.
- **(step-3 /code-review)** `createCall` waits up to three seconds for the callee's capability
  before the call document exists. Nothing is on screen during that wait. A rotation in it drops
  the call without a word, because `onDestroy` cancels `activityScope` and the recreated activity
  skips `handleIntent()`. Show that the call is being placed, and let the wait outlive a
  configuration change.
- **(step-3)** What the screen reads: `CallUiControls` (`cameraOn`, `cameraPaused`, `frontCamera`,
  `videoAvailable`) and `CallStateHolder.participants`. A tile shows video while `cameraOn` and
  `hasFrame` are both true. `videoAvailable` is false from the start of an outgoing call to an
  app without video, and from the applied offer on the side that answers.
- **(step-3)** `CallVideoSinks.createView(context, participantId)` makes a tile's view, and
  `CallVideoSinks.LOCAL` is the self view. A view is single-use: a tile that comes back asks for
  a new one.
- **(step-3)** The service starts every call as not shown and never asks for a permission. A
  screen calls `CallService.sendScreenVisible(true)` once the call exists and again on every
  change, and `sendSetCamera(true)` only with `CAMERA` granted.
- **(step-3 /code-review)** A call started as video to an app without video still plays on the
  speaker by default, because `callVideo` alone sets `preferSpeaker`. On screen it is a voice
  call. Decide here whether it starts on the earpiece.
- **(step-3 /simplify)** `CallVideoSinks.open()` hands a view made before the call its EGL context
  late and replays its surface by hand. If the first emulator run shows trouble there, let
  `CallVideoSinks` own the one `EglBase` and pass its context into the factory.
- **(step-3)** The two emulator checks of risks 1 and 3 are still open, in `docs/BACKLOG.md`. This
  is the first step with a screen that can make them.
- `ArchitectureTest`: add `CallVideoSinks` to `UI_ALLOWED_DATA_IMPORTS` and name it in the
  allowlist entry of `TECH_DEBT.md`.
- Tests (Robolectric): the camera button's three states, avatar or tile per participant state, the
  unavailable line, the voice-only screen, the answer row for a video ring, a voice ring and a
  locked phone, and the dock hiding only while video shows.
- Docs: `SPEC.md`, `FEATURE-MAP.md`, `BACKLOG.md` (the hardware list below, and risk 7), CHANGELOG
  `Added` — **Video calls**.

### Step 4a — The call docks over its chat (UI) — skills: app-ui-design, code-review; model: strong

Two activities hand one call over, and the camera follows whichever is on screen. The card and the
strip are C's: `DockedCall`, `DockedStrip` and `DockedBody` in `VariantCDocked.kt` on
`prototype/video-call`. Rewrite them properly; do not copy them in.

**Approach**

1. State first. `CallSurface` (`STAGE`, `DOCK`) in `domain/model/CallState.kt`. `CallStateHolder`
   gains the call's `chatId`, the set of surfaces that show the call, and `onScreen`: true while
   any surface shows, and false one second after the last one left.
2. `CallService`: `beginCall` takes the chat, the callee resolves it with
   `ChatRepository.getOrCreateChat` when it answers (into the holder only, never into
   `currentChatId`), `ACTION_SET_SCREEN_VISIBLE` goes, a collector of `onScreen` drives the camera,
   and the preview of a video ring starts with the first surface that shows the call.
3. `CallViewModel` exposes the chat, the surfaces and the camera switch. `CallActivity` reports
   `STAGE` from `onStart` to `onStop`, docks (deep link to the chat, a slide and fade, its task to
   the back, the unlock first on a locked phone), and finishes in the background when the call ends
   there. `preparedCallId` and `reportVisible` go.
4. `CallScreen`: a swipe up on the stage calls `onMinimise`. It starts only where no child took
   the touch, and not in the bottom gesture strip.
5. `ui/call/DockedCallCard.kt`: a stateless card and strip, and a stateful `DockedCall(chatId)`
   that `ChatScreen` puts at the top of its content column.
6. Tests: `DockedCallCardUiTest` (Robolectric: strip and card for voice and video, the resting
   size, *Call ended*), `DockedCallRuleTest` (only in the call's chat, not while the stage shows,
   not for an incoming ring), `CallStateHolderTest` (the chat, the visibility rule with virtual
   time), and a swipe row in `CallStageUiTest`.
7. One note is not followed. The step-4 /simplify note wants `Ended` to go back to `Idle` on the
   holder. A stage that waits under a permission dialog sees `Idle` with nothing being placed, so
   it would still need its own "an end I watched" rule. The card gets the same rule instead.
8. This session has no device. The hand-over, the unlock and the card under the real keyboard go
   to `docs/BACKLOG.md`.
9. Skills: `app-ui-design` and `code-review` (tagged), `changelog-release` for the bump, and
   `simplify` because the diff will pass 600 lines.

**Shipped** `d849aa6a` (2026-10-04) — tier: strong, tagged strong. skills: app-ui-design, code-review, simplify, changelog-release. Reviewer models: simplify: sonnet, opus, sonnet, opus; code-review: opus, opus. CHANGELOG entry: `d849aa6a`.
Departures (for sign-off):
- **Nothing ran on a device or an emulator.** The hand-over between the two activities, the swipe, the unlock, the slide and fade, and the card with a real video view are unseen. Robolectric tests draw the card and the strip with a box in place of a video tile. `docs/BACKLOG.md` § *The call docked over its chat* lists fifteen checks.
- The step-4 /simplify note about `Ended` going back to `Idle` on the holder is not followed. A stage that waits under a permission dialog sees `Idle` with nothing being placed, so it would still need its own rule. The card and `CallActivity` each keep "an end I watched" instead, as `CallScreen` does. `CallStateHolder.reset()` still has no caller.
- A call whose chat is not known cannot dock. That is the side that answered, until `getOrCreateChat` returns, and for the whole call when that lookup fails. It is not retried. The arrow, the back button and the swipe then do what step 4 did: picture-in-picture while video shows, the background otherwise.
- The dock's deep link carries a new extra, `MainActivity.EXTRA_KEEP_PLACE`. /code-review found that the plain deep link scrolled an open chat to its newest message and stacked the chat a second time over a screen opened from it. With the extra an open chat stays as it is, and a chat further down the back stack comes back up (`warmDeepLinkAction` in `NavGraph.kt`, with a test). Notifications behave as before.
- The stage gives up its claim on the call the moment it docks, not when it stops. /code-review found that the chat otherwise came in bare and the card grew in afterwards.
- The stage's dock now takes every touch on it. A tap on its padding no longer toggles the controls, and a swipe from a disabled button does not dock.
- "Pulling the card down past its height" is a pull of 96 dp. The card does not follow the finger: it gives a little, and the size changes when it is let go. Pushing it up by 40 dp leaves the strip.
- The card's picture is 30 % of the screen height, between 168 and 260 dp. Until the call connects, the own preview fills it.
- A size the user chose holds until the call's resting size changes: video starting or stopping puts the call back into its resting size.
- The card asks for the camera permission from the chat on the first tap of its camera button, and explains a refusal once.
- A docked call that ends while the app is in the background says *Call ended* when the user returns, however long ago it ended. Check 15 in the backlog asks whether that is wanted.
- `CallStage`'s status line is one line everywhere now (`StageStatus` gained `maxLines = 1`), because the card shares it.
- Strings are literals, like the rest of the call screen.
- Not done, from /simplify: one set of camera, flip and microphone buttons for the stage's dock and the card (`DockSwitches` and the card's buttons repeat the icon and label mapping), one source for the ring wording, and one camera-permission flow for the stage and the card. Notes for the chat intent and the holder's per-call state are in steps 6 and 9.
- Not done, from /code-review: a test for the stateful `DockedCall` (the watched end, the `DOCK` report, the permission path). It needs a `CallViewModel`, which starts `CallService`. Two theoretical races stay: a preview posted while `cleanup()` runs on another thread can leave `cameraOn` set for the ended call (the same class as the `ACTION_SET_CAMERA` race, noted in step 7), and a call that ends in the frame before the stage stops may leave the stage open in the background.
- `scripts/check-changelog-header.sh` was not run, as in steps 3 and 4.

- A swipe up on the stage docks the call. The call's chat opens in the main activity with the call
  as a card under the chat's top bar. The thread and the composer below stay usable. The top bar's
  *minimise* arrow does the same.
- The swipe starts on the stage, not on the self tile or the dock, and works from the outgoing ring
  on. An incoming ring cannot be docked. A swipe from the bottom edge stays Android's home gesture.
- `CallActivity` opens the chat through the main activity's deep link (`MainActivity.EXTRA_CHAT_ID`
  and `EXTRA_SENDER_ID`) and moves its own task to the back. The hand-over is a short slide and
  fade, not a drag that follows the finger. On a locked phone the swipe asks for the unlock first
  and does nothing when it is refused.
- `CallStateHolder` carries the call's `chatId`. The caller has it from the start. The callee
  resolves it with `ChatRepository.getOrCreateChat(callerId)` when it answers. Trap: `CallService`
  writes the `CALL` message only where `currentChatId` is set, which is the caller's side. The
  callee must still not write one.
- `ui/call/DockedCallCard.kt` holds the card and the strip, in the app theme. `ChatScreen` shows it
  while a call of this chat is running and the stage, its picture-in-picture window included, is
  not on screen. It reads `CallViewModel`; `ChatUiState` gains nothing. Tiles come through the same
  `videoTile` slot.
- Two sizes, changed by a drag on the card or a tap on the strip. The strip has avatars, name,
  timer, camera, mic and hang up. The card has the other person's video with the self view fixed in
  a corner, and camera, flip, mic, audio route and hang up. A voice call rests as the strip, a call
  with video as the card. The thread starts below whichever shows.
- Pulling the card down past its height, or its *full screen* button, brings `CallActivity` back to
  the front.
- When the call ends while docked, the card says *Call ended* for a moment and goes. `CallActivity`
  finishes without coming to the front.
- Visibility: both activities report to the service. The call counts as visible while either does.
  The camera pauses only after one second with neither, so the hand-over does not blink.
- **(step-3 /simplify)** `CallService.ACTION_SET_SCREEN_VISIBLE` carries one boolean, which cannot
  say "visible while either activity shows". Replace it with a set of screen tokens on
  `CallStateHolder`, which both activities inject, and a `screenShowing` flow the service
  collects. That also removes the rule that a screen reports again once the call exists.
- Leaving the chat for another screen, or the app, pauses the camera like any time the call is off
  screen. The call notification leads back to the stage.
- **(step-4)** The stage is `CallStage(state: CallStageState, callbacks: CallScreenCallbacks,
  videoTile)` in `ui/call/CallScreen.kt`. Its pieces are in `CallStageTiles.kt` and
  `CallStageControls.kt`. `showsVideo(controls, participants)` is the rule for "any video shows",
  and the card-or-strip choice reads it. `CallAudioRouteButton` takes `CallControlColors`;
  `CallControlColors.themed()` is the app theme's set, for the card.
- **(step-4)** `CallActivity.minimise()` enters picture-in-picture while video shows and calls
  `moveTaskToBack` otherwise. The back button of a live call does the same through a
  `BackHandler` in `CallScreen`. The dock replaces both branches here, and the auto-enter of
  picture-in-picture stays for the home gesture.
- **(step-4)** `ConnectedScene` has a tap detector on the whole stage, which toggles the dock, and
  the self tile has its own tap and drag. The swipe up must not eat those taps.
- **(step-4)** A call that is still being placed has no call id. `OutgoingCallPlacer.placing`
  carries its `chatId`, and the stage draws it as *Calling…* without the *minimise* arrow. It
  cannot be docked until the service has it.
- **(step-4 /simplify)** `CallActivity.prepareCall` reports the call as visible and starts the
  preview once per call, and keeps `preparedCallId` in the saved state to do it only once. The
  screen tokens remove the report. Move the preview too: the service starts it when the first
  screen shows a ring of a video call, and reads the camera permission and the keyguard itself.
  `preparedCallId` and `reportVisible` then go.
- **(step-4 /simplify)** `CallScreen` closes only after an end it watched (`sawCall`), because
  `CallStateHolder` keeps `Ended` until the next call starts. The card's *Call ended* needs the
  same rule, or a chat opened later shows a stale one. Let the holder own it: `Ended` goes back
  to `Idle` after a moment, on the application scope, and a screen closes on `Idle`.
  `CallStateHolder.reset()` has no caller today.
- Tests (Robolectric): what the card and the strip show for voice and for video, which size a call
  rests in, and the card showing only in the call's chat. A unit test for the visibility rule:
  stage to card without a pause, and the pause after a second with neither.
- Docs: `SPEC.md`, `FEATURE-MAP.md`, `ARCHITECTURE.md` (the two activities), `BACKLOG.md` (the
  composer with the real keyboard under the card, the hand-over on a device), CHANGELOG `Added`.

**‖ Checkpoint.** The owner deploys the functions (`firebase deploy --only functions`), makes the
two-device pass from Verification, and reads the direct-or-relayed log lines. `/code-review ultra` if
wanted. Step 5 runs only if a call failed to connect or stuttered over the relay; otherwise continue
with `--from 6`.

### Step 5 — Relay credentials from Cloudflare — skills: code-review; model: strong

Optional, see the checkpoint above. A secret and an authenticated endpoint are added here.

**Approach**

1. `functions/index.js`: `getTurnCredentials`, with the two secrets. Then `domain/model/IceServerData`,
   `data/remote/source/IceServerSource`, `FirebaseIceServerSource` (with a pure parser for the
   function's answer), the pocketbase one, and the two bindings.
2. `data/call/IceServerProvider.kt`. The fetch runs on the application scope, so a caller that
   stops waiting after three seconds does not cancel it, and the answer still fills the cache.
   A failed fetch is not asked again for a minute.
3. `WebRtcPeerConnectionFactory.createPeerConnection(observer, iceServers)` and
   `PeerSession(iceServers)`. The `openrelay` constants go.
4. Where the wait sits. Nothing may wait between the ring and the offer (step 3). The caller
   therefore waits inside `CallRepository.createCall`, beside the capability read and before the
   call document exists, and `CallService` then takes the set without waiting. The side that
   answers fetches during the ring and waits before it opens its session.
5. `CallActivity` warms through a new `CallRepository.prepareCall()`, so the UI gains no import
   from `data/`.
6. The checkpoint reads the relay's host from the log. The direct-or-relayed line names no server
   today. `IcePath` gains the URL of the server that gave this side its relay candidate.
7. Tests: `IceServerProviderTest` (cached set, expiry, timeout, the late answer kept, error, the
   minute after an error, one fetch for two callers), `FirebaseIceServerSourceTest` (the parser),
   new rows in `CallRepositoryImplTest`, `IcePathTest` and `PeerSessionTest`.
8. One consequence of the spec: without the deployed function and its secrets, this build has no
   relay at all. It goes into the Shipped block and the backlog.
9. Skills: `code-review` (tagged), and `changelog-release` for the entry and the bump.

**Shipped** `cd6a382e` (2026-10-05) — tier: strong, tagged strong. skills: code-review, changelog-release, simplify. Reviewer models: code-review: opus, opus; simplify: sonnet, opus, sonnet, opus. CHANGELOG entry: `cd6a382e`.
Departures (for sign-off):
- **Until `getTurnCredentials` is deployed with its two secrets, this build has no relay at all.** The `openrelay` servers are gone, as the step says. A call then connects only where a direct path exists. Do not release this build before the checkpoint below is done.
- **Nothing ran on a device, and the function was never deployed or called.** The shape of Cloudflare's answer is taken from its documentation. `node --check functions/index.js` was not run. `docs/BACKLOG.md` § *The Cloudflare relay for calls* lists eight checks.
- The secrets are named `CLOUDFLARE_TURN_KEY_ID` and `CLOUDFLARE_TURN_API_TOKEN`. The function answers every failure on Cloudflare's side with `unavailable`, gives the request ten seconds, and logs only the HTTP status.
- Where the wait sits. The caller waits inside `CallRepository.createCall`, beside the capability read, before the call document exists. `CallService` then takes the kept set with `IceServerProvider.current()` and waits for nothing. The side that answers starts the fetch when the ring starts. It opens its session at once when the fetch has settled, and otherwise waits up to three seconds first.
- The first call after twelve hours rings up to three seconds later than before, while the servers are fetched.
- The fetch runs on the application scope. A caller that stops waiting does not cancel it, and the late answer is kept for the next call.
- A failed fetch is not repeated for one minute. The spec did not ask for it. Without it the side that answers would wait again for what just failed during the ring.
- `CallActivity` warms through a new `CallRepository.prepareCall()`, so the UI gains no import from `data/`.
- The log line names the relay only when this side's end of the path is the relay: `relayed (local relay, …) through turn:turn.cloudflare.com:…`. `IcePath` gained the server's URL for it. Whether WebRTC reports that URL on the selected pair is unchecked.
- A fetched set replaces the server list, Google's STUN servers included. Cloudflare's answer carries its own STUN servers.
- The app drops every URL that is not `stun:`, `turn:` or `turns:`, and every relay entry without a full login. /code-review found that WebRTC builds no connection on such an entry, and the set would have been kept for twelve hours.
- /code-review also found that a request cancelled by the Firebase client reached the caller as its own cancellation and failed the call. It is a failed fetch now.
- Not done, from /code-review: the side that answers in the last three seconds of the ring, before the servers arrived, can lose to the caller's ring timeout. It is check 8 in the backlog.
- Not done, from /simplify: a fetch started when a chat opens, so the first call does not wait (`CallActivity` opens only after the tap), the set kept in the function between requests, and the validity rules of an entry moved from the Firebase parser to the model. The lifetime of the login is written in the function and in the app; `TECH_DEBT.md` has the entry. Notes for the mesh are in step 7.
- Any signed-in user can ask for a relay login, without a limit. `docs/BACKLOG.md` § *The relay hands a login to every signed-in user* has the options.
- `docs/ARCHITECTURE.md` no longer counts the domain models. The count was wrong.
- `scripts/check-changelog-header.sh` was not run, as in steps 3 to 4a.

- `functions/index.js`: `getTurnCredentials`, an `onCall` function that rejects a caller who is not
  signed in. It posts `{"ttl": 86400}` to
  `https://rtc.live.cloudflare.com/v1/turn/keys/<key id>/credentials/generate-ice-servers` with the
  API token as bearer and returns `iceServers` unchanged. Key id and token are `defineSecret` values.
  It logs neither the token nor the credentials.
- `domain/model/IceServerData`. `data/remote/source/IceServerSource.kt`, with a firebase
  implementation over `FirebaseFunctions` and a pocketbase one that returns nothing.
- `data/call/IceServerProvider.kt`: keeps a set for twelve hours, waits at most three seconds, and
  answers with STUN only on a failure or a timeout. Concurrent callers share one fetch
  (`data/util/SingleFlight.kt`). `CallActivity` warms it when it opens.
- `WebRtcPeerConnectionFactory.createPeerConnection` takes the servers. The `openrelay` constants go.
- Tests: `IceServerProviderTest` — a cached set, expiry, timeout, error, one fetch for two callers.
- Docs: `CLOUD-FUNCTIONS.md`, `ARCHITECTURE.md`. CHANGELOG `Changed`.

**‖ Checkpoint.** The owner creates a TURN key in the Cloudflare dashboard, runs
`firebase functions:secrets:set` for the key id and the token, deploys the functions, and confirms
with one call on mobile data that the log names a relay at `turn.cloudflare.com`.

### Step 5a — The video work moves onto `CallSession` — skills: code-review, simplify; model: max; budget: 60

Steps 1 to 5 were built on `plan/video-calls` while main rebuilt the same call code. Both replaced
`CallService`. This step merges main into the branch and leaves one design. It adds no feature.

**Approach**

1. `git merge main`, 36 conflicted files. The mechanical ones first: the sticker columns beside
   `isVideoCall`, `AppDatabase` at 32, the sources, the docs. Then the call code by the design below.
2. `data/call/`: main's `CallSession`, `CallHost` and `CallService` are the base. `CallMedia` and
   `WebRtcCallMedia` go. `CallLocalMedia` replaces them, implemented by `WebRtcCallLocalMedia`: the
   factory, the microphone, the camera, the video sinks, and `openPeer`, which starts a
   `PeerSession` and hands back its events. `CameraSwitch` is the plain class for the camera rule.
   `CallSession` gains the call's kind, the video line, `CallMediaPublisher`, remote `media`, the
   chat lookup and the relay's servers. `CallStateHolder` is main's placing with the branch's
   participants, chat and surfaces.
3. `ui/call/`: main's `CallViewModel.placeCall` and `CallLaunch` are the base, and
   `OutgoingCallPlacer` goes. `CallState.Placing` carries `video`. The stage and the docked card
   read the holder only. `CallActivity` is main's launch and placing with the branch's stage:
   the camera permission, picture-in-picture and the dock.
4. The fallback ring and its intent carry the kind. `firestore.rules`: `video` on create, the own
   `media` entry on update, each with rows in `firestore-rules-tests/calls.test.js`.
5. Tests: `CallSessionTest` on a `FakeCallLocalMedia`, with every case of main kept and the five
   new ones. A new `CameraSwitchTest`. `OutgoingCallPlacerTest` becomes rows in `CallViewModelTest`.
   `CallServiceStartIntentTest`, `CallStageUiTest` and main's two `CallScreen*UiTest` follow.
6. The code contradicts the spec in four places. None touches a decision or the model.
   - `PeerSession.close()` closes the connection and does not dispose it. Main's fix disposes it,
     or the native connection and its observer leak with every call. `close()` disposes now.
   - `LocalCamera` must not be called on the main thread. The call's state stays on the main
     thread. The camera's device calls and the release of a finished call's media run on one
     worker thread, and report back to the main thread.
   - Main's update rule lets only the callee write to an answered call. The caller's `media`
     entry needs its own clause.
   - The rules tests need the Firestore emulator. This session may not be able to run it.
7. Skills: `code-review` and `simplify` (tagged), `app-ui-design` because Compose code in
   `ui/call/` is merged, and `changelog-release` for the header and the version.

**Shipped** `3ca3c54c` (2026-10-09) — tier: max, tagged max. skills: code-review, simplify, app-ui-design, changelog-release. Reviewer models: simplify: sonnet, opus, sonnet, opus; code-review: opus, opus.
Departures (for sign-off):
- **Nothing ran on a device or an emulator, and the rules tests did not run.** `npm test` in `firestore-rules-tests/` needs the Firestore emulator and an `npm ci`, which would leave an untracked `node_modules` in the worktree. The rules and their new rows were checked by reading, by this session and by a reviewer. Run them before the rules are deployed. `docs/BACKLOG.md` § *Calls after the video work moved onto `CallSession`* lists twelve checks.
- **Deploy `firestore.rules` before a build from this branch places a call.** The call document carries `video`, which the deployed rules refuse.
- Main's `CallMedia` and `WebRtcCallMedia` are replaced by `CallLocalMedia` and `WebRtcCallLocalMedia`. It is one interface for the microphone, the camera, the connections and the first frames, and it is the seam `CallSessionTest` fakes. `openPeer` starts a `PeerSession` and hands back its events. `WebRtcCallLocalMedia` has no JVM test, as `WebRtcCallMedia` had none.
- The camera rule is the new plain class `CameraSwitch`, with `CameraSwitchTest`.
- `PeerSession.close()` disposes its connection. Before, on this branch, it only closed it, which leaks the native connection and its observer. That is main's fix, kept. Four rows of `PeerSessionTest` follow it.
- `LocalCamera` must not be called on the main thread. `WebRtcCallLocalMedia` runs the camera's device calls on one worker for the whole process, and brings every result back to the main thread. A finished call closes its connections at once on the main thread, so the call is silent before the audio session is handed back. Its camera, tracks and factory are released on the worker.
- The side that answers reads the call document once in `CallSession`, for the status, and hands the offer it read to `OneToOneSignaling`. The signalling no longer fetches the document itself. The read and the wait for the relay's servers run side by side.
- `OutgoingCallPlacer` and its test are gone. `CallViewModel.placeCall` places the call as on main, with the call's kind and the video line from `createCall`, and `CallViewModelTest` has the placer's cases. A placing that fails ends as `Ended(ERROR)` and shows *Call ended* with main's toast. *Call failed* is gone from the stage.
- The stage closes on any `Ended` it shows, as on main, because `prepareOutgoingCall` clears the end of the call before. The `sawCall` rule of step 4 is gone from `CallScreen`. `CallActivity` closes at once when it is opened to show a call and none is ongoing. The docked card keeps its own watched-end rule.
- What waits on a permission prompt lives in `CallViewModel` as a `PermissionAction`, so a rotation under the prompt keeps it. That is main's `MicAction`, widened by the camera.
- Answering is one intent. `CallService.sendAnswer(camera)` replaces the camera switch followed by the answer, so a refused answer leaves the ring's preview alone. /simplify asked for it.
- `CallStateHolder.compareAndSetState` and the holder's chat lock are gone. The holder is written on the main thread only. `CallSessionTest` has the case the first one guarded: an answer that arrives after the connect does not take the call back.
- `CallLogEntry` keeps main's `CallLogType` and gains `video`. The call bubble and the Calls tab label a call from both. A declined video call in the Calls tab's detail sheet reads *Declined video call*.
- `firestore.rules`: a call may be created with `video`, as a boolean or not at all. Each side may write its own `media` entry, as two booleans, while the call rings, is answered or has ended. The caller could not write to an answered call before.
- `AppDatabase` is at version 32. The upgrade wipes the local message database, and messages sync back.
- CHANGELOG: main's top section is released (`1.40.4`), so the branch's two `Added` entries and its `Changed` entry sit under a new `[UNRELEASED] [1.41.0]`. The branch's `Fixed` entry about the mute button is dropped, because main shipped the same fix in `1.40.2`. This step adds no entry. `scripts/check-changelog-header.sh` was refused by the session's permissions. `v1.41.0` is not a tag.
- The direct-or-relayed line is logged under the tag `PeerSession` now, not `CallService`. The backlog's `adb logcat` lines follow.
- /code-review fixes: the connections close before the audio session is handed back, a camera start's late answer is dropped when a newer request came, and an answer that ends the call builds no media for it. Header notes that still named `CallService` as the owner were corrected.
- Not done, from /simplify: the status and subcollection names as constants in one place, `CallRepository.answerCall` and `sendAnswer`, which have no caller, one builder for the outgoing and the ongoing notification, the first `media` write of the default state, and a cache of the callee's capability. Notes for the foreground calls of `CallHost`, the holder's per-call state and the person published twice are in steps 6, 7 and 9.
- Not done, from /code-review: a video ring answered from the notification can open the camera for a moment before the answer reaches the service. It is check 11 in the backlog.
- `TECH_DEBT.md`: the `mediaLock` entry is replaced by *A call's WebRTC objects are built on the main thread*.

What each side holds:

- **Main** runs each call in a `CallSession`. It reaches Android through `CallHost` and the
  connection through `data/call/CallMedia`, implemented by `WebRtcCallMedia`. Everything runs on
  the main thread, without locks. `CallState` has `Placing`, and `CallStateHolder.takeOverPlacing`
  hands a placing over to the service. `FCMService` rings with a notification when Android will not
  start the service. `EndReason` is typed through the repository. `firestore.rules` lists the
  fields a call may hold.
- **The branch** keeps the call in `CallService`, on `Dispatchers.IO` with locks. A `PeerSession`
  per remote person runs the offer, the answer and the candidates through `PeerSignaling`. It adds
  the camera, `CallVideoSinks`, `CallMediaPublisher`, `IceServerProvider`, `OutgoingCallPlacer`,
  the stage, the docked card and the `callVideoLine` capability.

The design after this step:

- **Main's structure is the base.** `CallSession` owns one call: its states, its ring, its timers,
  the status of the call document, the end reason and the call's chat message. `CallService` is
  the host and holds no call logic. Nothing of a call lives in `CallService` again.
- **`PeerSession` is the connection.** `CallSession` opens one per remote person and collects its
  events on the main thread. `PeerSignaling` carries the pair's offer, answer and candidates, so
  `CallSession` no longer sequences them itself. `WebRtcCallMedia` goes.
- **Main's fixes in that sequencing stay.** The answer and `answered` go in one write. The side
  that answers reads the call document first and closes when it is no longer `ringing`. An answer
  is applied once. A call that cannot connect ends after the connect timeout, and a lost
  connection after the reconnect timeout. Each of these keeps its test.
- **The call's state is confined to the main thread.** `PeerSession` keeps its own three lock
  rules, because WebRTC calls it on the signalling thread. Every lock, `@Volatile` field and
  posted block with an identity check that the branch added to `CallService` goes. Teardown never
  runs on the signalling thread.
- **`CallSession` stays testable on the JVM.** It reaches the connection, the camera, the local
  tracks and the video sinks through interfaces that a test fakes, as it does `CallHost` today.
  The camera switch (wanted, screen visible, permission, video line agreed, a refused foreground
  type) is a plain class with its own test.
- **One name per thing.** `domain/model/CallMedia` is a person's live camera and microphone state.
  Main's `data/call/CallMedia` interface gets another name or goes with `WebRtcCallMedia`.
- **Placing follows main.** `CallState.Placing` and `takeOverPlacing` stay. `Placing` carries
  whether the call was started as video. `OutgoingCallPlacer` goes, or shrinks to what main's
  placing lacks. `CallRepository.createCall(calleeId, video)` still reads the callee's capability
  and fetches the relay's servers before the call document exists.
- **`EndReason` stays typed** in `CallRepository`, with the branch's `video` argument beside it.
- **The fallback ring knows the call's kind.** It says *video call* for one, and its Decline still
  works without a session.
- **`firestore.rules` gains the branch's fields.** A call may be created with `video`, and each
  side may update its own entry under `media`. Each gets a test in `firestore-rules-tests/`. The
  owner deploys the rules before a build from this branch places a call, or the call is refused.
- **`AppDatabase` takes the next free number** after main's, with both sides' columns.
- **`CHANGELOG.md`:** the branch's entries move under main's current `[UNRELEASED]` header. The
  `changelog-release` skill decides the version.

How to do it:

- `git merge main` in the worktree. The merge commit is this step's code commit. 34 files
  conflict. Resolve the call code by the design above, not hunk by hunk.
- Read main's `CallSession.kt`, `CallService.kt`, `CallHost.kt`, `WebRtcCallMedia.kt`,
  `CallViewModel.kt`, `CallLaunch.kt` and `CallStateHolder.kt` before resolving anything. Read
  main's `docs/ARCHITECTURE.md` on calls.
- No test is deleted. Main's `CallSession` tests and the branch's `PeerSession`, `CallVideoSinks`,
  `LocalCamera`, `CallMediaPublisher` and `IceServerProvider` tests all pass. A test of something
  that no longer exists is rewritten against its replacement.
- New tests on `CallSession` with fakes: a call started as video, the video line offered only
  with the capability, the camera switch, remote `media`, and the end of a call while the camera
  runs.
- `ArchitectureTest` passes without a new baseline.
- Docs: `ARCHITECTURE.md`, `FEATURE-MAP.md`, `DOMAIN-MODELS.md`, `SCHEMA-FIRESTORE.md`,
  `CLOUD-FUNCTIONS.md`, and the model section of this plan.
- Rewrite the notes under steps 6 to 9 that name `CallService`, `OutgoingCallPlacer` or a serial
  dispatcher. The main thread is that dispatcher now. Step 7's mesh lives beside `CallSession`.

Stop with a decision when a fix of main and a behaviour of the branch cannot both hold, or when
`PeerSession` cannot sit under `CallSession` without changing what steps 6 to 9 build on.

### Step 6 — Group calls: model, signalling, rules — skills: code-review; model: max

- `domain/model/GroupCall.kt`: `GroupCall` and `GroupCallMember`, as in the Firestore layout above.
- `domain/util/MeshPlan.kt`, pure. From my uid, my session id, the member rows, the sessions I hold
  and the time, it answers which sessions to open (and whether I offer) and which to close. A session
  exists with every live member but me. The smaller uid offers. A member whose `sessionId` changed
  is closed and reopened. A row not seen for 60 seconds is gone. Never more than three sessions.
- `domain/repository/GroupCallRepository.kt` and `data/remote/source/GroupCallSignalingSource.kt`,
  with a Firestore implementation and a pocketbase stub: create, join, leave, heartbeat, set media,
  end, observe the call and its members, the link methods, and `observeLiveCall(chatId)`.
- `data/call/GroupLinkSignaling.kt` implements `PeerSignaling` over a link document. It ignores an
  offer, an answer or a candidate made for another pair of session ids.
- `firestore.rules`: a group call is created by its `createdBy`, who is in `invited`, with at most
  four entries. Only the invited read it. A member row is written by its own uid. A link and its
  candidates are read and written by the two uids in its id. The 1:1 rules check `callerId` and
  `calleeId`, so they must branch on the call's kind.
- `firestore.indexes.json` (new, referenced from `firebase.json`) for the live-call query.
- **(step-4a)** `CallStateHolder.beginCall(callId, participants, chatId)` carries the call's chat,
  and `setChatId(callId, chatId)` drops a lookup that returns after the next call began. A group
  call knows its chat from the call document (`chatId`), on every side, so no lookup is needed.
- **(step-5a /simplify)** `CallStateHolder` gives a call fresh controls in three places:
  `startPlacing`, `beginCall` and `updateState`. It keeps a shadow call id beside the chat, and
  `CallSession.finish()` clears the camera fields by hand. Group members and links arrive late in
  the same way as the chat. One `MutableStateFlow` of a value keyed by the call id (chat,
  participants, controls), replaced whole by `startPlacing` and `beginCall` and changed with
  `update { if (it.callId == callId) … }`, replaces all of it. The holder is written on the main
  thread only, so it needs no lock.
- **(step-5a)** A new field on the call document needs `firestore.rules` changed first. The
  create rule lists the fields a call may hold, and the update rule lists what may change.
  `firestore-rules-tests/calls.test.js` holds a row for every write the app makes.
- Tests: `MeshPlanTest` as a table (join order, both joining at once, rejoin with a new session id,
  the cap, leave, a stale row), `GroupLinkSignalingTest`, a repository test.
- Docs: `SCHEMA-FIRESTORE.md`, `DOMAIN-MODELS.md`.

### Step 7 — `CallService` runs a mesh — skills: code-review, simplify; model: max

- `data/call/MeshCoordinator.kt`, a plain class so it can be tested without a `Service`. It observes
  the members, asks `MeshPlan`, and opens and closes `PeerSession`s over `GroupLinkSignaling`. A
  failed session is reopened once. A second failure marks that person as not connected and the call
  goes on.
- `CallService`: group start and group answer intents. Joining writes my member row and starts the
  heartbeat. Hanging up writes `leftAt`. When I am the last one left after someone else had joined, I
  end the call. A creator nobody joins within 30 seconds ends it as a timeout.
- The audio session starts with the first connected session and stops with the last.
- `domain/util/CallQuality.kt`, pure: capture size, frame rate and bitrate cap by the number of
  people. Two: 720p, 30 fps. Three: 480p, 24 fps. Four: 360p, 20 fps. Applied through
  `LocalCamera` and each sender's encoding parameters.
- `CallStateHolder.participants` carries everyone. The ringing states gain the group's name.
- The creator writes the `CALL` message into the group chat when it leaves or the call ends.
- A second incoming call during a call is ignored, as today.
- **(step-5a)** `CallService` holds no call logic. It is the Android host of a `CallSession`
  (`CallHost`), and it only passes intents on. Everything this step gives `CallService` belongs
  to a session beside `CallSession`: the group intents arrive in the service and go to it. The
  mesh, `MeshCoordinator`, lives beside `CallSession` and is driven on the main thread, which is
  the serial dispatcher the earlier notes asked for. Nothing here takes a lock.
- **(step-5a)** A session reaches WebRTC through `CallLocalMedia`. `openPeer(remoteId,
  signaling, offers, offerVideoLine, iceServers)` starts a `PeerSession` on the call's shared
  microphone and camera and returns its events. It returns no handle. A mesh closes and reopens
  one person's session while the others live, so `openPeer` must return something to close, and
  the owner must cancel that session's collector with it. A closed session still delivers events
  queued before the close.
- **(step-5a)** `CallSession` starts the audio session at the first connect and stops it only
  when the call finishes. A mesh needs it to start with the first connected session and to stop
  with the last.
- **(step-5a)** `CallSession.onConnected` and its timers assume one connection. A lost
  connection starts the reconnect timeout, which ends the whole call. In a mesh a session that
  fails is reopened once, and the call goes on.
- **(step-1 /simplify)** `PeerSession` logs an error of the answer flow and keeps waiting, while an
  error of the offer flow fails the session. "Reopened once" needs the answer side to fail too.
- **(step-5a)** Every `PeerSession` logs under its default tag, `PeerSession`. Give each session
  of a mesh its own, so the direct-or-relayed lines can be told apart.
- **(step-5a)** The relay's servers: `CallSession` takes `IceServerProvider.current()` for the
  caller of a 1:1 call, who may not wait, and `get()` for the side that answers. A mesh opens and
  reopens sessions during a call. Resolve the set once when the call starts or is joined, with
  `get()`, keep it with the call's state, and give every session of the call that set. A group
  call has no single fetch of an offer, so joining may wait.
- **(step-3)** `PeerSession` takes `offerVideoLine`. A mesh session passes true: only an app with
  group calls joins one, and every such app takes a video line. `CallMediaPublisher` writes the
  1:1 call document only. A group call's `camera` and `mic` go to the member row.
- **(step-3)** `LocalCamera` captures at a constant 1280×720 and 30 fps. `CallQuality` needs it
  to take the size and the rate.
- **(step-5a)** The camera rule is `CameraSwitch`, a plain class with `CameraSwitchTest`. A group
  call uses it as it is, through its own `Port`. The call's kind has one store,
  `CallSession.video`.
- **(step-5a /code-review)** `WebRtcCallLocalMedia` has no JVM test. It holds the order a call's
  media is released in, and the rule that a camera start's late answer is dropped when a newer
  request came. Three connections make both matter more. Give it seams for the factory, the
  camera and the session, and test it, before the mesh opens sessions through it.
- **(step-5a /simplify)** Not done in step 5a, and cheaper to do with the mesh: one call of
  `host.foreground(phase, camera)` in place of `CallHost`'s six foreground methods and the
  notification `CallService` keeps for a change of type, the starting audio values passed to
  `startAudioSession` in place of the two fields the service keeps, and `openPeer` returning an
  event type without the WebRTC track.
- Tests: `MeshCoordinatorTest` (join, leave, reopen, second failure, the cap), `CallQualityTest`.

### Step 8 — Ringing a group — skills: code-review; model: strong

- `functions/index.js`: `sendCallPushNotification` branches on `kind`. For a group it checks that
  `createdBy` and everyone in `invited` are participants of `chats/{chatId}`, then pushes
  `type: "incoming_group_call"` to each invited person but the creator, skipping anyone who blocked
  the creator.
- `FCMService`: the new type starts a group ring. A muted chat does not ring.
- My ring stops when I answer or decline, after 30 seconds, or when the call document says `ended`.
- `CallNotificationManager`: the group's name and the caller's name.
- Check what an app without this plan does with the new push type, and write the answer in the
  Shipped block.
- Tests: the `FCMService` branch and the mute rule.
- Docs: `CLOUD-FUNCTIONS.md`.

**‖ Checkpoint.** The owner deploys functions, rules and indexes
(`firebase deploy --only functions,firestore`). Three devices join one call, to answer risk 5.

### Step 9 — Group call screen, entry and joining late (UI) — skills: app-ui-design

The layout for three and four people is B's grid: `SplitTiles` and `splitSlots` in
`VariantBSplit.kt` on `prototype/video-call`. Everything around it stays the stage from step 4.
Rewrite it properly; do not copy it in.

- Entry: phone and camera icons in a group chat's top bar. Up to four members: everyone rings.
  More: a picker for up to three people.
- A banner in the group chat while `observeLiveCall` reports a call I am invited to and not in:
  *Call in progress · Join*.
- Two people in the call look like step 4.
- Three and four people get equal rounded tiles with a gap, on the dark stage. The own view is one
  of them, and no self tile floats. Three is one over two, four is two by two.
- A tap enlarges a tile and puts the others in a row along the bottom. A second tap returns to
  equal tiles. Each tile keeps its identity and animates to its slot.
- In the grid the dock and the top bar do not hide, and the tiles sit between them. A tap cannot
  both enlarge a tile and toggle the controls.
- The slot arithmetic is a pure function with a table test.
- Ringing: the ring screen from step 4, with the faces in a row, the group's name and the caller's
  name.
- Docked: the card from step 4a shows the same grid, smaller. The strip shows everyone's avatars.
- Call log: a group entry shows the group's name and calls the group back. `CallLogEntry.isGroup`.
- **(step-4)** The stage draws one other person: `CallStageState.remote` is the first
  participant, and the picture-in-picture scene, the top bar's name and the *muted* mark all read
  it. `CallStageState.person` takes the name from the 1:1 call states.
- **(step-5a)** A 1:1 call is placed by `CallViewModel.placeCall`, which publishes
  `CallState.Placing` on `CallStateHolder`, creates the call through `CallRepository.createCall`
  and starts `CallService`. A failure ends the placing as `Ended(ERROR)`, and `CallActivity`
  says so in a toast. "Is there a call" has one source, `CallStateHolder.callState`, and
  `isOngoing` is the rule. A group call needs its own way in, and its own placing state or a
  `Placing` that names a group. `CallLaunch.kt` decides what an intent that opens `CallActivity`
  asks for, and `PermissionAction` what waits on the permission prompt.
- **(step-5a /simplify)** The other person is published twice: in every `CallState.Live` state
  under three names, and in `CallStateHolder.participants`. `CallStageState.person` reads the
  first and falls back to the second. Let the live states carry `callId`, `video` and
  `startTime` only, put the callee into `participants` when the placing starts, and read the
  person from `participants` everywhere. The stage's and the card's camera-permission flow is
  also written twice. One `rememberCameraSwitch(viewModel)` in `ui/call/` would hold it.
- **(step-4a)** The docked call is `DockedCallCard(state, callbacks, onFullScreen, videoTile)` in
  `ui/call/DockedCallCard.kt`. Its card draws one other person through `RemoteTile` and takes the
  name from `CallStageState.person`. The strip draws one avatar. `docksIn` and `CallState.dockable`
  decide where the call docks; a group ring that came in is not dockable either.
- **(step-4a)** `CallActivity.dock()` opens the chat with `participants.first().id` as the deep
  link's sender id. A group chat needs the hint a group notification uses
  (`notificationPartnerHint` in `FCMService`).
- **(step-4a /simplify)** Four places build `MainActivity`'s chat deep link by hand: `FCMService`,
  `ReminderNotificationPoster`, `TimerAlarmReceiver` and `CallActivity.dock()`. Give `MainActivity`
  a `chatIntent(context, chatId, partnerIdHint)` when the group path adds a fifth.
- **(step-4a /simplify)** The stage's dock and the card each map the camera, flip and microphone
  state to an icon and a label (`DockSwitches`, and `CameraButton` / `MicButton` in
  `DockedCallCard.kt`). The grid adds a third place. Share them, with `CallControlColors` and the
  size as parameters.
- Tests (Robolectric): the layout for 2, 3 and 4, an enlarged tile, the docked grid, the banner's
  states, the picker's cap.
- Docs: `SPEC.md`, `FEATURE-MAP.md`, `BACKLOG.md`, CHANGELOG `Added` — **Group calls**.

## Verification

- **Gate, every step:** `./gradlew test` and `./gradlew assembleDebug`. Both flavors must compile,
  because the pocketbase stubs follow every signature change.
- **After step 1:** one voice call between the phone and the emulator, both directions. The log says
  direct or relayed.
- **After step 4a, phone and emulator:** start as voice and turn the camera on from each side. Start
  as video. Answer a video call with each of the two buttons. Swipe up: the chat opens with the
  card, the video does not blink, and a message can be typed and sent with the keyboard open. Pull
  the card down and the stage is back. A docked voice call rests as the strip. Camera off shows the avatar on the other side, and the camera indicator goes out. Flip.
  Refuse the camera permission and confirm the call goes on. Leave the screen and confirm
  picture-in-picture; close the small window and confirm the camera pauses. Answer from the lock
  screen. A headset connected during video takes the audio. The call log and the bubble say *video*.
- **After step 5:** a call on mobile data runs through `turn.cloudflare.com`.
- **After step 5a, phone and emulator:** everything listed after step 4a, again. A voice call and a
  video call in both directions. Cancel a call while it is being placed. Let a call ring out. Kill
  the app on the phone that is called and confirm the fallback ring and its Decline. Call a phone
  that runs the released app and confirm a voice call with the camera button disabled.
- **After step 8:** three devices in one call; one leaves and rejoins; one is killed and its tile
  goes within a minute.
- **After step 9:** a group of five rings only the picked people; a late join from the banner. Three
  and four people show the grid, a tap enlarges a tile, and the docked card shows the grid.
- **Owed on hardware** (to `docs/BACKLOG.md` § *Pending on-device verification*): two phones on
  mobile data, a Bluetooth headset during video, heat and battery in a four-person call, a phone with
  an older app version as the partner.

## Run

```bash
scripts/run-plan.sh docs/plans/video-calls.md --dry-run
scripts/run-plan.sh docs/plans/video-calls.md
```

A run stops at every `‖` of the Order line. Run it again to go on.
