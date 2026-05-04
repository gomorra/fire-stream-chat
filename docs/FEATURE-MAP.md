<!-- last-verified: 2026-04-29 -->

# Feature → File Map

For each cross-cutting feature, the files that implement it across all layers. Lookup table only — for the *why* behind a pattern, see [PATTERNS.md](PATTERNS.md); for product description, see [SPEC.md](SPEC.md).

> **Maintenance.** When you add, move, rename, or delete a file in `app/src/main/java/`, check whether it's listed below and update if so. Refresh `last-verified` quarterly. Stale entries are worse than missing ones — prune aggressively.

Only features that span **4+ packages** are listed here. Single-screen features (Settings sections, Starred, Archived, Profile setup, etc.) are obvious from the package layout in [ARCHITECTURE.md §12](ARCHITECTURE.md).

---

## Voice Call (1-on-1, WebRTC)

Real-time audio call via WebRTC, signalled through Firestore, woken by a high-priority FCM push.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/call/CallService.kt` | Foreground service — owns `PeerConnection` lifecycle, ICE, media streams |
| `app/src/main/java/com/firestream/chat/data/call/CallStateHolder.kt` | `@Singleton` — bridges service ↔ UI via `StateFlow<CallState>` |
| `app/src/main/java/com/firestream/chat/data/call/CallNotificationManager.kt` | Ongoing-call + incoming-call notifications |
| `app/src/main/java/com/firestream/chat/data/call/WebRtcPeerConnectionFactory.kt` | WebRTC factory + ICE server config |
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirestoreCallSource.kt` | Signalling — `calls/{callId}` doc + ICE subcollections |
| `app/src/main/java/com/firestream/chat/data/repository/CallRepositoryImpl.kt` | Domain wrapper around the call source |
| `app/src/main/java/com/firestream/chat/ui/call/CallActivity.kt` | Separate Android Activity (lock-screen support) — *not* a NavHost route |
| `app/src/main/java/com/firestream/chat/ui/call/CallScreen.kt` | In-call UI |
| `app/src/main/java/com/firestream/chat/ui/call/CallViewModel.kt` | UI state from `CallStateHolder` + control intents |
| `app/src/main/java/com/firestream/chat/ui/call/CallControlButton.kt` | Mute / speaker control |
| `app/src/main/java/com/firestream/chat/ui/calls/CallsScreen.kt` | Call-log tab in MainScreen pager |
| `app/src/main/java/com/firestream/chat/ui/calls/CallsViewModel.kt` | Call-log derived from message store |
| `functions/index.js` | `sendCallPushNotification` Cloud Function — high-priority FCM on `calls/{id}` create |
| `app/src/test/java/com/firestream/chat/data/call/CallStateHolderTest.kt` | State-flow transitions |
| `app/src/test/java/com/firestream/chat/ui/calls/CallsViewModelTest.kt` | Call-log derivation |

**Entry point:** outgoing tap → `ChatScreen.kt` phone icon → `CallStateHolder.startCall()` → `CallService` foregrounds.

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

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/util/ImageCompressor.kt` | EXIF-aware compress; `inSampleSize` for memory-safe decode |
| `app/src/main/java/com/firestream/chat/data/util/MediaFileManager.kt` | `Android/media/com.firestream.chat/{chatId}/{messageId}.{ext}` storage + gallery export |
| `app/src/main/java/com/firestream/chat/data/worker/MediaBackfillWorker.kt` | WorkManager job — 15-min periodic backfill, respects `AutoDownloadOption` + WiFi |
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirebaseStorageSource.kt` | Upload with `addOnProgressListener` → `uploadProgress` flow |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendMediaMessage`, `downloadAndSave` (in-flight dedup map), per-chat scan |
| `app/src/main/java/com/firestream/chat/ui/chat/MessageBubble.kt` | IMAGE branch — aspect ratio from `mediaWidth/mediaHeight`, prefers `localUri` |
| `app/src/main/java/com/firestream/chat/ui/chat/ImagePreviewScreen.kt` | Pinch-to-zoom + caption before send |
| `app/src/main/java/com/firestream/chat/ui/chat/FullscreenImageViewer.kt` | Tap-to-open viewer |
| `app/src/main/java/com/firestream/chat/ui/chat/SharedMediaScreen.kt` | Shared-media gallery in profile |
| `app/src/main/java/com/firestream/chat/ui/chat/SharedMediaViewModel.kt` | Image stream for the gallery |
| `app/src/test/java/com/firestream/chat/data/util/MediaFileManagerTest.kt` | Local file path semantics |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryLocalUriTest.kt` | `localUri` Room round-trip |

