# Changelog

All notable changes to FireStream Chat. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); each section is headed by the SemVer `versionName` shipped on that merge day (e.g. `## [1.2.3] — 2026-04-24`). Bump rule: `feat:` → minor, `fix:` → patch, `feat!:` / `BREAKING CHANGE:` → major. `versionCode` is derived from `git rev-list --count HEAD`.

## [Unreleased]

## [1.3.0] — 2026-04-26

### Added
- **Settings → Chat → Dictation Language.** A new "Chat" section in Settings exposes a picker for the dictation language with two options: German (`de-DE`, default) and English (`en-US`). The choice persists in DataStore (`dictation_language` key) and `ChatViewModel.startDictation` reads it on each mic tap, replacing the previous diagnostic hardcode and the earlier `Locale.getDefault()` fallback that silently returned the wrong recognizer locale on some devices.

### Fixed
- **Voice dictation: mic now actually records.** The recording bar opened for a fraction of a second and closed again with no transcript. Cause: `RecognizerIntent.EXTRA_PREFER_OFFLINE` was set unconditionally on SDK ≥ 31, which forces the recognizer to fail with `ERROR_NETWORK` whenever the offline language pack for the user's locale isn't downloaded. Dropped the flag — the system now picks online or offline by itself. Also surfaced `dictation.error` as a snackbar (previously silently swallowed) and added the `RECORD_AUDIO` calling-package extra plus a logcat line tagged `SpeechRecognizer` for the error code.

## [1.2.0] — 2026-04-26

### Added
- **Release-mode opt-out for end-to-end encryption.** Settings → Privacy now has an "End-to-End Encryption" toggle (release builds only). Default on; disabling sends new outgoing 1:1 messages as plaintext via `sendPlainMessage` instead of the Signal-encrypted path. Already-sent messages and the receive path are unaffected — peers can mix encrypted and plaintext freely. Disabling routes through a confirmation dialog. Group and broadcast were never encrypted, so the toggle has no effect on those.
- **Image thumbnails in reply previews.** Replying to a photo now shows a small thumbnail next to the snippet — both in the composer's "Replying to" banner and in the quoted block at the top of the sent reply bubble. Falls back to "Photo" when the original has no caption. Pure UI change: the existing reply-by-id lookup already had the source `Message` (with `localUri`/`mediaUrl`/dimensions) in scope at both render sites, so no Firestore/Room schema change was needed.
- **Voice dictation in the message composer.** A mic button morphs from the send icon while the field is empty; tapping it requests `RECORD_AUDIO`, then streams the system speech recognizer (`android.speech.SpeechRecognizer`) live into the editable input — no model bundling, on-device on modern phones. While listening, a sliding control bar above the composer shows an animated sine waveform driven by mic RMS plus a cancel ✕. Recording ends only on a second mic tap (silence does not auto-finalize — the manager restarts the recognizer between segments and joins them with spaces); typing into the field cancels dictation without overwriting what's already there. Refuses to start during a voice call.

## [1.1.3] — 2026-04-26

### Refactored
- **Signal Protocol tables moved to a dedicated `signal.db`.** Splits the seven `signal_*` tables out of `AppDatabase` into a new `SignalDatabase` so destructive schema migrations on the main app no longer wipe identity / pre-keys / sessions. `AuthRepository.signOut()` now clears both databases. Sets up a follow-up to enable encryption-in-debug. (`f7783d1`)

## [1.1.2] — 2026-04-24

### Added
- **Build info in Settings → App Version.** Subtitle now shows the real `versionName` with a `(debug build)` suffix on debug. Tap the row to open a Material3 dialog with version / build / commit SHA / committed date / type; long-press to copy the same block to the clipboard. `versionCode` is now derived from `git rev-list --count HEAD` at configure time, and `BuildConfig` carries the HEAD SHA + committer date. (`dbc17ff`)
- **Save image from chat bubble.** Long-pressing an image message now surfaces a `Save image` action in the dropdown, routing through the existing `saveImageToDownloads` → `MediaFileManager.saveToDownloads` pipeline. (`8bb2a2e`)
- **Profile → Shared Media fullscreen viewer.** Tapping a thumbnail in the ProfileScreen shared-media grid now opens the existing `FullscreenImageViewer` (pinch-zoom, double-tap, tap-to-dismiss). Prefers `localUri` over the remote thumbnail, matching the in-chat image bubble. (`37300aa`)
- **Recent emojis + usage tracking in `ImagePreviewScreen`.** Emoji picker surfaces the user's most-recent selections alongside the standard set. (`5e76b7c`)
- **Baseline profile.** Shipped the first baseline profile plus generator module, testTags on key screens, and unblocked release-build encryption. (`c11aee5`, `d42bc7f`, `8bc1db4`, `8872785`)
- **Directional iOS-style slide transitions** between NavHost destinations, with per-route duration escalation for Chat and List Detail, tuned for snappier feel. (`df74ea3`, `f974e5b`)

