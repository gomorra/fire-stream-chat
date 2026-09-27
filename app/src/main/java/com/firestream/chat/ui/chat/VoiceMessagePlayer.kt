package com.firestream.chat.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun VoiceMessagePlayer(
    mediaUrl: String?,
    durationSeconds: Int,
    textColor: Color
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(1f) }
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }
    // Set when the source would not prepare. Voice notes are always app-recorded
    // AAC, but an audio *file* can be anything the sender picked.
    var unplayable by remember(mediaUrl) { mutableStateOf(false) }

    DisposableEffect(mediaUrl) {
        onDispose {
            mediaPlayer.value?.release()
            mediaPlayer.value = null
            // The source changed under a playing player (a sent file moving out
            // of the outbox): back to the start, not stuck on Pause.
            isPlaying = false
            progress = 0f
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val total = (mediaPlayer.value?.duration ?: (durationSeconds * 1000)).toFloat()
            while (isPlaying) {
                val current = mediaPlayer.value?.currentPosition ?: 0
                progress = if (total > 0) current / total else 0f
                if (mediaPlayer.value?.isPlaying != true) {
                    isPlaying = false
                    progress = 0f
                }
                delay(100)
            }
        }
    }

    Column(modifier = Modifier.widthIn(min = 160.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (mediaUrl == null) return@IconButton
                    if (isPlaying) {
                        mediaPlayer.value?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer.value == null) {
                            val player = MediaPlayer()
                            try {
                                player.setDataSource(mediaUrl)
                                player.playbackParams = player.playbackParams.setSpeed(speed)
                                player.prepare()
                                mediaPlayer.value = player
                            } catch (e: Exception) {
                                // IOException on a device, IllegalArgument/State from a bad source.
                                player.release()
                                unplayable = true
                                return@IconButton
                            }
                        }
                        mediaPlayer.value?.let {
                            it.playbackParams = it.playbackParams.setSpeed(speed)
                            it.start()
                        }
                        isPlaying = true
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = textColor
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(3.dp),
                color = textColor,
                trackColor = textColor.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            AssistChip(
                onClick = {
                    speed = when (speed) {
                        1f -> 1.5f
                        1.5f -> 2f
                        else -> 1f
                    }
                    mediaPlayer.value?.let {
                        if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(speed)
                    }
                },
                label = { Text("${speed}x", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(24.dp)
            )
        }
        Text(
            text = if (unplayable) "Can't play this file" else formatDuration(durationSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 36.dp)
        )
    }
}
