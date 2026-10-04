package com.firestream.chat.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.CallUiControls

/**
 * The floating dock: camera, flip, microphone, audio route, hang up. Flip is there only while the
 * own camera is on. Without a video line the camera button is disabled and [unavailableLine] says why.
 *
 * @param hangUpOnly the call is still being placed, so there is nothing to switch yet.
 * @param onTouch a control was used. The stage starts its hide timer again.
 */
@Composable
internal fun StageDock(
    controls: CallUiControls,
    callbacks: CallScreenCallbacks,
    modifier: Modifier = Modifier,
    unavailableLine: String? = null,
    hangUpOnly: Boolean = false,
    onTouch: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        if (unavailableLine != null && !controls.videoAvailable && !hangUpOnly) {
            Text(
                text = unavailableLine,
                style = MaterialTheme.typography.bodySmall,
                color = StageColors.DimText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = StageColors.Dock,
            modifier = Modifier.testTag(CallStageTags.DOCK),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                if (!hangUpOnly) DockSwitches(controls, callbacks, onTouch)
                HangUpButton(onClick = callbacks.onHangup)
            }
        }
    }
}

@Composable
private fun DockSwitches(controls: CallUiControls, callbacks: CallScreenCallbacks, onTouch: () -> Unit) {
    DockButton(
        icon = if (controls.cameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
        label = if (controls.cameraOn) "Turn camera off" else "Turn camera on",
        lit = controls.cameraOn,
        enabled = controls.videoAvailable,
    ) {
        callbacks.onSetCamera(!controls.cameraOn)
        onTouch()
    }
    AnimatedVisibility(
        visible = controls.cameraOn,
        enter = fadeIn() + expandHorizontally(),
        exit = fadeOut() + shrinkHorizontally(),
    ) {
        DockButton(Icons.Default.Cameraswitch, "Flip camera", lit = false) {
            callbacks.onFlipCamera()
            onTouch()
        }
    }
    DockButton(
        icon = if (controls.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
        label = if (controls.isMuted) "Unmute" else "Mute",
        lit = controls.isMuted,
    ) {
        callbacks.onToggleMute()
        onTouch()
    }
    CallAudioRouteButton(
        audioRoute = controls.audioRoute,
        availableRoutes = controls.availableRoutes,
        onSelectRoute = {
            callbacks.onSelectRoute(it)
            onTouch()
        },
        colors = StageColors.Controls,
        size = DockButtonSize,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private val DockButtonSize = 52.dp

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    lit: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    CallControlButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        backgroundColor = StageColors.Controls.background(lit),
        iconTint = StageColors.Controls.icon(lit),
        size = DockButtonSize,
        modifier = Modifier.padding(horizontal = 4.dp),
        enabled = enabled,
    )
}

@Composable
private fun HangUpButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(width = 76.dp, height = DockButtonSize)
            .clip(CircleShape)
            .background(StageColors.HangUp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Icon(Icons.Default.CallEnd, contentDescription = "Hang up", tint = Color.White)
    }
}

/**
 * The top bar: the *minimise* arrow, and the name with one line below it while a video covers the
 * middle of the stage.
 *
 * @param title null while the stage itself shows the name.
 */
@Composable
internal fun StageTopBar(
    shown: Boolean,
    title: String?,
    subtitle: () -> String,
    muted: Boolean,
    onMinimise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 12.dp),
        ) {
            MinimiseButton(onMinimise)
            if (title != null) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StageStatus(subtitle, MaterialTheme.typography.labelSmall)
                }
                if (muted) MutedMark()
            }
        }
    }
}

/** Leaves the stage and keeps the call. */
@Composable
internal fun MinimiseButton(onMinimise: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onMinimise, modifier = modifier) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimise", tint = Color.White)
    }
}

/**
 * The buttons of an incoming ring. A video call offers *Voice only* and *With video*, so the
 * decision whether the other side sees you is made before the call connects. A voice call offers
 * *Answer*. So does a video call on a locked phone, which starts with the camera off.
 */
@Composable
internal fun StageAnswerRow(
    video: Boolean,
    locked: Boolean,
    callbacks: CallScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        AnswerButton(Icons.Default.CallEnd, "Decline", StageColors.HangUp, onClick = callbacks.onDecline)
        if (video && !locked) {
            AnswerButton(Icons.Default.Call, "Voice only", StageColors.Glass) { callbacks.onAnswer(false) }
            AnswerButton(Icons.Default.Videocam, "With video", StageColors.Answer) { callbacks.onAnswer(true) }
        } else {
            AnswerButton(Icons.Default.Call, "Answer", StageColors.Answer) { callbacks.onAnswer(false) }
        }
    }
}

@Composable
private fun AnswerButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        CallControlButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            backgroundColor = color,
            iconTint = Color.White,
            size = 68.dp,
        )
        Spacer(Modifier.height(8.dp))
        // The button already says it.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = StageColors.DimText,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}
