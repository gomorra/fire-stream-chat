package com.firestream.chat.ui.chat

import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

// Resolves a bubble's local-first source string into a playable Uri. `source`
// is already localUri ?: mediaUrl by the time it reaches here (see
// FullscreenVideo doc in ChatOverlaysState.kt) — this just decides whether
// that string is a bare filesystem path (no scheme, e.g.
// "/data/user/0/.../video.mp4") or something Uri.parse already understands
// (content://, https://, etc.), mirroring FullscreenImageViewer's
// local-vs-remote resolution for images.
private fun resolveVideoUri(source: String): Uri {
    val hasScheme = source.contains("://")
    return if (!hasScheme && source.startsWith("/")) {
        Uri.fromFile(File(source))
    } else {
        Uri.parse(source)
    }
}

/**
 * Fullscreen video playback overlay. Mirrors [FullscreenImageViewer]'s shape
 * (black scrim, top-corner close button over the status bar inset) but swaps
 * the zoomable image for an ExoPlayer/PlayerView pair. Lives in
 * [OverlaysState.fullscreenVideo] — a [ChatViewModel] slice, not screen-local
 * compose state — so the open player survives activity recreation on
 * rotation the same way the image viewer does.
 *
 * Lifecycle: the player is built once per [source] and released via
 * [DisposableEffect] when this composable leaves composition (dismiss, or
 * the host screen recomposing away on rotation). A separate
 * [LifecycleEventObserver] pauses playback on `ON_PAUSE` so backgrounding the
 * app (or a system dialog stealing focus) doesn't keep audio running —
 * `BackHandler`-driven dismissal (wired in ChatScreen) fully tears the
 * player down instead of just pausing it.
 */
@Composable
internal fun FullscreenVideoPlayer(
    source: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val player = remember(source) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(resolveVideoUri(source)))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    useController = true
                    this.player = player
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(12.dp)
                .size(36.dp)
                .background(color = Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
