# App review — 2026-10-08

Whole-app review across nine areas. The action plan that fixes these findings is
[`docs/plans/app-improvements.md`](../plans/app-improvements.md).

- Reviewed `main` at `a16f93e` (v1.40.2). Line numbers are from that commit. `9247d4b` later moved a few lines in `MessageBubble.kt` and `CallsScreen.kt`; re-verify every line number before editing.
- Eight read-only reviewers ran in parallel, one per area. Six ran on the strong tier: visual design, UX, architecture, performance, security, and CLAUDE.md with the development process. Two ran on the mid tier: accessibility with i18n, and build, CI, tests and docs.
- The lead session re-checked every Critical and High claim against the code. **✔** marks a finding the lead verified; the rest were verified by their reviewer. §10 lists the claims verification corrected.
- Severity: **Critical** exposes user data now; **High** is a user-visible defect, a data-loss or security risk, or something that blocks later work; **Medium** is real friction or maintenance cost; **Low** is polish.
- Effort: S ≤ half a day, M ≤ 2 days, L > 2 days. `→ Step N` names the plan step that fixes it; `→ F-n` names a follow-up plan.
- Nothing here re-proposes a TECH_DEBT "Declined" entry or a settled design decision (no dynamic color, no Gradle modules, no repository split, E2E stays opt-in).

## 1. Summary

FireStream has stronger guardrails than most apps of its size. Konsist enforces the architecture, about 2,245 tests run on every push, bug fixes start with a regression test, and the plan runner carries state in tracked files. The weak layers are the ones no test reaches: the Firestore rules, the release build, half-built flows, and the theme's unset roles.

| # | Finding | Severity | Effort | Fix |
|---|---|---|---|---|
| 1 | Unauthenticated clients can rewrite any user document, including the push token; pushes carry the text of unencrypted messages (SEC-1, SEC-5) | Critical | S | Steps 3, 6 |
| 2 | Rules check chat membership, not fields: a member can rewrite group roles and other people's messages, and post as another member (SEC-2, L-2) | High | M | Steps 3, 5 |
| 3 | Every signed-in user can read every phone number and push token (SEC-3) | High | M–L | Step 6, F-6 |
| 4 | The mandatory correctness review (`/code-review`) has been a style-and-spec review since an imported skill took its name (MD-1) | High | S | Step 1 |
| 5 | Every Room version bump wipes queued unsent messages, stars and local files; a Signal database bump would wipe identity keys. CLAUDE.md's rule points agents at this path (L-1) | High | M | Step 9 |
| 6 | The release APK is 107.5 MB; an unstripped `libsignal_jni.so` is 74 MB of it (PERF-1) | High | S | Step 12 |
| 7 | Six Settings toggles do nothing, two of them privacy controls (UX-1) | High | S | Step 14 |
| 8 | "Add member" opens a 1:1 chat; invite links can be created but never joined (UX-2, L-3) | High | M | Step 16 |
| 9 | "Delete for everyone" fires on one tap with no confirmation (UX-3) | High | S | Step 15 |
| 10 | Location sharing requests a permission Android 12+ ignores (UX-4) | High | S | Step 17 |
| 11 | `resultOf` swallows coroutine cancellation at 84 repository call sites (ARCH-1) | High | S–M | Step 11 |
| 12 | Each keystroke writes to Firestore and fans out to every member's phone; each cold start re-downloads every message (PERF-2, PERF-3) | High | S–M | Step 23 |
| 13 | The theme leaves half of Material 3's type and color roles at library defaults: Roboto buttons, lavender dialogs (VIS-1, VIS-2) | High | S | Step 27 |
| 14 | Light-theme contrast is 2.3–3.1:1 on brand-orange text, buttons and the read tick (A11Y-1) | High | S | Step 27 |
| 15 | 37 shipped features (about 207 checks) have never been checked on a device; the oldest is from April (PROC-3) | High | M | Step 38 |
| 16 | Voice notes can be played but not recorded (UX-6) | High | S / L | Step 14, F-1 |
| 17 | About 850 user-facing strings are hard-coded; dates mix forced 12- and 24-hour clocks (I18N-1, I18N-3) | High | L | Steps 13, 32, F-4 |

**Strengths to keep.** Architecture rules as Konsist tests. The send path refuses on doubt and never downgrades to plaintext. `allowBackup=false`, immutable PendingIntents, narrow FileProvider paths and a hardened sticker-archive parser. The offline outbox with "Waiting for network…". Jump-to-message with a flash. Undo on list-item swipes. AGENT-NOTE headers, GOTCHAS discipline and an accurate FEATURE-MAP (all 409 paths exist).

## 2. Security and privacy

Rule findings are from `firestore.rules` as committed; the deployed rules were not inspected.

