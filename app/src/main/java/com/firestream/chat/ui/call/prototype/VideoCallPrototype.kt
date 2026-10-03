// PROTOTYPE — throwaway. Lives on prototype/video-call and is never merged into main.
//
// Question: what does a call with video look like and how is it driven, for two, three and
// four people?
//
// Three variants in one activity, switched by the floating pill under the status bar:
//   A · Stage    the remote video fills the screen, the self view floats, the dock hides
//   B · Split    an equal tile for everyone, controls in a bottom sheet that never hides
//   C · Docked   the call is a card over the chat, and the thread below stays usable
//
// Everything is simulated. No camera, no microphone, no CallService, no Firestore, no network:
// a video tile is a Canvas drawing. Plan: docs/plans/video-calls.md § Prototype first.
package com.firestream.chat.ui.call.prototype

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.ui.theme.FireOrange
import com.firestream.chat.ui.theme.FireStreamTheme
import com.firestream.chat.ui.theme.FsSurface2
import com.firestream.chat.ui.theme.FsSurface3
import com.firestream.chat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ── The scenario ────────────────────────────────────────────────────────────

internal enum class CallVariant(val key: String, val title: String) {
    STAGE("A", "Stage"),
    SPLIT("B", "Split"),
    DOCKED("C", "Docked");

    companion object {
        fun of(key: String?): CallVariant =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: STAGE
    }
}

internal enum class CallPhase(val key: String, val label: String) {
    OUTGOING("outgoing", "Outgoing ring"),
    INCOMING("incoming", "Incoming ring"),
    CONNECTING("connecting", "Connecting"),
    CONNECTED("connected", "Connected"),
    ENDED("ended", "Ended");

