# Image editing for the send-preview and the fullscreen viewer

Status: **Phases 1–5 shipped; Phase 6 planned, not implemented.** Phase 1 (the
toolbar shell, per-image HD and download), Phase 2 (the rasterizer, the fit mapper
and the live preview-level history) and Phase 3 (the adjust screen) landed on
2026-09-09; Phase 4 (the draw screen) on 2026-09-10. The rest is still the agreed
design awaiting its implementation sessions. **Phases 3 and 4 are build- and
test-verified only — nothing in either has been on hardware**, and every unchecked
item is listed in `docs/BACKLOG.md` §*Pending on-device verification*.

Goal: bring the pre-send preview (`ImagePreviewScreen`) up to WhatsApp's editor —
download, per-image HD toggle, an adjust screen (rotate/flip/straighten/crop/
resize), movable emoji stickers, a paint screen — plus text, blur-to-redact, and
an entry point from the fullscreen viewer.

**UI direction: Option A, "overlay rail"** (chosen 2026-09-09 from three drafted
directions). Entry icons float top-right over the photo; each tool is a full-screen
overlay with cancel / undo / redo / layer-eye / done in one top bar; the history
controls appear top-left only once an edit exists. The two directions not taken were
a thumb-reachable bottom dock (B) and a single editor with mode tabs and a layer
list (C) — C is noted in §5, since it would have reopened §2.1.

**Screen mockups** (all three directions, 15 artboards):
<https://claude.ai/code/artifact/f21151fb-7d70-4f98-99b9-52a0bd816541> — row A is the
chosen direction (preview → adjust → draw → overlay, then the shared picker in the
composer and its search-expanded state). Frames are drawn from the real theme: Plus
Jakarta Sans, `#F26A1F`, the `Fs*` dark surfaces, and the 40/36 dp circular controls
and 6/12/24 dp radii already in `ImagePreviewScreen` and `FullscreenImageViewer`. The
`.dc.html` sources were only ever in a session scratchpad; to change the canvas from a
later session, extract them back out of the published page (the `design` skill's
"Updating an existing canvas") rather than redrawing.

**Build gate for this work** (verified 2026-09-09 in a cloud container, see CLAUDE.md):
`./gradlew :app:testFirebaseDebugUnitTest` — 910 tests, and the only expected failure is
`ApkDownloaderTest.unresolvable host…`, which fails behind the sandbox proxy and nowhere
else. `./gradlew :app:assembleFirebaseDebug` for the build half. Use the flavor-qualified
tasks: bare `test` also builds pocketbase, which is not maintained yet.

`Order: 1 → 2 → 3 → 4 → 5a → 5b → 6`

---

## 1. What already exists

| Piece | Where | State |
|---|---|---|
| Pre-send batch review | `ui/chat/ImagePreviewScreen.kt` | Pager + per-item caption + thumbnail strip + remove. **No editing at all.** |
| Fullscreen viewer / gallery | `ui/chat/FullscreenImageViewer.kt` | Zoom/pan, Close, and **Download already implemented** (`onSaveToDownloads`) |
| Zoom/pan surface | `ui/chat/ZoomableBox.kt` | Shared, pager-aware, gesture-reconciled. Reuse; never fork it. |
| The queued item | `ui/chat/PendingMedia.kt` | `uri` + `mimeType` + `caption` + `ListSaver` |
| Compress / EXIF / rotate | `data/util/ImageCompressor.kt` | `processImage(uri, fullQuality)` — `MAX_DIMENSION = 1600`, JPEG q80; holds a limiter permit |
| Concurrency bound | `data/util/MediaProcessingLimiter.kt` | Process-wide cap of 2 full bitmaps. Callers own ordering only. |
| Save to Downloads | `data/util/MediaFileManager.saveToDownloads` | MediaStore, API 29+ |
| HD flag | `Message.isHd`, `PreferencesDataStore.sendImagesFullQualityFlow` | **Global setting only** — read once at `MessageRepositoryImpl:645`. Badge at `MessageBubble:882`. |
| Large-image decode workaround | `ui/components/ScaledImageDecoder.kt` | `ImageDecoder`-based; large camera originals decode **black** through a subsampled `BitmapFactory` |

Two of the five asks are therefore partly done already: **download exists** in the
fullscreen viewer (and is missing from the preview screen), and **HD exists but is
not per-image**.

---

## 2. Decisions

### 2.1 Edits rasterize per editor screen

Each editor screen takes a source `Uri`, and on **Done** writes a new full-size
JPEG into `cacheDir/edits/` and hands that URI back. `PendingMedia` points at the
newest file; the pick's original URI is kept alongside it for *Revert*.

The alternative — accumulating an `ImageEdit` value object and rasterizing once at
send — was considered and rejected here. It is lossless and fully revertible, but
it needs coordinate math that survives editing in one space and re-cropping in
another, a domain-layer value object, and a renderer folded into `ImageCompressor`.
Rasterize-per-screen is what WhatsApp does, keeps each screen's coordinate mapping
to a single uniform fit-rect scale, and — the decisive part — **leaves the send
pipeline completely untouched**: `sendMediaMessage` receives a different URI and
is otherwise unaware that editing exists.

Consequences to accept explicitly:

- **Generational JPEG loss.** Intermediates are written at **quality 95**, only the
  final send goes through `ImageCompressor` at q80 (or q100 for HD). Three edit
  passes at q95 followed by one q80 is not visually distinguishable from a single
  q80; this is a real cost, it is bounded, and it is documented rather than denied.
- **Revert is file-based**, not model-based: `PendingMedia.originalUri` is kept
  untouched, and the rasterized steps in between *are* the undo/redo history (§2.7).
- **A working-resolution ceiling is required.** Rasterizing at true source
  resolution OOMs on a 108 MP camera original. Edits rasterize at **min(source,
  4096 px long edge)**. So an HD send of an *edited* photo is capped at 4096 px
  while an HD send of an *untouched* photo stays at full resolution. That is a
  deliberate trade and belongs in the release note.

### 2.2 One rasterizer, in the data layer

`data/util/ImageEditRasterizer.kt` owns every full-res bitmap operation:

```
suspend fun rasterize(source: Uri, ops: List<RasterOp>): Uri   // holds a permit
suspend fun estimateSize(source: Uri, hd: Boolean): Long
fun discard(uris: Collection<Uri>)                              // cache cleanup
```

`RasterOp` is a sealed class declared **in that file** — `Rotate`, `Flip`,
`Straighten`, `Crop`, `Resize`, `Strokes`, `Overlays` — carrying plain floats and
`Long` ARGB colours, never Compose types. Reason: `ArchitectureTest` forbids
`data → ui` imports, so the UI converts its Compose-flavoured editor state into
`RasterOp`s at the boundary. Keeping the ops here also keeps
`MediaProcessingLimiter` where PATTERNS.md says it belongs — inside the data layer,
owning the concurrency bound, with callers owning ordering only.

This adds exactly one entry to `UI_ALLOWED_DATA_IMPORTS` in `ArchitectureTest`
(`com.firestream.chat.data.util.ImageEditRasterizer`) plus the matching line in
`TECH_DEBT.md`, consistent with `MediaFileManager` already being on that list.
It is a platform/file adapter, not a repository.

Editor composables never touch Hilt. The hosting ViewModel injects the rasterizer
and passes it down as `suspend (List<RasterOp>) -> Uri`, so every editor screen is
constructible in a Robolectric test with a fake.

### 2.3 Coordinate mapping: one helper, four consumers

Every overlay tool needs "where on the bitmap did the finger land". Because each
screen displays the *current* image axis-aligned under `ContentScale.Fit`, the
mapping is a single uniform scale plus an offset — no inverse rotation anywhere.