- **SEC-1** ✔ Critical · S — Unauthenticated clients can update any `users/{uid}` document. `firestore.rules:23-25` allows `update` when `request.auth == null`. The comment says `syncPresenceToFirestore` needs this, but that function writes through the Admin SDK (`functions/index.js:28`), which bypasses rules. Anyone holding the app's public config can replace a user's `fcmToken`, profile fields or presence. Fix: delete the clause. → Step 3
- **SEC-5** ✔ Medium alone, Critical with SEC-1 — The message push puts `messageContent` in its data payload (`functions/index.js:286`). Encrypted messages send an empty body, but E2E is off by default, so most pushes carry the message text. Combined with SEC-1, a replaced token receives that text. Fix: send ids and type only; the client already fetches the message (`reconcileFromPush`, `FCMService.kt:81-85`) and should build the notification text from it. → Step 6
- **SEC-2** ✔ High · M — Chat and message updates check membership only (`firestore.rules:44-45`, `54-55`). A group member can make themselves admin or owner, change members and permissions, and edit or tombstone other people's messages. `GroupPermissions` is enforced only in the client. Fix: `diff().affectedKeys()` allowlists per role, with `senderId` immutable and content edits author-only. → Steps 3, 4, 5
- **L-2** ✔ High · S — Message `create` (`firestore.rules:52-53`) has no `senderId == request.auth.uid` check, so a member can post a message attributed to another member. Audit every client write path before constraining it (call and list-event messages may set a sender). → Step 3
- **SEC-3** ✔ High · M–L — `users/{uid}` is readable by any signed-in user (`firestore.rules:21`), and contact sync downloads the whole collection (`FirestoreContactSource.kt:19-20`, `ContactRepositoryImpl.syncContacts`). One account can harvest every phone number and push token. Fix: move `fcmToken` to an Admin-only collection now (Step 6); replace the readable directory with hashed-number discovery through a Cloud Function (F-6).
- **SEC-11** ✔ Low–Medium · S — Call ICE candidates are readable and creatable by any signed-in user (`firestore.rules:70-77`); they carry IP addresses. Scope them to the call's two parties with a `get()` on the parent. Tracked: TECH_DEBT "Calls — known problems left unfixed". → Step 3
- **SEC-12** ✔ Medium · S — `inviteLinks` allows read, list, create and delete to any signed-in user (`firestore.rules:122-126`), so anyone can list every group's token or delete one. See L-3 for why joining fails anyway. → Step 3
- **SEC-4** Medium · S — Sticker-pack manifests can be pre-claimed by id. Tracked: TECH_DEBT "A sticker pack's id can be claimed by whoever writes its manifest first". → Step 5
- **SEC-6** Medium · L — A changed identity key is re-trusted silently (`SignalManager.kt:99-105`), and there are no safety numbers. Tracked: BACKLOG 3.4. → F-8
- **SEC-7** ✔ Medium · S — The E2E toggle says "Encrypt 1:1 messages … (recommended)" (`SettingsScreen.kt:305`). Even when it is on, edits travel in plaintext (`MessageRepositoryImpl.editMessage`), media, voice notes, files, stickers and locations are never encrypted, and groups never are. `docs/SPEC.md:9` claims encryption is on by default. Fix the copy and the SPEC; the opt-in default stays. → Steps 2, 7
- **SEC-8** Medium · L — Signal keys and sessions are stored unencrypted in Room; `allowBackup=false` covers the main exfiltration path. → F-8
- **SEC-9** ✔ Medium · S–M — The updater checks the APK's SHA-256 against the same manifest that names the APK (`UpdateManifestSource.kt:56-57`, `ApkDownloader.kt:93-131`). It never compares the APK's signing certificate with a pinned one before installing. → Step 8
- **SEC-10** Medium · S (owner) — Storage and Realtime Database rules are not in the repo (`firebase.json` declares only Firestore). Media, avatars and sticker objects live in Storage, so their exposure cannot be reviewed. → owner action before Step 4
- **SEC-13** Low · S — Release builds keep all logs (no `-assumenosideeffects` in `proguard-rules.pro`). `WebPagePreviewCapture` logs full visited URLs, and `ShareContentResolver` logs shared URIs. No message bodies, keys or tokens are logged. → Step 7
- **SEC-14** Low–Medium · S — Every received link is fetched automatically (`ChatMessageLoader.fetchLinkPreviewsFor`), which tells a sender-controlled server the recipient's IP. The fallback renders the page in a JavaScript-enabled WebView (`WebPagePreviewCapture.kt:307,310`). Fix: previews for received links behind a setting (D-3), file and content access set off explicitly, private-address blocklist. → Step 7
- **SEC-15** Low — Reviewed clean: exported `MainActivity` extras can only open a chat, never send or call. Optional: check the chat belongs to the user before navigating.
- **SEC-16** Medium · M — No account deletion; sign-out leaves the profile, keys, messages and media on the server. → F-6

## 3. UX flows

