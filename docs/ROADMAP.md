# FireStream Chat — Product Roadmap

## Context

FireStream Chat is an Android messaging app built with Kotlin, Jetpack Compose, and Firebase, using the Signal Protocol for E2E encryption. The app already has a solid foundation: phone-based authentication, 1-to-1 and group chats, text and media messaging with encryption, message editing/deletion, typing indicators, contact sync, and push notifications.

This roadmap charts the path from the current state to a fully-featured messaging platform comparable to Signal and WhatsApp, organized into progressive phases that each deliver user-visible value.

---

## Current State (Already Implemented)

- Phone number authentication with OTP
- User profile setup (display name)
- 1-to-1 and group chats (create, list, delete)
- Text messages with E2E encryption (Signal Protocol + post-quantum Kyber)
- Media messages (images, videos, documents)
- Message editing and deletion
- Message status tracking (sending/sent/delivered/read/failed)
- Typing indicators
- Contact sync and search
- Push notifications (FCM)
- Online/offline status with last seen
- Local caching (Room) with real-time Firestore sync

---

## Phase 1 — Core Messaging Completeness

**Goal:** Bring messaging to feature parity with the basics users expect from any modern chat app.

### 1.1 Voice Messages
- Record audio with waveform visualization in the chat composer
- Playback inline with progress indicator and speed control (1x/1.5x/2x)
- Encrypt audio files via Signal Protocol before upload
- New `MessageType.VOICE` with duration metadata
- Files: `ui/chat/ChatScreen.kt`, `domain/model/Message.kt`, `data/repository/MessageRepositoryImpl.kt`

### 1.2 Message Reactions
- Long-press a message to show emoji reaction picker (quick reactions + full picker)
- Store reactions as a map (`userId → emoji`) on the message document in Firestore
- Display reaction chips below message bubbles with counts
- New field `reactions: Map<String, String>` on `Message` model
- Files: `domain/model/Message.kt`, `ui/chat/MessageBubble.kt`, `data/local/entity/MessageEntity.kt`

### 1.3 Reply-to Messages (UI)
- The `replyToId` field already exists on the Message model — build the UI
- Swipe-to-reply gesture on message bubbles
- Reply preview banner in composer (showing quoted message)
- Quoted message snippet rendered above the reply in the chat
- Files: `ui/chat/ChatScreen.kt`, `ui/chat/ChatViewModel.kt`

### 1.4 Message Forwarding
- Long-press menu option to forward a message
- Chat picker screen to select destination chat(s)
- Forward indicator label on forwarded messages
- New field `isForwarded: Boolean` on `Message` model
- Files: `domain/model/Message.kt`, `ui/chat/ChatScreen.kt`

### 1.5 Link Previews
- Detect URLs in outgoing/incoming text messages
- Fetch Open Graph metadata (title, description, image) server-side via Firebase Cloud Function
- Render preview card below the message text
- Cache previews locally in Room
- Files: `ui/chat/MessageBubble.kt`, new `data/remote/LinkPreviewSource.kt`

### 1.6 Read Receipts (Enhanced)
- Message status is tracked but not fully surfaced — build a detailed view
- Double-tap or long-press on sent message → "Message Info" screen showing delivery/read timestamps per recipient
- Group chat: show who has read the message
- Files: `ui/chat/ChatScreen.kt`, new `ui/chat/MessageInfoScreen.kt`

---

## Phase 2 — User Experience & Chat Management

**Goal:** Provide the organizational and personalization features that make daily use comfortable.

### 2.1 Settings Screen
- Central settings hub accessible from chat list
- Sections: Account, Privacy, Notifications, Storage, Appearance, Help
- New route `Routes.SETTINGS` and sub-routes
- Files: `navigation/NavGraph.kt`, new `ui/settings/` package

### 2.2 User Profile Screen
- View other users' profiles (avatar, name, status, phone, shared media)
- Accessible from chat header tap
- Block/unblock user action
- Shared media gallery tab
- Files: new `ui/profile/ProfileScreen.kt`, `navigation/NavGraph.kt`

### 2.3 Dark Mode / Theming
- System-default, light, dark mode toggle in settings
- Persist preference in DataStore
- Extend existing `ui/theme/` with dark color scheme
- Files: `ui/theme/Theme.kt`, `ui/theme/Color.kt`, new `data/local/PreferencesDataStore.kt`

### 2.4 Chat Organization
- **Pin chats** — pin up to 3 chats to the top of the list
- **Archive chats** — swipe to archive, separate "Archived" section
- **Mute notifications** per chat (1h / 8h / 1 week / always)
- New fields on `Chat` model: `isPinned`, `isArchived`, `muteUntil`
- Files: `domain/model/Chat.kt`, `ui/chatlist/ChatListScreen.kt`, `data/local/entity/ChatEntity.kt`

