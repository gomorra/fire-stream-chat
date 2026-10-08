<!-- last-verified: 2026-09-20 -->

# Feature → File Map

For each cross-cutting feature, the files that implement it across all layers. Lookup table only — for the *why* behind a pattern, see [PATTERNS.md](PATTERNS.md); for product description, see [SPEC.md](SPEC.md).

> **Maintenance.** When you add, move, rename, or delete a file in `app/src/main/java/`, check whether it's listed below and update if so. Refresh `last-verified` quarterly. Stale entries are worse than missing ones — prune aggressively.

Only features that span **4+ packages** are listed here. Single-screen features (Settings sections, Starred, Archived, Profile setup, etc.) are obvious from the package layout in [ARCHITECTURE.md §12](ARCHITECTURE.md).

---

## Voice Call (1-on-1, WebRTC)

Real-time audio call via WebRTC, signalled through Firestore, woken by a high-priority FCM push.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/call/CallService.kt` | Foreground service — Android side of a call: foreground type and notifications, the audio session (router + proximity lock), one `CallSession` at a time; all on the main thread; stops with the latest start id; a Decline that names a call no session holds declines it directly |
| `app/src/main/java/com/firestream/chat/data/call/CallSession.kt` | One call's transitions, signalling, ring timeout and end-of-call writes, without Android or WebRTC |
| `app/src/main/java/com/firestream/chat/data/call/CallHost.kt` | What a `CallSession` needs from Android; `CallService` implements it |
| `app/src/main/java/com/firestream/chat/data/call/CallMedia.kt` | One call's connection as the session sees it, and the `ConnectionChange`s it reports |
| `app/src/main/java/com/firestream/chat/data/call/WebRtcCallMedia.kt` | `CallMedia` over a WebRTC `PeerConnection`; posts WebRTC callbacks to the main thread and drops them after dispose |
| `app/src/main/java/com/firestream/chat/data/call/CallStateHolder.kt` | `@Singleton` — bridges service ↔ UI via `StateFlow<CallState>`; fresh controls per call; the placing of an outgoing call: `prepareOutgoingCall` before the microphone prompt, then `startPlacing`, `placingCreated`, `takeOverPlacing`, `failPlacing`, `cancelPlacing`, each keyed by the placing's id (compare-and-set, so the setup and the service never both win) |
| `app/src/main/java/com/firestream/chat/domain/model/CallState.kt` | Call states, `Placing` included, `isOngoing` (the one "am I in a call" rule), `CallUiControls` |
| `app/src/main/java/com/firestream/chat/domain/model/CallLogEntry.kt` | Call-log row; `CallLogType.of` — the one rule for how a call reads (outgoing, no answer, incoming, missed, declined), used by the Calls tab and `MessageBubble`'s call row |
| `app/src/main/java/com/firestream/chat/data/call/CallAudioRoutePolicy.kt` | Pure policy — which route wins, and `AudioDeviceInfo.TYPE_*` → `CallAudioRoute` |
| `app/src/main/java/com/firestream/chat/data/call/CallAudioRouter.kt` | `AudioManager.setCommunicationDevice()` wrapper — device callbacks, live `RouteState` |
| `app/src/main/java/com/firestream/chat/data/call/ProximityLock.kt` | Proximity wake lock — held only while the playing route is the earpiece |
| `app/src/main/java/com/firestream/chat/data/call/CallNotificationManager.kt` | Ongoing-call + incoming-call notifications; the incoming channel rings until answered (`FLAG_INSISTENT`); the fallback ring, which opens the call screen when a push may not start the service, and whose Decline names its call |
| `app/src/main/java/com/firestream/chat/data/call/WebRtcPeerConnectionFactory.kt` | WebRTC factory + ICE server config |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreCallSource.kt` | Signalling — `calls/{callId}` doc + ICE subcollections; creates a call in a transaction, which fails offline instead of ringing later |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirestoreCallSourceTest.kt` | A call is created in a transaction that only writes |
| `app/src/pocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseCallSignalingSource.kt` | No calls on the PocketBase backend: every call fails with `UnsupportedOperationException`, and `BuildConfig.SUPPORTS_CALLS` hides the call button |
| `app/src/testPocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseCallSignalingSourceTest.kt` | A call placed on PocketBase fails instead of crashing |
| `app/src/main/java/com/firestream/chat/data/repository/CallRepositoryImpl.kt` | Domain wrapper around the call source; lets cancellation through (`cancellableResultOf`) |
| `app/src/main/java/com/firestream/chat/ui/call/CallActivity.kt` | Separate Android Activity (lock-screen support) — *not* a NavHost route; owns the microphone permission and the launch decision |
| `app/src/main/java/com/firestream/chat/ui/call/CallLaunch.kt` | What an intent that opens `CallActivity` asks for — place, answer, show, or close (a Recents relaunch); a ring from the fallback notification; `MicAction`, what waits on the microphone prompt; `closesUnseen`, which closes a screen whose call ended while it was not shown |
| `app/src/main/java/com/firestream/chat/ui/call/CallScreen.kt` | In-call UI |
| `app/src/main/java/com/firestream/chat/ui/call/CallViewModel.kt` | UI state from `CallStateHolder` + control intents; outgoing-call setup on the application scope, bounded at every step, ended instead if the screen closed or Cancel was pressed; a created call that no service holds is ended and recorded in the chat; the action waiting on the microphone prompt |
| `app/src/main/java/com/firestream/chat/ui/call/CallControlButton.kt` | Mute / hang up / route control |
| `app/src/main/java/com/firestream/chat/ui/call/CallAudioRouteSheet.kt` | Route button + `ModalBottomSheet` of available routes; shared icon/label mapping |
| `app/src/main/java/com/firestream/chat/ui/calls/CallsScreen.kt` | Call-log tab in MainScreen pager |
| `app/src/main/java/com/firestream/chat/ui/calls/CallsViewModel.kt` | Call-log derived from message store |
| `functions/index.js` | `sendCallPushNotification` Cloud Function — high-priority FCM on `calls/{id}` create, kept by FCM for 30 s; `sendPushNotification` counts a call message as unread only when it was missed |
| `functions/callPush.js` | What the call pushes decide: a missed call, the unread updates, the call push's Android options (tested by `functions/test/callPush.test.js`, `npm test`) |
| `app/src/main/java/com/firestream/chat/data/remote/fcm/FCMService.kt` | `incoming_call` push → `CallService.startIncoming`, or the fallback ring when Android refuses the start; a call-message push notifies only a missed call (`callPushNotificationText`) |
| `firestore.rules` | `calls/{callId}`: only the caller and the callee read and write it; the status only moves forward; each side writes its own SDP and its own ICE candidate list |
| `firestore-rules-tests/calls.test.js` | Every call write the app makes passes the rules, and the abuses fail (Firestore emulator; `npm test` in that folder) |
| `app/src/test/java/com/firestream/chat/data/call/CallStateHolderTest.kt` | State-flow transitions, fresh controls per call, mute toggles racing route updates, the placing transitions by placing id, and the service racing a Cancel for a placing |
| `app/src/test/java/com/firestream/chat/data/call/CallNotificationManagerTest.kt` | The incoming channel rings and vibrates on the ringtone stream, insistently; the old silent channel is removed; the fallback ring times out with the ring, opens the call screen to ring, and declines the call it names (Robolectric) |
| `app/src/test/java/com/firestream/chat/domain/model/CallStateTest.kt` | `isOngoing` per state |
| `app/src/test/java/com/firestream/chat/data/call/CallSessionTest.kt` | A call's transitions on fakes of `CallHost` and `CallMedia`: offer and answer, candidates once each, the clock, the ring timeout, how each end is recorded, late events after the end |
| `app/src/test/java/com/firestream/chat/data/repository/CallRepositoryImplTest.kt` | A cancelled caller stops instead of getting a failure |
| `app/src/test/java/com/firestream/chat/domain/model/CallLogTypeTest.kt` | The call-log rule — a received call that never connected is missed, a declined call is declined on both sides |
| `app/src/test/java/com/firestream/chat/ui/call/CallLaunchTest.kt` | Recents never places, answers or rings a call again; a call screen closes when its call ends unseen |
| `app/src/test/java/com/firestream/chat/ui/call/CallScreenAnswerUiTest.kt` | Answer goes to the host's permission check, not straight to the service |
| `app/src/test/java/com/firestream/chat/ui/call/CallViewModelTest.kt` | Outgoing setup — handed to the service once, ended if the screen closed or Cancel was pressed, every timeout, a late create ended and recorded, a cancelled call's late create leaves the next call alone, a failure leaves a call that rang meanwhile alone (Robolectric) |
| `app/src/test/java/com/firestream/chat/ui/call/CallScreenPlacingUiTest.kt` | A call being placed shows the callee and "Calling..."; Cancel ends it without the service |
| `app/src/test/java/com/firestream/chat/ui/chat/CallMessageBubbleUiTest.kt` | The call row's label in a chat |
| `app/src/test/java/com/firestream/chat/data/remote/fcm/CallPushNotificationTextTest.kt` | Only a missed call notifies; a push without the duration says "Call" |
| `app/src/test/java/com/firestream/chat/ui/calls/CallsScreenUiTest.kt` | The Calls tab's labels match the chat's, in the row and the details sheet (Robolectric) |
| `app/src/test/java/com/firestream/chat/data/call/CallAudioRoutePolicyTest.kt` | Route-resolution table + device-type mapping |
| `app/src/test/java/com/firestream/chat/data/call/CallAudioRouterTest.kt` | Which device is selected, pick clearing, start/stop idempotency (MockK, no Robolectric) |
| `app/src/test/java/com/firestream/chat/data/call/ProximityLockTest.kt` | Acquire/release per route, re-acquire after a timed-out lock, shutdown latch |
| `app/src/test/java/com/firestream/chat/ui/call/CallAudioRouteUiTest.kt` | Route button branch (≤2 toggles, 3 opens the sheet) + sheet rows |
| `app/src/test/java/com/firestream/chat/ui/calls/CallsViewModelTest.kt` | Call-log derivation; names that arrive after the log, and profile names that outlive a contacts reload |

**Entry point:** outgoing tap → `ChatScreen.kt` phone icon → `CallActivity` (`ACTION_OUTGOING`) → `CallStateHolder.prepareOutgoingCall()` → microphone prompt → `CallViewModel.placeCall()` → `CallStateHolder.startPlacing()` → `CallRepository.createCall()` → `CallService.startOutgoing()` foregrounds. Incoming: the `incoming_call` push → `FCMService.handleIncomingCall` → `CallService.startIncoming()`.

---

## Voice Dictation (composer)

System `SpeechRecognizer` powering the composer mic button. Language picker in Settings.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/util/SpeechRecognizerManager.kt` | Wraps `SpeechRecognizer` — emits `DictationEvent` flow + handles offline-pack errors |
| `app/src/main/java/com/firestream/chat/data/local/PreferencesDataStore.kt` | `dictationLanguageFlow` — `de` / `en` |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatDictationManager.kt` | Slice owner — drives `ChatUiState.dictation` |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatDictationState.kt` | Slice definition |
| `app/src/main/java/com/firestream/chat/ui/chat/DictationControlBar.kt` | Composer overlay — record/cancel, audio-level meter |
| `app/src/main/java/com/firestream/chat/ui/chat/TypingRow.kt` | Typing+dictation status row |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatViewModel.kt` | Wires the manager + listens to `commits` SharedFlow |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatScreen.kt` | Composer mic button + state observation |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsScreen.kt` | Language picker (Settings → Chat) |
| `app/src/main/res/values/strings.xml` | Dictation strings (`dictation_unavailable`, `dictation_in_call`, …) |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatDictationManagerTest.kt` | Manager state-machine tests |

**Entry point:** mic icon in `ChatScreen.kt` composer → `ChatDictationManager.start()`.

---

## Image / Media Pipeline

Local-first image send: compress → store locally → display immediately → upload with progress → backfill on first launch.

