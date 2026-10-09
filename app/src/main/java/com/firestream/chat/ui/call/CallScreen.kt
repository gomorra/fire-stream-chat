package com.firestream.chat.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.data.call.CallVideoSinks
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.dockable
import kotlinx.coroutines.delay

/** The id the `videoTile` slot is asked for when it should draw the own camera. */
internal const val SELF_TILE_ID = CallVideoSinks.LOCAL

/** While any video shows, the dock and the top bar hide this long after the last touch. */
internal const val CHROME_HIDE_MILLIS = 4_000L

private const val CLOSE_AFTER_END_MILLIS = 1_500L

@Immutable
internal data class CallScreenCallbacks(
    val onAnswer: (withVideo: Boolean) -> Unit = {},
    val onDecline: () -> Unit = {},
    val onHangup: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onSetCamera: (on: Boolean) -> Unit = {},
    val onFlipCamera: () -> Unit = {},
    val onSelectRoute: (CallAudioRoute) -> Unit = {},
    val onMinimise: () -> Unit = {},
)

/**
 * Everything the stage draws from.
 *
 * @param locked the phone is locked. An incoming video call then offers only *Answer*.
 * @param inPictureInPicture the stage is the small window. Only the other person is drawn.
 */
@Immutable
internal data class CallStageState(
    val call: CallState = CallState.Idle,
    val controls: CallUiControls = CallUiControls(),
    val participants: List<CallParticipant> = emptyList(),
    val locked: Boolean = false,
    val inPictureInPicture: Boolean = false,
) {
    val remote: CallParticipant? get() = participants.firstOrNull()

    val selfVideo: Boolean get() = controls.selfVideo

    val showsVideo: Boolean get() = showsVideo(controls, participants)

    val person: StagePerson?
        get() = when (call) {
            // Not held by the call service yet, so the holder has nobody in the call.
            is CallState.Placing -> StagePerson(call.calleeName, call.calleeAvatarUrl)
            is CallState.OutgoingRinging ->
                StagePerson(call.calleeName, call.calleeAvatarUrl, call.calleeLocalAvatarPath)
            is CallState.IncomingRinging ->
                StagePerson(call.callerName, call.callerAvatarUrl, call.callerLocalAvatarPath)
            is CallState.Connecting ->
                StagePerson(call.remoteName, call.remoteAvatarUrl, call.remoteLocalAvatarPath)
            is CallState.Connected ->
                StagePerson(call.remoteName, call.remoteAvatarUrl, call.remoteLocalAvatarPath)
            // An ended call still names who it was with.
            else -> remote?.let { StagePerson(it.name, it.avatarUrl, it.localAvatarPath) }
        }
}

/** Their camera is on and a frame of it has arrived. Until then the avatar shows. */
internal val CallParticipant.showsVideo: Boolean get() = cameraOn && hasFrame

/** The own camera is running, so the self view has something to draw. */
internal val CallUiControls.selfVideo: Boolean get() = cameraOn && !cameraPaused

/** Any video is on screen: the own camera runs, or somebody else's picture has arrived. */
internal fun showsVideo(controls: CallUiControls, participants: List<CallParticipant>): Boolean =
    controls.selfVideo || participants.any { it.showsVideo }

/**
 * The call screen of [CallActivity]. The activity owns what needs a permission or the window:
 * answering, the camera switch and leaving the stage.
 */
@Composable
internal fun CallScreen(
    locked: Boolean,
    inPictureInPicture: Boolean,
    onAnswer: (withVideo: Boolean) -> Unit,
    onSetCamera: (on: Boolean) -> Unit,
    onMinimise: () -> Unit,
    onFinish: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val uiControls by viewModel.uiControls.collectAsState()
    val participants by viewModel.participants.collectAsState()

    // The end shows for a moment, then the screen closes. A call that could not be placed ends
    // the same way. The activity clears the end of the call before, ahead of placing the next
    // one, so an end seen here is the end of the call this screen shows.
    val ended = callState is CallState.Ended
    LaunchedEffect(ended) {
        if (ended) {
            delay(CLOSE_AFTER_END_MILLIS)
            onFinish()
        }
    }

    BackHandler(enabled = callState.dockable, onBack = onMinimise)

    val callbacks = remember(viewModel, onAnswer, onSetCamera, onMinimise) {
        CallScreenCallbacks(
            onAnswer = onAnswer,
            onDecline = viewModel::decline,
            onHangup = viewModel::hangup,
            onToggleMute = viewModel::toggleMute,
            onSetCamera = onSetCamera,
            onFlipCamera = viewModel::flipCamera,
            onSelectRoute = viewModel::selectAudioRoute,
            onMinimise = onMinimise,
        )
    }

    val state = remember(callState, uiControls, participants, locked, inPictureInPicture) {
        CallStageState(callState, uiControls, participants, locked, inPictureInPicture)
    }
    CallStage(state, callbacks) { participantId -> CallVideoView(viewModel, participantId) }
}

