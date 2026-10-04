# Stickers and GIFs

Status: approved, steps 1–6 shipped. The prototype's verdict is variant A, the island panel, and step 5 was built to it.

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

1. **How many of the owner's files carry pack metadata is unverified, and so is the folder grant
   from inside the app.** The folder itself is confirmed (pre-flight below). The checkpoint after
   step 2 settles the rest.
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

**Pre-flight, done:** the owner opened
`Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers` in the phone's Files app, and it lists
sticker files.

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

### Step 1 — Sticker library: packs, files, importers — skills: code-review; model: strong

Untrusted files and archives are parsed here.

**Approach**
- Order: pure parsers first (`WebpContainer`, `WaStickerMetadata`, `StickerPackArchive`), then
  `StickerFiles`, the Room tables and `StickerDao`, then `StickerRepositoryImpl` and its DI bindings.
- Pack ids are random UUIDs, because step 6 makes a pack id a Firestore document id that only its
  owner may write. A re-import finds its pack through a new `importKey` column instead.
- `importFrom` takes a second argument, `loosePackName`. Files without pack metadata go to a pack of
  that name (the WhatsApp route passes *WhatsApp*), or to the `SAVED` pack when it is null.
- An import sniffs the first bytes to tell an archive from a WebP. A file name or a mime type from
  another app decides nothing.
- `StickerFiles` reads at most 1 MB + 1 byte into memory, hashes and parses that, then writes a
  temp file and renames it. One pass, and the cap bounds the buffer.
- Recents are `PreferencesDataStore.recentStickerIdsFlow`, next to the emoji recents.
- `domain/model/StickerPack` shares its simple name with the bundled editor pack,
  `domain/util/StickerPack`. They live in different packages and no file in this step needs both.
- Tests: the five the step lists, plus `StickerDaoTest` for the ordering queries and a small
  `WhatsAppStickerFolderTest` for the filter and sort.
- Further skill intended: `simplify`, since the diff will pass 600 lines.

**Shipped** `f7c6640c` (2026-10-03) — tier: strong, tagged strong. skills: code-review, simplify. Reviewer models: code-review: opus, opus; simplify: sonnet, opus, sonnet, opus.
Departures (for sign-off):
- `importFrom(uris, loosePackName)` has a second argument. Stickers without pack metadata join a pack of that name, or the `SAVED` pack when it is null.
- Pack ids are random UUIDs. A new `sticker_packs.importKey` column, unique, is what a re-import finds its pack by. `FAVOURITES` and `SAVED` have the fixed keys `kind:FAVOURITES` and `kind:SAVED`.
- A WhatsApp pack is keyed by id, name and publisher together, because two sticker apps can reuse a pack id.
- `StickerFiles` reads at most 1 MB + 1 byte into memory and writes through a temp file. It does not hash while streaming to disk.
- `StickerFiles` also refuses a sticker wider or taller than 2048 px, and `WebpContainer` refuses a frame larger than its canvas. The plan named only the 1 MB cap.
- `StickerFiles.open` reads a bare path or a `file://` uri only inside `cacheDir`. A uri can come from another app.
- An archive is recognised by its first bytes. A zip with no `.webp` entry, an entry name that is not UTF-8, or a broken cap refuses the whole archive, counts as one rejected input and deletes the files it had stored.
- `sticker_packs.syncState` exists and every `StickerDao` pack write sets it to `PENDING`. Nothing reads it. `stickers` has no remote-url column yet.
- Removing a sticker or deleting a pack deletes item rows only. Files and `stickers` rows stay (`TECH_DEBT.md`, *Sticker files are never deleted*).
- `listWhatsAppFolder` returns uri, name, size and date. It reads no pack metadata, which is read at import.
- `domain/model/StickerPack` shares its simple name with the editor's `domain/util/StickerPack`. Neither was renamed.
- `PreferencesDataStore` got one shared helper pair for the emoji and the sticker recents (`/simplify`).
- Extra tests: `StickerDaoTest`, `WhatsAppStickerFolderTest`. Not user-visible, so no CHANGELOG entry and no version bump.

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

### Step 2 — Settings → Import stickers, and the library screen (UI)

**Approach**
- Order: `StickerLibraryViewModel` and its test first, then `StickerLibraryScreen.kt`, `WhatsAppImportScreen.kt`,
  the `Routes.STICKERS` destination and the Settings row, then the docs.
- One ViewModel and one route. The pack grid and the WhatsApp grid are views of the library screen, chosen by
  `openPackId` and `whatsApp` in its state, each closed by the system back.
- The WhatsApp grid shows the folder ungrouped, newest first. The import does the grouping, so no per-file
  `peek` is added to the repository.
- Packs reorder with *Move up* and *Move down* in the pack's menu, which call `reorderPacks`. No drag handle.
- Stickers are selected by a long press in the pack grid, then moved to another pack or removed from the top bar.
- The folder grant is taken in the composable and not stored. Every import from WhatsApp opens the folder picker.
- The file picker offers every type, because `.wastickers` has no mime type. The repository checks the bytes.
- Tests: `StickerLibraryViewModelTest` (MockK repository), `StickerLibraryScreenTest` (Robolectric, empty state).
- Further skills intended: none. One ViewModel, no concurrency, crypto or sync path.