`ui/chat/imageedit/ImageFitMapper.kt` — pure Kotlin, no Android imports, JVM-unit-
testable: given canvas size and bitmap size, produce the fitted image rect and map
`screen ↔ normalized ↔ bitmap px` in both directions. Used by draw, blur, sticker
and text; the crop screen uses the same rect to place its handles.

Overlay geometry is stored **normalized to the image being edited**, so it survives
rotation of the device and a screen-size change without drifting.

### 2.4 The editors are full-screen overlays, not NavHost routes

`pendingMedia` is `rememberSaveable` state local to `ChatScreen`, and
`ImagePreviewScreen` is already an `AnimatedVisibility` overlay rather than a
route. Making the editors routes would force the whole batch through a shared
ViewModel or a `SavedStateHandle` round-trip for no user-visible gain — "new
screen" is a statement about UX, not about the navigation graph. Each editor is a
standalone composable `(Uri, onDone: (Uri) -> Unit, onCancel: () -> Unit)`, so the
share path and the fullscreen viewer can host it just as easily.

### 2.5 Per-image HD

`PendingMedia.isHd: Boolean?` — `null` means *follow the global preference*, so
nothing changes for anyone who never touches the toggle. Threaded as
`MessageRepository.sendMediaMessage(..., isHd: Boolean? = null)`; the default keeps
every existing call site compiling, and `MessageRepositoryImpl` keeps
`sendImagesFullQualityFlow.first()` as the fallback when it is null.

**No Room migration.** `Message.isHd` already exists, is already persisted, already
synced, and already renders a badge.

Size labels (`Standard · ~340 KB` / `HD · ~3.9 MB`) are **estimated** from pixel
dimensions and source file size, computed lazily for the current image only when
the HD sheet opens. Measuring exactly means a second full decode+encode per image,
which is precisely what the limiter exists to prevent. The sheet says "about".

**Interaction with the resize tool:** an explicit resize wins. If the user sets an
output long edge in the adjust screen, that is the resolution; HD then governs only
the encode quality (100 vs 80), not a second downscale. Without an explicit resize,
HD/standard behaves exactly as it does today.

### 2.6 Editing from the fullscreen viewer means sending a new image

A sent message is immutable — its bytes are in storage and in other people's
devices. The fullscreen viewer's **Edit** action therefore copies the displayed
image into the edit cache, wraps it in a `PendingMedia`, and opens the ordinary
preview screen; the result is a **new** send. If the image has no local file yet,
`MediaFileManager.downloadAndSave` runs first behind a spinner.

No HD toggle in the fullscreen viewer: you cannot un-compress a photo that arrived
compressed, and offering the control would imply otherwise.

### 2.7 Undo / redo, and turning the layer off — two levels, because rasterizing flattens

Rasterize-per-screen (§2.1) means the moment you press **Done**, the strokes and
stickers stop being objects and become pixels. So undo, redo and "hide the layer"
each have to exist at *two* levels, and conflating them would produce a button that
silently does nothing on one screen and something drastic on the other.

**Inside an editor screen — the layer is still live.**

- **Undo / Redo** move along the working list: the strokes on the draw screen, the
  placed emoji and text runs on the overlay screen, the transform steps (rotate /
  flip / straighten / crop) on the adjust screen. Undo moves back, redo moves
  forward, each disabling itself at its end of the list. Cheap, because none of it
  is flattened yet — the objects are still in memory.
- **Linear history, not a tree**: acting after an undo discards what redo was
  holding. The alternative is branch management inside a send-preview, which nobody
  wants and nobody would find.
- **Layer visibility** is an eye / eye-off toggle sitting with the *tools*, not with
  the actions, because it is a view control: it hides everything you have added so
  you can see the photo underneath and judge whether the redaction covers what you
  meant it to. **It never changes what gets written.** Pressing Done with the layer
  hidden still flattens it, and re-entering the screen always comes back visible.
  A control that could silently discard your work by being left in the wrong
  position is not worth the ambiguity it saves.

**On the preview screen — the layer is already pixels.**

Here the chosen model pays for itself. Every Done writes a *new* file and the
previous one is still sitting in `cacheDir/edits/`, so that chain of files **is**
the undo history — nothing extra has to be recorded to get one.

Because redo has to be able to go forward again, undo cannot delete the file it
steps off. So the history is a **cursor**, not a stack:

```
PendingMedia(
  originalUri,          // the pick, never written to
  editHistory,          // every rasterized step, oldest → newest
  editCursor,           // 0 = the original; n = editHistory[n - 1]
  uri = if (editCursor == 0) originalUri else editHistory[editCursor - 1],
)
```

- **Undo** is `editCursor--`, **Redo** is `editCursor++`, each walking one whole
  editor visit at a time — undo the crop, then undo the drawing, all the way back
  to the original, and forward again. This is the answer to "I rotated it three
  screens ago and now I regret it", which the flattening model otherwise makes
  impossible.
- **Finishing an edit while the cursor is not at the top truncates the tail**: the
  abandoned files are deleted immediately and the new step is appended. Linear
  history, same rule as inside the editors, and it is what keeps the disk bounded
  now that undo no longer deletes anything itself.
- **Original ⇄ Edited** is the preview-level layer toggle, and it is simply a jump
  between the two ends of that same axis: off sends `originalUri`, on returns to the
  step you were on (the top, the first time). Deliberately *not* a separate
  `editsEnabled` flag — two fields could disagree, and "edits are off but undo says
  I'm on step 3" is a state nobody can reason about. One cursor, one truth.
- History is **per item**, so undo acts on the page you are looking at, not on the
  batch. The thumbnail strip renders each item at its own cursor, so the strip never
  disagrees with what the send button will send.

The cost of keeping redo is that intermediates now live for the whole preview
session rather than being freed on undo — which is what makes the cap and the cache
budget in §4 load-bearing rather than tidy.

### 2.8 One picker, four hosts, host-declared tabs

The editor's sticker step needs an emoji picker, and the app already has one —
`EmojiHandlerPanel`, ~700 lines mounted from three places (`ChatScreen:1962`
composer, `ChatScreen:2138` reaction sheet, `ImagePreviewScreen:330` caption bar).
Adding a fourth copy for the editor would be the third mistake in a row. It becomes
a shared module instead, and the editor is the reason to build the shell now.

**The chrome.** A circular **search button** on the left, a segmented **island** of
whatever tabs the host declares, and — where the host has a selection to act on — a
**delete button** at the right end of the same row. The composer's island is
Emoji · Sticker · GIF; the editor's is Emoji · Sticker · Text · Shapes. Tapping
search expands the field out of the button and the island slides right and fades;
the field's × collapses it and brings the island back. One row, two states, and the
tab you are in stays visible whenever you are not typing.

**The island's tabs are declared by the host, not fixed** — this is the part that
keeps it honest:

| Host | Emoji | Sticker | GIF | Text | Shapes | Why |
|---|:--:|:--:|:--:|:--:|:--:|---|
| Composer | ✓ | later | later | — | — | Sending a sticker or GIF needs a new `MessageType` + a Room version bump; text is what the composer already is |
| Reaction sheet | ✓ | — | — | — | — | A reaction is one grapheme stored on the message; a sticker reaction is a different data model |
| Caption bar (preview) | ✓ | — | — | — | — | It types into a text field |
| **Editor overlay** (Phase 5) | ✓ | ✓ | — | ✓ | ✓ | Everything here is placed and flattened into the JPEG — which an animation cannot be. Text and Shapes are placed objects, so they belong to this host alone |

A host that declares one tab renders **no island at all**, just the search button,
so the reaction sheet and the caption bar look exactly as they do today. Nothing
ships greyed-out and unreachable.

