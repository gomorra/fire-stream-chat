// PROTOTYPE — throwaway. See VideoCallPrototype.kt.
//
// C · Docked over the chat — the call never takes the screen away. It is a card at the top of
// the chat, and the thread and the composer below stay usable. The card has three sizes by
// drag: a slim strip, the card, and full screen. A voice call rests as the strip and a call
// with video as the card. A group is one large active speaker and a row of the others. An
// incoming call is a card over the dimmed chat. Follows the app theme.
package com.firestream.chat.ui.call.prototype

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.ui.call.CallControlButton
import com.firestream.chat.ui.chat.BubbleTailShape
import com.firestream.chat.ui.theme.FsSurface2
import com.firestream.chat.ui.theme.LocalIsDarkTheme
import com.firestream.chat.ui.theme.OnlineGreen
import com.firestream.chat.ui.theme.ReceivedBubble
import com.firestream.chat.ui.theme.ReceivedBubbleDark
import com.firestream.chat.ui.theme.SentBubble
import com.firestream.chat.ui.theme.SentBubbleDark
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val ChatBarHeight = 56.dp
private val StripHeight = 72.dp

private data class StandInMessage(val id: Int, val own: Boolean, val text: String, val time: String)

private val StandInThread = listOf(
    StandInMessage(1, false, "Are you free for a quick call?", "18:02"),
    StandInMessage(2, true, "Give me two minutes", "18:02"),
    StandInMessage(3, false, "I'll send the floor plan while we talk", "18:03"),
    StandInMessage(4, false, "Second layout, the one with the door on the left", "18:04"),
    StandInMessage(5, true, "Got it. That one is better", "18:05"),
    StandInMessage(6, false, "Can you mark where the desk goes?", "18:05"),
    StandInMessage(7, true, "Marking it now", "18:06"),
)

@Composable
internal fun VariantCDocked(scenario: CallScenario, host: PrototypeHost) {
    val messages = remember { mutableStateListOf<StandInMessage>().apply { addAll(StandInThread) } }
    val incoming = scenario.phase == CallPhase.INCOMING

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(top = LocalTopClearance.current),
    ) {
        val density = LocalDensity.current
        val stripPx = with(density) { StripHeight.toPx() }
        val cardPx = with(density) { minOf(maxHeight * 0.46f, 372.dp).toPx() }
        val fullPx = constraints.maxHeight.toFloat()

        // The card's height. A voice call rests as the strip, a call with video as the card.
        val card = remember { SnapDragState(if (scenario.wantsCard()) cardPx else stripPx) }
        card.stops = listOf(stripPx, cardPx, fullPx)
        LaunchedEffect(card) {
            snapshotFlow { scenario.wantsCard() }
                .drop(1)
                .collect { wants -> card.animateTo(if (wants) card.stops[1] else card.stops[0]) }
        }

        // The thread follows the card down to its docked size. Past that the card grows over it.
        val docked = if (incoming) 0.dp else with(density) { minOf(card.value, cardPx).toDp() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            DockedChatBar(scenario, host)
            Spacer(Modifier.height(docked))
            DockedThread(messages, Modifier.weight(1f))
            DockedComposer(onSend = { text -> messages.add(StandInMessage(messages.size + 1, true, text, "now")) })
        }

        if (incoming) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { } },
            )
            DockedIncoming(scenario, Modifier.align(Alignment.TopCenter).padding(top = ChatBarHeight))
        } else {
            DockedCall(scenario, host, card, Modifier.align(Alignment.TopCenter))
        }
    }
}

private fun CallScenario.wantsCard(): Boolean = when (phase) {
    CallPhase.CONNECTED -> showsVideo
    CallPhase.ENDED -> false
    else -> myCamera
}

// ── The stand-in chat ───────────────────────────────────────────────────────

