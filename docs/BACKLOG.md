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

### Client-set message ids and the if-absent retry (offline outbox step 1, 2026-09-11)

Shipped in `68a53e75`; nothing has been on hardware. Firestore's transaction, its
`waitForPendingWrites()` flush and `MetadataChanges.INCLUDE` cannot run under Robolectric,
so the unit tests pin only which SDK call each attempt makes. Check on a device, upgrading
over an existing install, with a second device as recipient:
1. Send a text online → the tick must appear no later than before (the ack now arrives as a
   metadata-only event; if it looks slower, `MetadataChanges.INCLUDE` is not reaching the
   listener).
2. Send, kill the app the instant it goes online, reopen the chat → the row must heal to
   sent on its own (acknowledged echo), no retry needed, and the recipient has exactly one copy.
3. Same, but tap retry before the heal → still one copy, and a reaction plus a read tick the
   recipient added beforehand must survive the retry.
4. Airplane mode → tap retry on a failed message → it must land back at failed within
   30 s, not stay on the clock.
5. (Step 2, `97baf879`) Clear app data or pick a contact never messaged since launch, go
   offline, send a photo → the bubble must stay, marked failed with retry, instead of a
   snackbar and nothing. Reconnect, retry → one copy arrives.
The rest of the outbox checklist lives in `.claude/plans/offline-outbox.md` §4 and moves
here when step 6 ships.

### Encrypt once and the per-contact Signal lock (offline outbox step 4, 2026-09-11)

Shipped in `bcd4426c`; nothing has been on hardware. Upgrade over an existing install:
1. The one-time reset (Room 25 → 27) breaks nothing else: every chat reloads its full history
   on open, already-downloaded photos show without a download spinner, and only unsent or
   failed messages, stars and reminders are gone.
2. Forward a video, a voice note and a location to a second device → thumbnail, duration and
   map point arrive, and the forwarded copy carries no mentions.
3. Airplane mode → forward a photo → leave and reopen the chat (it shows failed) → airplane
   off → retry → it arrives once, without the app trying to re-compress anything.

**Before end-to-end encryption is switched on for the phones** (planned, not yet enabled —
these belong to that double-check). `SignalManagerTest` runs real libsignal only on the JVM,
and the encrypted send path is tested with `SignalManager` mocked. On a **release** build
(debug never encrypts) with Settings → Privacy → Encryption on, two devices:
4. Pick a contact never messaged since the upgrade, send four photos at once plus a text →
   all five decrypt on the recipient.
5. Airplane mode → send a text (fails) → airplane off → retry → the recipient reads it once.
6. Retry after the recipient reinstalled: send a text, kill the sender the instant it goes
   online (the write is lost), reinstall on the recipient, retry → the recipient reads it.
7. The recipient's chat list shows "Message" (or "📷 Photo") for an encrypted last message,
   never its text or caption; the sender's own list still shows the text.
Four open items from the step-4 reviews also belong to that check, two of them blocking:
pre-key replenishment (one fixed-id pre-key, replenished only after a successful decrypt),
the double decrypt between the chat-list sync and the open chat, the stale-bundle race when
a peer re-registers, and the lock tests' 300 ms window
(`.claude/plans/offline-outbox.md`, "Before end-to-end encryption is switched on").

### Image editor — edit from the fullscreen viewer (Phase 6, 2026-09-11)

**Nothing in this phase has been on hardware.** Its risk is the whole path, which no
Robolectric test runs end to end: tap a sent photo, Edit, fetch, copy, preview, edit,
send. The ViewModel's state machine and the tray are unit-tested; the hand-offs between
them are not. **On a device holding real conversations, stop at the send button** — the
path is verified once the preview shows the right photo, not once a message goes out.

- **All three buttons are on screen from the start.** Open a photo in a chat: Edit,
  the download button and × sit side by side top-right, with no `<` to unfold them
  (the folded tray of `5406ef3c` was reversed on the first hardware pass). Open a
  profile picture from the chat list and confirm it shows only ×.
- **Edit on a downloaded photo opens the preview straight away**, on that photo, with
  the viewer gone underneath. Run an adjust and an undo, then confirm Original ⇄ Edited
  still returns to the photo as received. Back out of the preview and confirm you land
  in the chat, not back in the viewer.
- **No blink on the way into the preview.** Open a downloaded photo, tap Edit and watch
  the photo itself: it must stay put at full brightness, with no dip towards the chat
  behind it, no flash to black and no spinner scrim (the first hardware pass saw all
  three; fixed after it). A slow-motion screen recording settles it if the eye cannot.
  Then swipe to a second photo before tapping Edit and confirm the chat has landed on
  that photo once you back out of the preview. Also press back within the first
  half-second of the preview fading in and confirm you land in the chat, not back in
  the viewer. This timing is the one part of the fix no Robolectric test reaches: the
  hand-off lives inline in `ChatScreen`.
