# Changelog

All notable changes to FireStream Chat. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); each section is headed by the SemVer `versionName` shipped on that merge day (e.g. `## [1.2.3] — 2026-04-24`). Bump rule: `feat:` → minor, `fix:` → patch, `feat!:` / `BREAKING CHANGE:` → major. `versionCode` is derived from `git rev-list --count HEAD`.

## [1.9.0] — 2026-05-13

### Added
- **React to shared-list bubbles.** List-update bubbles (shares, item add/remove, checks, renames, deletions) gained the same reaction affordance as text messages: long-press the bubble — or tap an existing reaction chip — to open the full emoji picker, and grouped reactions render beneath the bubble with your own choice highlighted in the primary color. Previously `ListBubble` was display-only with no long-press menu, so list-share moments couldn't be acknowledged with a 👍 or ❤️ inline. The picker, repository plumbing, and Firestore sync are the existing reaction path — only the bubble is new.

### Changed
- **Double-tap zoom centers on the tap position in the fullscreen image viewer.** Previously every double-tap zoomed around the image center, forcing a pan afterwards to inspect anything off-axis. The handler now translates so the content point under the finger stays anchored across the 1× → 3× → 6× → 1× cycle, matching the gesture model users expect from gallery apps.

## [1.8.0] — 2026-05-10

### Added
- **Retry button for failed sends.** Failed messages now expose a retry control instead of stranding you with no recovery path. Image, video, and document bubbles render a centered Refresh-icon overlay with a "Failed to send" caption; for every other message type (text, voice, location, timer) the small red error icon at the bubble tail becomes tappable. Tap drives the existing send pipeline in place — the same Room row flips back to `SENDING`, replays upload/encrypt/Firestore steps, and either lands as `SENT` or reverts to `FAILED` for another try. Image retries skip recompression when dimensions are already stored, avoiding a second round of quality loss after a network-only failure. As part of this work, `sendMessage` (text), `sendVoiceMessage`, `sendLocationMessage`, and `sendTimerMessage` gained the same FAILED-on-throw guarantee that `sendMediaMessage` already had — previously their optimistic rows would stay stuck on `SENDING` indefinitely after a transport error, so retry would never have a state to key off.

### Fixed
- **Second image no longer silently dropped when sending two pictures rapidly.** Sending two photos back-to-back sometimes left only the first bubble visible — the second send call vanished without a snackbar or FAILED indicator. `MessageRepositoryImpl.sendMediaMessage` was running `ImageCompressor.processImage` and `MediaFileManager.copyToLocal` *before* the optimistic `messageDao.insertMessage`, so any throw from those steps (typically `BitmapFactory.decodeStream` returning null when two large bitmaps decode concurrently on `Dispatchers.IO`) bubbled up through `resultOf { }` without ever writing a Room row. The optimistic insert now runs first with the original `content://` URI as `localUri` (Coil renders it directly), and a `replaceMessage` swaps in the compressed local file once it's ready. Compression / upload / network failures now leave the bubble visible with `MessageStatus.FAILED` instead of disappearing — matching the existing text-message contract.

## [1.7.1] — 2026-05-04

### Fixed
- **`.timer.set` wheel picker now actually registers selections.** Dialing 1 hour (or any non-zero duration) in the hh:mm:ss wheel left the Send button greyed out: the `snapshotFlow` chain ordered `filter { !it }` *before* `distinctUntilChanged`, so once the filter stripped every `true`, the dedup operator saw only `false` values and emitted exactly once at launch — subsequent scroll-then-snap cycles never reached `onSelected`. Reordered the operators (`distinctUntilChanged().drop(1).filter { !it }`) and swapped the brittle `firstVisibleItemIndex` lookup for a `layoutInfo`-based "closest item to viewport center" calculation that's robust against the contentPadding offset.

## [1.7.0] — 2026-05-04

### Added
- **`.command` grammar + `.timer` (synchronized timers).** Type `.` at message start in any chat composer to open a vertical palette of available commands. `.timer.set` mounts an hh:mm:ss wheel-picker widget; sending it persists a TIMER message that schedules an exact `AlarmManager` alarm on **both** devices against a server-stamped fire time, so sender and recipient ring together. The bubble shows a live countdown that flips to "Timer ended" when the alarm fires (alarm-style notification, system default alarm sound, full-screen intent) or to a struck-through "Cancelled" when either side long-presses → Cancel timer. Cancellation propagates through the message observer, so cancelling on either device unschedules the other's pending alarm. Permissions and notification channel are gated by an in-app banner that deep-links to system "Alarms & reminders" when SCHEDULE_EXACT_ALARM is denied on Android 12+. Survives process death and device reboot via `BootCompletedReceiver`. (`c0589d2`, `0d76af6`, `6dd6ea2`, `645c583`, `66540e5`)

