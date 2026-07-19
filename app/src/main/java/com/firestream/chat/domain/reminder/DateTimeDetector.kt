package com.firestream.chat.domain.reminder

/**
 * Best-effort natural-language date/time detector for the message-snooze picker's
 * "Detected" preset. Pure domain contract — the real implementation
 * (`AndroidDateTimeDetector` in `data/reminder/`) wraps Android's on-device
 * `TextClassifier`; this interface carries no Android dependency so it stays
 * unit-testable via a fake.
 *
 * The contract is deliberately forgiving: detection is a nice-to-have, never a
 * blocking or error-surfacing path. Implementations must swallow every failure
 * internally and return `null` rather than throw.
 */
interface DateTimeDetector {

    /**
     * Scans [text] for the nearest **future** date/time reference relative to
     * [nowMs] (epoch millis) and returns it as epoch millis, or `null` when
     * nothing is found, the reference resolves to the past, or classification
     * fails for any reason.
     */
    suspend fun detect(text: String, nowMs: Long): Long?
}
