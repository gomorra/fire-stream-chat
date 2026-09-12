# Changelog

All notable changes to FireStream Chat. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); each section is headed by the SemVer `versionName` shipped on that merge day (e.g. `## [1.2.3] — 2026-04-24`). Bump rule: `feat:` → minor, `fix:` → patch, `feat!:` / `BREAKING CHANGE:` → major. `versionCode` is derived from `git rev-list --count HEAD`.

## [UNRELEASED] [1.31.0] — 2026-09-12

### Added

- **Messages send themselves once you are back online, even if the app was closed in between.** A message written without a connection used to fail on the spot, with a tap on the failed bubble the only way to try again, and only while the app was open. Everything you send is now kept in a queue on the phone and goes out on its own the moment there is a connection, whether you left the chat, swiped the app away or restarted the phone in between; the clock icon means "waiting to be sent", a tick means the server has it, and a message never arrives twice. A message that keeps failing gives up after eight tries and shows the failed bubble, deleting a message that is still waiting deletes it for good on both sides, and sending to someone you have blocked is still refused. On Android 10 and 11 an upload shows a small "Sending" notification while it runs. (`7ad1b81f`)
- **A chat says when there is no connection, so a waiting message explains itself.** The clock icon on a message meant either "on its way" or "nothing to send it over", with no way to tell which. The chat's top bar now reads "Waiting for network…" in place of the other person's status whenever the phone has no working connection, and the message details of a waiting message say "Waiting to send". A Wi-Fi that is connected but still wants a login page counts as no connection, which is what it is for a message. Nothing about sending changes — the message is already safely queued and goes out by itself. (`0657d35f`)
- **A photo sent to you while you were offline is on your phone before you open the chat.** Until now a message that arrived while the app was closed only reached the phone once you opened its chat, and its photo or video was downloaded only then, so the first look at a chat after a flight meant a wait behind every picture. The notification that announces a message now also fetches the message itself and, following your auto-download setting, its photo, video or document, so the chat opens with everything already there. A download the network drops halfway is picked up again as soon as there is a connection, and "Wi-Fi only" waits for Wi-Fi. One limit: if the phone stops the app mid-download, the file arrives with the daily catch-up or when you open the chat. (`ecbc38a9`)

### Changed

- **The app rebuilds its local copy of your messages once more on this update.** The bookkeeping a queued message needs moved beside the message in the local store, so the store is reset once again, exactly as with the previous update: every chat reloads its history from the server the first time you open it, photos and videos already on the phone are picked up without downloading again, and unsent or failed messages, stars and scheduled reminders do not come back. (`f1b5d887`)

## [UNRELEASED] [1.30.0] — 2026-09-11

### Added

- **A photo in a chat can be edited and sent again straight from the fullscreen viewer.** Edit sits beside Save to Downloads and opens the photo in the send preview, where the adjust, draw and sticker tools and a caption all work as they do for a new pick. The result goes out as a new message, and the original stays exactly as it was, both for you and for everyone who received it. A photo that has not been downloaded to this device yet is fetched first behind a spinner, and pressing back during the fetch cancels it. Edit works from the chat's photo gallery and from the photos in search results, but not on link-preview images or profile pictures. (`5406ef3c`)

### Fixed

- **Retrying a message no longer delivers it twice.** If you left a chat while a message was still sending, the app could lose track of it even though it had gone through, mark it as failed, and then send a second copy when you tapped retry. Every message now keeps one identity from the moment it is composed, so a retry only fills in what is genuinely missing and leaves a message that already arrived, along with its read ticks and reactions, exactly as it is. A message marked failed that turns out to have arrived corrects itself to sent the next time the chat opens. One limit: a message that was already marked failed before this update, and had in fact arrived, can still be duplicated by a retry. (`68a53e75`)
- **A message sent without a connection no longer disappears.** Before sending, the app checks that you have not blocked the person you are writing to. Without a connection that check could fail, and when it did the message, photo, voice note or location was thrown away with only a brief error, so it had to be written or picked again. The message now stays in the chat marked as failed, with the retry button, and goes out on a retry once you are back online. Messages to someone you have blocked are still refused, and now also stay visible as failed rather than vanishing. (`97baf879`)
- **A sticker or a line of text placed on a photo no longer jumps when you start dragging it.** Grabbing an object anywhere but its exact centre snapped that centre under your finger, and did so again at the start of every drag. A line of text wider than the photo could never be dragged across: each swipe slid it back to where the previous one had started, so the same words seemed to scroll past endlessly. The point you grab now stays under your finger for the whole drag, and a quick drag no longer sends the object racing off to the edge of the photo. (`a56cd055`)
- **The keyboard no longer hides what you are typing onto a photo.** In the photo editor's Text tab the keyboard covered the text field and the button that places the text, so nothing could be placed without closing the keyboard first, and the emoji search in the same editor was covered the same way. The panel now sits above the keyboard while it is open. (`5ca6c5ea`)
- **Editing a photo from the fullscreen viewer no longer makes it blink.** Tapping Edit put the photo in the same place in the send preview, but it vanished and came back on the way there: the viewer faded out while the preview faded in over the chat underneath, the preview drew nothing until it had decoded its own copy of the photo, and the fetch spinner flashed even for a photo already on the device. The preview now starts from the very bitmap the viewer was showing, fades in over the still-open viewer and closes it only once fully in, and the spinner appears only when a download is actually needed. (`81afc469`)
- **Saving a photo from the search results now confirms it.** The "saved to Downloads" message was shown underneath the fullscreen photo, where nobody could see it or tap Open. (`5406ef3c`)
- **Long text placed on a photo now wraps instead of running off both sides.** A caption of more than a few words was drawn on one line wider than the photo, could not be dragged far enough to see either end, and lost both ends when the photo was sent. Text now wraps at the photo's width into centred lines, in the editor and in the sent photo alike. (`a39940fe`)
- **The photo editor's Text and Shapes tabs no longer show a search field that does nothing.** Both tabs offered a search box that accepted typing but had nothing to search: the Text tab is where you type the words themselves, and every shape already fits on screen. Search now appears only on the tabs with a list to filter, Emoji and Stickers. (`edce7fdc`)
- **A forwarded photo or video that failed to send can be retried.** Retrying a forward tried to re-compress the picture from a file the forwarded copy never had, so the retry failed every time and the message stayed marked as failed. The forwarded copy already points at the uploaded file, so a retry now just sends it. Also groundwork for end-to-end encryption, which is not switched on yet: an encrypted message's preview in the recipient's chat list will say "Message" or "📷 Photo" rather than carry the text beside the encrypted copy, a retry will not reuse an encrypted copy the recipient can no longer read after reinstalling, and a message decrypted while the app was cancelling a background sync is no longer lost. (`2f8bc711`)
- **Forwarding a video, voice note or location now sends all of it.** Forwarding copied only a message's text, media link and size, so a forwarded video arrived without its preview image or length, a forwarded voice note without its length, and a forwarded location without its map point. A forward now carries the message as it appears in your own chat — minus mentions, which named people in the chat it came from. (`bcd4426c`)
- **The chat list shows the message you sent last, even when several go out at once.** Messages sent together finish at different speeds, and the chat list, yours and the other person's alike, showed whichever finished last: a photo picked before a text but slower to upload took the preview from the text sent after it, and so could a retried message that was older than the chat's latest. The preview now only ever moves to a newer message, and messages sent in the same instant, like several photos picked together, always keep the order they were sent in. (`40023dbc`)

