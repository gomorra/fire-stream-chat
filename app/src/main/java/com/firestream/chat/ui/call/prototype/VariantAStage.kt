// PROTOTYPE — throwaway. See VideoCallPrototype.kt.
//
// A · Stage — the WhatsApp and Signal convention. The remote video fills the screen. The self
// view is a tile that is dragged and snaps to a corner, and a tap swaps the two. The controls
// are a floating dock that hides after four seconds while video shows. Always dark.
// Three and four people split the stage, and the self tile keeps floating.
package com.firestream.chat.ui.call.prototype

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.ui.call.CallControlButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val StageBlack = Color(0xFF08090A)
private val DockSurface = Color(0xE617181B)
private val Glass = Color(0x2EFFFFFF)
private val DimText = Color(0xB8FFFFFF)

private enum class StageScene { RINGING, CONNECTED, ENDED }

@Composable
internal fun VariantAStage(scenario: CallScenario, host: PrototypeHost) {
    val scene = when (scenario.phase) {
        CallPhase.CONNECTED -> StageScene.CONNECTED
        CallPhase.ENDED -> StageScene.ENDED
        else -> StageScene.RINGING
    }
    Box(Modifier.fillMaxSize().background(StageBlack)) {
        Crossfade(targetState = scene, animationSpec = tween(260), label = "stage") { shown ->
            when (shown) {
                StageScene.RINGING -> StageRinging(scenario)
                StageScene.CONNECTED -> StageConnected(scenario, host)
                StageScene.ENDED -> StageEnded(scenario, host)
            }
        }
    }
}

// ── Connected ───────────────────────────────────────────────────────────────

@Composable
private fun StageConnected(scenario: CallScenario, host: PrototypeHost) {
    var swapped by rememberSaveable { mutableStateOf(false) }
    var chromeShown by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }

    // The dock only hides while there is video to look at. A voice call keeps it.
    val canHide = scenario.showsVideo
    LaunchedEffect(touches, canHide) {
        if (!canHide) {
            chromeShown = true
        } else if (chromeShown) {
            delay(4_000)
            chromeShown = false
        }
    }

    val solo = scenario.people == 2
    val selfOnStage = solo && swapped && scenario.myCamera
    val stagePerson = if (selfOnStage) scenario.me else scenario.remotes.first()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    chromeShown = !chromeShown
                    touches++
                }
            },
    ) {
        if (solo) StageSolo(stagePerson, scenario) else StageGroup(scenario)
        StageScrims(chromeShown)
        StageTopBar(
            scenario = scenario,
            shown = chromeShown,
            showTitle = !solo || scenario.cameraOn(stagePerson),
            host = host,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        FloatingSelf(
            scenario = scenario,
            showsRemote = selfOnStage,
            dockShown = chromeShown,
            onTap = { if (solo) swapped = !swapped },
        )
        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            StageDock(scenario, onTouch = { touches++ })
        }
    }
}

/** One person across the whole screen: their video, or the voice-call look when their camera is off. */
@Composable
private fun StageSolo(person: StandInPerson, scenario: CallScenario) {
    val isMe = person.index == 0
    val weak = !isMe && scenario.weakNetwork
    Crossfade(targetState = scenario.cameraOn(person), animationSpec = tween(320), label = "solo") { on ->
        if (on) {
            Box(Modifier.fillMaxSize()) {
                StandInVideo(person, mirrored = isMe && scenario.frontCamera, weak = weak)
            }
        } else {
            Box(Modifier.fillMaxSize().background(voiceBackdrop(person.tint)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SpeakingAvatar(person, scenario, 132.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = scenario.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = rememberCallTimer(scenario),
                        style = MaterialTheme.typography.bodyLarge,
                        color = DimText,
                    )
                    if (!scenario.micOn(person)) {
                        Spacer(Modifier.height(12.dp))
                        NameTag(name = "Muted", micOn = false, onVideo = true)
                    }
                }
            }
        }
    }
}

private fun voiceBackdrop(tint: Color): Brush = Brush.radialGradient(
    0f to tint.copy(alpha = 0.26f),
    1f to Color.Transparent,
    radius = 900f,
)

/** Two remote people stack. Three put one over two. Names sit on the seam, clear of the bars. */
@Composable
private fun StageGroup(scenario: CallScenario) {
    val remotes = scenario.remotes
    val seam = Arrangement.spacedBy(3.dp)
    Column(verticalArrangement = seam, modifier = Modifier.fillMaxSize()) {
        ParticipantTile(
            person = remotes[0],
            scenario = scenario,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            nameAlignment = Alignment.BottomStart,
        )
        if (remotes.size == 2) {
            ParticipantTile(
                person = remotes[1],
                scenario = scenario,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                nameAlignment = Alignment.TopStart,
            )
        } else {
            Row(horizontalArrangement = seam, modifier = Modifier.weight(1f)) {
                remotes.drop(1).forEach { person ->
                    ParticipantTile(
                        person = person,
                        scenario = scenario,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        nameAlignment = Alignment.TopStart,
                    )
                }
            }
        }
    }
}

