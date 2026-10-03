# Stickers and GIFs

Status: approved, no step started. The prototype's verdict is variant A, the island panel, and step 5 is written to it.

## Context

The only stickers in the app today are twelve drawn marks that can be placed on a photo in the
image editor. Nothing can be sent as a sticker or a GIF: every image send is re-encoded to JPEG
(`ImageCompressor`, `OutboxSender.encodeToLocalFile`), which drops transparency and animation, and
Coil has no animated decoder.

The owner wants sticker handling at the level of WhatsApp and Telegram, GIF support, and a way to
bring over the stickers already used in WhatsApp, reached from a button in Settings.

## Decisions (agreed with the owner, 2026-10-03)

| Question | Decision |
|---|---|
| Sticker scope | Packs, favourites, recents, emoji search · make your own · online catalogue · Lottie stickers |
| Import entry point | A Settings item, **Import stickers**, opening the sticker library screen |
| Import sources | WhatsApp's sticker folder (one folder grant, multi-select grid) and files (`.webp`, `.wastickers`, later `.was` / `.tgs`) |
| Library backup | Packs and favourites are saved under the account and restored on login |
| GIF sources | Keyboard insertion and an in-app GIFs tab backed by Klipy |
| Provider privacy | Search and media go through a Cloud Function. The provider never sees a user's IP, and the key lives in Functions secrets. The pocketbase flavor gets no GIFs tab and no online catalogue |
| Recipient privacy | Unchanged from `docs/BACKLOG.md` §4.6: the recipient fetches from Storage only |

## The model

- **A sticker is an immutable file named by the SHA-256 of its bytes.** Locally it is
  `filesDir/stickers/<id>.<ext>`. Remotely it is the Storage object `stickers/<id>.<ext>`,
  create-only, uploaded the first time it is sent or backed up and never again.
- **A message points at a sticker.** `type = STICKER` with new nullable `stickerId` and
  `stickerPackId`, plus `mediaUrl`, `mimeType`, dimensions, and the first emoji as `content`.
  A repeat send uploads nothing; a repeat receive downloads nothing.
- **A pack is an ordered list of sticker ids owned by one user.** Kinds: `USER` (made or imported),
  `INSTALLED` (copied from someone, keeps `originPackId`), `FAVOURITES`, `SAVED` (loose stickers).
  Firestore `stickerPacks/{packId}` holds the manifest. Any signed-in user who has the id can read
  it; only the owner writes it. That one collection is both the backup and what "view this pack" reads.
- **Room is a cache of the library** (`stickers`, `sticker_packs`, `sticker_pack_items`).
  `AppDatabase` is destructive on a version bump, so Firestore restores it; rows not yet synced
  carry a `syncState`. Recents stay in DataStore, device-only.
- **A GIF is a plain media message.** `type = GIF`, bytes untouched, one upload per message, stored
  in `DocumentFiles` so it never reaches the gallery.
- **Animated decoders are attached per request**, mirroring `ui/components/VideoFrameRequest.kt`.
  A global registration would start animating link previews and avatars.

## What WhatsApp import can and cannot reach

- **Can:** `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/` (and `com.whatsapp.w4b`).
  It holds a file for every sticker seen in a chat, sent or received.
- **Can:** pack grouping and emojis, where the file carries them. A WhatsApp WebP embeds
  `sticker-pack-id`, `sticker-pack-name`, `sticker-pack-publisher` and `emojis` in its EXIF chunk.
  Files without it land in one *WhatsApp* pack.
- **Cannot:** favourites, recently-used order, and packs downloaded but never used.
- **Cannot:** other sticker apps' content providers (`com.whatsapp.sticker.READ` is believed to be
  signature-level). Not attempted.

## Open risks, each with the step that settles it

1. **Folder access, and how many of the owner's files carry pack metadata, are unverified.**
   Pre-flight below, then the checkpoint after step 2.
2. **Storage rules cannot check that a file matches its name.** A signed-in user could claim a hash
   with wrong bytes. Clients verify the hash on download and refuse a mismatch. Accepted for a
   closed user base; recorded in `TECH_DEBT.md` in step 3.
