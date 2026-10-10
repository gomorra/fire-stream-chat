# Sticker manager and sticker names

Status: approved by the owner on 2026-10-10. Steps 1 and 2 are shipped.

## Context

A WhatsApp import makes one pack for every pack name a sticker file carries
(`StickerRepositoryImpl.targetFor`). WhatsApp's folder holds every sticker ever sent or received,
so an import of a few hundred stickers makes dozens of packs, most with one to three stickers.
The composer's sticker tab shows one thumbnail per pack (`stickerShelves` in
`ui/chat/picker/StickerLibraryTab.kt`), and the row becomes too long to overlook.

The library screen behind Settings → *Import stickers* (`ui/stickers/StickerLibraryScreen.kt`)
lists the packs and can rename, reorder and delete them, and move stickers between two packs. It
cannot merge packs, cannot show every sticker at once, and is not reachable from the picker once
the library has stickers.

A sticker has no name. Search finds it only by an emoji it is tagged with (`StickerSearch`), and
many imported stickers carry no emoji.

A second WhatsApp import with *Select all* undoes tidying. The import looks for duplicates per
pack (`StickerDao.importInto`), so a sticker that was moved joins its old pack again, and a pack
or a sticker that was deleted comes back.

## Decisions (agreed with the owner, 2026-10-10)

| Question | Decision |
|---|---|
| WhatsApp packs in the picker | They share one *WhatsApp* thumbnail. The packs stay as they are in the data. Merging at import was refused: it loses the pack names for good, and *View pack* on a sent sticker would offer the whole collection |
| Every other pack | Keeps its own thumbnail: stickers made in the app, a pack added through *View pack*, a `.wastickers` archive |
| Manager | The full version, in the screen behind Settings → *Import stickers*: a *Packs* tab and an *All stickers* tab |
| Tidying and import | A new WhatsApp import keeps what the owner tidied. It shows only what is new |
| Search | By name and search words in German and English, by emoji, by pack name |
| Names by hand | An *Add names* button beside *Add emoji* in the sticker maker, and an edit in the manager |
| Automatic names | A service on the owner's PC with a local picture model. No token cost, and the pictures go to no third party |
| Engine and model | Ollama with Gemma 4 26b. Gemma 4 12b when the PC is too busy. Both are settings of the service. Qwen 3.8 27B on llama.cpp is the second choice. The default comes from a test with 12 stickers. The owner's test with about 40 real stickers at the checkpoint after step 7 can change it |
| The phone and the PC | A phone never connects to the PC and holds no listener. It asks through Firestore, and a background job fetches the answer later. The namer and Ollama stay on and wait. Only the model leaves the graphics card after a batch |
| Names that are already there | The service never overwrites them. What it finds is added beside them |
| Review of automatic names | No review state. The additions sit in their own group, the manager has a *Named automatically* filter, and the edit sheet shows both groups. A review mark on 500 stickers is a queue nobody works through |
| Self-made stickers | Left out of automatic naming until the owner allows it |
| Lottie stickers | The service names WebP stickers only. A Lottie sticker keeps its emojis |
| Consent | Automatic naming is off until a person switches it on. A pack and a single sticker can be left out |
| Where the *WhatsApp* thumbnail sits | After *Favourites*, before the packs with their own thumbnail |
| Deleted stickers after a reinstall | The list of deleted stickers stays on the device. After a reinstall, a full WhatsApp import brings them back. A backup of the list was refused: it is more sync code |

**Looked at and dropped**

- *Gemini Nano through ML Kit GenAI.* The Galaxy S24 is not on Google's device list, the image
  description is English only, and it runs only while the app is on screen.
- *A model on the phone (Gemma 4 E2B through LiteRT-LM).* Possible on both phones. The owner chose
  the PC: no 2.6 GB download per phone, and a larger model.
- *Claude through Google Cloud in an EU region.* It costs tokens, which the owner does not want.

**Deferred, not in this plan**

- *A cloud model as backup* for when the PC is off: Claude's smallest model (about 10 cents for 500
  stickers), or Gemini paid by the Cloud credits of the owner's Google AI Pro plan. The request
  document of step 6 is neutral, so a Cloud Function could answer it in place of the PC.
- *Reading a sticker's text on the phone* with ML Kit text recognition, for a person who leaves
  automatic naming off.

## The model

- **A pack says whether it has its own thumbnail.** `shownInRow` is false for a pack a WhatsApp
  import made (import key `wa:…` or `loose:WhatsApp`) and true for every other pack. The owner
  flips it in the manager. *Favourites* and *Saved stickers* are always in the row.
- **The *WhatsApp* shelf is a view.** It lists the stickers of every pack with `shownInRow` false.
  A pack of four or more stickers has a title. Smaller packs share one section at the end. A pick
  is sent with the id of the sticker's own pack, so *View pack* shows that pack.
