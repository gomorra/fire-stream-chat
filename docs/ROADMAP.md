# FireStream Chat — Product Roadmap

## Context

FireStream Chat is an Android messaging app built with Kotlin, Jetpack Compose, and Firebase, using the Signal Protocol for E2E encryption. The app already has a solid foundation: phone-based authentication, 1-to-1 and group chats, text and media messaging with encryption, message editing/deletion, typing indicators, contact sync, and push notifications.

This roadmap charts the path from the current state to a fully-featured messaging platform comparable to Signal and WhatsApp, organized into progressive phases that each deliver user-visible value.

**Refreshed 2026-07-18:** this document had drifted badly out of date (last touched 2026-04-30) — most of Phases 1, 2, and 5, plus 4.1 and half of 4.5, had already shipped without the roadmap being updated. Every phase/item below has been re-verified against `CHANGELOG.md` and the current codebase and marked accordingly. **Legend: ✅ = shipped (version noted where the CHANGELOG dates it); unmarked = still open.**

For the current product surface, see `docs/SPEC.md`. For the authoritative shipping history (what changed, when, and why), see `CHANGELOG.md` at the repo root — do not maintain a parallel "already implemented" list here.

---

## Phase 1 — Core Messaging Completeness ✅ (v1.0.0)

**Goal:** Bring messaging to feature parity with the basics users expect from any modern chat app.

All six sub-items below shipped together as part of the pre-1.0.0 bulk build (CHANGELOG "Phase 1 — Core messaging completeness", 2026-03-07) and are live in the current app; see `docs/SPEC.md` for the shipped shape of each. Kept as a historical record, not a to-do list.

### 1.1 Voice Messages ✅ (v1.0.0)
- Record audio with waveform visualization in the chat composer
- Playback inline with progress indicator and speed control (1x/1.5x/2x)
- Encrypt audio files via Signal Protocol before upload
- New `MessageType.VOICE` with duration metadata
- Files: `ui/chat/ChatScreen.kt`, `domain/model/Message.kt`, `data/repository/MessageRepositoryImpl.kt`

### 1.2 Message Reactions ✅ (v1.0.0)
- Long-press a message to show emoji reaction picker (quick reactions + full picker)
- Store reactions as a map (`userId → emoji`) on the message document in Firestore
- Display reaction chips below message bubbles with counts
- New field `reactions: Map<String, String>` on `Message` model

### 1.3 Reply-to Messages (UI) ✅ (v1.0.0)
- Swipe-to-reply gesture, reply preview banner in composer, quoted snippet rendered above the reply. Jump-to-source on tap shipped later (v1.0.0, 2026-04-16).

### 1.4 Message Forwarding ✅ (v1.0.0)
- Long-press menu option to forward a message to a picked destination chat, with a forward indicator label (`ForwardChatPicker`).

### 1.5 Link Previews ✅ (v1.0.0)
- URL detection with fullscreen viewer support for link-preview images (2026-03-13). Cloud Function OG-metadata fetch as originally scoped.

### 1.6 Read Receipts (Enhanced) ✅ (v1.0.0)
- Per-recipient delivery/read detail is reachable via `Routes.messageInfo(messageId, chatId)`.

---

## Phase 2 — User Experience & Chat Management ✅ (v1.0.0)

**Goal:** Provide the organizational and personalization features that make daily use comfortable.

All six sub-items shipped as part of the pre-1.0.0 bulk build (CHANGELOG "Phase 2 — User experience & chat management", 2026-03-08, plus the earlier bundled work) and are live today: `ui/settings/`, `ui/profile/ProfileScreen.kt`, `ui/theme/` dark mode, chat pin/archive/mute on `ChatEntity`, in-chat + chat-list search, and `ui/starred/StarredMessagesScreen.kt`. Kept as a historical record, not a to-do list.

### 2.1 Settings Screen ✅ (v1.0.0)
### 2.2 User Profile Screen ✅ (v1.0.0)
### 2.3 Dark Mode / Theming ✅ (v1.0.0) — refreshed 2026-04-19 ("Dark palette refresh")
### 2.4 Chat Organization ✅ (v1.0.0) — pin/archive/mute on `Chat`
### 2.5 In-App Search ✅ (v1.0.0)
### 2.6 Starred / Saved Messages ✅ (v1.0.0)

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

### 3.3 Block & Report — **Block half shipped** ✅ (v1.0.0), Report still open
- Block/unblock is fully wired: `UserRepository.blockUser/unblockUser`, `ProfileViewModel`, and `MessageRepositoryImpl` filters incoming messages and refuses sends to/from blocked users (`ERR_USER_BLOCKED`) — done via `UserSource`, not a separate `BlockRepository`, so the file path in this item is stale.
- Report users/messages (Firestore admin collection) is **not implemented** — no report action exists anywhere in the codebase.
- Blocked users list screen in settings with unblock option — not verified as a dedicated screen; only the per-profile block toggle exists.