Editing sits *before* that pipeline and leaves it untouched: each editor screen rasterizes its layer into a new JPEG in `cacheDir/edits/` and `PendingMedia` points at the newest one, so `sendMediaMessage` receives a different URI and is otherwise unaware editing exists (`docs/plans/image-editor.md` §2.1).

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/util/ImageCompressor.kt` | EXIF-aware compress; `inSampleSize` for memory-safe decode |
| `app/src/main/java/com/firestream/chat/domain/util/ImageEditGeometry.kt` | `RasterOp` + `Stroke`/`StrokePoint`/`StrokeTool` + the pure dimension arithmetic (ceiling, crop rects, resize, size estimates) — no Android, so both the editor screens and the rasterizer depend on it |
| `app/src/main/java/com/firestream/chat/domain/util/StrokeGeometry.kt` | The draw screen's arithmetic — stroke width against the long edge, the smoothed path via `PathSink`, the blur/painted layer split, mosaic block size. The one copy, because Compose and `android.graphics` both render strokes and must not disagree |
| `app/src/main/java/com/firestream/chat/data/util/ImageEditRasterizer.kt` | The platform half: `ImageDecoder` decode, JPEG encode, stroke flattening, the pixelate mosaic, limiter permit, `cacheDir/edits/` lifecycle and byte budget |
| `app/src/main/java/com/firestream/chat/data/util/MediaFileManager.kt` | `Android/media/com.firestream.chat/{chatId}/{messageId}.{ext}` storage + gallery export; `downloadFor` routes a DOCUMENT to `DocumentFiles` instead of MediaStore |
| `app/src/main/java/com/firestream/chat/data/util/DocumentFiles.kt` | Documents' home, `filesDir/documents/<id>.<ext>` — the extension rule (name → type → URL → `bin`), a picked uri's name and size, the sent copy's move out of the outbox |
| `app/src/main/java/com/firestream/chat/data/worker/MediaBackfillWorker.kt` | WorkManager job — daily (24h) periodic backfill, respects `AutoDownloadOption` + WiFi; also the one-time run a failed download queues |
| `app/src/main/java/com/firestream/chat/data/worker/MediaBackfillScheduler.kt` | `retryDownloads` — the failed-download retry (`media-download-retry`, KEEP, network constraint from the preference); the periodic run is scheduled in `FireStreamApp`, the manual one in `SettingsViewModel` |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirebaseStorageSource.kt` | Upload with `addOnProgressListener` → `uploadProgress` flow |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendMediaMessage` (limit guard, optimistic row, block check), `downloadFor` (in-flight dedup map), per-chat scan; a failed auto-download or scan row hands off to `MediaBackfillScheduler.retryDownloads` |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxSender.kt` | The pipeline behind every retryable send — compress / transcode → thumbnail → upload (owns `uploadProgress`) → encrypt once → write → SENT, persisting each step with a column update and skipping any step the row already records |
| `app/src/main/java/com/firestream/chat/data/outbox/SendClock.kt` | Send timestamps, strictly increasing within the process (`max(now, last + 1)`), so a multi-photo batch keeps a fixed order and the newer-only chat preview (`ChatDao.updateLastMessage`, `ChatDao.upsertRemote`, `FirestoreMessageSource.writeBackChatPreview`) has no ties within it — tests `MessageRepositorySendClockTest`, `ChatDaoLastMessageTest` |
| `app/src/main/java/com/firestream/chat/ui/chat/MessageBubble.kt` | IMAGE branch — aspect ratio from `mediaWidth/mediaHeight`, prefers `localUri` |
| `app/src/main/java/com/firestream/chat/ui/chat/ImagePreviewScreen.kt` | Pager over the picked batch — per-item caption, thumbnail strip, remove-before-send, editor rail; a page's pinch-zoom plus the crop-shape pill are the crop that is sent (`PendingCrop`), flattened on Send and before Draw/Overlay open, transferred into Adjust's crop tool |
| `app/src/main/java/com/firestream/chat/ui/chat/PendingMedia.kt` | The queued-but-unsent item (original uri + mime + caption + per-item HD + edit cursor) and its rotation-safe `Saver` |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/ImageEditToolbar.kt` | The overlay rail — HD pill, adjust/overlay/draw entry points, download, and the undo/redo/original history pill |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/HdQualitySheet.kt` | Per-image Standard-vs-HD sheet with estimated output size |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/ImageFitMapper.kt` | Pure screen ↔ normalized ↔ bitmap-pixel mapping under `ContentScale.Fit`, shared by every overlay tool |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/AdjustImageScreen.kt` | The adjust overlay — rotate / flip / straighten / crop / resize, with `AdjustCallbacks` to stay under the param ceiling |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/AdjustStack.kt` | The adjust screen's op stack + cursor, the collapse rule for slider-driven ops, and its rotation-safe saver |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/CropGeometry.kt` | Pure crop-frame arithmetic — aspect presets across two spaces, corner drags, clamping, handle hit-testing |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/DrawImageScreen.kt` | The draw overlay — pen / highlighter / blur, colour strip, width slider, layer eye, live capture; `DrawCallbacks` keeps it under the param ceiling |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/DrawStack.kt` | The draw screen's stroke list + cursor (one stroke per undo) and its rotation-safe, point-quantising saver |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/ImageEditServices.kt` | The editor's ViewModel-supplied capabilities in one `@Immutable` bundle — keeps every screen under the ~15-param ceiling |
| `app/src/main/java/com/firestream/chat/domain/util/OverlayGeometry.kt` | The overlay screen's arithmetic — base size, handle positions, hit tests, drag→scale/rotation, the 15° snap and its wider cardinal pull. The one copy, for the same reason `StrokeGeometry` is |
| `app/src/main/java/com/firestream/chat/domain/util/StickerPack.kt` | The bundled pack as flat coloured parts in a `0..1` box — no assets to decode, so both renderers build each sticker from the same description |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/OverlayImageScreen.kt` | The overlay editor — emoji / stickers / text / shapes, the picker with four tabs, the layer eye; `OverlayCallbacks` keeps it under the param ceiling |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/OverlayCanvas.kt` | The photo, the placed objects, the dashed selection frame and the two handles, plus the one gesture that selects, moves, scales and rotates |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/OverlayPainter.kt` | The Compose half of drawing an overlay — the twin of the rasterizer's `android.graphics` half, sharing every number with it |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/OverlayStack.kt` | The overlay screen's history — whole-state snapshots (so delete is undoable) with a cursor, and its rotation-safe saver |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/EditorChrome.kt` | The shell every editor screen wears — top bar, tool button, failure banner, flatten scrim, and the colour strip shared by draw, text and shapes |
| `app/src/main/java/com/firestream/chat/ui/chat/ZoomableBox.kt` | Shared pinch-zoom/pan surface with hoistable `ZoomableState` and an optional content-size clamp; `detectZoomAndPan` splits zoom/pan from an enclosing pager's swipe |
| `app/src/main/java/com/firestream/chat/ui/chat/ZoomCropSurface.kt` | `ZoomableBox` whose zoom means a crop: keeps a host-owned `PendingCrop` in step with the gestures (restore once, clamp on resize, gestures write back) and draws the chosen shape's frame over the photo |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/PendingCrop.kt` | The crop a fullscreen photo would get — zoom viewport + crop-shape preset + decoded size — with its frame arithmetic and saver; carried from the viewer into the preview on Edit |
| `app/src/main/java/com/firestream/chat/ui/chat/imageedit/ViewportGeometry.kt` | Pure arithmetic between a zoom surface's `(scale, offset)` and the normalized frame of the photo it shows — the crop a zoomed preview page sends, the pan clamp, and the restore after a rotation |
| `app/src/main/java/com/firestream/chat/ui/chat/FullscreenImageViewer.kt` | Tap-to-open viewer + `FullscreenImagePager` (swipeable gallery, zoom/pan via `ZoomCropSurface`, pans clamped to the decoded photo, crop-shape pill bottom-left; Edit hands the pending crop to `ChatViewModel.editFromViewer`) |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatMediaGallery.kt` | `chatImageGallery()` — chat messages → gallery pages for the in-chat swipeable viewer |
| `app/src/main/java/com/firestream/chat/ui/search/SearchResults.kt` | Per-type search-result rendering, shared by in-chat and global search — the media grid the three-dot "Shared Media" item lands in |
| `app/src/main/java/com/firestream/chat/ui/search/SearchFilterBar.kt` | Search prefilter chips, date-range picker, active-filter summary — shared by both scopes |
| `app/src/main/java/com/firestream/chat/ui/components/SharedMediaTile.kt` | Shared grid-tile composable used by both the search media grid and `ProfileScreen` |
| `app/src/main/java/com/firestream/chat/ui/components/ScaledImageDecoder.kt` | Coil decoder via Android `ImageDecoder` — avoids the `BitmapFactory` subsample-to-black bug on large old images |
| `app/src/main/java/com/firestream/chat/ui/profile/ProfileScreen.kt` | Profile / chat-detail screen — its Shared Media section reuses `SharedMediaTile` |
| `app/src/test/java/com/firestream/chat/data/util/MediaFileManagerTest.kt` | Local file path semantics |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryLocalUriTest.kt` | `localUri` Room round-trip |
| `app/src/test/java/com/firestream/chat/ui/chat/ImagePreviewScreenMultiTest.kt` | Batch preview — per-item captions, removal, send-all |
| `app/src/test/java/com/firestream/chat/ui/chat/PendingMediaTest.kt` | Edit-cursor derivation and the saver's fixed-field history encoding |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryHdPrecedenceTest.kt` | Per-item `isHd` beats the global preference; null falls through to it |
| `app/src/test/java/com/firestream/chat/domain/util/ImageEditGeometryTest.kt` | JVM dimension arithmetic — ceiling, quarter turns, crop rects, resize, op composition, size estimates |
| `app/src/test/java/com/firestream/chat/data/util/ImageEditRasterizerTest.kt` | Robolectric bitmap round-trips, edit-cache discard/sweep, size estimates |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/ImageFitMapperTest.kt` | Fit-rect mapping round-trips, letterbox and pillarbox |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/ViewportGeometryTest.kt` | Zoom ⇄ frame arithmetic on the JVM — the visible crop at each zoom and pan, the clamp on both axes, the frame's place on screen, and the restore round-trip into a box of the same and another shape |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/PendingCropTest.kt` | The pending crop on the JVM — a shape cut from the zoomed viewport, Original and an unsized photo, the pill's cycle, the saver |
| `app/src/test/java/com/firestream/chat/ui/chat/ImagePreviewScreenZoomTest.kt` | A zoom and the crop-shape pill are the crop that is sent — through Coil on a real JPEG: the flatten on Send and before Draw opens, the transfer into Adjust, a crop arriving from the viewer, the failure line, and the frame surviving a recreation |
| `app/src/test/java/com/firestream/chat/ui/chat/ImagePreviewScreenHistoryTest.kt` | Undo/redo/original⇄edited through the screen, and the vanished-step fallback |
| `app/src/test/java/com/firestream/chat/ui/chat/ImagePreviewScreenAdjustTest.kt` | The adjust → `landEdit` → `discard` join: what lands, what is orphaned, and which steps are named live |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/AdjustImageScreenTest.kt` | What the adjust screen hands back — the ops, the skipped no-op flatten, the history controls |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/AdjustStackTest.kt` | Undo/redo cursor, the collapse rule, `previewOps`, and the saver round-trip |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/CropGeometryTest.kt` | Crop drags on the JVM — past the opposite corner, past the photo's edge, and under an aspect lock |
| `app/src/test/java/com/firestream/chat/domain/util/StrokeGeometryTest.kt` | Stroke arithmetic on the JVM — width against the long edge, midpoint smoothing, the layer split, resolution-independent mosaic blocks |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/DrawImageScreenTest.kt` | The draw screen under a real pointer — a swipe becomes one normalized stroke, the layer eye does not change the output, undo/redo per stroke, and a `Bundle` round-trip |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/DrawStackTest.kt` | Stroke cursor, the discarded redo tail, and the saver's quantised, thinned encoding |
| `app/src/test/java/com/firestream/chat/domain/util/OverlayGeometryTest.kt` | Overlay arithmetic on the JVM — resolution-independent size, local⇄world round-trips, rotated hit tests, handle corners, drag→scale/rotation, and the snap tolerances |
| `app/src/test/java/com/firestream/chat/domain/util/StickerPackTest.kt` | The hand-written pack's invariants — unique ids, whole points, every coordinate inside its box |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/OverlayStackTest.kt` | Placement/delete as steps, drags that collapse into a placement, the caps, and the saver round-trip for all four kinds |
| `app/src/test/java/com/firestream/chat/ui/chat/imageedit/OverlayImageScreenTest.kt` | What the overlay screen hands back — the four tabs, delete and its undo, the layer eye not changing the output, and a `Bundle` round-trip |
| `app/src/main/java/com/firestream/chat/ui/chat/FullscreenImageViewer.kt` | The fullscreen single viewer and swipeable pager; Edit, Save and Close side by side, the first two opt-in per host and each handed the photo on screen |
| `app/src/test/java/com/firestream/chat/ui/chat/FullscreenOverlayControlsTest.kt` | The controls — no chevron, granted actions shown at once, opt-in per host, the pager's Edit gets the current page |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatViewModelViewerEditTest.kt` | Edit-from-viewer state machine — local file vs download, unreadable-file refetch, spinner only for a download, failure, cancel, double tap, consume, the placeholder key Ready carries |
| `app/src/main/java/com/firestream/chat/ui/components/OnEnterSettled.kt` | Fires once an `AnimatedVisibility`'s enter has landed — what lets the preview close the viewer it was opened from only once it is opaque over it |
| `app/src/test/java/com/firestream/chat/ui/components/OnEnterSettledTest.kt` | Not during the fade, once when landed, and still once when a dismissal cuts the enter short |
| `app/src/test/java/com/firestream/chat/ui/chat/FullscreenImageRequestTest.kt` | Viewer and preview agree on the memory-cache key: the viewer files its bitmap under it, the preview names it as its placeholder for an unedited original only |