- **Deleted means "do not import again".** Deleting a sticker from the library takes it out of
  every pack and remembers its id on the device. The WhatsApp import skips a remembered id. Adding
  the sticker on purpose (from files, from a chat) forgets it.
- **A sticker has two groups of words.** *Yours*: the names typed by a person, and the emojis from
  the file or a person. *Automatic*: an English name, a German name, search words in both
  languages, the text read off the sticker, and emojis. The automatic group never changes yours.
  Search reads both. A screen shows your name when there is one, else the automatic name.
- **An automatic name belongs to the sticker, not to a person.** It is one Firestore document per
  sticker id, `stickerNames/{stickerId}`. A phone asks by creating it as `pending`. The service
  answers by filling it in. Every phone that holds the sticker reads the same answer, so a sticker
  is named once. The request carries nothing a person typed.
- **The service sees a picture and nothing else.** It reads the file from Storage, where the
  backup already put it (`StickerSyncWorker`), and never talks to a phone. Nothing on the PC is
  reachable from the internet.
- **What comes back is untrusted input.** The phone caps and cleans it like a pack manifest
  (`StickerManifest`, `cleanStickerText`).

## What was measured (2026-10-10)

Twelve test stickers from the emulator's debug install, on the owner's PC (RTX 4080, 16 GB). Each
model got the same question and returned the same JSON shape. The question and the schema are in
step 7.

| | Gemma 4 12b | Gemma 4 26b | Qwen 3.8 27B |
|---|---|---|---|
| Engine | Ollama | Ollama | llama.cpp server |
| Seconds per sticker | 2.5 | 6.7 | 3.5 |
| Graphics memory | 10 of 16 GB | 15 GB, 30 % of the model on the CPU | 15.8 of 16 GB |
| Text on the sticker | 5 of 5 right | 5 of 5, one slip in small print | brand name right, one doubled word |
| Wrong about what is shown | 2 of 12 | 1 of 12, a sloth called a bear | 1 of 12, one wrong character name |
| German | good | best | three wrong or invented words |

- Four of the twelve were photos of real people. All three models described them and named nobody.
- All three sometimes put the word *Sticker* into a name. The question has to forbid it.
- German search words came back without capitals. The question has to ask for them.
- Qwen ran with a context of 4096 tokens. The owner reports it is unstable with a larger one.
- Ollama's own `/api/chat` and llama.cpp's `/v1/chat/completions` both took a base64 PNG and a
  JSON schema. The service can speak to either.
- Twelve stickers are a first impression. The order can change with the owner's own stickers.

## Open risks, each with the step that settles it

1. **Each Room bump empties the local library.** `AppDatabase` is destructive on a version bump,
   and the backup restores the packs. A pack that was not synced yet is lost, and the pocketbase
   flavor has no backup. This plan bumps twice (steps 1 and 5). Both checkpoints test by upgrading
   over an install.
2. **A manifest written by an older build has no `shownInRow`.** The restore derives it from the
   import key (step 1).
3. **A merged pack can come back small.** Merging frees the import keys of the packs that were
   merged away. A later import of a new sticker from one of them makes that pack again, under the
   *WhatsApp* thumbnail. Accepted, and recorded in `docs/BACKLOG.md` (step 1).
4. **`stickerNames` must not be listable.** A sticker id is all it takes to fetch the file from
   Storage. A rule that allowed `list` would hand every user every sticker of every other user.
   The app reads one document at a time (step 6).
5. **Anyone signed in can ask for a name for any sticker id.** The service then spends time on it.
   Accepted for a closed user base (step 6).
6. **Storage cannot check that a file matches its name** (`TECH_DEBT.md`). The service checks the
   hash itself and refuses a mismatch (step 7).
7. **The text on a sticker can be written to steer the model.** The model has no tools, the answer
   is bound to a schema, and both the service and the phone treat it as data (steps 6 and 7).
8. **The model needs almost the whole graphics card.** The service waits while the card is in use
   (step 7).
9. **The service key can read every user's files.** It lives on the owner's PC only, in a file
   only the owner can read, with a role that allows nothing else (step 7).
10. **A build with step 6 and no deployed rules** gets *permission denied* on every request. The
    app treats that as "naming is not available".
11. **This is eight steps.** Run it one checkpoint at a time.

## Steps

Order: 1 → 2 ‖ 3 → 4 ‖ 5 → 6 → 7 ‖ 8

Every step follows CLAUDE.md's post-step workflow (tests, `./gradlew test`,
`./gradlew assembleDebug`, review skills, one commit, docs). UI steps load the `app-ui-design`
skill. User-visible steps get a CHANGELOG entry and a bump through the `changelog-release` skill.

