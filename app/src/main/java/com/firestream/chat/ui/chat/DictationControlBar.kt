package com.firestream.chat.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.firestream.chat.R
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Recording control bar shown above the composer while dictation is active.
 * Renders a sine waveform whose amplitude tracks the RMS dB from
 * SpeechRecognizer (typically -2..12) and a cancel ✕ that discards the partial.
 *
 * Takes a StateFlow rather than a Float so that ~10 Hz audio-level updates
 * recompose only this composable, not all of ChatScreen.
 */
@Composable
internal fun DictationControlBar(
    audioLevel: StateFlow<Float>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val level by audioLevel.collectAsState()
    val transition = rememberInfiniteTransition(label = "dictation-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave-phase",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.dictation_listening),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SineWaveform(
                phase = phase,
                audioLevel = level,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp),
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.dictation_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SineWaveform(
    phase: Float,
    audioLevel: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // RMS dB from SpeechRecognizer is roughly [-2 .. 12]; clamp + scale to 0..1.
    val normalised = ((audioLevel + 2f) / 14f).coerceIn(0.05f, 1f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val amplitude = (h / 2f) * 0.85f * normalised
        val wavelength = w / 1.5f

        val path = Path().apply {
            moveTo(0f, midY)
            var x = 0f
            while (x <= w) {
                val y = midY + amplitude * sin(2f * PI.toFloat() * (x / wavelength) + phase)
                lineTo(x, y)
                x += 2f
            }
        }
        drawPath(path = path, color = color, style = Stroke(width = 2.5f))
    }
}