### 2.5 In-App Search
- **Global search** — search across all chats by message content
- **In-chat search** — search within a specific conversation
- Full-text search via Room FTS (FTS4) on the messages table
- Files: `ui/chatlist/ChatListScreen.kt`, `ui/chat/ChatScreen.kt`, `data/local/dao/MessageDao.kt`

### 2.6 Starred / Saved Messages
- Star individual messages for quick reference
- Dedicated "Starred Messages" screen accessible from settings
- New field `isStarred: Boolean` on `Message` model
- Files: `domain/model/Message.kt`, new `ui/starred/StarredMessagesScreen.kt`

---

## Phase 3 — Privacy & Security Enhancements

**Goal:** Match Signal-level privacy controls and strengthen trust.

### 3.1 Disappearing Messages
- Per-chat timer setting (off / 5s / 30s / 1min / 5min / 1h / 24h / 7d)
- Timer starts on read for 1-to-1, on send for groups
- Background WorkManager job to prune expired messages
- New fields on `Chat`: `disappearingMessagesDuration`
- Files: `domain/model/Chat.kt`, `data/repository/MessageRepositoryImpl.kt`, new `data/worker/MessageExpiryWorker.kt`

### 3.2 App Lock / Biometric Authentication
- Require fingerprint/face unlock to open app
- Auto-lock timeout setting (immediately / 1min / 5min / 30min)
- Use AndroidX Biometric library
- Files: new `ui/lock/AppLockScreen.kt`, `data/local/PreferencesDataStore.kt`

### 3.3 Block & Report
- Block users to prevent them from sending messages
- Report users/messages (send report to Firestore admin collection)
- Blocked users list in settings with unblock option
- New `BlockedUserRepository` and Firestore `blockedUsers` subcollection
- Files: new `domain/repository/BlockRepository.kt`, `ui/settings/`

### 3.4 Safety Number / Key Verification
- Display safety number for each contact (Signal-style numeric fingerprint)
- QR code generation and scanning for in-person verification
- Mark contacts as "verified" after successful comparison
- Files: new `ui/verification/SafetyNumberScreen.kt`, `data/crypto/SignalManager.kt`

### 3.5 Group E2E Encryption (Sender Keys)
- The Signal Sender Key infrastructure is already scaffolded (SenderKeyStore, entities)
- Implement Sender Key Distribution Messages on group creation/member join
- Encrypt group messages using SenderKeyMessage
- Files: `data/crypto/SignalManager.kt`, `data/repository/MessageRepositoryImpl.kt`

### 3.6 Screen Security
- Prevent screenshots in-app (FLAG_SECURE)
- Toggle in privacy settings
- Files: `MainActivity.kt`, `data/local/PreferencesDataStore.kt`

---

## Phase 4 — Rich Media & Communication

**Goal:** Go beyond text — support the full range of communication modalities.

### 4.1 Voice Calls (1-to-1)
- WebRTC peer-to-peer audio calls
- Firebase Cloud Functions for signaling (offer/answer/ICE candidates)
- In-call UI with mute, speaker, end call controls
- Call history screen
- Push notification for incoming calls (high-priority FCM)
- Files: new `ui/call/` package, new `data/remote/webrtc/` package, `navigation/NavGraph.kt`

### 4.2 Video Calls (1-to-1)
- Extend voice call infrastructure with video track
- Camera switch (front/back), video toggle
- Picture-in-picture support
- Files: `ui/call/` package extension

### 4.3 Group Voice/Video Calls
- SFU (Selective Forwarding Unit) server for multi-party calls
- Grid layout for participant video feeds
- Mute/video toggles per participant
- Files: `ui/call/` package extension, backend SFU integration

### 4.4 Stories / Status Updates
- Post text/image/video stories visible for 24 hours
- Story viewer with progress bar and navigation
- Privacy controls (my contacts / selected contacts / everyone)
- Stories tab or section in chat list
- Files: new `ui/stories/` package, new `domain/model/Story.kt`, `navigation/NavGraph.kt`

### 4.5 Location Sharing
- Send current location as a message (static map thumbnail)
- Live location sharing with configurable duration (15min / 1h / 8h)
- Google Maps integration for rendering
- Files: `domain/model/Message.kt` (new `MessageType.LOCATION`), new `ui/chat/LocationPicker.kt`