## [1.6.6] — 2026-05-03

### Added
- **"Image saved" snackbar with Open action in fullscreen viewer.** Tapping the download button while viewing a fullscreen image now shows a "Image saved to Downloads" snackbar at the bottom of the viewer with an "Open" button that launches the file directly. Previously the confirmation snackbar was hidden behind the viewer.

### Changed
- **Fullscreen image viewer zooms up to 10×.** Pinch-to-zoom ceiling raised from 5× to 10×. Double-tap now cycles through three steps — 1× → 3× → 6× → 1× — so you can reach deep zoom without pinching.

## [1.6.5] — 2026-05-03

### Added
- **"What's new" in Build info dialog.** Settings → Help → "App Version" now shows a scrollable "What's new in X.Y.Z" section below the existing build fields. Entries are parsed from CHANGELOG.md (bundled as an APK asset via a Gradle copy task) using a pure-Kotlin line-based parser — no markdown library. Bold entry names render in SemiBold, commit-hash trailers are stripped, and dev builds fall back to the `[Unreleased]` section when available.

## [1.6.4] — 2026-05-02

### Fixed
- **Cold-start image spinner.** Tapping an image after closing and reopening the app showed the loading spinner again even when the file was already on local storage. `MessageBubble` and `FullscreenImageViewer` resolved the local-vs-remote choice asynchronously via `produceState(initialValue = false)`, so on every fresh composition `AsyncImage` was handed the remote URL first and only swapped to the local file once the IO check completed — long enough that Coil started a network request whose visible spinner never went away (memory cache was empty post-restart, and Firebase Storage `?token=` rotation makes disk-cache hits unreliable). Replaced with a synchronous `remember(localUri)` `File.exists() && canRead()` check (microseconds on a warm filesystem), so the model is decided before `AsyncImage` ever sees it. Also scheduled `MediaBackfillWorker` as a daily periodic job from `FireStreamApp.onCreate()` (24h, NetworkType.CONNECTED, 1h initial delay) — it was previously only reachable via the manual Settings tap, so the existing "clear stale localUri / re-download missing" pass never ran in the background

## [1.6.3] — 2026-05-01

### Fixed
- **Update row auto-refreshes after granting "Install unknown apps" permission.** Previously, returning from the system permission screen left the Settings update row stuck on "Allow installs from FireStream to continue" — the user had to leave and re-enter Settings for the row to update. A lifecycle `ON_RESUME` observer now calls `recheckInstallPermission()` which detects the granted permission and transitions the row to "Update ready — tap to install" immediately. (`02d35d1`) 

## [1.6.2] — 2026-05-01

### Fixed
- **Debug builds now use the release keystore so they can self-update over release APKs.** Without a local `releaseStoreFile` in `local.properties` (or `RELEASE_STORE_FILE` in the env), debug builds previously used the auto-generated debug keystore, while release builds used the release keystore from CI. This mismatch caused the system installer to reject in-place upgrades when the APK signature changed, resulting in "App not installed" errors during self-updates. The fix makes both build types use the same release keystore when available, ensuring consistent signatures and seamless upgrades across debug and release installs. When the release keystore is not configured, both builds fall back to the debug keystore as before. (`65fa6f3`)

## [1.6.1] — 2026-05-01

