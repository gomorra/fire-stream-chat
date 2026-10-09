# Video calls

Status: approved. The prototype verdict is in and written into steps 4, 4a and 9.

> **Steps 1 to 5 are shipped on `plan/video-calls`, on the `CallService` from before main's call rework.** Main runs each call in a `CallSession` (`CallHost`, `CallMedia`, `WebRtcCallMedia`), `CallState` has `Placing`, and `firestore.rules` lists the fields a call may hold, with tests in `firestore-rules-tests/`. Step 5a brings the two together. Steps 6 to 9 build on its result.

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
- **Video is available when both sides agreed to send and receive on the video line.** An older app
  answers without it, and an older caller offers none. `PeerSession` reads this from the negotiated
  direction, so no version field is needed.
- **`PeerSession` owns one connection to one remote person.** `CallService` owns the call: the
  foreground state, the notification, the audio session, the local media, and a map of sessions.
  A 1:1 call has one session. A group call has up to three.
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
- Tests: `PeerSessionTest` (directions, `setCamera`, availability with an old peer on either side),
  `CallVideoSinksTest` (bind, rebind, the drop-before-dispose order), new rows in
  `CallAudioRoutePolicyTest`, `ProximityLockTest`, `CallStateHolderTest`.
- Docs: `SCHEMA-FIRESTORE.md`, `ARCHITECTURE.md`, and `GOTCHAS.md` for each trap this step pays for.

### Step 4 — The call screen with video, for two people (UI) — skills: app-ui-design

The layout is the prototype's A · Stage: `VariantAStage.kt` on `prototype/video-call`. The answer
buttons are B's: `SplitAnswerRow` in `VariantBSplit.kt`. Rewrite them properly; do not copy them in.

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
- Leaving the chat for another screen, or the app, pauses the camera like any time the call is off
  screen. The call notification leads back to the stage.
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
