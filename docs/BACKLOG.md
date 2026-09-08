# FireStream Chat — Backlog

Open, unshipped work only: features, enhancements, and ideas that have **not** landed yet.

- **Shipped history** lives in [`CHANGELOG.md`](../CHANGELOG.md) (what changed, when, why) and the current product surface in [`SPEC.md`](SPEC.md). This file deliberately keeps no "already implemented" list — that parallel list is what rotted its predecessor.
- **Deferred/declined refactors** with a recorded reason live in [`TECH_DEBT.md`](../TECH_DEBT.md), not here. This file is for work nobody has ruled on yet.
- **Testing requirements** for anything built from this list are in [`TESTING.md`](TESTING.md).
- **Shipped but unverified** work — code that landed but whose on-device check never ran —
  is the one exception to "unshipped only"; it sits in *Pending on-device verification* below.

Items carry their old roadmap number in parentheses (e.g. `3.4`) so external references still resolve. Ordering within a section implies no priority.

**When an item ships, delete it from this file** — ideally as part of cutting the release that shipped it (see the `changelog-release` skill).

---

## Pending on-device verification

Code that has **shipped** but whose behaviour nobody has confirmed on real hardware.
It is not a feature gap and not tech debt — it is an unfinished check, and it is listed
here because a cloud agent has no other way to learn that the work is not fully done.
Delete an item once it has been verified (or once a fix for what the check found ships).

### Message search — prefilter chips + global scope (`56cb67a`…`261704d`, 2026-09-07; global 2026-09-08)
- Device pass never run for the whole feature: the chip row's horizontal scroll under a
  thumb, the date range picker, the photo/video grid, and tapping a video tile through to
  the player.
- Specifically unconfirmed: that a **local** video's frame decodes into a grid tile
  (`ui/search/SearchResults.kt` uses `rememberVideoFrameRequest`; only the remote-thumbnail
  fallback is obviously safe), and that closing the search image pager lands back in the
  grid rather than in the conversation.
- Also unconfirmed: the deferred `(chatId, timestamp)` index. Trigger to revisit is the
  Photos chip feeling sluggish on a real long chat — the browse `LIMIT` is 200
  (`MessageSearchLimits`), and raising it further means doing the index too.
- The "Shared Media" three-dot item now opens search pre-filtered to Photos; the standalone
  screen is deleted, so a regression here has no fallback path.
- Confirm on device that **system back closes the search overlay** rather than the chat
  (`261704d` added the `BackHandler`; the deleted screen used to get this from the NavHost),
  and that it still yields to the two fullscreen viewers while either is open.
- **Global search (`Routes.SEARCH`, from the chat list magnifier) is entirely unverified
  on hardware.** Specifically: that the chip row scrolls freely now that it is under a
  pager-free destination and not a page of `MainScreen`'s `HorizontalPager` — this is the
  reason it is its own destination, so it is the check that matters most; that the field
  is focused **with the keyboard already up** on entry (`LaunchedEffect` + `FocusRequester`
  only requests focus, and some OEM IMEs need more); and that tapping any global result —
  media tiles included — lands *at the message* in the right conversation rather than at
  the bottom of it.
- Global search has no `MessageSearchFilter` persistence across the destination's
  lifecycle, so process death mid-search drops the chips. Accepted (search is transient),
  recorded so it reads as a decision rather than an oversight.

### Timer alarm prominence — insistent ring (`5176172`…`cf98eb2`, 2026-07-25)
- Unconfirmed on hardware: that `FLAG_INSISTENT` actually loops, and that the 2-minute
  auto-silence cancel stops it.
- Unconfirmed: whether `USE_FULL_SCREEN_INTENT` is still granted on Android 14+ for this
  sideloaded app.
- **Test by upgrading over an existing install, never a clean one** — notification-channel
  sound/vibration is frozen at creation, so channel-freeze bugs are invisible on a fresh
  install.
- Not load-bearing by design: both audible styles escalate through the same re-post chain,
  so `FLAG_INSISTENT` is additive. Don't "simplify" that chain out for INSISTENT.

### Reaction cue follow-ups (`3309b41`, 2026-07-25)
- The cue itself (`dc1dd24`) is confirmed working on device. `3309b41` is not: list/poll
  bubble highlighting, the flash starting on scroll arrival rather than at tap, and the
  animated `scrollToAndCenter`.
- Note for any new bubble type added outside `MessageBubble`: wire up `isHighlighted` or it
  silently loses every jump highlight.

### Message reminders / snooze (`dd519e5`…`a3669f5`, 2026-07-19)
- Device pass never run: cold / warm / background notification taps, reboot restore, and
  `.remind` both with and without a reply target.

### Baseline profile generation (2026-04-24)
- The `:baselineprofile` module, generator, and testTags are in place; the actual on-device
  generation run has never happened. Blocked on a connected device plus a Firebase console
  test phone number.