**GIF is impossible in the editor, not merely unbuilt.** The pipeline ends at
`ImageCompressor` → JPEG; placing an animated GIF could only flatten one frame,
which is a worse sticker. GIF-on-photo would require an animated output format
(WEBP) and would touch upload, Room, the bubble renderer and the backfill worker.
That is a different feature, not a checkbox here.

**Search scopes to the active tab**, and the three are not alike: emoji filters a
local list synchronously (`buildSearchResults`, already written), stickers filter
local packs, and **GIF search is a debounced network query** with loading, empty,
error and rate-limit states plus pagination. So the query is per-tab state with a
per-tab placeholder, never one shared string — switching tabs must not carry a
query that means nothing where it lands.

**The split.** `ui/chat/picker/` gains `PickerPanel.kt` (the shell: search button,
island, delete button, animated swap, per-tab state — owns no content), `EmojiTab.kt`
(today's grid, category headers, recents, quick-reactions row, backspace and the
long-press size drag, moved out essentially unchanged), plus `StickerTab.kt`,
`TextTab.kt` and `ShapeTab.kt` (all new, all editor-only for now). `PickerTab`
enumerates the tabs and a `PickerSelection` sealed result — `Emoji(emoji, size)` /
`Sticker(id)` / `Gif(...)` / `Text(style)` / `Shape(kind, filled)` — gives a host one
callback instead of five.

`EmojiHandlerPanel` survives as a thin alias over `PickerPanel(tabs = setOf(EMOJI))`
so the three existing call sites are untouched by the extraction commit. The risky
refactor and the new feature stay separable — and `sessionRecents` freezing (the fix
for the grid reordering under your finger) must survive the move intact.

**Stickers in the editor need no schema change.** A sticker placed on a photo is
flattened into the JPEG; only *sending* a sticker as its own message needs
`MessageType.STICKER`, a Room bump and a sync path. That is why the sticker tab can
ship with the editor while sticker-as-message stays in `docs/BACKLOG.md` §4.6.

**Before anyone reaches for the Giphy SDK**, the GIF feature has a privacy decision
to make that this app cannot duck: sending a provider URL means the recipient's
device fetches from Giphy, which tells a third party who received what and hollows
out the Signal-Protocol story; downloading and re-uploading the bytes as an ordinary
media message keeps it private and costs bandwidth. For this app only the second is
consistent. Written down here so it is decided, not defaulted.

---

## 3. Phases

Each phase is one commit, green on its own, carrying its own tests, its CHANGELOG
entry and its version bump (`feat:` → minor). Phase 5 is the exception and is two:
**5a** is a pure refactor (no CHANGELOG entry, no bump) and **5b** the feature on top,
so a regression in the picker extraction is bisectable away from the new tabs.

### Phase 1 — toolbar, download, per-image HD  ✅ shipped 2026-09-09

Delivered in full. Four departures from the bullets below were taken during
implementation and are **flagged here for sign-off rather than settled** — say so
if any should be undone before Phase 2 builds on it:

1. **The rail is two composables, not one `ImageEditToolbar`.** `ImageEditActions`
   (tools, top-right) and `ImageEditHistory` (undo / redo / original⇄edited,
   top-left), because they are hidden and shown by different rules and the plan's
   own UI direction already puts the history controls in the other corner. The
   three editor entry points take **nullable** callbacks and render dimmed while
   null, so Phase 3/4/5 wire one up by passing a lambda and changing nothing else.
2. **A third file, `ui/chat/imageedit/ImageSizeEstimator.kt`**, not on the file
   list: a header probe plus pure arithmetic, because Phase 1 has no rasterizer to
   ask for `estimateSize` (§2.2) and no `UI_ALLOWED_DATA_IMPORTS` entry to spend
   on one. It duplicates `ImageCompressor`'s 1600/q80 contract, which is recorded
   in `TECH_DEBT.md`; **Phase 2 should delete this file**, not port it.
3. **The batch page counter moved from beside the rail to below it.** Five
   controls at 40 dp do not leave a centred counter room on a 390 dp row — the
   row-A mockup collides there too.
4. **The preview-level history controls are wired, not inert**, which §3 assigns
   to Phase 2. They are unreachable in Phase 1 (nothing can produce a history
   yet), and wiring them is what let the §2.7 "returns to the step you were on"
   rule be implemented and regression-tested now rather than being rediscovered
   later — `ImagePreviewScreenHistoryTest` covers both ends of the axis and the
   peek-and-return. Phase 2 inherits tested arithmetic; what remains genuinely
   its own is appending on Done, truncating the tail, and the missing-file
   fallback.

Also worth knowing before Phase 2: caption keys and thumbnail-strip keys are now
`originalUri`, not `uri`, because `uri` moves every time a step lands.

- `ImageEditToolbar` in `ImagePreviewScreen`: `[HD] [Adjust] [Overlay] [Draw] [Undo]
  [Redo] [Original⇄Edited] [Download]` top-right, back arrow top-left; everything but
  HD and Download is inert this phase, and the three history controls are hidden
  (not merely disabled) while an item has no history at all, so a fresh pick looks
  untouched rather than greyed-out.
- `PendingMedia`: add `originalUri`, `isHd: Boolean?`, `editHistory: List<String>`
  and `editCursor: Int` (§2.7); `uri` becomes derived from the cursor.
- `MessageRepository.sendMediaMessage(..., isHd: Boolean? = null)` + impl fallback.
- HD bottom sheet: two rows with estimated sizes, current selection ticked.
- Download saves the **current** (post-edit, once later phases land) image via
  `MediaFileManager.saveToDownloads`, with the existing snackbar pattern.
- Files: `ui/chat/imageedit/ImageEditToolbar.kt`, `ui/chat/imageedit/HdQualitySheet.kt`,
  edits to `PendingMedia.kt`, `ImagePreviewScreen.kt`, `ChatMessageSender.kt`,
  `MessageRepository.kt`, `MessageRepositoryImpl.kt`, `ChatViewModel.kt`.
- Tests: saver round-trip with the new fields, including a multi-entry history, the
  cursor and the newline encoding; `uri` derivation at cursor 0, mid-history and top;
  `isHd` precedence (per-item `true` over pref `false`, per-item `null` falls
  through to the pref).

### Phase 2 — the rasterizer  ✅ shipped 2026-09-09

Delivered. Eight departures from the bullets below were taken during
implementation and are **flagged here for sign-off rather than settled**. Both
axes of `/code-review` ran on the diff and each found a real bug — the eviction
scope in #7 and the dismiss-path leak — which are fixed rather than recorded:

1. **`RasterOp`, `SizeEstimate` and the dimension arithmetic live in
   `domain/util/ImageEditGeometry.kt`** — *amended 2026-09-09, signed off.*
   This first shipped as types nested inside `ImageEditRasterizer`, to hold the
   editor to the one `UI_ALLOWED_DATA_IMPORTS` entry §2.2 budgets. Phase 3
   disproved that within minutes: nesting works for a type reached through the
   outer name, but the moment a screen wants a *companion helper* it is a fresh
   import name again, and `AdjustImageScreen` immediately wanted `outputSize`
   (to label each resize preset with its `W × H`, exactly as §3 Phase 3
   specifies) and `normalizeQuarterTurn`. The ops and the arithmetic are pure
   functions over floats with no Android type in them, so they are not a
   platform adapter at all — they belong in `domain/`, which the UI may import
   without any allowlist. `ImageEditRasterizer` keeps only decode, encode, the
   cache lifecycle, the limiter permit and the header probe, and is now imported
   by exactly one UI file, `ChatViewModel`, which injects it. One allowlist
   entry, permanently, and clean call sites.
2. **`estimateSize` returns `SizeEstimate(width, height, bytes)?`, not `Long`.**
   The HD sheet already renders `1600 x 1200 - about 340 KB`; returning bytes
   alone would either drop the dimensions line Phase 1 shipped or force a second
   header read for it.