3. **Storage rules are not in the repo.** The owner adds the `stickers/` rule in the console (step 3).
4. **The `.was` container format is unconfirmed.** Step 7 opens with a spike on the owner's real files.
5. **ML Kit subject segmentation is a beta API** delivered through Play services, with a model
   download on first use (step 8).
6. **`Modifier.contentReceiver` on the value-based `BasicTextField`** (`ChatScreen.kt:1929`) is
   unverified. Samsung's stock keyboard is reported to refuse content in Compose fields (step 9).
7. **Klipy's response shape, CDN hosts, `customer_id` rule and re-hosting terms** could not be read
   from its docs in planning. Step 10 confirms them first; terms that forbid re-upload are a `needs_decision`.
8. **Old builds show the new types as an empty text bubble** (`parseMessageType` falls back to `TEXT`).
9. **Bytes sit unencrypted in Storage**, like every file today, and a pack manifest is readable by
   any signed-in user who has its id.
10. **This is eleven steps.** Run it one checkpoint at a time.

**Pre-flight (owner, 30 seconds):** in the phone's Files app open
`Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` and confirm it lists sticker files.

## Prototype (done, throwaway branch, not a runner step)

**Question:** how are stickers and GIFs reached from the composer?

**Verdict:** variant A, the island panel. The owner tried all three in the chat screen and chose it.
Step 5 carries the decision. The rest of this section describes what was built, so the branch can be
read later.

- **Where:** branch `prototype/sticker-gif-picker` in its own worktree (copy `local.properties` and
  `google-services.json` in; never `./gradlew --stop` from it). It is never merged into main.
- **Mounted in the real chat screen**, at the composer row and its panel mount (`ChatScreen.kt:2074`),
  gated on `BuildConfig.DEBUG`. Files in `ui/chat/prototype/`: `StickerPickerPrototype.kt` (switcher,
  stand-in data), `VariantAIsland.kt`, `VariantBTypeToFind.kt`, `VariantCDrawer.kt`.
- **A · Island panel** (the owner's WhatsApp reference): the existing `PickerPanel` shell with three
  tabs. Search button left, Emoji / GIFs / Stickers island, delete right, a pack row, then a grid.
  The panel replaces the keyboard.
- **B · Type to find:** the keyboard stays. `.gif <query>` or `.sticker <query>` in the composer, or
  one emoji alone, fills a sideways-scrolling results strip above it. No pack browsing.
- **C · Media drawer:** a new sticker button in the composer opens a tall sheet with one search across
  stickers and GIFs, a shelf per pack, trending GIFs, and a drag to full screen. The emoji panel
  stays emoji-only.
- **Stand-in content:** the bundled vector pack through `drawSticker` for stickers, placeholder tiles
  of varied aspect ratio for GIFs. No network, nothing sent. A pick shows as a stand-in bubble with no
  bubble background, so the in-chat look can be judged too.
- **Switcher:** a floating pill `< A (Island) >` under the top bar (the bottom is what is being
  judged), state in `rememberSaveable`.
- **Run:** `ANDROID_SERIAL=emulator-5554 ./gradlew installFirebaseDebug`, open any chat. A screenshot
  of each variant goes to the owner; the same build runs on the phone.
- **Not owed:** tests, CHANGELOG, docs. It must only compile (`assembleFirebaseDebug`). Load the
  `app-ui-design` skill so the variants look like this app.
- **Capture:** one commit on the throwaway branch (`e1338e0f`). The verdict is in step 5 below.

## Steps

Order: 1 → 2 ‖ 3 → 4 → 5 ‖ 6 ‖ 7 → 8 ‖ 9 → 10 ‖ 11

Every step follows CLAUDE.md's post-step workflow (tests, `./gradlew test`, `./gradlew assembleDebug`,
review skills, one commit, docs). UI steps load the `app-ui-design` skill. User-visible steps get a
CHANGELOG entry and a bump through the `changelog-release` skill.