### Step 1 — Packs that stay tidy: the row flag, merge, delete, and an import that respects them — skills: code-review; model: strong

- `StickerPackEntity` and `StickerPack` get `shownInRow: Boolean`. `AppDatabase` goes from 32 to 33.
  `StickerRepositoryImpl.newPack` sets it from the target: false for `Target.whatsApp` and for the
  loose *WhatsApp* pack, true otherwise.
- `RemoteStickerPack` gets `shownInRow: Boolean?`. `StickerManifest.of` writes it. `packOf` reads
  it and, when it is missing, derives it from the import key. `FirestoreStickerPackSource` maps the
  field. The pocketbase source keeps no manifests and only has to compile.
- `StickerRepository` gains, each one a single `StickerDao` transaction followed by
  `syncScheduler.syncIfPending()`:
  - `setPackShownInRow(packId, shown)`.
  - `mergePacks(packIds, name)`. The first pack in the list keeps its id and its import key and
    takes the name. The stickers of the others are appended in order, without repeats. The others
    are deleted the way `deletePack` deletes. *Favourites* and *Saved stickers* cannot be merged.
  - `createPack(name, stickerIds)`: a new `USER` pack with no import key, holding those stickers.
  - `deleteStickers(stickerIds)`: takes each sticker out of every pack, *Favourites* included, and
    out of the recents, and remembers its id. The `stickers` row and the file stay, as they do for
    a sticker that was only received. A chat bubble still draws it.
- The remembered ids live in `PreferencesDataStore` as a string set, device-only. They are not a
  Room table, because the next version bump would empty it.
- `importFrom` gets a third argument, `skipKnown: Boolean = false`. The WhatsApp route passes
  true. With it, a sticker that is in any pack of the library, or whose id is remembered as
  deleted, joins no pack. A file stored for such a sticker is discarded again when no row uses it,
  under `StickerFiles.rowLock`, as `readArchive` does for a refused archive.
- `StickerImportResult` gets `alreadyInLibrary` and `deletedEarlier`. `importSummary`
  (`ui/stickers/StickerLabels.kt`) names them.
- An add on purpose forgets a remembered id: `importFrom` without `skipKnown`, `saveSticker`,
  `toggleFavourite`, `installPack`, `createSticker`.
- `StickerLibraryViewModel.importSelectedWhatsApp` passes `skipKnown = true`. Nothing else in the
  UI changes in this step.
- Tests: `StickerRepositoryImplTest` (a moved sticker stays where it is after a second import; a
  deleted sticker and a deleted pack stay away; the files route still adds; merge keeps the order
  and drops repeats; merge of *Favourites* is refused), `StickerDaoTest`, `StickerManifestTest`
  (the field, and a manifest without it), `FirestoreStickerPackSourceTest`.
- Docs: `SCHEMA-ROOM.md`, `SCHEMA-FIRESTORE.md`, `DOMAIN-MODELS.md`, the AGENT-NOTE of
  `StickerRepositoryImpl`. `docs/BACKLOG.md`: a merged pack can come back small, and the deleted
  list does not survive a reinstall.

Done when: the gate is green and a second import with every file selected changes nothing in a
library that was tidied.

**Approach**

- Order: `StickerPackEntity` / `StickerPack` / `RemoteStickerPack` and the `AppDatabase` bump,
  then `StickerManifest` and `FirestoreStickerPackSource`, then `StickerDao`,
  `PreferencesDataStore`, `StickerRepository` + `StickerRepositoryImpl`, and last
  `StickerLibraryViewModel` and `StickerLabels`.
- The default of `shownInRow` is one function of the import key
  (`StickerPackEntity.shownInRowByDefault`). `newPack` and `StickerManifest.packOf` both call it,
  so a new pack and a restored old manifest cannot disagree.
- The spec contradicts itself in one place. `deletePack` remembers nothing, so a deleted WhatsApp
  pack would come back with the next import, and the step's own test says it stays away. A
  sticker that `deletePack` or `removeStickers` leaves in no pack is therefore remembered like one
  that `deleteStickers` took out. This changes no §0 decision: it is what *Tidying and import* asks for.
- The remembered ids are cleared at sign-out, like the recents. They belong to the user who leaves.
- Tests: the cases the step lists, in `StickerRepositoryImplTest`, `StickerDaoTest`,
  `StickerManifestTest` and `FirestoreStickerPackSourceTest`, plus the summary line and the
  `skipKnown` argument in `StickerLibraryViewModelTest`.
- Skills: `code-review` (tagged), and `simplify` when the diff passes 600 lines, which it will.