3. **Four ops, not seven.** `Rotate`, `Flip`, `Crop` and `Resize` are the four
   Phase 2's own test list names, and all four are fully specified here.
   `Straighten`, `Strokes` and `Overlays` are *not* declared: each one's output
   contract is a decision the phase that designs its UI has to make (does
   straighten expand the bounds or inscribe-crop them? is a blur stroke a mask
   over a pixelated copy?), and guessing now would ship a data shape Phase 3/4/5
   would have to change anyway. The sealed hierarchy is one file, so adding a
   subtype stays local.
4. **The per-item cap of 8 lives in `PendingMedia.landEdit`, not the
   rasterizer.** `rasterize(source, ops)` carries no item identity, and giving
   it one would put per-item bookkeeping in the data layer to enforce a rule
   about the length of a list the UI owns. The byte budget and its eviction *are*
   in the rasterizer, as planned. `landEdit` returns the trimmed head alongside
   the abandoned tail, so both still reach `discard` through one call site.
5. **The app-start sweep is age-based (24 h), not wholesale.** A send
   interrupted mid-upload is flipped to FAILED at startup and keeps its manual
   retry, and that retry re-reads the URI it was given; emptying the directory on
   every launch would turn every such retry into a broken image. Same shape as
   `FireStreamApp.cleanOldSharedMedia`.
6. **`landEdit` has no production call site yet.** The handoff's instruction was
   that Phase 2 changes nothing about the three editor entry points, which stay
   nullable until Phase 3/4/5 pass a lambda. So the append-and-truncate half of
   this phase ships as tested arithmetic plus a tested `discard`, and Phase 3's
   Done handler is the one line that joins them. The *fallback* half is live: the
   visible page re-resolves itself and the whole batch re-resolves on send.

7. **`rasterize` takes a third argument, `liveSteps: Set<Uri>`.** §3 promises
   eviction "shortens how far back undo reaches without ever invalidating the
   step the user is currently on", and a directory-wide oldest-first sweep
   cannot keep that promise: in a batch, page 3's *current* step is old in
   global terms while the user edits page 1, so evicting it destroys an edit
   they can still see rather than only its depth. The rasterizer has no way to
   know which steps are live, so the caller now says. Deliberately **not**
   defaulted — a forgotten argument would be a silent data loss, so the compiler
   asks Phase 3 for it.
   *Re-reviewed on request and found incomplete:* `MediaProcessingLimiter`
   allows two operations at once, so a second `rasterize` could evict the
   first's just-written output, which is in nobody's `liveSteps` until it has
   been returned and recorded. Outputs are now registered as in-flight before
   the bytes are written and eviction is serialised behind a `Mutex`; when the
   protected set makes the budget unreachable the cache stays over budget rather
   than deleting a live step.
8. **A sent batch's current step is not swept at send time.** §4 says the cache
   is swept "when a batch is sent"; `ChatScreen.onSend` discards every step
   *except* the one being sent, because `sendMediaMessage` is about to read
   those bytes and there is no completion signal to hang a delete on. That one
   file is collected by `sweepStale` on the next launch, 24 h later. Closing it
   properly needs a send-completion hook, which is a send-pipeline change §2.1
   set out to avoid.

Also done here, per the Phase 1 sign-off list: `ImageSizeEstimator.kt` and its
test are deleted, `HdQualitySheet` runs on `estimateSize`, `ImageCompressor`'s
`MAX_DIMENSION` is `internal` so the estimate quotes it instead of restating it,
and that `TECH_DEBT.md` entry is gone — replaced by the 4096 px ceiling entry §6
asks for.


- `data/util/ImageEditRasterizer.kt` + `RasterOp` + the 4096 px ceiling + the
  `cacheDir/edits/` lifecycle (`discard`, and a sweep of the directory on app
  start via the existing bootstrap).
- Decode through `ImageDecoder`, never a heavily-subsampled `BitmapFactory` —
  see `ScaledImageDecoder`'s KDoc for the black-bitmap pathology.
- `ArchitectureTest` allowlist entry + `TECH_DEBT.md` line.
- `ui/chat/imageedit/ImageFitMapper.kt`.
- **Preview-level undo, redo and Original⇄Edited go live here**, since this is the
  phase that starts producing history files: append on Done (truncating any tail the
  cursor was behind, deleting those files), move the cursor on undo/redo, and clamp
  to the nearest surviving entry if the cache was evicted under us (§4).
- Per-item history cap (8 steps) and a total edit-cache byte budget, both enforced in
  `ImageEditRasterizer`. Eviction trims the *oldest* steps of the *least recently
  touched* item, which shortens how far back undo reaches without ever invalidating
  the step the user is currently on.
- Tests: `ImageFitMapper` mapping round-trips (pure JVM, both directions, letterbox
  and pillarbox); Robolectric bitmap tests for rotate/flip/crop/resize output
  dimensions and for the resolution ceiling; undo/redo cursor arithmetic including
  both ends; undo to zero yields `originalUri`; a new edit mid-history truncates and
  deletes exactly the abandoned tail; redo is unavailable after that truncation; a
  history entry whose file has vanished is skipped rather than sent.

### Phase 3 — Adjust screen (rotate / flip / straighten / crop / resize)  ✅ shipped 2026-09-09

Delivered, **except the on-device pass**, which nobody has run — see the header.
Six departures from the bullets below were taken during implementation and are
**flagged here for sign-off rather than settled**:

1. **Four files, not one.** `AdjustImageScreen.kt` is the screen; `CropGeometry.kt`
   (crop-frame arithmetic), `AdjustStack.kt` (the op stack, its cursor and its
   saver) and `ImageEditServices.kt` (the ViewModel-supplied capability bundle)
   are separate because the first two are **pure Kotlin and JVM-testable**, which
   is the only way the crop and straighten arithmetic gets checked at all — this
   phase is gestures over a coordinate mapping, and a Robolectric test renders
   the screen without ever moving a pointer across it. Same seam and same reason
   as `ImageFitMapper` in Phase 2.
2. **`ImageEditServices` replaced three `ImagePreviewScreen` parameters with one
   bundle.** Phase 3 needed three more services (rasterize, preview render,
   header probe), which would have put that screen at 14 parameters against a
   ceiling ART enforces with a `VerifyError` **on first render** — and
   Robolectric runs on the JVM, so the existing tests would have gone on passing
   while the app crashed on opening a photo. Same shape as `MessageBubbleCallbacks`
   and `AdjustCallbacks`.
3. **`RasterOp.Straighten` auto-crops to the largest inscribed rectangle of the
   *same aspect ratio*.** The plan settled that it crops; the contract it does
   not state is what shape comes out. Preserving the aspect ratio is what lets
   the editor preview a straighten by scaling a screen-sized bitmap up — the
   scale depends only on the ratio, not the pixel count — so the preview and the
   flatten cannot disagree. `ImageEditGeometry.straightenScale` derives it and
   `ImageEditGeometryTest` checks the inscribed rectangle really fits, at five
   angles across four aspect ratios.
4. **The straighten slider and the resize row edit their op in place rather than
   appending one per interaction** (`AdjustStack.collapse`). Dragging a slider
   from 0° to 5° is one thing the user did, and it is not decomposable anyway:
   3° then 2° is not 5°, because the second rotation acts on the already-cropped
   result of the first. Re-entering the tool later edits the same step, which is
   also why the slider comes back showing the angle the photo has.
5. **Done on an untouched photo cancels instead of flattening.** Writing a
   re-encoded copy of an unchanged image would burn a history step, a cache file
   and a generation of JPEG quality on a no-op.