- **Edit on a photo not yet on this device shows a spinner, then the preview.** Use a
  photo received with auto-download off. While the spinner is up, confirm the photo
  cannot be swiped. Press back mid-fetch and confirm you stay in the viewer and no
  preview appears when the download would have finished.
- **Rotate the phone mid-fetch.** The spinner should survive and the preview should
  still open.
- **Offline fails visibly.** In airplane mode, Edit an undownloaded photo and confirm
  the "Couldn't open the photo for editing" snackbar shows *over* the viewer.
- **Search results.** Search → Photos → open one → Edit. The preview should open, and
  backing out of it should land in the search grid. Also confirm a save from this
  gallery now shows its "saved to Downloads" snackbar with a working Open.
- **No duplicate in the gallery app.** After editing an undownloaded photo, Google
  Photos should show one copy of it under *FireStream Images*, named by message id — not
  a second `download_…` file.
- **The HD pill is honest.** Open the HD sheet on a photo edited this way and judge
  whether its HD row reads as promising more than the received photo has; the plan
  (Phase 6, item 10) left the pill in place on that bet.

### Image editor — the overlay screen and the sticker/text/shape tabs (Phase 5b, 2026-09-10)

**Nothing in this phase has been on hardware.** More of it is machine-checkable than
Phase 3's was — `OverlayGeometryTest` pins every drag, snap and hit-test on the JVM, and
`ImageEditRasterizerTest` reads the flattened pixels back — but the whole phase is a
gesture over a coordinate mapping, and the two claims it was designed on are claims about
a *hand*. Check in **both orientations**: placements are saved and normalized to the
image, so a rotation mid-placement is a supported path and an untested one.

- **A 26 dp handle with a 48 dp reach is grabbable without occluding what it sits on.**
  *The claim the two-handle design rests on.* Place a small sticker, then try to grab the
  bottom-right (scale) and top-right (rotate) corners. If the handle covers the thing it
  is attached to in practice, the fallback is **the readout pill becoming the drag
  target — not a second left-edge slider**, which would read like the draw screen's width
  control and mean something else entirely.
- **The four-segment island plus the search and delete buttons fit a 390 dp row.** The
  island is icon-only except the active segment for exactly this reason. Check on the
  narrowest device to hand, and check that expanding search still slides the island away
  cleanly and that its × brings it back. Since `edce7fdc` the Text and Shapes tabs show
  no search button at all: switch to each and confirm the row is just the island and
  delete, then switch back to Emoji or Stickers and confirm search returns.
- **The rotate snap feels right, not sticky.** 15° everywhere, 8° of pull on the
  cardinals. Confirm a deliberate 22° tilt stays at 22° and that "exactly square" is easy
  to hit.
- **A drag keeps up with the finger.** Place several objects on a full-resolution photo
  and drag the topmost. Also confirm a second finger landing mid-drag does not tug the
  object to it, and that tapping bare photo deselects. Then place a line of text wider
  than the photo, grab it near one end and drag it several times in the same direction:
  it must keep travelling, not jump back to where each drag began — which it did until
  the grab offset landed, and only on hardware was it noticed.
- **The picker rises above the keyboard.** Open the Text tab and tap the field: the field
  and its Add button must sit just above the keyboard, and the panel must drop back to
  the navigation bar, not float, once the keyboard closes. Repeat with the emoji search.
  Check both gesture and three-button navigation, since the panel takes the union of the
  bar's inset and the keyboard's.
- **The flattened file matches the preview.** Place a sticker, a text run and a shape,
  press Done, and inspect the **written file** via the preview's *Save to Downloads*
  button — not the preview. Check position, size, angle and the text's vertical centring,
  which is the one measurement the two renderers compute by different routes.
- **Long text wraps the same way in the preview and in the file.** Type a sentence long
  enough to need three or four lines, place it, and confirm it wraps at the photo's width
  on screen rather than running off both sides. Press Done and inspect the written file:
  the same line breaks, every line centred, and the block's vertical centre where the
  preview had it. Then scale it up until a single word no longer fits a line and confirm
  the preview and the file break that word at the same place. Both renderers lay the
  text out through the same `StaticLayout` with font padding off; a `lineHeight` or an
  `includePadding` on either side would separate them, and only a written file shows it.