### 4.6 Stickers & GIFs
- Built-in sticker packs with download/management
- GIF search via Giphy/Tenor API integration
- Sticker/GIF picker accessible from composer
- Files: new `ui/chat/StickerPicker.kt`, new `data/remote/GiphySource.kt`

### 4.7 Document Sharing Enhancements
- In-app document viewer (PDF, images)
- File size display and download progress
- Cloud storage integration (Google Drive picker)
- Files: `ui/chat/ChatScreen.kt`, `ui/chat/MessageBubble.kt`

---

## Phase 5 — Group Features & Administration

**Goal:** Make group chats powerful and manageable for communities.

### 5.1 Enhanced Group Management
- Group description field
- Group invite links (shareable URL)
- QR code for group join
- Admin approval for new members (optional)
- Files: `domain/model/Chat.kt`, `ui/chat/ChatScreen.kt`, new `ui/group/GroupSettingsScreen.kt`

### 5.2 Group Permissions
- Admin hierarchy: Owner → Admin → Member
- Configurable permissions: who can send messages, edit group info, add members
- Admin-only announcements mode
- Files: `domain/model/Chat.kt`, `ui/group/GroupSettingsScreen.kt`

### 5.3 Polls
- Create polls within group chats
- Single-choice and multiple-choice options
- Anonymous voting option
- Real-time vote count display
- Files: new `domain/model/Poll.kt`, `ui/chat/` poll components

### 5.4 Mentions & Notifications
- @mention individual users or @everyone in group chats
- Highlighted mention text in message bubbles
- Filtered notification: notify only when mentioned (optional setting)
- Files: `ui/chat/ChatScreen.kt`, `ui/chat/MessageBubble.kt`

### 5.5 Broadcast Lists
- Send a message to multiple contacts at once (1-to-many, not a group)
- Recipients see it as an individual message
- Files: new `domain/model/BroadcastList.kt`, new `ui/broadcast/` package

---

## Phase 6 — Platform & Reliability

**Goal:** Ensure the app is robust, performant, and ready for scale.

### 6.1 Chat Backup & Restore
- Encrypted backup to Google Drive
- Backup scheduling (daily/weekly/manual)
- Restore flow during new device setup
- Include media option (with size warning)
- Files: new `data/backup/` package, `ui/settings/`

### 6.2 Multi-Device Support
- Link secondary devices (tablet, web) via QR code
- Device-specific Signal Protocol sessions
- Message sync across linked devices
- Files: `data/crypto/SignalManager.kt`, new `ui/settings/LinkedDevicesScreen.kt`

### 6.3 Offline Resilience
- Queue outgoing messages when offline, auto-send on reconnect
- WorkManager-based message retry with exponential backoff
- Offline indicator in UI
- Files: `data/repository/MessageRepositoryImpl.kt`, new `data/worker/MessageRetryWorker.kt`

### 6.4 Performance & Pagination
- Paginated message loading (Paging 3 library)
- Lazy image loading with thumbnail placeholders
- Database query optimization with proper indices
- Files: `data/local/dao/MessageDao.kt`, `ui/chat/ChatViewModel.kt`

### 6.5 Notifications Enhancement
- Notification grouping by chat
- Inline reply from notification
- Message preview in notification (with privacy option to hide content)
- Notification channels per chat category
- Files: `data/remote/fcm/FCMService.kt`, `data/local/PreferencesDataStore.kt`

### 6.6 Accessibility
- Content descriptions on all interactive elements
- Screen reader support throughout
- Dynamic font sizing
- High contrast mode
- Files: all UI files across `ui/` package

---

## Phase 7 — Growth & Engagement

**Goal:** Features that help the app grow and keep users engaged.

### 7.1 Invite System
- Deep link invitations ("Join me on FireStream")
- SMS invite for contacts not on the platform
- Referral tracking
- Files: new `ui/invite/` package

### 7.2 Payment Integration (Optional)
- In-chat peer-to-peer payments
- Integration with payment APIs (Google Pay, UPI)
- Payment request messages
- Files: new `domain/model/Payment.kt`, new `data/remote/PaymentSource.kt`

### 7.3 Channels
- Public one-way broadcast channels (like Telegram channels)
- Subscribe/unsubscribe model
- Admin posting with comments section
- Files: new `domain/model/Channel.kt`, new `ui/channel/` package

---

## Ideas for General Improvements & New Features

### UX Improvements
- **Swipe actions on chat list** — swipe right to pin, swipe left to archive/delete
- **Chat wallpapers** — per-chat or global custom backgrounds
- **Message scheduling** — compose a message and schedule it for later delivery
- **Quick-switch between chats** — edge swipe gesture to jump to next unread chat
- **Compact/comfortable density toggle** — let users choose between spacious and compact chat layouts
- **Animated transitions** — shared element transitions between chat list and chat detail
- **Haptic feedback** — subtle vibrations on message send, reactions, and gestures