    companion object {
        fun of(key: String?): CallPhase? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** MIXED turns one remote camera off and leaves the others on. Groups only. */
internal enum class RemoteCameras(val key: String) {
    ON("on"),
    OFF("off"),
    MIXED("mixed");

    companion object {
        fun of(key: String?): RemoteCameras? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** What the activity was started with. Every field maps to one intent extra. */
internal data class PrototypeLaunch(
    val variant: CallVariant = CallVariant.STAGE,
    val people: Int = 2,
    val phase: CallPhase = CallPhase.CONNECTED,
    val chatName: String = "Mara Lindqvist",
    val isGroup: Boolean = false,
    val video: Boolean = true,
    val myCamera: Boolean = true,
    val remoteCameras: RemoteCameras = RemoteCameras.ON,
    val weak: Boolean = false,
    val bare: Boolean = false,
    val autoAdvance: Boolean = false,
    val forceDark: Boolean? = null,
)

@Immutable
internal data class StandInPerson(val index: Int, val name: String, val tint: Color) {
    val initial: String get() = name.take(1).uppercase()
}

/** What a variant may ask of the activity. */
@Immutable
internal class PrototypeHost(
    val setPipEligible: (Boolean) -> Unit,
    val enterPip: () -> Unit,
    val close: () -> Unit,
)

private val PersonTints = listOf(Color(0xFF2F8F83), Color(0xFF4A72B8), Color(0xFF8A5FA8))
private val MeTint = Color(0xFFB7743B)

internal val HangUpRed = Color(0xFFE53935)
internal val AnswerGreen = Color(0xFF43A047)

/**
 * The made-up call every variant renders. Person 0 is the user, 1 to 3 are the remote people.
 * The switcher's chips and the variants' own controls both write here.
 */
@Stable
internal class CallScenario(private val launch: PrototypeLaunch) {
    var variant by mutableStateOf(launch.variant)
    var phase by mutableStateOf(launch.phase)
        private set
    var people by mutableIntStateOf(launch.people.coerceIn(2, 4))
        private set
    var startedAsVideo by mutableStateOf(launch.video)
    var myCamera by mutableStateOf(launch.myCamera)
    var remoteCameras by mutableStateOf(launch.remoteCameras)
        private set
    var theirMic by mutableStateOf(true)
    var weakNetwork by mutableStateOf(launch.weak)
    var myMic by mutableStateOf(true)
    var frontCamera by mutableStateOf(true)
    var audioRoute by mutableStateOf(CallAudioRoute.EARPIECE)
    var routePicked by mutableStateOf(false)
        private set
    var connectedAtMs by mutableLongStateOf(System.currentTimeMillis() - 134_000L)
        private set
    var endedAfterSeconds by mutableLongStateOf(0L)
        private set

    val me = StandInPerson(0, "You", MeTint)

    private val cast: List<StandInPerson> = run {
        val first = if (launch.isGroup) "Mara" else launch.chatName.substringBefore(' ').ifBlank { "Mara" }
        listOf(
            StandInPerson(1, first, PersonTints[0]),
            StandInPerson(2, "Jonas", PersonTints[1]),
            StandInPerson(3, "Aylin", PersonTints[2]),
        )
    }

    val remotes: List<StandInPerson> get() = cast.take(people - 1)

    /** Remote people first, the user last. */
    val everyone: List<StandInPerson> get() = remotes + me

    val title: String
        get() = when {
            launch.isGroup -> launch.chatName
            people == 2 -> launch.chatName
            else -> remotes.joinToString(", ") { it.name }
        }

    val isGroup: Boolean get() = launch.isGroup || people > 2

    /** A remote camera shows only once connected. The own preview also shows while ringing. */
    fun cameraOn(person: StandInPerson): Boolean = when {
        phase == CallPhase.ENDED -> false
        person.index == 0 -> myCamera
        phase != CallPhase.CONNECTED -> false
        remoteCameras == RemoteCameras.ON -> true
        remoteCameras == RemoteCameras.OFF -> false
        else -> person.index != 2
    }

    fun micOn(person: StandInPerson): Boolean = when (person.index) {
        0 -> myMic
        1 -> theirMic
        else -> true
    }

    fun levelOf(person: StandInPerson, t: Float): Float =
        if (phase != CallPhase.CONNECTED || !micOn(person)) 0f else audioLevel(person.index, t, people)

    /** Any video on screen: the plan's rule for the speaker, the proximity lock and picture-in-picture. */
    val showsVideo: Boolean get() = phase == CallPhase.CONNECTED && everyone.any { cameraOn(it) }

    val elapsedSeconds: Long get() = ((System.currentTimeMillis() - connectedAtMs) / 1000).coerceAtLeast(0)

    fun go(next: CallPhase) {
        if (next == phase) return
        if (next == CallPhase.ENDED) endedAfterSeconds = if (phase == CallPhase.CONNECTED) elapsedSeconds else 0
        if (next == CallPhase.CONNECTED) connectedAtMs = System.currentTimeMillis()
        phase = next
    }

    fun answer(withVideo: Boolean) {
        myCamera = withVideo
        go(CallPhase.CONNECTING)
    }

    fun hangUp() = go(CallPhase.ENDED)

    fun callAgain() = go(CallPhase.OUTGOING)

    fun cyclePeople() {
        people = if (people == 4) 2 else people + 1
        if (people == 2 && remoteCameras == RemoteCameras.MIXED) remoteCameras = RemoteCameras.ON
    }

    fun cycleRemoteCameras() {
        remoteCameras = when (remoteCameras) {
            RemoteCameras.ON -> RemoteCameras.OFF
            RemoteCameras.OFF -> if (people > 2) RemoteCameras.MIXED else RemoteCameras.ON
            RemoteCameras.MIXED -> RemoteCameras.ON
        }
    }

    fun pickRoute(route: CallAudioRoute) {
        audioRoute = route
        routePicked = true
    }

    fun toggleSpeaker() =
        pickRoute(if (audioRoute == CallAudioRoute.SPEAKER) CallAudioRoute.EARPIECE else CallAudioRoute.SPEAKER)

    companion object {
        fun saver(launch: PrototypeLaunch): Saver<CallScenario, Any> = listSaver(
            save = { s ->
                listOf<Any>(
                    s.variant.ordinal, s.phase.ordinal, s.people, s.startedAsVideo, s.myCamera,
                    s.remoteCameras.ordinal, s.theirMic, s.weakNetwork, s.myMic, s.frontCamera,
                    s.audioRoute.ordinal, s.routePicked, s.connectedAtMs, s.endedAfterSeconds,
                )
            },
            restore = { v ->
                CallScenario(launch).apply {
                    variant = CallVariant.entries[v[0] as Int]
                    phase = CallPhase.entries[v[1] as Int]
                    people = v[2] as Int
                    startedAsVideo = v[3] as Boolean
                    myCamera = v[4] as Boolean
                    remoteCameras = RemoteCameras.entries[v[5] as Int]
                    theirMic = v[6] as Boolean
                    weakNetwork = v[7] as Boolean
                    myMic = v[8] as Boolean
                    frontCamera = v[9] as Boolean
                    audioRoute = CallAudioRoute.entries[v[10] as Int]
                    routePicked = v[11] as Boolean
                    connectedAtMs = v[12] as Long
                    endedAfterSeconds = v[13] as Long
                }
            },
        )
    }
}

internal fun CallScenario.statusText(timer: String): String = when (phase) {
    CallPhase.OUTGOING -> "Ringing…"
    CallPhase.INCOMING -> if (startedAsVideo) "Incoming video call" else "Incoming voice call"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.CONNECTED -> timer
    CallPhase.ENDED -> "Call ended"
}

internal fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
internal fun rememberCallTimer(scenario: CallScenario): String {
    val now by produceState(System.currentTimeMillis(), scenario.connectedAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    return formatElapsed(((now - scenario.connectedAtMs) / 1000).coerceAtLeast(0))
}

// ── Made-up time and audio ──────────────────────────────────────────────────

/** Seconds since the prototype opened. Read it in a draw lambda, so a tick redraws and nothing recomposes. */
internal val LocalCallClock = staticCompositionLocalOf<FloatState> { mutableFloatStateOf(0f) }

/** The height the switcher covers under the status bar. Variants keep their own top edge clear of it. */
internal val LocalTopClearance = compositionLocalOf { 0.dp }

@Composable
private fun rememberPrototypeClock(): FloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { seconds.floatValue = (it - start) / 1_000_000_000f }
    }
    return seconds
}

// Who has the floor, turn by turn: 0 is the user, 1 to 3 the remote people, -1 nobody.
private val SpeakerTurns = intArrayOf(1, 1, 0, 2, 1, -1, 3, 2, 0, 1, 3, -1)
private const val TurnSeconds = 2.6f

private fun speakerOfTurn(turn: Int, people: Int): Int {
    val who = SpeakerTurns[turn.mod(SpeakerTurns.size)]
    return if (who <= 0) who else (who - 1) % (people - 1) + 1
}

internal fun audioLevel(index: Int, t: Float, people: Int): Float {
    if (speakerOfTurn((t / TurnSeconds).toInt(), people) != index) return 0f
    val into = (t % TurnSeconds) / TurnSeconds
    val envelope = sin(PI.toFloat() * into)
    val syllables = 0.55f + 0.45f * abs(sin(t * 9f + index))
    return envelope * syllables
}

/** The remote person who spoke last, for a layout that features one speaker. */
internal fun activeRemoteAt(t: Float, people: Int): Int {
    val now = (t / TurnSeconds).toInt()
    for (back in 0 until SpeakerTurns.size) {
        val who = speakerOfTurn(now - back, people)
        if (who > 0) return who
    }
    return 1
}

// ── Stand-in video, avatar and tile ─────────────────────────────────────────

@Composable
internal fun StandInVideo(
    person: StandInPerson,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    weak: Boolean = false,
) {
    val clock = LocalCallClock.current
    Canvas(modifier.fillMaxSize().then(if (weak) Modifier.blur(9.dp) else Modifier)) {
        // A weak network stutters: the frame only moves three times a second.
        val t = clock.floatValue.let { if (weak) floor(it * 3f) / 3f else it }
        clipRect {
            scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f) { drawStandInFrame(person, t) }
        }
    }
}

private fun DrawScope.drawStandInFrame(person: StandInPerson, t: Float) {
    val w = size.width
    val h = size.height
    val u = min(w, h)
    val seed = person.index * 1.7f

    // The room: a wall that lightens towards the floor, a window light that drifts, a warm lamp.
    drawRect(
        Brush.verticalGradient(
            listOf(lerp(person.tint, Color.Black, 0.62f), lerp(person.tint, Color.Black, 0.30f)),
        ),
    )
    val window = Offset(w * (0.26f + 0.14f * sin(t * 0.31f + seed)), h * (0.20f + 0.05f * cos(t * 0.23f + seed)))
    drawCircle(
        brush = Brush.radialGradient(
            listOf(lerp(person.tint, Color.White, 0.45f).copy(alpha = 0.55f), Color.Transparent),
            center = window,
            radius = u * 0.9f,
        ),
        radius = u * 0.9f,
        center = window,
    )
    val lamp = Offset(w * (0.84f + 0.06f * cos(t * 0.27f + seed)), h * 0.74f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(lerp(person.tint, FireOrange, 0.5f).copy(alpha = 0.24f), Color.Transparent),
            center = lamp,
            radius = u * 0.7f,
        ),
        radius = u * 0.7f,
        center = lamp,
    )

    // Head and shoulders, framed like a phone held at arm's length and swaying a little so
    // the tile reads as live. The hair sits off-centre, so a mirrored self view is visible.
    val head = u * 0.17f
    val cx = w / 2f + sin(t * 0.8f + seed) * u * 0.014f
    val cy = h * 0.43f + sin(t * 1.3f + seed) * u * 0.008f
    val skin = lerp(person.tint, Color.White, 0.5f)
    val cloth = lerp(person.tint, Color.White, 0.16f)
    val shoulders = min(head * 4.8f, w * 0.94f)
    val shoulderTop = cy + head * 1.32f
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(cloth, lerp(cloth, Color.Black, 0.42f)),
            startY = shoulderTop,
            endY = max(h, shoulderTop + 1f),
        ),
        topLeft = Offset(cx - shoulders / 2f, shoulderTop),
        size = Size(shoulders, (h - shoulderTop) + shoulders),
        cornerRadius = CornerRadius(shoulders * 0.44f),
    )
    drawRect(
        color = lerp(skin, Color.Black, 0.2f),
        topLeft = Offset(cx - head * 0.34f, cy + head * 0.7f),
        size = Size(head * 0.68f, head * 0.95f),
    )
    drawCircle(color = skin, radius = head, center = Offset(cx, cy))
    drawArc(
        color = lerp(person.tint, Color.Black, 0.5f),
        startAngle = 188f + person.index * 7f,
        sweepAngle = 152f,
        useCenter = false,
        topLeft = Offset(cx - head, cy - head),
        size = Size(head * 2f, head * 2f),
    )

    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f)),
            center = center,
            radius = max(w, h) * 0.75f,
        ),
    )
}