### 1. Sticker library: packs, files, importers — model: strong, skills: code-review

Untrusted files and archives are parsed here.

- `domain/model/Sticker.kt`, `StickerPack.kt` (kinds above, `StickerFormat`), and
  `domain/repository/StickerRepository.kt`: observe packs and recents, `listWhatsAppFolder(treeUri)`,
  `importFrom(uris): ImportResult`, favourite, rename / reorder / delete pack, move and remove
  stickers, `markUsed`. URIs cross the boundary as strings.
- Room: `StickerEntity`, `StickerPackEntity`, `StickerPackItemEntity`, `StickerDao`. `AppDatabase` 29 → 30.
- `data/sticker/StickerFiles.kt`: the content-addressed directory. Streams to a temp file while
  hashing, checks the format and a 1 MB cap, renames into place.
- `domain/util/WebpContainer.kt`, pure: RIFF chunk walk, the `VP8X` animation flag, dimensions, the
  raw EXIF chunk. `data/sticker/WaStickerMetadata.kt`: reads the pack fields out of that chunk and
  tolerates their absence.
- `data/sticker/WhatsAppStickerFolder.kt`: one `DocumentsContract.buildChildDocumentsUriUsingTree`
  query (`DocumentFile.listFiles` is too slow for thousands of entries), newest first.
- `data/sticker/StickerPackArchive.kt`: `.wastickers` / zip with caps on entry count, bytes per
  entry and total bytes. Entry names are never used as paths. Reads `title.txt` and `author.txt`.
- `data/repository/StickerRepositoryImpl.kt` with an AGENT-NOTE header. Groups an import by pack
  metadata, de-duplicates by hash, reports counts. No remote yet.
- Tests: `WebpContainerTest` and `WaStickerMetadataTest` (byte fixtures, including truncated and
  metadata-free files), `StickerFilesTest`, `StickerPackArchiveTest` (each cap), a repository test
  for grouping, dedup and pack edits.

### 2. Settings → Import stickers, and the library screen (UI)

- `Routes.STICKERS` and its `NavGraph.kt` destination. A `SettingsItem` titled **Import stickers**
  beside *Auto-download Media* in `ui/settings/SettingsScreen.kt`.
- `ui/stickers/StickerLibraryScreen.kt` + ViewModel: packs with icon, name and count; a pack's grid;
  rename, reorder and delete packs; move and remove stickers.
- **From WhatsApp:** `OpenDocumentTree` with the initial URI, `takePersistableUriPermission`, then
  `WhatsAppImportScreen.kt`: lazy grid grouped by pack, multi-select, *Select all*, *Import N*.
  One line says what the folder holds and that favourites are not available.
- **From files:** `OpenMultipleDocuments`, validated after the pick.
- Launchers stay in the composable, as in `ChatScreen.kt`. The ViewModel sees `StickerRepository`
  only, so the UI→data allowlist in `ArchitectureTest` is untouched. Errors are `AppError`.
- Tests: `StickerLibraryViewModelTest`, one Robolectric test for the empty state.
- Docs: new *Stickers & GIFs* section in `docs/FEATURE-MAP.md`.

**‖ Checkpoint.** The owner imports from the real folder and reports whether packs came out grouped.

### 3. `STICKER` and `GIF` as messages — model: max, skills: code-review

The outbox, the sync path and a new storage model change here.

- `MessageType` gains `STICKER`, `GIF`. `Message`, `MessageRecord`, `RawMessage`, `MessageWriter` and
  both message sources gain `stickerId`, `stickerPackId`. `AppDatabase` 30 → 31.
- `data/remote/source/StickerObjectSource.kt`: `ensureUploaded(id, ext, mimeType, file): url`.
  `FirebaseStickerObjectSource` looks the object up and uploads only when it is missing. A pocketbase
  implementation that compiles and uploads through its existing storage.