**Entry point:** image picker in `ChatScreen.kt` → `ImagePreviewScreen` → `MessageRepositoryImpl.sendMediaMessage()`.

---

## Shared Lists

Lists shared into chats as a live `LIST` message bubble. Subcollection-based item storage.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/domain/usecase/list/SendListUpdateToChatsUseCase.kt` | Multi-repo orchestration — creates `LIST` message, updates list, writes history |
| `app/src/main/java/com/firestream/chat/data/repository/ListRepositoryImpl.kt` | List CRUD + share/unshare flows |
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirestoreListSource.kt` | `lists/{id}/items/{itemId}` subcollection, denormalized counts |
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirestoreListHistorySource.kt` | `lists/{id}/history/{entryId}` audit trail |
| `app/src/main/java/com/firestream/chat/ui/lists/ListsScreen.kt` | Lists tab in MainScreen pager |
| `app/src/main/java/com/firestream/chat/ui/lists/ListsViewModel.kt` | List index + counts |
| `app/src/main/java/com/firestream/chat/ui/lists/ListDetailScreen.kt` | List edit screen |
| `app/src/main/java/com/firestream/chat/ui/lists/ListDetailViewModel.kt` | 30s debounce for `LIST` update bubble fan-out |
| `app/src/main/java/com/firestream/chat/ui/lists/SharedListsScreen.kt` | Lists shared into a specific chat |
| `app/src/main/java/com/firestream/chat/ui/lists/SharedListsViewModel.kt` | Per-chat list filter |
| `app/src/main/java/com/firestream/chat/ui/lists/AvatarStack.kt` | Participant stack |
| `app/src/main/java/com/firestream/chat/ui/lists/ListContextSheet.kt` | Context actions sheet |
| `app/src/main/java/com/firestream/chat/ui/lists/ListShareSheet.kt` | Chat-picker for share |
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
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirestoreMessageSource.kt` | `pollData` field on the message subcollection |
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
| `app/src/main/java/com/firestream/chat/data/remote/firebase/RealtimePresenceSource.kt` | `.info/connected` pattern + `onDisconnect()` registration |
| `app/src/main/java/com/firestream/chat/data/repository/UserRepositoryImpl.kt` | Combines RTDB presence into the `observeUser()` stream |
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirestoreUserSource.kt` | Persisted `lastSeen` mirror |
| `functions/index.js` | `syncPresenceToFirestore` Cloud Function — RTDB → Firestore mirror with `lastSeen` transaction guard |
| `app/src/test/java/com/firestream/chat/data/remote/firebase/RealtimePresenceSourceTest.kt` | State-machine reconnect/teardown |
| `app/src/test/java/com/firestream/chat/data/repository/UserRepositoryImplPresenceTest.kt` | Presence stream merge |

**Entry point:** `FireStreamApp.onCreate` → `ProcessLifecycleOwner.observe(AppLifecycleObserver)`.

---

## E2E Encryption (with release-mode opt-out)

Signal Protocol message encryption. Disabled in debug builds; release users can opt out via Settings → Privacy.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/crypto/SignalManager.kt` | Encrypt / decrypt orchestration |
| `app/src/main/java/com/firestream/chat/data/crypto/SignalProtocolStoreImpl.kt` | `SignalProtocolStore` backed by `SignalDatabase` |
| `app/src/main/java/com/firestream/chat/data/local/SignalDatabase.kt` | Dedicated `signal.db` — keys survive `AppDatabase` destructive migrations |
| `app/src/main/java/com/firestream/chat/data/remote/firebase/FirebaseKeySource.kt` | `keyBundles/{userId}` pre-key bundle exchange |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `BuildConfig.DEBUG` + `e2eEncryptionEnabledFlow` guard around the Signal branch |
| `app/src/main/java/com/firestream/chat/data/local/PreferencesDataStore.kt` | `e2eEncryptionEnabledFlow` (default `true`) |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsScreen.kt` | Privacy → Encryption toggle (release builds) |
| `app/src/main/java/com/firestream/chat/ui/settings/SettingsViewModel.kt` | Wires the toggle |
| `app/src/test/java/com/firestream/chat/data/local/SignalDatabaseSmokeTest.kt` | Dedicated DB smoke |
| `app/src/test/java/com/firestream/chat/ui/settings/SettingsViewModelTest.kt` | Toggle persistence |

**Entry point:** every send via `MessageRepositoryImpl.sendMessage()` — the guard at the top of the function picks plaintext or Signal.

---

## Push Notifications

FCM-driven message + call wake-ups. Per-user unread counts in Firestore.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/remote/fcm/FCMService.kt` | `FirebaseMessagingService` — extracts payload, marks delivered, suppresses for active chat |
| `app/src/main/java/com/firestream/chat/data/remote/fcm/ActiveChatTracker.kt` | `@Singleton` — tracks the foreground chatId for suppression |
| `app/src/main/java/com/firestream/chat/MainActivity.kt` | Reads `chatId` / `senderId` extras → deep link |
| `functions/index.js` | `sendPushNotification` (on message create) + `sendCallPushNotification` (on call create) Cloud Functions |
| `app/src/main/AndroidManifest.xml` | `FirebaseMessagingService` + `POST_NOTIFICATIONS` permission |
| `app/src/test/java/com/firestream/chat/data/remote/fcm/ActiveChatTrackerTest.kt` | Suppression behaviour |

