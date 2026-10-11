# Sticker manager and sticker names

Status: approved by the owner on 2026-10-10. Steps 1 to 7 are shipped.

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
| Seeing a sticker's words | One sheet shows the sticker large, its names and its search words. It opens by a tap on a sticker in a chat, inside a pack and in *All stickers*. *All stickers* also has a list view with the words beside each sticker. A name under every cell of the grid was refused: six in a row leave room for ten letters. Agreed on 2026-10-11 |
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
   over an install. Main bumps the version too (`docs/plans/message-sync.md`). A merge where both
   sides bumped sets the version above both (`docs/PATTERNS.md#room-version-bump-rule`).
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

Order: 1 → 2 ‖ 3 → 4 ‖ 5 → 6 → 7 ‖ 7a ‖ 8

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

**Approach**

- Order: `StickerLibraryViewModel.kt` (the tab, the pack selection, merge, delete, the switch, the
  drop), then `StickerLibraryScreen.kt` (tabs, the **+** menu, the two groups, the drag, the
  selection bar and its two dialogs), then `SettingsScreen.kt` and `NavGraph.kt` for the row's name.
- The two groups are two filters over one list in the owner's order. A drag moves a pack inside
  its group only, and the drop writes the whole order with `reorderPacks`. The switch is the one
  way between the groups.
- The ViewModel shows a dropped order at once and takes it back when the write fails. Without
  that the row jumps back until Room answers.
- Only a `USER` or `INSTALLED` pack can be selected, since `mergePacks` refuses the other two.
  *Favourites* and *Saved stickers* keep *Delete* in their row menu.
- `movePack` and its tests go, with *Move up* and *Move down*.
- Tests: `StickerLibraryViewModelTest` (merge in the shown order, delete of a selection, the
  switch, a selection that loses a pack, the drop and a failed drop) and `StickerLibraryScreenTest`
  (the two groups, the **+** menu, the selection bar, no switch on *Favourites*).
- Nothing in the code contradicts the spec. Skills beyond the floor: `simplify` if the diff passes
  600 lines. One ViewModel changes, and no worker, DI or crypto file.

**Shipped** `5b7dfccb` (2026-10-10) — tier: mid. skills: simplify. Reviewer models: simplify: sonnet, sonnet, sonnet, sonnet. CHANGELOG entry (`Added`, under `[UNRELEASED] [1.42.0]`) is in `5b7dfccb`, and its hash was added in the `docs(plan):` commit.
Departures (for sign-off):
- Only a `USER` or `INSTALLED` pack can be selected. *Favourites* and *Saved stickers* keep *Delete* in their row menu, and a long press on one opens it like a tap.
- A drag moves a pack inside its group only. The drop writes the whole order with `reorderPacks`. The ViewModel shows the dropped order at once and takes it back when the write fails.
- The switch sits in the row's second line, beside the words *Own thumbnail*. With the menu and the handle at the row's end, the switch is in the middle of a narrow row.
- The row menu keeps *Rename* and *Delete*. The delete question of one pack also says that a WhatsApp import will not bring its stickers back.
- The three entries of the **+** menu have no second line any more. The file types *From files* takes are no longer named on the screen.
- *Delete* of a selection calls `deletePack` once per pack and stops at the first failure (`TECH_DEBT.md`).
- The Settings row's subtitle is new too. `SettingsScreen`'s parameter is `onStickersClick`.
- `StickerPack.hasOwnThumbnail` is new on the domain model. The picker's `stickerShelves` reads it in place of its own rule (from `/simplify`).
- `StickerLibraryUiState.selectedPacks` and `canMerge` are derived. The state has no list per group: the screen splits the list it shows.
- No test drags a row. `StickerLibraryScreenTest` covers the rule a drag follows (`movedWithinGroup`), and the drag itself is on the `docs/BACKLOG.md` checklist.
- Not done, from `/simplify`: one `deletePacks(ids)` in the repository, and one copy of the dropped order in place of two (`TECH_DEBT.md`).

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
- **(step-3)** `StickerLibraryContent` draws the second tab as an empty `Box` with the tag
  `ALL_STICKERS_TAB_TAG`. Replace it. `StickerLibraryUiState.selectedStickerIds` belongs to the
  open pack: the packs collector drops every id that is not in `openPack`, and `editSelection`
  reads `openPackId`. A selection across packs needs its own entries. The top bar's `when` already
  tells a sticker selection from a pack selection (`selectedPackIds`), and `selectTab` ends a pack
  selection. `PackNameDialog` asks for a pack's name and serves *New pack from these*. In a
  Robolectric test the middle of a pack's row is its switch, so tap a row at its start.

**Approach**

- Order: `PreferencesDataStore` and `StickerRepository` + `StickerRepositoryImpl` (the import mark),
  `StickerSearch` (one test for "tagged with"), then `StickerLibraryViewModel.kt`, a new
  `AllStickersTab.kt`, `StickerLibraryScreen.kt` and `WhatsAppImportScreen.kt`.
