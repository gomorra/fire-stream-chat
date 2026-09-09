# Image editing for the send-preview and the fullscreen viewer

Status: **planned, not implemented.** No code was written in the session that
produced this file; it is the agreed design for a later implementation session.

Goal: bring the pre-send preview (`ImagePreviewScreen`) up to WhatsApp's editor —
download, per-image HD toggle, an adjust screen (rotate/flip/straighten/crop/
resize), movable emoji stickers, a paint screen — plus text, blur-to-redact, and
an entry point from the fullscreen viewer.

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
  untouched, and the rasterized steps in between become the undo history (§2.7).
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

### 2.7 Undo, and turning the layer off — two levels, because rasterizing flattens

Rasterize-per-screen (§2.1) means the moment you press **Done**, the strokes and
stickers stop being objects and become pixels. So "undo" and "hide the layer" have
to exist at *two* levels, and conflating them would produce a button that silently
does nothing on one screen and something drastic on the other.

**Inside an editor screen — the layer is still live.**

- **Undo** pops the last object off the working list: the last stroke on the draw
  screen, the last emoji or text run on the overlay screen, the last transform step
  (rotate / flip / straighten / crop) on the adjust screen. Multi-step, down to
  empty; the button disables when there is nothing left. Cheap, because none of it
  is flattened yet.
- **Layer visibility** is an eye / eye-off toggle sitting with the *tools*, not with
  the actions, because it is a view control: it hides everything you have added so
  you can see the photo underneath and judge whether the redaction covers what you
  meant it to. **It never changes what gets written.** Pressing Done with the layer
  hidden still flattens it, and re-entering the screen always comes back visible.
  A control that could silently discard your work by being left in the wrong
  position is not worth the ambiguity it saves.

**On the preview screen — the layer is already pixels.**

Here the chosen model pays for itself. Every Done writes a *new* file and the
previous one is still sitting in `cacheDir/edits/`, so keeping that chain gives
**cross-screen undo for free**:

```
PendingMedia(
  originalUri,          // the pick, never written to
  editHistory,          // rasterized steps, oldest → newest
  editsEnabled,         // false → fall back to the original, history kept
  uri = if (editsEnabled) editHistory.lastOrNull() ?: originalUri
        else originalUri
)
```

- **Undo** pops `editHistory` and deletes the popped file — so it walks back one
  whole editor visit at a time (undo the crop, then undo the drawing), all the way
  to the original. This is the answer to "I rotated it three screens ago and now I
  regret it", which the flattening model otherwise makes impossible.
- **Edits on/off** is the preview-level version of the layer toggle, and unlike the
  in-screen one it **is** destructive-by-intent: off means *send the original after
  all*, keeping the history so it can be switched back on. It reads as "I did all
  that and actually the plain photo was better" — a decision, not a viewing aid.
  The thumbnail strip shows whichever version will be sent, so the strip never
  disagrees with the send button.

There is deliberately **no redo**. Undo already deletes the file it popped, and
keeping both directions means keeping every branch alive on disk for a gain nobody
asks for in a send-preview.

---

## 3. Phases

Each phase is one commit, green on its own, carrying its own tests, its CHANGELOG
entry and its version bump (`feat:` → minor).

### Phase 1 — toolbar, download, per-image HD

- `ImageEditToolbar` in `ImagePreviewScreen`: `[HD] [Adjust] [Overlay] [Draw] [Undo]
  [Edits on/off] [Download]` top-right, back arrow top-left; everything but HD and
  Download is inert this phase, and Undo / Edits-on-off are hidden (not merely
  disabled) while an item has no edit history, so a fresh pick looks untouched.
- `PendingMedia`: add `originalUri`, `isHd: Boolean?`, `editHistory: List<String>`
  and `editsEnabled: Boolean` (§2.7); `uri` becomes derived — the last history entry
  when edits are enabled, `originalUri` otherwise.
- `MessageRepository.sendMediaMessage(..., isHd: Boolean? = null)` + impl fallback.
- HD bottom sheet: two rows with estimated sizes, current selection ticked.
- Download saves the **current** (post-edit, once later phases land) image via
  `MediaFileManager.saveToDownloads`, with the existing snackbar pattern.
- Files: `ui/chat/imageedit/ImageEditToolbar.kt`, `ui/chat/imageedit/HdQualitySheet.kt`,
  edits to `PendingMedia.kt`, `ImagePreviewScreen.kt`, `ChatMessageSender.kt`,
  `MessageRepository.kt`, `MessageRepositoryImpl.kt`, `ChatViewModel.kt`.
- Tests: saver round-trip with the new fields, including a multi-entry history and
  the newline encoding; `uri` derivation across `editsEnabled` and an empty history;
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
- **Preview-level undo and Edits-on/off go live here**, since this is the phase that
  starts producing history files: push on Done, pop-and-delete on Undo, clamp to the
  newest surviving file if the cache was evicted under us (§4), and the strip
  thumbnail follows `editsEnabled`.
- Per-item history cap (10 steps, oldest evicted) and a total edit-cache budget
  enforced in `ImageEditRasterizer`.