### Fixed
- **Build info dialog: long timestamps no longer collide with the label.** The `Committed` ISO-8601 value was wrapping into the space reserved for its own label in the `SpaceBetween` row layout. Switched to a vertical stack (small muted label above, monospace value below) so long values get the full row width. (`3b9338f`)
- **IME inset plumbing in chat.** Composer and last bubble now lift cleanly above the keyboard via `Scaffold` padding; replaced the snapshot/scrollBy hack with `reverseLayout=true` + `messages.asReversed()`. (`a660c07`, `a972533`, `e892f58`)
- **`MessageBubble` Compose register-allocator risk.** Collapsed `isHighlighted`/`uploadProgress` into a state holder and added a lint guard against `@Composable` param-count regressions. (`9777076`, `9929769`)
- **Presence log level.** Downgraded a routine reconnect warning from `w` to the documented state machine log. (`c35722c`)

### Changed
- **Code-review workflow.** `/simplify` now runs three parallel Sonnet-medium reviewers by default, with an Opus-medium triggered path for concurrency- or security-heavy diffs. (`378c5bb`, `6a72ea2`)

### Refactored
- **Clipboard: use Android `ClipboardManager` directly.** Settings → App Version long-press migrated off the deprecated `LocalClipboardManager`, matching the pattern already used in `MessageBubble.kt:714`. (`dd0947e`)

## [1.0.0] — 2026-04-23

### Added
- **`AppError` sealed type.** Every UiState's `error` field is now `AppError?` instead of `String?`; `AppError.from(Throwable)` standardizes VM-boundary wrapping. (`5242ece`, `182fd15`, `0dfc285`)
- **Chat scroll + last-open list restoration across process death.** (`b2d6ff3`)
- **Copy-text action** on the message-bubble context menu. (`8045553`)
- **Image-bubble long-press context menu.** Tapping the three-dot overflow or long-pressing an image opens the same actions as text bubbles. (`cf61a8a`)
- **List-update chat bubble** — single aggregated "list updated" message with a 10-second debounce that resets on each new edit. (`db6897a`)

### Changed
- **ChatUiState split into four cohesive slices** — `MessagesState`, `ComposerState`, `OverlaysState`, `SessionState`. Each `Chat*Manager` owns one slice. (`b43c68b`, `cb44e84`)
- **List items moved to a Firestore subcollection** (`lists/{id}/items/{itemId}`) with denormalized counts, ending the sync races from the embedded-array layout. (`b798f6e`, `3bdd93a`)

### Fixed
- **Room writes per list are now serialized** so live updates stay visible during rapid edits. (`eed7519`)
- **New list items use `max(order) + 1`** to avoid collisions after deletes. (`e3c2c9c`)
- **Bubble colors honor the manual theme override.** (`97c8e79`)
- **Chat notification → chat screen** now scrolls to the latest message on open. (`104da74`)
- **Incoming bubble body** uses `onSurface` so light-theme text stays legible. (`3c4f424`)
- **Gradle daemon SIGSEGV** on CachyOS JDK 17 — disabled CDS via `-Xshare:off`. (`c9d553d`)

### Refactored
- **Lists feature** uses `AuthRepository` instead of leaking `FirebaseAuthSource` into ViewModels. (`e6985b0`)
- **Repository fakes expanded** (`FakeMessageRepository`, `FakeChatRepository`); flagship VM tests migrated off MockK. (`2e91150`)

## [1.0.0] — 2026-04-19

### Added
- **Dark palette refresh** — calmer neutrals with orange as the true accent. (`28b7c02`)

### Fixed
- **Presence listener leak** that caused false-online status after reconnect. (`e40c3d8`)
- **Chat list ordering** preserved across the bottom-nav tab persistence refactor. (`700e332`)
- **List-update chat bubble** now delivers even if the user leaves the detail screen mid-debounce. (`ddd387c`)

## [1.0.0] — 2026-04-16

### Added
- **Jump-to-source** when tapping a reply preview — scrolls the quoted message into view and highlights it. (`d079d7c`)

### Fixed
- **Receiver flashing online after `goOffline`.** (`88e31d0`)
- **Bottom-nav tab persistence** restores the last open tab on relaunch. (`5065338`)

## [1.0.0] — 2026-04-12