- One selection serves both grids. `selectedStickers` holds entries of a pack id and a sticker id,
  and replaces `selectedStickerIds`. In an open pack every entry names that pack. The four actions
  group the entries by pack, so the pack grid and *All stickers* share one bar and one code path.
- The pack grid keeps *Remove from pack* beside the four new actions. It is how a star is taken off
  in *Favourites*.
- The ViewModel sees `StickerRepository` only, so the mark crosses it:
  `whatsAppImportedUntil()` and `markWhatsAppImported(lastModified)`. The ViewModel moves it after a
  WhatsApp import that succeeded, to the newest `lastModified` of the listing. Sign-out clears it
  with the other sticker lists.
- The search is a pure function, `allStickerSections(packs, query)`. A pack whose name holds the
  query shows whole. Any other pack shows its stickers tagged with an emoji the query names.
- Tests: `StickerLibraryViewModelTest` (a selection across packs, each action, a selection that
  loses a sticker, the filter, *Show all*, when the mark moves), `AllStickerSectionsTest` (pure),
  `StickerLibraryScreenTest` (the grid's titles, the bar, the delete question),
  `WhatsAppImportScreenTest` (the switch), `StickerSearchTest`, `StickerRepositoryImplTest`.
- Nothing in the code contradicts the spec. Skills beyond the floor: `simplify` when the diff passes
  600 lines, which it will. One ViewModel changes, and no worker, DI or crypto file.

**Shipped** `0567f9e8` (2026-10-10) — tier: mid. skills: simplify. Reviewer models: simplify: sonnet, sonnet, sonnet, sonnet. CHANGELOG entry (`Added`, under `[UNRELEASED] [1.42.0]`) is in `0567f9e8`, and its hash was added in the `docs(plan):` commit.
Departures (for sign-off):
- One selection serves both grids. `StickerLibraryUiState.selectedStickers` holds `StickerEntry` (a pack id and a sticker id) and replaces `selectedStickerIds`.
- The top bar of a sticker selection shows *Move to pack* and *Delete from library* as icons. *New pack from these* and *Add to favourites* are in a menu beside them, because four icons and the count do not fit a narrow phone.
- A pack's own grid keeps *Remove from pack*, in that menu. It is how a star is taken off in *Favourites*.
- A sticker that two packs hold is shown under both titles. A sticker picked in two packs counts once in the delete question.
- *New pack from these* and *Move to pack* take a sticker out of the pack it was picked in, *Favourites* included. *Favourites* is no move target.
- *Add to favourites* skips a sticker that has a star, because the repository only has a toggle.
- The cells are 56 dp at least, not 64. That gives six in a row on a phone 360 dp wide, which both of the owner's phones are.
- The search finds a pack by the name the screen shows, so *Favourites* and *Saved stickers* are found too. A pack whose name matches shows whole.
- The mark crosses the repository: `StickerRepository.whatsAppImportedUntil()` and `markWhatsAppImported(lastModified)`. The ViewModel moves it after a WhatsApp import that succeeded. Sign-out clears it with the recents and the deleted ids.
- An import of some of the new files moves the mark past all of them. The rest are then behind *Show all*.
- Before the first import there is no *Show all* switch, and every file is shown. Switching *Show all* off takes a file that is hidden again out of the selection.
- A file whose provider reports no `lastModified` counts as old after the first import.
- Not done, from `/simplify`: one repository transaction per selection action, an `addFavourites`, and a WhatsApp import that moves its own mark (`TECH_DEBT.md`). The search stays in `ui/stickers` until step 5 replaces it. It runs on every keystroke in composition.

**‖ Checkpoint.** The owner tidies the real library on the phone and imports from WhatsApp again.

### Step 5 — Names: the fields, the search, and typing them by hand — skills: code-review; model: strong

- `Sticker` gets `names: List<String>` and `autoName: StickerAutoName?` (an English name, a German
  name, words, text, emojis). `StickerEntity` gets the columns for both, plus the two that step 6
  needs: `autoNameAllowed: Boolean` and `nameRequestedAt: Long?`. `StickerPackEntity` gets
  `autoNameAllowed: Boolean`. `AppDatabase` takes the next free version, and this is the plan's last bump.
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
- **(step-4)** The manager's search is `allStickerSections(packs, query)` in
  `ui/stickers/AllStickersTab.kt`, with `AllStickerSectionsTest`. It returns sections per pack, and
  a sticker that two packs hold is in both: the grid's key is the pack id and the sticker id. The
  text search has to keep that shape there, so move the function into `StickerSearch` beside the
  picker's. `StickerSearch.taggedWith` is the emoji test both use. The field's placeholder says
  *Search by emoji or pack*. A selection is `StickerLibraryUiState.selectedStickers`, a set of
  `StickerEntry`. *Edit* is offered when `selectedStickerCount` is 1, and belongs in
  `StickerSelectionActions` (`StickerLibraryScreen.kt`), whose questions are `SelectionDialog`.
  `StickerCell` takes `isSmall` and `tag` for the grid of every sticker.
- Docs: `SCHEMA-ROOM.md`, `SCHEMA-FIRESTORE.md`, `DOMAIN-MODELS.md`, `FEATURE-MAP.md`, CHANGELOG
  `Added`.

**Approach**

- Order: `Sticker` / `StickerPack` / `StickerEntity` / `StickerPackEntity` and the `AppDatabase` bump
  (34 to 35), then `StickerText` (the limits), `RemoteSticker` / `RemoteStickerPack`,
  `StickerManifest` and `FirestoreStickerPackSource`, then `StickerDao`, `StickerRepository` +
  `StickerRepositoryImpl`, `StickerSearch`, and last the UI: `StickerLibraryTab`, `AllStickersTab`,
  `StickerCreateViewModel` + `StickerCreateScreen`, `StickerLibraryViewModel` + `StickerLibraryScreen`
  and a new `StickerWordsEditor.kt`.
- The text search is one ranking function in `StickerSearch`. The picker asks for each sticker once,
  best rank first. The manager asks for sections per pack. Both pass the emoji keyword table and the
  pack's shown name in, so `StickerSearch` stays in the domain.
- A name typed on one phone has to reach the other. `StickerDao.mergeStickers` keeps a known
  sticker's row, so a restore would drop the edit. A newer manifest of the owner's own pack that
  carries names therefore replaces the words of the stickers it lists. A manifest of an older build
  carries none and replaces nothing.
- A manifest of an older build does not say which pack the maker made. After the bump, the restore
  reads `autoNameAllowed` from the import key: the maker's default pack is left out, every other
  pack is allowed.
- The limits and the add of one name or emoji are pure functions (`StickerWords`), used by the maker,
  the edit sheet and the repository.
- Tests: `StickerSearchTest` (the table, and the sections that `AllStickerSectionsTest` held),
  `StickerWordsTest`, `StickerManifestTest`, `FirestoreStickerPackSourceTest`, `StickerDaoTest`,
  `StickerRepositoryImplTest`, `StickerCreateViewModelTest`, `StickerLibraryViewModelTest`, and the
  two screen tests for the new chip and *Edit*.
- Nothing in the code contradicts a §0 decision. Skills beyond the floor: `simplify`, because the
  diff will pass 600 lines and two ViewModels change.

**Shipped** `c6854e58` (2026-10-11) — tier: strong, tagged strong. skills: simplify, code-review. Reviewer models: simplify: sonnet, sonnet, sonnet, sonnet; code-review: opus, opus. CHANGELOG entry (`Added`, under `[UNRELEASED] [1.43.0]`) is in `c6854e58`, and its hash was added in the `docs(plan):` commit.
Departures (for sign-off):
- `AppDatabase` went from 34 to 35. Main had taken 33 and 34 since the plan was written.
- The CHANGELOG got a new section, `[UNRELEASED] [1.43.0]`. The tags `v1.42.0` and `v1.42.1` exist, and this branch does not hold their release commits. So `[1.42.0]` lost its `[UNRELEASED]` prefix here, as on main. The merge with main has to place `[1.42.1]` between the two.
- A newer manifest of the owner's own pack replaces the emojis, the names and `autoNameAllowed` of the stickers it lists, when it states names (`StickerDao.mergeStickers(takeWords)`, `StickerManifest.statesWords`). Without this a name typed on one phone never reached the other. Until now a known sticker only gained emojis when it had none, and that still holds for every other restore and for *Add pack*.
- A pack change on a phone that has not yet seen an edit can take the edit back. Two phones that edit one sticker at once can end with different words. Both are in `TECH_DEBT.md`, and step 8 reviews the path.
- A manifest of an older build does not say which stickers were made in the app. A restore of one reads `autoNameAllowed` from the import key: a pack found by its name (`loose:…`, other than `loose:WhatsApp`) is the maker's and is closed, and its stickers with it. A made sticker that sat in an imported pack before this build reads as allowed.
- `Sticker` and `StickerPack` also carry `autoNameAllowed`. *Add pack* needs it to keep a sticker its owner left out, and step 6 shows it.
- The search has four ranks, not three. A hit by the pack's name sits between the automatic group and an emoji hit.
- Every word of a query has to be found, in any order. Each may be found in another field.
- The limits live in `domain/util/StickerWords`, which repeats what `cleanStickerText` does with a cap of 40. `cleanStickerText` is in the data layer, and the ViewModels and the sheet need the same rule. An edit keeps up to eight emojis, as an import does. The maker still stops at three.
- The manifest, the WhatsApp metadata and the edit now clean emojis by one rule (`StickerWords.cleanEmojis`). A restored tag is also trimmed and kept once.
- *Edit* is the entry *Edit names and emojis* in the selection's **⋮** menu. It is offered when the selection is one sticker, also when that sticker is picked in two packs. Its sheet writes nothing before *Save*.
- The sheet already shows what was found automatically, read-only. Step 6 adds *Use as mine*.
- The maker shows emojis and names as chips in one row, under the title *Emojis and names*. *Add names* opens a field that takes one name after the other.
- The same picture made into a sticker twice keeps its row. It takes the newly typed names after its own and is closed to naming (`StickerDao.markMade`, found by `/code-review`).
- The manager's search field says *Search by name, emoji or pack*. `allStickerSections` and `AllStickerSectionsTest` are gone: the function is `StickerSearch.sections`, and its cases are in `StickerSearchTest`.
- A manifest entry is weighed at 160 bytes before its words, up from 128, for the two new fields.
- Not done, from `/simplify`: one value type for a sticker's emojis and names, and an index of folded words built once per library (`TECH_DEBT.md`).

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
  **(step-4)** The tab is `AllStickersTab`, and its sections come from one function
  (`allStickerSections`, wherever step 5 left it). A filter is one more argument there. *Name now*
  and *Do not name automatically* go into the menu of `StickerSelectionActions`. The ViewModel runs
  them through `editSelection`, which hands over the sticker ids by pack id.
- A refused request (`PERMISSION_DENIED`) switches nothing off. The manager says that naming is
  not available.
- **(step-5)** The columns are there, and this step needs no Room bump: `stickers.autoNameEn`,
  `autoNameDe`, `autoWords`, `autoText`, `autoEmojis`, `autoNameAllowed`, `nameRequestedAt`, and
  `sticker_packs.autoNameAllowed`. `StickerEntity.toDomain` builds `Sticker.autoName` when one of
  the five is not empty, so "has no automatic name" is `autoName == null`. No column says that
  the service answered `skipped`. Such a sticker is only held back by `nameRequestedAt`, so look
  at what a day later does with it.
- **(step-5)** Write an answer with a `StickerDao` query that sets the `auto…` columns only. It
  must not go through `setStickerWords` or `mergeStickers`, and it must not call `touchPack`: no
  manifest carries the automatic group. `mergeStickers` keeps those columns in both of its modes.
- **(step-5)** Who may be asked for is two flags, and both must hold: the sticker's
  `autoNameAllowed` and that of every pack that holds it. `Sticker.autoNameAllowed` and
  `StickerPack.autoNameAllowed` are on the domain models. A manifest carries both, so a write of
  either has to mark the packs like `setStickerWords` does. A restore of a manifest of an older
  build closes the maker's packs by their import key
  (`StickerPackEntity.autoNameAllowedByDefault`). A made sticker that was moved into an imported
  pack before step 5 reads as allowed (`TECH_DEBT.md`). Decide whether that needs a rule here.
- **(step-5)** The edit sheet is `StickerWordsEditor` in `ui/stickers/StickerWordsEditor.kt`. Its
  `AutomaticWords` already shows the group read-only. *Use as mine* goes there, and it can save
  through `StickerRepository.setStickerWords` plus the switch. The names it copies pass
  `StickerWords.cleanNames`, which keeps eight. The filters are one more argument of
  `StickerSearch.sections`. `StickerSearch` already reads the automatic names, words, text and
  emojis, so a stored answer is found with no further code.
- Tests: `StickerNameSyncTest` as a table of who is asked for (each of the six conditions alone),
  the answer check (too long, too many, not a list, an emoji that is a word), a test that an
  answer leaves `names` and `emojis` alone, `StickerNameWorkerTest`, `FirestoreStickerNameSourceTest`,
  the rules tests.
- Docs: `SCHEMA-FIRESTORE.md`, `FEATURE-MAP.md`, `SPEC.md`, the `docs/BACKLOG.md` checklist,
  CHANGELOG `Added`. `TECH_DEBT.md`: Lottie stickers get no automatic name, and anyone signed in
  can ask for any id.

**Approach**

- Order: `firestore.rules` and `firestore-rules-tests/stickerNames.test.js`, then
  `StickerNameSource` with its Firestore and pocketbase implementations and both DI modules, then
  `StickerDao` (the candidates, the answer, the two switches), `PreferencesDataStore`,
  `StickerNameAnswer` (the check), `StickerNameSync`, `StickerNameScheduler` + `StickerNameWorker`,
  `StickerSyncWorker` and `StickerLibrarySync` (the trigger and the sign-out), `StickerRepository` +
  `StickerRepositoryImpl`, `StickerSearch` (the filter), and last the manager's UI.
- A run looks a sticker up before it asks: one `get`, and a create only when the backend has no
  document. A second phone then finds the first phone's request or answer and writes nothing. The
  create is a transaction that writes only when the document is absent, because the rules refuse a
  second write and that refusal must not read as "naming is not available".
- `nameRequestedAt` is when this phone last asked for, or found, a record without a usable answer.
  Within a day of it the sticker is only looked up. A `skipped` or malformed record therefore costs
  one read per run and is never asked for twice.
- The spec's "growing delay, up to a day" is WorkManager's own backoff here, which stops growing at
  five hours. A trigger replaces a waiting run, so *Name now* is not held up by a backoff.
- The phone asks for WebP stickers only, and only for a sticker that some pack holds. The switch,
  the packs and the stickers are read again before every batch.
- Made stickers that an older build's manifest does not mark (step 5's note): no rule can tell them
  from imported ones. Nothing is added. The device checklist tells the owner to close them by hand.