/** Shade behind the top bar and the dock, so white text holds on any video. */
@Composable
private fun BoxWithConstraintsScope.StageScrims(shown: Boolean) {
    val strength by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "scrim")
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(180.dp + LocalTopClearance.current)
            .graphicsLayer { alpha = strength }
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
    )
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(220.dp)
            .graphicsLayer { alpha = strength }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)))),
    )
}

@Composable
private fun StageTopBar(
    scenario: CallScenario,
    shown: Boolean,
    showTitle: Boolean,
    host: PrototypeHost,
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
                .padding(top = LocalTopClearance.current)
                .padding(start = 4.dp, end = 12.dp),
        ) {
            // Leaves the screen and keeps the call: picture-in-picture while video shows.
            IconButton(onClick = host.enterPip) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimise", tint = Color.White)
            }
            if (showTitle) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = scenario.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = rememberCallTimer(scenario),
                        style = MaterialTheme.typography.labelSmall,
                        color = DimText,
                    )
                }
                // A group tile carries its own chip. The single full-screen video has none.
                if (scenario.weakNetwork && scenario.people == 2) WeakNetworkChip()
            }
        }
    }
}

// ── The floating self view ──────────────────────────────────────────────────

/**
 * Follows the finger and snaps to the nearest corner. The corners move up when the dock
 * shows, so the tile never sits under it.
 */
@Composable
private fun BoxWithConstraintsScope.FloatingSelf(
    scenario: CallScenario,
    showsRemote: Boolean,
    dockShown: Boolean,
    onTap: () -> Unit,
) {
    val density = LocalDensity.current
    val small = scenario.people > 2
    val tileWidth = if (small) 76.dp else 108.dp
    val tileHeight = if (small) 106.dp else 152.dp
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val topEdge = safe.calculateTopPadding() + LocalTopClearance.current + 64.dp
    val bottomEdge = safe.calculateBottomPadding() + if (dockShown) 108.dp else 20.dp
    val side = 14.dp
    val corners = with(density) {
        Rect(
            left = side.toPx(),
            top = topEdge.toPx(),
            right = (maxWidth - side - tileWidth).toPx(),
            bottom = (maxHeight - bottomEdge - tileHeight).toPx(),
        )
    }

    var corner by rememberSaveable { mutableIntStateOf(3) }
    val position = remember { Animatable(cornerOffset(corner, corners), Offset.VectorConverter) }
    val settle = spring<Offset>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    LaunchedEffect(corner, corners) { position.animateTo(cornerOffset(corner, corners), settle) }

    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)
    val shape = RoundedCornerShape(18.dp)

    AnimatedVisibility(
        visible = scenario.myCamera,
        enter = fadeIn() + scaleIn(initialScale = 0.7f),
        exit = fadeOut() + scaleOut(targetScale = 0.7f),
        modifier = Modifier.offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) },
    ) {
        ParticipantTile(
            person = if (showsRemote) scenario.remotes.first() else scenario.me,
            scenario = scenario,
            shape = shape,
            showName = false,
            ring = false,
            avatarSize = 40.dp,
            modifier = Modifier
                .size(tileWidth, tileHeight)
                .shadow(10.dp, shape)
                .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                .pointerInput(Unit) { detectTapGestures { currentOnTap() } }
                .pointerInput(corners) {
                    detectDragGestures(
                        onDragEnd = {
                            corner = nearestCorner(position.value, corners)
                            scope.launch { position.animateTo(cornerOffset(corner, corners), settle) }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch { position.snapTo(position.value + amount) }
                        },
                    )
                },
        )
    }
}

// Corners count 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right.
private fun cornerOffset(corner: Int, corners: Rect) = Offset(
    x = if (corner % 2 == 0) corners.left else corners.right,
    y = if (corner < 2) corners.top else corners.bottom,
)

private fun nearestCorner(at: Offset, corners: Rect): Int {
    val right = at.x > (corners.left + corners.right) / 2f
    val bottom = at.y > (corners.top + corners.bottom) / 2f
    return (if (bottom) 2 else 0) + (if (right) 1 else 0)
}

// ── The dock ────────────────────────────────────────────────────────────────