### Fixed
- **Updater no longer crashes the app and no longer hijacks Settings while downloading.** Tapping Settings → Help → Check for updates → Update now reliably crashed the process with `IllegalArgumentException: foregroundServiceType 0x00000001 is not a subset of 0x00000000` — WorkManager's `SystemForegroundService` ships without a `foregroundServiceType` attribute, so on Android 14+ our worker's `setForeground(FOREGROUND_SERVICE_TYPE_DATA_SYNC)` was rejected on the binder thread and killed the host process. A `tools:node="merge"` override in our manifest sets `foregroundServiceType="dataSync"` on that service, and the worker is now wrapped in defense-in-depth: `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` lets WorkManager handle FGS lifecycle, `doWork()` is a try/catch around the whole body so any future surprise surfaces as a real `KEY_FAILURE_MESSAGE` instead of the misleading "Update failed: net error" fallback, and the manual `setForeground` calls degrade gracefully when the OS rejects them. UX is non-blocking: the modal "Downloading update" dialog is gone — the Settings row now shows live progress (`Downloading 12 MB / 28 MB · 43%` plus a thin `LinearProgressIndicator`) with a trailing ✕ icon that opens a confirm dialog, and the user can navigate around the app freely while the worker runs. When the download finishes the row offers tap-to-install and the worker also posts a separate "Tap to install" notification (via `PendingIntent.getActivity`, which qualifies for the BAL exemption — install works even when the app is backgrounded). New states cover the corner cases: `ReadyToInstall(file)` after a successful download, `NeedsInstallPermission(file)` if the user revoked "Install unknown apps". The `@DownloadClient` `OkHttpClient` adds `pingInterval(30s)` to surface dead sockets in seconds (was: hung behind the 5-min readTimeout) and a 30-min `callTimeout` as a hard backstop on stuck connections. `IOException` mapping converts bare OkHttp messages into user-facing strings (`No internet connection`, `Connection timed out — try again on Wi-Fi`, `Couldn't verify update server`, `Connection dropped — please retry`, `Server returned malformed response`). Bounded retry on the rare checksum mismatch path: the worker now returns `Result.retry()` once before surfacing `Update file is corrupt — please report to the team`. `translate()` stats the APK file on `WorkInfo.SUCCEEDED`, so a cache-evicted file no longer leaves the row claiming "Update ready" pointing at nothing. `WorkManager.pruneWork()` after install/cancel prevents stale `Done`/`Cancelled` states from rehydrating on the next Settings re-entry. (`0e27f80`, `b1d95e5`, `de36549`, `b98ef54`, `8588b3a`)

## [1.6.0] — 2026-05-01

### Added
- **HD label on full-quality image bubbles.** Images sent with Settings → Send images in full quality enabled now carry an HD pill in the bubble's footer row (left of the timestamp) on both sides of the conversation. The flag is stored on the message record so it round-trips through Firestore and survives reinstalls.

### Fixed
- **Re-archived chats no longer disappear.** Archive → restore → archive again silently lost the chat from the archive list. Cause: `ChatRepositoryImpl.getChats` did the Firestore-snapshot merge as separate `getChatsByIds` → map → `insertChats` calls, so a concurrent `setArchived(true)` flipping in between the read and the write was overwritten by the merged entity rebuilt from the stale snapshot. Wrapped the read+merge+write in a new `ChatDao.upsertRemote` `@Transaction` so single-statement local writes (`setArchived` / `setPinned` / `setMuteUntil`) are serialised against it by SQLite's writer lock and the user's flip always wins.
- **APK self-updater survives a locked screen and resumes on retry.** The 96 MB release-APK download used to run inline in `viewModelScope` over OkHttp blocking I/O, so locking the phone mid-download stalled the read under Doze and surfaced "Network error" — forcing a restart from byte 0. Downloads now run in a foreground `ApkDownloadWorker` (`FOREGROUND_SERVICE_DATA_SYNC`) on a long-stream `OkHttpClient` (5 min read timeout, no call timeout), with a low-importance "Downloading update" progress notification reusing the existing `fire_stream_app_updates` channel. The downloader is also resumable: on retry it sends `Range: bytes=N-`, seeds the SHA-256 digest from the existing prefix, and handles 200/206/416/IOException paths without losing progress. The progress dialog gains a Cancel button, and re-entering Settings during a download rehydrates the dialog at the current byte count instead of restarting from 0. (`481938c`)

### Changed
- **`versionName` is auto-derived from `git describe --tags`.** The tag is now the single source of truth — exact-tag builds report `X.Y.Z`, untagged HEAD reports `X.Y.Z-dev+<sha>` so debug builds can't masquerade as a release. Release-cut shrinks to: rename CHANGELOG `[Unreleased]`, commit, tag, push. (`93751db`, `7e4db78`)

## [1.5.1] — 2026-04-30

### Fixed
- **Updater no longer surfaces "Network unavailable" before the first release.** The GitHub `/releases/latest/download/` alias returns HTTP 404 until at least one tag has been published; the manifest fetcher previously treated this as a network failure. Now it returns `UpdateCheckResult.UpToDate`, so Settings → Check for updates shows "You're on the latest version" cleanly during the bootstrap window.