- **The layer eye does not change the output.** Place objects, press the eye until they
  vanish, press Done, and confirm they are still in the file (§2.7). Re-open and confirm
  it comes back visible.
- **Placements survive a rotation.** Place several, undo one, turn the phone, and confirm
  both the objects and the redo are still there.
- **The stickers look like what they are called.** Twelve hand-written vector designs
  that no test can eyeball — check the heart, the pin and the speech bubble in particular,
  at both thumbnail and placed size.

### Image editor — the picker extraction (Phase 5a, 2026-09-10)

A pure refactor: the ~700-line emoji panel became a shared `ui/chat/picker/` shell
(`PickerPanel`) plus its emoji content (`EmojiTab`), and `EmojiHandlerPanel` is now a
one-tab alias over it so the composer, the reaction sheet and the caption bar call
exactly what they called before. `PickerPanelTest` asserts the structural half — no
island, no delete button, backspace only where a text field is, the quick strip only on
the reaction sheet, a frozen recents order. What it cannot assert is the half that is
**felt**, which is precisely the half a move puts at risk:

- **The long-press size drag still feels the same.** Hold an emoji in the composer and
  drag up: the size readout, the anchored preview panel, and the fade of the *other*
  emoji in that row. Release and confirm the emoji is inserted at the size chosen.
- **The recents block holds still under the finger.** Tap several emoji in quick
  succession without closing the panel; the Recents row must not reorder while it is open.
  Close and reopen and confirm the new order has been picked up.
- **All three hosts are indistinguishable from before.** Composer, reaction sheet
  (long-press a message) and the caption bar in the send preview — the search field
  expanded with no island, the category rail at the bottom, and the same panel height.

### Image editor — the draw screen (Phase 4, 2026-09-10)

**Nothing in this phase has been on hardware either.** More of it is machine-checkable than
Phase 3's was — `DrawImageScreenTest` drives a real pointer across the canvas, and
`ImageEditRasterizerTest` reads the flattened pixels back — but the two questions that
matter most are still hardware questions, and one of them is a privacy question rather
than a cosmetic one. Check in **both orientations**: the drawing is saved and the strokes
are normalized to the image, so a rotation mid-drawing is a supported path and an
untested one.

- **The flattened file redacts what the preview showed as covered.** *The one check
  nothing else can stand in for.* Blur a face or a card number, press Done, and inspect
  the **written file** — not the preview. A blur landing a few pixels off in the JPEG is a
  privacy failure. The installed APK is not debuggable (`run-as: package not debuggable`)
  and `adb root` is refused, so either reinstall
  `app/build/outputs/apk/firebase/debug/app-firebase-debug.apk` and confirm `run-as`
  reaches `cacheDir/edits/`, or route the check through the preview's **Save to Downloads**
  button, which lands the current image where `adb pull` can reach it and tests a real
  user path besides.
- **The layer eye does not change the output.** Draw, press the eye until the strokes
  vanish, press Done, and confirm the strokes are still in the file (§2.7). Then re-open
  the draw screen and confirm it comes back visible.
- **A stroke keeps up with the finger on a full-resolution photo.** The preview decodes at
  1600 px; drawing on a 12 MP original is where a dropped frame would show. Check a fast
  scribble and a slow curve, and that a second finger landing mid-stroke does not tug the
  line to it.
- **A blur is coarse enough to actually redact.** The mosaic is 48 blocks across the long
  edge. Blur a face at a realistic size and confirm the result reads as redacted rather
  than as softened, and that the blocks look the same size in the preview and in the file.
- **Pen survives a blur drawn over it.** Circle something, then blur across the circle;
  the circle must still be there, because blur is painted under the annotation layer.
- **A drawing survives a rotation.** Draw several strokes, undo one, turn the phone, and
  confirm both the strokes and the redo are still there. A long scribble is thinned on
  save (`StrokeGeometry.MAX_SAVED_POINTS`) — confirm the thinning is invisible in practice.

### Image editor — the adjust screen (Phase 3, 2026-09-09)

**Nothing in this phase has been on hardware.** Every item below is a gesture over a
coordinate mapping, which is exactly the class of bug a Robolectric test passes through:
`AdjustImageScreenTest` renders the screen without ever moving a pointer across it, and
`CropGeometryTest` checks the arithmetic in isolation from the pointer that feeds it.
Check in **both orientations** — the crop frame is normalized to the image and the op
stack is saved, so a rotation mid-crop is a supported path and an untested one.