- Tests: `StickerNameSyncTest` (the table, the answers, a refusal), `StickerNameAnswerTest`,
  `StickerNameWorkerTest`, `StickerNameSchedulerTest`, `FirestoreStickerNameSourceTest`, the rules
  tests, and additions to `StickerDaoTest`, `StickerRepositoryImplTest`, `StickerSyncWorkerTest`,
  `StickerSearchTest`, `StickerLibraryViewModelTest`, `StickerLibraryScreenTest` and
  `StickerWordsEditorTest`.
- Nothing in the code contradicts a §0 decision. Skills beyond the floor: `simplify`, because the
  diff will pass 600 lines.

**Shipped** `db3d6b82` (2026-10-11) — tier: max, tagged max. skills: simplify, code-review. Reviewer models: simplify: sonnet, sonnet, sonnet, sonnet; code-review: opus, sonnet. CHANGELOG entry (`Added`, under `[UNRELEASED] [1.43.0]`) is in `db3d6b82`, and its hash was added in the `docs(plan):` commit.
Departures (for sign-off):
- A run comes back by WorkManager's own backoff: after 1, 2, 4 … minutes, and at the latest every five hours. The plan said up to a day, and WorkManager grows no further (`docs/GOTCHAS.md`). A new trigger replaces a waiting run, so *Name now* and an open manager do not wait.
- The phone looks a sticker up before it asks: one `get`, and a create only when there is no document, inside a transaction. A second phone that holds the sticker creates nothing.
- "Its file is on the backend" is `remoteUrl`, or a synced pack that holds the sticker. A restored row has no `remoteUrl` until the sticker is first sent. With the plan's test alone, nothing was asked for after the update or on a second phone (found by `/code-review`).
- `nameRequestedAt` is also set by a look that finds a request, a `skipped` record or a malformed answer. Such a sticker is read once per run and never asked for twice. `skipped` is final for the phones, because the rules let no phone write over a document.
- The phone asks for WebP stickers only, and only for a sticker that some pack holds.
- An answer is taken whole or not at all. The limits are a name of 80 characters, 16 words of 40, a text of 128 and 3 emojis. An emoji has no letter and no space.
- An answer is written by a transaction that reads the row and writes it back with the `auto…` columns changed, not by one `UPDATE` query. The lists go through Room's converter that way.
- A sticker that leaves a pack left out of naming is closed itself: by a move, a removal, a merge, the pack's delete, and a star taken off in a *Favourites* that is left out. Without this, a merge opened what its owner had closed (found by `/code-review`). The owner opens such a sticker again with *Name now*.
- *Name now* opens the stickers and asks. For a sticker in a pack that is left out it only says which pack holds it back, and the pack stays as it is. *Name now* is offered only while the switch is on.
- *Use as mine* adds the German name, the English name and the words to the names, up to eight in all. Its save closes the sticker to naming in the same write (`setStickerWords(stopAutoNaming)`).
- *No name* shows a sticker with no name of a person's and nothing found automatically. *Named automatically* shows every sticker the service named, also after *Use as mine*.
- The switch is the first row of the *Packs* tab. It is kept per device and cleared at sign-out, where the naming work is cancelled too. The three consent writes finish although their caller is cancelled (`docs/PATTERNS.md`).
- A refusal is kept in `PreferencesDataStore` (`sticker_naming_refused`), so the manager can show it after the worker's process is gone. The next run that reaches the backend takes it back.
- The rules also cap `format` at 16 characters. `ConsentNotice` is new, and `OnlineMediaNotice` draws with it (from `/simplify`).
- No rule was added for a made sticker that an older manifest does not mark. The device checklist in `docs/BACKLOG.md` tells the owner to close such stickers by hand before switching on.
- The rules tests did not run on this machine: `npm ci` needs an approval that a headless run cannot give. `.github/workflows/firestore-rules.yml` runs them on the push. Look at that run before `firestore.rules` is deployed.
- `/code-review` was the standards-and-spec skill. It hunted no bugs beyond the two it found on the way, and step 8 carries the notes.
- Not done, from `/simplify`: one write per batch in a naming run, a shared helper for the two schedulers' enqueue, and a naming trigger at every first upload (`TECH_DEBT.md`).

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
- **(step-6)** The document is `stickerNames/{stickerId}`, and its shape is in
  `docs/SCHEMA-FIRESTORE.md`. A request's `format` is the name of the sticker's format, `WEBP`, and
  not a file extension. Map `WEBP` to `webp` and answer every other value `skipped`. It is typed
  by a client, so treat it as untrusted. The rules cap it at 16 characters.