### Changed

- **The app rebuilds its local copy of your messages once, on this update.** Messages gained the bookkeeping a retry needs to resume without encrypting a message twice, and the app resets its local message store whenever that layout changes. Every chat reloads its full history from the server the first time you open it, and photos, videos, documents and voice notes all stay available — files already on the phone are picked up without downloading again. What does not come back: messages that were still unsent or marked failed, starred marks, and scheduled message reminders. The encryption side changes nothing you can see yet, because end-to-end encryption is not switched on: once it is, several messages sent to one person at once can no longer garble each other, and a retried message goes out as the same encrypted copy. (`bcd4426c`, `2f8bc711`)

## [1.29.0] — 2026-09-11

### Added

- **The crop frame can be resized along one side.** Alongside the four corners, each side of the frame now has a grip at its middle that moves only that edge, so a crop can be made narrower or shorter without touching the other dimension. With an aspect preset chosen, the other dimension follows to keep the ratio, growing and shrinking about its centre. (`fb3cdcb7`)

### Fixed

- **The crop corners can be reached with a finger.** A photo as wide as the phone was fitted edge to edge, so its crop corners sat on the very edge of the screen: half of each grab area was off the glass and the rest sat inside the strip Android reserves for the back swipe, so reaching for a corner closed the editor instead. With the crop tool open the photo now shrinks into a narrow margin that brings every corner in off the edge, and grows back when the tool closes. The photo also no longer runs under the top bar and the bottom panel on phones with a status or navigation bar. (`56c2533d`, `fb3cdcb7`)
- **Crop corners and side grips can be grabbed from inside the frame.** Even with the margin, a finger aimed at a corner on the left or right of the photo often landed in the strip Android reserves for the back swipe, so the editor closed instead. Each grip now also answers a touch up to about a finger's width inside the frame, with nothing new drawn, and a grip grabbed that way moves with the finger rather than jumping under it. (`e70ea6fd`)
- **A crop corner dragged past the opposite corner no longer grows the frame back out.** The frame shrank to its minimum as the corner reached the opposite one, then started growing again as the finger kept going. It now stays at the minimum. (`fb3cdcb7`)

## [1.28.0] — 2026-09-10

### Added

- **Photos can be decorated and annotated before they are sent.** The sticker button in the send preview now opens a full-screen editor for putting things *on* a photo: emoji, a bundled pack of twelve stickers, a line of text, and five shapes — rectangle, rounded rectangle, ellipse, line and arrow. Text and shapes come in the colour you choose and in either a solid or an outlined style, which is what makes an arrow or a box round something readable over any photo. Tap to select, drag to move, and use the two corner handles to resize and turn — bottom-right scales and top-right rotates, each showing what it is doing as you drag, and rotation snaps every 15° with a firmer pull towards square. Two fingers do both at once. A trash button appears beside the picker while something is selected, undo and redo step back one placement at a time (including a deletion), and an eye button hides everything you have placed so you can see the photo underneath — hiding never changes what gets sent. Done adds it all as a single step in the preview's edit history, so it can be undone alongside a crop or a drawing; Cancel writes nothing. (`021b5b94`)

### Changed

- **The sticker button in the send preview is no longer dimmed.** It shipped alongside Adjust and Draw with nothing behind it; it now opens the editor above. (`021b5b94`)

## [1.27.0] — 2026-09-10

### Added

- **Photos can be drawn on before they are sent, including a blur that redacts for good.** The Draw button in the send preview opens a full-screen editor with three tools: a pen, a highlighter that lets what is underneath stay readable, and a blur for covering up a face, an address or a bank card. Blur is pixelation rather than a soft smear, and the detail it covers is genuinely gone from the photo that gets sent — it cannot be sharpened back out. Each tool has a colour strip and a width slider, undo and redo step back one stroke at a time, and an eye button hides everything you have drawn so you can check a blur against what it was meant to cover. Hiding never changes what gets sent: Done writes every stroke either way. Done adds the drawing as a single step in the preview's edit history, so it can be undone there alongside a crop or a rotate; Cancel writes nothing. (`4f40203`)
- **Photo quality is now per photo, not a single global setting.** Sending in HD was one switch in Settings that applied to everything; picking one photo out of a batch to send at full resolution meant changing the setting, sending, and changing it back. The send preview now carries an HD button that acts on the photo you are looking at, opening a sheet with Standard and HD side by side and an approximate size for each — sizes are estimated from the photo's own dimensions rather than measured, because measuring them exactly would mean compressing every photo twice. A photo you never touch still follows the Settings preference exactly as before. (`2b69002`)
- **Photos can now be cropped, straightened, rotated and resized before they are sent.** The Adjust button in the send preview opens a full-screen editor: rotate in quarter turns, flip, a straighten slider with a thirds grid, a crop frame with draggable corners and Free / Original / 1:1 / 4:5 / 16:9 presets, and a resize row offering 1600, 1080 or 720 px on the long edge — each preset labelled with the exact dimensions it will produce and roughly what the file will weigh. Straightening crops as you drag, so the photo never stops being a full rectangle and no black corners can be sent by accident. Inside the editor, undo and redo step back through the individual transforms and Reset clears them all at once; Done writes the result as a single step and Cancel writes nothing. (`debe9b1`)
- **Undo, redo and Original ⇄ Edited in the send preview do something now.** These three shipped alongside the toolbar but nothing could produce an edit for them to act on. Now that the adjust screen exists they work as intended: undo steps back one whole editor visit at a time, all the way to the untouched photo, redo walks forward again, and Original ⇄ Edited jumps between the photo as picked and the step you were on. Each acts on the photo you are looking at, not on the batch, and the thumbnail strip follows. (`debe9b1`)
- **Photos can be saved to Downloads before they are sent.** The download button existed in the fullscreen viewer but not in the send preview, so keeping a copy of something you had just taken or been handed meant sending it first. It is now in the preview too, and saves the photo on the page you are looking at. (`2b69002`)

### Changed