/** Camera, flip, mic, speaker, hang up. Flip is only there while the own camera is on. */
@Composable
private fun StageDock(scenario: CallScenario, onTouch: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(36.dp), color = DockSurface, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            DockButton(
                icon = if (scenario.myCamera) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = if (scenario.myCamera) "Turn camera off" else "Turn camera on",
                lit = scenario.myCamera,
            ) {
                scenario.myCamera = !scenario.myCamera
                onTouch()
            }
            AnimatedVisibility(
                visible = scenario.myCamera,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                DockButton(Icons.Default.Cameraswitch, "Flip camera", lit = false) {
                    scenario.frontCamera = !scenario.frontCamera
                    onTouch()
                }
            }
            DockButton(
                icon = if (scenario.myMic) Icons.Default.Mic else Icons.Default.MicOff,
                label = if (scenario.myMic) "Mute" else "Unmute",
                lit = !scenario.myMic,
            ) {
                scenario.myMic = !scenario.myMic
                onTouch()
            }
            DockButton(
                icon = Icons.Default.VolumeUp,
                label = "Speaker",
                lit = scenario.audioRoute == CallAudioRoute.SPEAKER,
            ) {
                scenario.toggleSpeaker()
                onTouch()
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = 76.dp, height = 52.dp)
                    .clip(CircleShape)
                    .background(HangUpRed)
                    .clickable(onClick = scenario::hangUp),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Hang up", tint = Color.White)
            }
        }
    }
}

/** Lit means the thing is on and out of its resting state: camera on, muted, speaker. */
@Composable
private fun DockButton(icon: ImageVector, label: String, lit: Boolean, onClick: () -> Unit) {
    CallControlButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        backgroundColor = if (lit) Color.White else Glass,
        iconTint = if (lit) StageBlack else Color.White,
        size = 52.dp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ── Ringing, connecting, ended ──────────────────────────────────────────────

/** The own preview fills the screen behind the name. A voice ring gets a quiet glow instead. */
@Composable
private fun StageRinging(scenario: CallScenario) {
    val caller = scenario.remotes.first()
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = scenario.myCamera, animationSpec = tween(320), label = "preview") { preview ->
            if (preview) {
                Box(Modifier.fillMaxSize()) {
                    StandInVideo(scenario.me, mirrored = scenario.frontCamera)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
                }
            } else {
                Box(Modifier.fillMaxSize().background(voiceBackdrop(caller.tint)))
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = LocalTopClearance.current + 44.dp, start = 24.dp, end = 24.dp),
        ) {
            RingingFaces(scenario)
            Spacer(Modifier.height(22.dp))
            Text(
                text = scenario.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    scenario.phase == CallPhase.INCOMING && scenario.isGroup ->
                        "${caller.name} · ${scenario.statusText("")}"
                    else -> scenario.statusText("")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = DimText,
            )
            if (scenario.phase == CallPhase.CONNECTING) {
                Spacer(Modifier.height(18.dp))
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            if (scenario.phase == CallPhase.INCOMING) StageAnswer(scenario) else StageDock(scenario, onTouch = {})
        }
    }
}

@Composable
private fun RingingFaces(scenario: CallScenario) {
    val remotes = scenario.remotes
    if (remotes.size == 1) {
        PulsingAvatar(
            person = remotes[0],
            diameter = 108.dp,
            pulsing = scenario.phase != CallPhase.CONNECTING,
            ringColor = Color.White,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy((-18).dp)) {
            remotes.forEach { StandInAvatar(it, 76.dp, Modifier.border(3.dp, StageBlack, CircleShape)) }
        }
    }
}

/** Answering a video call: decide first whether the other side will see you. */
@Composable
private fun StageAnswer(scenario: CallScenario) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (scenario.startedAsVideo) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Glass)
                    .clickable { scenario.myCamera = !scenario.myCamera }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = if (scenario.myCamera) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (scenario.myCamera) "Your camera is on" else "Your camera is off",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(28.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(72.dp)) {
            AnswerButton(Icons.Default.CallEnd, "Decline", HangUpRed, onClick = scenario::hangUp)
            AnswerButton(
                icon = if (scenario.myCamera) Icons.Default.Videocam else Icons.Default.Call,
                label = "Answer",
                color = AnswerGreen,
                onClick = { scenario.answer(withVideo = scenario.myCamera) },
            )
        }
    }
}

@Composable
private fun AnswerButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CallControlButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            backgroundColor = color,
            iconTint = Color.White,
            size = 68.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = DimText)
    }
}

@Composable
private fun StageEnded(scenario: CallScenario, host: PrototypeHost) {
    Box(Modifier.fillMaxSize().background(voiceBackdrop(scenario.remotes.first().tint)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RingingFaces(scenario)
            Spacer(Modifier.height(22.dp))
            Text(scenario.title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (scenario.endedAfterSeconds > 0) {
                    "Call ended · ${formatElapsed(scenario.endedAfterSeconds)}"
                } else {
                    "Call ended"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = DimText,
            )
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StagePill("Call again", filled = true, onClick = scenario::callAgain)
                StagePill("Close", filled = false, onClick = host.close)
            }
        }
    }
}

@Composable
private fun StagePill(text: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (filled) StageBlack else Color.White,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (filled) Color.White else Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
    )
}