### Signal database split — remove the debug-encryption guards (2026-04-26)
- The split moved Signal tables into `signal.db`, which was the reason the debug guards
  existed. Both are still in place **by design**, pending verification across a few schema
  iterations: `!BuildConfig.DEBUG` in `MessageRepositoryImpl.kt` and the
  `libsignal_jni.so` debug-variant exclusion in `app/build.gradle.kts`.
- Removing them is the follow-up; until then, debug builds still send plaintext.

---

## Privacy & Security

### Disappearing messages (3.1)
- Per-chat timer setting (off / 5s / 30s / 1min / 5min / 1h / 24h / 7d)
- Timer starts on read for 1-to-1, on send for groups
- Background WorkManager job to prune expired messages
- New field on `Chat`: `disappearingMessagesDuration`
- Files: `domain/model/Chat.kt`, `data/repository/MessageRepositoryImpl.kt`, new `data/worker/MessageExpiryWorker.kt`

### App lock / biometric authentication (3.2)
- Require fingerprint/face unlock to open the app
- Auto-lock timeout setting (immediately / 1min / 5min / 30min)
- Use the AndroidX Biometric library
- Files: new `ui/lock/AppLockScreen.kt`, `data/local/PreferencesDataStore.kt`

### Report users & messages (3.3)
- Blocking is already wired end-to-end; **only the report half is open.**
- Report users/messages into a Firestore admin collection — no report action exists anywhere in the codebase today.
- A dedicated blocked-users list screen in settings with an unblock option; currently only the per-profile block toggle exists.

### Safety number / key verification (3.4)
- Display a safety number for each contact (Signal-style numeric fingerprint)
- QR code generation and scanning for in-person verification
- Mark contacts as "verified" after a successful comparison
- Files: new `ui/verification/SafetyNumberScreen.kt`, `data/crypto/SignalManager.kt`

### Group E2E encryption — sender keys (3.5)
- Still scaffolding only: `SignalSenderKeyEntity` / `SignalProtocolStoreImpl` exist, but there is no `SenderKeyDistributionMessage` / `GroupCipher` usage anywhere. Group and broadcast messages are **not** encrypted, and the encryption toggle has no effect on them.
- Implement Sender Key Distribution Messages on group creation and member join
- Encrypt group messages using `SenderKeyMessage`
- Files: `data/crypto/SignalManager.kt`, `data/repository/MessageRepositoryImpl.kt`

### Screen security (3.6)
- Prevent screenshots in-app (`FLAG_SECURE`)
- Toggle in privacy settings
- Files: `MainActivity.kt`, `data/local/PreferencesDataStore.kt`

---

## Rich Media & Communication

### Video calls, 1-to-1 (4.2)
- Extend the existing voice-call infrastructure with a video track
- Camera switch (front/back), video toggle
- Picture-in-picture support
- Files: `ui/call/` package extension

### Group voice/video calls (4.3)
- SFU (Selective Forwarding Unit) server for multi-party calls
- Grid layout for participant video feeds
- Per-participant mute/video toggles
- Files: `ui/call/` package extension, backend SFU integration

### Stories / status updates (4.4)
- Post text/image/video stories visible for 24 hours
- Story viewer with progress bar and navigation
- Privacy controls (my contacts / selected contacts / everyone)
- Stories tab or section in the chat list
- Files: new `ui/stories/` package, new `domain/model/Story.kt`, `navigation/NavGraph.kt`

### Live location sharing (4.5)
- Static location send already ships (OpenStreetMap tiles, `MessageType.LOCATION`); **only live sharing is open.**
- Live location sharing with a configurable duration (15min / 1h / 8h)

### Stickers & GIFs (4.6)
- Built-in sticker packs with download/management
- GIF search via Giphy/Tenor API integration
- Sticker/GIF picker accessible from the composer
- Files: new `ui/chat/StickerPicker.kt`, new `data/remote/GiphySource.kt`

### Document sharing enhancements (4.7)
- In-app document viewer (PDF, images)
- File size display and download progress
- Cloud storage integration (Google Drive picker)
- Files: `ui/chat/ChatScreen.kt`, `ui/chat/MessageBubble.kt`

---

## Platform & Reliability

### Chat backup & restore (6.1)
- Encrypted backup to Google Drive
- Backup scheduling (daily / weekly / manual)
- Restore flow during new-device setup
- Include-media option, with a size warning
- Files: new `data/backup/` package, `ui/settings/`

### Multi-device support (6.2)
- Link secondary devices (tablet, web) via QR code
- Device-specific Signal Protocol sessions
- Message sync across linked devices
- Files: `data/crypto/SignalManager.kt`, new `ui/settings/LinkedDevicesScreen.kt`

