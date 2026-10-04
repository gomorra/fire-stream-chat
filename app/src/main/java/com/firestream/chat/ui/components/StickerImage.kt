package com.firestream.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.firestream.chat.domain.model.Sticker
import java.io.File

/**
 * Builds a Coil request that plays an animated WebP or a GIF.
 *
 * [ImageDecoderDecoder.Factory] must be attached **per request**, never to the
 * global `ImageLoader`. A global registration would start animating link
 * previews and avatars. Every surface that plays an animation goes through
 * here, as every video still goes through [rememberVideoFrameRequest].
 *
 * A request without this decoder shows the first frame, which is what a
 * preview wants.
 *
 * [data] is anything Coil can open: a `File`, a path, a uri or a URL string.
 */
@Composable
fun rememberAnimatedImageRequest(data: Any): ImageRequest {
    val context = LocalContext.current
    return remember(data) {
        ImageRequest.Builder(context)
            .data(data)
            .decoderFactory(ImageDecoderDecoder.Factory())
            .build()
    }
}

/**
 * The one composable every surface draws a sticker with: the bubble, the
 * library grids and the previews.
 *
 * [model] is the library file, its path, a folder entry's uri or a remote URL.
 * With [animated] off the sticker shows its first frame. Grids and previews
 * pass that, so a screen of stickers does not run a decoder per cell.
 *
 * The caller fixes the size through [modifier]. A sticker is never cropped.
 */
@Composable
fun StickerImage(
    model: Any,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    contentDescription: String? = "Sticker",
) {
    AsyncImage(
        model = if (animated) rememberAnimatedImageRequest(model) else model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        error = rememberVectorPainter(Icons.Default.BrokenImage),
    )
}

/**
 * Fetches the file of a library sticker that has none on this device yet, and
 * says whether it is there now. A restored pack, and a pack added from someone
 * else, bring their stickers' rows before the files.
 *
 * `MainActivity` provides it for the whole app. The default fetches nothing,
 * which is what a test and a preview get.
 */
val LocalStickerFetcher = staticCompositionLocalOf<suspend (Sticker) -> Boolean> { { false } }

/**
 * A library sticker's first frame. A sticker whose file is not here yet shows
 * a placeholder, asks [LocalStickerFetcher] for the file once, and draws it
 * when it arrives.
 */
@Composable
fun LibraryStickerImage(sticker: Sticker, modifier: Modifier = Modifier) {
    val fetch = LocalStickerFetcher.current
    var fetched by remember(sticker.id) { mutableStateOf(false) }
    // Checked in composition, not in an effect: an effect would draw one frame
    // with the wrong answer (docs/GOTCHAS.md, "Local-vs-remote image model").
    val hasFile = remember(sticker.localPath, fetched) { File(sticker.localPath).isFile }
    LaunchedEffect(sticker.id, hasFile) {
        if (!hasFile) fetched = fetch(sticker)
    }
    if (hasFile) {
        StickerImage(model = sticker.localPath, modifier = modifier, animated = false)
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .semantics { contentDescription = "Sticker, not downloaded yet" },
        )
    }
}
