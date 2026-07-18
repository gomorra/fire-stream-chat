# Video Sharing in Chat

## Execution status (updated as steps land — handover anchor)

- Step 1 ✅ `25a7b74` — Media3 **1.9.0** (1.10.1 needs compileSdk 36 → incompatible) + coil-video.
- Step 2 ✅ `3b1344a` — VideoQualityOption + Settings picker. Konsist UI→data allowlist extended with `VideoQualityOption` + TECH_DEBT.md entry updated (documented protocol).
- Step 3 ✅ `feddc46` — VideoTranscoder (readMetadata/transcode/extractThumbnail, pure fns + 12 tests). durationSec rounds; outputDimensions floors to even.
- Step 4 ✅ `e2ff3f8` — repo send/retry video branch, 3min/100MB guard pre-insert, thumbnail upload `_thumb.jpg`, MessageSource `mediaThumbnailUrl` param both flavors, retry re-uploads w/o re-transcode. Deviations (good): retry renames local mp4 to remoteId; optimistic row carries duration+thumb.
- /simplify ✅ ran over steps 3+4 (4 opus reviewers), fixes in `13e70f1`: `ensureWithinLimits()` owns guard+message+single metadata parse (transcode takes metadata param; readMetadata now private); `extractThumbnail` suspend+IO returns File; `localExtFor`/`thumbStorageId` helpers. Skipped: thumb/video upload parallelization, Transformer direct-write, ImageCompressor bitmap-core merge (subtle semantic mismatch: ImageCompressor scales to EXIF-adjusted source dims, not bitmap dims).
- Step 5 ✅ `7690fd6` — VIDEO bubble (per-request VideoFrameDecoder, play overlay, duration badge), reply-preview + starred branches, `onVideoClick` in MessageBubbleCallbacks. SharedMediaScreen omits VIDEO → TECH_DEBT entry added.
- Step 6 ✅ `d44ecc8` — OverlaysState.fullscreenVideo + FullscreenVideoPlayer (ExoPlayer, release-on-dispose, pause-on-ON_PAUSE), ChatScreen wiring incl. onVideoClick.
- Step 7 ✅ `da4603c` — ImageAndVideo picker, READ_MEDIA_VIDEO (RequestMultiplePermissions on 33+), Record-video option via CaptureVideo + createCameraVideoUri, ImagePreviewScreen video mode, SharePicker video test (FakeMessageRepository gained lastSentMimeType).
- Step 8 ✅ `6ac4d63` — CHANGELOG `## [UNRELEASED] [1.13.0] — 2026-07-18`. ALL STEPS DONE. Remaining: manual on-device verification (see Verification section) + eventual `v1.13.0` tag.

## Context

The chat composer can already share pictures (gallery/camera, compressed, upload-progress bubbles) and arbitrary files. This plan adds first-class **video sharing**: pick a video from the gallery or record one with the camera, transcode it before upload (three quality settings in Settings, default 720p), and render received videos as a thumbnail bubble with play button + duration badge that opens a fullscreen player.

Exploration confirmed the codebase is already half video-ready: `MessageType.VIDEO` exists, `MessageEntity`/`Message` already carry `mediaUrl`, `mediaThumbnailUrl`, `localUri`, `mediaWidth/Height`, `duration` (**no Room schema change → no version bump**), `MessageDao` media queries + `AUTO_DOWNLOAD_TYPES` include VIDEO, `MediaFileManager` writes video mimes to `MediaStore.Video`, the manifest has `video/*` share filters + `READ_MEDIA_VIDEO`, `FCMService` already shows "🎥 Video", cloud functions pass the type through untouched, and `FirebaseStorageSource.uploadMedia` is mime-agnostic. The gaps: the send path types video as DOCUMENT, no transcoder/thumbnail extraction, no VIDEO bubble branch, no video player, no quality setting, and `lastContentFor` (chat-list preview) lacks a VIDEO branch in both flavors.

**User decisions:** gallery pick + in-app recording; Media3 Transformer transcode with three quality settings in Settings (default 720p) plus a max-duration/size guard; thumbnail bubble → fullscreen player.

## Key design decisions