/** The video of a participant, or of the own camera for [SELF_TILE_ID], as a view of this activity. */
@Composable
internal fun CallVideoView(viewModel: CallViewModel, participantId: String) {
    // A video view is single-use: a tile that comes back gets a new one.
    key(participantId) {
        AndroidView(
            factory = { context -> viewModel.createVideoView(context, participantId) },
            onRelease = viewModel::releaseVideoView,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The stage: one screen for every call, always dark. A voice call is a call with both cameras off.
 *
 * @param videoTile draws the video of a participant, or of the own camera for [SELF_TILE_ID]. It
 *   is asked only while that camera is on.
 */
@Composable
internal fun CallStage(
    state: CallStageState,
    callbacks: CallScreenCallbacks,
    modifier: Modifier = Modifier,
    videoTile: @Composable (participantId: String) -> Unit,
) {
    val call = state.call
    val person = state.person
    Box(modifier.fillMaxSize().background(StageColors.Black)) {
        when {
            // A placing that failed ends with nobody in the call.
            call is CallState.Ended -> EndedScene(person)
            person == null -> Unit
            state.inPictureInPicture && call is CallState.Live ->
                RemoteTile(state.remote, videoTile) { StageAvatar(person, 72.dp, Modifier.align(Alignment.Center)) }
            call is CallState.IncomingRinging -> RingScene(
                state = state,
                person = person,
                status = if (call.video) "Incoming video call" else "Incoming voice call",
                onMinimise = null,
                videoTile = videoTile,
            ) {
                StageAnswerRow(call.video, state.locked, callbacks)
            }
            call is CallState.OutgoingRinging || call is CallState.Connecting -> RingScene(
                state = state,
                person = person,
                status = if (call is CallState.Connecting) "Connecting…" else "Calling…",
                onMinimise = callbacks.onMinimise,
                videoTile = videoTile,
            ) {
                StageDock(state.controls, callbacks, unavailableLine = unavailableLine(person))
            }
            call is CallState.Connected -> ConnectedScene(state, person, call.startTime, callbacks, videoTile)
            // The call service does not hold the call yet, so there is nothing to switch and
            // nothing to minimise. Looks like the ringing that follows it.
            call is CallState.Placing -> RingScene(state, person, "Calling…", onMinimise = null, videoTile = videoTile) {
                StageDock(state.controls, callbacks, hangUpOnly = true)
            }
        }
    }
}

private fun unavailableLine(person: StagePerson) = "${person.name} needs the latest app for video"

// ── Ringing, connecting, ended ──────────────────────────────────────────────

/** The own preview fills the stage behind the name while the own camera runs. Otherwise a glow. */
@Composable
private fun RingScene(
    state: CallStageState,
    person: StagePerson,
    status: String,
    onMinimise: (() -> Unit)?,
    videoTile: @Composable (String) -> Unit,
    bottom: @Composable () -> Unit,
) {
    // Whatever can be minimised can be docked. A ring that came in cannot.
    Box(if (onMinimise != null) Modifier.fillMaxSize().swipeUpToDock(onMinimise) else Modifier.fillMaxSize()) {
        if (state.selfVideo) {
            videoTile(SELF_TILE_ID)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
        } else {
            StageGlow()
        }
        if (onMinimise != null) {
            MinimiseButton(onMinimise, Modifier.statusBarsPadding().padding(start = 4.dp))
        }
        StageIdentity(
            person = person,
            status = { status },
            pulsing = state.call !is CallState.Connecting,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 88.dp),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
        ) {
            bottom()
        }
    }
}

@Composable
private fun EndedScene(person: StagePerson?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StageGlow()
        if (person != null) {
            StageIdentity(person, { CALL_ENDED })
        } else {
            Text(text = CALL_ENDED, style = MaterialTheme.typography.headlineSmall, color = StageColors.DimText)
        }
    }
}

private const val CALL_ENDED = "Call ended"

// ── Connected ───────────────────────────────────────────────────────────────

/**
 * The other person fills the stage and the self view floats above. A tap on the self view swaps
 * the two. While any video shows, the dock and the top bar hide after [CHROME_HIDE_MILLIS] and a
 * tap on the stage brings them back. A voice call keeps them.
 */
@Composable
private fun ConnectedScene(
    state: CallStageState,
    person: StagePerson,
    startTime: Long,
    callbacks: CallScreenCallbacks,
    videoTile: @Composable (String) -> Unit,
) {
    var swapped by rememberSaveable { mutableStateOf(false) }
    var chromeShown by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) }

    val canHide = state.showsVideo
    val currentCanHide by rememberUpdatedState(canHide)
    LaunchedEffect(touches, canHide, chromeShown) {
        if (!canHide) {
            chromeShown = true
        } else if (chromeShown) {
            delay(CHROME_HIDE_MILLIS)
            chromeShown = false
        }
    }

    val remote = state.remote
    val selfOnStage = swapped && state.selfVideo
    val videoOnStage = selfOnStage || remote?.showsVideo == true
    // Read only where the timer is drawn, so a second ticking does not recompose the scene.
    val elapsed = rememberElapsedText(startTime)
    val remoteMuted = remote?.micOn == false

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CallStageTags.STAGE)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (currentCanHide) chromeShown = !chromeShown
                    touches++
                }
            }
            // After the taps in the chain, so it sees a touch before they claim it.
            .swipeUpToDock(callbacks.onMinimise),
    ) {
        if (selfOnStage) {
            videoTile(SELF_TILE_ID)
        } else {
            RemoteTile(remote, videoTile) {
                StageIdentity(person, elapsed, Modifier.align(Alignment.Center), muted = remoteMuted)
            }
        }
        if (videoOnStage) StageScrims(chromeShown)
        StageTopBar(
            shown = chromeShown,
            title = person.name.takeIf { videoOnStage },
            subtitle = elapsed,
            muted = remoteMuted,
            onMinimise = callbacks.onMinimise,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        FloatingTile(visible = state.selfVideo, dockShown = chromeShown, onTap = { swapped = !swapped }) {
            if (selfOnStage) {
                RemoteTile(remote, videoTile) { StageAvatar(person, 48.dp, Modifier.align(Alignment.Center)) }
            } else {
                videoTile(SELF_TILE_ID)
            }
        }
        AnimatedVisibility(
            visible = chromeShown,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
        ) {
            StageDock(state.controls, callbacks, unavailableLine = unavailableLine(person), onTouch = { touches++ })
        }
    }
}