- **Photo quality estimates now come from the same code that does the compressing.** The Standard and HD sizes in the quality sheet were worked out by a separate copy of the compressor's rules, which would have drifted silently the first time those rules changed. Both rows are now measured against the real thing, and the sheet leaves out a size it genuinely cannot work out rather than printing a number to fill the space. (`30b7b83`)
- **Photos left in the send preview no longer keep working files around.** The editor writes each change to its own file so it can be undone; those files are now cleaned up when a photo is removed from the batch, when the batch is sent or thrown away, and on app start for anything older than a day. (`30b7b83`, `63a8f99`)
- **The send preview has an editing toolbar.** Adjust, sticker and draw buttons now sit alongside HD and download at the top of the preview screen; Adjust and Draw are live, while sticker stays dimmed until a later update wires it up. The batch counter has moved below the toolbar to make room. (`2b69002`, `8fa8f0f`, `debe9b1`, `4f40203`)
- **An edited photo is capped at 4096 px on its long edge.** Rotating or cropping means decoding the whole photo, which a 108 MP original cannot survive, so an edit pass works at 4096 px. Sending an *untouched* photo in HD is unaffected and still goes at full resolution; only a photo that has been through the editor is capped. Editing also strips the photo's location and camera metadata, since the result is re-encoded from scratch. (`30b7b83`, `debe9b1`)

### Fixed

- **Debug builds no longer crash when you open a chat.** Opening any chat in a debug build threw a `VerifyError` before a single message rendered. The message bubble had grown into one very large function, past a limit Android enforces when it loads the class rather than when the code is compiled — so the tests passed and only a real device refused it. Release builds were never affected, which is why it stayed hidden. (`8522232c`)

- **The crop frame can be dragged again.** In the adjust screen the crop corners moved once and then snapped back to the whole photo, and the frame could not be dragged to a new position at all. The gesture was acting on where the frame had been when the drag started rather than where it is now. (`9169ce3`)

- **A photo's crop handles now sit on the photo.** The crop frame was drawn against the photo's fitted rectangle while the photo itself was positioned half a letterbox away from it, so on any image that did not exactly fill the screen the corner handles sat a finger-width from the edges they were supposed to be grabbing. The editor also survives turning the phone now — a half-finished crop used to be thrown away on rotation. (`8e93a95`)

## [1.25.0] — 2026-09-08

### Changed

- **Link search results now carry the page's preview image.** A hit under the Links chip was a single-height row with the bare URL on it, and a column of those is close to unreadable — the host is rarely what anyone remembers about a link they were sent. Each link result is now twice as tall and leads with the page's preview image and headline, with the URL kept underneath so a title alone never has to answer "is this the shop or the review of it?". Previews are fetched as rows scroll into view rather than all at once, and a link already previewed in the conversation reuses that image instead of being fetched a second time. Document results are unchanged. (`238d051`)

## [1.24.4] — 2026-09-08

### Fixed

- **Google Maps links show a preview again.** Sharing a place from Google Maps produced a bare link with no card at all, in the chat bubble and in the share sheet alike — Google redirects an ordinary browser to its consent wall, and the metadata it does serve writes `content=` before `property=`, which the tag parser could not match. The preview fetch now identifies itself as a social crawler, which Google answers with no consent wall and a ready-made map image of the place, and the place name is read out of the Maps link itself when the page only calls itself "Google Maps". (`ae89ec1`)
- **A refused link preview no longer costs ten minutes of silence.** Sites that screen for crawlers answer with a refusal, and one refusal used to leave that link previewless until the cooldown expired; the fetch now quietly tries once more as an ordinary browser. (`ae89ec1`)

## [1.24.3] — 2026-09-08

### Changed

- **The scroll-to-bottom button appears much sooner.** Returning to the newest messages previously required scrolling past more than two full screens of conversation before the down arrow button would appear. It now appears as soon as the screen is scrolled up by 20% of the chat screen's height. (`0e275c7`)

## [1.24.2] — 2026-09-08

### Fixed

- **Link previews no longer show a cookie banner instead of the page.** When a shared link had no preview image of its own, the app rendered the page offscreen and photographed it — and what it photographed was the consent wall sitting on top, so the preview for a news article was a picture of "We value your privacy". Consent scripts are now blocked before they can build a banner, anything that still appears is stripped by checking what is genuinely covering the page rather than by matching a list of vendor names, and a link that lands on a consent page outright — which is where a Google Maps link goes — gets no image rather than a photograph of the wall. Previews also stopped identifying themselves as a bot when reading a page, which is why several large sites were serving them an interstitial with no preview information in it at all. (`6ef4abc`)

- **Opening a chat with links in it is no longer sluggish.** Every time a message changed status — sent, delivered, read, and each of those is its own update — the app re-fetched the preview for every link on screen, including re-rendering the offscreen page, which takes up to twenty seconds. A busy conversation could have several of those running at once, which is what the stutter was. Each link is now fetched once, shared between everything that asks for it, retried on a cooldown rather than on repeat if it fails, and rendered one page at a time. Scrolling a long conversation is cheaper too: matching a message to its preview, and a reply to what it replies to, no longer means searching the whole conversation for each bubble on screen. (`6ef4abc`)

## [1.24.1] — 2026-09-08

### Fixed

- **Link and document results in search now say who sent them, and where.** A hit under the Links or Docs chip was a bare URL or filename with a date under it — fine inside one conversation, useless across all of them, since the whole point of a global search is finding the link someone sent you without remembering which chat it was in. Those rows now carry the same `Alice · Weekend Trip` heading the text results have had, in the same place, so the two kinds of result read as one list. In a conversation the heading is just the sender, as it is for text hits. (`329c35c`)

## [1.24.0] — 2026-09-08

### Added

- **Search in a conversation now has filter chips, so it doubles as a browser.** Searching a chat used to mean typing a word and reading a list of text rows — no help at all when what you actually want is "the photos", or "that PDF someone sent in March". A row of chips now sits under the search box — Photos, Videos, Links, Docs, Voice, Starred, Date — and a chip on its own, with nothing typed, browses: photos and videos come back as a thumbnail grid you can tap straight into, documents and links as rows showing the filename or the URL rather than the sentence around it. Chips combine with what you type, so "report" with Docs selected searches filenames only. The Date chip opens a range picker and then wears the range you chose, and the line above the results spells out every active filter with an × to clear them, because chips scroll off-screen and a filter you can't see is a filter you'll blame the app for. Counts are capped, so a full page reads "200+" rather than claiming an exact total it doesn't have. (`56cb67a`, `0fad83b`, `344da67`, `905aa24`)

- **Search across every chat, with the same chips the conversation search has.** The magnifier on the chat list used to open a thin strip that matched text and nothing else: no filters, no way to tell which conversation a hit came from, and tapping one dropped you at the *bottom* of that chat rather than at the message you had just read. It is now a screen of its own, opening with the keyboard already up, carrying the full chip row — Photos, Videos, Links, Docs, Voice, Starred, Date — so "every photo anyone sent me in March" is a search you can actually run. A chip on its own still browses, counts still say `200+` rather than claiming a total they don't have, and deleted messages stay out of the results — a guard the old global search never had, and the one that mattered most once it gained a Photos chip, because a deleted photo keeps its file reference. Search is its own destination rather than a panel over the list for a concrete reason: the list is a swipeable tab, and a scrollable row of chips inside it would have fought the swipe. (`17cb1c3`)