@Composable
internal fun StandInAvatar(person: StandInPerson, diameter: Dp, modifier: Modifier = Modifier) {
    val dark = LocalIsDarkTheme.current
    val fill = if (dark) lerp(person.tint, FsSurface3, 0.6f) else lerp(person.tint, Color.White, 0.72f)
    val ink = if (dark) lerp(person.tint, Color.White, 0.68f) else lerp(person.tint, Color.Black, 0.38f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(fill),
    ) {
        Text(
            text = person.initial,
            color = ink,
            fontWeight = FontWeight.SemiBold,
            // The one ad hoc size: an initial has to scale with its circle.
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = (diameter.value * 0.4f).sp,
                lineHeight = (diameter.value * 0.5f).sp,
            ),
        )
    }
}

/** The avatar with a halo that follows that person's audio level. */
@Composable
internal fun SpeakingAvatar(
    person: StandInPerson,
    scenario: CallScenario,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val clock = LocalCallClock.current
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.drawBehind {
            val level = scenario.levelOf(person, clock.floatValue)
            if (level > 0.04f) {
                val radius = this.size.minDimension / 2f
                drawCircle(color = accent.copy(alpha = 0.1f * level), radius = radius + 16.dp.toPx() * level)
                drawCircle(
                    color = accent.copy(alpha = 0.45f + 0.55f * level),
                    radius = radius + (4f + 3f * level).dp.toPx(),
                    style = Stroke((1.5f + 1.5f * level).dp.toPx()),
                )
            }
        },
    ) {
        StandInAvatar(person, diameter)
    }
}