**Shipped** `4d0edd6d` (2026-10-03) — tier: mid, tagged mid. skills: simplify. Reviewer models: simplify: sonnet, opus, sonnet, opus.
Departures (for sign-off):
- The WhatsApp grid is not grouped by pack. It shows the folder newest first, and the import sorts the stickers into packs. No per-file `peek` was added to the repository.
- The pack grid and the WhatsApp grid are views inside `Routes.STICKERS`, switched by the screen's state and closed by the system back. `WhatsAppImportScreen` has no route of its own.
- Packs reorder through *Move up* and *Move down* in a pack's menu. There is no drag handle.
- The folder grant is taken and not stored. Each import from WhatsApp opens the folder picker again, inside the WhatsApp sticker folder.
- The picker opens in `com.whatsapp` only. A WhatsApp Business folder has to be navigated to by hand.
- The file picker offers every file type, because a `.wastickers` archive has no mime type. The repository refuses what is not a sticker and the summary line counts it.
- An import runs in `viewModelScope`, so leaving the screen cancels it. The pack rows are written at the end of an import, so a cancelled one adds no stickers, and running it again imports them.
- The *Favourites* pack is not offered as a target when moving stickers. Only the screen enforces that.
- `/simplify` was added at the re-decision, because the diff passed 600 lines. It gave both screens one `StickerTopBar`, `StickerCell` and `EmptyHint`, and moved the labels into `StickerLabels.kt`.
- `/simplify` findings not taken: an import-source enum in place of `loosePackName` (the plan fixes that signature), and one shared rule for which pack kinds have a name (it needs a change in `StickerRepositoryImpl`).
- CHANGELOG: a new `[UNRELEASED] [1.38.0]` section, entry hash `4d0edd6d`. `docs/BACKLOG.md` has the device checklist.
- Nothing ran on a device or an emulator. The folder grant under `Android/media` is the first thing to check.

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
- **(step-1)** The WhatsApp route calls `importFrom(uris, loosePackName = "WhatsApp")`. The files route passes no name.
- **(step-1)** `listWhatsAppFolder` returns uri, name, size and date only. Pack metadata is read at import, so the
  grid cannot group by pack without a new repository call that reads each file. Either add one (a bounded,
  per-file `peek`), or show the folder ungrouped and let the import do the grouping.
- **(step-1)** The `FAVOURITES` and `SAVED` packs have an empty `name`. Label them by `kind`. `renamePack` fails for both.
- **(step-1)** `observePacks()` maps the whole library on every emission, once per collector. Collect it once in the
  ViewModel with `stateIn`.
- **(step-1)** A failed edit is a `Result.failure` whose message is fit to show (`That pack no longer exists`,
  `A pack needs a name`). `AppError.from` turns it into `Unknown` with that message.
- Tests: `StickerLibraryViewModelTest`, one Robolectric test for the empty state.
- Docs: new *Stickers & GIFs* section in `docs/FEATURE-MAP.md`. **(step-1)** It also lists step 1's files:
  `domain/model/Sticker.kt` and `StickerPack.kt`, `domain/repository/StickerRepository.kt`,
  `domain/util/WebpContainer.kt`, the five files in `data/sticker/`, `StickerDao`, `StickerEntity.kt`,
  `StickerRepositoryImpl` and their tests.

**‖ Checkpoint.** The owner imports from the real folder and reports whether packs came out grouped.

### Step 3 — `STICKER` and `GIF` as messages — skills: code-review; model: max

The outbox, the sync path and a new storage model change here.

**Approach**
- Order: the model and Room first (`MessageType`, `Message`, `MessageRecord`, `RawMessage`, `stickers.remoteUrl`, 30 → 31),
  then `StickerObjectSource` and both message sources, then the receive side (`data/sticker/StickerDownloads.kt`,
  `MediaFileManager.downloadFor`), then `OutboxSender`, then the two repository sends, then the labels.
- The sticker fields cross the `MessageSource` boundary as one value, `StickerRef`, beside `FileMetadata`.
- `MediaFileManager.downloadFor` stays the one router by type. It sends a `STICKER` to `StickerDownloads` and a `GIF`
  to `DocumentFiles`, and returns `null` for a sticker that is refused. So both types join all three `MessageDao` lists
  and `AUTO_DOWNLOAD_TYPES`, and the chat-open scan and the backfill worker retry a sticker like any other media.
- A received sticker is hashed before it is stored, so a mismatch writes nothing. The file and its `stickers` row are
  written under a lock in `StickerFiles`, which the refused-archive undo in `StickerRepositoryImpl` takes too.
- A `stickers` row gets its `remoteUrl` from `ensureUploaded` only, never from a received message's `mediaUrl`.
- `sendStickerMessage` shares a pack id only for a `USER` or `INSTALLED` pack. Favourites and loose stickers stay private.
- `MessageBubble` is step 4's. This step adds only the labels the plan lists.
- Tests: the plan's list, plus `StickerDownloadsTest` (mismatch, repeat receive, the interleaving with a refused
  archive), `FirebaseStickerObjectSourceTest` and the `MediaFileManagerTest` routing cases.
- Further skill intended: `simplify`, since the diff will pass 600 lines.

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
- **(step-1)** `stickers` has no remote-url column. Add it with the 30 → 31 bump.
- **(step-1)** A sticker id from a message is untrusted. Check it with `StickerFiles.isValidId` before
  `fileFor`, which throws for anything that is not 64 lowercase hex digits. `StickerFiles.store` computes the
  id from the bytes, so the receive path compares the returned id with `stickerId`. Its 1 MB, 2048 px and
  WebP-only checks apply to a received sticker too.