- **UX-1** ✔ High · S — Six Settings toggles are stored but nothing reads them: Last seen, Screen security, Message notifications, Group notifications, Sound and Vibration (`SettingsViewModel.kt:225-256`; their flows are read only by Settings). Nothing sets `FLAG_SECURE`. The profile shows "Last seen" whatever the toggle says (`ProfileScreen.kt:263-267`). Tracked as a feature in BACKLOG 3.6; the shipped no-op toggles are new. → Step 14
- **UX-2** ✔ High · M — "Add member" in Group Settings navigates to Contacts (`NavGraph.kt:621-624`), whose tap opens a 1:1 chat (`:519-525`). `ChatRepository.addGroupMember` has no UI caller. `docs/ARCHITECTURE.md:344` documents the broken flow as intended. → Step 16
- **UX-3** ✔ High · S/M — "Delete for everyone" deletes on one tap (`MessageBubble.kt:1562-1570` → `ChatMessageActions.deleteMessage`), with no confirmation, no undo and no "Delete for me". Incoming messages have no delete at all. → Step 15
- **UX-4** ✔ High · S — Location sharing launches a request for `ACCESS_FINE_LOCATION` alone (`ChatScreen.kt:2298-2301`). Android 12+ ignores a fine-location request without coarse, so on a clean install the sheet never opens. Verify on a device. → Step 17
- **UX-5** High · M — Phone sign-in drops the auto-verified credential and the resend token (`FirebasePhoneAuth.kt:36-48`). The OTP screen has no resend, countdown, "Wrong number?", SMS autofill or auto-submit. Firebase errors are shown raw. → Step 19
- **UX-6** ✔ High · S/L — Voice notes can be played but not recorded: `sendVoiceMessage` has no UI caller and nothing records audio. The mic starts dictation, which defaults to German (`PreferencesDataStore.kt:246-249`). `docs/SPEC.md:8` claims voice messages can be sent. → Step 14 (interim honesty), F-1 (recording)
- **UX-7** High · S — Archived chats are reachable only from Settings (`SettingsScreen.kt:244-249`). Archiving has no undo, the archived screen says "Swipe left" where no swipe exists (`ArchivedChatsScreen.kt:74`), and the chat list is blank when every chat is archived. → Step 15
- **UX-8** ✔ Medium · S — Message and update notifications use `NEW_TASK | CLEAR_TASK` (`FCMService.kt:253`, `UpdateCheckWorker.kt:72`). Each tap rebuilds the activity, discarding the back stack and unsent media, and bypasses `onNewIntent`. TECH_DEBT "`MainActivity` — no `onNewIntent`" is stale: the method exists, but message taps never reach it. → Step 18
- **UX-9** Medium · M — A draft lives in `remember` state in `ChatScreen` and is lost when the chat closes. Starting an edit overwrites it. The chat list shows no "Draft:". → F-2
- **UX-10** Medium · M — No "N unread messages" divider, and the scroll-to-bottom button shows no count. → F-2
- **UX-11** Medium · S–M — Poll bubbles have no long-press menu, and a list bubble's long-press opens the reaction picker. → Step 20
- **UX-12** Medium · M–L — The long-press menu is a column of up to 12 full-width buttons (`MessageBubble.kt:1435-1576`), with no quick reactions and no multi-select. → F-2
- **UX-13** Medium · S — Reaction chips do nothing when tapped (`MessageBubble.kt:544-548`), and there is no way to see who reacted. → Step 20
- **UX-14** Medium · M — Permission requests have no rationale and no consistent recovery. Notification permission is asked before login. A first mic denial jumps to system settings. Gallery asks for `READ_MEDIA_*` before a Photo Picker that needs none. `READ_CONTACTS` is declared and unused. → Step 17
- **UX-15** Medium · S — Nothing in a chat shows whether it is encrypted. → Step 7
- **UX-16** Medium · S — Sign-in fields use `remember` and are lost on rotation (`LoginScreen.kt:63-64`, `OtpScreen.kt:50`, `ProfileSetupScreen.kt:51`). Message Info renders blank after process death (ARCH-3). → Steps 18, 19
- **UX-17** Medium · S — The chat-list overflow uses the megaphone icon (`ChatListScreen.kt:150-152`). Back on the Calls or Lists tab exits the app (no `BackHandler` in `MainScreen`). Settings and search exist only on the Chats tab. → Step 21
- **UX-18** Medium · S–M — The chat ⋮ menu holds only Shared Media and Shared Lists (`ChatScreen.kt:1135-1163`). Mute is reachable only from the chat list, where "Unmute" opens a dialog titled "Mute notifications". → Step 21
- **UX-19** Medium · S/L — In landscape the emoji panel's height, derived from width (`ChatScreen.kt:318-335`), exceeds the screen. No window size classes. → Step 20 (clamp), F-11 (adaptive layouts)
- **UX-20** ✔ Medium · S — Group typing shows bare dots; `TypingRow` (avatars) is never used. → Step 20
- **UX-21** Medium · S — Back on the send preview discards edits and caption without asking (`ImagePreviewScreen.kt:313-319`). → Step 20
- **UX-22** Low–Medium · S–M — Settings is one scroll of about 30 rows. Storage Used, Terms, Privacy and Support do nothing (`SettingsScreen.kt:389-394`, `:532-551`). "Import stickers" sits under Storage. → Steps 14, 31
- **UX-23** Low · S — The pinned banner shows a lock icon and has a 24 dp unpin button (`ChatScreen.kt:1201-1219`). Revoking an invite link has no confirmation. → Steps 15, 20
- **UX-24** Low · S — Left-swipe means react on a message, delete-with-undo on a list item and delete-without-undo on a reminder (`ScheduledRemindersScreen.kt:124-131`). → Step 15

Screens with no error channel at all: Settings, Profile (after the first load), Archived, Reminders, Starred, Shared lists. The five `Toast` sites are justified (API 31–32 clipboard, the finishing `CallActivity`); everything else should use one snackbar event flow (ARCH-11). → Steps 14, 15, 31

## 4. Visual design

