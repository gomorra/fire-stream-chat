// PROTOTYPE — throwaway. See VideoCallPrototype.kt.
//
// B · Split — nobody floats and nothing hides. Everyone gets an equal tile, the self view
// included: halves for two, one over two for three, two by two for four. A tap enlarges a
// tile. The controls are a bottom sheet that stays put; pulled up, it lists the audio routes
// and the people. Ringing is split the same way. Follows the app theme.
package com.firestream.chat.ui.call.prototype

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.ui.call.CallAudioRouteList
import com.firestream.chat.ui.call.CallControlButton
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

// Handle, one row of labelled controls, a little air.
private val SheetRest = 136.dp
private val TileGap = 8.dp
private val TileShape = RoundedCornerShape(24.dp)

@Composable
internal fun VariantBSplit(scenario: CallScenario, host: PrototypeHost) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val density = LocalDensity.current
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val restHeight = SheetRest + navBar
        // How far the sheet is pulled above its resting height.
        val pull = remember { SnapDragState(0f) }
        pull.stops = listOf(0f, with(density) { (maxHeight * 0.64f - restHeight).coerceAtLeast(160.dp).toPx() })

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(
                    top = LocalTopClearance.current + TileGap,
                    start = TileGap,
                    end = TileGap,
                    bottom = restHeight + TileGap,
                ),
        ) {
            if (scenario.phase == CallPhase.ENDED) SplitEnded(scenario) else SplitTiles(scenario)
        }
        SplitSheet(scenario, host, pull, restHeight)
    }
}

// ── Tiles ───────────────────────────────────────────────────────────────────

/**
 * Every tile keeps its identity and animates to its slot, so enlarging one reads as that
 * tile growing and the others stepping aside.
 */
@Composable
private fun SplitTiles(scenario: CallScenario) {
    var focusedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val people = scenario.everyone
    val focus = people.indexOfFirst { it.index == focusedIndex }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val gap = with(density) { TileGap.toPx() }
        val slots = splitSlots(
            count = people.size,
            focus = focus,
            w = constraints.maxWidth.toFloat(),
            h = constraints.maxHeight.toFloat(),
            gap = gap,
            strip = with(density) { 132.dp.toPx() },
        )
        val smallBelow = with(density) { 170.dp.toPx() }
        people.forEachIndexed { i, person ->
            key(person.index) {
                val rect by animateRectAsState(
                    targetValue = slots[i],
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                    label = "slot",
                )
                ParticipantTile(
                    person = person,
                    scenario = scenario,
                    shape = TileShape,
                    avatarSize = if (min(slots[i].width, slots[i].height) < smallBelow) 48.dp else 92.dp,
                    caption = scenario.ringCaption(person),
                    pulsing = scenario.rings(person),
                    modifier = Modifier
                        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .clip(TileShape)
                        .clickable { focusedIndex = if (focusedIndex == person.index) -1 else person.index },
                )
            }
        }
    }
}

/** Where each tile goes, in list order. [focus] is the enlarged tile's position, or -1 for equal tiles. */
private fun splitSlots(count: Int, focus: Int, w: Float, h: Float, gap: Float, strip: Float): List<Rect> {
    if (focus >= 0) {
        // One large tile, the others in a row along the bottom, kept to the right.
        val others = count - 1
        val each = min((w - gap * (others - 1)) / others, strip * 1.15f)
        val start = w - (each * others + gap * (others - 1))
        var next = 0
        return List(count) { i ->
            if (i == focus) {
                Rect(0f, 0f, w, h - strip - gap)
            } else {
                val left = start + next++ * (each + gap)
                Rect(left, h - strip, left + each, h)
            }
        }
    }
    val halfW = (w - gap) / 2f
    val halfH = (h - gap) / 2f
    return when (count) {
        2 -> listOf(Rect(0f, 0f, w, halfH), Rect(0f, halfH + gap, w, h))
        3 -> listOf(
            Rect(0f, 0f, w, halfH),
            Rect(0f, halfH + gap, halfW, h),
            Rect(halfW + gap, halfH + gap, w, h),
        )
        else -> listOf(
            Rect(0f, 0f, halfW, halfH),
            Rect(halfW + gap, 0f, w, halfH),
            Rect(0f, halfH + gap, halfW, h),
            Rect(halfW + gap, halfH + gap, w, h),
        )
    }
}

/** The avatar that pulses while the call rings: the people being called, or the caller. */
private fun CallScenario.rings(person: StandInPerson): Boolean = when (phase) {
    CallPhase.OUTGOING -> person.index != 0
    CallPhase.INCOMING -> person.index == 1
    else -> false
}

/** What a remote tile says under its avatar before the call is connected. */
private fun CallScenario.ringCaption(person: StandInPerson): String? = when {
    person.index == 0 -> null
    phase == CallPhase.OUTGOING -> "Ringing…"
    phase == CallPhase.CONNECTING -> "Connecting…"
    phase == CallPhase.INCOMING -> if (person.index == 1) statusText("") else "Also invited"
    else -> null
}