1. **Thumbnail**: at send time extract a frame (`MediaMetadataRetriever`), JPEG-compress it, upload it as a second storage object `media/{chatId}/{id}_thumb.jpg`, and populate the existing `mediaThumbnailUrl` field. Receivers see the thumbnail before/without downloading the video. **Contract gap found:** `MessageSource.sendMessage`/`sendPlainMessage` have no `mediaThumbnailUrl` param — `RawMessage` has it and `FirestoreMessageSource` already reads it on receive, but the write map never sets it. Add the param end-to-end (both flavors).
2. **Transcoder**: new `VideoTranscoder` @Singleton in `data/util/` mirroring `ImageCompressor`. `readMetadata(uri)` (width/height/duration/rotation/size) + `suspend transcode(uri, targetHeight)` wrapping Media3 Transformer in `suspendCancellableCoroutine` (`invokeOnCancellation { transformer.cancel(); tempFile.delete() }`; Transformer needs a Looper thread → build/start under `withContext(Dispatchers.Main)`). Always run Transformer with H.264/AAC output; add `Presentation.createForHeight(target)` only when rotation-adjusted height > target — Transformer passes compatible tracks through without re-encoding, so "skip" is free while guaranteeing mp4 output in one code path. Output to `cacheDir/transcoded/<uuid>.mp4`, then `mediaFileManager.copyToLocal(chatId, tempId, ..., "mp4")` and delete the temp in the existing `finally` — identical lifecycle to the image path.
3. **Quality setting**: `enum class VideoQualityOption(val targetHeight: Int) { DATA_SAVER(480), STANDARD(720), HIGH(1080) }` in `PreferencesDataStore` cloning the `AutoDownloadOption` pattern (valueOf with STANDARD fallback); read at send time exactly like the `isHd` pref.
4. **Guard**: max **3 min / 100 MB source**, enforced in `sendMediaMessage` **before** the optimistic placeholder insert (no dead SENDING row). New `MediaLimitException` mapped by `AppError.from` → `AppError.Validation` — surfaces through existing error handling in both `ChatMessageSender` and `SharePickerViewModel` with zero UI changes.
5. **Camera**: `ActivityResultContracts.CaptureVideo` with a FileProvider cache URI (`createCameraVideoUri` beside the existing `createCameraUri`, same authority — `cacheDir/camera` already covered). Mirrors the existing `TakePicture` flow incl. CAMERA permission.
6. **Preview-before-send**: extend `ImagePreviewScreen` with a video mode (frame via coil-video `VideoFrameDecoder` + play badge + duration, zoom disabled) — caption/send flow unchanged; no new screen.
7. **Fullscreen playback**: new `FullscreenVideoPlayer.kt` — ExoPlayer + `PlayerView` in `AndroidView` (`useController = true`), wired as `OverlaysState.fullscreenVideo` beside `fullscreenImage`; show/dismiss are one-line `ChatViewModel` slice updates (no new manager). `DisposableEffect` releases the player, lifecycle observer pauses on `ON_PAUSE`, `BackHandler` dismisses. Local-first source (`localUri ?: mediaUrl`).
8. **Long transcode/upload + user leaves chat**: sends stay in the ViewModel scope; cancellation stops Transformer and cleans temps; the stuck SENDING row is flipped to FAILED by the **existing orphan recovery** (`failStuckSendingMessages`) and tap-to-retry works. No new outbox (consciously deferred in `TECH_DEBT.md`).

## Steps

**Order: 1 → 2+3 → 4 → 5+6 → 7 → 8**

| Step | Model | Effort | Rationale |
|------|-------|--------|-----------|
| 1 Dependencies | Sonnet 4.6 | Low | Mechanical version-catalog additions |
| 2 Quality pref + Settings UI | Sonnet 4.6 | Low | Direct clone of existing enum-pref + picker-dialog pattern |
| 3 VideoTranscoder | Fable 5 | High | Coroutine cancellation + Looper threading + codec/rotation edge cases |
| 4 Repository send/retry + source contract | Fable 5 | High | Cross-flavor contract change + optimistic-send/retry state machine; riskiest step |
| 5 MessageBubble VIDEO branch | Sonnet 4.6 | Medium | Close clone of the IMAGE branch |
| 6 Fullscreen player overlay | Sonnet 4.6 | Medium | Pattern-following overlay; ExoPlayer lifecycle boilerplate must be exact |
| 7 Pickers, preview, share intent | Sonnet 4.6 | Medium | Launcher/state plumbing following existing inline picker code |
| 8 Changelog + version bump | Sonnet 4.6 | Low | Bookkeeping |

