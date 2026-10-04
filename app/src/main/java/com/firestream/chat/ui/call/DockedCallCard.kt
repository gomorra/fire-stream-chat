package com.firestream.chat.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.dockable
import com.firestream.chat.ui.theme.OnlineGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object DockedCallTags {
    const val STRIP = "docked_call_strip"
    const val CARD = "docked_call_card"
}

private const val ENDED_SHOWN_MILLIS = 1_500L

/**
 * The chat [chatId] draws the running call: it is the call's own chat, the call is past an
 * incoming ring, and the stage is not on screen. The stage's picture-in-picture window counts as
 * the stage.
 */
internal fun docksIn(chatId: String, callChatId: String?, call: CallState, surfaces: Set<CallSurface>): Boolean =
    call.dockable && callChatId == chatId && CallSurface.STAGE !in surfaces

/**
 * The call docked over its chat, at the top of the chat's content. It draws nothing unless
 * [docksIn] says so. While it shows, it reports itself as [CallSurface.DOCK], so the camera keeps
 * running. A call that ends while docked says *Call ended* for a moment.
 */
@Composable
internal fun DockedCall(
    chatId: String,
    modifier: Modifier = Modifier,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val callState by viewModel.callState.collectAsState()
    val callChatId by viewModel.callChatId.collectAsState()
    val surfaces by viewModel.surfaces.collectAsState()
    val docked = docksIn(chatId, callChatId, callState, surfaces)

    // The state of the call before stays `Ended` until the next one starts. Only an end this
    // card watched is shown, or a chat opened later would say it again.
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(callState, docked) {
        if (callState is CallState.Live) watched = docked
    }
    val ended = callState is CallState.Ended && watched
    LaunchedEffect(ended) {
        if (ended) {
            delay(ENDED_SHOWN_MILLIS)
            watched = false
        }
    }

    if (docked) {
        LifecycleStartEffect(viewModel) {
            viewModel.setDockShowing(true)
            onStopOrDispose { viewModel.setDockShowing(false) }
        }
    }

    val context = LocalContext.current
    // Said once, like on the stage. A second refused tap says nothing.
    var refusalExplained by remember { mutableStateOf(false) }
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setCamera(true)
        } else if (!refusalExplained) {
            refusalExplained = true
            Toast.makeText(context, CAMERA_REFUSED_MESSAGE, Toast.LENGTH_LONG).show()
        }
    }
    val callbacks = remember(viewModel, context, askForCamera) {
        CallScreenCallbacks(
            onHangup = { viewModel.hangup() },
            onToggleMute = viewModel::toggleMute,
            onSetCamera = { on ->
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (!on || granted) viewModel.setCamera(on) else askForCamera.launch(Manifest.permission.CAMERA)
            },
            onFlipCamera = viewModel::flipCamera,
            onSelectRoute = viewModel::selectAudioRoute,
        )
    }

    AnimatedVisibility(
        visible = docked || ended,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val uiControls by viewModel.uiControls.collectAsState()
        val participants by viewModel.participants.collectAsState()
        val state = remember(callState, uiControls, participants) {
            CallStageState(callState, uiControls, participants)
        }
        DockedCallCard(
            state = state,
            callbacks = callbacks,
            onFullScreen = { context.startActivity(CallActivity.stageIntent(context)) },
        ) { participantId -> CallVideoView(viewModel, participantId) }
    }
}

/**
 * The docked call, in the app theme. It has two sizes: a strip with the name, the time and three
 * buttons, and a card with the video and every control. A voice call rests as the strip and a call
 * with video as the card. A tap on the strip, or a drag, changes the size until the call's resting
 * size changes. Pulling the card down, or its *Full screen* button, goes back to the stage.
 *
 * @param videoTile draws the video of a participant, or of the own camera for [SELF_TILE_ID].
 */
