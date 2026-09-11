package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * One picked-but-not-yet-sent media item, queued in [ImagePreviewScreen].
 *
 * A gallery multi-pick, a camera capture and a video capture all collapse into a
 * list of these, so the preview screen has a single shape to render whether the
 * user picked one photo or ten. The caption belongs to the *item*, not the
 * screen — each image in a batch carries its own.
 *
 * ### Edits are a cursor over rasterized files, not a stack
 *
 * Every editor screen flattens its layer into a new full-size JPEG (see
 * `.claude/plans/image-editor.md` §2.1), so the chain of files in
 * [editHistory] *is* the undo history — nothing extra has to be recorded to get
 * one. Because redo must be able to walk forward again, undo cannot delete the
 * file it steps off: [editCursor] moves, the files stay. `0` means "the
 * original"; `n` means `editHistory[n - 1]`, so [uri] — what actually gets sent
 * — is derived from the cursor rather than stored alongside it. Two fields
 * could disagree; one cursor cannot.
 *
 * [originalUri] is the pick itself and is never written to, which makes it the
 * one URI here that is not a cache file the OS may reclaim. It is also the
 * stable identity of the item for the whole preview session — caption keys and
 * list keys hang off it, not off [uri], which moves as edits land.
 *
 * The share-sheet path does not use this: an incoming share is already
 * materialised as `SharedContent.Media.MediaItem` and has no editable caption.
 */
