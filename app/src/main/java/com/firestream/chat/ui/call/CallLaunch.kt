package com.firestream.chat.ui.call

import androidx.lifecycle.Lifecycle
import com.firestream.chat.domain.model.CallState

/** What [CallActivity] does with the intent that opened it. */
internal enum class CallLaunch { PLACE_CALL, ANSWER, RING, SHOW, CLOSE }

/**
 * What [CallActivity] runs once the permission prompt has been answered. Placing and answering
 * need the microphone. [camera] says whether the prompt asks for the camera too, which no action
 * needs: a refusal leaves the call running with the camera off.
 */
internal enum class PermissionAction(val microphone: Boolean, val camera: Boolean) {
    PLACE_VOICE_CALL(microphone = true, camera = false),
    PLACE_VIDEO_CALL(microphone = true, camera = true),

    /** Answer with the camera off: *Answer*, *Voice only*, the notification and the lock screen. */
    ANSWER(microphone = true, camera = false),

    /** Answer *With video*. */
    ANSWER_WITH_VIDEO(microphone = true, camera = true),

    /** The camera button of a running call. */
    CAMERA_ON(microphone = false, camera = true),
}

/**
 * Decide what an intent that opened [CallActivity] asks for.
 *
 * Recents relaunches the call screen's task with the intent that first opened it. Replaying that
 * intent would place the old call a second time, or answer it twice. So a launch from history only
 * returns to a call that is still going, and otherwise closes the screen.
 *
 * @param action the intent's [CallActivity.EXTRA_ACTION], or null when it has none.
 * @param launchedFromHistory whether the intent carries `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`.
 * @param callOngoing whether a call is ringing, connecting or connected right now.
 */
internal fun callLaunchFor(action: String?, launchedFromHistory: Boolean, callOngoing: Boolean): CallLaunch =
    when {
        launchedFromHistory -> if (callOngoing) CallLaunch.SHOW else CallLaunch.CLOSE
        action == CallActivity.ACTION_OUTGOING -> CallLaunch.PLACE_CALL
        action == CallActivity.ACTION_ANSWER -> CallLaunch.ANSWER
        action == CallActivity.ACTION_RING -> CallLaunch.RING
        else -> CallLaunch.SHOW
    }

/**
 * Whether the call screen closes at once on [state], given the [screen]'s lifecycle state: the call
 * ended while the screen is not shown. A shown screen shows "Call Ended" first, and CallScreen
 * closes it 1.5 s later. A screen that is not shown would otherwise stay in Recents until it is
 * next opened.
 */
internal fun closesUnseen(state: CallState, screen: Lifecycle.State): Boolean =
    state is CallState.Ended && !screen.isAtLeast(Lifecycle.State.STARTED)