**Shipped** `28a3b8e6` (2026-10-10) — tier: strong. skills: simplify, code-review. Reviewer models: simplify: opus, opus, opus, opus; code-review: opus, opus. CHANGELOG entry (`Changed`, under `[UNRELEASED] [1.42.0]`) is in `28a3b8e6`, and its hash was added in the `docs(plan):` commit.
Departures (for sign-off):
- `deletePack` and `removeStickers` also remember a sticker they leave in no pack, and take it out of the recents. Without this a deleted WhatsApp pack came back with the next import.
- `toggleFavourite` does not forget a remembered id. A favourite is skipped by the import anyway while it is one. With the forget, a deleted sticker that was starred and unstarred came back (found by `/code-review`).
- `createPack` forgets the ids it holds, like the other adds on purpose. It returns the new pack's id.
- Sign-out clears the remembered ids, with the recents (`PreferencesDataStore.clearStickerLists`). Signing out and in again as the same user therefore loses the list. Recorded in `docs/BACKLOG.md`.
- `StickerImportResult.duplicates` still counts repeats inside one import. `importSummary` adds it to `alreadyInLibrary` for the one line *already in the library*.
- A manifest cannot take *Favourites* or *Saved stickers* out of the row: `StickerManifest.packOf` forces `shownInRow` for them.
- Not done, from `/simplify`: one home for all import-key formats, and a file of its own for the remembered ids. A pack made in the app and named *WhatsApp* shares the key `loose:WhatsApp` (`docs/BACKLOG.md`).

### Step 2 — One *WhatsApp* thumbnail in the picker, and a way into the manager (UI + state)

- `stickerShelves` (`StickerLibraryTab.kt`) builds the row as: *Recents*, *Favourites*, one
  *WhatsApp* shelf, then every pack with `shownInRow`, in the owner's order. The *WhatsApp* shelf
  is left out when it would be empty. Its thumbnail is an icon, not WhatsApp's logo.
- A shelf can hold sections. The *WhatsApp* shelf has one titled section per pack of four or more
  stickers, in pack order, and one last section, *More*, with the stickers of the smaller packs.
  A title is the pack's name and its count. The other shelves stay one section.
- A pick from the *WhatsApp* shelf is sent with the id of the pack the sticker sits in, like a
  search result is (`foundIn` in `StickerLibraryTab`).
- A manage button ends the pack row, beside the **+**. `ComposerPickerCallbacks.onImportStickers`
  becomes `onManageStickers` and opens `Routes.STICKERS`. The empty library keeps its
  *Import stickers* button.
- The grid's keys stay unique when a sticker sits in two grouped packs: the first pack that holds
  it shows it.
- Tests: a pure test for the shelf builder (the order of the row, the four-sticker limit, the
  *More* section, an empty *WhatsApp* shelf, a sticker in two packs), and a Robolectric test in the
  shape of `ui/chatlist/ChatListItemUiTest.kt` that a pick from a section carries its pack's id.
- Docs: `FEATURE-MAP.md` (*Emoji / Sticker Picker* and *Stickers & GIFs*), a `docs/BACKLOG.md`
  device checklist, CHANGELOG `Changed`.
- **(step-1)** `StickerPack.shownInRow` has the default `true`, so `testStickerPack` and every
  older fixture is a pack in the row. A test of the *WhatsApp* shelf has to pass `false`.
  `StickerPackKind.isNamed` tells a `USER` or `INSTALLED` pack from *Favourites* and *Saved
  stickers*, whose `shownInRow` is always true.

**Approach**

- Order: `StickerLibraryTab.kt` (the shelf model, `stickerShelves`, the grid, the manage button),
  then `ComposerPickerPanel.kt` (`onManageStickers`), `ChatScreen.kt` and `NavGraph.kt`.
- `StickerShelf` holds `sections`. A section carries the pack id its picks are sent with, so the
  *WhatsApp* shelf and a plain pack share one pick path. Recents is one section with no pack id.
- The four-sticker limit counts what a section shows. A sticker that an earlier grouped pack
  already shows is taken out first, so a title's count always matches its cells.
- The empty library's *Import stickers* button and the manage button call the same callback.
  Both open `Routes.STICKERS`.
- Tests: `StickerShelvesTest` (pure, the five cases the step lists) and three cases in
  `ComposerPickerPanelTest` (a pick from a titled section, a pick from *More*, the manage button).
- Nothing in the code contradicts the spec. Skills beyond the floor: none planned. The diff is
  UI and one pure function, with no ViewModel, worker or DI change.

