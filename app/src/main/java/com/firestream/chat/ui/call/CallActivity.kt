package com.firestream.chat.ui.call

import android.Manifest
import android.app.ActivityOptions
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
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.dockable
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The stage of a call, full screen. It owns what needs a permission or the window: the microphone
 * and camera requests, the lock state, picture-in-picture, and handing the call over to its chat,
 * where the main activity draws it as a docked card. While it is started it reports itself as
 * [CallSurface.STAGE]. The service never asks for a permission.
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

        /** The intent that brings the stage of the running call to the front. */
        fun stageIntent(context: Context): Intent = Intent(context, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

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

    /** Any video is on screen. Leaving the app from the stage then goes into picture-in-picture. */
    private var videoShowing = false

    /** The stage is handing the call over to its chat. A docked call has no picture-in-picture. */
    private var docking = false

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
        inPictureInPicture = isInPictureInPictureMode

        if (savedInstanceState == null) {
            handleIntent()
        }

        lifecycleScope.launch {
            // A call that ends while it is docked, or otherwise off the stage, closes the stage
            // where it is. Only an end this activity watched: the state of the call before stays
            // `Ended` until the next one starts. On the stage, CallScreen says so first and closes.
            var watched = false
            callStateHolder.callState.collect { state ->
                if (state is CallState.Live) {
                    watched = true
                } else if (state is CallState.Ended && watched && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    finish()
                }
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
                applyPictureInPictureParams()
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

    override fun onStart() {
        super.onStart()
        locked = keyguard().isKeyguardLocked
        if (docking) {
            docking = false
            applyPictureInPictureParams()
        }
        callStateHolder.setSurfaceShowing(CallSurface.STAGE, true)
    }

    // The small window counts as on screen: it stops the activity only when it is closed.
    override fun onStop() {
        super.onStop()
        callStateHolder.setSurfaceShowing(CallSurface.STAGE, false)
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
        // The service starts the preview of a video call once the call exists and the stage shows it.
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

    // ── Leaving the stage ───────────────────────────────────────────────────

    /**
     * Leave the stage and keep the call: it docks over its chat. The arrow, the back button and a
     * swipe up all end here. A locked phone asks for the unlock first, and stays on the stage
     * when that is refused.
     */
    private fun minimise() {
        if (dockTarget() == null) return leaveWithoutDock()
        if (!keyguard().isKeyguardLocked) return dock()
        keyguard().requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                locked = false
                dock()
            }
        })
    }

    /** The chat and the person the running call docks to. Null for a ring that came in, and while the chat is not known. */
    private fun dockTarget(): Pair<String, String>? {
        if (!callStateHolder.callState.value.dockable) return null
        val chatId = callStateHolder.chatId.value ?: return null
        val remoteId = callStateHolder.participants.value.firstOrNull()?.id ?: return null
        return chatId to remoteId
    }

    /**
     * Open the call's chat in the main activity, which draws the call as a card, and move the
     * stage's own task to the back. The hand-over is a short slide and fade.
     */
    private fun dock() {
        // Read again: the call may have ended while the phone was being unlocked.
        val (chatId, remoteId) = dockTarget() ?: return
        docking = true
        applyPictureInPictureParams()
        val openChat = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            putExtra(MainActivity.EXTRA_SENDER_ID, remoteId)
            // The chat is usually still open under the stage. It keeps its place in the thread.
            putExtra(MainActivity.EXTRA_KEEP_PLACE, true)
        }
        // The stage gives the call up now, so the chat draws the card as it comes in and not
        // only once this activity has stopped. The camera keeps running through the gap.
        callStateHolder.setSurfaceShowing(CallSurface.STAGE, false)
        val handOver = ActivityOptions.makeCustomAnimation(this, R.anim.call_dock_chat_in, R.anim.call_dock_stage_out)
        startActivity(openChat, handOver.toBundle())
        moveTaskToBack(true)
    }

    /** A call whose chat is not known cannot dock. It leaves into the small window while video shows, and into the background otherwise. */
    private fun leaveWithoutDock() {
        if (videoShowing && supportsPictureInPicture()) {
            enterPictureInPictureMode(pictureInPictureParams())
        } else {
            moveTaskToBack(true)
        }
    }

    private fun keyguard() = getSystemService(KeyguardManager::class.java)

    private fun supportsPictureInPicture() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun applyPictureInPictureParams() {
        if (supportsPictureInPicture()) setPictureInPictureParams(pictureInPictureParams())
    }

    /** The home gesture goes into the small window while video shows. Docking does not. */
    private fun pictureInPictureParams() = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(9, 16))
        .setAutoEnterEnabled(videoShowing && !docking)
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
        Toast.makeText(this, CAMERA_REFUSED_MESSAGE, Toast.LENGTH_LONG).show()
    }
}

/** What the stage and the docked card say, once each, when the camera permission is refused. */
internal const val CAMERA_REFUSED_MESSAGE = "Without the camera permission the call goes on with the camera off"