- `MessageRepository.sendStickerMessage(chatId, stickerId, packId)`. The repository resolves the
  library row; the UI never passes a file. `sendGifMessage(chatId, uri, mimeType, caption = "")`
  with an 8 MB guard before the optimistic insert.
- `OutboxSender`: both types join `SENDABLE_TYPES`. A sticker takes its `mediaUrl` from the library
  row or from `ensureUploaded`, persisted on both rows. A GIF takes the document route: bounds into
  `mediaWidth` / `mediaHeight`, no encode, upload under `row.mimeType`, `keepDocument`. Update the
  step table in the KDoc.
- Receiving: a sticker downloads into `StickerFiles`, verified against `stickerId`. A mismatch keeps
  the remote render and logs. `MediaFileManager.downloadFor` routes GIFs to `DocumentFiles`.
- `OutboxJob.UPLOAD_TYPES` (GIF only), `AUTO_DOWNLOAD_TYPES`, and the three type lists in `MessageDao`.
- Labels: `lastContentFor` in both flavors, `FCMService`, `MessageTypeLabel.placeholderLabel`, and
  the `when`s in `SnoozePickerSheet`, `ForwardMessagePanel`, `ChatMessageActions.snapshotContentFor`,
  `StarredMessagesScreen`, `RemindWidget`.
- Tests: `OutboxSenderTest` (second send of a sticker uploads nothing; GIF bytes unchanged and the
  compressor never called; resume points), `MessageDaoOutboxQueueTest`, repository tests for both
  sends and the guard, `MessageRepositoryForwardTest`, `FirestoreMessageSourceTest` (new fields),
  a hash-mismatch test.
- **Owner, before the device pass:** add to the Storage rules in the console —
  `match /stickers/{file} { allow read: if request.auth != null; allow create: if request.auth != null && resource == null && request.resource.size < 1024 * 1024; }`

### 4. Bubbles for stickers and GIFs (UI)

- `coil-gif` in `gradle/libs.versions.toml` and `app/build.gradle.kts`.
  `ui/components/StickerImage.kt`: the one composable every surface draws a sticker with, attaching
  `ImageDecoderDecoder.Factory()` per request. Step 7 adds Lottie inside it.
- `MessageBubble.kt`: a `STICKER` branch with no bubble fill or tail, a square of at most 160 dp,
  time and ticks beneath. A `GIF` branch that reuses the `IMAGE` layout and caption with the animated
  request and a small GIF badge. New callbacks go into `MessageBubbleCallbacks`.
- Reply, forward and starred previews show the first frame.
- Test: one Robolectric test for the type dispatch.

### 5. Stickers tab in the composer (UI + state)

Stickers and GIFs are reached through the island panel. The emoji panel gains tabs and takes the
keyboard's place, as it does today. This is variant A of the prototype on branch
`prototype/sticker-gif-picker`; `ui/chat/prototype/VariantAIsland.kt` there is the reference for layout.

- The picker row keeps `PickerPanel`'s left-aligned order: search button, island, backspace key.
  A centred island with the two buttons pinned to the edges was tried and rejected by the owner.
- `PickerTab.STICKER_LIBRARY` and `PickerSelection.Sticker`. Refresh the `PickerTab` KDoc.
- `ui/chat/picker/StickerLibraryTab.kt`: a row of pack icons (Recents, Favourites, then packs), the
  active pack's grid with static thumbnails, long-press to favourite. An empty library shows an
  **Import stickers** button to `Routes.STICKERS`.
- Search: the query becomes emojis through the existing `EmojiSearchData` keyword table, then
  stickers tagged with them. `domain/util/StickerSearch.kt`, pure.
- Suggestions: when the composer holds exactly one emoji, a strip of matching stickers above it.
- `ui/chat/ComposerPickerPanel.kt` replaces `EmojiHandlerPanel(mode = TEXT_INPUT)` at the composer
  mount only (`ChatScreen.kt:2074`). The reaction sheet and caption bar stay on the alias.
- `ChatInfoManager` mirrors packs and recents into `OverlaysState` in one `.update {}`.
  `ChatMessageSender.sendSticker` sends and calls `markUsed`.