- **(step-6)** The answer's fields are `nameEn`, `nameDe`, `words`, `text`, `emojis`, `model` and
  `namedAt`, with `status: "done"`. `words` is one list: `words_en` and `words_de` of the model's
  answer joined. Keep `format` and `requestedAt` in the document.
- **(step-6)** The phone's limits are in `StickerNameAnswer`: a name of at most 80 characters, at
  most 16 words of at most 40 characters, a text of at most 128 characters, at most 3 emojis. An
  emoji has no letter and no space, and at least one character outside ASCII. One field over a
  limit makes the phone drop the whole answer. So the service cuts a long `text`, drops a word or
  an emoji that does not fit, and asks the model again for a name that is too long. It never
  writes a field that breaks a limit.
- **(step-6)** `skipped` is final for the phones. The rules let no phone create a second request
  over a document, and a phone only reads a skipped one again. Answer `skipped` only for a
  failure that will not pass. To have a sticker named after all, the service or the owner deletes
  its document, and a phone asks again within a day.
- **(step-6)** The phone asks for WebP stickers only. A request for a Lottie sticker can still
  come from another client, so the `skipped` answer for one stays.
- **(step-6)** A phone fetches answers when the manager opens, and otherwise after 1, 2, 4 …
  minutes, up to five hours. For the end-to-end check in `setup.sh`, tell the owner to open the
  manager. The listener's query (`status == "pending"`) needs no composite index. The rules tests
  in `firestore-rules-tests/stickerNames.test.js` did not run on the machine that wrote them:
  `.github/workflows/firestore-rules.yml` runs them on the push. Look at that run before the
  rules are deployed.

