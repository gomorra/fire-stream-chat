package com.firestream.chat.domain.model

/**
 * Outcome of arming a reminder's OS alarm. Pure (no `android.*`) so it can cross
 * the domain boundary — the UI plumbs it back to the existing `ExactAlarmBanner`
 * flow (shared with the timer feature) when Android 12+ denies exact alarms.
 *
 *  - [EXACT] — fire time is guaranteed to be honoured (up to OS scheduling).
 *  - [INEXACT_FALLBACK] — Android 12+ user denied SCHEDULE_EXACT_ALARM; the alarm
 *    still fires eventually via `setAndAllowWhileIdle` but may be delayed up to
 *    ~15 min in Doze mode. UI should prompt the user to grant the permission.
 */
enum class ReminderScheduleOutcome { EXACT, INEXACT_FALLBACK }