- **(step-1 /code-review)** The undo of a refused archive in `StickerRepositoryImpl.readArchive` deletes every
  file that archive wrote and that has no `stickers` row. A received sticker stored during an import would be
  such a file. The receive path must write the `stickers` row in the same step as the file, or store under
  the repository's import lock. Add a test for the interleaving.
- `OutboxJob.UPLOAD_TYPES` (GIF only), `AUTO_DOWNLOAD_TYPES`, and the three type lists in `MessageDao`.
- Labels: `lastContentFor` in both flavors, `FCMService`, `MessageTypeLabel.placeholderLabel`, and
  the `when`s in `SnoozePickerSheet`, `ForwardMessagePanel`, `ChatMessageActions.snapshotContentFor`,
  `StarredMessagesScreen`, `RemindWidget`.
- Tests: `OutboxSenderTest` (second send of a sticker uploads nothing; GIF bytes unchanged and the
  compressor never called; resume points), `MessageDaoOutboxQueueTest`, repository tests for both
  sends and the guard, `MessageRepositoryForwardTest`, `FirestoreMessageSourceTest` (new fields),
  a hash-mismatch test.
- **Owner, before the device pass:** add to the Storage rules in the console —
  `match /stickers/{file} { allow read: if request.auth != null; allow create: if request.auth != null && resource == null && request.resource.size <= 1024 * 1024; }`
  **(step-3 /code-review)** The size check is `<=`, not `<`. `StickerFiles` accepts a file of exactly 1 MB, and
  with `<` that sticker would import and then fail every send.

**Shipped** `0f70776a` (2026-10-03) — tier: max, tagged max. skills: code-review, simplify. Reviewer models: code-review: opus, opus; simplify: sonnet, sonnet, sonnet, sonnet.
Departures (for sign-off):
- A sticker ignores the auto-download preference. It is fetched on receive and on chat open, also under *Never* and under *Wi-Fi only* off Wi-Fi. `/code-review` found that with the preference applied, a sticker the device already held got no local file.
- A forwarded sticker the library holds goes out with the library's url, or with none, so that `OutboxSender` looks the shared object up. The received `mediaUrl` is handed on only for a sticker the library does not hold (`/code-review`).
- A `stickers` row gets its `remoteUrl` from `ensureUploaded` only. A received sticker's row has none, so the first send of a received sticker costs one lookup.
- `sendStickerMessage` takes a nullable `packId` and shares it only for a `USER` or `INSTALLED` pack. For an `INSTALLED` pack it sends that pack's own id, not `originPackId`.
- `sendGifMessage` refuses a mime type that is not an image type.
- `MediaFileManager.downloadFor` takes a `stickerId` and returns `null` for a refused sticker. Both types joined all three `MessageDao` lists, so the chat-open scan and `MediaBackfillWorker` handle them.
- A refused sticker is remembered for the process only. Each new process fetches it once more, at most 1 MB.
- A received sticker is hashed before it is stored. A file left without its row, which a destructive Room bump leaves behind, is checked and taken back without a download.
- `StickerFiles.rowLock` is a second, short lock beside the import lock, so a received sticker does not wait for a whole import. The refused-archive undo takes it too.
- The two sticker fields cross `MessageSource` as one `StickerRef`. The PocketBase source accepts and ignores them, like every field its v0 schema lacks. `PocketBaseStickerObjectSource` uploads through `StorageSource`, which is still a stub there.
- The Storage rule in this step's last bullet says `<=` now.
- The console rules are published (owner, 2026-10-04). The catch-all there allowed every signed-in write, which would have overridden the `stickers/` block. It now reads `match /{folder}/{rest=**} { allow read, write: if request.auth != null && folder != 'stickers'; }`, beside the `stickers/` block from the last bullet.
- The UI got labels only. `MessageBubble` is untouched, so until step 4 a sticker shows as a text bubble with its emoji.
- `/code-review` findings not taken: `stickers.remoteUrl` is never cleared (`TECH_DEBT.md`), and the label sites keep their own wording.
- `/simplify` findings not taken: one owner for "the url of a library sticker" (noted in step 6), a value type in place of `downloadFor`'s seven parameters, one shared HTTP fetch and one shared image-bounds probe, concurrent sticker downloads on chat open, a cap on the refusal set.
- The hash-mismatch test and the interleaving test are in `StickerDownloadsTest`. Further new tests: `FirebaseStickerObjectSourceTest`, `MessageRepositoryStickerGifSendTest`, routing cases in `MediaFileManagerTest`.
- Not user-visible, so no CHANGELOG entry and no version bump. Nothing ran on a device.
- The Gradle daemon crashed three times in its parallel GC (`SIGSEGV` in `libjvm.so`). The gate passed on a daemon started with `-XX:+UseSerialGC`.
- This block is at the end of the section, not under the Approach block, for the driver's check (`docs/GOTCHAS.md`, `grep -q`).

### Step 4 — Bubbles for stickers and GIFs (UI)

**Approach**
- Order: `coil-gif` in the catalog and the build file, then `ui/components/StickerImage.kt` with the per-request
  animated decoder, then `MessageBubble.kt`, then the reply, forward and starred previews, then `StickerThumbnail`.
- `StickerImage` takes an `animated` flag. A request without the decoder shows the first frame, which is what the
  previews and the library grids use.
- The `STICKER` branch lives in its own composable, `StickerBubbleContent`, so `MessageBubble` and `MessageBubbleBody`
  gain few registers. The dex register count is checked on the built APK (`docs/GOTCHAS.md`).
- A sticker bubble drops the fill, the tail and the padding, and is 160 dp wide. Time and ticks stay in the shared
  metadata row, which follows the grouping rule of every other bubble. A deleted sticker is the usual tombstone bubble.
