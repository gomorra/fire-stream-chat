# Sticker duplicates

Status: written on 2026-10-11 at the owner's request. Waiting for the owner's approval.

## Context

The owner's pack *Memoji* holds the same glass of beer four times. The library knows a sticker by
the SHA-256 of its file. WhatsApp saves one picture again with other data inside the file, such as
the pack it came from. The files differ, so the app holds four stickers with one picture.

`docs/plans/sticker-manager-and-names.md` built the manager this plan adds to. Run this plan after
that plan's step 8 is merged into main, because both change `StickerDao` and `StickerRepositoryImpl`.

## Decisions (agreed with the owner, 2026-10-11)

| Question | Decision |
|---|---|
| What counts as the same | The same pixels: the same size, and every frame equal. The file's other data does not count |
| Almost the same picture | Not in this plan. A copy that was compressed again stays a sticker of its own. A second level that asks about each group can follow when the first leaves too many behind |
| Who deletes | The person, by a tap. The app proposes groups and deletes nothing by itself |
| Which one stays | The one with names of the person's, else a favourite, else the one used last, else the first in its pack |
| What the kept one takes over | The names and emojis of the others, up to the limits, and a place in every pack that held one of them |
| A new import | A file whose picture the library already holds is skipped and counted |

## Open points for the step to settle

- Whether the owner's four beers have the same pixels was not checked. No file of them was on the
  PC. The step's first test uses two real WhatsApp files of one picture, if the owner provides
  them, and files made by the test otherwise.

## Steps

Order: 1

### Step 1 — Find stickers with the same picture, keep one, and skip them at import — skills: code-review; model: strong

- **A picture hash.** `data/sticker/StickerPixels.kt`: the SHA-256 over a sticker's size and the
  ARGB pixels of every frame, with each frame's duration for an animated WebP. A Lottie sticker
  has no hash and is never in a group. A file that cannot be decoded has none either.
- **Where the hashes live.** In a file under `filesDir/stickers/`, by sticker id. A sticker's id is
  the hash of its file, so an entry never goes stale. Room gets no column and `AppDatabase` no
  bump: a bump empties the library on every phone. A missing entry is computed when it is asked
  for, through `MediaProcessingLimiter`.
- **`StickerRepository.findDuplicates(): Result<List<StickerDuplicateGroup>>`.** A group is the
  stickers of one picture hash, two or more, with the one that stays named first by the rule in
  *Decisions*. A sticker whose file is not on the phone is left out.
- **`StickerRepository.keepOne(group): Result<Unit>`.** In one `StickerDao` transaction the kept
  sticker takes the others' names and emojis (`StickerWords`), joins every pack that held one of
  them at that sticker's place, and takes the star when one of them had it. Then the others go
  the way `deleteStickers` sends them, so they are remembered as deleted and a WhatsApp import
  does not bring them back. The kept sticker stays open to automatic naming only when every
  sticker of the group was.
- **The manager.** The **⋮** menu of *All stickers* gets *Find duplicates*. It shows a progress
  line while the hashes are computed, then a list of groups: the picture, how often, and in which
  packs. A group has *Keep one*, and the list has *Keep one of each* with one question that names
  the count of stickers that go. No group says *No duplicates found*.
- **The import.** `importFrom` skips a file whose picture hash the library already holds, with
  and without `skipKnown`. `StickerImportResult` gets `samePicture: Int`, and the import's summary
  counts it: *3 skipped, same picture as a sticker you have*.
- **The ceiling.** Hand new composables one state object and one callbacks object. Run the
  register check of `docs/GOTCHAS.md` on a debug APK, or say in the Shipped block that it could
  not run.
- Tests: `StickerPixelsTest` (two files of one picture with different metadata hash the same, a
  one-pixel change does not, an animated pair that differs in one frame does not, a Lottie file
  has none), `StickerRepositoryImplTest` for `findDuplicates` and `keepOne` (which one stays by
  each rule, the words and packs taken over, the others remembered as deleted, a group across
  two packs, naming closed when one was closed), the import's count, `StickerLibraryViewModelTest`,
  and a screen test of the groups list.
- Docs: `FEATURE-MAP.md`, `SPEC.md`, a checklist in `docs/BACKLOG.md` § *Pending on-device
  verification*, CHANGELOG `Added`.

## Verification

- **Gate:** `./gradlew test` and `./gradlew assembleDebug`.
- **On the phone:** *Find duplicates* shows the beer of *Memoji* as one group of four. *Keep one*
  leaves one beer. A WhatsApp import with *Show all* and *Select all* brings none back, and its
  summary counts them.

## Run

```bash
scripts/run-plan.sh docs/plans/sticker-duplicates.md --dry-run
scripts/run-plan.sh docs/plans/sticker-duplicates.md
```