- Tapping a sticker bubble opens a sheet: **Add to favourites**.
- Tests: `StickerSearchTest`, `PickerPanelTest` (two tabs draw the island; one-tab hosts unchanged),
  `ChatInfoManager` and `ChatMessageSender` tests.
- Docs: rewrite `docs/BACKLOG.md` §4.6, add *Pending on-device verification* items.

**‖ Checkpoint.** Sending and receiving stickers is complete. Device pass between two accounts.

### 6. Backup, restore and sharing packs — model: max, skills: code-review

A sync engine and new security rules.

- `data/remote/source/StickerPackSource.kt`, `FirestoreStickerPackSource`, a pocketbase stub.
- `firestore.rules`: `stickerPacks/{packId}` — get for any signed-in user, list and write for the owner.
- `data/worker/StickerSyncWorker.kt` (unique work, connected): for each pending pack, `ensureUploaded`
  every sticker, then write the manifest. Deletes are written as deletes.
- Restore: a listener on the user's own packs upserts into Room, newer-only by `updatedAt`. Files are
  fetched when first displayed. Sign-out clears the library rows.
- The sticker sheet gains **View pack**: fetch the message's `stickerPackId`, preview it, **Add pack**
  copies it as `INSTALLED`. A pack already installed says so.
- Tests: the worker (pending → synced, each file uploaded once, delete), restore mapping and the
  newer-only rule, install and the already-installed case.

**‖ Checkpoint.** The owner deploys `firestore.rules`. Device: reinstall and confirm the library
returns; a second account adds a pack from a received sticker.

### 7. Lottie stickers

- **Spike first:** open real `.was` files from the owner's folder and confirm the container. If it
  cannot be read, ship `.tgs` only and report `needs_decision`.
- `lottie-compose` dependency. `StickerFormat.LOTTIE`. `data/sticker/LottieContainer.kt`: gzip or zip
  to the animation JSON, with a size cap and a parse guard.
- Importers accept `.was` and `.tgs`. A first-frame PNG thumbnail is written at import for grids.
- `StickerImage` switches on format, so the bubble, the picker and the library all render Lottie.
- Tests: `LottieContainerTest` (fixtures for both containers, oversize, not JSON), importer cases.

### 8. Make your own stickers

- `play-services-mlkit-subject-segmentation`. `ui/stickers/create/`: pick a photo, cut out the
  subject, toggle cutout / original, crop, outline on or off, choose emojis and a pack.
- `data/sticker/StickerEncoder.kt`: 512 × 512 WebP, quality lowered until it is under 100 KB.
  Segmentation and encoding run under `MediaProcessingLimiter`.
- Draw and overlay tools: `ImageEditRasterizer.rasterize` writes JPEG today (line 172). Give it an
  output format so a source with alpha stays PNG, then mount the existing Draw and Overlay screens on
  the cutout. If their hosts assume the pre-send preview too deeply for one step, ship without them
  and file it in `docs/BACKLOG.md`.
- Without Play services the flow skips the cutout and offers crop only.
- Entry points: **Create** on the library screen and a **+** in the picker's pack row.
- Tests: the encoder's size loop, the outline geometry (pure), the ViewModel.

**‖ Checkpoint.** Device pass for Lottie and the maker.

### 9. GIFs and stickers from the keyboard

- **Spike first:** confirm on the emulator that `Modifier.contentReceiver` delivers Gboard content to
  the composer's `BasicTextField`. Fallback: `InterceptPlatformTextInput` wrapping the input connection
  with `InputConnectionCompat` and `EditorInfoCompat.setContentMimeTypes`. If neither works, report
  `needs_decision`. Do not migrate the composer to `TextFieldState` in this plan.
- Routing: `image/gif` → `sendGifMessage`. Any other `image/*` from the keyboard is imported into the
  `SAVED` pack and sent with `sendStickerMessage`.
- `sendMediaMessage` sends an unedited `image/gif` as a `GIF`, which covers the gallery and the share
  sheet. An edited one is a JPEG by then and stays an `IMAGE`.
