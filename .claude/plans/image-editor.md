# Image editing for the send-preview and the fullscreen viewer

Status: **planned, not implemented.** No code was written in the session that
produced this file; it is the agreed design for a later implementation session.

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

`Order: 1 → 2 → 3 → 4 → 5 → 6`

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

**The chrome.** A circular **search button** on the left, and to its right a
segmented **island** — Emoji · Sticker · GIF — with a sliding selector. Tapping
search expands the field out of the button and the island slides right and fades;
the field's × collapses it and brings the island back. One row, two states, and the
tab you are in stays visible whenever you are not typing.

**The island's tabs are declared by the host, not fixed** — this is the part that
keeps it honest:

| Host | Emoji | Sticker | GIF | Why |
|---|:--:|:--:|:--:|---|
| Composer | ✓ | later | later | Sending either needs a new `MessageType` + a Room version bump |
| Reaction sheet | ✓ | — | — | A reaction is one grapheme stored on the message; a sticker reaction is a different data model |
| Caption bar (preview) | ✓ | — | — | It types into a text field |
| **Editor overlay** (Phase 5) | ✓ | ✓ | — | Placed and flattened into the JPEG — an animation cannot be. Also carries Text and Shape tabs, which no other host wants |

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
island, animated swap, per-tab state — owns no content), `EmojiTab.kt` (today's
grid, category headers, recents, quick-reactions row, backspace and the long-press
size drag, moved out essentially unchanged) and `StickerTab.kt` (new). `PickerTab`
enumerates the tabs and a `PickerSelection` sealed result (`Emoji(emoji, size)` /
`Sticker(id)` / `Gif(...)`) gives a host one callback instead of three.

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
entry and its version bump (`feat:` → minor).

### Phase 1 — toolbar, download, per-image HD

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

### Phase 2 — the rasterizer

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

### Phase 3 — Adjust screen (rotate / flip / straighten / crop / resize)

- Rotate 90° CW, flip horizontal, straighten slider (−45°..45°) with a faint grid.
- Crop with draggable corner handles and aspect presets: Free / Original / 1:1 /
  4:5 / 16:9.
- Resize presets on the same screen — Original / 2048 / 1600 / 1080 / 720 long edge
  — each showing the resulting `W × H` and an approximate file size. This is the
  part that has no WhatsApp equivalent.
- **Undo / Redo** step through the transform stack — the crop, then the straighten,
  then the rotate — rather than resetting everything; **Reset** is the separate
  all-at-once escape. No layer-visibility toggle on this screen: there is no added
  layer to hide, only the photo itself, and an eye button that did nothing here
  would teach people to distrust it on the two screens where it works.
- Cancel, Done. Done rasterizes once and pushes one history entry.
- File: `ui/chat/imageedit/AdjustImageScreen.kt`.

### Phase 4 — Draw screen (pen, highlighter, blur)

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
- File: `ui/chat/imageedit/DrawImageScreen.kt`.

### Phase 5 — Overlay screen (emoji, stickers + text) and the shared picker

One screen for emoji, stickers and text, because drag / pinch-scale / rotate /
z-order / delete are the same machinery for all three; splitting them would mean
writing it three times.

**5a — extract the picker shell first, as its own commit** (§2.8). `ui/chat/picker/`
gains `PickerPanel` + `EmojiTab` + `PickerTab`/`PickerSelection`; `EmojiHandlerPanel`
becomes a one-tab alias so the composer, reaction sheet and caption bar are byte-for-
byte unchanged in behaviour. No new feature in this commit — it is a refactor with a
test to prove the three existing hosts still behave.

**5b — the sticker and shape tabs, and the overlay screen.**

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
- Files: `ui/chat/imageedit/OverlayImageScreen.kt`, `ui/chat/picker/PickerPanel.kt`,
  `ui/chat/picker/EmojiTab.kt`, `ui/chat/picker/StickerTab.kt`,
  `ui/chat/picker/PickerTab.kt`.
- Tests: the three pre-existing hosts still render one tab and no island; the
  editor host renders two and switches between them; a query typed on one tab does
  not survive a tab switch; `sessionRecents` still freezes for the panel's lifetime.

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
- **Cloud sessions cannot run `./gradlew`** (blocked `dl.google.com`). Every phase
  lands build-unverified from the web and needs a local
  `./gradlew test assembleDebug` before it is trusted.

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
- **GIF and sticker packs.** Already tracked in `docs/BACKLOG.md` §4.6.

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