- **VIS-1** ✔ High · S — `Type.kt` defines 8 of 15 styles. `labelLarge` (every button label), `labelMedium`, `titleSmall`, `headlineSmall` and the `display*` styles fall back to Material's default sans, so buttons, chips, menu items and all 28 dialog titles render in Roboto. Add a test that every style uses Plus Jakarta Sans. → Step 27
- **VIS-2** ✔ High · S — `Theme.kt` sets no `surfaceContainer*`, `tertiary*`, `outline*` or `inverse*` roles. All 28 dialogs, 15 sheets and 8 menus sit on Material's purple-tinted baseline surfaces. The library-default pink `tertiary` draws the jump-to-message frame (`MessageBubble.kt:240`). Light `secondaryContainer` is dark charcoal, so tonal menu buttons render as heavy grey blocks. Derive every role from existing tokens. → Step 27
- **VIS-3** ✔ High · S — System bars follow the system dark mode, not the in-app theme. `Theme.kt` writes the deprecated `window.statusBarColor`, which does nothing on Android 15. Navigation-bar icon color is never set. `values/themes.xml` has no night variant, so the window behind the app is light in dark mode. → Step 28
- **VIS-4** High · S — The selected bottom-nav icon is orange on a salmon pill, 1.43:1 (`BottomNavBar.kt:100,142-144`). → Step 27
- **VIS-7** High · S–M — Group members never show their photos: `MemberRow` ignores the loaded `avatarUrl` (`GroupSettingsScreen.kt:651-664`). Six avatar placeholders exist; `UserAvatar` should gain an initials variant and replace them. → Step 30
- **VIS-5** Medium · S — The Lists tab differs from its pager siblings: top-bar color, FAB (translucent in dark mode), missing `contentWindowInsets = WindowInsets(0)` (`ListsScreen.kt:131-133`; likely doubled bottom inset), a skeleton that doesn't match its rows. → Step 30
- **VIS-6** Medium · S — Poll and list bubbles use asymmetric corner rounding instead of `BubbleTailShape` (`ListBubble.kt:66-71`, `PollBubble.kt:54-59`), are 300 dp wide against 280 dp elsewhere, and copy the bubble-color choice four times. → Step 30
- **VIS-8** Medium · S — Touch targets of 16–28 dp: the failed-send retry icon (`MessageBubble.kt:473-477`), picker search clear (`PickerPanel.kt:313-328`), send-preview remove (`ImagePreviewScreen.kt:930-935`), edit-photo badges. → Step 30
- **VIS-9** Medium · S — Four greens (two different "online" greens) and four reds are hard-coded outside the theme. About 85 of 91 `Color.White/Black` uses are legitimate media chrome. → Step 29
- **VIS-10** Medium · S — 18 copies of the top-bar color block; `ListDetailScreen` changes color on scroll. → Step 29
- **VIS-11** Medium · S–M — Three section-header styles, two duplicated editable-field composables, list rows on `surface` against `background`. → Step 29
- **VIS-12** Medium · S–M — Five copies of the single-choice dialog (current option marked by text color only), about ten copies of the destructive-confirm dialog, mixed Title Case and sentence case. → Step 29
- **VIS-13** Medium · S — Three patterns for "choose an action"; the tonal-pill menu is copied 28 times. → Step 29
- **VIS-14** Medium · S — Skeletons are nearly invisible (about 1.05:1) and don't match their rows; six empty-state layouts. → Step 30
- **VIS-20** Medium · M — No component catalogue, and the Roborazzi suite gates nothing. Tracked: TECH_DEBT "The Roborazzi baselines are stale, and no gate notices"; its revisit trigger (a typography change) arrives with VIS-1. → Step 26
- **VIS-15–19, VIS-21** Low — 69 literal corner radii against 5 theme references; spacing already follows a 4-point scale (a `Spacing` object would be churn); the 15.5 sp row title in three weights; chat colors that branch on `LocalIsDarkTheme` belong in a small `ChatColors`; six dead color tokens; duplicated upload overlays; four search-field implementations; three banner treatments in ChatScreen. → Steps 29, 30

The two `isSystemInDarkTheme()` calls outside the theme (`MainActivity.kt:109`, `CallActivity.kt:86`) are legitimate: they resolve the "System" option.

## 5. Accessibility and internationalization

- **I18N-1** High · L — About 1,110 user-facing literals (about 850 distinct strings) live in Kotlin: `ui/chat` 417, `ui/settings` 152, `data/` 116, `ui/stickers` 72, `ui/lists` 63, `ui/group` 57. `strings.xml` holds 46 strings, 12 unused; there are 29 `stringResource` calls and no plurals. Only `ui/auth` is migrated. → Step 13 (guardrails), F-4 (extraction)
- **I18N-3** ✔ High · M — 23 `SimpleDateFormat` calls use fixed patterns. Five force 12-hour (`SnoozeOptions.kt:54`, `ScheduledRemindersScreen.kt:177`, `ProfileScreen.kt:264`, `CallsScreen.kt:443`, `StarredMessagesScreen.kt:148`), while chat bubbles and the chat list force 24-hour (`ChatUtils.kt:91`, `ChatListItem.kt:244`). The app disagrees with itself, and German users see "2:30 PM". → Step 32
- **I18N-4** Medium · S — No plurals: "1 items" (`ProfileScreen.kt:384`, `SharePickerScreen.kt:347`), "(s)" (`ChatPickerPanel.kt:100`, `CreateGroupScreen.kt:76`, `CreateBroadcastScreen.kt:76`). → Step 13
- **I18N-5** Medium — Sentences built from fragments, worst in notifications (`FCMService.kt:165-176`). → Step 6, F-4
- **I18N-6** Low — The message-type label table exists three times (`MessageTypeLabel.kt`, `ChatMessageActions.kt:200-214`, `FCMService.kt:233-242`). → Step 6
- **I18N-7** Medium — English is persisted to Firestore: list-event message content (`MessageRepositoryImpl.kt:155-158`), the default status (`User.kt:9`), server fallback names in `functions/index.js`. → F-4
- **I18N-8** Low — Emoji search keywords are English only (`EmojiSearchData.kt`). → F-4
- **A11Y-1** ✔ High · S — Light-theme contrast fails WCAG: white on FireOrange 3.06:1, FireOrange text on the background 2.93:1, the read tick on a sent bubble 2.26:1. Inside the brand: `FireOrangeDark` gives 4.59:1 under white (D-8); bubble metadata at alpha 0.8 gives about 5.5:1. Dark theme mostly passes. → Step 27
- **A11Y-2** ✔ Medium · S — Delivered and Read use the same `DoneAll` icon and differ only by tint (`MessageBubble.kt:483-498`). → Step 27
- **A11Y-3** Medium · S — Settings toggle rows are focused twice by TalkBack and the switch has no name (`SettingsScreen.kt:914-930`); unlabeled switches in `CreatePollSheet.kt:124,139` and `GroupSettingsScreen.kt:373`. → Step 29
- **A11Y-4** Medium · S — No `heading()` semantics anywhere, including Settings section headers (`SettingsScreen.kt:884`). → Step 29
- **A11Y-5** Medium · M — Bubbles advertise a tap that does nothing; the long-press has no label (33 long-press sites, no `onLongClickLabel`); swipe-to-reply and swipe-to-react have no custom actions. Reply and React stay reachable through the long-press menu. → Step 33
- **A11Y-6** Medium · S — The typing indicator has no text or semantics (`ChatScreen.kt:1674`); in the chat list it replaces the last-message preview (`ChatListItem.kt:161-166`). No live regions anywhere. → Step 33
- **A11Y-7** Medium · S — Small touch targets (see VIS-8), plus `IconButton`s shrunk to 24–36 dp. → Step 30
- **A11Y-8** Low · S — Three meaningful images without labels: `SearchResults.kt:416`, `ImagePreviewScreen.kt:912/925`, `CallsScreen.kt:230`. The other 89 `contentDescription = null` are decorative. → Step 33
- **A11Y-9** Medium · S — Links in bubbles are fixed at 12 sp and ignore the chat font size (`MessageBubble.kt:1372`). The 20 dp unread badge and the 64 dp font-preview box clip at large font scales. `ChatTextTheme` honours the system font scale. → Steps 30, 33
- **A11Y-10** Low · S — Lottie stickers and GIFs loop forever with no reduce-motion handling. → Step 33
- **A11Y-11** Low · S — In RTL the swipe drag goes the wrong way (`MessageBubble.kt:302`) and `BubbleTailShape` ignores layout direction. → Step 33
- **A11Y-12** Medium · S — No accessibility assertions in tests; the 316 `onNodeWithContentDescription` lookups cover labels only by accident. → Step 13