6. **The resize row scrolls.** Presets each carrying `W × H · ~size` do not fit
   a 390 dp row. Unlike the picker's island (§4), a preset row is not a mode
   switcher — nothing is hidden by scrolling except more of the same kind of
   choice — so a `LazyRow` is the answer rather than dropping the labels. The
   test that found this had to be taught to scroll, which is the honest version.
7. **The resize presets are Original / 1600 / 1080 / 720 — the drafted 2048 is
   not offered.** §2.5 says "an explicit resize wins … HD then governs only the
   encode quality, not a second downscale", and the send path cannot honour that
   for a preset above `ImageCompressor.MAX_DIMENSION`: a standard-quality send
   re-caps at 1600 whatever the edit wrote, so a 2048 preset would take effect on
   an HD send and silently do nothing on a standard one. Threading the chosen
   edge down instead would mean an argument on `sendMediaMessage`, a parameter on
   `ImageCompressor`, a field on `PendingMedia` **and** — because the retry path
   at `MessageRepositoryImpl:891` re-compresses from the stored `Message` — a
   Room column and a version bump, which is precisely the send-pipeline change
   §2.1 exists to avoid. Dropping the one preset above the cap makes §2.5 true
   for every preset that ships, with no send-path change at all. Recorded in
   `TECH_DEBT.md` with the trigger for revisiting.
8. **`AdjustCallbacks` is joined by two more bundles, and both are the same
   rule.** `ImageEditServices` (§3 departure 2) and `AdjustCallbacks` exist for
   the ART parameter ceiling; `AdjustTarget`, `CropRect.Saver` and
   `AdjustStack.StackSaver` exist so the editor survives a rotation. The last was
   caught by review, not by a test: the stack's saver was written and unit-tested
   while the *screen* was held in a plain `remember`, so turning the phone closed
   the editor and made the saver dead code in production. It is now covered by a
   `StateRestorationTester` case that round-trips through a real `Bundle`.

Also worth knowing before Phase 4: `ImageEditRasterizer.preview(source, ops,
maxDimension)` applies an op stack at screen resolution without writing a file,
and shares `decodeAndApply` with `rasterize` — so what an editor shows is what
Done writes, scaled, rather than a second implementation that can drift. Phase 4
should render its strokes over that rather than decoding its own bitmap.


- Rotate 90° CW, flip horizontal, straighten slider (−45°..45°) with a faint grid.
- **Straighten auto-crops live, during the drag** (decided 2026-09-09). As the
  angle changes the image scales up so it never stops being a full rectangle —
  what Google Photos, Snapseed and iOS Photos all do. The alternative considered
  and rejected was to expand the frame, show the black corners and offer an
  explicit "auto crop" button: it is more honest about what rotation costs, but
  it has a failure mode this one cannot have — straighten, miss the button,
  press Done, and send a photo with black triangles in the corners.
  If the trade is ever to be exposed, the control is **not** an auto-crop
  button but a **fit ⇄ fill toggle**, and it belongs at the **right end of the
  straighten slider row**, not floating over the photo: it is a property of the
  straighten *tool*, so it sits with the tool and appears only while that tool is
  active. Same reasoning that separates the draw screen's width slider (a tool
  property) from the overlay screen's scale handle (a selection property). A
  48 dp target at the row's end leaves ~330 dp for the slider and its angle
  readout, which fits 390 dp.
- **`AdjustImageScreen` will hit the ~15-parameter Composable ceiling**, which
  throws `VerifyError` on first render rather than at compile time — this repo
  has paid for that once already with a chat-open crash. Rotate, flip,
  straighten, crop, resize, undo, redo, reset, cancel and done is ten callbacks
  before anything else. Collapse them into an `@Immutable AdjustCallbacks` data
  class, the way `MessageBubbleCallbacks` does it.
- Crop with draggable corner handles and aspect presets: Free / Original / 1:1 /
  4:5 / 16:9.
- Resize presets on the same screen — Original / 1600 / 1080 / 720 long edge
  (2048 was drafted and dropped; see departure 7)
  — each showing the resulting `W × H` and an approximate file size. This is the
  part that has no WhatsApp equivalent.
- **Undo / Redo** step through the transform stack — the crop, then the straighten,
  then the rotate — rather than resetting everything; **Reset** is the separate
  all-at-once escape. No layer-visibility toggle on this screen: there is no added
  layer to hide, only the photo itself, and an eye button that did nothing here
  would teach people to distrust it on the two screens where it works.
- Cancel, Done. Done rasterizes once and pushes one history entry.
- **Verify on device before calling the phase done.** Everything here is a
  gesture over a coordinate mapping, and that is exactly the class of bug a
  Robolectric test passes through: crop handles that sit a few dp from where the
  finger expects them, a straighten that leaves the image imperceptibly
  off-axis, a rotate that fights the pager's own drag. Check it on hardware in
  both orientations, and log anything still unchecked under
  [`docs/BACKLOG.md`](../../docs/BACKLOG.md) § *Pending on-device verification*.
- File: `ui/chat/imageedit/AdjustImageScreen.kt`.

### Phase 4 — Draw screen (pen, highlighter, blur)  ✅ shipped 2026-09-10

Delivered, **except the on-device pass**, which nobody has run — see the header
and `docs/BACKLOG.md` §*Pending on-device verification*. Seven departures from
the bullets below were taken during implementation and are **flagged here for
sign-off rather than settled**:

1. **The mosaic is a fixed 48 blocks across the long edge, not a fixed ×1/16
   scale.** The bullet's "×1/16 downscale" makes the block size a property of
   whatever bitmap it is computed from: the editor's 1600 px preview would get
   blocks 1% of the frame wide and the 4096 px file blocks 0.4% wide, so the
   preview would show a *coarser* redaction than the one written — which is
   exactly the failure this phase's own verification bullet calls a privacy
   failure rather than a cosmetic one. Pinning the count instead makes the
   mosaic the same fraction of the photo at every size, and 48 across is coarse
   enough that a face inside a stroke is gone rather than softened.
   `StrokeGeometry.PIXELATE_BLOCKS`, tested at two resolutions.
2. **The mosaic is produced by halving repeatedly, not by one downscale.** A
   single 85× bilinear downscale samples four neighbours per output pixel, so a
   striped or textured region survives as aliasing instead of being averaged
   away — a "redaction" that still carries the pattern it was hiding. Halving
   averages every source pixel in. `ImageEditRasterizerTest` pins it with 4 px
   stripes under 10 px blocks and asserts the contrast across the covered run
   collapses.
3. **Blur is a layer under the drawing, not a stroke in sequence.** Every blur
   stroke is painted first, whatever order it was drawn in
   (`StrokeGeometry.layers`). The mosaic is a copy of the *photo* and knows
   nothing about the marks over it, so painting in capture order would let a
   blur drawn afterwards swallow the arrow that pointed at the thing being
   blurred. Blur redacts; pen and highlighter annotate; annotation is on top.
   Undo and redo still walk capture order, because the unit a user expects back
   is the last thing they *did*.
4. **`ImageEditRasterizer` gained `pixelate` and `ImageEditServices` a seventh
   entry.** The alternative was to pixelate the preview bitmap in the screen,
   which would have been a second implementation of the one thing on this screen
   that must not differ between the preview and the file. Same argument §3 makes
   for `preview` sharing `decodeAndApply`, and the brief explicitly budgets the
   bundle for this. `decodeCapped` also now asks `ImageDecoder` for a **mutable**
   bitmap so a drawing paints into the decode rather than into a copy of it —
   at working resolution that copy is another 64 MB resident.
5. **Drawing is suspended while the layer is hidden.** The eye stays a pure view
   control — Done flattens every stroke either way, and the screen always
   re-opens visible (§2.7) — but a stroke captured while nothing is drawn would
   be worse feedback than none, so the canvas stops accepting input rather than
   accepting it invisibly.