## [1.5.0] — 2026-04-29

### Added
- **In-app updater + automated APK release pipeline.** Releases are now produced by a tag-driven GitHub Actions workflow (`.github/workflows/release-apk.yml`): pushing a `v*` tag builds signed `firebase` and `pocketbase` release APKs, computes SHA-256, renders flavor-specific manifests (`latest-firebase.json`, `latest-pocketbase.json`), and publishes everything as GitHub Release assets at the predictable `.../releases/latest/download/<file>` alias URL. The app fetches that manifest, compares `versionCode`, downloads with checksum verification into the cache dir, and hands the APK to the system installer via FileProvider + `ACTION_VIEW`. A 24-hour `UpdateCheckWorker` (`UNMETERED` constraint) posts a low-priority "App updates" notification when a newer version is found; tapping deep-links to Settings → Check for updates, where the user sees release notes and a download progress bar. Settings → Help also gains a manual "Check for updates" row above "App Version". Release builds now read keystore credentials from CI secrets / `local.properties` and fall back to the debug keystore when absent — see `docs/RELEASING.md` for the one-time setup. Adds `REQUEST_INSTALL_PACKAGES` to the manifest.

## [1.4.0] — 2026-04-28

### Added
- **PocketBase backend variant (walking skeleton).** A new `pocketbase` Gradle product flavor swaps Firestore + RTDB for a self-hostable PocketBase server. v0 covers login (Firebase Phone OTP exchanged for a PB session via the `firebase_bridge.pb.js` hook), 1:1 text messaging, presence (heartbeat + cron sweeper), and FCM push notifications. SSE realtime auto-pauses on backgrounding via a `ProcessLifecycleOwner`-bound hook so idle phones don't pin a connection. Calls, polls, lists, group permissions, and Signal encryption are out of scope for v0 and surface `NotImplementedError` at the source boundary — tracked in `TECH_DEBT.md`. The default `firebase` flavor is unchanged. (`b95bd07`, `1e5f3bf`, `0d1b019`)

## [1.3.0] — 2026-04-26

### Added
- **Settings → Chat → Dictation Language.** A new "Chat" section in Settings exposes a picker for the dictation language with two options: German (`de-DE`, default) and English (`en-US`). The choice persists in DataStore (`dictation_language` key) and `ChatViewModel.startDictation` reads it on each mic tap, replacing the previous diagnostic hardcode and the earlier `Locale.getDefault()` fallback that silently returned the wrong recognizer locale on some devices.

### Changed
- **End-to-end encryption now defaults to off in release.** The Settings → Privacy toggle introduced in 1.2.0 previously defaulted on; new installs (and existing users who never touched the toggle) now start with the plaintext path until they explicitly enable Signal in Settings. Receive path is unchanged — incoming encrypted messages still decrypt. Debug builds were already plaintext-only and are unaffected.

### Fixed
- **Older images now open fullscreen.** Tapping an image bubble older than the most recent ~2 days previously opened to a black frame; the file existed at the local path but the current install couldn't open it (`EACCES` on direct `File` access to `Pictures/FireStream Images/` entries written via MediaStore by a previous install). The chat bubble already guarded with `canRead()` and silently fell back to the remote URL — `FullscreenImageViewer` only checked `exists()`, so it committed to the unreadable path. Aligned the viewer's check with the bubble's, and added `placeholder` / `error` slots (`SubcomposeAsyncImage` with a spinner during load and a labeled `BrokenImage` for "No image data" vs. "Failed to load") so any future load failure surfaces visibly instead of as a silent black frame.
- **Voice dictation: mic now actually records.** The recording bar opened for a fraction of a second and closed again with no transcript. Cause: `RecognizerIntent.EXTRA_PREFER_OFFLINE` was set unconditionally on SDK ≥ 31, which forces the recognizer to fail with `ERROR_NETWORK` whenever the offline language pack for the user's locale isn't downloaded. Dropped the flag — the system now picks online or offline by itself. Also surfaced `dictation.error` as a snackbar (previously silently swallowed) and added the `RECORD_AUDIO` calling-package extra plus a logcat line tagged `SpeechRecognizer` for the error code.
- **Quoted reply preview no longer renders in italic.** The snippet shown inside a chat bubble when replying to a message was set to `FontStyle.Italic`, leaving it visually inconsistent with the upright "Replying to" banner in the composer. Dropped the italic so both surfaces use the same plain `bodySmall` style.

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