**Approach**

- Order, all under `tools/sticker-namer/`: `config.py` (the env file), `answer.py` (the schema, the
  question, the limits of `StickerNameAnswer`), `picture.py` (hash, first frame to PNG), `card.py`
  (`nvidia-smi`), `engine.py` (both clients), `namer.py` (one request, and the loop),
  `firebase_store.py` (the only file that imports `firebase-admin`), `evaluate.py`, the unit,
  `setup.sh` from the `wizard` skill's template, the README. Then `FEATURE-MAP.md` and
  `ARCHITECTURE.md`.
- `namer.py` sees Firestore and Storage through two small interfaces, so the tests run with fakes
  and without `firebase-admin`. The listener only wakes the loop. The loop reads the pending
  requests itself, which is also how a request that was left pending is tried again.
- An answer is written in a transaction that writes only while the document is still `pending`.
  That covers a request answered during the wait for the graphics card, and two namers at once.
- The card is only looked at while the model is not loaded. A loaded model fills the card itself,
  and the namer would wait for its own model to leave.
- A file that is not in Storage yet may still be uploading. Such a request stays pending for a day
  and is answered `skipped` after that.
- Tests (`pytest`): the answer check, the picture (hash, Lottie, a broken file), the card, both
  engine request bodies, the config, and `namer` end to end with fakes for the six cases the step
  lists.