6. **One width slider shared by all three tools, not one remembered per tool.**
   Per-tool memory is what most editors do and it makes the slider jump on every
   tool change with no visible cause. A shared value is always the value on
   screen.
7. **`AdjustTarget` was generalised to `EditTarget` with an `Editor` enum**, as
   the brief asked, and the Done handler that lands a flattened step is now one
   lambda shared by both editors — landing a step is the same act whichever
   screen produced it.

**Also fixed here, and not part of this phase:** `ArchitectureTest`'s worktree
exclusion matched *every* path when the gate ran from **inside** a worktree,
which is the ordinary case for an agent on a branch. That emptied the Konsist
scope and turned every architecture rule into a vacuous pass — silently, because
a rule with nothing to check does not fail; only the roster's `assertEquals`
noticed, and it read as a missing manager rather than as a missing codebase. The
exclusion now applies only when it leaves a checkout behind, and
`the architecture rules have a codebase to check` is the tripwire.

**CHANGELOG placement judgment call:** the `changelog-release` skill says to open
a fresh section when the top one is not dated today, but `[UNRELEASED] [1.26.0]`
is genuinely untagged (`git describe` → `v1.25.0-28-…`), and two `[UNRELEASED]`
sections would break `cut-release.sh`, which drops the prefix from the top one
only. The existing section was retitled `[UNRELEASED] [1.27.0] — 2026-09-10`
instead, taking the minor bump the `feat:` warrants.


- Tools: **pen**, **highlighter** (alpha, `BlendMode.Multiply`), **blur** — plus
  colour strip and width slider.
- **Undo / Redo** move one stroke at a time and disable themselves at each end; a
  new stroke after an undo discards what redo was holding. **Layer visibility**
  (eye / eye-off, with the tools) hides every stroke so the photo
  underneath can be checked — view-only, never affects the output (§2.7). Verifying
  that a blur actually covers the thing you meant to hide is the reason this button
  matters most on this screen.
- **Blur is implemented as pixelate**: a ×1/16 downscale-then-nearest-upscale copy
  of the bitmap is computed once, and blur strokes act as a mask that reveals it.
  Pixelation is cheaper than a real gaussian, is irreversible in the output (which
  is the point when the user is hiding a face or a bank detail), and reads
  unambiguously as *redacted*. The tool is still labelled "Blur".
- Strokes are captured in normalized image space via `ImageFitMapper` and flattened
  on Done.
- **Verify on device before calling the phase done.** Two things only hardware
  can answer: whether a drawn stroke keeps up with the finger on a real
  full-resolution photo, and whether the flattened output actually redacts what
  the on-screen preview showed as covered — a blur that lands a few pixels off
  in the JPEG is a privacy failure, not a cosmetic one. Check the layer-eye
  toggle against the written file, not just the preview, and log anything still
  unchecked under [`docs/BACKLOG.md`](../../docs/BACKLOG.md) § *Pending
  on-device verification*.
- File: `ui/chat/imageedit/DrawImageScreen.kt`.

### Phase 5 — Overlay screen (emoji, stickers, text + shapes) and the shared picker

One screen for emoji, stickers, text and shapes, because drag / scale / rotate /
z-order / delete are the same machinery for all four; splitting them would mean
writing it four times.

**5a — extract the picker shell first, as its own commit** (§2.8).  ✅ shipped 2026-09-10

`ui/chat/picker/` gains `PickerPanel` + `EmojiTab` + `PickerTab`/`PickerSelection`;
`EmojiHandlerPanel` becomes a one-tab alias so the composer, reaction sheet and caption
bar are byte-for-byte unchanged in behaviour. No new feature in this commit — it is a
refactor with a test to prove the three existing hosts still behave.

Five departures from the bullets below were taken during implementation and are
**flagged here for sign-off rather than settled**:

1. **The two host-varying controls are slots the caller fills, not fields the shell
   knows.** §4 names four behaviours as emoji-*tab* concerns rather than shell
   concerns, and warns that putting any of them in `PickerPanel` is the wrong seam.
   Two of them — the frozen `sessionRecents` order and the long-press size drag with
   its row-sibling fade — moved into `EmojiTab` unchanged, exactly as written. The
   other two are drawn *outside* the tab's own box: the quick-reactions row sits
   **above** the search row and the backspace key **at the end of** it, so moving
   them into the tab literally would have moved them on screen, which is the one
   thing this commit promised not to do. They are `header` and `searchTrailing`
   slots instead — and not symmetrically, which is worth stating: the
   quick-reactions strip is emoji content and lives in `EmojiTab.kt` as
   `EmojiQuickReactions`, while the backspace is a *host's* control over its own
   text field and is written in the alias, never touching the tab at all. The
   seam §4 actually cares about holds either way: `PickerPanel` still knows
   nothing about a backspace or a reaction, and neither can leak into the sticker
   tab, because the shell cannot name either of them.
2. **A one-tab host renders no search *button* either.** §2.8 says it renders "just
   the search button", but with no island to hide there is nothing a collapsed field
   buys, and collapsing would have been a visible change to three screens the
   refactor exists not to touch. The field is simply always expanded there — which is
   what the same sentence's "look exactly as they do today" actually requires.
3. **The query is one string keyed on the active tab, not a per-tab map.** §2.8 asks
   for "per-tab state with a per-tab placeholder, never one shared string", and
   `rememberSaveable(active) { mutableStateOf("") }` is exactly that in one line: a
   switch arrives at that tab's own empty field asking that tab's own question, and a
   query can never follow you somewhere it means nothing. A map keyed by tab would
   also have *restored* the old query on return, which the collapse rule makes moot —
   the field's × clears as well as collapses, because a filter still running behind a
   field that is no longer on screen is a tab that looks broken.
4. **`PickerSelection` ships with only its `Emoji` subtype**, and `EmojiTab`
   emits it rather than handing back two loose primitives — so the type §2.8
   asks for is live code from the first commit rather than a shape waiting for
   a user. The alias unpacks it again for the three hosts, which is what keeps
   their call sites untouched. On the subtype itself: Same rule Phase 2
   applied to `RasterOp` and for the same reason: a selection's shape is a decision
   the phase that designs the tab's UI has to make, and the hierarchy is one file so
   adding a subtype stays local.
5. **`EmojiHandlerPanel.kt` stays in `ui/chat/`.** The alias could have moved to the
   picker package, but leaving it where it is means the three call sites are untouched
   by a single character — which is what makes a regression in the move bisectable
   away from anything else.

`PickerPanelTest` covers the structural half (no island, no delete button, backspace
and quick strip only where they belong, a frozen recents order, and the multi-tab
chrome). The felt half is on the device pass.

**Verify on device before calling 5a done.** The behaviour most at risk in the
move is felt, not asserted: the long-press size drag with its row-sibling fade,
and the frozen `sessionRecents` order holding still under your finger. Open the
composer, the reaction sheet and the caption bar on hardware and confirm each is
indistinguishable from before; log anything still unchecked under
[`docs/BACKLOG.md`](../../docs/BACKLOG.md) § *Pending on-device verification*.

**5b — the sticker and shape tabs, and the overlay screen.**  ✅ shipped 2026-09-10

Delivered, **except the on-device pass**, which nobody has run — see the header and
`docs/BACKLOG.md` §*Pending on-device verification*. Nine departures from the bullets
below were taken during implementation and are **flagged here for sign-off rather than
settled**:

1. **The overlay history is whole-state snapshots, not a list with a cursor.**
   `DrawStack` and `AdjustStack` can be a prefix cursor because their unit of work
   only ever *appends*, so "everything up to the cursor" describes what is in
   effect. This screen has an operation those two do not — **delete** — and after
   placing A and B and deleting A, no prefix of `[A, B]` is `[B]`. A step here is
   therefore the whole set of objects. It costs a few dozen small immutable
   objects and buys the thing nobody would expect to be missing: deleting
   something and undoing brings it back.
2. **Scale is uniform, and each `ShapeKind` carries a fixed aspect ratio.** §3
   specifies one scale handle with a `1.4×` readout, which cannot express a
   non-square resize. So a rectangle comes out 3:2 — the proportion of the thing
   people draw a box around — and is *turned* rather than reshaped. Two-axis
   sizing would need either a second handle or a handle that means something
   different depending on what is selected, which is the ambiguity the two-handle
   decision exists to avoid.
3. **The sticker pack is drawn, not decoded.** No image assets: each sticker is a
   list of flat coloured parts (circle / polygon / stroked polyline) in a `0..1`
   box, in `domain/util/StickerPack.kt`, walked by both renderers. A pack of PNGs
   would have had a thumbnail size *and* a placed size, two filtering paths, and
   an intrinsic size the geometry would have to ask about — three more chances
   for the preview and the file to disagree, on a screen whose whole design is
   about them not being able to. What no test can check is whether a heart looks
   like a heart, which is on the device pass.
4. **No recents row on the sticker tab.** §3 pairs the pack with recents. Twelve
   stickers fit on screen without scrolling, so a recents row would take a fifth
   of the panel to save no scrolling at all — and persisting it means a DataStore
   key, a manager and a ViewModel path that the emoji recents already own and
   this would duplicate. It becomes worth building when pack management does
   (`docs/BACKLOG.md` §4.6).
5. **A text run is one line, and `filled` means solid-versus-stroked glyphs.** A
   wrapping text box needs a width and a uniform scale handle has nothing to
   express one with; one line is also what lets the Compose preview and the
   `android.graphics` flatten centre it by the same measurement. `filled` is the
   same property the shape toggle sets, deliberately — one word for one thing
   across two tabs, rather than "filled" meaning a background pill here and an
   interior there.
6. **Placing text happens on an explicit Add, not on every keystroke.** Typing
   "meet me here" would otherwise leave twelve overlapping runs on the photo,
   each its own undo step.
7. **`PickerPanel` gained a `BackHandler`.** 5a's spec review found §4's "back
   must close search before it closes the panel" unimplemented. It is enabled
   only while a multi-tab host has search open, so it is inert for the three
   one-tab hosts, which own back themselves.
8. **The colour strip moved into `EditorChrome` and the draw screen now uses
   it.** §3 says the shape tab uses "the draw screen's colour strip"; taking that
   literally would have meant a second copy of the palette, and two palettes are
   how an arrow and the box round it end up almost the same red.
9. **The rotate snap has a wider mouth on the cardinals.** §3 asks for "every 15°
   and to 0/90/180/270", and every cardinal is already a multiple of 15 — so the
   second half only means something if the cardinals pull harder. They do: 8°
   against 4°. "Exactly square" is the angle a hand cannot hit and the one a
   rectangle drawn round something most often wants.

`OverlayImageScreen` was measured at **95 registers** against ART's 256 ceiling
(the plan makes that check part of this phase); the highest new method is
`TextTab` at 159, and `MessageBubble` is still the app's tightest at 252.


- The picker mounts here with `tabs = setOf(EMOJI, STICKER, TEXT, SHAPE)`. Four
  segments plus the search and delete buttons do not fit a 390 dp row with labels, so
  the island goes **icon-only except the active segment, which keeps its label** —
  meaning stays visible without the row scrolling. Emoji content is the existing
  grid; do not build a second picker.
- `ShapeTab`: rectangle, rounded rectangle, ellipse, line and arrow, with an
  outline/filled toggle and the draw screen's colour strip. Shapes are what make
  annotation work — "put a box round this" is the other half of the blur tool, and
  both serve the same redact-before-sending job.
- `StickerTab`: a bundled local pack plus recents. Placed stickers are flattened
  into the JPEG, so **no `MessageType` and no Room bump** — sticker-as-message stays
  in `docs/BACKLOG.md` §4.6.
- Text objects: colour strip, a filled/outline style toggle, centre alignment.
- Manipulation: tap to select, one-finger drag to move, two-finger pinch to scale
  and rotate.
- **Scale and rotate are two separate handles**, not one combined corner. Bottom-right
  scales, top-right rotates, each drawn at 26 dp with the hit rect expanded to 48 dp
  and its own live readout (`1.4×`, `−8°`) pinned clear of the finger. No arming tap —
  both are directly draggable.
  This supersedes the single combined handle drafted earlier. A combined handle is
  fine while everything on the canvas is an emoji, where neither exact size nor exact
  angle matters. It stops being fine once shapes are in: a rectangle drawn round
  something is usually wanted axis-aligned or at a deliberate angle, and a combined
  handle cannot rotate without also resizing. Rotation **snaps every 15°** and to
  0/90/180/270, which is only meaningful with a dedicated rotate gesture.
  Rejected: a left-edge slider like the draw screen's. It reads identically but means
  something else — on the draw screen that slider is a *tool* property (brush width,
  no selection required), here it would be a *selected object's* property, appearing
  and disappearing with the selection. Same affordance, different noun, in adjacent
  screens of one editor.
- **Delete is a button on the picker row, not a drop zone.** A trash button sits at
  the right end of the search/island row, acting on the current selection: reachable
  one-handed, discoverable without first dragging something, and it keeps the photo
  free of chrome that only appears mid-gesture. It stays put when search expands and
  slides the island away — deletion belongs to the selection, not to the picker — and
  it is hidden, not greyed, while nothing is selected.
- **Undo / Redo** move over *placements* — the unit people expect back is "that
  emoji I just added", not "the last two millimetres I dragged it". Moves, scales and
  rotations of an already-placed object therefore collapse into its placement rather
  than each becoming a history step. **Layer visibility** hides every emoji and text
  run at once, same contract as the draw screen.
- Rendered on Done with `Canvas.drawText` for emoji and text (emoji are text) and
  `drawBitmap` for stickers, at output resolution so glyphs and edges stay crisp.
- **Verify on device before calling 5b done.** The separate scale and rotate
  handles were chosen over one combined corner on a claim about the hand — that
  a 26 dp handle with a 48 dp hit rect is grabbable without occluding what it
  sits on, and that two-finger pinch and rotate do not fight the selection. Only
  a real screen settles that, along with whether the four-segment island and its
  search and delete buttons still fit a 390 dp row. If the handle does occlude
  small stickers in practice, the fallback is the readout pill, not a second
  slider. Log anything still unchecked under
  [`docs/BACKLOG.md`](../../docs/BACKLOG.md) § *Pending on-device verification*.
- Files: `ui/chat/imageedit/OverlayImageScreen.kt`, `ui/chat/picker/PickerPanel.kt`,
  `ui/chat/picker/EmojiTab.kt`, `ui/chat/picker/StickerTab.kt`,
  `ui/chat/picker/TextTab.kt`, `ui/chat/picker/ShapeTab.kt`,
  `ui/chat/picker/PickerTab.kt`.
- Tests: the three pre-existing hosts still render one tab, no island and no delete
  button; the editor host renders four segments and switches between them; a query
  typed on one tab does not survive a tab switch; `sessionRecents` still freezes for
  the panel's lifetime; rotation snaps to the nearest 15° and to the cardinals.

### Phase 6 — Edit from the fullscreen viewer

- **Edit** action in `FullscreenOverlayControls`, opt-in per host via a nullable
  `onEdit` exactly like `onSaveToDownloads`, so the avatar and link-preview viewers
  do not get it.