/** The avatar with rings that travel outwards while a call rings. */
@Composable
internal fun PulsingAvatar(
    person: StandInPerson,
    diameter: Dp,
    pulsing: Boolean,
    modifier: Modifier = Modifier,
    ringColor: Color = person.tint,
) {
    val pulse by rememberInfiniteTransition(label = "ring").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "ring",
    )
    Box(
        modifier = modifier.drawBehind {
            if (!pulsing) return@drawBehind
            repeat(2) { i ->
                val p = (pulse + i * 0.5f) % 1f
                drawCircle(
                    color = ringColor.copy(alpha = (1f - p) * 0.34f),
                    radius = this.size.minDimension / 2f * (1f + 0.6f * p),
                )
            }
        },
    ) {
        StandInAvatar(person, diameter)
    }
}

/** A ring on the tile's own outline, as wide and as bright as that person is loud. */
@Composable
private fun Modifier.speakingRing(person: StandInPerson, scenario: CallScenario, shape: Shape): Modifier {
    val clock = LocalCallClock.current
    val accent = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val level = scenario.levelOf(person, clock.floatValue)
        if (level > 0.04f) {
            val stroke = (1.5f + 2f * level).dp.toPx()
            val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
            translate(stroke / 2f, stroke / 2f) {
                drawOutline(outline, accent.copy(alpha = 0.4f + 0.6f * level), style = Stroke(stroke))
            }
        }
    }
}

