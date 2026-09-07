package com.firestream.chat.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest

/**
 * Builds a Coil request that decodes a still frame out of a video.
 *
 * The constraint this exists to hold: [VideoFrameDecoder.Factory] must be
 * attached **per request**, never to the global `ImageLoader` — registering it
 * globally makes it compete for ordinary image loads. Every surface that shows a
 * video still (message bubble, the pre-send preview, the preview's thumbnail
 * strip) goes through here so that rule lives in exactly one place.
 *
 * [data] is anything Coil can open: a `File`, a `content://` [android.net.Uri],
 * or a URL string.
 */
@Composable
fun rememberVideoFrameRequest(data: Any): ImageRequest {
    val context = LocalContext.current
    return remember(data) {
        ImageRequest.Builder(context)
            .data(data)
            .decoderFactory(VideoFrameDecoder.Factory())
            .build()
    }
}