- Tests: `ImageFitMapper` mapping round-trips (pure JVM, both directions, letterbox
  and pillarbox); Robolectric bitmap tests for rotate/flip/crop/resize output
  dimensions and for the resolution ceiling; undo pops and deletes exactly one file;
  undo past the first step lands on `originalUri`; a history entry whose file has
  vanished is skipped rather than sent.

### Phase 3 — Adjust screen (rotate / flip / straighten / crop / resize)

- Rotate 90° CW, flip horizontal, straighten slider (−45°..45°) with a faint grid.
- Crop with draggable corner handles and aspect presets: Free / Original / 1:1 /
  4:5 / 16:9.
- Resize presets on the same screen — Original / 2048 / 1600 / 1080 / 720 long edge
  — each showing the resulting `W × H` and an approximate file size. This is the
  part that has no WhatsApp equivalent.
- **Undo** steps back through the transform stack — the crop, then the straighten,
  then the rotate — rather than resetting everything; **Reset** is the separate
  all-at-once escape. No layer-visibility toggle on this screen: there is no added
  layer to hide, only the photo itself, and an eye button that did nothing here
  would teach people to distrust it on the two screens where it works.
- Cancel, Done. Done rasterizes once and pushes one history entry.
- File: `ui/chat/imageedit/AdjustImageScreen.kt`.

### Phase 4 — Draw screen (pen, highlighter, blur)

- Tools: **pen**, **highlighter** (alpha, `BlendMode.Multiply`), **blur** — plus
  colour strip and width slider.
- **Undo** pops one stroke at a time and disables itself at zero. **Layer
  visibility** (eye / eye-off, with the tools) hides every stroke so the photo
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

### Phase 5 — Overlay screen (emoji stickers + text)

One screen for both, because drag / pinch-scale / rotate / z-order / delete are the
same machinery for an emoji and for a text run; splitting them would mean writing
that twice.

- Emoji chosen through the existing `EmojiHandlerPanel` (`EmojiMode.TEXT_INPUT`) —
  do not build a second picker.
- Text objects: colour strip, a filled/outline style toggle, centre alignment.
- Manipulation: one-finger drag, two-finger pinch-scale and rotate, tap to select,
  drag onto a trash zone that appears at the bottom while dragging.
- **Undo** removes the most recently *added* object (not the most recently moved —
  a placement is the unit people expect back). **Layer visibility** hides every
  emoji and text run at once, same contract as the draw screen.
- Rendered on Done with `Canvas.drawText` for both (emoji are text), at output
  resolution so glyphs stay crisp.
- File: `ui/chat/imageedit/OverlayImageScreen.kt`.

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
- **Cache growth.** `cacheDir/edits/` must be swept when a batch is sent, when it is
  dismissed, and on app start. An unswept editor cache is an unbounded leak on a
  device that never clears caches — and the undo history (§2.7) makes it grow *per
  edit step*, not per image: twenty picks × ten steps is two hundred full-size
  JPEGs. Hence the per-item cap of 10 steps and a total cache budget, both enforced
  in the rasterizer rather than left to the screens.
- **The undo history is a list of files the OS may delete.** `cacheDir` can be
  reclaimed under storage pressure at any moment, including between a rotation and
  its state restore. Every read of a history entry checks the file still exists and
  falls back to the newest surviving one, ending at `originalUri` — which is the one
  URI in the model that is never a cache file. A missing intermediate must never
  produce a send failure or a blank pager page.
- **Saving the history through process death.** `PendingMedia.ListSaver` currently
  flattens to fixed-size triples; a variable-length history breaks that shape. Join
  the history with `\n` into one field — a URI cannot contain a newline — and keep
  the per-item field count fixed. A saver that silently drops history on rotation
  would make undo look broken exactly when the user least expects it.
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
- **Redo.** Undo deletes the file it popped; keeping the other direction means
  keeping every abandoned branch on disk for a gain nobody looks for in a
  send-preview. Reconsider only if undo turns out to be used as a browsing gesture
  rather than a correction.
- **Per-object selection and re-editing after Done** (move the emoji you placed two
  screens ago). Flattening forbids it by construction; it is the main thing the
  accumulated-`ImageEdit` model above would have bought.
- **Drawing on videos.** Needs the transformer pipeline, not this one.
- **GIF and sticker packs.** Already tracked in `docs/BACKLOG.md` §4.6.

---

## 6. Docs to update as phases land

- `docs/FEATURE-MAP.md` — the *Image / Media Pipeline* table gains the
  `ui/chat/imageedit/` files and `ImageEditRasterizer`; the entry-point line gains
  the editor hop.
- `docs/PATTERNS.md` + a one-line pointer in `CLAUDE.md` §Key Conventions — a new
  entry for the rasterize-per-screen convention and the normalized-overlay rule.
- `TECH_DEBT.md` — the `ImageEditRasterizer` allowlist entry, and the 4096 px
  edited-HD ceiling with its revisit trigger.
- `CHANGELOG.md` — one entry per phase, editorial, with the commit hash.