**Shipped** `e36497fa` (2026-10-10) — tier: mid. skills: none. Reviewer models: none. CHANGELOG entry (`Changed`, under `[UNRELEASED] [1.42.0]`) is in `e36497fa`, and its hash was added in the `docs(plan):` commit.
Departures (for sign-off):
- The four-sticker limit counts what a section shows, after a sticker that an earlier grouped pack already shows is taken out. A pack of four that shares one sticker with an earlier pack therefore goes to *More*.
- The *WhatsApp* thumbnail is a chat-bubble icon (`Icons.AutoMirrored.Outlined.Chat`). The manage button is a gear with the description *Manage stickers*, after the **+**.
- `ChatScreen`'s parameter `onImportStickersClick` is renamed to `onManageStickersClick`, with the callbacks field. `SettingsScreen` keeps its own `onImportStickersClick`.
- A *Favourites* or *Saved stickers* pack stays in the row even when its stored `shownInRow` is false. `stickerShelves` checks `kind.isNamed`.
- The *WhatsApp* shelf's grid has no title of its own, only the section titles. A shelf of small packs alone shows one *More* section.

**‖ Checkpoint.** The owner installs over the current build, lets the library restore, and looks
at the row with the real collection.

### Step 3 — The manager's *Packs* tab (UI)

- The Settings row is named **Stickers** (`ui/settings/SettingsScreen.kt`). The screen keeps
  `Routes.STICKERS`.
- `StickerLibraryScreen` gets two tabs, *Packs* and *All stickers*. This step builds *Packs* and an
  empty second tab.
- *Create*, *From WhatsApp* and *From files* move from three list rows into one **+** in the top
  bar, with a menu.
- The pack list has two groups: *In the picker row* and *Behind the WhatsApp thumbnail*. A pack's
  row has a switch, *Own thumbnail*, that moves it between them (`setPackShownInRow`).
  *Favourites* and *Saved stickers* have no switch.
- A pack is dragged by a handle to reorder it, with `sh.calvin.reorderable` as
  `ui/lists/ListDetailScreen.kt` uses it. The drop calls `reorderPacks`. *Move up* and *Move down*
  leave the menu.
- A long press starts a selection of packs. The top bar then offers *Merge*, from two packs on, and
  *Delete*. *Merge* asks for the name, with the first pack's name filled in. *Delete* asks once
  and names the count of stickers.
- `StickerLibraryUiState` gets the tab and the selected pack ids. `StickerLibraryActions` carries
  the new callbacks, so no composable passes the parameter ceiling (`docs/GOTCHAS.md`).
- Tests: `StickerLibraryViewModelTest` (merge, the switch, a selection that loses a pack),
  `StickerLibraryScreenTest` (the two groups, the selection bar).
- Docs: `FEATURE-MAP.md`, `SPEC.md`, the `docs/BACKLOG.md` checklist, CHANGELOG `Added`.
- **(step-1)** `setPackShownInRow` and `mergePacks` fail for *Favourites* and *Saved stickers*, and
  `mergePacks` fails for fewer than two packs. The UI should not offer what fails.
  `deletePack` now remembers every sticker it leaves in no pack, so the *Delete* question should
  say that a WhatsApp import will not bring them back.
- **(step-2)** The picker's manage button already opens `Routes.STICKERS` (`onManageStickers`).
  `SettingsScreen` and its `NavGraph` call still use the name `onImportStickersClick`. Rename it
  with the row. The picker follows the switch with no further code: `stickerShelves` reads
  `shownInRow` from the packs flow.

### Step 4 — The manager's *All stickers* tab, and a WhatsApp import that shows what is new (UI)

- *All stickers* is one grid of every sticker, with a title per pack and cells of about 64 dp, so
  a phone shows six in a row.
- A search field tops the tab. In this step it finds by emoji word (`EmojiSearchData`) and by pack
  name. Step 5 adds names.
- A long press starts a selection that can span packs. One entry is a pack id and a sticker id.
  The top bar offers *Move to pack*, *New pack from these* (`createPack`, then the stickers leave
  their old packs), *Add to favourites* and *Delete from library* (`deleteStickers`, asked once).
- A pack's own grid (`PackGrid`) gets the same bar.
- `WhatsAppImportScreen` shows the files that are newer than the last import, with a *Show all*
  switch. `PreferencesDataStore` keeps the newest `lastModified` of the folder as it was listed at
  the last finished import. Closing the screen without an import does not move it.
- Tests: `StickerLibraryViewModelTest` (a selection across packs, each action, the new-only filter
  and when its mark moves), `StickerLibraryScreenTest`.
- Docs: `FEATURE-MAP.md`, the `docs/BACKLOG.md` checklist, CHANGELOG `Added`.
- **(step-1)** `createPack` succeeds with the new pack's id. For *New pack from these*, call it
  first and `removeStickers` per old pack after it: a sticker is then never in no pack, so it is
  not remembered as deleted. `removeStickers` remembers a sticker it takes out of its last pack,
  which makes the existing *Remove* in `PackGrid` a delete from the library for such a sticker.
  `StickerImportResult` has no count of what was new in the folder. The new-only filter needs
  its own mark, as this step says.

