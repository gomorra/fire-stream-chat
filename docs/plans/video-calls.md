# Video calls

Status: approved, no step started. The prototype is built on `prototype/video-call` and waits for the owner's verdict. Steps 4 and 9 wait for that verdict.

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

Set by this plan, not asked:

- **firebase flavor only.** `PocketBaseCallSignalingSource` stays a stub and only follows signature changes.
- **An older app keeps working as a voice partner.** The camera button is then disabled.
- **The camera runs only while the call screen or its picture-in-picture window is visible.**
- **An answer from the notification or the lock screen starts with the camera off.**
- **A group of more than four members** gets a picker for up to three people to ring.

Not in this plan: screen sharing, more than four people, adding someone to a running 1:1 call,
background blur, recording, calls on the pocketbase flavor, authenticated signalling.

## What exists today (verified 2026-10-03)

- `data/call/CallService.kt` (735 lines) holds one `PeerConnection`. Offer and answer both set
  `OfferToReceiveVideo = false`. `onTrack` is empty.
- `data/call/WebRtcPeerConnectionFactory.kt` builds the factory with no video codecs and no `EglBase`.
  Its ICE servers are Google STUN and the public relay `openrelay.metered.ca` with fixed credentials.
- `FirestoreCallSource` writes `calls/{callId}`: `callerId`, `calleeId`, `status`, `offer`, `answer`,
  and the subcollections `callerCandidates` and `calleeCandidates`.
- `firestore.rules` lets the caller and callee read and update a call. Any signed-in user can read
  and create ICE candidates of any call.
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
  per participant id and rebinds it as tracks come and go.
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
   or relayed. The checkpoint after step 4 decides whether step 5 runs.
5. **Three video encoders at once** in a four-person call may be too much for a phone. Step 7 lowers
   resolution and bitrate by group size. The checkpoint after step 8 tries it on the owner's phones.
6. **Two plans bump `AppDatabase`.** `docs/plans/stickers-and-gifs.md` also goes from 29 to 30.
   Whichever runs second takes the next number.
7. **Signalling is not authenticated end to end.** Media is encrypted between the phones, relay
   included. The key fingerprints travel through Firestore, so whoever can rewrite a call document
   could sit in the middle. Not settled here. Step 4 records it in `docs/BACKLOG.md`.
8. **Variant C of the prototype moves the in-call screen into the main activity.** If it wins, the
   plan gets one more step before step 4 for that host. See the prototype section.

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
- **Capture:** one commit on the throwaway branch. The verdict (which variant, which parts of the
  others) is written into steps 4 and 9 in a `docs(plan):` commit on main.
- **Built:** the commit is the tip of `prototype/video-call`. The header of
  `VideoCallPrototypeActivity.kt` lists every intent extra (`phase`, `theircam`, `weak`, `theme`,
  `bare` and more), so any state can be opened without a tap. Comparison boards of all three
  variants are in `docs/plans/video-calls-prototype/` on that branch.
- **Screenshots without a device:**
  `./gradlew :app:testFirebaseDebugUnitTest --tests '*VideoCallPrototypeShots*' -Proborazzi.test.record=true`
  writes every variant and scenario to `app/build/prototype-shots/`.

**What the verdict changes.** With A or B, steps 4 and 9 rebuild `CallScreen` inside `CallActivity`.
With C, the in-call screen lives in the main activity and `CallActivity` keeps only ringing on the
lock screen. The plan then gets a step before step 4 for the overlay host, and picture-in-picture
is re-decided.

## Steps

Order: 1 → 2 → 3 → 4 ‖ 5 ‖ 6 → 7 → 8 ‖ 9

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
  has drawn a first frame. It drops every track before a connection is disposed.
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

**Waits for the prototype verdict.** The layout comes from the winning variant file on
`prototype/video-call`. Rewrite it properly; do not copy it in. The rest holds for any variant:

- One `CallScreen` for every call. Video tiles come in through a slot,
  `videoTile: @Composable (participantId) -> Unit`, so a Robolectric test can pass a plain box.
  Callbacks collapse into an `@Immutable CallScreenCallbacks`.
- A tile shows the avatar while that person's camera is off or no frame has arrived, and a *muted*
  mark from `micOn`.
- Controls: camera, flip (only while the own camera is on), mic, the existing
  `CallAudioRouteButton`, hang up. With `videoAvailable` false the camera button is disabled and one
  line says the other side needs the latest app.
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
  unavailable line, the voice-only screen.
- Docs: `SPEC.md`, `FEATURE-MAP.md`, `BACKLOG.md` (the hardware list below, and risk 7), CHANGELOG
  `Added` — **Video calls**.

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
  candidates are read and written by the two uids in its id. The 1:1 candidate subcollections are
  narrowed to the caller and the callee.
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
- If the verdict's group layout needs an active speaker, each session reads its audio level from the
  connection's stats twice a second into `CallParticipant.speaking`.
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

**Waits for the prototype verdict** for the three- and four-person layout.

- Entry: phone and camera icons in a group chat's top bar. Up to four members: everyone rings.
  More: a picker for up to three people.
- A banner in the group chat while `observeLiveCall` reports a call I am invited to and not in:
  *Call in progress · Join*.
- The tiles from step 4, laid out for three and four people.
- Call log: a group entry shows the group's name and calls the group back. `CallLogEntry.isGroup`.
- Tests (Robolectric): the layout for 2, 3 and 4, the banner's states, the picker's cap.
- Docs: `SPEC.md`, `FEATURE-MAP.md`, `BACKLOG.md`, CHANGELOG `Added` — **Group calls**.

## Verification

- **Gate, every step:** `./gradlew test` and `./gradlew assembleDebug`. Both flavors must compile,
  because the pocketbase stubs follow every signature change.
- **After step 1:** one voice call between the phone and the emulator, both directions. The log says
  direct or relayed.
- **After step 4, phone and emulator:** start as voice and turn the camera on from each side. Start
  as video. Camera off shows the avatar on the other side, and the camera indicator goes out. Flip.
  Refuse the camera permission and confirm the call goes on. Leave the screen and confirm
  picture-in-picture; close the small window and confirm the camera pauses. Answer from the lock
  screen. A headset connected during video takes the audio. The call log and the bubble say *video*.
- **After step 5:** a call on mobile data runs through `turn.cloudflare.com`.
- **After step 8:** three devices in one call; one leaves and rejoins; one is killed and its tile
  goes within a minute.
- **After step 9:** a group of five rings only the picked people; a late join from the banner.
- **Owed on hardware** (to `docs/BACKLOG.md` § *Pending on-device verification*): two phones on
  mobile data, a Bluetooth headset during video, heat and battery in a four-person call, a phone with
  an older app version as the partner.

## Run

The prototype comes first, in an interactive session: *build the prototype per
`docs/plans/video-calls.md` § Prototype first*. After the verdict is in steps 4 and 9:

```bash
scripts/run-plan.sh docs/plans/video-calls.md --dry-run
scripts/run-plan.sh docs/plans/video-calls.md --to 4
```