### Changed

- **"Shared Media" now opens search, pre-filtered to photos, instead of its own screen.** The three-dot menu item lands you in the same media grid the Photos chip does — so you can narrow it by date, or switch to videos, without backing out and starting again. The separate screen is gone; keeping two per-chat media browsers with different sources only guaranteed they would drift apart. The Shared Media grid on a profile is unaffected: that one is per-person rather than per-chat. (`b594d7c`)

### Fixed

- **A deleted photo could have come back as a thumbnail in a media browse.** Deleting a message blanks its text but keeps the file reference, which was invisible to a text search and would not have been to a filter-only one. Search now excludes deleted messages outright, so what it can return matches what the conversation actually shows. Tapping a result that can no longer be reached — including a photo whose file is gone — also says so and keeps your results, rather than doing nothing at all. (`56cb67a`)

- **Search result counts could be presented as exact when they weren't.** Search fetches a capped page and then narrows it to whole-word matches, so a search for "cat" in a chat full of "category" could fill its page with near-misses, show the two real hits as "2 results", and never fetch the older ones. The count now reports truncation from the layer that saw the full page, so a capped search says so.

- **Back out of a search, not out of the conversation.** With search open — including the media grid "Shared Media" now opens — the system back button closed the chat entirely, since search is an overlay rather than a screen of its own. It closes the search and leaves you where you were. An empty result also takes the whole pane now, instead of a one-line note above the conversation that made "Shared Media" in a chat with no photos look like it had done nothing.

- **Search results named the sender by a raw account id.** Every text result in a conversation search was headed by a twelve-character fragment of the sender's internal id — `fIBTup2Ablac` — where a name belongs, which told you nothing about who wrote the message you were looking at. Results now say **You** for your own messages and the person's name for everyone else's, falling back to the group name; the same resolver now serves the reminder notifications and the `.remind` widget, which had each grown their own copy of it. (`57f7cfa`)

- **A global search result now says where it came from, and takes you to the message.** Every hit is headed by the conversation it was said in — `Bob · Weekend Trip`, or just the person's name in a one-to-one, where repeating it on both sides would be noise — and your own messages read "You". Tapping any result, photos and videos included, lands in that conversation *at that message* instead of at the bottom of it. (`17cb1c3`)

## [1.22.0] — 2026-09-07

### Added

- **Send more than one photo at a time.** The gallery button in a chat only ever let you attach a single image — a second one meant going back and doing the whole thing again. You can now pick up to ten at once. They open in a preview you can swipe through, with a filmstrip along the bottom to jump between them and an × on each to drop one you didn't mean to include, so a mis-tap no longer means cancelling and re-picking the lot. Each image gets **its own caption**: the caption box always writes to the picture you're looking at. Pinch-to-zoom still works on every image, and a pinch now zooms rather than flicking you to the next one. The batch is sent in the order you arranged it, one image at a time — sending them all at once was what made the app run out of memory on a large selection. (`bde8a30`)

### Fixed

- **"Clear checked" (and other list changes) sometimes needed leaving the list and coming back.** The list detail screen keeps its own copy of the items so a row can follow your finger while a drag is still being saved. That copy could get stuck: once you had dragged anything, an update arriving in the wrong moment was discarded outright rather than applied a beat later, so clearing the checked items — or an item added on another device — left the list looking exactly as it was until you backed out and reopened it. The items were genuinely gone the whole time; only the screen disagreed. Content changes now always win over the drag copy, and anything that arrives mid-drag is applied as soon as the drag ends. (`cedbc22`)
- **Sending and receiving messages is no longer sluggish, and a new message can't get stuck behind delivery receipts.** Opening a chat with unread messages set off a burst of receipt writes, and every one of those came back to the app as a fresh copy of the *entire* conversation. The app then re-walked that whole conversation from the top on each copy — and, worse, threw away the pass it was in the middle of every time the next copy landed. In a busy moment the newest message sat at the end of a list the app never got to finish reading, so it simply didn't appear; sending another message into the chat was often what finally let the backlog settle, which is why replying seemed to "unstick" it. Now a pass always runs to completion, only messages that actually changed are looked at again, and the receipts go out together instead of one after another. Sending is quicker too: the send button no longer locks up until the network confirms — so a second message can be typed and fired straight away — the block-list lookup that used to sit in front of every send and every incoming batch is now remembered for a few seconds, and the chat-list preview is updated alongside the message instead of adding a second wait before the ✓ appears. That last one also fixes a message that had genuinely been delivered being marked as failed when only the preview update failed. (`cf1b678`)
- **Sharing several images into FireStream from another app could fail entirely, or arrive shuffled.** Three separate problems with the Android share sheet. One unreadable picture — a photo since deleted, or one the sending app no longer has permission for — used to sink the whole share: you got an error screen instead of the other nine images, which were perfectly fine. Those now come through, and only a share where *nothing* is readable reports a failure. Images also arrived in whatever order their uploads happened to finish rather than the order you picked them, and a large share started every upload simultaneously, each one decoding a full-size image, which is what made big shares run the phone out of memory. Finally, some apps describe a batch only as "images" without saying what kind; that placeholder was being passed along as the actual file type, which could leave an image unopenable at the other end. The share preview also shows every image now instead of stopping at the first eight. (`bde8a30`)
- **Shared Lists in a chat showed "0 items" for every list.** The Shared Lists screen (chat → ⋮ → Shared Lists) counted the items it had actually loaded, but that screen only ever loads each list's *metadata* — the items themselves live in a separate collection and are fetched when you open a list. So the count was always zero, no matter how full the list. It now reads the same stored item count the Lists tab does, and follows the same wording: checklists and shopping lists read "3/7 checked", generic lists read "7 items". (`a67ad40`)

## [1.21.0] — 2026-07-27

### Added

- **The `.command` palette now finds nested commands by their verb alone.** Typing `.set` used to come up empty, because `set` isn't a command in its own right — it lives under `.timer`, and the palette only ever searched the level you were standing on. It now falls back to searching the levels below: when nothing at the current level matches, the palette offers what it found further down, listed by full path (`.timer.set`) so it's clear where the command actually lives, and headed "Nested matches" so it's clear why a row you didn't type is there. Tapping one fills the composer with the full command. The level you're on still wins outright — as long as something there matches, nothing nested is shown — and a nested result never opens itself; it waits for a tap, so the palette and what you typed can't drift apart. Also fixes the palette claiming "No commands available" whenever a search missed, which was true of an empty app and of nothing else.

### Changed