- The `GIF` branch is the `IMAGE` branch with the animated request and a badge. The 4:3 fallback shape for missing
  dimensions is already there.
- New callback: `MessageBubbleCallbacks.onStickerClick`, a no-op until step 5 wires the sheet. A tap on a GIF does
  nothing, because the fullscreen viewer has no animated decoder and its gallery lists photos only.
- Tests: `MessageBubbleStickerGifTest` (Robolectric) for the type dispatch, the tap, the badge, the tombstone and
  the reply preview.
- Further skills intended: none. UI only, one file per surface, no ViewModel, no concurrency.

- `coil-gif` in `gradle/libs.versions.toml` and `app/build.gradle.kts`.
  `ui/components/StickerImage.kt`: the one composable every surface draws a sticker with, attaching
  `ImageDecoderDecoder.Factory()` per request. Step 7 adds Lottie inside it.
- `MessageBubble.kt`: a `STICKER` branch with no bubble fill or tail, a square of at most 160 dp,
  time and ticks beneath. A `GIF` branch that reuses the `IMAGE` layout and caption with the animated
  request and a small GIF badge. New callbacks go into `MessageBubbleCallbacks`.
- Reply, forward and starred previews show the first frame.
- **(step-2)** `ui/stickers/StickerLibraryScreen.kt` has a plain `StickerThumbnail(model: String)` over `AsyncImage`,
  used by `StickerCell` and the pack rows. Replace its body with `StickerImage`, so there is one sticker renderer.
- **(step-3)** A `STICKER` or `GIF` row's `localUri` is null until its download lands. It stays null for a sticker that was
  refused, whose bytes did not hash to `stickerId`. The bubble renders from `mediaUrl` then. A sticker's `localUri` is
  the shared file in `filesDir/stickers/`, and a GIF's is its copy in `filesDir/documents/`.
- **(step-3)** A GIF's `mediaWidth` and `mediaHeight` are null when its header could not be read at send. The layout
  needs a fallback shape.
- **(step-3)** `MessageBubble`'s `copyableText` falls through to `content` for a sticker, which is its emoji. Add
  `STICKER` to the types with nothing to copy.
- **(step-3)** `ui/components/MessageTypeLabel.kt` has `stickerLabel(emoji)`. `ForwardMessagePanel` shows a labelled
  icon for both types until this step gives it the first frame.
- Test: one Robolectric test for the type dispatch.

**Shipped** `da2eaf18` (2026-10-03) — tier: mid, tagged mid. skills: none. Reviewer models: none.
Departures (for sign-off):
- A tap on a GIF does nothing. The fullscreen viewer has no animated decoder and its gallery lists photos only, so it would show a still. A GIF has no *Save image* either.
- `MessageBubbleCallbacks.onStickerClick` exists and `ChatScreen` does not set it. Step 5 wires the sheet.
- Time and ticks under a sticker follow the grouping rule of every bubble: they show on the last message of a group.
- A sticker's forwarded line and reply preview sit above it on the chat background, 160 dp wide. A deleted sticker is the usual tombstone bubble.
- `StickerImage(model, animated)` shows the first frame with `animated = false`. The library grids, and every preview through `ReplyImageThumbnail`, are still. The starred list got a 40 dp thumbnail for both types.
- The reply preview moved into `ReplyPreviewRow`, to take register pressure off `MessageBubble`.
- The dex register check from `docs/GOTCHAS.md` did not run: the session's allowlist refused `unzip` and `dexdump`. `javap` shows `MessageBubble` at 216 locals and 32 stack slots, which is 4 locals fewer than before the move. A debug build on a device is the real check (`docs/BACKLOG.md`).
- Not user-visible, because no screen sends either type yet. No CHANGELOG entry and no version bump; step 5 carries both. Nothing ran on a device.

### Step 5 — Stickers tab in the composer (UI + state)

Stickers and GIFs are reached through the island panel. The emoji panel gains tabs and takes the
keyboard's place, as it does today. This is variant A of the prototype on branch
`prototype/sticker-gif-picker`; `ui/chat/prototype/VariantAIsland.kt` there is the reference for layout.

**Approach**
- Order: `domain/util/StickerSearch.kt` and its test, then `PickerTab` / `PickerSelection`, `OverlaysState`, `ChatInfoManager`,
  `ChatMessageSender` and `ChatMessageActions`, then `picker/StickerLibraryTab.kt`, `ComposerPickerPanel.kt`,
  `StickerActionsSheet.kt`, then the `ChatScreen` and `NavGraph` wiring, then the docs.
- `StickerSearch` stays pure, so it takes emojis, not a query. The tab turns the query into emojis with `EmojiSearchData`,
  which lives in `ui/chat/picker`.
- The composer declares two tabs, Emoji and Stickers. The GIFs tab is step 11's, behind its flag.
- `ChatInfoManager` combines `observePacks` and `observeRecents` and writes packs, recents and the favourite ids in one update.
- The favourite toggle lives in `ChatMessageActions`, which has `MessageRepository` for `ensureLocalFile`. It serves the
  bubble's sheet and the grid's long press.
- A suggestion is a sticker tagged with exactly the composer's text, so no emoji detection is needed. Picking one sends
  it and clears the composer.
- The Recents shelf is frozen per open panel (`docs/GOTCHAS.md`, list order), because every send reorders it.
- `ChatScreen` gains one parameter, `onImportStickersClick`, and the new UI sits in its own composables to keep the
  register count of `ChatScreen` down.
