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
import android.util.Log
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.dockable
import com.firestream.chat.domain.model.isOngoing
import com.firestream.chat.ui.theme.FireStreamTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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

    /** Outlives a rotation, so the call setup and a pending permission request are not lost to one. */
    private val viewModel: CallViewModel by viewModels()

    companion object {
        const val EXTRA_ACTION = "call_action"
        const val EXTRA_CALLEE_ID = "callee_id"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALLEE_AVATAR_URL = "callee_avatar_url"
        const val EXTRA_CHAT_ID = "chat_id"
        /** With [ACTION_OUTGOING] and [ACTION_RING]: the call was started as a video call. Absent means a voice call. */
        const val EXTRA_VIDEO = "video"
        const val ACTION_OUTGOING = "outgoing"
        const val ACTION_ANSWER = "answer"

        /**
         * Ring for an incoming call whose push could not start the call service. The fallback
         * notification opens the screen with it, and the extras below name the call.
         */
        const val ACTION_RING = "ring"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_ID = "caller_id"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_AVATAR_URL = "caller_avatar_url"

        private const val TAG = "CallActivity"

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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = viewModel.pendingPermissionAction
        viewModel.pendingPermissionAction = null
        action?.let(::runPermissionAction)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The stage is dark whatever the app theme, so the bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        inPictureInPicture = isInPictureInPictureMode

        // The stage opens for a call that is about to be placed or answered.
        viewModel.prepareCall()
        if (savedInstanceState == null) {
            handleIntent()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.setupFailed.collect {
                    Toast.makeText(this@CallActivity, "Couldn't start the call", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Collected while the screen is stopped too: CallScreen only closes a screen it shows, and
        // a call that ends while it is docked closes the stage where it is.
        // The first value is the state the screen opened on, which handleIntent has dealt with.
        lifecycleScope.launch {
            callStateHolder.callState.drop(1).collect { state ->
                if (closesUnseen(state, lifecycle.currentState)) finishAndRemoveTask()
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
                    // Out of Recents too: relaunching a finished call screen from there must not
                    // bring back the intent that placed or answered its call.
                    onFinish = { finishAndRemoveTask() },
                    viewModel = viewModel
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

    override fun onPause() {
        super.onPause()
        // Back or finishAndRemoveTask(). A setup still running must end its call, not start it.
        if (isFinishing) viewModel.onScreenClosed()
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
        val launchedFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val launch = callLaunchFor(
            action = intent.getStringExtra(EXTRA_ACTION),
            launchedFromHistory = launchedFromHistory,
            callOngoing = callStateHolder.callState.value.isOngoing
        )
        when (launch) {
            CallLaunch.PLACE_CALL -> placeOutgoingCall()
            // From the notification or the lock screen: the camera stays off.
            CallLaunch.ANSWER -> answer(withVideo = false)
            CallLaunch.RING -> ringIncomingCall()
            // Opened from a notification that outlived its call.
            CallLaunch.SHOW -> if (!callStateHolder.callState.value.isOngoing) finishAndRemoveTask()
            CallLaunch.CLOSE -> finishAndRemoveTask()
        }
    }

    // ── Starting and answering ──────────────────────────────────────────────

    /**
     * Runs before the permission prompt. The call screen composes behind the prompt, and the
     * previous call's Ended state would finish this activity underneath it.
     */
    private fun placeOutgoingCall() {
        if (!callStateHolder.prepareOutgoingCall()) {
            // A call is already running. This screen shows it instead of placing a second one.
            Toast.makeText(this, "You're already in a call", Toast.LENGTH_SHORT).show()
            return
        }
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        withPermissions(if (video) PermissionAction.PLACE_VIDEO_CALL else PermissionAction.PLACE_VOICE_CALL)
    }

    /** The session starts the preview of a video call once the call exists and the stage shows it. */
    private fun startOutgoingCall() {
        val calleeId = intent.getStringExtra(EXTRA_CALLEE_ID) ?: return finishAndRemoveTask()
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return finishAndRemoveTask()
        val calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Unknown"
        val calleeAvatarUrl = intent.getStringExtra(EXTRA_CALLEE_AVATAR_URL)
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        viewModel.placeCall(calleeId, chatId, calleeName, calleeAvatarUrl, video)
    }

    /**
     * Ring for the call the intent names. Android would not let its push start the call service,
     * so FCMService rang with a notification that opens this screen. From the foreground the
     * service may start, and it then rings as usual, in place of the notification.
     */
    private fun ringIncomingCall() {
        // A call that is already going is shown instead.
        if (callStateHolder.callState.value.isOngoing) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val callerId = intent.getStringExtra(EXTRA_CALLER_ID)
        if (callId == null || callerId == null) {
            finishAndRemoveTask()
            return
        }
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        val callerAvatarUrl = intent.getStringExtra(EXTRA_CALLER_AVATAR_URL)
        val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
        try {
            CallService.startIncoming(this, callId, callerId, callerName, callerAvatarUrl, video)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not start the call service for call $callId", e)
            finishAndRemoveTask()
        }
    }

    /** [withVideo] asks for the camera if needed. A refusal answers with the camera off. */
    private fun answer(withVideo: Boolean) =
        withPermissions(if (withVideo) PermissionAction.ANSWER_WITH_VIDEO else PermissionAction.ANSWER)

    private fun setCamera(on: Boolean) {
        if (on) withPermissions(PermissionAction.CAMERA_ON) else viewModel.setCamera(false)
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

    // ── Permissions ─────────────────────────────────────────────────────────

    /**
     * Run [action] now when nothing it asks for is missing, or once the prompt has been answered,
     * whatever the answer. The action waits in the ViewModel, so a rotation under the prompt does
     * not lose it.
     */
    private fun withPermissions(action: PermissionAction) {
        val missing = buildList {
            if (action.microphone && !hasMicrophone()) add(Manifest.permission.RECORD_AUDIO)
            if (action.camera && !hasCamera()) add(Manifest.permission.CAMERA)
        }
        if (missing.isEmpty()) return runPermissionAction(action)
        viewModel.pendingPermissionAction = action
        requestPermissions.launch(missing.toTypedArray())
    }

    /**
     * No call without the microphone. A call goes on with the camera off when the camera is
     * refused, and says why once.
     */
    private fun runPermissionAction(action: PermissionAction) {
        if (action.microphone && !hasMicrophone()) return refuseWithoutMicrophone()
        val cameraOn = action.camera && hasCamera()
        if (action.camera && !cameraOn) explainCameraRefusal()
        when (action) {
            PermissionAction.PLACE_VOICE_CALL, PermissionAction.PLACE_VIDEO_CALL -> startOutgoingCall()
            PermissionAction.ANSWER, PermissionAction.ANSWER_WITH_VIDEO -> viewModel.answer(cameraOn)
            PermissionAction.CAMERA_ON -> if (cameraOn) viewModel.setCamera(true)
        }
    }

    private fun hasMicrophone() = isGranted(Manifest.permission.RECORD_AUDIO)

    private fun hasCamera() = isGranted(Manifest.permission.CAMERA)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refuseWithoutMicrophone() {
        Toast.makeText(this, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
        // No call was placed. A ringing call stays, so it can still be declined.
        if (callStateHolder.callState.value is CallState.Idle) finishAndRemoveTask()
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