## 6. Architecture

Status of the 2026-09-20 review's candidates (`docs/reviews/architecture-review-2026-09-20.html` after Step 2):

| # | Candidate | Status at `a16f93e` | Priority |
|---|---|---|---|
| 1 | Send addressing | Shipped, fenced by three Konsist rules | closed |
| 2 | Partner and title on `Chat` | Open. `Chat.kt` has no methods; 12 partner copies in `ui/`, 2 in `data/`, 4 title copies; the blank-name row bug is live | up → Step 22 |
| 3 | Zoomable pager | Open, untouched | same → F-3 |
| 4 | Composer text module | Open; ChatScreen writes composer state from about 12 places (was 5) | up → F-2 |
| 5 | Image editor chain | Open, untouched | same → F-3 |
| 6 | Send managers | Open; waits on the slice-ownership decision | up → F-3 |
| 7 | Reconcile and `dagger.Lazy` | Open, 5 Lazy | same → F-3 |
| 8 | Outbox front door | Open; size limits now checked before and after the insert | same → F-3 |
| 9 | Media download | Open; 4 loops, the auto-download setting read in 4 places that disagree about stickers | up → F-3 |
| 10 | ChatScreen cut | Open; 2,668 → 2,885 lines | up → F-2 |
| 11 | Per-key lock module | Open; half its trigger fired | slightly up → F-3 |
| 12 | `MessageColumns` shim | Open; its trigger (a new messages column) fired with the sticker columns | up → F-3 |

The "Ruled out" verdict on `CallViewModel` no longer holds: it is now 125 lines of call-setup protocol (ARCH-7).