- **Crop handles land where the finger expects them.** Grab each of the four corners near
  its bracket and confirm the one that moves is the one under the finger, that the
  opposite corner does not drift, and that a drag running off the photo stops at the edge
  rather than cropping black in from outside it. Also drag the frame's interior to move it.
- **Every crop grip is reachable by thumb (margin fix + side grips, 2026-09-11).** With
  gesture navigation on, open Crop on a portrait photo and grab all four corners and all
  four side grips of the full frame, in portrait and in landscape: each should move, and
  back should never fire when the finger lands just inside the bracket. Each grip also takes
  a touch up to 48 dp inside the frame, with nothing drawn there — grab from there too and
  confirm the grip moves with the finger rather than jumping under it. The margin is
  a third of full clearance by choice, so repeat with back sensitivity at its **highest** —
  that is where the reachable band is thinnest, and the case that would argue for raising
  `CropGeometry.MARGIN_FRACTION`. Under 1:1 and 16:9, a side drag should resize the other
  axis about its centre and never push the frame off the photo. Opening and closing Crop
  animates the photo into and out of its margin — confirm nothing jumps.
- **A straighten leaves the image genuinely axis-aligned.** Photograph something with a
  hard horizontal (a windowsill, a table edge), straighten it against the thirds grid, press
  Done, and check the *written file* — not the preview — is level. A degree of drift between
  what the slider showed and what was flattened would be invisible on screen.
- **The live auto-crop matches the flatten.** While dragging the slider the image scales up
  so the corners stay full; confirm the framing Done writes is the framing that was on
  screen when the finger lifted, and that no black triangle survives anywhere in the output.
- **Rotate does not fight the pager's own drag.** The editor replaces the preview's content
  rather than floating over it, so the pager should be unreachable while the editor is open —
  confirm a horizontal crop drag never pages the batch, and that system back closes the
  editor rather than throwing the whole pick away.
- **The resize presets are honest.** Send the same photo at 1080 and at 720 and compare the
  received files against the labels; they say "~" but should be the right order of magnitude
  and in the right order.
- **The whole undo axis end to end.** Adjust a photo three times, undo back to the pick,
  redo forward, then adjust again mid-history and confirm the redo tail is gone and the
  photo that sends is the one on screen.