**Entry point:** image picker in `ChatScreen.kt` (`PickMultipleVisualMedia`, capped at `MAX_GALLERY_PICK`) → `ImagePreviewScreen` (editor rail per page) → **`AdjustImageScreen`**, **`DrawImageScreen`** or **`OverlayImageScreen`**, each of which replaces the preview's content rather than floating over it, flattens its layer once on Done and hands back a URI → `ChatMessageSender.sendMediaMessages()` → `MessageRepositoryImpl.sendMediaMessage()` per item, **sequentially** (each send decodes a full bitmap, so a batch must not run concurrently). Each item carries its own `isHd`; `null` there is what makes the global preference the fallback.

**Second entry point — a photo already sent:** `FullscreenImagePager` / `FullscreenImageViewer` (Edit, beside Save and Close) → `ChatViewModel.editFromViewer()` → `MediaFileManager.downloadAndSave()` only if there is no readable local file → `ImageEditRasterizer.importSource()` copies it into `cacheDir/edits/sources/` → `OverlaysState.viewerEdit = Ready(uri)` → `ChatScreen` consumes it into a one-item `pendingMedia` (carrying the viewer's memory-cache key as the preview's placeholder) and closes the viewer only once the preview has faded in over it (`OnEnterSettled`) → the same `ImagePreviewScreen` path as above. The copy, not the message's own file, is the batch's `originalUri`, and the result is a new message.

The `ui/chat/imageedit/` package is Phases 1–4 of [`docs/plans/image-editor.md`](../docs/plans/image-editor.md) — the toolbar shell and per-image HD (1), the rasterizer and the fit mapper (2), the adjust screen (3) and the draw screen (4). Phase 5a extracted the shared picker into its own cross-cutting feature (see *Emoji / Sticker Picker* below), 5b added the overlay screen and its three editor-only tabs, and Phase 6 added the entry from the fullscreen viewer above.

---

## Emoji / Sticker Picker (one shell, several hosts)

One panel mounted from several places, which is why it is a feature of its own rather than
a detail of any screen. `PickerPanel` owns the chrome — a search control, an island of
tabs, a delete button for the host's selection — and **implements no content**: what a tab
shows is the host's business, so a tab's own state stays with the host that has it.

**The island's tabs are declared by the host** (`docs/plans/image-editor.md` §2.8), and
a host that declares one tab renders no island at all — so nothing ever ships greyed-out
and unreachable, and the three pre-existing hosts look exactly as they did before the
shell existed.

| Host | Tabs | Where |
|---|---|---|
| Composer | Emoji · Stickers (the library) | `ChatScreen.kt` — `ComposerPickerPanel`, with a backspace key on both tabs |
| Reaction sheet | Emoji | `ChatScreen.kt` — `EmojiHandlerPanel(mode = REACTION)`, with the quick-reactions strip |
| Caption bar | Emoji | `ImagePreviewScreen.kt` — `EmojiHandlerPanel(mode = TEXT_INPUT)` |
| Editor overlay | Emoji · Sticker (the bundled pack) · Text · Shapes | `imageedit/OverlayImageScreen.kt` — the one host with a selection to delete |

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/ui/chat/picker/PickerPanel.kt` | The shell — search button ⇄ expanded field, the tab island, the delete button, the compact layout (results above the search row), and the slots a host fills |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/PickerPanelState.kt` | The active tab, the open search and the per-tab query, hoistable so a host can move the panel while a search runs |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/PickerTab.kt` | Which tabs exist (`STICKER` is the editor's bundled pack, `STICKER_LIBRARY` the user's library, `GIF` is declared by nobody) and the `PickerSelection` a tab hands back |
| `app/src/main/java/com/firestream/chat/ui/chat/ComposerPickerPanel.kt` | The composer's two-tab picker, its callbacks bundle, `composerSearchLayout` (where the picker goes while a search runs), and `StickerSuggestionStrip` above the composer |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/StickerLibraryTab.kt` | The library as a tab: the pack row (Recents, favourites, packs), the active pack's grid, search by emoji, the empty state's import button |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/EmojiTab.kt` | The emoji grid, the category rail, the frozen recents order, the long-press size drag, and the quick-reactions strip a host mounts as a header |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/EmojiGridLayout.kt` | Pure layout arithmetic for the emoji grid — which row and column each item lands on once headers span a row, and which side of the held cell the size panel fits on |
| `app/src/test/java/com/firestream/chat/ui/chat/picker/EmojiGridLayoutTest.kt` | That the last cell of a row is the last column, whatever headers sit above it, and that the size panel flips left rather than leave the grid |
| `app/src/main/java/com/firestream/chat/ui/chat/EmojiHandlerPanel.kt` | The one-tab alias the reaction sheet and the caption bar call — `EmojiMode`, the two controls that differ by host, and the shared `PickerBackspaceKey` |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/EmojiSearchData.kt` | Bundled emoji → keyword table for in-panel search; no network |
| `app/src/main/java/com/firestream/chat/ui/chat/SwipeReactionPanel.kt` | The compact swipe-to-react strip; shares `QUICK_REACTION_EMOJIS` with the picker |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatInfoManager.kt` | Owns `recentEmojis` in `OverlaysState` and the DataStore write behind it |
| `app/src/test/java/com/firestream/chat/ui/chat/picker/PickerPanelTest.kt` | That the one-tab hosts are unchanged by the extraction, and the chrome only a multi-tab host sees |
| `app/src/test/java/com/firestream/chat/ui/chat/picker/PickerPanelStateTest.kt` | The state's query and tab rules, and `composerSearchLayout`: emoji search is a strip on the keyboard, sticker and GIF search fill the screen |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/StickerTab.kt` | The bundled pack as a grid, drawn by the same code that paints a placed sticker |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/TextTab.kt` | A single-line draft, its solid/outline style and the shared colour strip; places on an explicit Add |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/ShapeTab.kt` | Rectangle / rounded / ellipse / line / arrow, each previewed in the colour and fill it will be placed with |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatInfoManagerRecentEmojiTest.kt` | Recents ordering and the cap, on the manager side of the panel |

**Entry point:** whichever host mounts it. The panel is `internal` and takes no Hilt
dependency — recents arrive as a `List<String>` and leave as a callback, so every host is
testable without a ViewModel.

---

## Shared Lists

Lists shared into chats as a live `LIST` message bubble. Subcollection-based item storage.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/usecase/list/SendListUpdateToChatsUseCase.kt` | Multi-repo orchestration — creates `LIST` message, updates list, writes history |
| `app/src/main/java/com/firestream/chat/data/repository/ListRepositoryImpl.kt` | List CRUD + share/unshare flows |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreListSource.kt` | `lists/{id}/items/{itemId}` subcollection, denormalized counts |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreListHistorySource.kt` | `lists/{id}/history/{entryId}` audit trail |
| `app/src/main/java/com/firestream/chat/ui/lists/ListsScreen.kt` | Lists tab in MainScreen pager |
| `app/src/main/java/com/firestream/chat/ui/lists/ListsViewModel.kt` | List index + counts |
| `app/src/main/java/com/firestream/chat/ui/lists/ListDetailScreen.kt` | List edit screen |
| `app/src/main/java/com/firestream/chat/ui/lists/ListDetailViewModel.kt` | 30s debounce for `LIST` update bubble fan-out |
| `app/src/main/java/com/firestream/chat/ui/lists/SharedListsScreen.kt` | Lists shared into a specific chat |
| `app/src/main/java/com/firestream/chat/ui/lists/SharedListsViewModel.kt` | Per-chat list filter |
| `app/src/main/java/com/firestream/chat/ui/lists/AvatarStack.kt` | Participant stack |
| `app/src/main/java/com/firestream/chat/ui/lists/ListContextSheet.kt` | Context actions sheet |
| `app/src/main/java/com/firestream/chat/ui/lists/ListShareSheet.kt` | Manage-sharing sheet (toggle a list's chats on and off) |
| `app/src/main/java/com/firestream/chat/ui/lists/ShareListPanel.kt` | Share-to-chats panel from the Lists tab — the shared `ChatPickerPanel` |
| `app/src/main/java/com/firestream/chat/ui/chat/ListBubble.kt` | `LIST` message rendering |
| `app/src/main/java/com/firestream/chat/ui/chat/CreateListSheet.kt` | Create-and-share flow from chat |
| `app/src/test/java/com/firestream/chat/data/repository/ListRepositoryImplRaceTest.kt` | Concurrent-mutation safety |
| `app/src/test/java/com/firestream/chat/data/repository/ListRepositoryUnshareTest.kt` | Unshare semantics |
| `app/src/test/java/com/firestream/chat/ui/lists/ListDetailViewModelTest.kt` | VM behaviour |
| `app/src/test/java/com/firestream/chat/ui/lists/ListDetailViewModelCoalesceTest.kt` | Debounce coalescing |
| `app/src/test/java/com/firestream/chat/ui/lists/ListsViewModelTest.kt` | Index VM |
| `app/src/test/java/com/firestream/chat/domain/usecase/list/SendListUpdateToChatsUseCaseTest.kt` | Use-case orchestration |

**Entry point:** Lists tab → `ListDetailScreen` → mutations debounce in `ListDetailViewModel` → `SendListUpdateToChatsUseCase`.

---

## Polls

Create / vote / close. Lives inside the message stream (no separate collection).

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/repository/PollRepository.kt` | Vote + close interface |
| `app/src/main/java/com/firestream/chat/data/repository/PollRepositoryImpl.kt` | Vote/close, delegates message updates |
| `app/src/main/java/com/firestream/chat/data/repository/PollMapper.kt` | `Poll` ↔ Firestore map serialisation |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSource.kt` | `pollData` field on the message subcollection |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatPollManager.kt` | Slice owner for poll send/vote intents |
| `app/src/main/java/com/firestream/chat/ui/chat/PollBubble.kt` | Vote UI inside a message bubble |
| `app/src/main/java/com/firestream/chat/ui/chat/CreatePollSheet.kt` | Create-poll bottom sheet |
| `app/src/test/java/com/firestream/chat/data/local/entity/PollSerializationTest.kt` | Round-trip serialisation |

**Entry point:** chat composer "+" → `CreatePollSheet` → `ChatPollManager.send()`.

---

## Presence (online / last seen)

RTDB-backed presence with a Cloud Function mirror to Firestore.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/AppLifecycleObserver.kt` | Process-level `DefaultLifecycleObserver` — drives RTDB enter/leave |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/RealtimePresenceSource.kt` | `.info/connected` pattern + `onDisconnect()` registration |
| `app/src/main/java/com/firestream/chat/data/repository/UserRepositoryImpl.kt` | Combines RTDB presence into the `observeUser()` stream |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreUserSource.kt` | Persisted `lastSeen` mirror |
| `functions/index.js` | `syncPresenceToFirestore` Cloud Function — RTDB → Firestore mirror with `lastSeen` transaction guard |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/RealtimePresenceSourceTest.kt` | State-machine reconnect/teardown |
| `app/src/test/java/com/firestream/chat/data/repository/UserRepositoryImplPresenceTest.kt` | Presence stream merge |

**Entry point:** `FireStreamApp.onCreate` → `ProcessLifecycleOwner.observe(AppLifecycleObserver)`.

---

## Offline Outbox (queued, idempotent sends)

