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

    companion object {
        /** Field count per item in [ListSaver]; see its KDoc for the layout. */
        private const val SAVED_FIELDS = 6

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