- **The chat keyboard now offers a line-break key instead of a send key.** The Enter key in the message composer used to be a Send button, which meant a message could never contain a line break — pressing it fired the message off mid-thought. Enter now inserts a newline, so lists, addresses and paragraphs can be typed the way they read; sending is what the send button next to the composer has always been for. The same applies while editing a message, since it's the same field.
- **The long-press "Snooze" entry is now called "Reminder".** Every other surface of this feature already said *reminder* — the entry that replaces it once one is pending reads "Cancel reminder", the picker that opens is headed "Remind me…", the composer command is `.remind`, and the list in Settings is "Scheduled Reminders". Only the entry that starts the whole flow said "Snooze", which made it read like a separate feature. The two follow-on mentions moved with it: the empty Scheduled Reminders screen now points at "Reminder", and the Settings subtitle says "Message reminders you've scheduled".

## [1.20.0] — 2026-07-25

### Added

- **Choose how insistently a timer rings.** Setting a timer now offers three alarm styles alongside the duration. **Insistent** keeps ringing until you dismiss it — with a Dismiss button on the notification and an automatic cut-off after two minutes, so a timer that fires while the phone is in another room can't ring itself flat. **Normal** rings once and then nudges you twice more, a minute apart, but only for as long as you leave the notification untouched: tapping or swiping it stops the reminders. **Silent** stays quiet as before. You can also pick the sound — the alarm tone, your ringtone, or a gentle chime — and the alarm and ringtone options now vibrate for about seven seconds rather than three, while the gentle one stays a brief double-tap so it's actually gentle. The choice belongs to whoever sets the timer and travels with it, so a timer you share rings the same way on both phones; the sound travels as a *choice* rather than a file, so each phone plays its own version of it. (`5176172`, `0f67c0a`, `6a902ff`)

### Changed

- **Timer alarms now have three notification channels instead of one.** Android freezes a notification channel's sound and vibration when it's created and never lets them be edited, so offering a choice of sound means one channel per sound. The old single "Timer alarms" channel is removed on first launch and replaced by "Timer alarms", "Timer alarms (ringtone)" and "Timer alarms (gentle)" — if you had customised the old one in Android's settings, that customisation won't carry over. All three still play through the alarm stream, so a timer sounds even when the phone is set to vibrate-only. (`0f67c0a`)

### Fixed

- **Timers keep their alarm settings across a reboot.** Restarting the phone re-armed every running timer with its context stripped: a silent timer would start ringing, and tapping the resulting notification couldn't open the chat at all, because the re-armed alarm no longer knew who the conversation was with. A reboot now restores the whole alarm — style, sound, and chat partner. (`0f67c0a`)

## [1.19.0] — 2026-07-25

### Added

- **Swipe through a chat's photos from any image.** Opening a picture in a chat no longer shows just that one picture: the fullscreen view is now the same swipeable gallery as Shared Media, spanning every image in the conversation, opened at the one you tapped. Swipe left and right to move through them — oldest to newest, the same direction the chat reads — with pinch-zoom and the Save button following whichever photo is on screen. Closing the viewer lands the chat exactly where the photo you were looking at was sent, flashing that message so you can see where you came out; close without swiping and the list stays put, since you never left. Link-preview thumbnails still open on their own, as they aren't part of the chat's media. (`b699eb2`)

### Fixed

- **Timer notifications now jump to the timer that rang.** Tapping a "Timer ended" notification opened the chat but landed on the newest message, leaving you to find the timer yourself — in a busy conversation the bubble that just fired could be well out of view. The notification now carries the timer message's id, so opening it scrolls straight to that bubble and flashes it with the pink frame for 1.5 seconds, matching how reply previews, reaction cues, and reminder notifications already behave. (`09aabc0`)
- **Jump-to-reaction button now actually appears.** Third attempt at the in-chat reaction cues from 1.18.0: when someone reacted to one of your messages while you had the chat open, the pink jump-to-reaction button never showed up — 1.18.0 delivered the cue over a side channel and 1.18.3 rebuilt it as an on-screen diff, and neither reached the button. The cue now travels on the message state itself, alongside the very list it was detected from — the same state that draws the reaction chip you can already see — and the screen waits for the message list to be measured before deciding whether the reacted bubble is visible (flash it in place) or off-screen (raise the button). Previously that decision could be made before anything had been measured, which read as "visible" and silently swallowed the cue. Delivery now has direct test coverage, which is what was missing both earlier times. (`dc1dd24`)
- **Jumping to a message now flashes every bubble type.** Tapping the pink jump-to-reaction button, a reply preview, or a reaction notification highlighted the destination with the pink frame only for ordinary bubbles — shared lists and polls render their own surfaces and never received the highlight at all, so jumping to one landed with no visual cue. All bubble types now flash identically. The 1.5-second flash also starts on arrival instead of at the moment you tap, so the whole window is spent looking at the message rather than being partly consumed by the scroll. (`3309b41`)
- **The jump-to-reaction button scrolls smoothly.** It snapped to the reacted message instead of gliding, unlike the scroll-to-bottom button right beside it. Both now use the same animated scroll. Opening a chat from a notification still positions instantly, where an animated sweep away from the just-restored position would look like a glitch. (`3309b41`)

### Changed

- **Reaction cues now fire in both directions.** A reaction only raised an in-chat cue when someone reacted to a message *you* sent; reactions on the other person's own messages passed silently, even though the push notification for them was already being sent in one-to-one chats. Any reaction another person adds now raises the cue regardless of whose message it landed on, so the in-chat behaviour matches the notification. Your own reactions still never cue you. (`3309b41`)

## [1.18.4] — 2026-07-24

### Fixed

- **Reaction notifications now jump to the reacted message.** Tapping a push notification for a reaction (received while the app is closed or in the background) opened the chat but landed on the newest message instead of the one that was reacted to. The reaction's message id — already sent in the push payload — is now carried into the tap, so opening the notification scrolls straight to that bubble and highlights it with the pink frame, matching how message and reminder notifications already behave. (`3f465c9`)

## [1.18.3] — 2026-07-24

### Fixed

- **In-chat reaction cues now actually fire.** The cues added in 1.18.0 (pink bubble flash / jump-to-reaction button) never triggered on-device: detection ran through a separate event channel that didn't reach the screen. Detection now runs directly off the rendered message list — the same state that already shows the reaction chip — so a reaction on your message reliably flashes the bubble when it's visible or raises the pink up/down button when it's off-screen. Also hardened against firing for pre-existing reactions when a chat first opens. (`4cc5b0b`)

## [1.18.2] — 2026-07-24

### Fixed

- **Shared media stops re-downloading old images.** Opening a chat's Shared Media gallery now guarantees a durable on-disk copy of every image it shows, so remote-only pictures (older photos that were never saved locally, or whose cached file was evicted) no longer get re-fetched from the network on every re-entry. The backfill runs the moment the gallery opens and — unlike the background auto-download — ignores the auto-download preference, since the grid is already fetching those files over the network to render them. (`0805772`)

## [1.18.1] — 2026-07-24

### Fixed

