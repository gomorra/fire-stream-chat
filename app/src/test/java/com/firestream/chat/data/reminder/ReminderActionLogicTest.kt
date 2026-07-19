package com.firestream.chat.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderActionLogicTest {

    private val now = 1_700_000_000_000L
    private val oneHourMs = 60L * 60L * 1_000L

    @Test
    fun `plusOneHour adds exactly one hour to now`() {
        assertEquals(now + oneHourMs, ReminderActionLogic.plusOneHour(now))
    }

    @Test
    fun `plusOneHour is relative to the passed-in now, not a fixed anchor`() {
        val later = now + 5_000_000L
        assertEquals(later + oneHourMs, ReminderActionLogic.plusOneHour(later))
    }

    @Test
    fun `plusOneHour of zero epoch is one hour`() {
        assertEquals(oneHourMs, ReminderActionLogic.plusOneHour(0L))
    }
}
