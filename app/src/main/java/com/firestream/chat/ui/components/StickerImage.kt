package com.firestream.chat.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest

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