- Wired in `ChatScreen` (both the pager and the single-image branch): download if
  needed, copy into the edit cache, push a one-item `pendingMedia`.
- Audit the download button's presence while in there — it is currently absent from
  the group-avatar and share-preview viewers, which is an inconsistency rather than
  a decision.

---

## 4. Traps

- **Black bitmaps.** Large camera originals decode black through a heavily
  subsampled `BitmapFactory`. Use `ImageDecoder` everywhere in the rasterizer;
  `ScaledImageDecoder` documents why.
- **Memory.** Rotation via `Bitmap.createBitmap(src, …, matrix, true)` holds source
  and destination simultaneously. Every rasterize takes a `MediaProcessingLimiter`
  permit; editor screens display a *screen-sized* bitmap and only touch full
  resolution inside `rasterize`.
- **Cache growth, and redo is what makes it bite.** `cacheDir/edits/` must be swept
  when a batch is sent, when it is dismissed, and on app start. An unswept editor
  cache is an unbounded leak on a device that never clears caches — and because redo
  means undo can no longer free the file it steps off, the cache grows *per edit
  step*, not per image, and stays grown for the whole preview session. Twenty picks ×
  eight steps of 4096 px JPEG is comfortably past a gigabyte. Hence the per-item cap
  of 8 and a hard byte budget, both enforced in the rasterizer rather than left to
  the screens, and both load-bearing rather than tidy.
- **The history is a list of files the OS may delete.** `cacheDir` can be reclaimed
  under storage pressure at any moment, including between a rotation and its state
  restore, and our own budget eviction does the same deliberately. Every read of a
  history entry checks the file still exists and falls back to the nearest surviving
  entry at or below the cursor, ending at `originalUri` — the one URI in the model
  that is never a cache file. Losing undo *depth* is acceptable; a send failure, a
  blank pager page, or a cursor pointing at nothing is not.
- **Saving the history through process death.** `PendingMedia.ListSaver` currently
  flattens to fixed-size triples; a variable-length history breaks that shape. Join
  the history with `\n` into one field — a URI cannot contain a newline — carry the
  cursor as a sixth field, and keep the per-item field count fixed. A saver that
  silently drops history on rotation would make undo look broken exactly when the
  user least expects it, and one that saves the history but not the cursor would
  quietly re-apply edits the user had just undone.
- **`ArchitectureTest`.** `data ⇏ ui` (hence `RasterOp` living in the data layer),
  and the UI→data allowlist needs its one new entry plus the `TECH_DEBT.md` line.
- **EXIF.** Re-encoding through `Bitmap.compress` already drops EXIF, so an edited
  image carries no GPS or camera metadata. That is a privacy property worth keeping
  and stating, not a regression to fix.
- **`ImagePreviewScreen` recomposition.** Captions are deliberately held in a map
  read only inside `CaptionBar` so a keystroke does not re-run the pager's
  `AsyncImage`s. Editor state must respect the same discipline — keep it out of the
  pager's read set.
- **Videos.** Every editor entry point is gated on `!item.isVideo`; the toolbar
  shows only HD-less controls for a video page.
- **The picker extraction is where a regression hides.** `EmojiHandlerPanel` carries
  behaviour that is easy to lose in a move: the frozen `sessionRecents` order, the
  long-press drag-to-size gesture with its row-sibling fade, the quick-reactions row
  that only `EmojiMode.REACTION` shows, and the backspace key that only
  `TEXT_INPUT` shows. All four are emoji-*tab* concerns, not shell concerns — putting
  any of them in `PickerPanel` is the wrong seam and will leak into the sticker tab.
- **Collapsing the search must be reachable.** The island slides away when search
  opens, so the field needs its own × (and back must close search before it closes
  the panel) — otherwise the tab switcher is gone with no way back to it.
- **The 390 dp row is the constraint that shaped the island.** Search button (38) +
  island + delete button (38) fits three *labelled* segments only at 11 dp segment
  padding and 15 dp icons. The fourth tab (Shapes) pushed it over, which is why the
  island is icon-only with a label on the active segment. A fifth tab does not fit at
  all — it would need the row to scroll, and a scrolling mode switcher is worse than
  no mode switcher.
- **The draw screen's slider and the overlay screen's scale must not look alike.**
  See Phase 5 — one is a tool property, the other a selection property. If the corner
  handle turns out to occlude small stickers in practice, the fallback is a readout
  pill that becomes a drag target, not a second left-edge slider.
- **Cloud sessions CAN run the gate — run it.** An earlier version of this plan said
  they could not, blaming a `dl.google.com` block; that was a misdiagnosis of a missing
  Android SDK. As of 2026-09-09 `./gradlew :app:testFirebaseDebugUnitTest` runs all 910
  tests in a cloud container. See the header for the exact tasks and the one expected
  proxy-only failure, and `CLAUDE.md` for what the session-start hook provisions. No
  phase should land build-unverified.

---

## 5. Ideas considered, not planned

- **Accumulated `ImageEdit` + single rasterize at send** — see §2.1. Revisit if
  generational quality loss turns out to be visible in practice.
- **Cropping straight into the avatar flow.** `ProfileSetupScreen` and
  `GroupSettingsScreen` take whatever aspect the picker returns; the adjust screen's
  1:1 preset would serve them. Deliberately out of scope — it is an avatar change,
  not an image-editor change, and it would widen the diff across three more screens.
- **Option C, "one editor, four modes"** (from the drafted directions): a single
  Edit entry, bottom mode tabs, and a Layers panel giving each addition its own eye
  and delete. Not taken because per-layer visibility only holds while edits are still
  data — it wants one flatten when you leave the editor rather than one per tool,
  which reopens §2.1. Revisit together with that decision, never separately.
- **Sticker-as-message and GIF-as-message** — `docs/BACKLOG.md` §4.6. Both need a
  new `MessageType`, a Room version bump, a sync path and a bubble renderer; GIF also
  needs the provider-privacy decision recorded in §2.8. The picker's `PickerTab`
  enum already has the shape for them, and no host declares them until then.
- **GIF on a photo.** Impossible while the output is JPEG (§2.8), not merely unbuilt.
- **Branching history.** Undo/redo is linear: editing after an undo discards the
  forward steps. Keeping abandoned branches alive would mean a tree UI and unbounded
  disk in a send-preview.
- **Per-object selection and re-editing after Done** (move the emoji you placed two
  screens ago). Flattening forbids it by construction; it is the main thing the
  accumulated-`ImageEdit` model above would have bought.
- **Drawing on videos.** Needs the transformer pipeline, not this one.
- **Downloadable sticker packs.** `docs/BACKLOG.md` §4.6 — Phase 5b ships one bundled
  pack; pack management is its own feature.

---

## 6. Docs to update as phases land

- `docs/FEATURE-MAP.md` — the *Image / Media Pipeline* table gains the
  `ui/chat/imageedit/` files and `ImageEditRasterizer`; the entry-point line gains
  the editor hop. The picker becomes a cross-cutting feature of its own (four hosts,
  three packages) and earns its own table.
- `docs/PATTERNS.md` + a one-line pointer in `CLAUDE.md` §Key Conventions — a new
  entry for the rasterize-per-screen convention and the normalized-overlay rule.
- `TECH_DEBT.md` — the `ImageEditRasterizer` allowlist entry, and the 4096 px
  edited-HD ceiling with its revisit trigger.
- `CHANGELOG.md` — one entry per phase, editorial, with the commit hash. The
  picker extraction (5a) is refactor-only and gets **no** entry; the sticker tab (5b)
  does.
- `docs/BACKLOG.md` §4.6 — reword once the picker shell lands: the shell and its
  `PickerTab` seam exist, so what remains open is sticker/GIF *as messages* and the
  provider decision, not "a picker".