**‖ Checkpoint.** The owner tidies the real library on the phone and imports from WhatsApp again.

### Step 5 — Names: the fields, the search, and typing them by hand — skills: code-review; model: strong

- `Sticker` gets `names: List<String>` and `autoName: StickerAutoName?` (an English name, a German
  name, words, text, emojis). `StickerEntity` gets the columns for both, plus the two that step 6
  needs: `autoNameAllowed: Boolean` and `nameRequestedAt: Long?`. `StickerPackEntity` gets
  `autoNameAllowed: Boolean`. `AppDatabase` goes from 33 to 34, and this is the plan's last bump.
- Limits: eight names of at most 40 characters, cleaned with `cleanStickerText`.
- `RemoteSticker` gets `names` and `autoNameAllowed`. `RemoteStickerPack` gets `autoNameAllowed`.
  `StickerManifest` counts the bytes of the names toward `MAX_LIST_BYTES`. The automatic group is
  not in a manifest: it comes from `stickerNames` (step 6). A pack added through *View pack* brings
  its owner's names.
- `StickerSearch` gets a text search. A query is cut into words. A word matches the start of a word
  in your names, the automatic names and words, the text on the sticker, or the pack name. Case,
  accents and umlauts are ignored, and `ß` equals `ss`. The emoji lookup that exists stays. Your
  names rank first, the automatic group second, emoji hits last. A sticker is returned once.
- `StickerSearch.suggestionsFor` also reads the automatic emojis.
- The picker's sticker search and the manager's search field use the text search.
- `StickerCreateScreen` gets an *Add names* chip beside *Add emoji*. It opens a field, and each
  name becomes a chip that can be removed. `createSticker` takes the names. A made sticker is
  stored with `autoNameAllowed = false`, and so is a pack the maker creates.
- A selection of one sticker in the manager offers *Edit*. The sheet shows the sticker, its emojis
  and its names, each as chips with add and remove. `StickerRepository.setStickerWords(stickerId,
  emojis, names)` writes them and marks every pack that holds the sticker for a backup.
- Tests: `StickerSearchTest` as a table (prefix, two words, umlauts, `ß`, the ranking, a pack-name
  hit, no repeats), `StickerManifestTest`, `StickerRepositoryImplTest`,
  `StickerCreateViewModelTest`, `StickerLibraryViewModelTest`.
- **(step-2)** In `StickerLibraryTab` a search is one `StickerSection` with the key `results`,
  built from `matches`, and a pick reads its pack from `foundIn`. The text search only has to
  replace what fills `matches`. A sticker must come back once, because the grid's key is its id.
- Docs: `SCHEMA-ROOM.md`, `SCHEMA-FIRESTORE.md`, `DOMAIN-MODELS.md`, `FEATURE-MAP.md`, CHANGELOG
  `Added`.

### Step 6 — Asking for a name: requests, answers, consent — skills: code-review; model: max

- Firestore `stickerNames/{stickerId}`. A request is `status: "pending"`, `format` and
  `requestedAt`. An answer adds `status: "done"`, `nameEn`, `nameDe`, `words`, `text`, `emojis`,
  `model` and `namedAt`. A sticker the service cannot name gets `status: "skipped"` and a `reason`.
- `firestore.rules`: a signed-in user may `get` one document. `list` is refused. `create` is
  allowed only as a request: those three fields, `status == "pending"`, a document id of 64 hex
  digits, `requestedAt == request.time`. `update` and `delete` are refused. The service writes
  with the Admin SDK, which the rules do not bind. `firestore-rules-tests/stickerNames.test.js`
  holds a row for each of these.
- `data/remote/source/StickerNameSource.kt`, backend-neutral: `isSupported`, `request(stickerId,
  format)` and `fetch(stickerId)`. `FirestoreStickerNameSource` in the firebase source set, and a
  pocketbase stub with `isSupported = false`.
- `data/sticker/StickerNameSync.kt` owns who is asked for. A sticker is asked for when the
  person's switch is on, every pack that holds it allows it, the sticker allows it, it has no
  automatic name, its file is on the backend (`remoteUrl`), and it was not asked for within the
  last day. It asks in small batches and fetches the answers one document at a time.
- `data/worker/StickerNameWorker.kt` and its scheduler, in the shape of `StickerSyncScheduler`.
  It runs after a backup run that uploaded something, and when the manager opens. While requests
  are unanswered it comes back with a growing delay, up to a day.