@Immutable
internal data class PendingMedia(
    val originalUri: Uri,
    val mimeType: String,
    val caption: String = "",
    /** `null` follows the global "send images in HD" preference; see §2.5. */
    val isHd: Boolean? = null,
    /** Every rasterized edit step, oldest → newest, as URI strings. */
    val editHistory: List<String> = emptyList(),
    /** `0` = [originalUri]; `n` = `editHistory[n - 1]`. */
    val editCursor: Int = 0,
    /**
     * A Coil memory-cache key under which [originalUri]'s pixels are already
     * decoded — set for a photo opened from a fullscreen viewer, which has just
     * drawn them — so the preview's first frame is the photo rather than black
     * while the edit-cache copy decodes. Presentation state, not part of the
     * pick: it is not saved across recreation (the copy is cached by then), and
     * `previewImageRequest` ignores it once an edit has replaced the original.
     */
    val originalMemoryCacheKey: String? = null,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** True once this item has at least one rasterized edit to step through. */
    val hasEdits: Boolean get() = editHistory.isNotEmpty()

    /**
     * The image as it currently stands — the original, or the history entry the
     * cursor sits on. Coerced rather than trusted: a restored cursor can outrun
     * a history the OS trimmed out from under us, and a page that renders
     * nothing is worse than one that renders an earlier step.
     */
    val uri: Uri
        get() {
            val step = editCursor.coerceIn(0, editHistory.size)
            return if (step == 0) originalUri else Uri.parse(editHistory[step - 1])
        }

    /**
     * This item after a rasterized step lands on top of it, plus every history
     * file the step made unreachable.
     *
     * Two things make a file unreachable, and both are this function's job
     * because both are consequences of where the cursor was
     * (`.claude/plans/image-editor.md` §2.7):
     *
     * - **The abandoned tail.** Finishing an edit while the cursor is not at the
     *   top discards what redo was holding — linear history, the same rule as
     *   inside the editors. Keeping those branches alive would mean a tree UI
     *   and unbounded disk in a send preview.
     * - **The overflow head.** The history is capped at [MAX_EDIT_STEPS], so the
     *   oldest step falls off once a ninth lands. That shortens how far back
     *   undo reaches; it never invalidates the step the user is on.
     *
     * The caller hands [LandedEdit.abandoned] to
     * `ImageEditRasterizer.discard` — undo can no longer free the file it steps
     * off, so this is the only moment an edit file becomes deletable, and
     * skipping it is what turns the edit cache into a leak.
     */
    fun landEdit(rasterized: Uri): LandedEdit {
        val step = editCursor.coerceIn(0, editHistory.size)
        val kept = editHistory.take(step)
        val appended = kept + rasterized.toString()
        val overflow = (appended.size - MAX_EDIT_STEPS).coerceAtLeast(0)
        val history = appended.drop(overflow)
        return LandedEdit(
            item = copy(editHistory = history, editCursor = history.size),
            abandoned = editHistory.drop(step) + appended.take(overflow),
        )
    }

    /**
     * This item with its cursor moved down to the nearest step whose file is
     * still there, ending at [originalUri] if none of them are.
     *
     * `cacheDir` can be reclaimed under storage pressure at any moment,
     * including between a rotation and its state restore, and the rasterizer's
     * own byte-budget eviction does the same deliberately. Losing undo *depth*
     * is an acceptable cost of that; a send failure, a blank pager page or a
     * cursor pointing at nothing is not (§4).
     *
     * **Walking down truncates what it walked past**, so the history stays a
     * contiguous run of steps that are all actually reachable. Leaving the
     * vanished entries in place would keep redo *enabled* over a hole it can
     * never cross — pressing it would land on the missing file and be resolved
     * straight back, which is exactly the control that silently does nothing
     * that §2.7 sets out to avoid. Nothing is truncated when every step under
     * the cursor is present: an ordinary undo must keep its redo tail.
     */
    fun onSurvivingStep(exists: (Uri) -> Boolean): PendingMedia {
        val cursor = editCursor.coerceIn(0, editHistory.size)
        var step = cursor
        while (step > 0 && !exists(Uri.parse(editHistory[step - 1]))) step--
        if (step == cursor) return if (cursor == editCursor) this else copy(editCursor = cursor)
        return copy(editHistory = editHistory.take(step), editCursor = step)
    }

    companion object {
        /** Field count per item in [ListSaver]; see its KDoc for the layout. */
        private const val SAVED_FIELDS = 6

        /**
         * How many rasterized steps one item keeps.
         *
         * The cap is what bounds the edit cache per image now that redo means
         * undo cannot free the file it steps off: eight steps of a 4096 px JPEG
         * is roughly 30 MB for a single photo, and twenty picks without a cap is
         * comfortably past a gigabyte (§4). Eight is also well past the number
         * of editor visits anyone makes before sending.
         */
        const val MAX_EDIT_STEPS = 8

        /**
         * Flattens to fixed-size `[originalUri, mime, caption, isHd, history,
         * cursor]` runs so a pick — and everything the user has edited into it —
         * survives rotation and process death.
         *
         * The history is variable-length, which a `listSaver` cannot express
         * directly, so it is joined with `\n` into a single field: a URI cannot
         * contain a newline, and keeping the per-item field count fixed is what
         * lets the restore chunk the flat list back apart. The cursor is saved
         * alongside it — a saver that kept the history but dropped the cursor
         * would quietly re-apply edits the user had just undone.
         */
        val ListSaver: Saver<List<PendingMedia>, Any> = listSaver(
            save = { items ->
                items.flatMap {
                    listOf(
                        it.originalUri.toString(),
                        it.mimeType,
                        it.caption,
                        it.isHd?.toString().orEmpty(),
                        it.editHistory.joinToString("\n"),
                        it.editCursor.toString(),
                    )
                }
            },
            restore = { flat ->
                flat.chunked(SAVED_FIELDS).mapNotNull { parts ->
                    if (parts.size == SAVED_FIELDS) {
                        val history = parts[4].split("\n").filter { it.isNotEmpty() }
                        PendingMedia(
                            originalUri = Uri.parse(parts[0]),
                            mimeType = parts[1],
                            caption = parts[2],
                            isHd = parts[3].toBooleanStrictOrNull(),
                            editHistory = history,
                            editCursor = (parts[5].toIntOrNull() ?: 0).coerceIn(0, history.size),
                        )
                    } else {
                        null
                    }
                }
            }
        )
    }
}

/**
 * The outcome of landing one rasterized edit: the item as it now stands, and
 * the history files that landing it orphaned.
 *
 * Returned as a pair rather than mutating in place because the two halves go to
 * different owners — the item goes back into the preview's draft list, the
 * orphans go to `ImageEditRasterizer.discard`. A caller that forgets the second
 * half leaks disk rather than corrupting state, and the type is what makes that
 * omission visible at the call site.
 */
@Immutable
internal data class LandedEdit(
    val item: PendingMedia,
    val abandoned: List<String>,
)
