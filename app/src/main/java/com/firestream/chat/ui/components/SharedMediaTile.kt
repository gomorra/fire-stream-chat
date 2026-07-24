package com.firestream.chat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * A single shared-media thumbnail tile, used by both the standalone Shared Media
 * screen (chat three-dot menu) and the Shared Media section of the profile /
 * chat-detail screen so the two render identically.
 *
 * The tile decodes through [ScaledImageDecoder] (Android `ImageDecoder`) rather
 * than Coil's default `BitmapFactory` path: heavily subsampling a large,
 * pre-compression original with `BitmapFactory` returns a black bitmap for
 * certain images, which is what rendered these tiles black. It prefers the
 * on-disk full file when present (mirroring `rememberMessageImageModel` /
 * `FullscreenImageViewer`), falls back to [thumbnailUrl] (a video's thumbnail)
 * and then [mediaUrl], and shows a broken-image icon over a surface background on
 * a genuine load failure instead of leaving the tile black.
 *
 * The caller supplies sizing/clip via [modifier] (e.g. `weight`/`aspectRatio`/
 * `clip` in a Row, or `fillMaxWidth`/`aspectRatio` in a grid).
 */
@Composable
fun SharedMediaTile(
    mediaUrl: String?,
    localUri: String?,
    thumbnailUrl: String? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(mediaUrl, localUri, thumbnailUrl) {
        val localFile = localUri
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.isFile && it.canRead() }
        ImageRequest.Builder(context)
            .data(localFile ?: thumbnailUrl ?: mediaUrl)
            .decoderFactory(ScaledImageDecoder.Factory())
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        error = rememberVectorPainter(Icons.Default.BrokenImage),
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    )
}