- An answer is checked before it is stored: the lengths, the counts, at most three emojis, and
  `cleanStickerText` on every string. A malformed answer is dropped. It fills the automatic
  columns only and never touches `names` or `emojis`.
- `PreferencesDataStore.autoNameStickersFlow`, off by default. The manager shows the switch,
  *Name my stickers automatically*, with a notice that says what happens: the pictures of the
  stickers are read by a computer that the person running this server keeps, a model there
  describes them, nothing else is sent, and stickers made in the app are left out. The notice has
  the shape of `OnlineMediaNotice`.
- A pack's menu gets *Name automatically*, on or off. A sticker selection gets *Name now*, which
  allows the stickers and asks at once, and *Do not name automatically*.
- The edit sheet shows the automatic group under your own, read-only, with *Use as mine*. That
  copies the automatic name and words into `names` and switches automatic naming off for the
  sticker.
- *All stickers* gets two filters: *No name* and *Named automatically*.
- A refused request (`PERMISSION_DENIED`) switches nothing off. The manager says that naming is
  not available.
- Tests: `StickerNameSyncTest` as a table of who is asked for (each of the six conditions alone),
  the answer check (too long, too many, not a list, an emoji that is a word), a test that an
  answer leaves `names` and `emojis` alone, `StickerNameWorkerTest`, `FirestoreStickerNameSourceTest`,
  the rules tests.
- Docs: `SCHEMA-FIRESTORE.md`, `FEATURE-MAP.md`, `SPEC.md`, the `docs/BACKLOG.md` checklist,
  CHANGELOG `Added`. `TECH_DEBT.md`: Lottie stickers get no automatic name, and anyone signed in
  can ask for any id.

### Step 7 — The naming service on the owner's PC — skills: code-review; model: strong

- `tools/sticker-namer/`, Python 3, with `firebase-admin` and `Pillow` pinned in
  `requirements.txt`. It is not part of the Gradle build or the CI gate. Its tests run with
  `python -m pytest tools/sticker-namer`.
- `namer.py` listens to `stickerNames` where `status == "pending"`. For each request it:
  1. waits while the graphics card has less free memory than `MIN_FREE_VRAM_MB` (`nvidia-smi`);
  2. reads `stickers/<id>.<ext>` from Storage and refuses a file whose SHA-256 is not its id;
  3. turns a WebP into a PNG of its first frame, on white, at most 512 px. A Lottie sticker is
     answered `skipped`;
  4. asks the model and checks the answer against the schema and the limits of step 6;
  5. writes the answer.
  A failure that may pass (the engine is down, no network) leaves the request pending and tries
  again later. A failure that will not pass is answered `skipped` with a reason.
- `engine.py` holds two clients behind one function: Ollama's `/api/chat` and the OpenAI-style
  `/v1/chat/completions` that llama.cpp's server speaks. Both were tried on 2026-10-10. Thinking is
  switched off, the temperature is 0.2, and Ollama gets a short `keep_alive`, so the model leaves
  the card soon after a batch.
- The question asks for: `name_en` (two to four words), `name_de` (two to four words, nouns with a
  capital), `words_en` and `words_de` (five each: what is shown, the mood, the occasion, German
  nouns with a capital), `text`
  (the text on the sticker as written, else empty) and `emojis` (one to three). It forbids the
  word *Sticker* and the name of a real person. The answer is bound to a JSON schema with these
  six fields.
- Settings come from `~/.config/firestream-sticker-namer/env`: the key file, the bucket, `ENGINE`,
  `ENGINE_URL`, `MODEL` (default `gemma4:26b`), `MIN_FREE_VRAM_MB`. No setting and no key is in the
  repo. `.gitignore` covers the virtual environment and any key file under the tool's folder.
- The key belongs to a service account that may read Storage objects and read and write Firestore
  documents, and nothing else. The key file is readable by the owner only.
- `sticker-namer.service`, a systemd user unit that restarts on failure and starts after the
  network.
- `evaluate.py <folder>` runs a folder of stickers through one or more models and prints the names
  side by side with the time per sticker. It is the tool for the owner's model test.
- `setup.sh`, written with the `wizard` skill, walks the owner through what only the owner can do:
  the service account and its key, the env file, the virtual environment, switching Ollama on, the
  unit, and one sticker named end to end.
- Tests, with a fake engine and fake Firestore and Storage clients: the answer check, a hash
  mismatch, a Lottie sticker, an engine that is down, a busy graphics card, a request that is
  answered while it waits.
- Docs: `tools/sticker-namer/README.md`, `FEATURE-MAP.md`, `ARCHITECTURE.md` (one paragraph on the
  service), CHANGELOG is not owed (nothing in the app changes).

**‖ Checkpoint.** The owner deploys `firestore.rules`, runs `setup.sh`, and tests about 40 of
their own stickers with `evaluate.py`. `MODEL` in the env file is set to the model the owner picks.
Then automatic naming is switched on, on both phones.