**Entry point:** Firestore message create → `sendPushNotification` Cloud Function → `FCMService.onMessageReceived` → notification or in-app marker.

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
| `app/src/main/java/com/firestream/chat/ui/chat/widget/TimerPickerWidget.kt` | hh:mm:ss wheel-picker widget mounted above composer |
| `app/src/main/java/com/firestream/chat/ui/chat/widget/TimerSetWidgetState.kt` | Widget-local state + duration math |
| `app/src/main/java/com/firestream/chat/ui/chat/TimerMessageBubble.kt` | Bubble content for TIMER — alarm icon + live countdown / "Timer ended" / struck-through "Cancelled" + caption |
| `app/src/main/java/com/firestream/chat/ui/chat/ChatTimerReactor.kt` | Observes `ChatUiState.messages` for TIMER state changes; schedules / cancels alarms idempotently for both sender and recipient |
| `app/src/main/java/com/firestream/chat/data/timer/TimerAlarmScheduler.kt` | Thin AlarmManager wrapper with exact-vs-inexact fallback |
| `app/src/main/java/com/firestream/chat/data/timer/TimerAlarmReceiver.kt` | BroadcastReceiver fired by AlarmManager → posts alarm-style notification + flips state to COMPLETED |
| `app/src/main/java/com/firestream/chat/data/timer/TimerNotificationChannel.kt` | `timer_alarms` channel (IMPORTANCE_HIGH, default alarm sound, alarm vibration) |
| `app/src/main/java/com/firestream/chat/data/timer/BootCompletedReceiver.kt` + `BootRestoreLogic.kt` | Re-registers RUNNING-and-still-future timers after device reboot |
| `app/src/main/java/com/firestream/chat/domain/model/Message.kt` + `TimerState.kt` | TIMER message type + `timerDurationMs` / `timerStartedAtMs` / `timerState` fields |
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendTimerMessage` / `cancelTimer` / `markTimerCompleted` (server-stamped fire time) |
| `app/src/main/AndroidManifest.xml` | `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` / `RECEIVE_BOOT_COMPLETED` permissions + receiver registrations |

**Entry point:** type `.` in any chat composer → `ChatCommandsManager.onComposerTextChanged()` → `CommandPalette` opens → tap `.timer.set` (or type it) → `TimerPickerWidget` mounts → send → `MessageRepository.sendTimerMessage()` → `ChatTimerReactor` schedules alarms on both sides via `TimerAlarmScheduler`.

---

## Adding a feature here

Create an entry only when the feature spans 4+ packages. Otherwise let the package layout speak for itself. New entries follow the same shape: one-paragraph description → table of files with one-line roles → entry point.
