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
 * The share-sheet path does not use this: an incoming share is already
 * materialised as `SharedContent.Media.MediaItem` and has no editable caption.
 */
@Immutable
internal data class PendingMedia(
    val uri: Uri,
    val mimeType: String,
    val caption: String = "",
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    companion object {
        /**
         * Flattens to `[uri, mime, caption]` triples so a pick survives rotation
         * and process death. A `List<PendingMedia>` is not Bundle-storable on its
         * own, and losing a ten-photo selection to a rotation is not acceptable.
         */
        val ListSaver: Saver<List<PendingMedia>, Any> = listSaver(
            save = { items -> items.flatMap { listOf(it.uri.toString(), it.mimeType, it.caption) } },
            restore = { flat ->
                flat.chunked(3).mapNotNull { parts ->
                    if (parts.size == 3) {
                        PendingMedia(Uri.parse(parts[0]), parts[1], parts[2])
                    } else {
                        null
                    }
                }
            }
        )
    }
}
