# FireStream Chat — Product Specification

This document is the product-level feature list for **FireStream**, a real-time Android messaging app with end-to-end encryption.

### Core Messaging

- **1-on-1 Chat**: Text messaging with real-time syncing.
- **Media Support**: Send and receive images, voice messages, and generic documents. Includes a fullscreen image viewer and voice media player with adjustable playback speed. Local-first media pipeline: images are compressed (`ImageCompressor`), stored locally (`MediaFileManager` at `filesDir/media/{chatId}/`), displayed immediately from local files, and uploaded to Firebase Storage with a progress overlay. `MediaBackfillWorker` runs on first launch to download existing media. Full quality opt-in via DataStore preference. **Image Preview with Caption**: Gallery/camera image selection opens a fullscreen preview screen (`ImagePreviewScreen`) with pinch-to-zoom and an optional caption text field before sending (WhatsApp-style). Captions are stored in the message `content` field and displayed below the image in message bubbles. Chat list previews show `📷 caption` for captioned images.
- **End-to-End Encryption (E2EE)**: All messages are encrypted natively on the client device using the **Signal Protocol** before transmission. Encryption is disabled in debug builds (`BuildConfig.DEBUG` guard) — messages are sent as plaintext to avoid key-loss issues during development. Release builds expose a Settings → Privacy toggle that lets users opt out of E2E encryption per-device (default: on).
- **Read Receipts Status**:
  - **Sent** (Single gray tick): Message reached the server.
  - **Delivered** (Double gray tick): Message reached the recipient's device.
  - **Read** (Double blue tick): Recipient opened and viewed the conversation.
  - **Privacy Control**: Users can disable read receipts (Bidirectional enforcement: if disabled by either user, both users see only up to the Delivered status).
- **Typing Indicators**: Real-time "typing..." status. In group chats the typing row shows the avatar of each participant currently typing.
- **Voice Dictation**: Composer microphone button uses the Android system `SpeechRecognizer` to dictate into the input field. Dictation language is selectable in Settings → Chat (German / English). Devices without an offline language pack surface a clear error rather than failing silently.

### Message Interactions

- **Reply**: Swipe-to-reply or long-press context menu to quote/reply to specific messages. Reply previews show an inline thumbnail when the quoted message is an image.
- **React**: Emoji reactions on messages (map of userId → emoji).
- **Forward**: Share messages to other active chats.
- **Star**: Bookmark messages.
- **Edit/Delete**: Edit a previously sent message or delete it entirely for both parties (soft-delete with `deletedAt` timestamp).
- **Message Info**: View exact delivery and read timestamps for participants.
- **Link Previews**: Automatic rich preview card generation for URLs included in messages. `LinkPreviewSource` parses Open Graph and Twitter Card meta tags; falls back to `WebPagePreviewCapture` (off-screen WebView screenshot) when no image metadata is available. Results are LRU-cached.
- **Location Sharing**: Send a location pin as a `LOCATION` message with `latitude`/`longitude` coordinates via `LocationPickerSheet`. Rendered as a map preview in the message bubble.
- **Polls**: Create and vote on polls within group or individual chats. Supports multiple-choice, anonymous voting, and manual close.
- **Mentions**: `@username` and `@everyone` support in group chats. Mentioned users receive targeted notifications.

### Group Chats

- **Create Groups**: Multi-participant group chats with a name and avatar.
- **Group Roles**: Three-tier role system — `OWNER`, `ADMIN`, `MEMBER`.
- **Group Permissions**: Configurable per-chat policies controlling who can send messages, edit group info, add members, and create polls. Announcement Mode restricts sending to admins only.
- **Group Management**: Update group name, avatar, and description. Transfer ownership, promote/demote admins, remove members.
- **Invite Links & QR Codes**: Generate a shareable invite link and QR code for joining a group. Links can be revoked.
- **Member Approval**: Optionally require admin approval before new members join via invite link. Approval/rejection workflow for pending members.
- **Leave Group**: Any non-owner member can leave. Owners must transfer ownership first.

### Broadcast Lists

- **One-Way Messaging**: Send a message to multiple recipients simultaneously. Recipients receive the message as an individual chat — they cannot see other recipients.
- **Broadcast Chat Type**: Implemented as a distinct `ChatType.BROADCAST`. The chat list shows a campaign icon to distinguish broadcasts from regular chats.
- **Read Receipts Hidden**: No delivery/read tracking for broadcast messages.

### Voice Calls