- **Cached profiles no longer flicker when opening a chat.** Entering a 1:1 chat with someone you've seen before now paints their name and avatar instantly from the local cache, instead of briefly showing the generic "Chat" title and a blank person-icon avatar until the live profile stream round-trips from Firestore. The top bar is seeded straight from the Room cache on open and the live stream still keeps it current. (`aa10b3b`)

## [1.18.0] — 2026-07-23

### Added

- **In-chat reaction cues.** When someone reacts to one of your messages while you're in the chat, you now get a visible cue instead of nothing. If the reacted message is on screen, its bubble briefly flashes the same pink highlight border you get when tapping a reaction notification. If it's scrolled off screen, a pink jump-to-reaction button appears in the bottom-right — sharing the scroll-to-bottom button's spot when you're at the bottom and lifting just above it when you're scrolled up — with its arrow pointing up or down toward the reacted message; tapping it scrolls there and flashes the highlight. Only reactions others add to your own messages trigger a cue. (`78c95b8`)

## [1.17.3] — 2026-07-23

### Fixed

- **Profile Shared Media thumbnails no longer go black.** The Shared Media section on the user profile / chat-detail screen (reached by tapping a chat's avatar/name) had its own grid that still used the plain decode path, so its tiles for large old images stayed black even after the standalone Shared Media screen was fixed. Both grids now render through one shared `SharedMediaTile` composable that decodes via Android's `ImageDecoder`, so the two look identical and neither goes black. A shared video keeps showing its thumbnail (the decoder falls back to Coil's default for video sources). (`5815b28`)

## [1.17.2] — 2026-07-23

### Fixed

- **Fullscreen shared media now actually swipes.** The swipeable gallery added in 1.17.0 never paged — the fullscreen image's transform-gesture detector consumed every horizontal drag before the pager could see it, so left/right swipe did nothing. The image now only claims a gesture when it's a pinch or a pan while already zoomed; a plain single-finger swipe at 1× falls through to the pager and pages, while pinch-zoom, pan, double-tap zoom and tap-to-dismiss are unchanged. (`5315dad`)

## [1.17.1] — 2026-07-23

### Fixed

- **Shared Media thumbnails — the real fix.** The 1.16.2 attempt (disabling hardware bitmaps) did not resolve this — tiles for large, old images still rendered black. The true cause is that Coil's default `BitmapFactory` decoder reaches the small tile via a heavy power-of-two subsample, and that heavily-subsampled decode returns a black bitmap for certain large/camera-original images (the barely-downsampled fullscreen decode of the same image is fine, which is why tapping always worked). Grid tiles now decode through Android's `ImageDecoder`, which does a proper high-quality scaled decode instead of the subsample that goes black — still a small, memory-cheap bitmap. (`394e402`)

## [1.17.0] — 2026-07-22

### Added

- **Swipeable fullscreen shared media.** Opening a shared-media image fullscreen — from a chat's Shared Media screen or a contact's profile — now lets you swipe left/right through the whole gallery instead of dismissing and tapping the next thumbnail. Pinch/double-tap to zoom still works, and paging pauses while an image is zoomed so you can pan freely. (`cbe935c`)

## [1.16.2] — 2026-07-22

### Fixed

- **Shared Media thumbnails no longer go black.** In a chat's Shared Media gallery, some tiles — mostly large, older images sent before on-send compression — rendered as solid black even though tapping opened the correct full image. The grid handed full-resolution images to Coil with hardware bitmaps enabled, and a fast-scrolled 3-wide grid exhausted the process hardware-bitmap budget. Grid tiles now decode with hardware bitmaps disabled (still downsampled to tile size), prefer the already-downloaded on-disk copy when present, and show a broken-image icon instead of black if a load genuinely fails. (`bc9d6bc`)

## [1.16.1] — 2026-07-22

### Fixed

- **Instant profile avatars.** Avatars in the chat list, contacts, calls, group screens and profile no longer pop in after a delay. A configured Coil image loader now keeps each decoded avatar warm in memory under a stable per-photo cache key, so an avatar is decoded once and rendered instantly on every subsequent appearance — with a smooth crossfade instead of a blank-then-flash — and only re-fetches when the user actually changes their photo. (`4ac4bff`)

## [1.16.0] — 2026-07-19

### Added

- **Message reminders (snooze).** Long-press any message → Snooze → pick a preset (In 1 hour · This evening · Tomorrow morning), a custom date & time, or — when the message itself mentions a time like "tomorrow at 5pm" — an automatically detected preset. At the chosen time a local notification shows the sender and message, with "+1 hour" and "Done" actions; tapping it opens the chat, scrolls to the message, and highlights it. Pending reminders show a bell on the bubble, can be cancelled from the same menu, are listed in Settings → Scheduled Reminders (tap to jump, swipe to cancel), survive reboots, and can also be set via the `.remind` composer command targeting the reply-target or newest message. Reminders are device-local, like starred messages. (`dd519e5`, `2bd57c1`, `82e24f2`, `b4f2f4d`, `9315e43`, `7a4e73f`, `a3669f5`)
- **Notification taps jump to the exact message.** Tapping a push notification (and any reminder notification) now scrolls to and highlights the specific message instead of just opening the chat — including when the app is already open, which previously dropped the tap silently. (`239f0a9`)

## [1.15.0] — 2026-07-19

### Changed

- **Reactions now overlap the bubble.** Emoji reaction chips used to float fully below the message bubble with a small gap; they now sit tucked 5dp up into the bubble's bottom edge for a more compact, attached look. The duplicated chip-rendering code in `MessageBubble` and `ListBubble` was also consolidated into a shared `ReactionRow` composable. (`9e1b373`)

## [1.14.0] — 2026-07-18

### Changed

- **Seamless emoji panel.** The keyboard now slides down to reveal the emoji panel sitting beneath it and slides back up over it, WhatsApp-style — the panel matches the keyboard's height and stays mounted, replacing the old collapse-and-reopen jump. Tapping the text field with the panel open raises the keyboard over it, and back closes keyboard → panel → chat in order. (`7f75c83`)

## [1.13.0] — 2026-07-18

### Added

- **Video sharing.** Chats can now share video — record with the camera or pick one from the gallery. Videos are transcoded to a configurable quality (480p/720p/1080p in Settings, default 720p) with a 3-minute/100 MB limit to keep sends fast and storage reasonable. Bubbles show a thumbnail with a play button and the clip's duration; tapping opens a fullscreen player. (`3b1344a`, `feddc46`, `e2ff3f8`, `7690fd6`, `d44ecc8`, `da4603c`)

## [1.12.3] — 2026-07-17

### Fixed

- **Chat opening is content-first again.** The 1.12.2 change held the message list behind a spinner until the slide animation finished — it felt slower, not smoother, and is reverted. Messages now appear the moment they're loaded (even mid-slide, as before), and the two real defects are fixed structurally instead: the saved scroll position is resolved *before* the list's first visible frame (no more populate-then-jump — the chat opens already scrolled to where you left off), and the first population is cheaper because the per-message appearance fade no longer runs for every item at once. The cold-start splash hold and the subtle resume fade from 1.12.2 remain. (`c82703f`)

## [1.12.2] — 2026-07-15

### Fixed

- **Reopening the app is now genuinely smooth.** The previous no-slide-on-resume fix replaced the animation with a hard snap that exposed the chat mid-load: a spinner, the message list popping in, and a visible jump to the saved scroll position. The app now holds the system splash screen until the restored chat (or list) is fully settled — populated and scrolled — and reveals it with a short fade, so a process restart lands directly on the finished screen with no flashes or jumps. (`5dca20e`)
- **Opening a chat from the chat list no longer stutters.** The slide animation used to drop frames because the entire message list composed in one heavy frame mid-slide. The list now waits for the slide to finish, then fades in already scrolled to the right position; returning to a chat from message info or a profile still shows its content immediately. (`5dca20e`)

## [1.12.1] — 2026-07-14

### Fixed

- **No more slide animation when the app reopens where you left off.** Restoring the last-open chat or list on app launch (and the automatic login redirect to the chat list) replayed the forward slide transition, as if you had just navigated there. Restored screens now snap into place instantly — the slide animation only plays for actual in-app navigation: tapping a chat, going back, or opening a chat from a notification. (`ab42038`)

## [1.12.0] — 2026-07-13

### Added

- **Auto-download app updates over Wi-Fi.** A new opt-in Settings toggle ("Auto-download updates on Wi-Fi", off by default) lets the daily update check download a newer release APK automatically in the background whenever you're on an unmetered network, so it's already on-device when you choose to install. When enabled the "update available" notification is suppressed — you get a single "Update ready — tap to install" notification once the download finishes. Nothing installs silently: tapping still opens the system installer as before. (`fb03109`)
- **Emoji/keyboard toggle in the chat composer.** With the emoji panel open, the emoji button turns into a keyboard button that swaps the panel back for the keyboard. Tapping the text field now also replaces the panel with the keyboard instead of stacking both on top of each other and eating most of the screen. (`65883c7`)

## [1.11.1] — 2026-07-12

### Fixed

- **Emoji size changes now stick when editing a message.** Editing a message and resizing an emoji (or adding an enlarged one) silently kept the old size — the edit only saved the new text and dropped the size information on the way to the server and the local database. The composer now also picks up the message's existing emoji sizes when an edit starts, so previously enlarged emoji keep their size through an edit instead of snapping back to standard. (`d124ac1`)
- **Fullscreen image viewer rotation fix, hardened.** The chat viewer's open/closed state moved from screen-local compose state into the ViewModel (`ChatUiState` overlays slice), which Android retains across rotation by object identity — closing the viewer on rotate is now impossible regardless of saved-instance-state behavior. Note: no APK release has shipped since `v1.9.7`, so neither this nor the 1.11.0 fix is on any installed build yet. (`7f7acfb`, `3e71acf`)

## [1.11.0] — 2026-07-11

### Added

- **Reaction notifications.** Reacting to a message with an emoji now sends the other side a push notification ("Reacted 👍 to your message"). In 1:1 chats the other person is always notified — including when you react to your own message; in group chats only the author of the reacted-to message is notified (reacting to your own message in a group stays silent). Removing a reaction sends nothing, changing your reaction notifies again; muted chats and the currently open chat suppress the notification as usual. Firebase flavor only — reactions are not yet implemented in the PocketBase flavor. (`603f376`)

### Fixed

- **Fullscreen image viewer survives rotating the phone.** Rotating while viewing an image fullscreen used to silently drop you back into the chat (activity recreation wiped the viewer's non-saved state) — the viewer now stays open across rotation in every place it's used: chat images and link previews, chat-list avatars, profile avatar and shared media, group avatar, the shared-media grid and the share picker. (`9320acb`)
- **Keyboard retracts when an image goes fullscreen.** Tapping an image while typing left the keyboard floating on top of the fullscreen view; it now always slides away when the viewer opens. (`9320acb`)
- **Presence-sync cloud function now deploys.** The `syncPresenceToFirestore` RTDB trigger defaulted to `us-central1` — where no Realtime Database instance exists — so the deploy failed and abrupt-disconnect presence (going offline when the app is killed) never mirrored to Firestore. The trigger is now pinned to `europe-west1`, co-located with the RTDB instance, so it deploys and the offline indicator updates reliably again. (`470956d`)

## [1.10.7] — 2026-07-10

### Fixed

- **Composer could become untypeable after dictation.** An intermittent state where only the first typed letter registered — no further typing, no deleting, surviving app restarts until a phone reboot — traced back to the dictation path: a stale listening flag (or a leaked system speech-recognizer session) kept rewriting the composer text on every recognition event, and the cancel-on-type escape hatch silently did nothing once the session reference was gone. Stop/cancel now always reset the dictation state, a watchdog force-ends sessions the system recognizer never closes, recognizer instances are capped and torn down defensively, stale recognition events can no longer touch the text field, and the keyboard hides while dictating. (`365af81`)
- **Reopening the app now reliably lands exactly where you left off.** The restore-last-location feature used to wipe its own saved state on the way back in: the chat list cleared the remembered chat and scroll position during the cold-start pass-through, so the restored chat opened at the newest message instead of your last position — and the *next* launch didn't restore at all. Entering any chat now durably marks it as the restore target, and the saved location is only cleared when you genuinely return to the chat list. (`2f07082`)

## [1.10.6] — 2026-06-10

### Changed

- **Send-path failures are handled consistently and no longer swallowed silently.** Internal refactor of `MessageRepositoryImpl` (code-quality review item #1): the six copy-pasted send-failure handlers collapse into one wrapper that logs the error, flips the message to FAILED so tap-to-retry reappears, and still leaves a coroutine-cancelled row in the sending state for orphan recovery. Previously-silent catches across the Signal-init, block-list, decrypt, sync, receipt, search, broadcast-fan-out and media-download paths now log instead of swallowing, and unknown backend enum values are logged before defaulting. No behaviour change beyond more reliable failure reporting. (`a0bdb5c`)
- **Regression guard for the shared-list sync race.** Added a white-box tripwire test (code-quality review item #4 / M4) asserting the three mutex-guarded Room writes in `ListRepositoryImpl`'s sync paths — both `observeList` listeners and the `observeMyLists` merge — stay inside their per-list lock, so the `eed7519` race (a freshly added list item silently lost to a stale-cache write) can't be re-introduced unnoticed. Test-only; no behaviour change. (`cb796cb`)

## [1.10.5] — 2026-06-08

### Fixed

- **Messages no longer get stuck "sending" forever after you leave a chat mid-send.** A send whose coroutine was cancelled when you navigated away from the chat was left in the sending state indefinitely — never retried, never marked failed — so the message was silently dropped with no way to recover it. Orphaned sends are now flipped to the failed state on app start and on chat re-entry, restoring the tap-to-retry button on the bubble. (Auto-retry, reconnect flush and an offline banner are tracked as the deferred durable-outbox follow-up in `TECH_DEBT.md`.) (`80e4ed1`)

## [1.10.4] — 2026-06-08

### Fixed

- **Recents row no longer feels stuck while the emoji picker is open.** Previously the recently-used row only reordered after the picker closed — a 3-second tap-idle debounce in `ChatInfoManager` that, in reaction mode, always fired after the sheet was already gone. The picker now snapshots the Recents order once when it opens and keeps it stable for that session (no reflow under your finger), while the data layer stays live so each fresh open reflects your latest taps immediately. (`08fe2b1`)

## [1.10.3] — 2026-06-08

### Changed

- **Emoji picker glyphs scaled down slightly.** Reduced `EMOJI_FILL_FRACTION` from 0.8 to 0.7, so each emoji fills 70% of its cell instead of 80% — a bit more breathing room between emojis in the picker grid. (`81728d3`)

## [1.10.2] — 2026-06-08

### Changed

- **Emoji picker reverted to 8 columns.** The grid goes back to 8 columns; the relative glyph scaling stays, so emojis are simply a little smaller per cell while still filling the available space with a small gap. (`c032109`)

## [1.10.1] — 2026-06-08

### Changed

- **Emoji picker glyphs now scale to fill their cells.** Instead of a fixed `22.sp` size, each emoji is sized to ~80% of its (square) grid cell via `BoxWithConstraints`, so emojis appear as large as the space allows while keeping a small gap between neighbours — matching the WhatsApp picker. (`a7ff5b7`)

## [1.10.0] — 2026-06-08

### Changed

- **Emoji picker now shows 7 columns instead of 8.** Dropping one column widens each grid cell, so emojis render larger and are easier to identify at a glance. (`3138678`)

## [1.9.7] — 2026-06-04

### Fixed

- **Date separator no longer slips between two consecutive messages.** When two messages were sent in quick succession, the day label (e.g. "Today") could render *between* them instead of above the first. The separator and the message bubble were emitted as two sibling composables inside a single `reverseLayout` `LazyColumn` item, and Compose places an item's sibling nodes bottom-to-top under reverse layout — flipping the separator below its bubble. Both are now wrapped in one `Column`, so the separator stays pinned above the day's first message. (`97c06df`)

## [1.9.6] — 2026-05-14

### Fixed

- **Pinch-to-zoom now anchors at the pinch centroid in both image viewers.** Previously, zooming with two fingers always scaled around the image center regardless of where the fingers were placed, requiring a manual pan afterwards to inspect the intended region. `detectTransformGestures` exposes the midpoint between both fingers (`centroid`), which was being discarded (`_`). The offset is now computed so the content point under the centroid stays fixed as scale changes — `newOffset = centroid − center − (centroid − center − oldOffset) × (newScale / oldScale) + pan` — the same formula already used for double-tap zoom. Fixed in both `FullscreenImageViewer` (full-quality viewer) and `ImagePreviewScreen` (pre-send preview).

## [1.9.5] — 2026-05-14

### Fixed

- **`ChatInfoManagerRecentEmojiDebounceTest` failing in CI.** The test `subsequent emission does not update before 5 seconds` was asserting that state remained unchanged after 4,999 ms, but `ChatInfoManager.observeRecentEmojis` uses `delay(3_000L)` — so the update had already fired by 3 s and the assertion at ~5 s always failed. Aligned all four debounce test cases to the 3-second window: the guard threshold is now `advanceTimeBy(2_999L)` and the success paths remain unchanged.

## [1.9.4] — 2026-05-14

### Changed

- **Update notification now taps directly into download.** Previously tapping the \"update available\" notification opened Settings at the top, requiring the user to scroll to the bottom, tap \"Check for updates\", wait for the network check, and then confirm the download. The notification now carries a `focusUpdate` flag that makes Settings auto-scroll to the update row and immediately fire the update check on arrival — the \"Update available\" dialog appears automatically and the user only needs to tap \"Update now\" to start the download. Notification copy updated from \"Open Settings → Check for updates to install\" to \"Tap to download and install\" to reflect this.

## [1.9.3] — 2026-05-14

### Fixed

- **Flaky `ChatInfoManagerRecentEmojiDebounceTest` in CI.** The two tests asserting state after a 5-second debounce window (`subsequent emission updates after 5 seconds`, `rapid emissions debounce — only last value applied after 5 seconds`) were failing non-deterministically on the GitHub Actions runner. With `StandardTestDispatcher`, `advanceTimeBy(5_000L)` completes the `delay` but the coroutine continuation (`_uiState.update`) is queued for the next dispatch cycle — the assertions ran before the state was applied. Added `advanceUntilIdle()` after each `advanceTimeBy` call so all pending coroutines are drained before asserting.

## [1.9.2] — 2026-05-14

### Fixed

- **Chat list ordering is now owned by `ChatListViewModel` instead of the Composable.** The sort key (`lastMessage?.timestamp ?: createdAt`, descending) was duplicated in `ChatListScreen` and `ChatDao.getAllChats`, with no ViewModel-level test to catch a drift between them — a regression in either layer could ship without unit-test coverage. The ViewModel now sorts the chat flow upfront and the screen relies on `filter` preserving that order, so the "newest activity at the top" guarantee is a single, testable contract. Added `ChatListViewModelTest` cases for out-of-order emissions, an incoming message bubbling its chat to the top, and the `createdAt` fallback for empty chats.

## [1.9.1] — 2026-05-13

### Fixed

- **Recent emojis no longer jump immediately on tap.** Every tap was calling `addRecentEmoji`, which triggered an instant DataStore write → `recentEmojisFlow` re-emission → recomposition, causing the grid to reorder mid-interaction. The observer now uses `collectLatest` with a 5-second `delay` as a trailing-edge debounce: rapid taps keep resetting the clock, and the grid only reorders once tapping pauses for 5 seconds. The initial load when the picker opens remains immediate.
- **Profile pictures no longer re-fetch from the cloud every time a screen opens.** The avatar download-and-cache pipeline (`ProfileImageManager` → Room `localAvatarPath`) was already in place and auto-downloading on URL change, but eight UI surfaces bypassed it — four called `UserAvatar` without passing `localAvatarPath` (AvatarStack, ForwardChatPicker, ListShareSheet, SharePickerScreen) and four reached for the remote `avatarUrl` directly via `AsyncImage` (Settings, CallScreen, CreateBroadcast, CreateGroup). Each open paid a network round-trip even when a current local copy was on disk. All eight now resolve via `resolveAvatarModel(localAvatarPath, avatarUrl)`, preferring the cached file. `CallService` now also stamps a `localAvatarPath` onto each `CallState` so the call UI's letter-fallback layout reads from disk too.

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