- This session cannot run Python: the headless allowlist refuses `python` and `pytest`. A workflow
  of its own, like `firestore-rules.yml`, runs the tool's tests on the push. It is not part of the
  CI gate. `setup.sh` runs them too, before anything else is set up.
- Nothing in the code contradicts a §0 decision. Skills beyond the floor: `simplify`, because the
  diff will pass 600 lines.

**Shipped** `6ca0aedf` (2026-10-11) — tier: strong, tagged strong. skills: code-review, simplify. Reviewer models: code-review: sonnet, opus; simplify: sonnet, sonnet, sonnet, sonnet. No CHANGELOG entry: nothing in the app changes.
Departures (for sign-off):
- **No Python ran.** The headless session's allowlist refuses `python`, `pytest`, `bash -n` and `shellcheck`. The tool's tests, the two pinned packages (`firebase-admin==7.1.0`, `Pillow==12.0.0`, and `pytest==8.4.2` for the tests) and `setup.sh` were read, by the author and by the `/code-review` reviewer, and never executed. The Gradle gate is green, and it does not cover the tool. Stage 1 of `setup.sh` runs the tests first, and `docs/BACKLOG.md` lists what has to be seen on the PC.
- `.github/workflows/sticker-namer.yml` is new. It runs the tool's tests when the folder changes. The plan said the tool is not part of the CI gate, and it is not: the workflow is separate, like `firestore-rules.yml`.
- Neither request body in `engine.py` was sent to an engine. Both were written from what the engines document, not copied from the test of 2026-10-10. Thinking is switched off with `think: false` for Ollama and with `chat_template_kwargs.enable_thinking` for llama.cpp. `evaluate.py` shows whether both hold.
- The commit prefix is `chore(stickers)`, not `feat`, because a `feat` owes a CHANGELOG entry and a version bump.
- The listener only wakes the loop, and the loop reads the pending requests itself. A round that left a request pending comes back after 1, 2, 4 … minutes, up to 30. A new request ends the wait at once.
- The cheap refusals come before the wait for the graphics card: the id, the format, the file, the hash, the picture. The card is only looked at while the model is not loaded (`/api/ps` for Ollama). A loaded model fills the card itself. llama.cpp's server always counts as loaded. Without `nvidia-smi` the service does not wait.
- An answer is written in a transaction, only while the document is still `pending`. After a wait for the card the document is read once more, so a request answered meanwhile costs no model call.
- A file that is not in Storage leaves the request pending for a day after `requestedAt`, then it is answered `skipped`. An upload may still be running.
- The model is asked up to three times. Three answers that break a limit end in `skipped`. An engine that refuses the request (a 4xx status, such as a `MODEL` it does not have) or answers no JSON leaves the request pending: a wrong setting must not close the library for good (found by `/code-review`). A 5xx status or no connection ends the batch.
- Also refused, with `skipped`: an id that is not 64 hex digits, a file above 2 MiB, a picture above 2048 × 2048 pixels, a file that is no WebP.
- `words` keeps at most eight words per language. A word or a name that holds *sticker* is dropped or asked for again.
- The service does not start with a key file that group or others can read, and exits with status 78, after which the unit does not restart it.
- A setting was added, `KEEP_ALIVE` (default `2m`). `MIN_FREE_VRAM_MB` defaults to 10000, which fits `gemma4:12b`. For `gemma4:26b` on the 16 GB card the owner sets it at the checkpoint. The wizard also writes `PROJECT_ID` into the env file, to remember it for a second run.
- The unit has no ordering after the network. A user unit cannot be ordered after the system's network target, so the service treats a missing network as a failure that passes. The wizard offers `loginctl enable-linger`.
- The roles are *Cloud Datastore User* and *Storage Object Viewer*, on the project. The viewer role is not narrowed to the one bucket.
- `setup.sh` was written out by hand with the `wizard` skill's library copied in, because the sandbox refused `cp` from the skill's folder. `diff` against `template.sh` shows the library is identical. It has seven stages.
- `docs/TESTING.md` and `docs/BACKLOG.md` were edited too, beyond the docs the step lists.
- Not done, from `/simplify` and `/code-review`: a class per engine and one settings type, the work list taken from the listener's snapshot, a not-before time per refused sticker (`TECH_DEBT.md`).