- Tests: the routing rule, and the `image/gif` branch in the repository test.

### 10. Media proxy Cloud Functions — model: strong, skills: code-review

Authentication and an outbound fetch on user-supplied input.

- Confirm against `docs.klipy.com` before coding: GIF and sticker search and trending endpoints,
  rendition fields, CDN host names, whether `customer_id` is required, attribution, re-hosting terms.
- `functions/mediaProxy.js`, exported from `index.js`:
  - `mediaSearch` (v2 `onCall`, auth required): `kind` is `gifs` or `stickers`, query or trending,
    key from `defineSecret("KLIPY_API_KEY")`, returns trimmed items. Any `customer_id` is an HMAC of
    the uid, never the uid.
  - `mediaFetch` (v2 `onRequest`): verifies the ID token, then streams one media URL. `https` only,
    host on an allowlist of the provider's CDN, redirects re-validated, byte cap, `image/*` only, a timeout.
- Tests: `node --test` over the pure parts; `functions/package.json` gets a real `test` script.
- Docs: `docs/CLOUD-FUNCTIONS.md`, and the function count in CLAUDE.md.

**‖ Checkpoint (owner).** Get a Klipy key, `firebase functions:secrets:set KLIPY_API_KEY`, deploy the
two functions. The `wizard` skill can script this.

### 11. GIFs tab and the online sticker catalogue

- `BuildConfig.SUPPORTS_ONLINE_MEDIA` per flavor, like `SUPPORTS_SIGNAL`.
  `data/remote/source/OnlineMediaSource.kt` with a Firebase implementation (the `firebase-functions`
  SDK is already a dependency, unused so far) and a pocketbase stub.
- `domain/model/OnlineMedia.kt`, `domain/repository/OnlineMediaRepository.kt`: `search`, `trending`,
  `download` into `cacheDir`.
- `ui/chat/gif/OnlineMediaViewModel.kt` (debounced query, paging, `AppError`).
  `ui/chat/picker/GifTab.kt`: animated previews through the proxy, the provider's required search hint
  and attribution. The composer declares `GIF` only when the flag is on.
- Stickers tab: an **Online** entry in the pack row and a *More online* section under a local search.
  A pick is downloaded, imported into `SAVED` and sent; long-press adds it to favourites or a pack.
- A GIF pick downloads the full rendition through the proxy, then calls `sendGifMessage`.
- Tests: `OnlineMediaViewModelTest`, a mapping test in `testFirebase`.
- Docs: FEATURE-MAP, BACKLOG, CHANGELOG.

## Verification

- **Gate, every step:** `./gradlew test` and `./gradlew assembleDebug` (both flavors must compile).
- **Emulator, after step 2:** `adb push` a few `.webp` files, a `.wastickers` archive and a stand-in
  `WhatsApp Stickers` folder. Import through both routes, then again, and confirm nothing duplicates.
- **Emulator, after step 5:** send a static and an animated sticker between two accounts. Transparency
  and animation survive, the preview and notification say *Sticker*, forward works, nothing new is in
  the gallery. Send the same sticker twice and confirm one Storage object. Kill the app mid-send and
  confirm the outbox finishes it.
- **After step 6:** clear app data, sign in, and confirm packs and favourites return.
- **After step 9:** insert a GIF and a sticker from Gboard; pick a `.gif` from the gallery.
- **After step 11:** search and send with OkHttp logging on. The app contacts only the Functions host
  and Storage, never the provider.
- **Owed on hardware** (to `docs/BACKLOG.md` § *Pending on-device verification*): the real WhatsApp
  folder grant, WhatsApp Business, Samsung's keyboard, the cutout model download, scroll performance
  with many animated bubbles.

## Run

Run one checkpoint at a time:

```bash
scripts/run-plan.sh docs/plans/stickers-and-gifs.md --dry-run
scripts/run-plan.sh docs/plans/stickers-and-gifs.md --to 2
```