### AI-Powered Features
- **Smart replies** — suggest contextual quick responses based on incoming messages
- **Message summarization** — summarize long unread conversations (on-device or via API)
- **Auto-translation** — translate incoming messages inline with language detection
- **Intelligent notification priority** — ML-based notification ranking (urgent vs. casual)
- **Voice-to-text transcription** — auto-transcribe voice messages with on-device ML

### Social & Community Features
- **Communities** — group of groups with shared membership (like WhatsApp Communities)
- **Events** — create and RSVP to events within group chats
- **Shared media albums** — collaborative photo/video albums within a chat
- **Custom group roles** — define custom roles beyond admin/member with configurable permissions

### Developer & Power User Features
- **Bot framework** — API for building automated bots (weather, reminders, integrations)
- **Webhook support** — connect external services to send messages to chats
- **Custom themes** — user-created color schemes sharable as theme files
- **Keyboard shortcuts** — for tablet/desktop companion app usage
- **Export chat as PDF/HTML** — export full conversation history

### Infrastructure & Technical Improvements
- **End-to-end encrypted backups** — use a user-derived key (not Google's) for backup encryption
- **Certificate pinning** — pin Firebase and API certificates to prevent MITM
- **Reproducible builds** — enable deterministic builds for security auditing
- **Crash reporting** — Firebase Crashlytics integration for production monitoring
- **Analytics** — privacy-respecting anonymous usage analytics (opt-in)
- **App size optimization** — split APKs per ABI, asset optimization, R8 fine-tuning
- **Modularization** — split the app into Gradle modules (`:core`, `:feature:chat`, `:feature:auth`, etc.) for build speed and team scalability
- **CI/CD pipeline** — release path shipped (1.5.0): tag-driven `release-apk.yml` builds signed APKs for both flavors, publishes manifests + APKs to GitHub Releases, and the in-app updater consumes them. Still TODO: a per-PR `assembleDebug` / `test` / `lint` gate, and Firebase App Distribution for closed beta tracks.
- **Widget** — home screen widget showing recent unread messages or quick-compose

### Accessibility & Inclusion
- **RTL language support** — full right-to-left layout mirroring
- **Color-blind friendly palette** — alternate color schemes for different types of color blindness
- **Reduced motion mode** — disable animations for users sensitive to motion
- **Voice navigation** — TalkBack-optimized flow with logical focus order

---

## Testing Strategy (Applies to Every Phase)

Testing is a first-class requirement — every feature must be accompanied by tests before it is considered complete. Use tests as the primary feedback loop during implementation.

### Per-Feature Testing Requirements

Each feature implementation must include:

1. **Unit tests (JUnit + MockK + kotlinx-coroutines-test)**
   - Every new use case gets at least: success case, error/edge case, boundary conditions
   - Every new repository method tested with mocked data sources
   - ViewModel tests verifying state transitions and error handling
   - Crypto tests: encryption round-trip (encrypt → decrypt = original plaintext)
   - Test files live alongside source in `app/src/test/`

2. **UI / Instrumentation tests (Compose UI Test + Espresso)**
   - Every new screen: renders correctly, handles empty/loading/error states
   - User interaction flows: tap, swipe, long-press trigger expected behavior
   - Navigation: correct route transitions with expected arguments
   - Test files in `app/src/androidTest/`

3. **Integration tests**
   - Firestore security rules tested for each new collection/document pattern
   - End-to-end message flow: send → encrypt → store → receive → decrypt → display
   - Offline → online transitions: queued messages send correctly after reconnect

### Continuous Feedback Loop

- Run `./gradlew test` after every implementation step — never batch test runs
- Run `./gradlew connectedAndroidTest` for UI tests on emulator/device
- Full build verification: `./gradlew assembleDebug` must pass with zero warnings treated as errors
- If a test fails, fix it before moving to the next feature — never skip ahead

### Test Coverage Targets

| Layer | Target |
|-------|--------|
| Domain (use cases, models) | 90%+ |
| Data (repositories, data sources) | 80%+ |
| UI (ViewModels) | 80%+ |
| UI (Compose screens) | Key user flows covered |
| Crypto (Signal Protocol) | 95%+ (security-critical) |

### Security-Specific Testing (Phases 3, 5)

- Fuzz testing on decryption paths (malformed ciphertext, wrong keys)
- Session establishment edge cases (simultaneous first messages, re-registration)
- Key rotation and re-keying scenarios
- Group encryption: member add/remove and key distribution