@Composable
private fun DockedChatBar(scenario: CallScenario, host: PrototypeHost) {
    val live = scenario.phase == CallPhase.CONNECTED
    val ink = MaterialTheme.colorScheme.onBackground
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ChatBarHeight)
            .padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = host.close) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ink)
        }
        StandInAvatar(scenario.remotes.first(), 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = scenario.title,
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (live) "In a call" else "Online",
                style = MaterialTheme.typography.labelSmall,
                color = if (live) MaterialTheme.colorScheme.primary else ink.copy(alpha = 0.8f),
            )
        }
        IconButton(onClick = {}) { Icon(Icons.Default.Search, contentDescription = "Search messages", tint = ink) }
        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = ink) }
    }
}

@Composable
private fun DockedThread(messages: List<StandInMessage>, modifier: Modifier = Modifier) {
    LazyColumn(
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(messages.asReversed(), key = { it.id }) { message -> StandInBubble(message) }
    }
}

@Composable
private fun StandInBubble(message: StandInMessage) {
    val dark = LocalIsDarkTheme.current
    val fill = when {
        message.own -> if (dark) SentBubbleDark else SentBubble
        else -> if (dark) ReceivedBubbleDark else ReceivedBubble
    }
    Row(
        horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(fill, BubbleTailShape(isOwnMessage = message.own))
                .padding(
                    start = if (message.own) 12.dp else 18.dp,
                    end = if (message.own) 18.dp else 12.dp,
                    top = 8.dp,
                    bottom = 10.dp,
                ),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = message.time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A real text field: the point of this variant is that the chat still works during the call. */
@Composable
private fun DockedComposer(onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val dark = LocalIsDarkTheme.current
    val fieldShape = RoundedCornerShape(24.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(fieldShape)
                .background(if (dark) FsSurface2 else MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f), fieldShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = "Message",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        CallControlButton(
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            onClick = {
                if (draft.isNotBlank()) {
                    onSend(draft.trim())
                    draft = ""
                }
            },
            backgroundColor = MaterialTheme.colorScheme.primary,
            iconTint = MaterialTheme.colorScheme.onPrimary,
            size = 48.dp,
        )
    }
}

// ── The call card ───────────────────────────────────────────────────────────

/**
 * One surface for all three sizes. It sits under the chat bar as a strip or a card, and
 * slides up over the bar as it grows to full screen.
 */
@Composable
private fun DockedCall(scenario: CallScenario, host: PrototypeHost, card: SnapDragState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val (stripPx, cardPx, fullPx) = card.stops
    val toFull = ((card.value - cardPx) / (fullPx - cardPx)).coerceIn(0f, 1f)
    val asStrip = card.value < (stripPx + cardPx) / 2f
    val margin = lerp(8.dp, 0.dp, toFull)

    Surface(
        shape = RoundedCornerShape(lerp(24.dp, 0.dp, toFull)),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        modifier = modifier
            .padding(top = lerp(ChatBarHeight, 0.dp, toFull))
            .fillMaxWidth()
            .height(with(density) { card.value.toDp() })
            .padding(horizontal = margin)
            .padding(bottom = margin)
            .snapDrag(card, growsDown = true),
    ) {
        Crossfade(targetState = asStrip, animationSpec = tween(160), label = "card") { strip ->
            if (strip) {
                DockedStrip(scenario, host, onExpand = { scope.launch { card.animateTo(cardPx) } })
            } else {
                DockedBody(
                    scenario = scenario,
                    toFull = toFull,
                    onResize = { scope.launch { card.animateTo(if (toFull > 0.5f) cardPx else fullPx) } },
                )
            }
        }
    }
}

/** The slim form: who, how long, mute and hang up. A tap or a pull opens the card. */
@Composable
private fun DockedStrip(scenario: CallScenario, host: PrototypeHost, onExpand: () -> Unit) {
    val ended = scenario.phase == CallPhase.ENDED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = !ended, onClickLabel = "Open the call", onClick = onExpand)
            .padding(start = 12.dp, end = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            scenario.remotes.forEach { SpeakingAvatar(it, scenario, 36.dp) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = scenario.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (scenario.phase == CallPhase.CONNECTED) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(OnlineGreen))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = if (ended && scenario.endedAfterSeconds > 0) {
                        "Call ended · ${formatElapsed(scenario.endedAfterSeconds)}"
                    } else {
                        scenario.statusText(rememberCallTimer(scenario))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ended) {
            CardButton(Icons.Default.Call, "Call again", fill = AnswerGreen, onClick = scenario::callAgain)
        } else {
            CardButton(
                icon = if (scenario.myCamera) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = if (scenario.myCamera) "Turn camera off" else "Turn camera on",
                lit = scenario.myCamera,
                onClick = { scenario.myCamera = !scenario.myCamera },
            )
            CardButton(
                icon = if (scenario.myMic) Icons.Default.Mic else Icons.Default.MicOff,
                label = if (scenario.myMic) "Mute" else "Unmute",
                lit = !scenario.myMic,
                onClick = { scenario.myMic = !scenario.myMic },
            )
            CardButton(Icons.Default.CallEnd, "Hang up", fill = HangUpRed, onClick = scenario::hangUp)
        }
    }
}

/** The card and the full screen: media, the other people, controls, a handle. */
@Composable
private fun DockedBody(scenario: CallScenario, toFull: Float, onResize: () -> Unit) {
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val clock = LocalCallClock.current
    val activeIndex by remember(scenario) {
        derivedStateOf { activeRemoteAt(clock.floatValue, scenario.people) }
    }
    val active = scenario.remotes.firstOrNull { it.index == activeIndex } ?: scenario.remotes.first()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = navBar * toFull),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (scenario.phase == CallPhase.CONNECTED) DockedMedia(scenario, active) else DockedRinging(scenario)
            MediaHeader(scenario, toFull, onResize)
        }
        if (scenario.phase == CallPhase.CONNECTED && scenario.people > 2) {
            Spacer(Modifier.height(6.dp))
            OthersRow(scenario, active, height = lerp(64.dp, 104.dp, toFull))
        }
        CardControls(scenario)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(14.dp)) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }
    }
}