@Composable
internal fun tileSurface(): Color =
    if (LocalIsDarkTheme.current) FsSurface2 else MaterialTheme.colorScheme.surfaceVariant

/** A resting control that sits on a surface: one tone above it in both themes. */
@Composable
internal fun quietControl(): Color =
    if (LocalIsDarkTheme.current) FsSurface3 else MaterialTheme.colorScheme.surfaceVariant

/**
 * One person. Video while that camera is on, otherwise the avatar. The rule from the plan's
 * step 4, with the first frame stood in for by a cross-fade.
 */
@Composable
internal fun ParticipantTile(
    person: StandInPerson,
    scenario: CallScenario,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    showName: Boolean = true,
    nameAlignment: Alignment = Alignment.BottomStart,
    ring: Boolean = true,
    avatarSize: Dp = 88.dp,
    caption: String? = null,
    pulsing: Boolean = false,
) {
    val cameraOn = scenario.cameraOn(person)
    val isMe = person.index == 0
    val weak = !isMe && scenario.weakNetwork
    Box(
        modifier = modifier
            .clip(shape)
            .background(tileSurface())
            // Over video the tile's outline shows who speaks. An avatar carries its own ring.
            .then(if (ring && cameraOn) Modifier.speakingRing(person, scenario, shape) else Modifier),
    ) {
        Crossfade(targetState = cameraOn, animationSpec = tween(320), label = "tile") { on ->
            if (on) {
                StandInVideo(person, mirrored = isMe && scenario.frontCamera, weak = weak)
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (pulsing) {
                        PulsingAvatar(person, avatarSize, pulsing = true)
                    } else {
                        SpeakingAvatar(person, scenario, avatarSize)
                    }
                    if (caption != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (showName) {
            NameTag(
                name = person.name,
                micOn = scenario.micOn(person),
                onVideo = cameraOn,
                modifier = Modifier.align(nameAlignment).padding(8.dp),
            )
        }
        if (cameraOn && weak) {
            WeakNetworkChip(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

@Composable
internal fun NameTag(name: String, micOn: Boolean, onVideo: Boolean, modifier: Modifier = Modifier) {
    val ink = if (onVideo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(if (onVideo) Color.Black.copy(alpha = 0.42f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (!micOn) {
            Icon(Icons.Default.MicOff, contentDescription = "Muted", tint = ink, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun WeakNetworkChip(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Default.NetworkCheck,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("Weak connection", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

// ── A value that is dragged and settles on the nearest stop ─────────────────

/** B's sheet and C's card both hold a height that follows the finger and snaps to a stop. */
@Stable
internal class SnapDragState(initial: Float) {
    private val anim = Animatable(initial)
    var stops: List<Float> = listOf(initial)

    val value: Float get() = anim.value

    fun nearest(): Float = stops.minBy { abs(it - anim.value) }

    suspend fun dragBy(delta: Float) {
        anim.snapTo((anim.value + delta).coerceIn(stops.first(), stops.last()))
    }

    suspend fun settle(velocity: Float) {
        val projected = anim.value + velocity * 0.16f
        animateTo(stops.minBy { abs(it - projected) }, velocity)
    }

    suspend fun animateTo(stop: Float, velocity: Float = 0f) {
        anim.animateTo(
            targetValue = stop,
            animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
            initialVelocity = velocity,
        )
    }
}

/** [growsDown] is true when a downward drag makes the value larger. */
@Composable
internal fun Modifier.snapDrag(state: SnapDragState, growsDown: Boolean): Modifier {
    val scope = rememberCoroutineScope()
    val sign = if (growsDown) 1f else -1f
    return draggable(
        state = rememberDraggableState { delta -> scope.launch { state.dragBy(sign * delta) } },
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> state.settle(sign * velocity) },
    )
}

// ── The switcher ────────────────────────────────────────────────────────────

// Paper and ink that belong to neither theme, so the switcher never reads as part of a variant.
private val SwitcherPaper = Color(0xFFF6F2EA)
private val SwitcherPaperOff = Color(0xFFD8D3C9)
private val SwitcherInk = Color(0xFF1A1D1E)

@Composable
private fun PrototypeSwitcher(
    scenario: CallScenario,
    chipsShown: Boolean,
    onToggleChips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val variants = CallVariant.entries
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = SwitcherPaper,
            contentColor = SwitcherInk,
            shadowElevation = 6.dp,
            border = BorderStroke(1.5.dp, FireOrange),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        scenario.variant = variants[(scenario.variant.ordinal + variants.size - 1) % variants.size]
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous variant")
                }
                Text(
                    text = "${scenario.variant.key} · ${scenario.variant.title}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClickLabel = "Show or hide the scenario row", onClick = onToggleChips)
                        .widthIn(min = 104.dp)
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
                IconButton(
                    onClick = { scenario.variant = variants[(scenario.variant.ordinal + 1) % variants.size] },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next variant")
                }
            }
        }
        if (chipsShown) {
            Spacer(Modifier.height(6.dp))
            ScenarioChips(scenario)
        }
    }
}

/** Each chip prints one piece of the scenario and changes it on a tap. */
@Composable
private fun ScenarioChips(scenario: CallScenario) {
    val phases = CallPhase.entries
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        ScenarioChip(scenario.phase.label, on = true) {
            scenario.go(phases[(scenario.phase.ordinal + 1) % phases.size])
        }
        ScenarioChip("${scenario.people} people", on = true) { scenario.cyclePeople() }
        ScenarioChip(if (scenario.startedAsVideo) "Started as video" else "Started as voice", scenario.startedAsVideo) {
            scenario.startedAsVideo = !scenario.startedAsVideo
        }
        ScenarioChip("My camera ${onOff(scenario.myCamera)}", scenario.myCamera) {
            scenario.myCamera = !scenario.myCamera
        }
        ScenarioChip("Their camera ${scenario.remoteCameras.key}", scenario.remoteCameras != RemoteCameras.OFF) {
            scenario.cycleRemoteCameras()
        }
        ScenarioChip("Their mic ${onOff(scenario.theirMic)}", scenario.theirMic) {
            scenario.theirMic = !scenario.theirMic
        }
        ScenarioChip(if (scenario.weakNetwork) "Weak network" else "Good network", !scenario.weakNetwork) {
            scenario.weakNetwork = !scenario.weakNetwork
        }
    }
}

private fun onOff(on: Boolean) = if (on) "on" else "off"

@Composable
private fun ScenarioChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = SwitcherInk,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) SwitcherPaper else SwitcherPaperOff)
            .border(1.dp, if (on) FireOrange else SwitcherInk.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

// ── Root ────────────────────────────────────────────────────────────────────

@Composable
internal fun VideoCallPrototype(
    launch: PrototypeLaunch,
    appDark: Boolean,
    inPip: Boolean,
    host: PrototypeHost,
) {
    val scenario = rememberSaveable(launch, saver = CallScenario.saver(launch)) { CallScenario(launch) }
    val clock = rememberPrototypeClock()
    var chipsShown by rememberSaveable { mutableStateOf(true) }
    PhaseDriver(scenario, launch.autoAdvance)

    // C has no picture-in-picture: its small form is the strip over the chat.
    val pipEligible = scenario.variant != CallVariant.DOCKED && scenario.showsVideo
    LaunchedEffect(pipEligible) { host.setPipEligible(pipEligible) }

    val showSwitcher = !launch.bare && !inPip
    val clearance = when {
        !showSwitcher -> 0.dp
        chipsShown -> 100.dp
        else -> 58.dp
    }

    FireStreamTheme(darkTheme = scenario.variant == CallVariant.STAGE || (launch.forceDark ?: appDark)) {
        CompositionLocalProvider(LocalCallClock provides clock, LocalTopClearance provides clearance) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (inPip) {
                    PipContent(scenario)
                } else {
                    when (scenario.variant) {
                        CallVariant.STAGE -> VariantAStage(scenario, host)
                        CallVariant.SPLIT -> VariantBSplit(scenario, host)
                        CallVariant.DOCKED -> VariantCDocked(scenario, host)
                    }
                }
                if (showSwitcher) {
                    PrototypeSwitcher(
                        scenario = scenario,
                        chipsShown = chipsShown,
                        onToggleChips = { chipsShown = !chipsShown },
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                    )
                }
            }
        }
    }
}

/** What the real service would do by itself: connect after a moment, and let audio follow video. */
@Composable
private fun PhaseDriver(scenario: CallScenario, autoAdvance: Boolean) {
    var ringOnce by rememberSaveable { mutableStateOf(autoAdvance) }
    LaunchedEffect(scenario.phase) {
        when (scenario.phase) {
            CallPhase.CONNECTING -> {
                delay(1400)
                scenario.go(CallPhase.CONNECTED)
            }
            CallPhase.OUTGOING -> if (ringOnce) {
                delay(3800)
                ringOnce = false
                scenario.go(CallPhase.CONNECTING)
            }
            else -> Unit
        }
    }

    val ringing = scenario.phase != CallPhase.CONNECTED && scenario.phase != CallPhase.ENDED
    val wantsSpeaker = scenario.showsVideo || (ringing && scenario.startedAsVideo)
    LaunchedEffect(wantsSpeaker) {
        if (!scenario.routePicked) {
            scenario.audioRoute = if (wantsSpeaker) CallAudioRoute.SPEAKER else CallAudioRoute.EARPIECE
        }
    }
}

/** Inside the small window only the remote tile is drawn, the active speaker in a group. */
@Composable
private fun PipContent(scenario: CallScenario) {
    val clock = LocalCallClock.current
    val speaker by remember(scenario) {
        derivedStateOf { activeRemoteAt(clock.floatValue, scenario.people) }
    }
    val person = scenario.remotes.firstOrNull { it.index == speaker } ?: scenario.remotes.first()
    ParticipantTile(
        person = person,
        scenario = scenario,
        modifier = Modifier.fillMaxSize(),
        showName = false,
        ring = false,
        avatarSize = 56.dp,
    )
}
