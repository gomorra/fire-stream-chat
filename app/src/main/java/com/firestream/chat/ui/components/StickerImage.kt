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
import androidx.compose.ui.platform.testTag
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
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
 * [format] is what [model] holds. A [StickerFormat.LOTTIE] sticker is drawn by
 * Lottie, and only from a file on this device: a library file has been hashed
 * and checked, and what a url serves has not. Until its file arrives it shows
 * a placeholder.
 *
 * The caller fixes the size through [modifier]. A sticker is never cropped.
 */
@Composable
fun StickerImage(
    model: Any,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    contentDescription: String? = "Sticker",
    format: StickerFormat = StickerFormat.WEBP,
) {
    if (format == StickerFormat.LOTTIE) {
        LottieSticker(model, modifier, animated, contentDescription)
        return
    }
    AsyncImage(
        model = if (animated) rememberAnimatedImageRequest(model) else model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        error = rememberVectorPainter(Icons.Default.BrokenImage),
    )
}

/** The test tag of a Lottie sticker that is drawn, as opposed to its placeholder. */
internal const val LOTTIE_STICKER_TAG = "sticker:lottie"

@Composable
private fun LottieSticker(model: Any, modifier: Modifier, animated: Boolean, description: String?) {
    val path = remember(model) { (model as? File)?.path ?: (model as? String)?.takeIf { it.startsWith("/") } }
    val described = if (description == null) modifier else modifier.semantics { contentDescription = description }
    if (path == null) {
        StickerPlaceholder(described)
        return
    }
    // Lottie reads and parses the file off the main thread, and keeps the parsed animation
    // under the path. The path names the bytes, so a sticker shown again is neither read nor
    // parsed. Until it is loaded nothing is drawn, which is never the url (docs/GOTCHAS.md,
    // "Local-vs-remote image model").
    val result = rememberLottieComposition(LottieCompositionSpec.File(path))
    val composition = result.value
    when {
        result.isFailure -> StickerPlaceholder(described)
        composition == null -> Box(described)
        else -> LottieAnimation(
            composition = composition,
            iterations = if (animated) LottieConstants.IterateForever else 1,
            isPlaying = animated,
            modifier = described.testTag(LOTTIE_STICKER_TAG),
        )
    }
}

/** Where a sticker will be once its file is on this device. */
@Composable
private fun StickerPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium))
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
    // A Lottie sticker has its first frame beside it as a PNG. Should that be
    // missing, the animation itself is drawn, stopped at its first frame.
    val hasStill = remember(sticker.stillPath, hasFile) { hasFile && File(sticker.stillPath).isFile }
    when {
        hasStill -> StickerImage(model = sticker.stillPath, modifier = modifier, animated = false)
        hasFile -> StickerImage(model = sticker.localPath, modifier = modifier, animated = false, format = sticker.format)
        else -> StickerPlaceholder(modifier.semantics { contentDescription = "Sticker, not downloaded yet" })
    }
}