- **ARCH-1** ✔ High · S–M — `resultOf` (`data/util/ResultExt.kt:17-22`) catches `CancellationException` by design, at 84 call sites in 5 repositories. 84 of 120 production `catch (Exception)` blocks neither rethrow cancellation nor call `rethrowIfCancellation`. `MediaBackfillWorker.kt:75` keeps looping after the worker is cancelled. GOTCHAS records this only as a caller workaround. Fix the helper once, sweep the hand-rolled copies, fence with Konsist. → Step 11
- **ARCH-2** ✔ High · M — "Who this chat is with" is a route, intent and stored argument with about 17 producers under 5 names, trusted for block state, presence, call targets and snooze targets. A group timer notification omits the extra (`TimerAlarmReceiver.kt:257`) and `MainActivity.deepLinkFromIntent` (`:146-150`) requires it, so tapping it opens the app without the chat. → Step 18 (timer tap), F-3 (derive from the chat row after #2)
- **ARCH-3** Medium · M — String routes carry real defects: Message Info gets its `Message` through a non-saveable `remember` (`NavGraph.kt:197-202`, `:536-544`) and renders blank after process death; the OTP path arguments are unencoded (`:151-152`). Navigation 2.8.5 supports typed routes. → Step 18 (Message Info), F-3 (typed routes)
- **ARCH-4** Medium · S — `getChats()` and `getContacts()` start a sync pipeline per collector; the main pager (`beyondViewportPageCount = 1`) runs two or three from the first frame. Tracked: TECH_DEBT "A read-only screen pays for `getChats()`'s full sync pipeline"; priority up. → Step 23
- **ARCH-5** Medium · M — Stickers cross the message seam at two layers: `MessageRepositoryImpl` injects `StickerDao` and `StickerFiles` (23 dependencies), two managers orchestrate across repositories, and the backup restore runs only while some screen collects `observePacks()`. → F-3
- **ARCH-6** Medium · S–M — Mime and extension rules live in 8 places because `ensureLocalFile` returns a bare path. Return a `LocalFile(path, mimeType, displayName)`. Tracked: TECH_DEBT "Mime-type normalisation lives at one producer"; priority up. → F-3
- **ARCH-7** Medium · M — Placing a call spans Activity, ViewModel, state holder, service and repository, with no `Placing` state; the outgoing-call intent is built 3 times. Pairs with TECH_DEBT's `CallSession` entry. → F-3
- **ARCH-8** Medium · S — The Konsist manager fence covers 5 of 10 slice writers and never checks slice ownership; `ChatInfoManager` writes 3 slices. → F-3
- **ARCH-9** Medium · S — Four TECH_DEBT triggers fired unnoticed (SingleFlight, the `CallNotificationManager` baseline, `MessageColumns`, the ChatScreen split), and three entries are stale ("no onNewIntent", "timer dual schedule path", "shared-media omits VIDEO"). → Steps 2, 38
- **ARCH-10** ✔ Low · S — Dead code: `TypingRow.kt` (reused by Step 20), `CommandChip.kt`, `MediaAttachment.kt`, and 11 repository members with no caller, 5 of them on `ChatRepository`. `joinGroupViaLink` has no caller, so SPEC's invite-link joining has no entry point. → Steps 16, 35
- **ARCH-11** Low · S — One-shot events use three mechanisms; the snackbar flow (`ChatViewModel.kt:184`, no buffer) drops "saved to Downloads" when nothing collects. Three `String?` errors remain (`ProfileViewModel.kt:30,32`, `GroupSettingsViewModel.kt:34`). 17 Settings writes run on `viewModelScope` (`SettingsViewModel.kt:214-286`), against PATTERNS "DataStore writes need @ApplicationScope". → Steps 14, 31
- **ARCH-12** Medium · S/L — PocketBase: 54 `NotImplementedError` stubs; CI runs the 230 shared test classes twice; its capability flag leaked into 3 `main/` sites. Options: keep, freeze out of the default build, or remove (D-11). → Step 10

## 7. Performance and reliability

- **PERF-1** ✔ High · S — The published v1.40.2 APK is 107,478,128 bytes. `lib/arm64-v8a/libsignal_jni.so` is 74.0 MB, stored uncompressed, "with debug_info, not stripped"; stripped it is 6.7 MB, so the APK would be about 40 MB. AGP cannot strip because no NDK is configured (`app/build.gradle.kts` has no `ndkVersion`; `release-apk.yml` installs none). `NetworkModule.kt:34-40` sizes updater timeouts for "the 96 MB APK". → Step 12
- **PERF-2** ✔ High · S — `ChatMessageSender.onTyping` writes `setTyping(true)` on every non-blank keystroke (`ChatMessageSender.kt:30-33`); only the stop is debounced. The chat document matches every member's chat-list query, so each keystroke re-runs two or three `getChats()` pipelines on every member's phone: main-thread mapping, a Room transaction over every chat row, re-downloaded group avatars. The chat list cannot even show those typing dots (`ChatEntity` has no typing column). → Step 23
- **PERF-3** ✔ High · S/M — Each cold start and pull-to-refresh calls `syncAllChatMessages` over every chat (`ChatListViewModel.kt:102`, `:240`), an unbounded `get()` per chat (`FirestoreMessageSource.kt:167`) plus a Room lookup per message. 50 chats × 2,000 messages is about 100,000 billed reads per launch. → Step 23 (guard), F-9 (incremental sync)
- **PERF-4** ✔ High · S — 11 of 12 snapshot listeners run their callback on the main thread; only `FirestoreStickerPackSource` passes an executor. The message listener maps the whole chat per snapshot, twice per own write (`MetadataChanges.INCLUDE`). → Step 24
- **PERF-5** Medium · S — Strong skipping is on but `Message` holds maps and lists and every Room emission builds new instances, so every visible bubble recomposes on each receipt. No `contentType` anywhere. Fix: stability config, reuse unchanged `Message` objects, `contentType`. → Step 25
- **PERF-6** Medium · S/M — Listeners stay attached in the background because ViewModels collect in `init`; switching to `collectAsStateWithLifecycle` alone would not detach them. Quick win: `FirebaseDatabase.goOffline()` on stop. → Step 24, F-9
- **PERF-7** ✔ Medium · S — The `messages` table has no index; the open chat's query and the call log are full scans re-run on every write. Tracked: BACKLOG 6.4. Adding one needs a real migration (L-1). → Step 9
- **PERF-8** Medium · M — The baseline profile is packaged but stale: 12 rules for a deleted `CryptoModule`, nothing for `ChatScreen` or `MessageBubble`. BACKLOG says it was never generated. → F-9
- **PERF-9** Medium · M — Read and delivery receipts cost one Firestore write per message; opening a chat with 200 unread messages is about 400 writes. → F-9
- **PERF-10** Medium · M — `Application.onCreate` builds Firebase, Room and OkHttp and starts WorkManager on the main thread. Measure before changing. → F-9
- **PERF-11** Medium · S — The hand-written single-flight in `MediaFileManager` and `ProfileImageManager` passes cancellation to waiters; auto-downloads run unbounded. Tracked: TECH_DEBT "`SingleFlight` exists but … still hand-roll it" (trigger fired). → Step 11
- **PERF-12** = ARCH-1. → Step 11
- **PERF-13–17** Low — OkHttp's 50 MB HTTP cache duplicates media already cached elsewhere; 4 MB of unused post-quantum BouncyCastle resources, an unused `firebase-functions` dependency and a broad `-keep com.google.firebase.**`; every presence flip recomposes all chat rows; a new `SimpleDateFormat` per row; typing dots recompose every frame. → Steps 12, 25, 32

## 8. Build, CI and tests

- **CI-1** ✔ High · S — CI never builds the release variant, so nothing checks that `libsignal_jni.so` is packaged (the build file records a packaging leak that once broke release encryption, `app/build.gradle.kts:165-168`) or stripped (PERF-1). `checkReleaseBuilds = false` also skips release lint. → Step 12
- **CI-2** Medium · S — Test reports are not uploaded on failure. `functions/**` is excluded from CI, so `functions/` and `firestore.rules` are never checked. → Steps 4, 34
- **CI-3** Medium · S — Write-scoped third-party actions are pinned by major tag only (`softprops/action-gh-release@v2`, `claude-code-action@v1`); no Dependabot or Renovate. → Step 34
- **BLD-1** Medium · S — `app/build.gradle.kts:19-29` uses `Project.exec`, which Gradle 9 removes and which blocks the configuration cache. Use `providers.exec`. → Step 34
- **BLD-2** Medium · L — Old toolchain: AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.0 on the deprecated KSP1 line, Room 2.6.1, Coil 2.7.0, Hilt 2.53.1, WorkManager 2.9.1, Navigation 2.8.5, MockK 1.13.13, Robolectric 4.14.1. Cloud Functions declare `nodejs20`, end-of-life upstream since April 2026. → Step 6 (Node), F-5
- **BLD-3** Medium · S after F-5 — Lint has `abortOnError = false`, no baseline and no Compose rules; there is no detekt or ktlint. Lint cannot gate yet: it crashes on this AGP/AndroidX combination (`docs/GOTCHAS.md`, "`./gradlew lint` crashes"). → F-5
- **BLD-4** Low · S — C1-only JVM flags tuned for one local machine apply to unit-test workers everywhere (`app/build.gradle.kts:241-243`), CI included. → Step 34
- **BLD-5** Low · S — `firebase-functions` is declared but never imported; an unused `compose-ui-test` version; espresso and androidTest dependencies for a source set that doesn't exist. → Steps 12, 34
- **TEST-1** High · M — `firestore.rules` has no emulator tests; the 4 Cloud Functions have no tests. → Step 4
- **TEST-2** Medium — No tests for `AuthViewModel`, `ProfileViewModel`, `ContactsViewModel`, `CreateGroupViewModel`, `StarredMessagesViewModel`, `ScheduledRemindersViewModel`, `FirestoreListSource` or `FirebaseKeySource`; 5 of 30 screens are rendered by a Compose test. Steps that touch these add tests as they go.
- **TEST-3** = VIS-20. → Step 26
- **TEST-4** Low · S — `ImagePreviewScreenZoomTest` and `SignalManagerTest:246` depend on wall-clock timing. → Step 34

## 9. Docs, CLAUDE.md and the development process

- **DOC-1** ✔ High · S — `functions/node_modules` is tracked: 6,849 of 7,806 tracked files, 83 MB, despite `.gitignore`. It arrived with a feature commit on 2026-09-27. 11 `.kotlin/errors` logs are tracked too. → Step 2
- **DOC-2** Medium · S — Stale entries mislead agents: TECH_DEBT "testReleaseUnitTest" (the task is disabled), TECH_DEBT sync-path coverage (the tripwire test exists), BACKLOG "both flavors" release builds, BACKLOG baseline profile "never generated", two pointers to local-only memory files (`app/build.gradle.kts:301`, `block-heredoc-commit.sh`). → Step 2
- **DOC-3** Medium · S — `ARCHITECTURE.md`'s routes table misses 4 routes and its package tree 5 packages; `SCHEMA-ROOM.md` names `signal_identities` where the code has `signal_identity`. → Step 2
- **DOC-4** Low · S — Six fully shipped plans sit in `docs/plans/` (call-audio-routes, file-handling, image-editor, offline-outbox, send-addressing, send-addressing-brief), the 2026-09-20 review HTML belongs in `docs/reviews/`, `handoff-benchmark-readout.md` is finished. → Step 2
- **DOC-5** Medium · M — Long docs cost every agent: CHANGELOG 124 KB (bundled into the APK as an asset), FEATURE-MAP 106 KB, TECH_DEBT 84 KB, BACKLOG 78 KB (77% of it the verification queue). → Steps 37, 38
- **DOC-6** Low · S — `README.md` is one heading, for a public repository. → Step 2
- **MD-1** ✔ High · S — CLAUDE.md makes `/code-review` the mandatory correctness review for Signal/crypto, coroutine-scoping and sync-path diffs. `.claude/skills/code-review` is a symlink to an imported skill that reviews "Standards" and "Spec", looks for no bugs, expects a missing `docs/agents/issue-tracker.md`, and asks for a base commit a headless step cannot give. Every mandatory correctness review since the import has been a smell-and-spec review. → Step 1
- **MD-2** ✔ High · S — CLAUDE.md's commands are wrong or contradict each other: `assembleDebug` "defaults to firebase" (it builds both flavors), the example test class `ArchiveChatUseCaseTest` doesn't exist, `./gradlew lint` is listed though it crashes, and the post-step gate differs from the cloud block's firebase-only advice. → Step 1
- **MD-3** Medium · S — Change Safety says "this repo pushes directly to main"; since September about 45% of commits arrived through merges of `ccr-*`, `claude/*` and `plan/*` branches. Nothing says when to open a PR. → Step 1
- **MD-4** Medium · S — Post-step item 6 says to update MEMORY.md; 72% of recent commits come from cloud sessions, which have no memory store. → Step 1
- **MD-5** Medium · M — About 10.6 KB of CLAUDE.md's 28 KB serves only some sessions (plan-runner options, the cloud setup block, changelog rules the skill repeats, the full Key Conventions text). → Step 36
- **MD-6** Low · S — CLAUDE.md breaks its own writing rules: dates inside rules, 81 em dashes chaining clauses. → Step 36
- **MD-7** High · S — No rule for bugs that only reproduce on a device. Two avatar releases (v1.39.1, v1.40.1) shipped guessed fixes that did not help (`docs/plans/handoff-android17-avatars.md`); their CHANGELOG entries still claim the fix. → Step 1
- **MD-8** Medium · S–M — The single pointer to GOTCHAS (61 entries, 43 KB) is too generic to route a Service, notification or WebRTC change to the traps the call sweep recorded. → Step 36
- **MD-9** Low · S — The repo's handoff convention (`docs/plans/handoff-*.md`) is unwritten, and the imported `handoff` skill writes to the OS temp directory instead. → Step 36
- **MD-10** Low · S — `block-heredoc-commit.sh` points to a memory file that exists only on the owner's machine and suggests a trailer with an outdated model name. → Step 1
- **DOC-7** ✔ Low · S — CLAUDE.md says the pre-commit `ask-simplify.sh` hook offers `/simplify`; `.claude/settings.json` has never registered it. → Step 1
- **L-1** ✔ High · M — `DatabaseModule.kt` builds `AppDatabase` (version 31) with `fallbackToDestructiveMigration()` and one historic migration, and `SignalDatabase` with the fallback and none. Both set `exportSchema = false`. A version bump therefore wipes every queued SENDING outbox row, local-only stars and local file paths, and a Signal schema bump would wipe identity keys and sessions. CLAUDE.md's "Room version bump rule" tells agents to rely on exactly this. → Step 9
- **PROC-1** High (cost) · S–M — CHANGELOG entries must end with their commit hash, which forces a second commit: 68 of the last 300 commits touch only `CHANGELOG.md`, 36 of them only to fill in a hash. 87 commits (29%) are mechanical bookkeeping in total. The release script can stamp hashes from `git log -S`. → Step 37
- **PROC-2** Medium · S — The per-commit `[UNRELEASED] [X.Y.Z]` header has needed 8 repair commits and its own CI guard, which checks nothing in a tagless cloud checkout. Release cadence is about 3.4 tags a week. Let `cut-release.sh` compute the bump from commit prefixes. → Step 37
- **PROC-3** High · M — `docs/BACKLOG.md` "Pending on-device verification" holds 37 entries with about 207 checks, the oldest from 2026-04-24; only 2 commits have ever removed one as verified. Incoming calls did not ring at all until the call sweep found it. → Step 38, F-10
- **PROC-4** High — Bugs cluster after features: three keyboard-covers-content fixes, three emoji-splitting fixes, five image-editor fixes the day after it landed. Instructions alone have not prevented them; a keyboard test helper and a shared grapheme helper with a test corpus would. Per-step review cannot see bugs between steps, so the plan adds a phase check at the end of each code phase (plan D-17). → Steps 1, 8c, 13c, 22c, 25c, F-2, F-4
- **PROC-5** Medium · S — See DOC-4. The runner earns its keep: 37 Shipped lines, 10 `plan/*` branches. → Step 2
- **PROC-6** = MD-3. → Step 1
- **PROC-7** Medium · S — 28 of 30 skills are symlinks to an upstream import pinned by `skills-lock.json`. Six depend on an issue-tracker setup that doesn't exist (`code-review`, `to-spec`, `to-tickets`, `triage`, `wayfinder`, `implement-spec`); several overlap (`grill-me` / `grill-with-docs` / `grilling`, `handoff` / `claude-handoff`, `implement` / `implement-spec`). An upstream update silently changed `/code-review`'s meaning (MD-1). → Steps 1, 36
- **PROC-8** Medium · S — Turn rules into checks: register or delete `ask-simplify.sh`; export Room schemas and fail CI when an existing schema file changes; a short CI script that fails on a FEATURE-MAP path that doesn't exist or a CHANGELOG hash that doesn't resolve (both pass today). Cloud checkouts are shallow (132 commits, no tags), so history-reading scripts need `git fetch --unshallow --tags`. → Steps 1, 9, 38
- **PROC-9** Medium · S–M — Agents read whole long docs to add one line. Archive old CHANGELOG sections, move the verification queue to `docs/VERIFY.md`, state a size budget, and Grep instead of Read. → Steps 36, 37, 38

## 10. Corrections made during verification

- **SEC-1:** the reviewer said a rewritten `publicIdentityKey` on the user document could enable a key swap. Nothing in the crypto path reads that field; Signal identities come from `keyBundles`, which only the owner can write. Dropped.
- **SEC-12:** the reviewer said the rules let anyone add themselves to a group through an invite link. They don't: chat `read` and `update` both require existing membership, and `willBeParticipant()` is defined but never used. Restated as L-3.
- **L-3** ✔ High (part of UX-2) — Invite links cannot work end to end. `joinGroupViaLink` has no UI caller and no deep link reaches it, and under the committed rules a non-member can neither read the chat nor add themselves (`firestore.rules:40-45`). Either the feature is unfinished or the deployed rules differ from the repo (SEC-10). → Step 16, F-7
- **BLD-3:** the reviewer recommended a lint gate now; lint crashes on the current toolchain, so the gate waits for F-5.

## 11. Existing records this review changes

- **TECH_DEBT stale:** "`MainActivity` — no `onNewIntent`", "Timer alarms: dual schedule path", "Shared-media grid omits VIDEO messages", "testReleaseUnitTest — 14 Compose/Robolectric tests fail…", the missing-tripwire half of "Sync-path regression coverage". → Step 2
- **TECH_DEBT triggers fired:** "`SingleFlight` exists but …" (Step 11), "`ChatScreen.kt` — split into …" (rewrite per the 2026-09-20 review #10, F-2), "`MessageEntity` delegates 33 columns" (F-3), the `CallNotificationManager` baseline (F-3).
- **BACKLOG:** "Screen security (3.6)" now has a shipped no-op toggle; "Baseline profile generation" is wrong (a stale profile ships); "CI/CD remaining work" says releases build both flavors. → Step 2
- **SPEC wrong:** E2E on by default (`SPEC.md:9`), voice messages can be sent (`:8`), group typing shows avatars, invite-link joining (`:37-38`). → Step 2
- **CLAUDE.md wrong:** the `assembleDebug` comment, the example test, the lint line, `/code-review`'s meaning, `/simplify`'s reviewer count (four, not three), the `ask-simplify.sh` claim, "pushes directly to main", the Room version-bump rule. → Steps 1, 9
