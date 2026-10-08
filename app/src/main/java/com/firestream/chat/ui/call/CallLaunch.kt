package com.firestream.chat.ui.call

import androidx.lifecycle.Lifecycle
import com.firestream.chat.domain.model.CallState

/** What [CallActivity] does with the intent that opened it. */
internal enum class CallLaunch { PLACE_CALL, ANSWER, SHOW, CLOSE }

/** What [CallActivity] runs once it may use the microphone. */
internal enum class MicAction { PLACE_CALL, ANSWER }

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
