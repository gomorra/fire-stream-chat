package com.firestream.chat.domain.reminder

/**
 * A single computed snooze option: a [kind] tag plus the absolute epoch millis it
 * would fire at. Labels are resolved from string resources in the UI layer — this
 * type carries no display text.
 *
 * `CUSTOM` (pick date & time) is a UI-only affordance with no computed time and is
 * therefore not a [Kind] here; the picker sheet renders it alongside this list rather
 * than as a member of it.
 */
data class SnoozePreset(
    val kind: Kind,
    val fireAtMs: Long
) {
    enum class Kind {
        DETECTED,
        IN_1_HOUR,
        THIS_EVENING,
        TOMORROW_MORNING
    }
}