/**
 * The other person's video, with [cover] over [backdrop] until their camera is on and its first
 * frame has arrived. The view is there from the moment the camera is on, so the frame has
 * somewhere to land. [backdrop] is opaque: it hides a view that has no frame yet.
 */
@Composable
internal fun RemoteTile(
    remote: CallParticipant?,
    videoTile: @Composable (String) -> Unit,
    backdrop: @Composable () -> Unit = { StageGlow() },
    cover: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (remote != null && remote.cameraOn) videoTile(remote.id)
        if (remote?.showsVideo != true) {
            backdrop()
            cover()
        }
    }
}

/** Shade behind the top bar and the dock, so white holds on any video. */
@Composable
private fun BoxScope.StageScrims(shown: Boolean) {
    val strength by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "scrim")
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(180.dp)
            .graphicsLayer { alpha = strength }
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(220.dp)
            .graphicsLayer { alpha = strength }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))))
    )
}

private val SwipeToDockDistance = 72.dp

/**
 * A swipe up docks the call. It starts only where no child took the touch, so the self tile, the
 * dock and the arrow keep theirs. It does not start in the bottom gesture strip, where a swipe up
 * is Android's home gesture. A tap passes through untouched.
 */
@Composable
private fun Modifier.swipeUpToDock(onDock: () -> Unit): Modifier {
    val currentOnDock by rememberUpdatedState(onDock)
    val homeStrip by rememberUpdatedState(WindowInsets.systemGestures.getBottom(LocalDensity.current))
    return pointerInput(Unit) {
        val distance = SwipeToDockDistance.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            if (down.position.y > size.height - homeStrip) return@awaitEachGesture
            var travelled = 0f
            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                change.consume()
                travelled = overSlop
            } ?: return@awaitEachGesture
            val lifted = verticalDrag(drag.id) { change ->
                travelled += change.positionChange().y
                change.consume()
            }
            if (lifted && travelled <= -distance) currentOnDock()
        }
    }
}

/** The call's running time as text. The returned function reads state, so only its caller follows the clock. */
@Composable
internal fun rememberElapsedText(startTime: Long): () -> String {
    val seconds = remember(startTime) { mutableLongStateOf(elapsedSeconds(startTime)) }
    LaunchedEffect(startTime) {
        while (true) {
            seconds.longValue = elapsedSeconds(startTime)
            delay(1000)
        }
    }
    return remember(seconds) { { formatElapsed(seconds.longValue) } }
}

private fun elapsedSeconds(startTime: Long) = ((System.currentTimeMillis() - startTime) / 1000).coerceAtLeast(0)

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