### 3.4 Safety Number / Key Verification
- Display safety number for each contact (Signal-style numeric fingerprint)
- QR code generation and scanning for in-person verification
- Mark contacts as "verified" after successful comparison
- Files: new `ui/verification/SafetyNumberScreen.kt`, `data/crypto/SignalManager.kt`

### 3.5 Group E2E Encryption (Sender Keys)
- Still just scaffolding as of 2026-07-18 — `SignalSenderKeyEntity` / `SignalProtocolStoreImpl` exist, but no `SenderKeyDistributionMessage`/`GroupCipher` usage anywhere. Confirmed by CHANGELOG 1.2.0: "Group and broadcast were never encrypted, so the toggle has no effect on those." — still true.
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

### 4.1 Voice Calls (1-to-1) ✅ (v1.0.0)
- Shipped 2026-03-13: `CallService` foreground service, `CallStateHolder`, incoming-call FCM, `CallActivity`, call push Cloud Function. Call history screen followed as the Calls tab (bottom nav, 2026-03-21).

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

### 4.5 Location Sharing — **static send shipped** ✅ (v1.0.0), live sharing still open
- Send current location as a message ✅ (v1.0.0, 2026-04-06): `MessageType.LOCATION`, GPS via `FusedLocationProviderClient`, static-tile preview, `geo:` URI on tap, via `LocationPickerSheet` — shipped with **OpenStreetMap** tiles rather than the Google Maps integration originally scoped.
- Live location sharing with configurable duration (15min / 1h / 8h) — not implemented.

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

## Phase 5 — Group Features & Administration ✅ (v1.0.0, all 5 sub-items)

**Goal:** Make group chats powerful and manageable for communities.

All five items shipped in the pre-1.0.0 bulk build — this phase is fully complete, including 5.2 and 5.4, which the previous roadmap revision believed were still open. Kept as a historical record, not a to-do list.

### 5.1 Enhanced Group Management ✅ (v1.0.0)
- Shipped 2026-03-09: description, invite links, QR codes (`5f0819b`). Admin-approval-for-new-members was not called out in the CHANGELOG line — not separately verified.

### 5.2 Group Permissions ✅ (v1.0.0)
- Contrary to this roadmap's own "believed open" assumption at the time of the last refresh, this is fully wired, not just scaffolded: `domain/model/GroupPermissions.kt` (`sendMessages`/`editGroupInfo`/`addMembers`/`createPolls` role gates + `isAnnouncementMode`), `GroupRole` (`OWNER`/`ADMIN`/`MEMBER`), `CheckGroupPermissionUseCase`, and enforcement in `ChatScreen`/`ChatInfoManager`/`ChatComposerState` (announcement-mode composer lock). No dedicated CHANGELOG entry — verified directly against code.

### 5.3 Polls ✅ (v1.0.0)
- Shipped 2026-03-09 (`eda95ae`).

### 5.4 Mentions & Notifications ✅ (v1.0.0)
- Contrary to this roadmap's own "believed open" assumption, this shipped 2026-03-09 alongside group creation ("Group creation + mention parser with mention-only notification setting") — `domain/util/MentionParser`.

### 5.5 Broadcast Lists ✅ (v1.0.0)
- Shipped 2026-03-09 (`cd7ec32`).

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

### 6.3 Offline Resilience — partially covered, full item still open
- A narrower related fix shipped in v1.10.5 (2026-06-08): orphaned sends (coroutine cancelled mid-send, e.g. leaving the chat) are flipped from stuck "sending" to `FAILED` on app start / chat re-entry, restoring tap-to-retry.
- The originally-scoped auto-queue-and-resend-on-reconnect + `MessageRetryWorker` with exponential backoff + offline indicator are **not implemented** — explicitly tracked as the deferred "durable-outbox follow-up" in `TECH_DEBT.md`.

### 6.4 Performance & Pagination
- Paginated message loading (Paging 3 library)
- Lazy image loading with thumbnail placeholders
- Database query optimization with proper indices
- Files: `data/local/dao/MessageDao.kt`, `ui/chat/ChatViewModel.kt`

### 6.5 Notifications Enhancement — partially shipped
- Notification grouping by chat ✅ — `FCMService` recovers the existing `MessagingStyle` per chat and bundles messages together (`setGroupConversation`), which also gives message-preview-in-notification for free.
- Inline reply from notification (`RemoteInput`) — not implemented.
- Privacy option to hide message content in the notification — not implemented.
- Notification channels per chat category — not implemented; still a single "Messages" channel plus the separate updates/timer channels.

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