- **1-to-1 Voice Calls**: Real-time audio calls using WebRTC.
- **Signaling**: Call state (ringing, connected, ended) is coordinated via Firestore. SDP offer/answer and ICE candidates are exchanged through dedicated call documents.
- **FCM Wake-Up**: Incoming calls trigger a high-priority FCM push notification so the callee's device wakes up even in the background.
- **Lock-Screen UI**: `CallActivity` is a separate Activity (not part of the NavHost) to support rendering on the lock screen.
- **In-Call Controls**: Mute microphone and toggle speakerphone.
- **Call Log**: Dedicated Calls tab (next to Chats in the bottom nav) shows a history of incoming, outgoing, and missed calls sourced from call-type messages in the Room database.

### Shared Lists

- **List Types**: Three list types — `CHECKLIST`, `SHOPPING`, and `GENERIC` (with bullet/number/dash/none style options).
- **List Items**: Each item has text, check state, optional quantity/unit fields, order index, and the ID of the user who added it.
- **Sharing**: Lists can be shared into one or more chats as a `LIST` message bubble. Recipients see the live list state and can interact with items.
- **Diff & Sync**: Client-side `ListDiff` accumulates mutations (add, update, reorder, check, remove, title change, type change). A 30-second debounce in `ListDetailViewModel` sends a single update bubble to all `sharedChatIds`.
- **History**: Every mutation is recorded via `ObserveListHistoryUseCase` / `FirestoreListHistorySource`. History is written fire-and-forget with a `SupervisorJob` scope so it never blocks the main flow.
- **Undo**: Removed items are filtered from the display via a `pendingRemoval` set; the actual `removeItem()` call fires only on snackbar dismiss.
- **Unshared Lists**: Lists that have been unshared from a chat show a locked-state indicator in the `ListBubble`.

### Organization & User Management

- **Local & Global Search**: Full-text search support to locate messages either within a specific conversation or globally across all chats.
- **Shared Media**: Dedicated screens in User Profile to browse shared images.
- **Online/Last Seen Presence**: Live presence backed by Firebase Realtime Database (`RealtimePresenceSource`). `AppLifecycleObserver` (process-level `DefaultLifecycleObserver` registered on `ProcessLifecycleOwner`) manages the online/offline transition — this eliminates false-offline flickers during Activity transitions (e.g. opening `CallActivity`, permission dialogs). `syncPresenceToFirestore` Cloud Function mirrors RTDB state to Firestore for abrupt-disconnect cases. Privacy controls exist to configure who can view the last seen status.
- **Profile Setup**: Phone-number authentication with profile creation (Display Name, Status Text, Avatar URL).
- **Blocking Mechanism**: Block/unblock users to prevent communication.
- **Share Intent**: External share intents (text or media) are routed through a `SharePickerScreen` so users can forward content into any chat.

### Distribution & Updates

- **Sideload distribution**: APKs are published to GitHub Releases by a tag-driven CI workflow (`.github/workflows/release-apk.yml`). Pushing a `v*` tag builds the signed `firebase` APK by default and attaches it plus the matching `latest-firebase.json` manifest to a GitHub Release; pocketbase is opt-in via manual `workflow_dispatch` (see [`docs/RELEASING.md`](RELEASING.md#selecting-flavors)). The Play Store is not part of the distribution path.
- **In-app updater**: A 24-hour `UpdateCheckWorker` (unmetered network constraint) fetches the manifest at the GitHub "latest release" alias URL baked into `BuildConfig.UPDATE_MANIFEST_URL`. When the manifest's `versionCode` exceeds the installed one, a low-priority "App updates" notification is posted; tapping it deep-links to Settings → Check for updates. No nag dialogs on app start.
- **Manual check**: Settings → Help also exposes a "Check for updates" row above "App Version" for on-demand checks. The dialog shows release notes and a download progress bar.
- **Verified install**: Downloads land in `cacheDir/apk_updates/`, are SHA-256 verified against the manifest, and handed to the system installer via FileProvider + `ACTION_VIEW`. The app declares `REQUEST_INSTALL_PACKAGES`; users grant "Install unknown apps" once on first install.
- **Release signing**: Production APKs are signed with a single CI keystore (passwords + base64 keystore stored in GitHub Secrets). Local `assembleRelease` falls back to the debug keystore when those env vars / `local.properties` keys are absent — see [RELEASING.md](RELEASING.md) for the one-time setup.

---

**See also:** [ARCHITECTURE.md](ARCHITECTURE.md) for how these features are implemented, [ROADMAP.md](ROADMAP.md) for upcoming work, [RELEASING.md](RELEASING.md) for the release pipeline.