@Composable
internal fun DockedCallCard(
    state: CallStageState,
    callbacks: CallScreenCallbacks,
    onFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
    videoTile: @Composable (participantId: String) -> Unit,
) {
    val person = state.person ?: return
    val live = state.call is CallState.Live
    val restsAsCard = live && state.showsVideo
    // The size the user chose holds until the call's resting size changes.
    var expanded by remember(restsAsCard) { mutableStateOf(restsAsCard) }
    val asCard = live && expanded
    val status = dockedStatus(state.call)

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AnimatedContent(
            targetState = asCard,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) using SizeTransform() },
            label = "docked_call",
        ) { card ->
            if (card) {
                DockedCard(
                    state = state,
                    person = person,
                    status = status,
                    callbacks = callbacks,
                    onCollapse = { expanded = false },
                    onFullScreen = onFullScreen,
                    videoTile = videoTile,
                )
            } else {
                DockedStrip(state, person, status, callbacks, onExpand = { expanded = true }.takeIf { live })
            }
        }
    }
}

/** The one line under the name. For a connected call it is the running time. */
@Composable
private fun dockedStatus(call: CallState): () -> String = when (call) {
    is CallState.Connected -> rememberElapsedText(call.startTime)
    is CallState.Connecting -> ({ "Connecting…" })
    is CallState.Live -> ({ "Calling…" })
    else -> ({ "Call ended" })
}

// ── The strip ───────────────────────────────────────────────────────────────

/** The slim form: who, how long, camera, microphone and hang up. A tap or a pull down opens the card. */
@Composable
private fun DockedStrip(
    state: CallStageState,
    person: StagePerson,
    status: () -> String,
    callbacks: CallScreenCallbacks,
    onExpand: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .testTag(DockedCallTags.STRIP)
            .then(
                if (onExpand != null) {
                    Modifier
                        .dockDrag(onPulledDown = onExpand)
                        .clickable(onClickLabel = "Open the call", onClick = onExpand)
                } else {
                    Modifier
                }
            )
            .padding(start = 12.dp, end = 8.dp),
    ) {
        StageAvatar(person, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.call is CallState.Connected) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(OnlineGreen))
                    Spacer(Modifier.width(6.dp))
                }
                StageStatus(status, MaterialTheme.typography.labelSmall, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // An ended call has nothing left to switch.
        if (onExpand != null) {
            CameraButton(state, callbacks)
            MicButton(state, callbacks)
            HangUpButton(callbacks)
        }
    }
}

// ── The card ────────────────────────────────────────────────────────────────

private val MediaShape = RoundedCornerShape(18.dp)

/** The card: the other person's video with the self view fixed in a corner, and every control. */
@Composable
private fun DockedCard(
    state: CallStageState,
    person: StagePerson,
    status: () -> String,
    callbacks: CallScreenCallbacks,
    onCollapse: () -> Unit,
    onFullScreen: () -> Unit,
    videoTile: @Composable (String) -> Unit,
) {
    // A share of the screen, so the thread and the composer below keep room on a small phone.
    val mediaHeight = (LocalConfiguration.current.screenHeightDp * 0.3f).dp.coerceIn(168.dp, 260.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(DockedCallTags.CARD)
            .dockDrag(onPulledUp = onCollapse, onPulledDown = onFullScreen, downDistance = 96.dp)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(mediaHeight)
                .clip(MediaShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Until the call connects, the own preview is the picture.
            val previewFills = state.call !is CallState.Connected && state.selfVideo
            if (previewFills) {
                videoTile(SELF_TILE_ID)
            } else {
                val tileSurface = MaterialTheme.colorScheme.surfaceVariant
                RemoteTile(
                    remote = state.remote,
                    videoTile = videoTile,
                    backdrop = { Box(Modifier.fillMaxSize().background(tileSurface)) },
                ) {
                    StageAvatar(person, 76.dp, Modifier.align(Alignment.Center))
                }
                if (state.selfVideo) {
                    val selfShape = MaterialTheme.shapes.medium
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(width = 64.dp, height = 88.dp)
                            .clip(selfShape)
                            .border(1.dp, Color.White.copy(alpha = 0.18f), selfShape)
                            .testTag(CallStageTags.SELF_TILE)
                    ) {
                        videoTile(SELF_TILE_ID)
                    }
                }
            }
            MediaHeader(person.name, status, muted = state.remote?.micOn == false, onFullScreen = onFullScreen)
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            CameraButton(state, callbacks)
            AnimatedVisibility(
                visible = state.controls.cameraOn,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                CardButton(Icons.Default.Cameraswitch, "Flip camera", onClick = callbacks.onFlipCamera)
            }
            MicButton(state, callbacks)
            CallAudioRouteButton(
                audioRoute = state.controls.audioRoute,
                availableRoutes = state.controls.availableRoutes,
                onSelectRoute = callbacks.onSelectRoute,
                size = CardButtonSize,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            HangUpButton(callbacks)
        }
        // The handle: the card can be pulled.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(14.dp)) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
    }
}