- Tests: `StickerSearchTest`, two new cases in `PickerPanelTest`, `ChatInfoManagerStickerTest`, `ChatMessageSenderStickerTest`,
  `ChatMessageActionsStickerTest`, `StickerLibraryTabTest` (Robolectric).
- Further skill intended: `simplify`, since the diff will pass 600 lines.

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
- **(step-2)** `ui/stickers/StickerLabels.kt` has `StickerPack.label()`, which names the `FAVOURITES` and `SAVED`
  packs. `StickerCell` in `StickerLibraryScreen.kt` is the grid cell, with click callbacks that hand the id back.
  Use both in the tab. `Routes.STICKERS` exists.
- **(step-3)** `sendStickerMessage(chatId, stickerId, packId)` decides itself whether the pack id is shared: only for a
  `USER` or `INSTALLED` pack. Pass the pack the user picked from, and null from Recents. It fails with a message fit to
  show when the library does not hold the sticker.
- **(step-3)** A received sticker has a `stickers` row and no pack item once its file is downloaded and checked, so
  `setFavourite` works for it. A message whose `localUri` is null has no row yet. Call
  `MessageRepository.ensureLocalFile(message)` first; it fails for a sticker that was refused.
- **(step-4)** The tap on a sticker bubble is `MessageBubbleCallbacks.onStickerClick`, which `ChatScreen` does not set
  yet. Grid cells use `StickerImage(animated = false)` through `StickerThumbnail`. This step carries the CHANGELOG
  entry for the bubbles too, and should run the dex register check on `MessageBubble` (`docs/GOTCHAS.md`).
- Tests: `StickerSearchTest`, `PickerPanelTest` (two tabs draw the island; one-tab hosts unchanged),
  `ChatInfoManager` and `ChatMessageSender` tests.
- Docs: rewrite `docs/BACKLOG.md` §4.6, add *Pending on-device verification* items.

**Shipped** `daf5a088` (2026-10-04) — tier: mid, tagged mid. skills: simplify. Reviewer models: simplify: sonnet, sonnet, sonnet, sonnet.
Departures (for sign-off):
- The composer declares two tabs, Emoji and Stickers. The GIFs tab of the prototype is step 11's, behind its flag.
- `StickerSearch` takes emojis, not a query. `EmojiSearchData` lives in `ui/chat/picker`, and `domain/util` may not import it. `StickerLibraryTab` turns the query into emojis.
- A suggestion is a sticker tagged with exactly the composer's text. No emoji detection runs. A text with a letter, a digit or a space offers nothing, so a keycap emoji offers nothing either.
- A pick from the suggestion strip sends the sticker and clears the composer. The strip shows at most 24 stickers.
- The favourite toggle is `ChatMessageActions.setStickerFavourite`, not in `ChatInfoManager`. It needs `MessageRepository.ensureLocalFile`. `ChatViewModel.toggleStickerFavourite` decides the direction from `OverlaysState.favouriteStickerIds`.
- `OverlaysState` has a third field, `favouriteStickerIds`, written in the same update as packs and recents.
- The sheet reads *Remove from favourites* for a sticker that is one. A long press in the grid toggles the same way. Each shows a snackbar.
- The Recents shelf holds its order while the panel is open. It follows when a sticker joins it.
- A shelf with no stickers is left out of the pack row. A search result is sent with the id of the first pack that holds it.
- `ChatScreen` has a new parameter, `onImportStickersClick`. `EmojiHandlerPanel` gave its backspace key to a shared `PickerBackspaceKey`.
- `StickerCell` sets a test tag, `sticker:<id>`, for every host (`/simplify`).
- Tests: `ChatStickerManagersTest` covers the three managers in one file. `ComposerPickerPanelTest` covers the tab and the strip. `PickerPanelTest` already had the island and the one-tab cases, and is unchanged.
- `/simplify` was intended from the start, because the diff passes 600 lines. It took: shared test builders, the named `when` branches in `ComposerPickerPanel`, the suggestion search keyed on the emoji and not on the text, one remembered pick function for the grid.
- `/simplify` findings not taken: a `toggleFavourite` in `StickerRepository` (noted in step 6), deriving the favourite ids at the read sites, remembering `ComposerPickerCallbacks` (the screen builds its callback bundles inline everywhere), a search debounce, an index of tags per library.
- The dex register check did not run: the allowlist refused `unzip` again. `javap` shows `ChatScreen` at 112 locals and 54 stack slots. `MessageBubble` is untouched. A debug build on a device is the real check (`docs/BACKLOG.md`).
- CHANGELOG: one entry in `[UNRELEASED] [1.38.0]` for this step and the bubbles of step 4. The section was a `feat` already, so no bump. The entry's own hash is added in the `docs(plan)` commit.
- Nothing ran on a device or an emulator. The checklist is in `docs/BACKLOG.md`.
- The driver's first gate run was red: the test worker's JVM died with `SIGSEGV` in `libjvm.so` before any test reported (`app/hs_err_pid951948.log`, ignored by git). No code changed for it. `./gradlew test assembleDebug` passed on the same commit before and after that run.

**‖ Checkpoint.** Sending and receiving stickers is complete. Device pass between two accounts.

### Step 6 — Backup, restore and sharing packs — skills: code-review; model: max

A sync engine and new security rules.