private val MediaShape = RoundedCornerShape(18.dp)

/** Two people: the other fills the card and the self view sits in its corner. A group features whoever spoke last. */
@Composable
private fun DockedMedia(scenario: CallScenario, active: StandInPerson) {
    if (scenario.people == 2) {
        Box(Modifier.fillMaxSize()) {
            ParticipantTile(
                person = scenario.remotes.first(),
                scenario = scenario,
                shape = MediaShape,
                showName = false,
                ring = false,
                avatarSize = 76.dp,
                modifier = Modifier.fillMaxSize(),
            )
            if (scenario.myCamera) {
                ParticipantTile(
                    person = scenario.me,
                    scenario = scenario,
                    shape = RoundedCornerShape(12.dp),
                    showName = false,
                    ring = false,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(width = 64.dp, height = 88.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                )
            }
        }
    } else {
        Crossfade(targetState = active, animationSpec = tween(240), label = "speaker") { person ->
            ParticipantTile(
                person = person,
                scenario = scenario,
                shape = MediaShape,
                nameAlignment = Alignment.BottomStart,
                avatarSize = 76.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Everyone but the featured speaker, in a fixed order with the user last, so only one tile changes at a time. */
@Composable
private fun OthersRow(scenario: CallScenario, active: StandInPerson, height: Dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        scenario.everyone.filter { it != active }.forEach { person ->
            ParticipantTile(
                person = person,
                scenario = scenario,
                shape = RoundedCornerShape(14.dp),
                avatarSize = 32.dp,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun DockedRinging(scenario: CallScenario) {
    val callee = scenario.remotes.first()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(MediaShape)
            .background(tileSurface()),
    ) {
        // With the camera on, the card is the own preview and the header chip says who rings.
        if (scenario.myCamera) {
            StandInVideo(scenario.me, mirrored = scenario.frontCamera)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingAvatar(callee, 72.dp, pulsing = scenario.phase == CallPhase.OUTGOING)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = scenario.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Name and time over the media, and the button that toggles full screen. */
@Composable
private fun MediaHeader(scenario: CallScenario, toFull: Float, onResize: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = scenario.statusText(rememberCallTimer(scenario)),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Spacer(Modifier.weight(1f))
        CallControlButton(
            icon = if (toFull > 0.5f) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
            contentDescription = if (toFull > 0.5f) "Back to the card" else "Full screen",
            onClick = onResize,
            backgroundColor = Color.Black.copy(alpha = 0.42f),
            iconTint = Color.White,
            size = 40.dp,
        )
    }
}

@Composable
private fun CardControls(scenario: CallScenario) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    ) {
        CardButton(
            icon = if (scenario.myCamera) Icons.Default.Videocam else Icons.Default.VideocamOff,
            label = if (scenario.myCamera) "Turn camera off" else "Turn camera on",
            lit = scenario.myCamera,
            onClick = { scenario.myCamera = !scenario.myCamera },
        )
        AnimatedVisibility(
            visible = scenario.myCamera,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            CardButton(Icons.Default.Cameraswitch, "Flip camera", onClick = { scenario.frontCamera = !scenario.frontCamera })
        }
        CardButton(
            icon = if (scenario.myMic) Icons.Default.Mic else Icons.Default.MicOff,
            label = if (scenario.myMic) "Mute" else "Unmute",
            lit = !scenario.myMic,
            onClick = { scenario.myMic = !scenario.myMic },
        )
        CardButton(
            icon = Icons.Default.VolumeUp,
            label = "Speaker",
            lit = scenario.audioRoute == CallAudioRoute.SPEAKER,
            onClick = scenario::toggleSpeaker,
        )
        CardButton(Icons.Default.CallEnd, "Hang up", fill = HangUpRed, onClick = scenario::hangUp)
    }
}

/** 44 dp glyph in a 48 dp slot: compact enough for a card, still a full touch target. */
@Composable
private fun CardButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    lit: Boolean = false,
    fill: Color? = null,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 3.dp).size(48.dp)) {
        CallControlButton(
            icon = icon,
            contentDescription = label,
            onClick = onClick,
            backgroundColor = fill ?: if (lit) MaterialTheme.colorScheme.secondary else quietControl(),
            iconTint = when {
                fill != null -> Color.White
                lit -> MaterialTheme.colorScheme.onSecondary
                else -> MaterialTheme.colorScheme.onSurface
            },
            size = 44.dp,
        )
    }
}

// ── Incoming ────────────────────────────────────────────────────────────────

/** A card where the call will dock, over the dimmed chat. */
@Composable
private fun DockedIncoming(scenario: CallScenario, modifier: Modifier = Modifier) {
    val caller = scenario.remotes.first()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (LocalIsDarkTheme.current) FsSurface2 else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 12.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingAvatar(caller, 56.dp, pulsing = true)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = scenario.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (scenario.isGroup) "${caller.name} · ${scenario.statusText("")}" else scenario.statusText(""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnswerPill("Decline", Icons.Default.CallEnd, HangUpRed, Modifier.weight(1f), onClick = scenario::hangUp)
                AnswerPill(
                    text = "Answer",
                    icon = if (scenario.startedAsVideo) Icons.Default.Videocam else Icons.Default.Call,
                    fill = AnswerGreen,
                    modifier = Modifier.weight(1f),
                    onClick = { scenario.answer(withVideo = scenario.startedAsVideo) },
                )
            }
            if (scenario.startedAsVideo) {
                Text(
                    text = "Answer without video",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .clip(CircleShape)
                        .clickable { scenario.answer(withVideo = false) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun AnswerPill(text: String, icon: ImageVector, fill: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}