### Step 8 — Bug hunt over everything this plan built — model: max; budget: 60

`/code-review` is the standards-and-spec skill while `.claude/skills/code-review` is a symlink,
and it looks for no bugs. This step is the correctness review. It adds no feature.

- **Scope from git.** The base is the parent of step 1's code commit, named in its Shipped line.
  `git diff --name-only <base>..HEAD -- app/src/main tools/sticker-namer firestore.rules` lists the
  files. Leave out a file that only work from outside this plan changed.
- **Which review.** When `.claude/skills/code-review` is a directory of this repo and no symlink,
  run it on that range and skip the next bullet.
- **One reviewer per area**, on this step's tier or below. Each reads its files whole and follows
  the calls that leave them.
  1. The library: `StickerRepositoryImpl`, `StickerDao`, `StickerManifest`, `StickerLibrarySync`,
     the pack sources. A second import after every kind of tidying. A merge or a delete that races
     a backup or a restore. A manifest from an older build. A sticker that sits in two packs.
     **(step-1)** Also: the remembered ids are written to `PreferencesDataStore` outside the
     `StickerDao` transaction and outside `importLock`, so look at a delete that runs beside an
     import, and at a process that dies between the two writes. A restore from a second phone
     can put a remembered sticker back into a pack. Step 1 had no correctness review: its
     `/code-review` was the standards-and-spec skill.
  2. The picker and the manager: `StickerLibraryTab`, `StickerLibraryScreen`,
     `StickerLibraryViewModel`, `WhatsAppImportScreen`. A selection whose pack or sticker goes
     away. The pack id a pick is sent with. Grid keys. Rotation and process death.
     **(step-2)** Also: `stickerShelves`, `StickerShelf.packIdOf` and the saved `activeKey` when
     the *WhatsApp* shelf appears or goes away while the panel is open. A sticker that sits in a
     grouped pack and in a pack with a thumbnail is on both shelves, and each sends its own pack.
     Step 2 had no review skill.
  3. Names in the app: `StickerSearch`, `StickerNameSync`, `StickerNameWorker`, the name sources,
     `firestore.rules`. Nothing is asked for while the switch is off. A made sticker, a pack that
     is switched off and a sticker that is switched off are never asked for. No answer changes
     `names` or `emojis`. A hostile answer.
  4. The service: `tools/sticker-namer/`. A request that is answered twice. A crash in the middle
     of a batch. What a log line holds. Where the key can be read from.
- **A finding needs a failure path.** A reviewer reports the input or state, the path through the
  code with `file:line`, and the wrong outcome. The step session reads each path itself and keeps
  only what holds. Where a JVM test can show the failure, the test is written first.
- **Fix what is confirmed and severe.** A confirmed finding that loses stickers or names, sends a
  picture a person did not allow, or opens a security hole is fixed in this step, with a test that
  fails without the fix. A fix that would change a decision of this plan goes to the owner in the
  Shipped block. Every other confirmed finding becomes one line in `docs/BACKLOG.md` or
  `TECH_DEBT.md`.
- **Record** a `**Bug hunt**` block above the Shipped line: the areas, each reviewer's model, every
  confirmed finding with its failure path and what became of it.

Done when: the **Bug hunt** block is committed, and every fix carries its test.

## Verification

- **Gate, every step:** `./gradlew test` and `./gradlew assembleDebug`. Both flavors must compile,
  because the pocketbase stubs follow every signature change.
- **After step 2, on the phone, by upgrading over an install:** the library restores, the row shows
  one *WhatsApp* thumbnail, a pick from it is sent, and *View pack* on that message shows the
  sticker's own pack.
- **After step 4:** merge two packs, move stickers into a new pack, delete a few, give one pack
  its own thumbnail. Import from WhatsApp again with *Show all* and *Select all*. Nothing moves
  back, and the summary counts what was skipped. Clear the app's data, sign in, and confirm the
  tidied library returns.
- **After step 7:** one sticker is named end to end. A search in German and one in English find it.
  A made sticker is not asked for. With the switch off, `stickerNames` gets no new document. With
  the PC off, requests wait and are answered when it is on again. A game on the graphics card
  makes the service wait.
- **Owed on hardware** (to `docs/BACKLOG.md` § *Pending on-device verification*): the picker and
  the manager with several hundred stickers on the S24 and the S25, and the drag in the pack list.

## Run

```bash
scripts/run-plan.sh docs/plans/sticker-manager-and-names.md --dry-run
scripts/run-plan.sh docs/plans/sticker-manager-and-names.md
```

A run stops at every `‖` of the Order line. Run it again to go on.