**Approach**
- Order: Room first (`StickerSyncState.DELETED`, the DAO's tombstone, manifest read, compare-and-set mark and remote merge;
  no column changes, so no version bump), then `StickerPackSource` with both implementations and `firestore.rules`, then `data/sticker/StickerUploads.kt`
  (the per-sticker lock and the `remoteUrl` write, out of `OutboxSender`), then `StickerSyncScheduler` and `StickerSyncWorker`,
  then `data/sticker/StickerLibrarySync.kt` (the restore), then `StickerRepositoryImpl` and `AuthRepositoryImpl`, then the UI.
- A manifest holds sticker ids and their metadata, and no urls. A file is always fetched from the object its id names
  (`stickers/<id>.<ext>`), looked up through a new `StickerObjectSource.urlIfPresent`, and hashed by `StickerDownloads`.
  So a manifest someone else wrote can never point this device at another host.
- The restore listener runs while something collects `observePacks()`: an open chat or the library screen. It applies
  changes newer-only by `updatedAt`, and removes a pack only on a `REMOVED` change of a `SYNCED` row.
- Two packs with one import key merge into the older one, on every device alike. The loser gets a tombstone.
- Sign-out already clears every table (`AuthRepositoryImpl.signOut`). This step adds a lock that keeps a restore from
  writing after it, cancels the sync work and clears the recents.
- **View pack** opens the pack the message names. **Add pack** copies it as `INSTALLED` under the root id
  (`originPackId` of the viewed pack, else its id) with the import key `installed:<root>`, which is also what
  "already installed" compares. Install writes rows only. Files arrive when a cell is first shown, as after a restore.
- Fetch on display: `StickerCell` takes a `Sticker`, checks its file and asks `LocalStickerFetcher`, which
  `MainActivity` provides from `StickerRepository.ensureFile`. No ViewModel is threaded through.
- The pack preview is its own sheet with its own `StickerPackPreviewViewModel`, so `ChatUiState` gains nothing.
- Tests: `StickerSyncWorkerTest`, `StickerLibrarySyncTest`, `StickerUploadsTest`, `FirestoreStickerPackSourceTest`, new
  cases in `StickerDaoTest` and `StickerRepositoryImplTest`, `StickerPackPreviewViewModelTest`, a Robolectric test for the
  sheet rows and the fetch on display.
- Further skills intended: `simplify` (the diff will pass 600 lines), `app-ui-design` (Compose), `changelog-release`.

- `data/remote/source/StickerPackSource.kt`, `FirestoreStickerPackSource`, a pocketbase stub.
- `firestore.rules`: `stickerPacks/{packId}` — get for any signed-in user, list and write for the owner.
- `data/worker/StickerSyncWorker.kt` (unique work, connected): for each pending pack, `ensureUploaded`
  every sticker, then write the manifest. Deletes are written as deletes.
- Restore: a listener on the user's own packs upserts into Room, newer-only by `updatedAt`. Files are
  fetched when first displayed. Sign-out clears the library rows.
- The sticker sheet gains **View pack**: fetch the message's `stickerPackId`, preview it, **Add pack**
  copies it as `INSTALLED`. A pack already installed says so.
- **(step-1)** `StickerDao.deletePack` deletes the row outright, so a delete leaves nothing to sync. Add a
  tombstone state to `StickerSyncState` and filter it out of `observePacks`.
- **(step-1 /code-review)** `sticker_packs.importKey` is unique. Back it up in the manifest and restore by it:
  a restored pack whose key a local pack already has must merge into that row, not insert beside it. This
  is also what keeps one `FAVOURITES` and one `SAVED` pack (keys `kind:FAVOURITES`, `kind:SAVED`) when a
  favourite was made before the restore arrived. Without the key in the backup, a re-import after a restore
  makes a second pack.
- **(step-1)** A restored `stickers` row arrives before its file. `Sticker.localPath` names where the file will
  be, so a renderer must handle a path with no file yet. Validate restored sticker ids with
  `StickerFiles.isValidId`; `StickerRepositoryImpl` drops rows that fail it.
- **(step-1 /simplify)** `StickerDao` leaves `insertItems`, `deleteItems` and `insertPack` public beside the
  transaction methods that mark a pack `PENDING` (`TECH_DEBT.md`). The worker and the restore must not
  change a pack through them without setting `syncState`.
- **(step-2 /simplify)** `StickerLibraryViewModel.WHATSAPP_PACK_NAME` is the loose pack name the WhatsApp route
  passes, and the repository stores it in the import key `loose:WhatsApp`. Once that key is backed up, the
  constant must not change. Move it into the repository if the name is ever to be translated.
- **(step-2)** Only the library screen keeps the `FAVOURITES` pack out of the move targets. `moveStickers` accepts
  it. If favourites sync differently from packs, refuse it in the repository.
- **(step-2)** An import runs in `viewModelScope`. If the sync worker is to start after an import, enqueue it from
  the repository, not from the screen.
- **(step-3)** `stickers.remoteUrl` exists. Only `OutboxSender.withStickerUrl` sets it, after
  `StickerObjectSource.ensureUploaded`. The worker's `ensureUploaded` for every sticker must set it too.
- **(step-3 /simplify)** `OutboxSender` owns the lock per sticker id and the `remoteUrl` write. Move both into one class
  in `data/sticker` that the worker and `OutboxSender` call, so a send and the sync exclude each other. Do not add a
  second lock.
- **(step-3)** Anything that stores a sticker file and writes its row outside an import must hold `StickerFiles.rowLock`
  for both. A restored sticker's file should come through `StickerDownloads.ensureLocal`, which does that and checks the hash.
- **(step-3)** A sticker message from an `INSTALLED` pack carries that pack's own id, not its `originPackId`. Decide
  which one **View pack** opens, and what "already installed" compares.