**‖ Checkpoint.** The owner deploys `firestore.rules`, runs `setup.sh`, and tests about 40 of
their own stickers with `evaluate.py`. `MODEL` in the env file is set to the model the owner picks.
Then automatic naming is switched on, on both phones.
**(step-7)** No line of `tools/sticker-namer/` has run yet. Stage 1 of `setup.sh` runs its tests. A
failing test there is a fault in the tool and belongs fixed before the service is switched on.
Work through the list *The sticker naming service on the PC* in `docs/BACKLOG.md`. Set
`MIN_FREE_VRAM_MB` with `MODEL`: the default of 10000 fits the 12b model.

### Step 7a — See what a sticker is found by: one sticker sheet in three places, and a list view (UI + state)

The words of a sticker are only in *Edit names and emojis*, one sticker at a time and three taps
deep. Nobody can look through a few hundred stickers to judge what the model wrote.

- **One sheet, `StickerDetailSheet` in `ui/stickers/`.** It slides up from the bottom and shows,
  from the top:
  1. the sticker, a little larger than in a chat, and a tap enlarges it. This is `StickerPreview`
     of `ui/chat/StickerActionsSheet.kt`, moved beside the sheet;
  2. *Your names* and the emojis, read-only, as one line each;
  3. *Found automatically*: the German and the English name, the search words, and the text on
     the sticker. `AutomaticWords` in `StickerWordsEditor.kt` already draws this group. Draw it
     from one function in both places;
  4. the rows: *Add to favourites* or *Remove from favourites*, *View pack* (in a chat only), and
     *Edit names and emojis*, which opens `StickerWordsSheet`.
  A group with nothing in it is left out. A sticker with no words at all says *No names yet*.
- **In a chat.** `StickerActionsSheet` becomes a caller of `StickerDetailSheet`. The words come
  from this phone's library row for `Message.stickerId`. A sticker that is not in the library
  shows the picture and the rows it has today, and no words and no *Edit*. The sheet asks nobody
  for a name: it reads Room only, and it makes no request to `stickerNames`.
- **In the manager, inside a pack.** A tap on a sticker of `PackGrid` opens the sheet. Today the
  tap does nothing (`NO_TAP`). While a selection runs, a tap still picks.
- **In the manager, *All stickers*.** A tap opens the sheet, by the same rule.
- **A list view in *All stickers*.** A button beside the search field switches between the grid
  and a list. A list row is the sticker at 56 dp, then one line with the names (yours first, then
  the German and the English automatic name) and one line with the search words, each cut at one
  line. A row with no words says *No name*. The sections, the search and the two filters are the
  same in both views (`StickerSearch.sections`). A long press starts a selection in the list too.
  The choice is kept while the manager is open (`SavedStateHandle`), and the grid is the default.
- The star in the manager's sheet uses `StickerRepository.toggleFavourite`.
- **The parameter ceiling.** `ChatScreen` and `StickerLibraryScreen` gain code. Hand the sheet
  one state object and one callbacks object. After the gate, run the register check of
  `docs/GOTCHAS.md` on a debug APK and write the highest count of the touched files into the
  Shipped block. `PackRow` stands at 127 of 255.
- Tests: a Robolectric test of the sheet (words of both groups, a sticker with none, a sticker
  that is not in the library, the rows per host), `StickerLibraryViewModelTest` for the view
  switch and the open sticker, a screen test that a tap opens the sheet and that a tap in a
  selection picks, and the chat's test of `StickerActionsSheet` kept green.