### Step 1 — Dependencies
`gradle/libs.versions.toml` + `app/build.gradle.kts`: add `androidx.media3` (latest stable 1.x) — `media3-transformer`, `media3-effect`, `media3-common`, `media3-exoplayer`, `media3-ui` — and `io.coil-kt:coil-video` (existing `coil = "2.7.0"` ref). Verify `assembleDebug`.

### Step 2 — Quality preference + Settings UI
- `data/local/PreferencesDataStore.kt`: `VideoQualityOption` enum, `videoQualityKey`, `videoQualityFlow` (fallback STANDARD), `setVideoQuality` — clone `AutoDownloadOption` (:181-188).
- `ui/settings/SettingsViewModel.kt` + `SettingsScreen.kt`: "Video quality" row + 3-option picker dialog cloned from `AutoDownloadPickerDialog`.
- Tests: enum fallback + SettingsViewModel setter.

### Step 3 — `VideoTranscoder` (new `data/util/VideoTranscoder.kt`)
As per design decision 2, plus `extractThumbnail(uri): ThumbResult(file, width, height)` (frame near t=0–1s, JPEG ≤1280px q80 into `cacheDir/transcoded/`). Constants `MAX_VIDEO_DURATION_MS = 180_000`, `MAX_VIDEO_SOURCE_BYTES = 100 MB` live here. Keep pure logic (rotation-adjusted target-height decision, needs-Presentation predicate, output-dimension math) in package-visible pure functions with JVM unit tests (Transformer itself is device-only — don't Robolectric it).

### Step 4 — Repository send/retry path + source contract
- `MessageRepositoryImpl.sendMediaMessage` (:509): `isVideo = mimeType.startsWith("video/")` → `MessageType.VIDEO`; guard before placeholder insert; read quality pref like `isHd` (:519); video branch parallel to image branch (:550-567): transcode → `copyToLocal(..., "mp4")` → extract+upload thumbnail (`"${tempId}_thumb"`, `image/jpeg`) → upload video (existing progress flow :578) → `sendEncryptedOrPlain(..., duration = seconds, mediaThumbnailUrl = thumbUrl)` (signature at :336 already has `duration`; add `mediaThumbnailUrl`) → sent entity carries thumb URL + duration. Fix the hardcoded `"jpg"` rename (:612-613) to per-type extension.
- New `MediaLimitException` + `AppError.from` branch.
- `data/remote/source/MessageSource.kt`: add `mediaThumbnailUrl: String? = null` to `sendMessage`/`sendPlainMessage`; `FirestoreMessageSource` writes it into both data maps; `PocketBaseMessageSource` accepts it (may no-op). Both `lastContentFor` implementations: `MessageType.VIDEO -> "🎥 Video"` (with caption prefix like the IMAGE branch).
- Retry: `retryFailedMessage` `when` (:648) adds VIDEO → `retrySendMedia`; in `retrySendMedia`, fix `uploadMimeType` fallback (currently `"application/octet-stream"` for non-image, ~:736) to `"video/mp4"` for VIDEO, and re-send `duration`/`mediaThumbnailUrl` from the persisted entity.
- Tests (mock `VideoTranscoder`): extend `MessageRepositoryMediaSendFailureTest` (`video/*` → VIDEO placeholder; guard failure → Validation error and **no** placeholder row), `MessageRepositoryRetryTest` (VIDEO dispatch), `MediaFileManagerTest` (mp4 paths).

### Step 5 — MessageBubble VIDEO branch
`ui/chat/MessageBubble.kt`: new `MessageType.VIDEO` branch cloning IMAGE sizing (aspect from `mediaWidth/Height` fallback 4:3, `widthIn(max=280).heightIn(100..400)`), upload-progress + FAILED overlays. New `rememberMessageVideoThumbModel(message)` beside `rememberMessageImageModel` (:1052): local file → `ImageRequest` with `VideoFrameDecoder.Factory()` (per-request, no global ImageLoader change); else `mediaThumbnailUrl`. Centered play button + duration badge (reuse mm:ss formatter from `ChatUtils`/`VoiceMessagePlayer`). Tap → new `onVideoClick(localUri ?: mediaUrl)` in `MessageBubbleCallbacks`. Check reply-preview rendering for VIDEO while there. Tests: VIDEO variant in `MessageBubbleScreenshotTest`/`SmokeTest`.

### Step 6 — Fullscreen video player overlay
- `ui/chat/ChatOverlaysState.kt`: `fullscreenVideo: FullscreenVideo?` beside `fullscreenImage`.
- `ChatViewModel.kt`: show/dismiss mirroring the fullscreen-image pair (:375-379), single-slice `.update {}`.
- New `ui/chat/FullscreenVideoPlayer.kt` per design decision 7.
- `ChatScreen.kt`: wire `onVideoClick`, `AnimatedVisibility` + `BackHandler` mirroring the fullscreen-image wiring (:1757-1770).

### Step 7 — Pickers, preview, share intent
- `ChatScreen.kt`: `galleryLauncher` → `PickVisualMedia.ImageAndVideo`; rename `pendingImageUri`/mime state to `pendingMediaUri` (keep `rememberSaveable`) and branch on mime; gallery permission block (:1618-1624) adds `READ_MEDIA_VIDEO`; new "Record video" `AttachmentOption` in the sheet (:1606-1688) → CAMERA permission → `CaptureVideo` launcher with new `createCameraVideoUri` helper (beside :1860) → route into preview with `video/mp4`.
- `ui/chat/ImagePreviewScreen.kt`: video mode per design decision 6.
- `ui/share/SharePickerViewModel.kt`: `video/*` shares already route through `sendMediaMessage` — no code change expected; extend `SharePickerViewModelTest` with a video-mime case.

### Step 8 — Changelog + version bump
`CHANGELOG.md` feat entry ("Video sharing…") under a minor-bumped section per project versioning rules. No Room version bump (no schema change). MEMORY.md note re Transformer main-Looper requirement.

## Execution mode (per user request)

The parent session orchestrates; **each step runs in its own subagent** via the Agent tool with an explicitly pinned `model`. Each subagent gets the plan file path + its step number and runs the step's tests itself; the parent verifies the gate (test → build → commit) between steps and enforces the Order line.

| Step | Agent `model` | Why |
|------|---------------|-----|
| 1 Dependencies | sonnet | Mechanical catalog edits |
| 2 Quality pref + Settings UI | sonnet | Clone of existing pattern |
| 3 VideoTranscoder | opus | Concurrency/cancellation-heavy; Opus 4.8 sanctioned by project table for this tier |
| 4 Repo send/retry + contract | opus | Riskiest step (cross-flavor contract + state machine); same tier |
| 5 Bubble VIDEO branch | sonnet | Clone of IMAGE branch |
| 6 Fullscreen player | sonnet | Pattern-following overlay |
| 7 Pickers/preview/share | sonnet | Launcher/state plumbing |
| 8 Changelog + bump | sonnet | Bookkeeping |

If a Sonnet step fails its gate twice, escalate that step to opus rather than iterating further on sonnet.

## Per-step gate (project convention)
After each step: `./gradlew test` → `./gradlew assembleDebug` → commit. Both flavors must compile after Step 4 (`assembleFirebaseDebug assemblePocketbaseDebug` — the MessageSource contract change touches both). `/simplify` triggers apply to Steps 3+4 (concurrency-heavy; pin Phase 2 agents to opus).

## Verification

**Unit/CI:** `./gradlew test` (extended repository/retry/MediaFileManager/SharePicker/bubble tests + transcoder pure-function tests); `./gradlew assembleFirebaseDebug assemblePocketbaseDebug`.

**Manual on-device (two devices/accounts):**
1. Send gallery video at default 720p — instant bubble with frame + progress ring → SENT; Storage has `.mp4` + `_thumb.jpg`; chat list shows "🎥 Video".
2. Receive on second device — remote thumbnail + duration badge appear before download; Wi-Fi auto-download sets `localUri`; tap → fullscreen plays; back dismisses and releases player (no audio afterwards).
3. Quality setting: 480p/1080p resends produce matching output heights; a 480p source at the 720p setting is not upscaled.
4. Guard: >3 min video → friendly validation message, no stray bubble.
5. Record via attachment sheet → preview with caption → send.
6. Share a video from the Gallery app into a chat → arrives typed VIDEO.
7. Kill the app mid-upload → relaunch shows FAILED + retry; retry succeeds.
8. Portrait/rotated source: correct aspect in bubble and playback orientation.
9. Caption under video bubble; reply-to-video preview is sane.