### Offline resilience — durable outbox (6.3)
- Orphaned-send recovery already ships (stuck "sending" flips to `FAILED` on app start / chat re-entry, restoring tap-to-retry); **the durable outbox is open.**
- Auto-queue and resend on reconnect, a `MessageRetryWorker` with exponential backoff, and an offline indicator.
- Cross-referenced as the deferred "durable-outbox follow-up" in [`TECH_DEBT.md`](../TECH_DEBT.md) — check there for the recorded reasoning before starting.

### Performance & pagination (6.4)
- Paginated message loading (Paging 3)
- Lazy image loading with thumbnail placeholders
- Database query optimisation with proper indices
- Files: `data/local/dao/MessageDao.kt`, `ui/chat/ChatViewModel.kt`

### Notifications enhancement (6.5)
- Grouping by chat already ships (`FCMService` recovers the per-chat `MessagingStyle` and bundles with `setGroupConversation`, which also gives message preview for free). The rest is open:
- Inline reply from the notification (`RemoteInput`)
- Privacy option to hide message content in the notification
- Per-chat-category notification channels — today there is a single "Messages" channel plus the separate updates and timer channels

### Accessibility (6.6)
- Content descriptions on all interactive elements
- Screen reader support throughout
- Dynamic font sizing
- High contrast mode
- Files: all UI files across the `ui/` package

---

## Growth & Engagement

### Invite system (7.1)
- Deep link invitations ("Join me on FireStream")
- SMS invite for contacts not on the platform
- Referral tracking
- Files: new `ui/invite/` package

### Payment integration — optional (7.2)
- In-chat peer-to-peer payments
- Integration with payment APIs (Google Pay, UPI)
- Payment request messages
- Files: new `domain/model/Payment.kt`, new `data/remote/PaymentSource.kt`

### Channels (7.3)
- Public one-way broadcast channels (Telegram-style)
- Subscribe/unsubscribe model
- Admin posting with a comments section
- Files: new `domain/model/Channel.kt`, new `ui/channel/` package

---

## Ideas

Less-specified than the items above — kept because the thinking is worth not re-deriving.

### UX improvements
- **Swipe actions on chat list** — swipe right to pin, swipe left to archive/delete
- **Chat wallpapers** — per-chat or global custom backgrounds
- **Message scheduling** — compose a message and schedule it for later delivery
- **Quick-switch between chats** — edge swipe gesture to jump to the next unread chat
- **Compact/comfortable density toggle** — spacious vs. compact chat layouts
- **Animated transitions** — shared element transitions between chat list and chat detail
- **Haptic feedback** — subtle vibrations on message send, reactions, and gestures

### AI-powered features
- **Smart replies** — contextual quick responses based on incoming messages
- **Message summarization** — summarize long unread conversations (on-device or via API)
- **Auto-translation** — translate incoming messages inline, with language detection
- **Intelligent notification priority** — ML-based ranking (urgent vs. casual)
- **Voice-to-text transcription** — auto-transcribe voice messages with on-device ML

### Social & community features
- **Communities** — a group of groups with shared membership (WhatsApp-style)
- **Events** — create and RSVP to events within group chats
- **Shared media albums** — collaborative photo/video albums within a chat
- **Custom group roles** — roles beyond admin/member with configurable permissions

### Developer & power-user features
- **Bot framework** — API for building automated bots (weather, reminders, integrations)
- **Webhook support** — connect external services to send messages to chats
- **Custom themes** — user-created colour schemes, sharable as theme files
- **Keyboard shortcuts** — for tablet / desktop companion use
- **Export chat as PDF/HTML** — export full conversation history

### Infrastructure & technical
- **End-to-end encrypted backups** — user-derived key, not Google's, for backup encryption
- **Certificate pinning** — pin Firebase and API certificates to prevent MITM
- **Reproducible builds** — deterministic builds for security auditing
- **Crash reporting** — Firebase Crashlytics for production monitoring
- **Analytics** — privacy-respecting, anonymous, opt-in usage analytics
- **App size optimisation** — split APKs per ABI, asset optimisation, R8 fine-tuning
- **Modularization** — split into Gradle modules (`:core`, `:feature:chat`, `:feature:auth`, …) for build speed and team scalability
- **CI/CD remaining work** — the tag-driven release path ships (`release-apk.yml` builds signed APKs for both flavors, publishes manifests + APKs to GitHub Releases, and the in-app updater consumes them), and `ci.yml` gates `test assembleDebug` on push and PR. Still open: **`lint` in the CI gate**, and **Firebase App Distribution** for closed beta tracks.
- **Widget** — home-screen widget showing recent unread messages, or quick-compose

### Accessibility & inclusion
- **RTL language support** — full right-to-left layout mirroring
- **Colour-blind friendly palette** — alternate schemes for different types of colour blindness
- **Reduced motion mode** — disable animations for users sensitive to motion
- **Voice navigation** — TalkBack-optimised flow with a logical focus order