A retryable send — text, photo, video, document, voice note, location, forward — is a `SENDING` row in `messages` plus one unique WorkManager job named after it. The row is the queue: it survives leaving the chat, process death and reboot, every attempt resumes from what the row already records, and the client-generated id is at once the Room key, the Firestore document id and the Storage object name, so a lost acknowledgement can never produce a second copy. Design and decisions: `docs/plans/offline-outbox.md`; the convention: [PATTERNS.md#sends-are-idempotent-by-client-id-and-drained-by-outboxworker](PATTERNS.md#sends-are-idempotent-by-client-id-and-drained-by-outboxworker).

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/local/entity/MessageEntity.kt` | `MessageRecord` — the backend's columns, Room's partial entity — embedded beside the local-only columns: `localUri`, `isStarred` and the five `outbox*` columns a snapshot upsert cannot reach |
| `app/src/main/java/com/firestream/chat/data/local/dao/MessageDao.kt` | `insertOutbox`, `upsertRecord` / `updateRecord`, `markSent` / `acknowledge` around the one `clearOutbox`, the step column updates, `getQueuedMessages` (the SQL half of `OutboxJob`), `failQueuedOfOtherTypes`, `requeueForRetry` |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxJob.kt` | What a row is queued for — `SEND`, `TOMBSTONE` or nothing — and whether its next attempt uploads; the one Kotlin rule the worker, the scheduler and the repository share |
| `app/src/main/java/com/firestream/chat/data/outbox/BlockCheck.kt` | The per-peer block-list read behind every send, cached 30 s and shared by the repository and the worker, so the worker's re-check is a cache hit when the repository just asked |
| `app/src/main/java/com/firestream/chat/data/outbox/SendTarget.kt` | `Peer(id)` / `NoPeer` — who a send is for. `forChat` resolves it from the chat's Room row and refuses on doubt; `fromColumn` reads it back from `outboxRecipientId`. The only place a target is built ([PATTERNS.md#the-repository-decides-who-a-send-is-for](PATTERNS.md#the-repository-decides-who-a-send-is-for)) |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxFiles.kt` | Stages a send's input under `filesDir/outbox/<id>.<ext>` before the enqueue; the durable-or-not rule for a SENT row's `localUri`; a document's picked type as the copy's extension |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxScheduler.kt` | One unique work per message id (`outbox-<id>`): KEEP on compose, REPLACE on retry, CONNECTED, exponential backoff from 10 s, the expedited rule (API 31+, or an upload); `requeueAll` on app start |
| `app/src/main/java/com/firestream/chat/data/worker/OutboxWorker.kt` | One attempt: the `OutboxJob` gate, the authoritative block check, the foreground for an upload, `OutboxSender.send`, the transient / permanent verdict, give-up after 8 executed attempts |
| `app/src/main/java/com/firestream/chat/data/worker/WorkerForeground.kt` | `tryPromoteForeground`, `dataSyncForegroundInfo`, `ensureLowImportanceChannel` — the foreground scaffolding `OutboxWorker` and `ApkDownloadWorker` share |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxSender.kt` | The pipeline an attempt resumes — compress / transcode → thumbnail → upload → encrypt once → write → the SENT transaction — plus the tombstone of a row deleted while queued and the per-id lock; an uploaded document's staged copy moves into `DocumentFiles` before the write |
| `app/src/main/java/com/firestream/chat/data/outbox/MessageWriter.kt` | `encode` / `write` / `send` by `SendTarget` — Signal ciphertext for a peer, plaintext otherwise |
| `app/src/main/java/com/firestream/chat/data/util/KeyedMutex.kt` | One lock per key, dropped once its last user leaves — `OutboxSender`'s per-message lock |
| `app/src/main/java/com/firestream/chat/data/remote/source/SendErrorClassifier.kt` | `SendFailure` and the backend-neutral verdicts (input and policy errors permanent, plain IO transient), walking the cause chain |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirebaseSendErrorClassifier.kt` | Firestore status codes and Storage error codes, transient or permanent |
| `app/src/pocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseSendErrorClassifier.kt` | v0 stub — neutral rules only |
| `app/src/main/java/com/firestream/chat/data/remote/source/MessageSource.kt` | `sendMessage` / `sendPlainMessage` under a client id with `ifAbsent`; `deleteIfExists`, the tombstone that never creates |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSource.kt` | `writeMessage` — `set()` on the first attempt, flush then create-if-absent on every later one, both under the 30 s ack timeout; `deleteIfExists`; the newer-only chat preview |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | Insert → block verdict (a definite block refuses, an unanswerable one queues; a timer keeps the strict rule) → stage → enqueue (`enqueueSend`); the retry's fresh budget; the forward through the outbox; the tombstone path of `deleteMessage`; the own-echo heal (`acknowledgeOwnEcho`) shared by the listener and the sync |
| `app/src/main/java/com/firestream/chat/domain/model/AppError.kt` | `RecipientBlockedException` — the one permanent "blocked" failure, raised by the repository and the worker alike |
| `app/src/main/java/com/firestream/chat/di/AppModule.kt` | `SystemModule.provideWorkManager`, injected as a `Provider` so the graph never builds WorkManager early |
| `app/src/main/java/com/firestream/chat/FireStreamApp.kt` | `requeueQueuedSends` → `OutboxScheduler.requeueAll` on start |
| `app/src/main/AndroidManifest.xml` | The `SystemForegroundService` merge with `dataSync`, which the upload foreground needs on Android 14+; `ACCESS_NETWORK_STATE` for the connectivity hint |
| `app/src/main/java/com/firestream/chat/domain/util/ConnectivityObserver.kt` | Display-only "is there a validated network" flow — the UI's interface, never read by the send path |
| `app/src/main/java/com/firestream/chat/data/util/AndroidConnectivityObserver.kt` | The default-network callback behind it; validated-only, so a captive portal reads as offline |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatInfoManager.kt` | Mirrors the observer into `SessionState.isOffline`, the top bar's "Waiting for network…" |
| `app/src/main/java/com/firestream/chat/ui/chat/MessageInfoScreen.kt` | A SENDING row reads "Waiting to send" — it is queued, not necessarily in flight |
| `app/src/test/java/com/firestream/chat/data/worker/OutboxWorkerTest.kt` | Verdicts, the budget, the block re-check, the tombstone exemptions, the foreground — via `TestListenableWorkerBuilder` |
| `app/src/test/java/com/firestream/chat/data/outbox/OutboxSchedulerTest.kt` | The work request, KEEP vs REPLACE, the expedited rule on both sides of API 31, `requeueAll` |
| `app/src/test/java/com/firestream/chat/data/outbox/OutboxJobTest.kt` | The queued-for rule and the upload rule |
| `app/src/test/java/com/firestream/chat/data/outbox/OutboxSenderTest.kt` | The pipeline against an in-memory table: resume points, encrypt once, the SENT transaction and the kept `localUri`, the tombstone |
| `app/src/test/java/com/firestream/chat/data/outbox/OutboxFilesTest.kt` | Staging on Robolectric — the extension carries the type, durability, the sweep |
| `app/src/test/java/com/firestream/chat/data/local/dao/MessageDaoOutboxColumnsTest.kt` | A record upsert cannot touch the local columns; `markSent` and `acknowledge` clear the outbox |
| `app/src/test/java/com/firestream/chat/data/local/dao/MessageDaoOutboxQueueTest.kt` | The queue queries: own SENDING rows of sendable types, the stranded-type flip, the budget reset, the echo dedupe |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryBlockTest.kt` | A definite block fails the row, an unanswerable check queues it; the timer keeps the strict rule |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryRetryTest.kt` | The flip, the budget, REPLACE, the block check against the row's target |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryForwardTest.kt` | A forward queues with attempt count 0 and stages nothing |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryDeleteTest.kt` | The tombstone path of a queued or failed own message vs the backend-first delete |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryMediaSendFailureTest.kt` | A staging failure leaves a FAILED bubble; success points the row at the copy |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositorySnapshotTest.kt` | Own echoes: pending never moves a status, acknowledged heals through `acknowledge`, on the listener and the sync alike; chat entry leaves a queued row alone |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSourceTest.kt` | Which SDK call each attempt makes, the ack timeout on first and later attempts, the tombstone body |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirebaseSendErrorClassifierTest.kt` | The verdict table |
| `app/src/test/java/com/firestream/chat/data/util/AndroidConnectivityObserverTest.kt` | Validated vs captive portal, the loss, and unregistering once nothing collects |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatInfoManagerTest.kt` | `isOffline` on the session slice: the current value on open, both transitions, group chats too |

**Entry point:** `ChatMessageSender` → `MessageRepositoryImpl.send*` inserts the row and returns once `OutboxScheduler.enqueue` has it → WorkManager runs `OutboxWorker` when connected → `OutboxSender.send` → `MessageWriter.write` → `FirestoreMessageSource.writeMessage`; the bubble's clock turns into a tick when `MessageDao.markSent` (or the acknowledged echo's `acknowledge`) lands. Tap-to-retry is `retryFailedMessage` → `OutboxScheduler.retryNow`. What the user sees while the queue waits: the top bar's "Waiting for network…" (`ConnectivityObserver` → `SessionState.isOffline`) and "Waiting to send" in the message-info sheet — both display-only, and neither gates a send.

---

## E2E Encryption (with release-mode opt-out)

Signal Protocol message encryption. Disabled in debug builds; release users can opt out via Settings → Privacy.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/crypto/SignalManager.kt` | Encrypt / decrypt orchestration; one session `Mutex` per peer shared by both, pre-key replenishment after it is released under its own lock; `isCurrentIdentity` says whether a stored ciphertext's peer identity is still the published one |
| `app/src/main/java/com/firestream/chat/data/crypto/SignalProtocolStoreImpl.kt` | `SignalProtocolStore` backed by `SignalDatabase` |
| `app/src/main/java/com/firestream/chat/data/local/SignalDatabase.kt` | Dedicated `signal.db` — keys survive `AppDatabase` destructive migrations |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirebaseKeySource.kt` | `keyBundles/{userId}` pre-key bundle exchange |
| `app/src/main/java/com/firestream/chat/data/outbox/MessageWriter.kt` | `encode` / `write` / `send` — the build gate (`SUPPORTS_SIGNAL && !DEBUG`, a constructor value so tests can encrypt), `e2eEncryptionEnabledFlow` and the always-plaintext types (LOCATION) around the Signal branch |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxSender.kt` | Encrypts once per message: keeps `outboxCiphertext` and the peer identity on the row before the write, and a retry reuses it while that identity is still current |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSource.kt` | `sendMessage` writes ciphertext only; the chat preview of an encrypted message carries the type, never the text or caption |
| `app/src/main/java/com/firestream/chat/data/local/PreferencesDataStore.kt` | `e2eEncryptionEnabledFlow` (default `true`) |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsScreen.kt` | Privacy → Encryption toggle (release builds) |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsViewModel.kt` | Wires the toggle |
| `app/src/test/java/com/firestream/chat/data/local/SignalDatabaseSmokeTest.kt` | Dedicated DB smoke |
| `app/src/test/java/com/firestream/chat/data/crypto/SignalManagerTest.kt` | Real libsignal, two parties: per-peer lock for encrypt + encrypt and encrypt + decrypt, lock released before the pre-key publish, a peer identity stops being current on re-registration |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositorySyncDecryptTest.kt` | The chat-list sync finishes decrypt-and-insert once started, even when cancelled meanwhile |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSourceTest.kt` | The encrypted write holds no `content`, and its chat preview no plaintext |
| `app/src/test/java/com/firestream/chat/data/outbox/MessageWriterTest.kt` | The encrypt-or-plaintext policy, one test per plaintext reason |
| `app/src/test/java/com/firestream/chat/data/local/dao/MessageDaoOutboxColumnsTest.kt` | A `MessageRecord` upsert cannot touch `localUri`, the star or the outbox columns; `markSent` and `acknowledge` are what clear the outbox |
| `app/src/test/java/com/firestream/chat/ui/settings/SettingsViewModelTest.kt` | Toggle persistence |

**Entry point:** every 1:1 send reaches `MessageWriter` — `OutboxSender.send()` calls `encode` (skipped when the row already holds ciphertext for the peer's current identity) and then `write` for text / media / voice / location / forward; the broadcast fan-out calls `send`, which does both — and `encode` picks plaintext or Signal by the row's `SendTarget`.

---

## Push Notifications

FCM-driven message + call wake-ups. Per-user unread counts in Firestore.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/remote/fcm/FCMService.kt` | `FirebaseMessagingService` — extracts payload, hands the named message to `reconcileFromPush`, marks delivered, suppresses for active chat |
| `app/src/main/java/com/firestream/chat/data/remote/fcm/ActiveChatTracker.kt` | `@Singleton` — tracks the foreground chatId for suppression, and for the push reconcile's yield to the open chat's listener |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `reconcileFromPush(chatId, messageId)` — the one message a push names, through the listener's `reconcileRawMessage` (own-echo guard, decrypt, auto-download); a thrown read is retried three times over the reconnect instant; skipped for the active chat; never throws |
| `app/src/main/java/com/firestream/chat/data/remote/source/MessageSource.kt` | `fetchMessage` — one document by id, `null` when the backend does not hold it |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSource.kt` | `fetchMessage` — a single `get()` under the client id |
| `app/src/pocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseMessageSource.kt` | `fetchMessage` — v0 stub, answers `null` (no FCM in that flavor) |
| `app/src/main/java/com/firestream/chat/data/worker/MediaBackfillScheduler.kt` | `retryDownloads` — a failed auto-download queues one unique `MediaBackfillWorker` run (`media-download-retry`, KEEP; UNMETERED for "Wi-Fi only", nothing for "never") |
| `app/src/main/java/com/firestream/chat/MainActivity.kt` | Reads `chatId` / `senderId` extras → deep link |
| `functions/index.js` | `sendPushNotification` (on message create) + `sendCallPushNotification` (on call create) Cloud Functions |
| `app/src/main/AndroidManifest.xml` | `FirebaseMessagingService` + `POST_NOTIFICATIONS` permission |
| `app/src/test/java/com/firestream/chat/data/remote/fcm/ActiveChatTrackerTest.kt` | Suppression behaviour |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryPushReconcileTest.kt` | A pushed photo lands in Room and downloads with the chat closed; the open chat, a blocked sender, a missing document and a fetch that keeps failing write nothing; a fetch that fails once is tried again; the own-echo guard; a failed download, and "Wi-Fi only" off Wi-Fi, queue the retry once |
| `app/src/test/java/com/firestream/chat/data/worker/MediaBackfillSchedulerTest.kt` | The retry request per auto-download preference |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSourceTest.kt` | `fetchMessage` reads one document, `null` for a missing one |

**Entry point:** Firestore message create → `sendPushNotification` Cloud Function → `FCMService.onMessageReceived` → `MessageRepository.reconcileFromPush` (the message and its media reach the phone before the chat is opened — FCM stores and forwards, so this is what runs on reconnect for a closed chat) alongside the delivery receipt → notification or in-app marker. A download the network drops is re-queued through `MediaBackfillScheduler.retryDownloads`; the daily backfill and the next chat open remain the fallbacks if the process dies mid-download.

---

## In-App Updater + APK Release Pipeline

Sideload-style updates: a tag-driven CI workflow publishes signed APKs + per-flavor manifests to GitHub Releases, and the app fetches the manifest, downloads with sha256 verification, and hands off to the system installer.

| File | Role |
|---|---|
| `.github/workflows/release-apk.yml` | Tag-triggered CI — signs APK, renders `latest-{flavor}.json`, attaches everything to a GitHub Release |
| `app/build.gradle.kts` | Release `signingConfig` from env / `local.properties`; per-flavor `BuildConfig.UPDATE_MANIFEST_URL` |
| `app/src/main/java/com/firestream/chat/domain/model/AppUpdate.kt` | Manifest model + `UpdateCheckResult` |
| `app/src/main/java/com/firestream/chat/domain/repository/AppUpdateRepository.kt` | Interface — check / download / install + `DownloadProgress` |
| `app/src/main/java/com/firestream/chat/data/remote/update/UpdateManifestSource.kt` | OkHttp fetch of `latest-{flavor}.json` + `JSONObject` parse |
| `app/src/main/java/com/firestream/chat/data/repository/AppUpdateRepositoryImpl.kt` | Compares manifest `versionCode` against `BuildConfig.VERSION_CODE` |
| `app/src/main/java/com/firestream/chat/data/util/ApkDownloader.kt` | Streaming download to `cacheDir/apk_updates/`, sha256 verification, progress flow |
| `app/src/main/java/com/firestream/chat/data/util/ApkInstaller.kt` | FileProvider + `ACTION_VIEW` install intent |
| `app/src/main/java/com/firestream/chat/data/worker/UpdateCheckWorker.kt` | 24h periodic check, low-priority notification on new version |
| `app/src/main/java/com/firestream/chat/FireStreamApp.kt` | Schedules `UpdateCheckWorker` on app start |
| `app/src/main/java/com/firestream/chat/MainActivity.kt` + `navigation/NavGraph.kt` | `openSettings` extra → deep-link to Settings on notification tap |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsViewModel.kt` | `UpdateUiState` slice + `checkForUpdate()` / `downloadAndInstall()` |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsScreen.kt` | "Check for updates" row + Available / Downloading / Failed dialogs |
| `app/src/main/AndroidManifest.xml` + `app/src/main/res/xml/file_paths.xml` | `REQUEST_INSTALL_PACKAGES` + `apk_updates` cache path for FileProvider |
| `docs/RELEASING.md` | Keystore generation, GitHub Secrets, tag-and-publish workflow |
| `app/src/test/java/com/firestream/chat/data/remote/update/UpdateManifestSourceTest.kt` | JSON parse coverage |
| `app/src/test/java/com/firestream/chat/data/repository/AppUpdateRepositoryImplTest.kt` | Version-comparison branches |

**Entry point:** push a `v*` tag → release workflow publishes manifest + APK → `UpdateCheckWorker` (24h) or Settings → Check for updates → `AppUpdateRepository.checkForUpdate()`.

---

## Dot Commands & Timer

Composer-driven `.command` grammar plus the timer as the first command. Typing `.` at message start opens a vertical palette of registered commands; `.timer.set` mounts an hh:mm:ss wheel widget that, on send, persists a TIMER message and schedules a synchronized `AlarmManager` alarm on both devices that rings at the server-stamped fire time.

The sender also picks the alarm's **style** and **sound** (`TimerAlarmStyle` / `TimerAlarmSound`), and both sync to every participant so a shared timer rings identically on both phones. Sound is symbolic, never a `content://` URI — a device-local URI wouldn't resolve on the recipient's phone. Two invariants worth knowing before touching this: the legacy `Message.timerSilent` boolean is still written in agreement with the style enum (older clients read only that), and channel sound/vibration are frozen at creation, which is why there is one channel per sound.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/command/ChatCommand.kt` | Command interface + `ChatCommandWidget` + `CommandPayload` sealed type |
| `app/src/main/java/com/firestream/chat/domain/command/CommandRegistry.kt` | Hilt multibound registry — `@IntoSet` lets each command self-register |
| `app/src/main/java/com/firestream/chat/domain/command/CommandPath.kt` | Value type wrapping `List<String>` (`["timer", "set"]`) |
| `app/src/main/java/com/firestream/chat/domain/command/CommandComposerParser.kt` | Pure parser — composer text → `ParsedCommand(completedSegments, pendingFilter)` |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatCommandsState.kt` | 6th `ChatUiState` slice — palette, navigation path, filter, active widget, exact-alarm banner |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatCommandsManager.kt` | Owns `CommandsState` slice; drives palette and widget mount from composer text |
| `app/src/main/java/com/firestream/chat/ui/chat/CommandPalette.kt` | Vertical scrollable overlay of available commands |
| `app/src/main/java/com/firestream/chat/ui/chat/CommandChip.kt` | AssistChip render of the `.command.subcommand` portion in the composer |
| `app/src/main/java/com/firestream/chat/ui/chat/ExactAlarmBanner.kt` | In-app banner deep-linking to system "Alarms & reminders" settings on Android 12+ when SCHEDULE_EXACT_ALARM is denied |
| `app/src/main/java/com/firestream/chat/ui/chat/command/TimerCommand.kt` | `ChatCommand` impl for `.timer` + `.timer.set` (multibound via `di/CommandModule.kt`) |
| `app/src/main/java/com/firestream/chat/ui/chat/widget/TimerPickerWidget.kt` | hh:mm:ss wheel-picker widget mounted above composer + alarm style/sound segmented rows |
| `app/src/main/java/com/firestream/chat/ui/chat/widget/TimerSetWidgetState.kt` | Widget-local state + duration math + alarm style/sound selection |
| `app/src/main/java/com/firestream/chat/ui/chat/TimerMessageBubble.kt` | Bubble content for TIMER — alarm icon + live countdown / "Timer ended" / struck-through "Cancelled" + caption |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatTimerReactor.kt` | Observes `ChatUiState.messages` for TIMER state changes; schedules / cancels alarms idempotently for both sender and recipient |
| `app/src/main/java/com/firestream/chat/data/timer/TimerAlarmScheduler.kt` | Thin AlarmManager wrapper with exact-vs-inexact fallback; also queues/cancels the NORMAL-style re-alert nag |
| `app/src/main/java/com/firestream/chat/data/timer/TimerAlarmReceiver.kt` | BroadcastReceiver for fire / re-alert / dismiss → posts the alarm notification (insistent + auto-silence for INSISTENT) + flips state to COMPLETED. Holds `TimerAlarmRequest`, which decodes the intent extras incl. the pre-enum legacy fallback |
| `app/src/main/java/com/firestream/chat/data/timer/TimerNotificationChannel.kt` | One `timer_alarm_v2_*` channel **per `TimerAlarmSound`** (channel sound/vibration are frozen at creation); deletes the superseded `timer_alarms`. All ALARM-grade audio attributes |
| `app/src/main/java/com/firestream/chat/data/timer/BootCompletedReceiver.kt` + `BootRestoreLogic.kt` | Re-registers RUNNING-and-still-future timers after device reboot, carrying style/sound/`otherUserId` so the alarm survives intact |
| `app/src/main/java/com/firestream/chat/domain/model/Message.kt` + `TimerState.kt` | TIMER message type + `timerDurationMs` / `timerStartedAtMs` / `timerState` fields |
| `app/src/main/java/com/firestream/chat/domain/model/TimerAlarm.kt` | `TimerAlarmStyle` (SILENT/NORMAL/INSISTENT) + `TimerAlarmSound` (ALARM/RINGTONE/GENTLE) — the sender's synced alarm choice. Read via `Message.alarmStyle` / `.alarmSound`, never the raw nullable fields |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendTimerMessage` / `cancelTimer` / `pauseTimer` / `resumeTimer` / `markTimerCompleted` (server-stamped fire time) |
| `app/src/main/AndroidManifest.xml` | `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED` permissions + receiver registrations |

**Entry point:** type `.` in any chat composer → `ChatCommandsManager.onComposerTextChanged()` → `CommandPalette` opens → tap `.timer.set` (or type it) → `TimerPickerWidget` mounts → send → `MessageRepository.sendTimerMessage()` → `ChatTimerReactor` schedules alarms on both sides via `TimerAlarmScheduler`.

---

## Message Reminders (snooze)

Long-press → Snooze schedules a device-local exact alarm for a message; the fired notification (sender + text snapshot, "+1 hour"/"Done" actions) deep-links back to the chat, which scrolls to and highlights the message. Clone of the timer alarm pipeline, but notification-grade (not alarm-grade) and local-only (no Firestore, like starred messages).

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/model/Reminder.kt` + `ReminderScheduleOutcome.kt` | Domain model with message/sender snapshot fields; EXACT vs INEXACT_FALLBACK outcome |
| `app/src/main/java/com/firestream/chat/domain/repository/ReminderRepository.kt` | schedule / cancel / reschedule / observePending / observePendingIdsForChat |
| `app/src/main/java/com/firestream/chat/domain/reminder/SnoozePreset.kt` + `SnoozePresets.kt` | Pure preset computation (In 1 hour / This evening / Tomorrow morning; past presets roll to next day; detected time prepended) |
| `app/src/main/java/com/firestream/chat/domain/reminder/DateTimeDetector.kt` + `DetectedTimeParser.kt` | Pure detection contract + EN/DE span-text → future-instant parser |
| `app/src/main/java/com/firestream/chat/data/reminder/AndroidDateTimeDetector.kt` | TextClassifier locates date/time spans; parser resolves the value (best-effort, null on anything) |
| `app/src/main/java/com/firestream/chat/data/local/entity/ReminderEntity.kt` + `dao/ReminderDao.kt` | `reminders` table, PK messageId (one pending reminder per message) — AppDatabase v23 |
| `app/src/main/java/com/firestream/chat/data/reminder/ReminderRepositoryImpl.kt` | Room row + alarm armed/cancelled together |
| `app/src/main/java/com/firestream/chat/data/reminder/ReminderAlarmScheduler.kt` + `ReminderAlarmScheduling.kt` | Exact-alarm wrapper (clone of TimerAlarmScheduler), idempotent per messageId |
| `app/src/main/java/com/firestream/chat/data/reminder/ReminderAlarmReceiver.kt` | FIRED (post + consume row) / SNOOZE_1H (rebuild from intent snapshots, re-arm now+1h) / DONE |
| `app/src/main/java/com/firestream/chat/data/reminder/ReminderNotificationPoster.kt` + `ReminderNotificationChannel.kt` | Shared notification builder (tag `message_reminder`); `message_reminders` channel — notification-grade, NOT alarm-grade |
| `app/src/main/java/com/firestream/chat/data/reminder/ReminderActionLogic.kt` + `ReminderBootRestoreLogic.kt` | Pure +1h math; pure boot classify (re-arm future / post overdue) |
| `app/src/main/java/com/firestream/chat/data/timer/BootCompletedReceiver.kt` | Now restores BOTH timers and reminders after reboot |
| `app/src/main/java/com/firestream/chat/ui/chat/SnoozePickerSheet.kt` + `SnoozeOptions.kt` | ModalBottomSheet picker; `SnoozeOptionsList` shared with the `.remind` widget (presets + date/time dialogs) |
| `app/src/main/java/com/firestream/chat/ui/chat/MessageBubble.kt` | Snooze ⇄ Cancel-reminder menu button + bell indicator (via holder fields — param ceiling!) |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatMessageLoader.kt` + `ChatMessagesState.kt` | `pendingReminderIds` combined into the MessagesState slice |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatMessageActions.kt` | snoozeMessage / cancelReminder / detectSnoozeTime; sender-name + media-snapshot resolution |
| `app/src/main/java/com/firestream/chat/ui/chat/command/RemindCommand.kt` + `widget/RemindWidget.kt` | `.remind` leaf command; composer widget targeting reply-target-else-newest |
| `app/src/main/java/com/firestream/chat/ui/reminders/ScheduledRemindersScreen.kt` + `ScheduledRemindersViewModel.kt` | Overview list (Settings → Scheduled Reminders): tap to jump, swipe to cancel |
| `app/src/main/java/com/firestream/chat/navigation/NavGraph.kt` + `MainActivity.kt` | `targetMessageId` route param + `EXTRA_MESSAGE_ID`; `DeepLinkRequest` re-drive incl. `onNewIntent` warm delivery |
| `app/src/main/java/com/firestream/chat/data/remote/fcm/FCMService.kt` | Forwards messageId so push taps also scroll-to-message |

**Entry points:** long-press bubble → Snooze → `SnoozePickerSheet` → `ChatViewModel.snoozeMessage()`; or `.remind` in the composer → `RemindWidget`. Fired path: `ReminderAlarmReceiver` → notification → `MainActivity` (`EXTRA_MESSAGE_ID`) → `Routes.chat(targetMessageId)` → `ChatScreen` jump + highlight.

---

## Video Sharing

Chats can send video — record with the camera or pick one from the gallery. Videos are typed `VIDEO`, guarded at 3 min / 100 MB before the optimistic insert, then transcoded to a configurable quality (480p/720p/1080p, default 720p, set in Settings) with a JPEG thumbnail extracted and uploaded alongside. Bubbles show the thumbnail with a play overlay and duration badge, IMAGE-matched sizing/progress/retry; tapping opens a fullscreen ExoPlayer overlay that mirrors the existing image viewer.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/local/PreferencesDataStore.kt` | `videoQualityFlow` preference (480p/720p/1080p, default 720p) |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsScreen.kt` | Video quality picker |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsViewModel.kt` | Wires the picker |
| `app/src/test/java/com/firestream/chat/ui/settings/SettingsViewModelTest.kt` | Video quality persistence |
| `app/src/main/java/com/firestream/chat/data/util/VideoTranscoder.kt` | Media3 Transformer wrapper — `ensureWithinLimits` (pre-insert guard), `transcode` to quality preset, per-request `VideoFrameDecoder` thumbnail extraction |
| `app/src/test/java/com/firestream/chat/data/util/VideoTranscoderLogicTest.kt` | Limit-guard and quality-mapping logic coverage |
| `app/src/main/java/com/firestream/chat/data/remote/source/MessageSource.kt` | `mediaThumbnailUrl` added to the cross-flavor `sendMessage`/`sendPlainMessage` contract |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSource.kt` | `mediaThumbnailUrl` param (firebase flavor) |
| `app/src/pocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseMessageSource.kt` | `mediaThumbnailUrl` param (pocketbase flavor) |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendMediaMessage` — shared image/video entry; runs the limit guard before the optimistic insert |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxSender.kt` | Video branch — transcodes, uploads the thumbnail, uploads the mp4; a retry skips whichever of those the row already records |
| `app/src/main/java/com/firestream/chat/domain/model/AppError.kt` | `MediaLimitException` → `AppError.Validation` mapping |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryMediaSendFailureTest.kt` | Video limit-guard / send-failure coverage |
| `app/src/test/java/com/firestream/chat/data/outbox/OutboxSenderTest.kt` | Video step order and resume — a retry re-uploads without re-transcoding |
| `app/src/main/java/com/firestream/chat/ui/chat/MessageBubble.kt` | `VIDEO` branch — thumbnail, play overlay, duration badge |
| `app/src/main/java/com/firestream/chat/ui/starred/StarredMessagesScreen.kt` | `VIDEO` branch in the starred list |
| `app/src/test/java/com/firestream/chat/ui/chat/MessageBubbleSmokeTest.kt` | `VIDEO` bubble render smoke coverage |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatOverlaysState.kt` | `fullscreenVideo` slice beside `fullscreenImage` |
| `app/src/main/java/com/firestream/chat/ui/chat/FullscreenVideoPlayer.kt` | `PlayerView` in `AndroidView` — release-on-dispose, pause-on-`ON_PAUSE`, `BackHandler` dismiss |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatViewModel.kt` | `showFullscreenVideo` / dismiss actions on the overlays slice |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatViewModelFullscreenImageTest.kt` | Fullscreen video overlay-slice coverage (same file as the image-viewer tests) |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatScreen.kt` | Fullscreen player mount + composer wiring — gallery picker widened to `ImageAndVideo`, `READ_MEDIA_VIDEO` on 13+, new Record-video attachment option via `CaptureVideo` |
| `app/src/main/java/com/firestream/chat/ui/chat/ImagePreviewScreen.kt` | Video mode — decoded frame + play badge, caption flow unchanged |
| `app/src/test/java/com/firestream/chat/test/fakes/FakeMessageRepository.kt` | Fake updated for the `mediaThumbnailUrl` send signature |
| `app/src/test/java/com/firestream/chat/ui/share/SharePickerViewModelTest.kt` | Gallery video share coverage |
| `app/src/main/res/values/strings.xml` | `reply_preview_video`, `attachment_record_video` |

**Entry point:** record or pick a video in `ChatScreen.kt`'s composer → `ImagePreviewScreen` (video mode) → `MessageRepositoryImpl.sendMediaMessage()` guards via `VideoTranscoder.ensureWithinLimits` → `OutboxSender.send()` transcodes, uploads a thumbnail → `MessageBubble` `VIDEO` branch renders it → tap opens `ChatViewModel.showFullscreenVideo()` → `FullscreenVideoPlayer`.

---

## Message Search (in-chat + global)

One search, two scopes. In a chat it is an overlay owned by `ChatSearchManager` on the overlays slice; from the chat list's magnifier it is `Routes.SEARCH`, its own NavHost destination — *not* a panel over the list, because the list is a page inside `MainScreen`'s `HorizontalPager` and the filter chips' `LazyRow` would fight the pager for horizontal drags. Both scopes share one repository method, one DAO query, and the whole of the rendering; the only thing global adds is a `sender · chat` label and a jump-to-message on tap. A blank query plus an active chip is *browse mode* — the filter, not the text, does the selecting.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/model/MessageSearchFilter.kt` | The filter (type / starred / date range), and `MessageSearchLimits` — TEXT 50, GLOBAL 100, BROWSE 200 |
| `app/src/main/java/com/firestream/chat/domain/model/MessageSearchResults.kt` | Results + the `truncated` flag the "200+" rendering depends on |
| `app/src/main/java/com/firestream/chat/domain/usecase/message/SearchMessagesUseCase.kt` | The blank-query guard for both scopes (blank **and** no filter → empty) |
| `app/src/main/java/com/firestream/chat/domain/repository/MessageRepository.kt` | `searchMessages(chatId: String?, query, filter)` — null `chatId` is global |
| `app/src/main/java/com/firestream/chat/data/local/dao/MessageDao.kt` | The one compile-time-verified query; every clause a nullable/zero short-circuit, incl. `deletedAt IS NULL` |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | Browse-mode short-circuit, partial-word matching from `PARTIAL_MATCH_MIN_LENGTH` (single letters still pinned to the whole word), truncation read off the **raw** row count |
| `app/src/main/java/com/firestream/chat/ui/search/SearchResults.kt` | Per-type rendering — media grid / icon rows / text rows; `resultLabel` is the caller's to resolve |
| `app/src/main/java/com/firestream/chat/ui/search/SearchFilterBar.kt` | Chips, date-range picker, active-filter summary, `searchResultsSummary` |
| `app/src/main/java/com/firestream/chat/ui/search/GlobalSearchScreen.kt` | The global destination: auto-focused field, chips, hint / empty / results |
| `app/src/main/java/com/firestream/chat/ui/search/GlobalSearchViewModel.kt` | Debounce (300 ms typing, immediate on a chip), chat + contact maps, `recipientIdFor` |
| `app/src/main/java/com/firestream/chat/ui/search/GlobalSearchLabels.kt` | Pure `sender · chat` labelling; collapses to the title alone in a 1:1 |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatSearchManager.kt` | The in-chat scope: same debounce asymmetry, on the overlays slice |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatScreen.kt` | In-chat search pane; result tap jumps within the conversation, media tiles open the fullscreen viewer |
| `app/src/main/java/com/firestream/chat/navigation/NavGraph.kt` | `Routes.SEARCH`; a global result navigates `Routes.chat(…, targetMessageId = …)` |

**Entry points:** chat list magnifier → `Routes.SEARCH` → `GlobalSearchScreen` → tap a result → `Routes.chat(targetMessageId)` (media tiles included — a cross-chat fullscreen pager would need gallery args spanning chats). In a chat: the ⋮ menu's Search / Shared Media → `ChatSearchManager.openSearchWithFilter()`.

---

## Chat Picker (share intent / forward / share a list)

One panel behind every "send this to a chat" flow. An incoming share intent, a
message forward and sharing a list all ask the user the same question, and each
used to answer it differently — a full screen with search and multi-select for
the share intent, a bare `AlertDialog` list for the other two. `ChatPickerPanel`
is that one answer: it owns the bar, the search field, the rows, the checkboxes
and the send button; each host supplies only the preview of *what* is being sent
and what sending means. Forward and share-a-list mount it as a slide-in overlay
over their host (a message and a list are already in hand, so neither needs a
route); the share intent keeps its own NavHost destination, which is where its
content resolution lives. An overlay slides with `ScreenMotion`, the same curve and duration the NavHost
pushes a screen with, so "the same panel" is also the same motion.
The panel hands over chats and each host sends by chat id. Who a send is for is
not decided here: `data/outbox/SendTarget.kt` resolves it from the chat's Room row
([PATTERNS.md#the-repository-decides-who-a-send-is-for](PATTERNS.md#the-repository-decides-who-a-send-is-for)).
`Chat.partnerIdHint` is for the chat route a host navigates to afterwards.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/ui/components/ChatPickerPanel.kt` | The panel: bar, preview slot, search (it filters its own rows), chat rows, send button |
| `app/src/main/java/com/firestream/chat/ui/components/ChatPickerOverlay.kt` | The panel mounted over an existing screen — slide in/out, back, the latched target, the search query, the ticked chats, one send per opening |
| `app/src/main/java/com/firestream/chat/ui/components/ChatTargets.kt` | The pure rules, free of Compose so a ViewModel can call them: `pickerDisplayName`, `partnerIdHint` (navigation only), `filterChatsByName`, `destinationLabel` |
| `app/src/main/java/com/firestream/chat/ui/chat/ForwardMessagePanel.kt` | "Forward to…" over the conversation; supplies the preview of the message being forwarded |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatMessageActions.kt` | The forward fan-out: one `forwardMessage(message, chat.id)` per picked chat, then the confirmation label |
| `app/src/main/java/com/firestream/chat/ui/lists/ShareListPanel.kt` | "Share list to…" over the Lists tab; supplies the list preview |
| `app/src/main/java/com/firestream/chat/ui/main/MainScreen.kt` | Locks its pager while a tab has a full-screen panel over it, so a sideways drag on the panel isn't a tab swipe |
| `app/src/main/java/com/firestream/chat/ui/components/ScreenMotion.kt` | The slide a screen arrives with, shared with `navigation/NavGraph.kt` so the overlay moves exactly as the routed panel does |
| `app/src/main/java/com/firestream/chat/ui/share/SharePickerScreen.kt` | "Share to…" — the panel plus the shared-content preview (text + link preview, one image, many) |
| `app/src/main/java/com/firestream/chat/ui/share/SharePickerViewModel.kt` | Chats, participant profiles, search, selection and the send fan-out for the share intent |
| `app/src/test/java/com/firestream/chat/ui/components/ChatPickerTargetsTest.kt` | The pure rules — `partnerIdHint` is empty for a group or broadcast, no row ever shows a raw uid |
| `app/src/test/java/com/firestream/chat/ui/components/ChatPickerPanelUiTest.kt` | Selection-before-send, the two empty states |
| `app/src/test/java/com/firestream/chat/ui/chat/ForwardMessagePanelUiTest.kt` | Multi-select forwarding, search keeping the ticks, one send per opening, selection reset between openings |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatMessageActionsForwardTest.kt` | The fan-out: each picked chat forwarded by its id, the confirmation, a failure reported instead |

**Entry points:** long-press a message → Forward; Lists tab → long-press a list → Share; another app's share sheet → FireStream → `Routes.SHARE_PICKER`.

---

## File Messages (documents)

A DOCUMENT is any picked file that is not a photo or a video. It carries its name, size and
type (`Message.fileName` / `fileSize` / `mimeType`), and its bytes live in
`filesDir/documents/<id>.<ext>` on both phones. They never go through MediaStore. The bubble is a
type-coloured card. A tap runs `ChatFileActions`, which gets a readable local copy (downloading it
if needed) and hands the screen a `FileLaunch`; the screen opens it through `FileProvider` with the
system *Open with* chooser. Design, decisions and the remaining steps (previews, send sheet, audio):
`docs/plans/file-handling.md`.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/util/FileKind.kt` | Pure classification (type, then extension) into kinds, `isRisky`, `hasPreview`; the badge label (short extension, else kind); `formatFileSize`, `MAX_DOCUMENT_BYTES` |
| `app/src/main/java/com/firestream/chat/data/util/DocumentFiles.kt` | The documents dir, the extension rule, a picked uri's name/size, the sent copy's move out of the outbox, `discard` |
| `app/src/main/java/com/firestream/chat/data/util/MediaFileManager.kt` | `downloadFor` — a DOCUMENT downloads into `DocumentFiles` with plain file IO |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxSender.kt` | `keepDocument` after the upload; `tombstone` discards a moved copy |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendMediaMessage` fills the file fields; `ensureLocalFile` — kept copy or download, remembered on the row |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatFileActions.kt` | Open / Share / Save for one chat: the preparing set (spinners), `FileLaunch` events, the type offered to other apps |
| `app/src/main/java/com/firestream/chat/ui/chat/FileMessageBubble.kt` | The file card (badge, name, `PDF · 1.4 MB` line, caption), the text and PDF previews, the inline `VoiceMessagePlayer` for a local audio file |
| `app/src/main/java/com/firestream/chat/ui/chat/RiskyFileDialog.kt` | The confirm before opening an APK or a script |
| `app/src/main/java/com/firestream/chat/domain/util/FilePreview.kt` | What a file bubble can show — `None`, `Text`, `Pdf` |
| `app/src/main/java/com/firestream/chat/domain/util/TextPreview.kt` | The text rule — at most 32 KB, binary refused, BOM/UTF-16/CRLF, a cut character dropped; a PDF text layer's cleanup |
| `app/src/main/java/com/firestream/chat/domain/util/FilePreviewSource.kt` | The bubble's way to a preview without a UI→data import; bound to `FilePreviewLoader` in `AppModule` |
| `app/src/main/java/com/firestream/chat/data/util/FilePreviewLoader.kt` | Builds a preview from the local copy on IO; LRU by path + mtime; a PDF's page 1 via `PdfRenderer` into `cacheDir/file_previews/`, under a `MediaProcessingLimiter` permit |
| `app/src/main/java/com/firestream/chat/data/util/PdfText.kt` | PdfBox-Android text layer of a PDF's first 3 pages (temp-file buffered) |
| `app/src/main/java/com/firestream/chat/ui/chat/SendFileSheet.kt` | `PendingFile` (picked uri + name/size, rotation-safe) and the confirm sheet — badge, caption, the 100 MB refusal |
| `app/src/main/java/com/firestream/chat/ui/components/FileIntents.kt` | `FileProvider` uri + *Open with* / share-sheet chooser |
| `app/src/main/res/xml/file_paths.xml` | `documents/` and `outbox/` roots the provider may grant |
| `app/src/test/java/com/firestream/chat/domain/util/FileKindTest.kt` | Classification, risk, size formatting |
| `app/src/test/java/com/firestream/chat/data/util/DocumentFilesTest.kt` | Extension rule and path safety, describe, adopt, discard |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatFileActionsTest.kt` | Launch events, risky flag, fallbacks, failure notice, double tap, save |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryEnsureLocalFileTest.kt` | Kept copy vs download vs refusal |
| `app/src/test/java/com/firestream/chat/domain/util/TextPreviewTest.kt` | Decoding rule — encodings, truncation, binary and blank refusals |
| `app/src/test/java/com/firestream/chat/data/util/FilePreviewLoaderTest.kt` | Reads only the head, caches, None for other kinds; a PdfBox-written PDF's first pages, a damaged PDF |
| `app/src/test/java/com/firestream/chat/ui/chat/SendFileSheetUiTest.kt` | Name/size/caption to send, the over-limit refusal, rotation |
| `app/src/test/java/com/firestream/chat/ui/chat/FileMessageBubbleUiTest.kt` | Preview above the card; Show more / Show less; where the preview ends |

**Entry point:** `MessageBubble` DOCUMENT branch → `FileMessageBubble` → `MessageBubbleCallbacks.onOpenFile` → `ChatViewModel.openFile` → `ChatFileActions.request` → `MessageRepository.ensureLocalFile` → `ChatViewModel.fileLaunches` → `ChatScreen` → `FileIntents.open`.

---

## Stickers & GIFs

A sticker is an immutable file named by the SHA-256 of its bytes, kept in `filesDir/stickers/`.
A pack is an ordered list of sticker ids. Room holds the library, and `StickerRepository` is the
only way into it. Stickers arrive by import: from WhatsApp's sticker folder through a folder grant,
or from picked `.webp`, `.was` and `.tgs` files and `.wastickers` archives. Every imported byte is
untrusted, so the parsers check sizes against the buffer and the archive reader works under caps.

A sticker file is a WebP or a Lottie animation. A Lottie sticker is stored as gzip-compressed JSON
(`<id>.tgs`), whichever container it came in, with its first frame beside it as `<id>.tgs.png`.
Lottie plays it in the bubble, and grids and previews draw the PNG.

A `STICKER` message points at a sticker by that hash and carries no bytes of its own. The file is
one shared Storage object, `stickers/<id>.<ext>`, uploaded the first time anyone sends the sticker.
A receiver hashes what it downloads before storing it. A `GIF` message is plain media sent as it
is: one upload per message, and the file is kept in `filesDir/documents/`, not in the gallery.
A sticker bubble has no fill and no tail, and a GIF bubble is the photo layout with a badge.
Both animate through a decoder attached per request. Previews show the first frame.

A sticker is sent from the composer's picker, whose second tab is the library, or from the
suggestion strip that appears while the composer holds one emoji.

The keyboard can insert a picture into the composer. `KeyboardContentReceiver` wraps the composer's
input connection, names the picture types to the keyboard and takes its `commitContent`. A GIF goes
out as a `GIF` message. Any other picture is put into the `SAVED` pack by
`StickerRepository.saveSticker`, converted to WebP by `StickerMaker.convert` when it is no sticker
file, and sent as a sticker. A GIF picked from the gallery or shared into the app is a `GIF` message
too: `MessageRepositoryImpl.sendMediaMessage` hands an `image/gif` to `sendGifMessage`.

A sticker can be made from a photo. `StickerMaker` prepares a draft in `cacheDir/sticker-maker/`:
the photo, its subject cut out by ML Kit, and the subject with a white outline. The maker's
screen picks one of the three and a crop. `StickerGeometry` places the picture in the square for
the preview and for the saved file alike. The result is a 512 × 512 WebP of at most 100 KB,
stored like an imported sticker.

The library is backed up under the account. Each pack is one Firestore document,
`stickerPacks/{packId}`, a manifest that names its stickers by hash. `StickerSyncWorker` uploads
every pack that changed, and a listener on the user's own manifests restores them into Room while
a sticker surface is open. A restored row comes before its file, which is fetched when the sticker
is first shown. The same document is what **View pack** reads for the pack a received sticker
names, and **Add pack** copies it into the library as an `INSTALLED` pack.
Design and the remaining steps: `docs/plans/stickers-and-gifs.md`.

The editor's bundled vector pack (`domain/util/StickerPack.kt`) is a different thing. It is listed
under *Image / Media Pipeline*.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/model/Sticker.kt` | `Sticker` and `StickerFormat` (extension, mime type, and where a format's first frame is: `stillPathOf`) |
| `app/src/main/java/com/firestream/chat/domain/model/StickerPack.kt` | `StickerPack`, `StickerPackKind`, `StickerPackPreview`, `WhatsAppStickerFile`, `StickerImportResult` |
| `app/src/main/java/com/firestream/chat/domain/repository/StickerRepository.kt` | Observe packs and recents, list the WhatsApp folder, import, `toggleFavourite`, pack and sticker edits, `markUsed`, `ensureFile`, `viewPack`, `installPack`, `prepareStickerDraft`, `createSticker` |
| `app/src/main/java/com/firestream/chat/domain/util/WebpContainer.kt` | Pure RIFF chunk walk: dimensions, the animation flag, the raw EXIF chunk |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerFiles.kt` | The content-addressed directory: the 1 MB and 2048 px limits, `isValidId`, `fileFor`, `stillFor`, `open` for a uri, and `rowLock`, which keeps a file and its `stickers` row together. `store` tells a WebP, a `.tgs` and bare Lottie JSON apart by their first bytes. `storeReceived` keeps a downloaded file unchanged or not at all |
| `app/src/main/java/com/firestream/chat/data/sticker/LottieContainer.kt` | A Lottie sticker out of its container: a `.tgs` (gzip) or the JSON a `.was` holds. The inflate cap, the nesting cap, no key twice in one object, what makes JSON an animation, no image assets, the layer count with precompositions laid out |
| `app/src/main/java/com/firestream/chat/data/sticker/LottieThumbnails.kt` | The first frame of a Lottie sticker as a PNG, which `StickerFiles` writes beside the file for grids and previews. Also the last gate: an animation Lottie cannot parse, build and draw is not stored |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerDownloads.kt` | A received sticker's local copy: fetched once, hashed against the id the message claims, stored with its row. A mismatch stores nothing |
| `app/src/main/java/com/firestream/chat/data/remote/source/StickerObjectSource.kt` | `ensureUploaded`: the url of a sticker's shared object, uploading only when the backend does not hold it. `urlIfPresent`: the url of a sticker known only by its id |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirebaseStickerObjectSource.kt` | `stickers/<id>.<ext>` in Firebase Storage: look up, then upload when missing |
| `app/src/pocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseStickerObjectSource.kt` | Uploads through this flavor's `StorageSource`, which is a stub in v0 |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerUploads.kt` | One lock per sticker and the write of `stickers.remoteUrl`, shared by a send and the backup |
| `app/src/main/java/com/firestream/chat/data/remote/source/StickerPackSource.kt` | Pack manifests on the backend: the listener on the owner's packs, fetch by id, write, delete. `RemoteStickerPack`, `StickerPackChanges` |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreStickerPackSource.kt` | `stickerPacks/{packId}`: the document shape, the listener that reports removals apart from changes, the acknowledgement timeout |
| `app/src/pocketbase/java/com/firestream/chat/data/remote/pocketbase/PocketBaseStickerPackSource.kt` | Stub. `isSupported` is false, so nothing is synced on this flavor |
| `firestore.rules` | `stickerPacks/{packId}`: get for any signed-in user, list and write for the owner |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerManifest.kt` | Library rows to a manifest and back; what of a manifest is let into the library; the cap on stickers per manifest |
| `app/src/main/java/com/firestream/chat/data/worker/StickerSyncWorker.kt` | The backup: uploads each pending pack's files, writes its manifest, marks it synced if it did not change meanwhile; deletes the manifest of a tombstone |
| `app/src/main/java/com/firestream/chat/data/worker/StickerSyncScheduler.kt` | The one unique sync run, queued whenever a pack is unsynced; cancelled on sign-out |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerLibrarySync.kt` | The restore: listens while the packs are observed, merges through `StickerDao.applyRemotePack`, and fences a sign-out |
| `app/src/main/java/com/firestream/chat/data/repository/AuthRepositoryImpl.kt` | `signOut` runs inside the restore's fence, so no restore writes into the cleared tables |
| `app/src/main/java/com/firestream/chat/FireStreamApp.kt` | Queues the sticker sync on start, for a pack left pending |
| `app/src/main/java/com/firestream/chat/MainActivity.kt` | Provides `LocalStickerFetcher` from `StickerRepository.ensureFile` for every screen |
| `app/src/main/java/com/firestream/chat/data/remote/source/StickerRef.kt` | A message's sticker id and pack id, crossing the `MessageSource` boundary as one value |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendStickerMessage` (the row is built from the library; which pack ids are shared) and `sendGifMessage` (the 8 MB guard) |
| `app/src/main/java/com/firestream/chat/data/outbox/OutboxSender.kt` | `withStickerUrl` (the url from `StickerUploads`, persisted on the message) and the GIF's document route |
| `app/src/main/java/com/firestream/chat/data/util/MediaFileManager.kt` | `downloadFor` routes a `STICKER` to `StickerDownloads` and a `GIF` to `DocumentFiles` |
| `app/src/main/java/com/firestream/chat/ui/components/MessageTypeLabel.kt` | `placeholderLabel` and `stickerLabel`, the words a sticker or a GIF is shown as in a preview |
| `app/src/main/java/com/firestream/chat/ui/components/StickerImage.kt` | `StickerImage`, the one sticker renderer, and `rememberAnimatedImageRequest`, which attaches the animated decoder to one request. `LibraryStickerImage` draws a library sticker and asks `LocalStickerFetcher` for a file that is not there yet. The `format` switch: a Lottie sticker is drawn by Lottie, from a local file only |
| `app/src/main/java/com/firestream/chat/ui/chat/MessageBubble.kt` | `StickerBubbleContent`, the `GIF` branch of the photo layout, `hasStillPreview` and `rememberMessageStillModel` for the reply, forward and starred previews |
| `app/src/main/java/com/firestream/chat/domain/util/StickerSearch.kt` | Pure: stickers by emoji tag across packs, and which composer text earns suggestions |
| `app/src/main/java/com/firestream/chat/ui/chat/ComposerPickerPanel.kt` | The composer's picker (Emoji and Stickers tabs) and `StickerSuggestionStrip` |
| `app/src/main/java/com/firestream/chat/ui/chat/picker/StickerLibraryTab.kt` | The Stickers tab: pack row with the **+** that opens the maker, grid, search, the import button of an empty library |
| `app/src/main/java/com/firestream/chat/ui/chat/KeyboardContentReceiver.kt` | Takes a GIF or a sticker the keyboard inserts into the composer, and the rule for what each type is sent as |
| `app/src/main/java/com/firestream/chat/ui/chat/StickerActionsSheet.kt` | The sheet a tap on a sticker bubble opens: add to or remove from the favourites, and *View pack* for a sticker that names one |
| `app/src/main/java/com/firestream/chat/ui/stickers/StickerPackSheet.kt` | The pack preview: name, stickers, *Add pack*, or that the library holds it already |
| `app/src/main/java/com/firestream/chat/ui/stickers/StickerPackPreviewViewModel.kt` | Looks a pack up each time the sheet opens, and installs it |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatInfoManager.kt` | Mirrors packs, recents and the favourite ids into `OverlaysState` in one update |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatMessageSender.kt` | `sendSticker`: sends by id and pack, then `markUsed` |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatMessageActions.kt` | `toggleStickerFavourite`: the repository decides the direction; a received sticker the library lacks is fetched first |
| `app/src/main/java/com/firestream/chat/data/sticker/WaStickerMetadata.kt` | Pack id, name, publisher and emojis out of a WhatsApp WebP's EXIF chunk |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerPackArchive.kt` | `.wastickers` / zip reader under caps on entry count, bytes per entry and total bytes |
| `app/src/main/java/com/firestream/chat/data/sticker/WhatsAppStickerFolder.kt` | One child-documents query over the granted folder, newest first |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerText.kt` | Cleans a pack name or publisher read from a file |
| `app/src/main/java/com/firestream/chat/data/local/dao/StickerDao.kt` | Pack and item queries; every multi-statement write is one transaction that marks the pack `PENDING`. The tombstone of a deleted pack, the compare-and-set `markSynced`, and `applyRemotePack`, which holds the restore's merge rules |
| `app/src/main/java/com/firestream/chat/data/local/entity/StickerEntity.kt` | `stickers`, `sticker_packs`, `sticker_pack_items`, `StickerSyncState` |
| `app/src/main/java/com/firestream/chat/data/repository/StickerRepositoryImpl.kt` | Which pack an imported sticker joins, the import key a re-import finds its pack by, the counts. Asks for a backup after every pack change. `ensureFile`, `viewPack`, `installPack` |
| `app/src/main/java/com/firestream/chat/data/local/PreferencesDataStore.kt` | `recentStickerIdsFlow`, device-only |
| `app/src/main/java/com/firestream/chat/ui/stickers/StickerLibraryViewModel.kt` | The library screen's state: packs, the open pack and its selection, the WhatsApp folder view |
| `app/src/main/java/com/firestream/chat/ui/stickers/StickerLabels.kt` | A pack's shown name (the unnamed kinds included), whether it can be renamed, the import summary line |
| `app/src/main/java/com/firestream/chat/ui/stickers/StickerLibraryScreen.kt` | The pack list with *Create* above the import routes, a pack's grid, the folder and file pickers, rename / move / delete dialogs, the shared `StickerCell` and `StickerTopBar` |
| `app/src/main/java/com/firestream/chat/ui/stickers/WhatsAppImportScreen.kt` | The granted folder as a multi-select grid with *Select all* and *Import N* |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsScreen.kt` | The *Import stickers* row |
| `app/src/main/java/com/firestream/chat/navigation/NavGraph.kt` | `Routes.STICKERS`, `Routes.STICKER_CREATE` |
| `app/src/main/java/com/firestream/chat/domain/model/StickerDraft.kt` | `StickerDraft` and `StickerDraftImage` (a prepared photo's pictures), `StickerCrop` (scale and offset in the square) |
| `app/src/main/java/com/firestream/chat/domain/util/StickerGeometry.kt` | Pure: where a picture sits in the square sticker, the crop's limits, the outline's width and the ring it is stamped along |
| `app/src/main/java/com/firestream/chat/data/sticker/SubjectCutout.kt` | ML Kit subject segmentation behind one call. Waits for the model, and answers `null` without Play services or on any failure |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerEncoder.kt` | A bitmap to a lossy WebP, at the first quality that stays under 100 KB |
| `app/src/main/java/com/firestream/chat/data/sticker/StickerMaker.kt` | The draft's files, the trim to the subject, the outline, and the render of one picture onto the 512 px canvas. Runs under `MediaProcessingLimiter` |
| `app/src/main/java/com/firestream/chat/ui/stickers/create/StickerCreateViewModel.kt` | The maker's state: the draft, cutout or photo, outline, crop, emojis, the pack |
| `app/src/main/java/com/firestream/chat/ui/stickers/create/StickerCreateScreen.kt` | The maker: the photo picker, the square preview with pinch and drag, the choices, the emoji sheet |
| `app/src/test/java/com/firestream/chat/domain/util/WebpContainerTest.kt` | Byte fixtures, truncated and oversize chunks |
| `app/src/test/java/com/firestream/chat/data/sticker/WaStickerMetadataTest.kt` | Metadata present, absent and malformed |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerFilesTest.kt` | Hash naming, the limits, the `cacheDir` fence of `open` |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerPackArchiveTest.kt` | Each cap, entry names, title and author, a `.was` file's animation |
| `app/src/test/java/com/firestream/chat/data/sticker/LottieContainerTest.kt` | Both containers, the inflate and nesting caps, JSON that is no animation, image assets, a repeated key, precompositions that loop or multiply |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerFilesLottieTest.kt` | One stored shape for both containers, the first frame really drawn, a missing one drawn again, an animation Lottie refuses, bare JSON refused on receive |
| `app/src/test/java/com/firestream/chat/ui/chat/LottieStickerUiTest.kt` | Which renderer draws a Lottie sticker in the bubble, a grid and a preview, and the placeholder for a file not here yet |
| `app/src/test/java/com/firestream/chat/test/LottieFixtures.kt` | `animation`, `tgs`, `was`: hand-built Lottie stickers that Lottie parses and draws |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerDownloadsTest.kt` | The hash mismatch, the refusals, a repeat receive, and a receive during a refused archive's undo |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryStickerGifSendTest.kt` | What is queued for a sticker and a GIF, the shared pack kinds, the GIF size guard |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirebaseStickerObjectSourceTest.kt` | Look up first, upload when missing, an upload refused because the object exists by now |
| `app/src/test/java/com/firestream/chat/data/outbox/OutboxSenderTest.kt` | The second send of a sticker uploads nothing; a GIF is never compressed; both resume points |
| `app/src/test/java/com/firestream/chat/data/sticker/WhatsAppStickerFolderTest.kt` | The folder filter and sort |
| `app/src/test/java/com/firestream/chat/data/local/dao/StickerDaoTest.kt` | Ordering queries and the transaction methods; tombstones, the compare-and-set mark, the newer-only merge, two packs with one import key |
| `app/src/test/java/com/firestream/chat/data/repository/StickerRepositoryImplTest.kt` | Grouping, de-duplication, a refused archive, pack edits; the tombstone, the fetch of a missing file, viewing and installing a pack; a made sticker's pack, emojis and refusals |
| `app/src/test/java/com/firestream/chat/data/repository/StickerRepositoryTestFactory.kt` | `newStickerRepository` with mocked collaborators |
| `app/src/test/java/com/firestream/chat/data/worker/StickerSyncWorkerTest.kt` | Pending to synced, each file uploaded once, the delete, a pack changed during its upload, the verdicts |
| `app/src/test/java/com/firestream/chat/data/worker/StickerSyncSchedulerTest.kt` | The unique work's name, policy, constraint and backoff; nothing queued while every pack is synced |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerLibrarySyncTest.kt` | When the listener runs, whose manifests are merged, nothing written after a sign-out, a sign-out that finishes though its caller is cancelled |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerUploadsTest.kt` | One upload for two callers at once, the url kept on the row |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerManifestTest.kt` | What a manifest carries, and what of one is refused |
| `app/src/testFirebase/java/com/firestream/chat/data/remote/firebase/FirestoreStickerPackSourceTest.kt` | The document round trip, the encoded import key, a document of any shape, the listener's changes |
| `app/src/test/java/com/firestream/chat/ui/stickers/StickerPackPreviewViewModelTest.kt` | Load, add, already in the library, a second look-up dropping the first |
| `app/src/test/java/com/firestream/chat/ui/stickers/StickerPackSheetTest.kt` | The preview's states, the *View pack* row, the fetch when a cell shows a sticker without a file |
| `app/src/test/java/com/firestream/chat/test/WebpFixtures.kt` | Builders for WebP, EXIF and zip test bytes |
| `app/src/test/java/com/firestream/chat/ui/stickers/StickerLibraryViewModelTest.kt` | Selection, pack reorder, both import routes, the summary line |
| `app/src/test/java/com/firestream/chat/ui/stickers/StickerLibraryScreenTest.kt` | The empty state, the loading state, the unnamed packs' labels |
| `app/src/test/java/com/firestream/chat/domain/util/StickerGeometryTest.kt` | The fit, the zoom and the offset, the crop's limits, the outline's width and ring |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerEncoderTest.kt` | The size loop: the first quality that fits, nothing lower tried, a sticker too large at every quality |
| `app/src/test/java/com/firestream/chat/data/sticker/StickerMakerTest.kt` | Real pixels: the trim, the outline, the draft's files, where a crop lands on the canvas, the path fence |
| `app/src/test/java/com/firestream/chat/ui/stickers/create/StickerCreateViewModelTest.kt` | Which picture the choices amount to, the crop's reset and limits, the emoji cap, the pack choice, one save |
| `app/src/test/java/com/firestream/chat/ui/stickers/create/StickerCreateScreenTest.kt` | What the maker offers with and without a cutout, the pack and emoji chips |
| `app/src/test/java/com/firestream/chat/domain/util/StickerSearchTest.kt` | Tag matching, the variation selector, one result per sticker, what earns suggestions |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatStickerManagersTest.kt` | The library mirror, `sendSticker` with `markUsed`, the favourite toggle and its fetch for a sticker the library lacks |
| `app/src/test/java/com/firestream/chat/ui/chat/KeyboardContentReceiverTest.kt` | The composer field's `EditorInfo` names the picture types, and a `commitContent` on its connection reaches the callback |
| `app/src/test/java/com/firestream/chat/ui/chat/ChatMessageSenderKeyboardContentTest.kt` | A keyboard GIF is sent as a GIF, any other picture is saved and sent as a sticker, and the keyboard's grant is always released |
| `app/src/test/java/com/firestream/chat/ui/chat/ComposerPickerPanelTest.kt` | The island and backspace key, the empty library, which pack id a pick carries, frozen Recents, the suggestion strip, the search field's focus, the compact emoji strip, a query surviving a move |
| `app/src/test/java/com/firestream/chat/test/fakes/StickerRepositoryMocks.kt` | `emptyStickerRepository`, `testSticker`, `testStickerPack` |

**Entry points:** Settings → Storage → *Import stickers* → `Routes.STICKERS` → `StickerLibraryScreen` → `StickerLibraryViewModel` → `StickerRepository.importFrom`. **Making one:** *Create* there, or the **+** of the Stickers tab's pack row → `Routes.STICKER_CREATE` → `StickerCreateScreen` → `StickerRepository.prepareStickerDraft` → `StickerMaker.prepare`, then *Save* → `createSticker` → `StickerMaker.render` → `StickerFiles.store`. In a chat: the emoji button → `ComposerPickerPanel` → *Stickers* → `ChatViewModel.sendSticker`. **From the keyboard:** `KeyboardContentReceiver` → `ChatViewModel.sendKeyboardContent` → `ChatMessageSender.sendKeyboardContent` → `MessageRepository.sendGifMessage`, or `StickerRepository.saveSticker` and then `sendStickerMessage`.

**Sending:** `MessageRepository.sendStickerMessage` / `sendGifMessage` → the outbox (`OutboxWorker` → `OutboxSender.send`) → `MessageWriter.write`. **Receiving:** `MessageRepositoryImpl.reconcileRawMessage` → `MediaFileManager.downloadFor` → `StickerDownloads.ensureLocal` for a sticker, `DocumentFiles` for a GIF. The chat-open scan and `MediaBackfillWorker` take the same route.

**Backup:** any pack change in `StickerRepositoryImpl` → `StickerSyncScheduler.syncIfPending` → `StickerSyncWorker` → `StickerUploads.ensureUploaded` per sticker → `StickerPackSource.writePack` → `StickerDao.markSynced`. **Restore:** collecting `StickerRepository.observePacks` → `StickerLibrarySync.whileObserved` → `StickerPackSource.observeOwnPacks` → `StickerDao.applyRemotePack`. A row's file: `LibraryStickerImage` → `LocalStickerFetcher` → `StickerRepository.ensureFile` → `StickerObjectSource.urlIfPresent` → `StickerDownloads.ensureLocal`. **A shared pack:** a tap on a sticker bubble → `StickerActionsSheet` → *View pack* → `StickerPackSheet` → `StickerRepository.viewPack` / `installPack`.

## Adding a feature here

Create an entry only when the feature spans 4+ packages. Otherwise let the package layout speak for itself. New entries follow the same shape: one-paragraph description → table of files with one-line roles → entry point.
