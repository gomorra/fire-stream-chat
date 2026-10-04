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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.data.call.CallVideoSinks
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
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
 * @param placing an outgoing call that has no call document yet, or that could not be created.
 * @param locked the phone is locked. An incoming video call then offers only *Answer*.
 * @param inPictureInPicture the stage is the small window. Only the other person is drawn.
 */
@Immutable
internal data class CallStageState(
    val call: CallState = CallState.Idle,
    val controls: CallUiControls = CallUiControls(),
    val participants: List<CallParticipant> = emptyList(),
    val placing: PlacingCall? = null,
    val locked: Boolean = false,
    val inPictureInPicture: Boolean = false,
) {
    val remote: CallParticipant? get() = participants.firstOrNull()

    val selfVideo: Boolean get() = controls.selfVideo

    val showsVideo: Boolean get() = showsVideo(controls, participants)

    val person: StagePerson?
        get() = when (call) {
            is CallState.OutgoingRinging ->
                StagePerson(call.calleeName, call.calleeAvatarUrl, call.calleeLocalAvatarPath)
            is CallState.IncomingRinging ->
                StagePerson(call.callerName, call.callerAvatarUrl, call.callerLocalAvatarPath)
            is CallState.Connecting ->
                StagePerson(call.remoteName, call.remoteAvatarUrl, call.remoteLocalAvatarPath)
            is CallState.Connected ->
                StagePerson(call.remoteName, call.remoteAvatarUrl, call.remoteLocalAvatarPath)
            else -> placing?.let { StagePerson(it.calleeName, it.calleeAvatarUrl) }
                ?: remote?.let { StagePerson(it.name, it.avatarUrl, it.localAvatarPath) }
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
    val placing by viewModel.placing.collectAsState()

    // The state of the call before stays `Ended` until the next one starts. Only an end this
    // screen watched closes it, or it would close under a call that is about to be placed.
    var sawCall by rememberSaveable { mutableStateOf(false) }
    if (callState is CallState.Live) sawCall = true
    val ended = callState is CallState.Ended && sawCall && placing == null
    val failed = placing?.failed == true
    LaunchedEffect(ended, failed) {
        if (ended || failed) {
            delay(CLOSE_AFTER_END_MILLIS)
            if (failed) viewModel.cancelPlacing()
            onFinish()
        }
    }

    val minimisable = callState is CallState.Live && callState !is CallState.IncomingRinging
    BackHandler(enabled = minimisable, onBack = onMinimise)

    val callbacks = remember(viewModel, onAnswer, onSetCamera, onMinimise, onFinish) {
        CallScreenCallbacks(
            onAnswer = onAnswer,
            onDecline = viewModel::decline,
            onHangup = { if (viewModel.hangup()) onFinish() },
            onToggleMute = viewModel::toggleMute,
            onSetCamera = onSetCamera,
            onFlipCamera = viewModel::flipCamera,
            onSelectRoute = viewModel::selectAudioRoute,
            onMinimise = onMinimise,
        )
    }

    val state = remember(callState, uiControls, participants, placing, locked, inPictureInPicture) {
        CallStageState(callState, uiControls, participants, placing, locked, inPictureInPicture)
    }
    CallStage(state, callbacks) { participantId ->
        // A video view is single-use: a tile that comes back gets a new one.
        key(participantId) {
            AndroidView(
                factory = { context -> viewModel.createVideoView(context, participantId) },
                onRelease = viewModel::releaseVideoView,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
    val placing = state.placing
    val person = state.person
    Box(modifier.fillMaxSize().background(StageColors.Black)) {
        when {
            person == null -> if (call is CallState.Ended) EndedScene(null, "Call ended")
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
            placing != null && placing.failed -> EndedScene(person, "Call failed")
            // No call exists yet, so there is nothing to switch and nothing to minimise.
            placing != null -> RingScene(state, person, "Calling…", onMinimise = null, videoTile = videoTile) {
                StageDock(state.controls, callbacks, hangUpOnly = true)
            }
            call is CallState.Ended -> EndedScene(person, "Call ended")
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
    Box(Modifier.fillMaxSize()) {
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
private fun EndedScene(person: StagePerson?, status: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StageGlow()
        if (person != null) {
            StageIdentity(person, { status })
        } else {
            Text(text = status, style = MaterialTheme.typography.headlineSmall, color = StageColors.DimText)
        }
    }
}

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
            },
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
 * The other person's video, with [cover] over a glow until their camera is on and its first
 * frame has arrived. The view is there from the moment the camera is on, so the frame has
 * somewhere to land.
 */
@Composable
private fun RemoteTile(
    remote: CallParticipant?,
    videoTile: @Composable (String) -> Unit,
    cover: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (remote != null && remote.cameraOn) videoTile(remote.id)
        if (remote?.showsVideo != true) {
            StageGlow()
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

/** The call's running time as text. The returned function reads state, so only its caller follows the clock. */
@Composable
private fun rememberElapsedText(startTime: Long): () -> String {
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