@Composable
private fun SplitEnded(scenario: CallScenario) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(TileShape)
            .background(tileSurface()),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
            scenario.remotes.forEach { StandInAvatar(it, 84.dp) }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = scenario.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (scenario.endedAfterSeconds > 0) {
                "Call ended · ${formatElapsed(scenario.endedAfterSeconds)}"
            } else {
                "Call ended"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── The sheet ───────────────────────────────────────────────────────────────

@Composable
private fun BoxWithConstraintsScope.SplitSheet(
    scenario: CallScenario,
    host: PrototypeHost,
    pull: SnapDragState,
    restHeight: Dp,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val open = (pull.value / pull.stops.last()).coerceIn(0f, 1f)
    val toggle: () -> Unit = {
        scope.launch { pull.animateTo(if (pull.nearest() > 0f) 0f else pull.stops.last()) }
    }

    if (open > 0.01f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = open * 0.5f }
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { scope.launch { pull.animateTo(0f) } } },
        )
    }
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 16.dp,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(restHeight + with(density) { pull.value.toDp() })
            .snapDrag(pull, growsDown = false),
    ) {
        Column(Modifier.fillMaxSize()) {
            SheetHandle(onClick = toggle)
            when (scenario.phase) {
                CallPhase.INCOMING -> SplitAnswerRow(scenario)
                CallPhase.ENDED -> SplitEndedRow(scenario, host)
                else -> SplitControls(scenario, moreOpen = open > 0.5f, onMore = toggle)
            }
            SplitDetails(
                scenario = scenario,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = open },
            )
        }
    }
}

@Composable
private fun SheetHandle(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable(onClickLabel = "Show or hide call details", onClick = onClick),
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

/** Mic, camera, hang up, flip, more. Flip stays in place and goes dim while the camera is off. */
@Composable
private fun SplitControls(scenario: CallScenario, moreOpen: Boolean, onMore: () -> Unit) {
    SheetRow {
        SheetControl(
            icon = if (scenario.myMic) Icons.Default.Mic else Icons.Default.MicOff,
            label = if (scenario.myMic) "Mute" else "Unmute",
            lit = !scenario.myMic,
            onClick = { scenario.myMic = !scenario.myMic },
        )
        SheetControl(
            icon = if (scenario.myCamera) Icons.Default.Videocam else Icons.Default.VideocamOff,
            label = "Camera",
            lit = scenario.myCamera,
            onClick = { scenario.myCamera = !scenario.myCamera },
        )
        SheetControl(Icons.Default.CallEnd, "End", fill = HangUpRed, onClick = scenario::hangUp)
        SheetControl(
            icon = Icons.Default.Cameraswitch,
            label = "Flip",
            enabled = scenario.myCamera,
            onClick = { scenario.frontCamera = !scenario.frontCamera },
        )
        SheetControl(Icons.Default.MoreHoriz, "More", lit = moreOpen, onClick = onMore)
    }
}

/** An incoming video call offers both answers at once. A voice call offers one. */
@Composable
private fun SplitAnswerRow(scenario: CallScenario) {
    SheetRow {
        SheetControl(Icons.Default.CallEnd, "Decline", fill = HangUpRed, onClick = scenario::hangUp)
        if (scenario.startedAsVideo) {
            SheetControl(Icons.Default.Call, "Voice only", onClick = { scenario.answer(withVideo = false) })
            SheetControl(
                icon = Icons.Default.Videocam,
                label = "With video",
                fill = AnswerGreen,
                onClick = { scenario.answer(withVideo = true) },
            )
        } else {
            SheetControl(
                icon = Icons.Default.Call,
                label = "Answer",
                fill = AnswerGreen,
                onClick = { scenario.answer(withVideo = false) },
            )
        }
    }
}

@Composable
private fun SplitEndedRow(scenario: CallScenario, host: PrototypeHost) {
    SheetRow {
        SheetControl(Icons.Default.Close, "Close", onClick = host.close)
        SheetControl(Icons.Default.Call, "Call again", fill = AnswerGreen, onClick = scenario::callAgain)
    }
}

@Composable
private fun SheetRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        content()
    }
}

/** [fill] makes the button the coloured one in its row: red to end, green to answer. */
@Composable
private fun SheetControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    lit: Boolean = false,
    enabled: Boolean = true,
    fill: Color? = null,
) {
    val background = fill ?: if (lit) MaterialTheme.colorScheme.secondary else quietControl()
    val tint = when {
        fill != null -> Color.White
        lit -> MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(68.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(64.dp)) {
            CallControlButton(
                icon = icon,
                contentDescription = label,
                onClick = { if (enabled) onClick() },
                backgroundColor = background,
                iconTint = tint,
                size = if (fill != null) 64.dp else 56.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** What the pulled-up sheet adds: where the sound goes, and who is here. */
@Composable
private fun SplitDetails(scenario: CallScenario, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        SheetHeading("Audio")
        CallAudioRouteList(
            current = scenario.audioRoute,
            available = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER, CallAudioRoute.BLUETOOTH),
            onSelect = scenario::pickRoute,
        )
        Spacer(Modifier.height(12.dp))
        SheetHeading("People · ${scenario.people}")
        scenario.everyone.forEach { person -> PersonRow(person, scenario) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PersonRow(person: StandInPerson, scenario: CallScenario) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
    ) {
        SpeakingAvatar(person, scenario, 40.dp)
        Spacer(Modifier.width(16.dp))
        Text(person.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (scenario.micOn(person)) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = if (scenario.micOn(person)) "Microphone on" else "Muted",
            tint = if (scenario.micOn(person)) quiet else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Icon(
            imageVector = if (scenario.cameraOn(person)) Icons.Default.Videocam else Icons.Default.VideocamOff,
            contentDescription = if (scenario.cameraOn(person)) "Camera on" else "Camera off",
            tint = quiet.copy(alpha = if (scenario.cameraOn(person)) 1f else 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}