### Added
- **Test infrastructure scaffold** — fakes, test data, dispatcher rule. (`24dde3f`, `317d9f5`)
- **Profile avatar fullscreen from chat list** — tap the avatar on a chat row to open the fullscreen viewer without entering the chat. (`2261ef0`)

### Fixed
- **Link-preview fullscreen** and **chat-list avatar tap** (including group avatars) now route through the correct handlers. (`7fdf0f9`)
- **`MessageBubble` VerifyError** — collapsed callbacks to dodge the Compose register-allocator crash that blocked chat-open on release builds. (`00b15da`)
- **x86_64 ABI** added to debug builds for native emulator support. (`4d56698`)

## [1.0.0] — 2026-04-11

### Refactored
- **Repository layer cleanup** — `resultOf` helper, `Uri → String` at the boundary, `FirestoreChatSource` tidy. (`3cc2229`)
- **`rememberImagePicker` composable** extracted for gallery/camera flows. (`f6132f0`)
- **Compose-specific mention formatter** moved out of the domain layer. (`6454836`)

## [1.0.0] — 2026-04-06

### Added
- **Location sharing.** New `LOCATION` message type with GPS capture via `FusedLocationProviderClient`, OpenStreetMap static-tile previews, and `geo:` URI intent on tap. `LocationPickerSheet` composable for one-tap capture.

## [1.0.0] — 2026-04-04

### Added
- **UI/UX polish pass** across the app:
  - Plus Jakarta Sans typography.
  - NavHost slide+fade transitions.
  - Pull-to-refresh on ChatList, Contacts, and Calls.
  - Mute/pin inline indicators on chat rows.
  - Unread badge bounce animation.
  - Typing bouncing dots (`TypingIndicator`).
  - Message bubble tails + grouping (`ALONE/FIRST/MIDDLE/LAST`).
  - Message enter animations and receipt transitions.
  - Skeleton shimmer loading for three screens.

## [1.0.0] — 2026-03-29

### Added
- **Image handling overhaul.** Local-first media storage under `Android/media/com.firestream.chat/{chatId}/`, EXIF-aware compression with a "full quality" preference, dynamic bubble sizing from `mediaWidth/mediaHeight`, determinate upload-progress overlay, and gallery export via MediaStore.

### Fixed
- Extension normalization (`jpeg→jpg`, `tiff→tif`, `mpeg→mpg`).
- In-flight download deduplication via `ConcurrentHashMap<String, CompletableDeferred<File>>`.
- Periodic media backfill (`MediaBackfillWorker`, 15-minute cadence).
- Per-chat download scan on chat open.
- Sent-image file rename from tempId to remoteId to prevent orphans.

## [1.0.0] — 2026-03-21

### Added
- **Bottom navigation + Calls tab.** `MainScreen` hosts a `HorizontalPager` with Chats, Calls, and Lists; `BottomNavBar` lives exclusively in `MainScreen`.
- **Robust online presence** via Firebase RTDB with the `.info/connected` reconnect pattern.

## [1.0.0] — 2026-03-13

### Added
- **Phase 4.1 — 1-to-1 voice calls** over WebRTC: domain layer, `CallService` foreground service, `CallStateHolder`, incoming-call FCM, `CallActivity`, and the call push Cloud Function.
- **Clickable links in message content** with a fullscreen image viewer for link previews and shared media.
- **Content sharing** — share picker UI; message search uses word-boundary matching.

## [1.0.0] — 2026-03-10

### Added
- **Message soft deletion.**
- **User and group avatar upload.**
- **Chat date separators.**
- **Contact synchronization** wired into `ChatListViewModel`.

### Fixed
- Sporadic message decryption failures caused by `collectLatest` cancellation. (`8f0b297`)

## [1.0.0] — 2026-03-09

### Added
- **Phase 5.5 — Broadcast lists.** (`cd7ec32`)
- **Phase 5.3 — Polls.** (`eda95ae`)
- **Phase 5.1 — Enhanced group management** (description, invite links, QR codes). (`5f0819b`)
- **Group creation + mention parser** with mention-only notification setting. (`1f0c009`)

## [1.0.0] — 2026-03-08

### Added
- **Phase 2 — User experience & chat management.** Archived chats screen, in-chat search, richer message-status indicators.
- Initial architecture documentation.

### Fixed
- **Chat screen back-navigation hang** under rapid-fire Room emissions. (`cb628d5`)

## [1.0.0] — 2026-03-07

### Added
- **Phase 1 — Core messaging completeness.**
- **Push notifications, fullscreen image viewer, media send.**
- **Firebase Phone Auth + Signal Protocol** wired up.

## [1.0.0] — 2026-03-06

### Added
- **Initial Android chat client** — project scaffold and first feature pass.