- **(step-3 /code-review)** A sticker forwarded from a device that does not hold it keeps the first sender's `mediaUrl`
  and `stickerPackId`.
- **(step-5)** The sticker sheet is `ui/chat/StickerActionsSheet.kt`, opened from `stickerSheetMessage` in `ChatScreen`.
  **View pack** is a second row there.
- **(step-5)** The composer's Stickers tab draws `Sticker.localPath` through `StickerCell`. A restored row whose file
  has not arrived shows the broken-image mark there, and a tap sends it. Fetch the file when the cell is first shown.
- **(step-5 /simplify)** `ChatViewModel.toggleStickerFavourite` reads `OverlaysState.favouriteStickerIds` and then calls
  `setFavourite`. Two quick taps both read the old state. If favourites sync, give `StickerRepository` a
  `toggleFavourite` that decides inside its own transaction.
- Tests: the worker (pending → synced, each file uploaded once, delete), restore mapping and the
  newer-only rule, install and the already-installed case.

**Review outcome** (`/code-review`, then `/simplify`). This block sits above the `**Shipped**` line for the driver's check (`docs/GOTCHAS.md`, `grep -q`).
- `/code-review` fixes: a sign-out finishes inside the fence though its caller is cancelled; the listener maps snapshots in order; the worker skips a pack that changed while its files uploaded; a manifest keeps 8 emoji tags and stops at 900 000 bytes; `StickerSyncSchedulerTest`; one `awaitAck`.
- `SingleFlight` no longer hands a cancelled first caller's cancellation to its waiters. `LinkPreviewSource` uses it too.
- `/simplify` fixes: `StickerSyncScheduler.syncIfPending` does not throw; `ensureFile` is cancellable, writes no `remoteUrl` and remembers a missing object; `countItem` and the sheet's fallback text are gone.
- Not taken: the two-device cases (`TECH_DEBT.md`), a sync trigger driven from a DAO flow, `ensureFile` inside `StickerDownloads`, batched `remoteUrl` writes during a backup, no mapping of unchanged manifests when the listener starts, one `StickerCell`, shared test builders.
- `/code-review` ran before the `/simplify` fixes and the `SingleFlight` change. They were not reviewed again.
  Each has a test: `SingleFlightTest`, `StickerLibrarySyncTest`, `StickerSyncWorkerTest`, `StickerSyncSchedulerTest`,
  `StickerManifestTest` and the fetch cases in `StickerRepositoryImplTest`.

