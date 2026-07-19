package com.firestream.chat.domain.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectedTimeParserTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun epochMs(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    // Sunday.
    private val today: LocalDate = LocalDate.of(2026, 7, 19)
    private val nowMs = epochMs(today.atTime(9, 0))

    @Test
    fun `tomorrow at 5pm resolves to 17-00 the next day`() {
        val result = DetectedTimeParser.parse("tomorrow at 5pm", nowMs, zone)
        assertEquals(epochMs(today.plusDays(1).atTime(17, 0)), result)
    }

    @Test
    fun `tomorrow at 5-30am resolves with minutes`() {
        val result = DetectedTimeParser.parse("tomorrow at 5:30am", nowMs, zone)
        assertEquals(epochMs(today.plusDays(1).atTime(5, 30)), result)
    }

    @Test
    fun `12pm is noon and 12am is midnight`() {
        assertEquals(epochMs(today.atTime(12, 0)), DetectedTimeParser.parse("today at 12pm", nowMs, zone))
        assertEquals(epochMs(today.plusDays(1).atTime(0, 0)), DetectedTimeParser.parse("tomorrow at 12am", nowMs, zone))
    }

    @Test
    fun `german morgen um 14 Uhr resolves to tomorrow 14-00`() {
        val result = DetectedTimeParser.parse("morgen um 14 Uhr", nowMs, zone)
        assertEquals(epochMs(today.plusDays(1).atTime(14, 0)), result)
    }

    @Test
    fun `german am Freitag um 14 Uhr resolves to the next Friday`() {
        // today is Sunday 2026-07-19; next Friday is 2026-07-24.
        val result = DetectedTimeParser.parse("am Freitag um 14 Uhr", nowMs, zone)
        assertEquals(epochMs(LocalDate.of(2026, 7, 24).atTime(14, 0)), result)
    }

    @Test
    fun `weekday matching today rolls to next week, not today`() {
        // today is Sunday; "on Sunday" should mean next Sunday, not today.
        val result = DetectedTimeParser.parse("on Sunday at 3pm", nowMs, zone)
        assertEquals(epochMs(today.plusDays(7).atTime(15, 0)), result)
    }

    @Test
    fun `bare 24-hour time with no am-pm or Uhr marker is understood`() {
        val result = DetectedTimeParser.parse("tomorrow 17:00", nowMs, zone)
        assertEquals(epochMs(today.plusDays(1).atTime(17, 0)), result)
    }

    @Test
    fun `day reference with no time returns null`() {
        assertNull(DetectedTimeParser.parse("tomorrow", nowMs, zone))
        assertNull(DetectedTimeParser.parse("on Friday", nowMs, zone))
    }

    @Test
    fun `unrecognized text returns null`() {
        assertNull(DetectedTimeParser.parse("sometime soonish", nowMs, zone))
    }

    @Test
    fun `time with no day reference defaults to today`() {
        val result = DetectedTimeParser.parse("at 11pm", nowMs, zone)
        assertEquals(epochMs(today.atTime(23, 0)), result)
    }

    @Test
    fun `nearestFuture picks the earliest strictly-future candidate`() {
        val past = nowMs - 1_000L
        val soon = nowMs + 60_000L
        val later = nowMs + 120_000L

        val result = DetectedTimeParser.nearestFuture(listOf(past, later, soon, null), nowMs)

        assertEquals(soon, result)
    }

    @Test
    fun `nearestFuture returns null when nothing qualifies`() {
        val past = nowMs - 1_000L
        assertNull(DetectedTimeParser.nearestFuture(listOf(past, null, nowMs), nowMs))
    }
}
