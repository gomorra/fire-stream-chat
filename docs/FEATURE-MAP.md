<!-- last-verified: 2026-07-18 -->

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
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirestoreCallSource.kt` | Signalling — `calls/{callId}` doc + ICE subcollections |
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
| `app/src/main/java/com/firestream/chat/data/worker/MediaBackfillWorker.kt` | WorkManager job — daily (24h) periodic backfill, respects `AutoDownloadOption` + WiFi |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirebaseStorageSource.kt` | Upload with `addOnProgressListener` → `uploadProgress` flow |
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

## E2E Encryption (with release-mode opt-out)

Signal Protocol message encryption. Disabled in debug builds; release users can opt out via Settings → Privacy.

| File | Role |
|---|---|
| `app/src/main/java/com/firestream/chat/data/crypto/SignalManager.kt` | Encrypt / decrypt orchestration |
| `app/src/main/java/com/firestream/chat/data/crypto/SignalProtocolStoreImpl.kt` | `SignalProtocolStore` backed by `SignalDatabase` |
| `app/src/main/java/com/firestream/chat/data/local/SignalDatabase.kt` | Dedicated `signal.db` — keys survive `AppDatabase` destructive migrations |
| `app/src/firebase/java/com/firestream/chat/data/remote/firebase/FirebaseKeySource.kt` | `keyBundles/{userId}` pre-key bundle exchange |
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
| `app/src/main/java/com/firestream/chat/data/repository/MessageRepositoryImpl.kt` | `sendMediaMessage` — shared image/video path; video branch transcodes, uploads thumbnail, retries without re-transcoding |
| `app/src/main/java/com/firestream/chat/domain/model/AppError.kt` | `MediaLimitException` → `AppError.Validation` mapping |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryMediaSendFailureTest.kt` | Video limit-guard / send-failure coverage |
| `app/src/test/java/com/firestream/chat/data/repository/MessageRepositoryRetryTest.kt` | Retry re-uploads without re-transcoding |
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

**Entry point:** record or pick a video in `ChatScreen.kt`'s composer → `ImagePreviewScreen` (video mode) → `MessageRepositoryImpl.sendMediaMessage()` guards via `VideoTranscoder.ensureWithinLimits`, transcodes, uploads a thumbnail → `MessageBubble` `VIDEO` branch renders it → tap opens `ChatViewModel.showFullscreenVideo()` → `FullscreenVideoPlayer`.

---

## Adding a feature here

Create an entry only when the feature spans 4+ packages. Otherwise let the package layout speak for itself. New entries follow the same shape: one-paragraph description → table of files with one-line roles → entry point.
