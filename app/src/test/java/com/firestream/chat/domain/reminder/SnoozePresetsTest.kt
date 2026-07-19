package com.firestream.chat.domain.reminder

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozePresetsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun epochMs(dateTime: LocalDateTime): Long =
        dateTime.atZone(zone).toInstant().toEpochMilli()

    private val today: LocalDate = LocalDate.of(2026, 7, 19)

    @Test
    fun `morning now - this evening targets today 18-00`() {
        val nowMs = epochMs(today.atTime(9, 0))

        val presets = SnoozePresets.compute(nowMs, zone)

        val evening = presets.single { it.kind == SnoozePreset.Kind.THIS_EVENING }
        assertEquals(epochMs(today.atTime(18, 0)), evening.fireAtMs)
    }

    @Test
    fun `evening now - this evening rolls to tomorrow 18-00`() {
        val nowMs = epochMs(today.atTime(19, 30))

        val presets = SnoozePresets.compute(nowMs, zone)

        val evening = presets.single { it.kind == SnoozePreset.Kind.THIS_EVENING }
        assertEquals(epochMs(today.plusDays(1).atTime(18, 0)), evening.fireAtMs)
    }

    @Test
    fun `just before 18-00 within the 5 minute margin rolls to tomorrow`() {
        // 17:58 -> today 18:00 is only 2 minutes away, inside the 5-minute margin.
        val nowMs = epochMs(today.atTime(17, 58))

        val presets = SnoozePresets.compute(nowMs, zone)

        val evening = presets.single { it.kind == SnoozePreset.Kind.THIS_EVENING }
        assertEquals(epochMs(today.plusDays(1).atTime(18, 0)), evening.fireAtMs)
    }

    @Test
    fun `well before 18-00 outside the margin stays today`() {
        // 17:30 -> 30 minutes before 18:00, outside the 5-minute margin.
        val nowMs = epochMs(today.atTime(17, 30))

        val presets = SnoozePresets.compute(nowMs, zone)

        val evening = presets.single { it.kind == SnoozePreset.Kind.THIS_EVENING }
        assertEquals(epochMs(today.atTime(18, 0)), evening.fireAtMs)
    }

    @Test
    fun `tomorrow morning always targets tomorrow 09-00, morning or evening now`() {
        val morningNowMs = epochMs(today.atTime(6, 0))
        val eveningNowMs = epochMs(today.atTime(23, 0))

        val morningPresets = SnoozePresets.compute(morningNowMs, zone)
        val eveningPresets = SnoozePresets.compute(eveningNowMs, zone)

        assertEquals(
            epochMs(today.plusDays(1).atTime(9, 0)),
            morningPresets.single { it.kind == SnoozePreset.Kind.TOMORROW_MORNING }.fireAtMs
        )
        assertEquals(
            epochMs(today.plusDays(1).atTime(9, 0)),
            eveningPresets.single { it.kind == SnoozePreset.Kind.TOMORROW_MORNING }.fireAtMs
        )
    }

    @Test
    fun `in 1 hour is always present and offset by exactly one hour`() {
        val nowMs = epochMs(today.atTime(12, 0))

        val presets = SnoozePresets.compute(nowMs, zone)

        val inOneHour = presets.single { it.kind == SnoozePreset.Kind.IN_1_HOUR }
        assertEquals(nowMs + 60L * 60L * 1000L, inOneHour.fireAtMs)
    }

    @Test
    fun `detected future time is prepended first`() {
        val nowMs = epochMs(today.atTime(12, 0))
        val detectedMs = epochMs(today.atTime(15, 0))

        val presets = SnoozePresets.compute(nowMs, zone, detectedFireAtMs = detectedMs)

        assertEquals(SnoozePreset.Kind.DETECTED, presets.first().kind)
        assertEquals(detectedMs, presets.first().fireAtMs)
        assertEquals(
            listOf(
                SnoozePreset.Kind.DETECTED,
                SnoozePreset.Kind.IN_1_HOUR,
                SnoozePreset.Kind.THIS_EVENING,
                SnoozePreset.Kind.TOMORROW_MORNING
            ),
            presets.map { it.kind }
        )
    }

    @Test
    fun `detected past time is silently dropped`() {
        val nowMs = epochMs(today.atTime(12, 0))
        val detectedMs = epochMs(today.atTime(9, 0))

        val presets = SnoozePresets.compute(nowMs, zone, detectedFireAtMs = detectedMs)

        assertFalse(presets.any { it.kind == SnoozePreset.Kind.DETECTED })
        assertEquals(
            listOf(
                SnoozePreset.Kind.IN_1_HOUR,
                SnoozePreset.Kind.THIS_EVENING,
                SnoozePreset.Kind.TOMORROW_MORNING
            ),
            presets.map { it.kind }
        )
    }

    @Test
    fun `detected exactly now is dropped, not strictly future`() {
        val nowMs = epochMs(today.atTime(12, 0))

        val presets = SnoozePresets.compute(nowMs, zone, detectedFireAtMs = nowMs)

        assertFalse(presets.any { it.kind == SnoozePreset.Kind.DETECTED })
    }

    @Test
    fun `no detected time - detected preset absent and no null crash`() {
        val nowMs = epochMs(today.atTime(12, 0))

        val presets = SnoozePresets.compute(nowMs, zone)

        assertFalse(presets.any { it.kind == SnoozePreset.Kind.DETECTED })
        assertNull(presets.firstOrNull { it.kind == SnoozePreset.Kind.DETECTED })
    }

    @Test
    fun `preset order is stable across all cases`() {
        val nowMs = epochMs(today.atTime(20, 0))

        val presets = SnoozePresets.compute(nowMs, zone)

        assertTrue(presets.map { it.kind } == listOf(
            SnoozePreset.Kind.IN_1_HOUR,
            SnoozePreset.Kind.THIS_EVENING,
            SnoozePreset.Kind.TOMORROW_MORNING
        ))
    }
}