### Image editor — the rasterizer and the edit cache (Phase 2, 2026-09-09)
- `ImageEditRasterizer` decodes through `ImageDecoder` with a 4096 px target size. Robolectric
  covers the round trip on synthetic bitmaps; what it cannot cover is the pathology the decoder
  was chosen for — large *real* camera originals, which come back black through a subsampled
  `BitmapFactory` (see `ScaledImageDecoder`'s KDoc). **Phase 3 is the first phase that calls
  `rasterize`, so this is now checkable**: open the adjust screen on a 12 MP+ camera original,
  rotate it, press Done, and confirm the preview and the sent photo are the photo rather than
  a black rectangle.
- Also unconfirmed: the HD sheet's size estimates against what actually leaves the device. Send
  the same photo Standard and HD and compare the two labels to the received file sizes — the
  labels say "about", but they should be in the right order and the right order of magnitude.
- Also unconfirmed: that `cacheDir/edits/` is empty after a cold start following a preview
  session older than 24 hours (`ImageEditRasterizer.sweepStale`, called from `FireStreamApp`).

### Link previews — consent walls, fetch de-duplication, Maps metadata (`6ef4abc`, `ae89ec1`, 2026-09-08)
- Nothing here has been seen on hardware. The offscreen `WebView`, `postVisualStateCallback`,
  and `PixelCopy` with a scaling `srcRect` are all outside what a JVM unit test can reach, so
  the whole capture path is build-verified only.
- Check on a device: (a) a news site with a cookie wall — the preview should be the page, not
  the banner; (b) a `maps.app.goo.gl` link shared from the Google Maps app — should now show the
  place name and Google's own map image, *not* a bare link and not a screenshot of
  `consent.google.com`; (c) a chat with several image-less links open at once — previews should
  fill in one at a time without the list stuttering.
- Specifically unconfirmed: that the geometric stripper
  (`WebPagePreviewCapture.OVERLAY_STRIPPER_JS`, `document.elementsFromPoint` at a sampled grid)
  hides banners without also hiding a site's real content — a full-viewport `fixed` wrapper that
  *contains* the page would trip the same size test. If a preview comes back blank white, that
  is the first thing to suspect.
- Also unconfirmed: that `PixelCopy`'s `srcRect` overload scales rather than crops here. A
  preview showing only the top-left corner of the page means it cropped, and the fix is to go
  back to a full-size destination bitmap plus an explicit downscale.
- New with `ae89ec1`, and unconfirmed on hardware: that the crawler User-Agent behaves on a
  phone the way it does from a desktop shell. It was verified against `google.com/maps` with
  `curl` (no consent redirect, `og:image` is a signed static map), but never from the device,
  and never against a real `maps.app.goo.gl` short link — short-link resolution now depends on
  `response.request.url` after redirects, which no unit test exercises.
- Also new and unconfirmed: the one-shot browser retry on 401/403/406/429. No site in testing
  actually refused the crawler UA, so the retry path has only ever run against a stubbed
  response. If previews start failing on a specific domain, check `LinkPreviewSource` there
  first — a refusal that is not one of those four codes still falls into the ten-minute cooldown.

### Message search — prefilter chips + global scope (`56cb67a`…`261704d`, 2026-09-07; global 2026-09-08)
- Device pass still outstanding for the in-chat surface: the date range picker, the
  photo/video grid, and tapping a video tile through to the player. (The chip row's
  horizontal scroll is no longer in doubt — `SearchFilterChipRow` is one composable and it
  was confirmed scrolling in global search on 2026-09-08.)
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
- Global search (`Routes.SEARCH`, from the chat list magnifier) is **partly verified**
  (release build, emulator API 36, 2026-09-08). Confirmed: the screen renders and survives
  R8 minification; a Links browse returns hits across chats with an honest count and
  newest-first icon rows; and **the chip row scrolls freely** under the pager-free
  destination — the check that motivated making it a destination at all, confirmed by the
  author on device.
  Also confirmed: tapping a global result lands *at the message* in the right conversation
  rather than at the bottom of it. Media tiles are covered by the same check rather than
  by analogy — `GlobalSearchScreen` passes one `openResult` lambda to both `onResultClick`
  and `onMediaClick`, so there is no second path to exercise.
  Still unverified, and the last item here: that the field is focused **with the keyboard
  already up** on entry — `LaunchedEffect` + `FocusRequester` only requests focus, and some
  OEM IMEs need more. An emulator would not prove this either way; it needs a real phone.
- Unverified on device: the **two-line link rows** (`SearchLinkRow`). Specifically that
  previews arrive as rows scroll into view without the list stalling or reflowing — the
  fetch behind one can end in `WebPagePreviewCapture`'s serialised offscreen WebView, so a
  fast scroll through a Links browse queues captures behind each other. Emulator-visible;
  the honest check is a real chat with a dozen image-less links in it.
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
  iterations: `!BuildConfig.DEBUG` in `MessageWriter`'s injected constructor
  (`data/outbox/MessageWriter.kt`) and the
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

**The picker is no longer the missing piece.** Phase 5a of the image editor extracted
`ui/chat/picker/` — a shell whose tabs are declared by the host, with `PickerTab` already
enumerating `STICKER` and `GIF` and `PickerSelection` shaped to carry them. What is left
open is not "a picker" but *sending* a sticker or a GIF as its own message, which is a
data-model change, and the provider decision a GIF forces:

- **Sticker-as-message** — a new `MessageType.STICKER`, an `AppDatabase` version bump, a
  sync path and a bubble renderer. Placing a sticker *on a photo* needed none of this and
  **shipped with the editor in Phase 5b**, because it is flattened into the JPEG.
- **GIF-as-message** — the same, plus an animated bubble renderer. GIF *on a photo* is
  impossible rather than unbuilt: the pipeline ends at JPEG, and a flattened animation is
  one frame and a worse sticker (`.claude/plans/image-editor.md` §2.8).
- **The provider-privacy decision, already made and written down:** sending a Giphy URL
  makes the recipient's device fetch from Giphy, which tells a third party who received
  what and hollows out the Signal-Protocol story. For this app only downloading the bytes
  and re-uploading them as an ordinary media message is consistent — it costs bandwidth
  and keeps the recipient private.
- **Downloadable sticker packs** — pack management is its own feature. Phase 5b shipped
  one bundled local pack of twelve drawn marks, and the sticker tab has no recents row
  because twelve fit on screen; both become worth revisiting together with pack
  management.
- Files: `ui/chat/picker/` (exists), new `data/remote/GifSource.kt`, `MessageType`,
  `AppDatabase`, `MessageBubble.kt`

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
- **Save from the profile's shared-media gallery** — it shows the same chat photos as the chat's own gallery, which has Save to Downloads, but offers no save. Needs `MediaFileManager` and a snackbar channel in `ProfileViewModel`; found in the image editor's Phase 6 download-button audit and deliberately left out of that commit (`.claude/plans/image-editor.md`, Phase 6)

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
