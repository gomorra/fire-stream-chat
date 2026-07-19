package com.firestream.chat.domain.reminder

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure computation of the snooze preset list shown in the message-snooze picker UI.
 * No Android dependencies — takes the current instant and zone explicitly so it is
 * trivially testable with a fixed clock.
 */
object SnoozePresets {

    private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L

    /**
     * Rolling a past-due local time (e.g. "this evening") to the next day also
     * applies inside this margin *before* it — a preset landing less than 5 minutes
     * from now reads as "now", not as a meaningful future snooze, so it rolls too.
     */
    private const val ROLL_MARGIN_MILLIS = 5L * 60L * 1000L

    private const val EVENING_HOUR = 18
    private const val MORNING_HOUR = 9

    /**
     * Computes the ordered preset list for [nowMs] (epoch millis) in [zoneId].
     *
     * - `IN_1_HOUR` is always present: `nowMs + 1h`.
     * - `THIS_EVENING` targets today [EVENING_HOUR]:00 local time. If that instant is
     *   already at or before `nowMs + `[ROLL_MARGIN_MILLIS] (i.e. it's already evening,
     *   or evening is imminently about to happen), it **rolls to tomorrow** at the
     *   same local time instead — a preset that's already "now" isn't a useful snooze.
     * - `TOMORROW_MORNING` always targets *tomorrow* [MORNING_HOUR]:00 local time,
     *   never today, regardless of [nowMs].
     * - `DETECTED` is prepended first only when [detectedFireAtMs] is non-null and
     *   strictly after `nowMs`; a null or non-future detected time is silently
     *   dropped (never shown, never crashes).
     *
     * Order: `DETECTED?`, `IN_1_HOUR`, `THIS_EVENING`, `TOMORROW_MORNING`.
     */
    fun compute(
        nowMs: Long,
        zoneId: ZoneId,
        detectedFireAtMs: Long? = null
    ): List<SnoozePreset> {
        val nowZoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId)
        val today = nowZoned.toLocalDate()

        val inOneHourMs = nowMs + ONE_HOUR_MILLIS

        val todayEveningMs = today.atTime(EVENING_HOUR, 0).atZone(zoneId).toInstant().toEpochMilli()
        val thisEveningMs = if (todayEveningMs <= nowMs + ROLL_MARGIN_MILLIS) {
            today.plusDays(1).atTime(EVENING_HOUR, 0).atZone(zoneId).toInstant().toEpochMilli()
        } else {
            todayEveningMs
        }

        val tomorrowMorningMs = today.plusDays(1)
            .atTime(MORNING_HOUR, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        return buildList {
            if (detectedFireAtMs != null && detectedFireAtMs > nowMs) {
                add(SnoozePreset(SnoozePreset.Kind.DETECTED, detectedFireAtMs))
            }
            add(SnoozePreset(SnoozePreset.Kind.IN_1_HOUR, inOneHourMs))
            add(SnoozePreset(SnoozePreset.Kind.THIS_EVENING, thisEveningMs))
            add(SnoozePreset(SnoozePreset.Kind.TOMORROW_MORNING, tomorrowMorningMs))
        }
    }
}