**Shipped** `33f54406` (2026-10-04) — tier: max, tagged max. skills: code-review, simplify, app-ui-design, changelog-release. Reviewer models: code-review: opus, opus; simplify: sonnet, sonnet, sonnet, sonnet.
Departures (for sign-off):
- This session found the step written and uncommitted in the worktree, left by an earlier attempt. It read, reviewed, fixed and gated that work.
- **For sign-off, not fixed (`/code-review`):** the rules let any signed-in user create a manifest under an id no document has yet. A recipient with a modified client can claim a pack id it was sent, before the owner's first upload or after a delete. `TECH_DEBT.md`, *A sticker pack's id can be claimed by whoever writes its manifest first*, has the fix. It changes the pack's document id, and its rule cannot be tested here.
- A manifest holds no urls. A file is found by its id through the new `StickerObjectSource.urlIfPresent` and hashed.
- The restore listens only while `observePacks()` is collected: an open chat or the library screen.
- `StickerRepository.setFavourite` became `toggleFavourite`, decided in one DAO transaction.
- *Add pack* installs under the root pack id with the import key `installed:<root>`. It writes rows only.
- A sticker whose file has not arrived shows grey. A tap on it is refused with *That sticker is not in the library*.
- `LocalStickerFetcher` is a CompositionLocal that `MainActivity` provides. It has no `docs/PATTERNS.md` entry.
- On pocketbase `StickerPackSource.isSupported` is false, and a deleted pack's row goes at once.
- `SingleFlight` (`data/util`, outside this step's files) changed. See *Review outcome* above.
- CHANGELOG: `v1.38.0` is tagged on main. The `[1.38.0]` header lost its prefix here and a new `[UNRELEASED] [1.39.0]` section holds this entry. A merge with main meets the same header line.
- Nothing ran against Firestore, on a device or an emulator. The rules are untested. The dex register check did not run. Checklist: `docs/BACKLOG.md`.

**‖ Checkpoint.** The owner deploys `firestore.rules`. Device: reinstall and confirm the library
returns; a second account adds a pack from a received sticker.

### Step 7 — Lottie stickers

- **Spike first:** open real `.was` files from the owner's folder and confirm the container. If it
  cannot be read, ship `.tgs` only and report `needs_decision`.
- `lottie-compose` dependency. `StickerFormat.LOTTIE`. `data/sticker/LottieContainer.kt`: gzip or zip
  to the animation JSON, with a size cap and a parse guard.
- Importers accept `.was` and `.tgs`. A first-frame PNG thumbnail is written at import for grids.
- **(step-1)** The importer tells inputs apart by their first bytes, not by name: `StickerPackArchive.isArchive`
  for a zip, else `StickerFiles.store`, which accepts WebP only. Add the gzip header there. A `.was` that is
  a zip must be told from a `.wastickers` pack by its entries. `StickerPackArchive.read` hands over `.webp`
  entries only, and `WhatsAppStickerFolder` lists `.webp` names only.
- `StickerImage` switches on format, so the bubble, the picker and the library all render Lottie.
- **(step-4)** `StickerImage` takes `model: Any` and no format. The bubble has `Message.mimeType` and the library has
  `Sticker.format`; give it a format parameter. `ReplyImageThumbnail` and the forward preview draw the first frame
  with a plain `AsyncImage`, which cannot read Lottie, so they need the PNG thumbnail.
- **(step-5)** The composer's Stickers tab, its pack row and the suggestion strip draw `Sticker.localPath` through
  `StickerCell` and `StickerThumbnail`. For a Lottie sticker they need the PNG thumbnail.
- **(step-6)** A restored or installed sticker is a row whose file arrives later, through
  `StickerRepositoryImpl.ensureFile` → `StickerDownloads.ensureLocal` → `StickerFiles.store`, which accepts WebP only.
  Extend that path for Lottie, and write the PNG thumbnail there too, not only at import. `StickerManifest.stickersOf`
  drops an entry whose format the build does not know, so an older build restores a pack without its Lottie stickers.
- **(step-6)** A library sticker is drawn by `LibraryStickerImage` in `ui/components/StickerImage.kt`, which checks
  `Sticker.localPath` and asks `LocalStickerFetcher` for a missing file. The format switch goes there as well.
- Tests: `LottieContainerTest` (fixtures for both containers, oversize, not JSON), importer cases.

### Step 8 — Make your own stickers

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
- **(step-2)** The library screen's import rows are the first items of `PackList` in `StickerLibraryScreen.kt`, and
  its callbacks are the `StickerLibraryActions` bundle. **Create** goes next to them.
- **(step-5)** The picker's pack row is the `LazyRow` in `ui/chat/picker/StickerLibraryTab.kt`. The **+** is a last item
  there, and its callback is a new field of `ComposerPickerCallbacks`. `ChatScreen` hands it to `NavGraph` like
  `onImportStickersClick`.
- **(step-6)** A new sticker joins a pack through a `StickerDao` transaction (`importInto`, `addToPack`), which marks
  the pack `PENDING`. `StickerRepositoryImpl` then calls `StickerSyncScheduler.syncIfPending()`, as after every edit,
  and `StickerSyncWorker` uploads the file. Nothing else is owed for the backup.
- Tests: the encoder's size loop, the outline geometry (pure), the ViewModel.

**‖ Checkpoint.** Device pass for Lottie and the maker.

### Step 9 — GIFs and stickers from the keyboard

- **Spike first:** confirm on the emulator that `Modifier.contentReceiver` delivers Gboard content to
  the composer's `BasicTextField`. Fallback: `InterceptPlatformTextInput` wrapping the input connection
  with `InputConnectionCompat` and `EditorInfoCompat.setContentMimeTypes`. If neither works, report
  `needs_decision`. Do not migrate the composer to `TextFieldState` in this plan.
- Routing: `image/gif` → `sendGifMessage`. Any other `image/*` from the keyboard is imported into the
  `SAVED` pack and sent with `sendStickerMessage`.
- `sendMediaMessage` sends an unedited `image/gif` as a `GIF`, which covers the gallery and the share
  sheet. An edited one is a JPEG by then and stays an `IMAGE`.
- **(step-1)** `StickerFiles.store` refuses anything that is not WebP, and Gboard stickers are often PNG. Either
  add PNG to `StickerFormat` with its own container check, or convert before the import.
- **(step-1)** `importFrom(listOf(uri))` with no pack name is the import into `SAVED`. Its result's `packIds` is
  empty when the sticker was already there, so find the sticker by its hash, not by the result.
- **(step-3)** `sendGifMessage` refuses a type that is not an image type and a file over 8 MB (`MAX_GIF_BYTES`).
  `sendMediaMessage` still sends `image/gif` as an `IMAGE`; the branch this step adds goes there.
- **(step-5)** A sticker from the keyboard is sent through `ChatViewModel.sendSticker(stickerId, packId = null)`, which
  also marks it used.
- **(step-6)** `importFrom` already asks for the backup of the `SAVED` pack. `StickerRepository.setFavourite` is gone:
  `toggleFavourite(stickerId)` decides the direction.
- Tests: the routing rule, and the `image/gif` branch in the repository test.

### Step 10 — Media proxy Cloud Functions — skills: code-review; model: strong

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

### Step 11 — GIFs tab and the online sticker catalogue

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
- **(step-1)** `StickerFiles.open` reads a bare path or a `file://` uri only inside `cacheDir`. Keep the
  download there, or the import refuses it.
- **(step-5)** The composer's tabs are `COMPOSER_TABS` in `ui/chat/ComposerPickerPanel.kt`, and its `when` names
  `PickerTab.GIF` as an empty branch. Add the tab between Emoji and Stickers when the flag is on, and refresh the
  `PickerTab` KDoc, which says nobody declares `GIF`.
- **(step-5)** The pack row is built by `stickerShelves` in `StickerLibraryTab.kt`, and a local search by
  `StickerSearch.byEmojis`. The **Online** entry and the *More online* section go there. Picks leave through
  `ComposerPickerCallbacks`.
- **(step-6)** The long press that adds an online sticker to the favourites is `StickerRepository.toggleFavourite`.
  `installPack` takes a `StickerPackPreview` read from a Firestore manifest, so an online pack is imported as files,
  not installed. On pocketbase `StickerPackSource.isSupported` is false, like the flag this step adds.
- **(step-6 /code-review)** `SingleFlight` no longer passes a cancelled first caller's cancellation to its waiters.
  A download that a picker cell starts and cancels is safe to share with other callers.
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
