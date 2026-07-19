package com.firestream.chat.data.reminder

/**
 * Pure time math for the reminder notification's "+1 hour" re-snooze action.
 *
 * A re-snooze is always **now + 1h**, deliberately relative to the moment the
 * user taps "+1 hour" — NOT the original `fireAtMs`. The original reminder has
 * already fired (its row is consumed on FIRED), so anchoring to the past fire
 * time could produce a time that is itself already in the past. Anchoring to
 * `now` guarantees the re-snooze lands a full hour into the future.
 */
internal object ReminderActionLogic {

    private const val ONE_HOUR_MS: Long = 60L * 60L * 1_000L

    fun plusOneHour(nowMs: Long): Long = nowMs + ONE_HOUR_MS
}
