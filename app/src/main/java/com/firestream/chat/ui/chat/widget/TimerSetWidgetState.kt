package com.firestream.chat.ui.chat.widget

import androidx.compose.runtime.mutableIntStateOf
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle

/**
 * State holder for the `.timer.set` widget. Plain class (not @HiltViewModel) so it
 * can be `remember { … }`-scoped per Render call and easily unit-tested. The Int
 * fields are backed by `mutableIntStateOf` so Compose recomposes when wheels move,
 * but reads/writes also work outside the snapshot system for plain JUnit tests.
 *
 * Validation:
 *  - Each component is clamped to its valid range (HH 0..23, MM/SS 0..59).
 *  - `isSendEnabled` is true only when total duration > 0.
 *  - `MAX_DURATION_MS` = 23h59m59s — there is no separate over-cap branch
 *    because clamping at the component level already enforces the cap.
 */
internal class TimerSetWidgetState {

    private val _hours = mutableIntStateOf(0)
    private val _minutes = mutableIntStateOf(0)
    private val _seconds = mutableIntStateOf(0)

    var hours: Int
        get() = _hours.intValue
        set(value) { _hours.intValue = value.coerceIn(0, MAX_HOURS) }

    var minutes: Int
        get() = _minutes.intValue
        set(value) { _minutes.intValue = value.coerceIn(0, MAX_MINUTES_OR_SECONDS) }

    var seconds: Int
        get() = _seconds.intValue
        set(value) { _seconds.intValue = value.coerceIn(0, MAX_MINUTES_OR_SECONDS) }

    val durationMs: Long
        get() = (hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds) * MILLIS_PER_SECOND

    val isSendEnabled: Boolean
        get() = durationMs > 0L

    /**
     * How the alarm rings. Synced to every participant when the timer is sent —
     * see [TimerAlarmStyle] — so this is the sender choosing for both phones.
     */
    private val _style = androidx.compose.runtime.mutableStateOf(TimerAlarmStyle.DEFAULT)
    var style: TimerAlarmStyle
        get() = _style.value
        set(value) { _style.value = value }

    private val _sound = androidx.compose.runtime.mutableStateOf(TimerAlarmSound.DEFAULT)
    var sound: TimerAlarmSound
        get() = _sound.value
        set(value) { _sound.value = value }

    /** A silent timer has no sound to choose, so the picker hides that row. */
    val isSoundPickerVisible: Boolean
        get() = !style.isSilent

    fun reset() {
        _hours.intValue = 0
        _minutes.intValue = 0
        _seconds.intValue = 0
        _style.value = TimerAlarmStyle.DEFAULT
        _sound.value = TimerAlarmSound.DEFAULT
    }

    companion object {
        const val MAX_HOURS = 23
        const val MAX_MINUTES_OR_SECONDS = 59

        private const val MILLIS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3_600L

        const val MAX_DURATION_MS: Long =
            (MAX_HOURS * SECONDS_PER_HOUR + MAX_MINUTES_OR_SECONDS * SECONDS_PER_MINUTE + MAX_MINUTES_OR_SECONDS) * MILLIS_PER_SECOND
    }
}
