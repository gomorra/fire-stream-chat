package com.firestream.chat.domain.reminder

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure, minimal parser for the small vocabulary of date/time phrases that
 * `AndroidDateTimeDetector` hands it after Android's `TextClassifier` has
 * already located a date/time span inside a message (device-locale-aware span
 * *detection* is TextClassifier's job; this object only resolves the *value*).
 *
 * Android's public `TextClassifier` API does not expose a parsed epoch
 * timestamp for a recognized date/time entity through any stable surface — see
 * `AndroidDateTimeDetector`'s KDoc for why — so this object exists to turn the
 * recognized span *text* into a concrete instant for the phrasings this
 * feature targets: English ("tomorrow at 5pm", "on Friday at 2pm") and German
 * ("morgen um 14 Uhr", "am Freitag um 14 Uhr"). Anything outside that small
 * vocabulary returns `null` — callers always treat `null` as "no detection",
 * never as an error; this is a best-effort convenience, not a general-purpose
 * date parser.
 */
object DetectedTimeParser {

    private val WEEKDAYS_EN = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )

    private val WEEKDAYS_DE = mapOf(
        "montag" to DayOfWeek.MONDAY,
        "dienstag" to DayOfWeek.TUESDAY,
        "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY,
        "freitag" to DayOfWeek.FRIDAY,
        "samstag" to DayOfWeek.SATURDAY,
        "sonntag" to DayOfWeek.SUNDAY,
    )

    // "5pm", "5:30 pm", "12am" — hour is required to precede the meridiem marker.
    private val TIME_12H = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)

    // German "14 Uhr", "14:30 Uhr" / "14.30 Uhr".
    private val TIME_24H_UHR = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*uhr\b""", RegexOption.IGNORE_CASE)

    // Bare 24-hour clock, e.g. "17:00", with no am/pm or "Uhr" marker.
    private val TIME_24H_BARE = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    /**
     * Resolves [spanText] (a date/time fragment already localized/identified by a
     * classifier) into an absolute instant relative to [nowMs] in [zoneId], or
     * `null` if it doesn't match the recognized vocabulary.
     *
     * A day reference ("tomorrow"/"morgen", "today"/"heute", or a weekday name in
     * either language) is optional and defaults to *today* when absent — a bare
     * "at 5pm" is read as "5pm today". A time-of-day is **required**: a day
     * reference with no parseable clock time returns `null` rather than guessing
     * (the caller's existing "This evening"/"Tomorrow morning" presets already
     * cover the vague, time-less case).
     *
     * A named weekday that matches *today's* weekday resolves to next week, not
     * today — "on Friday" said on a Friday is read as "next Friday".
     */
    fun parse(spanText: String, nowMs: Long, zoneId: ZoneId): Long? {
        val lower = spanText.lowercase()
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId).toLocalDate()

        val time = resolveTime(lower) ?: return null
        val date = resolveDate(lower, today)

        return date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
    }

    /** Picks the nearest strictly-future timestamp among [candidates], or `null` if none qualify. */
    fun nearestFuture(candidates: List<Long?>, nowMs: Long): Long? =
        candidates.filterNotNull().filter { it > nowMs }.minOrNull()

    private fun resolveDate(lower: String, today: LocalDate): LocalDate = when {
        "tomorrow" in lower || "morgen" in lower -> today.plusDays(1)
        "today" in lower || "heute" in lower -> today
        else -> resolveWeekday(lower, today) ?: today
    }

    private fun resolveWeekday(lower: String, today: LocalDate): LocalDate? {
        val target = WEEKDAYS_EN.entries.firstOrNull { it.key in lower }?.value
            ?: WEEKDAYS_DE.entries.firstOrNull { it.key in lower }?.value
            ?: return null
        val daysAhead = (target.value - today.dayOfWeek.value + 7) % 7
        val offset = if (daysAhead == 0) 7 else daysAhead
        return today.plusDays(offset.toLong())
    }

    private fun resolveTime(lower: String): LocalTime? {
        TIME_12H.find(lower)?.let { match ->
            val (hourStr, minuteStr, meridiem) = match.destructured
            var hour = hourStr.toInt() % 12
            if (meridiem.lowercase() == "pm") hour += 12
            val minute = minuteStr.toIntOrNull() ?: 0
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        TIME_24H_UHR.find(lower)?.let { match ->
            val (hourStr, minuteStr) = match.destructured
            val hour = hourStr.toIntOrNull() ?: return null
            val minute = minuteStr.toIntOrNull() ?: 0
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        TIME_24H_BARE.find(lower)?.let { match ->
            val (hourStr, minuteStr) = match.destructured
            val hour = hourStr.toIntOrNull() ?: return null
            val minute = minuteStr.toIntOrNull() ?: 0
            return runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }
        return null
    }
}