- Docs: `FEATURE-MAP.md`, a checklist in `docs/BACKLOG.md` § *Pending on-device verification*,
  CHANGELOG `Added`.

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
     **(step-3)** Also: the drag in `PackList`. It keeps a local copy of the packs (`shown`) and a
     `dragged` flag, and one `LaunchedEffect` both sends the drop and takes over new packs.
     `StickerLibraryViewModel.reorderPacks` sorts the state at once and puts `observedPacks` back
     when the write fails. Look at a write that fails at once, where the screen may never see the
     state change, at packs that arrive during a drag, and at a pack whose switch is flipped while
     another is dragged. `deleteSelected` stops at the first failure with the earlier packs gone.
     Step 3 had `/simplify` only, and no test drags a row.
     **(step-4)** Also: a selection's action is several repository calls
     (`StickerLibraryViewModel.editSelection`, `untilFailure`), and the selection is cleared
     before they finish. Look at a call that fails in the middle, at `newPackFromSelected` when
     `createPack` succeeds and a `removeStickers` fails, and at `favouriteSelected`, which reads the
     favourites from the state and then toggles. A selection stays while a search hides its
     cells, and an action still takes the hidden ones. The mark of the WhatsApp import moves in
     `viewModelScope` after `importFrom`: look at a screen left during the import, at an import
     of a part of the new files, at a file with `lastModified` 0, and at a phone whose clock or
     whose folder was restored. `WhatsAppImportState.shownFiles` and `setShowAllWhatsApp` decide
     what an import can take. Step 4 had `/simplify` only.
  3. Names in the app: `StickerSearch`, `StickerNameSync`, `StickerNameWorker`, the name sources,
     `firestore.rules`. Nothing is asked for while the switch is off. A made sticker, a pack that
     is switched off and a sticker that is switched off are never asked for. No answer changes
     `names` or `emojis`. A hostile answer.
     **(step-5 /code-review)** Also the words a person typed. `StickerDao.applyRemotePack` hands
     `statesWords` to `mergeStickers(takeWords)`, which replaces a known sticker's emojis, names
     and `autoNameAllowed` from a newer manifest of the same pack. Look at a sticker in two packs
     whose manifests disagree, at a phone that changes a pack before it has applied an edit, at a
     manifest that states names for some stickers only, and at a restore that flips
     `autoNameAllowed` back to true. Look at `StickerDao.markMade` and `setStickerWords` beside a
     running restore, at `StickerManifest.stickersOf` for an entry without `autoNameAllowed`, and
     at `StickerSearch` with a query of many words on a large library, which runs in composition.
     Step 5's `/code-review` was the standards-and-spec skill, and its `/simplify` hunted no bugs.
     **(step-6)** Also the naming run. `StickerNameScheduler` queues it with `REPLACE`, so a run
     is cancelled in the middle by every new trigger. Look at a cancel between
     `StickerNameSource.request` and `StickerDao.setNameRequestedAt`, at two runs that overlap on
     `StickerNameSync.runLock`, and at the `looked` set when rows change between two batches.
     Look at the consent writes in `StickerRepositoryImpl`, which run under `NonCancellable`.
     Look at a restore that opens a sticker again its owner had closed
     (`mergeStickers(takeWords)`), which the next run then asks for. Look at
     `StickerDao.keepOutOfNaming`: every way a sticker leaves a pack that is left out has to
     pass it, and `applyRemotePack`, `removeFromEveryPack` and `createPack` do not call it. Look
     at `StickerDao.getNameCandidates`, which takes a synced pack as proof that a file is on the
     backend, for a pack added through *View pack* whose files were never uploaded. Look at `PreferencesDataStore.stickerNamingRefusedFlow` after a sign-out in
     the middle of a run, at `StickerSyncWorker.uploadedFiles` when an upload fails half-way, and
     at `StickerNameAnswer` with an answer written to pass the check and still mislead a search.
     The rules: a `get` of any sticker's name by anyone signed in, and whether any write other
     than the request passes. Step 6's `/code-review` was the standards-and-spec skill, and the
     rules tests ran only in CI.
  4. The service: `tools/sticker-namer/`. A request that is answered twice. A crash in the middle
     of a batch. What a log line holds. Where the key can be read from.
     **(step-7)** Run the tool's tests first (`python -m pytest tools/sticker-namer`, in an
     environment with `requirements-dev.txt`). Step 7's session could run no Python, so a red
     test is a finding of its own. `firebase_store.py` has no test: read `answer` (the
     transaction and `namedAt`), `read` (`get_blob`, the size check) and `listen` against the
     pinned `firebase-admin`. Look at `namer.py`'s loop: `wake` is cleared before a round and set
     by the listener, also by the service's own answers. Look at the three ends of a request in
     `Namer._answer_fields`, and at what makes an end final: a `skipped` cannot be undone by a
     phone. Look at `engine.http_json`, which decides between `EngineDown` (the batch ends) and
     `NoAnswer` (the request waits), and at a sticker the engine always refuses, which is asked
     three times per round for ever. Look at `answer.fields_of` beside `StickerNameAnswer.of`
     for a field the service writes and the phone drops: lengths are UTF-16 units on both sides.
     Look at `picture.png_of` with a hostile WebP, and at `config.parse_env` beside what
     `setup.sh`'s `write_env` writes. Step 7's `/code-review` was the standards-and-spec skill.
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
