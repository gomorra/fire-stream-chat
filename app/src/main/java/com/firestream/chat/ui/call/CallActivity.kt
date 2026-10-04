package com.firestream.chat.ui.call

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The stage of a call, full screen. It owns what needs a permission or the window: the microphone
 * and camera requests, the lock state, picture-in-picture, and telling the service whether the
 * call is on screen. The service never asks for a permission.
 */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {

    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var callPlacer: OutgoingCallPlacer

    companion object {
        const val EXTRA_ACTION = "call_action"
        const val EXTRA_CALLEE_ID = "callee_id"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALLEE_AVATAR_URL = "callee_avatar_url"
        const val EXTRA_CHAT_ID = "chat_id"
        /** With [ACTION_OUTGOING]: start the call as a video call. Absent means a voice call. */
        const val EXTRA_VIDEO = "video"
        const val ACTION_OUTGOING = "outgoing"
        const val ACTION_ANSWER = "answer"

        private const val STATE_PREPARED_CALL = "prepared_call"

        /**
         * The intent that places a call to [calleeId].
         *
         * @param video how the call is started: the camera icon, or the phone icon. Whether the
         *   call can carry video at all is decided when it is created, not here.
         */
        fun outgoingIntent(
            context: Context,
            calleeId: String,
            calleeName: String,
            calleeAvatarUrl: String?,
            chatId: String,
            video: Boolean
        ): Intent = Intent(context, CallActivity::class.java).apply {
            putExtra(EXTRA_ACTION, ACTION_OUTGOING)
            putExtra(EXTRA_CALLEE_ID, calleeId)
            putExtra(EXTRA_CALLEE_NAME, calleeName)
            putExtra(EXTRA_CALLEE_AVATAR_URL, calleeAvatarUrl)
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_VIDEO, video)
        }
    }

    private var locked by mutableStateOf(false)
    private var inPictureInPicture by mutableStateOf(false)

    /** Any video is on screen. Leaving the stage then goes into picture-in-picture. */
    private var videoShowing = false

    /** The call this activity has already reported to and started the preview for. */
    private var preparedCallId: String? = null

    private var cameraRefusalExplained = false

    // What to do once the permission dialog has been answered, whatever the answer.
    private var afterPermissions: (() -> Unit)? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        afterPermissions?.invoke()
        afterPermissions = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stage is dark whatever the app theme, so the bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        preparedCallId = savedInstanceState?.getString(STATE_PREPARED_CALL)
        inPictureInPicture = isInPictureInPictureMode

        if (savedInstanceState == null) {
            handleIntent()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callStateHolder.callState.collect(::prepareCall)
            }
        }
        lifecycleScope.launch {
            combine(
                callStateHolder.callState,
                callStateHolder.uiControls,
                callStateHolder.participants
            ) { call, controls, participants ->
                call is CallState.Live && showsVideo(controls, participants)
            }.distinctUntilChanged().collect { showing ->
                videoShowing = showing
                if (supportsPictureInPicture()) setPictureInPictureParams(pictureInPictureParams())
            }
        }

        setContent {
            FireStreamTheme(darkTheme = true) {
                CallScreen(
                    locked = locked,
                    inPictureInPicture = inPictureInPicture,
                    onAnswer = ::answer,
                    onSetCamera = ::setCamera,
                    onMinimise = ::minimise,
                    onFinish = ::finish
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PREPARED_CALL, preparedCallId)
    }

    override fun onStart() {
        super.onStart()
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        reportVisible(true)
    }

    // The small window counts as on screen: it stops the activity only when it is closed.
    override fun onStop() {
        super.onStop()
        reportVisible(false)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    private fun handleIntent() {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_OUTGOING -> startOutgoingCall()
            // From the notification or the lock screen: the camera stays off.
            ACTION_ANSWER -> answer(withVideo = false)
            // Opened from a notification that outlived its call.
            else -> finishIfNoCall()
        }
    }

    // ── Starting and answering ──────────────────────────────────────────────

    private fun startOutgoingCall() {
        // A call is already running. The stage shows that one.
        if (callStateHolder.callState.value is CallState.Live) return
        val request = PlacingCall(
            calleeId = intent.getStringExtra(EXTRA_CALLEE_ID) ?: return finishIfNoCall(),
            calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Unknown",
            calleeAvatarUrl = intent.getStringExtra(EXTRA_CALLEE_AVATAR_URL),
            chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return finishIfNoCall(),
            video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        )
        // The camera comes on in prepareCall, once the call exists.
        withCallPermissions(video = request.video) { callPlacer.place(request) }
    }

    /** [withVideo] asks for the camera if needed. A refusal answers with the camera off. */
    private fun answer(withVideo: Boolean) {
        withCallPermissions(video = withVideo) { cameraOn ->
            // Before the answer, so a preview that ran during the ring goes off for "voice only".
            CallService.sendSetCamera(this, cameraOn)
            CallService.sendAction(this, CallService.ACTION_ANSWER)
        }
    }

    private fun setCamera(on: Boolean) {
        if (!on) return CallService.sendSetCamera(this, false)
        withPermissions(camera = true, microphone = false) {
            if (hasCamera()) CallService.sendSetCamera(this, true) else explainCameraRefusal()
        }
    }

    /**
     * What starting and answering a call share: no call without the microphone, and a call with
     * [video] goes on with the camera off when the camera is refused. [then] is told whether the
     * camera may come on.
     */
    private fun withCallPermissions(video: Boolean, then: (cameraOn: Boolean) -> Unit) {
        withPermissions(camera = video) {
            if (!hasMicrophone()) return@withPermissions refuseWithoutMicrophone()
            if (video && !hasCamera()) explainCameraRefusal()
            then(video && hasCamera())
        }
    }

    /**
     * A call this activity has not seen yet: tell it that it is on screen, and start the preview
     * of a call that was started as video. The ring never asks for the camera, and a locked
     * phone shows no preview.
     */
    private fun prepareCall(state: CallState) {
        val call = state as? CallState.Live ?: return
        if (call.callId == preparedCallId) return
        preparedCallId = call.callId
        CallService.sendScreenVisible(this, true)
        val preview = call.video && hasCamera() && when (call) {
            is CallState.OutgoingRinging -> true
            is CallState.IncomingRinging -> !locked
            else -> false
        }
        if (preview) CallService.sendSetCamera(this, true)
    }

    private fun reportVisible(visible: Boolean) {
        // Without a call the service has nothing to tell, and would be started for nothing.
        if (callStateHolder.callState.value is CallState.Live) CallService.sendScreenVisible(this, visible)
    }

    // ── Leaving the stage ───────────────────────────────────────────────────

    /** Leave the stage and keep the call: the small window while video shows, the background otherwise. */
    private fun minimise() {
        if (videoShowing && supportsPictureInPicture()) {
            enterPictureInPictureMode(pictureInPictureParams())
        } else {
            moveTaskToBack(true)
        }
    }

    private fun supportsPictureInPicture() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pictureInPictureParams() = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(9, 16))
        .setAutoEnterEnabled(videoShowing)
        .build()

    private fun finishIfNoCall() {
        if (callStateHolder.callState.value !is CallState.Live && callPlacer.placing.value == null) finish()
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    /** Ask for what is missing, then run [then] whatever the answer. [then] checks what it got. */
    private fun withPermissions(camera: Boolean, microphone: Boolean = true, then: () -> Unit) {
        val missing = buildList {
            if (microphone && !hasMicrophone()) add(Manifest.permission.RECORD_AUDIO)
            if (camera && !hasCamera()) add(Manifest.permission.CAMERA)
        }
        if (missing.isEmpty()) return then()
        afterPermissions = then
        requestPermissions.launch(missing.toTypedArray())
    }

    private fun hasMicrophone() = isGranted(Manifest.permission.RECORD_AUDIO)

    private fun hasCamera() = isGranted(Manifest.permission.CAMERA)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refuseWithoutMicrophone() {
        Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
        finishIfNoCall()
    }

    /** Says once why the camera stays off. The call goes on without it. */
    private fun explainCameraRefusal() {
        if (cameraRefusalExplained) return
        cameraRefusalExplained = true
        Toast.makeText(this, "Without the camera permission the call goes on with the camera off", Toast.LENGTH_LONG)
            .show()
    }
}