/** Over the picture: who and how long, a mark while they are muted, and the way back to the stage. */
@Composable
private fun MediaHeader(name: String, status: () -> String, muted: Boolean, onFullScreen: () -> Unit) {
    val shade = Color.Black.copy(alpha = 0.42f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(CircleShape)
                .background(shade)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            if (muted) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = "Muted",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            StageStatus(status, MaterialTheme.typography.labelSmall, Color.White)
        }
        Spacer(Modifier.weight(1f))
        CallControlButton(
            icon = Icons.Default.OpenInFull,
            contentDescription = "Full screen",
            onClick = onFullScreen,
            backgroundColor = shade,
            iconTint = Color.White,
            size = CardButtonSize,
        )
    }
}

// ── Controls ────────────────────────────────────────────────────────────────

private val CardButtonSize = 48.dp

@Composable
private fun CameraButton(state: CallStageState, callbacks: CallScreenCallbacks) {
    val on = state.controls.cameraOn
    CardButton(
        icon = if (on) Icons.Default.Videocam else Icons.Default.VideocamOff,
        label = if (on) "Turn camera off" else "Turn camera on",
        lit = on,
        enabled = state.controls.videoAvailable,
        onClick = { callbacks.onSetCamera(!on) },
    )
}

@Composable
private fun MicButton(state: CallStageState, callbacks: CallScreenCallbacks) {
    val muted = state.controls.isMuted
    CardButton(
        icon = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
        label = if (muted) "Unmute" else "Mute",
        lit = muted,
        onClick = callbacks.onToggleMute,
    )
}

@Composable
private fun HangUpButton(callbacks: CallScreenCallbacks) {
    CardButton(Icons.Default.CallEnd, "Hang up", fill = StageColors.HangUp, onClick = callbacks.onHangup)
}

@Composable
private fun CardButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    lit: Boolean = false,
    enabled: Boolean = true,
    fill: Color? = null,
) {
    val colors = CallControlColors.themed()
    CallControlButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        backgroundColor = fill ?: colors.background(lit),
        iconTint = if (fill != null) Color.White else colors.icon(lit),
        size = CardButtonSize,
        modifier = Modifier.padding(horizontal = 2.dp),
        enabled = enabled,
    )
}

// ── The drag ────────────────────────────────────────────────────────────────

/**
 * A vertical pull on the docked call. The surface gives a little under the finger, and the pull
 * counts when it is let go past its distance. The size changes afterwards, not under the finger.
 */
@Composable
private fun Modifier.dockDrag(
    onPulledUp: (() -> Unit)? = null,
    onPulledDown: (() -> Unit)? = null,
    downDistance: Dp = PullDistance,
): Modifier {
    val currentUp by rememberUpdatedState(onPulledUp)
    val currentDown by rememberUpdatedState(onPulledDown)
    val scope = rememberCoroutineScope()
    // Read only where the layer is drawn, so a drag recomposes nothing.
    val give = remember { Animatable(0f) }
    return this
        .graphicsLayer { translationY = give.value }
        .pointerInput(downDistance) {
            var pull = 0f
            val letGo = {
                scope.launch { give.animateTo(0f) }
                Unit
            }
            detectVerticalDragGestures(
                onDragStart = { pull = 0f },
                onDragEnd = {
                    letGo()
                    when {
                        pull <= -PullDistance.toPx() -> currentUp?.invoke()
                        pull >= downDistance.toPx() -> currentDown?.invoke()
                    }
                },
                onDragCancel = letGo,
                onVerticalDrag = { change, amount ->
                    change.consume()
                    pull += amount
                    val target = pull * PULL_GIVE
                    scope.launch { give.snapTo(target) }
                },
            )
        }
}

private val PullDistance = 40.dp

/** How much of a pull the surface follows. */
private const val PULL_GIVE = 0.2f
