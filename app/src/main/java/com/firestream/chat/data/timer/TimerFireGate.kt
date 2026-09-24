package com.firestream.chat.data.timer

import com.firestream.chat.domain.model.TimerState
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** The fields of a timer's local row that decide whether its alarm still rings. */
internal data class TimerRow(val state: TimerState?, val deleted: Boolean)

/**
 * The last check before a fired timer alarm rings: is the timer still wanted?
 *
 * The alarm sits in AlarmManager, outside the app, and the only thing that
 * cancels it on a change is `ChatTimerReactor` — which runs only while that chat
 * is open. A timer deleted for everyone (or paused on the other phone) while the
 * chat is closed keeps its alarm, so [TimerAlarmReceiver] asks here first.
 *
 * **Fails open.** The lookup is the local Room row, never the network, but if it
 * doesn't answer within [LOOKUP_TIMEOUT_MS], throws, or finds no row, the alarm
 * rings: a stray ring for a deleted timer is better than a real one going silent.
 * For the same reason `COMPLETED` rings — both phones fire at the same moment, and
 * the other one's completion can sync in just before ours goes off.
 */
internal object TimerFireGate {

    internal const val LOOKUP_TIMEOUT_MS: Long = 2_000L

    suspend fun shouldRing(lookup: suspend () -> TimerRow?): Boolean {
        val row = try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return row == null || shouldRing(row)
    }

    private fun shouldRing(row: TimerRow): Boolean = when {
        row.deleted -> false
        row.state == TimerState.PAUSED || row.state == TimerState.CANCELLED -> false
        else -> true
    }
}
