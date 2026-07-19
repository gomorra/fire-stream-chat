package com.firestream.chat.data.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderBootRestoreLogicTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `future fire time produces Schedule action`() {
        val action = ReminderBootRestoreLogic.classify(fireAtMs = now + 60_000L, nowMs = now)
        assertEquals(ReminderBootAction.Schedule, action)
    }

    @Test
    fun `past fire time produces PostOverdue action`() {
        val action = ReminderBootRestoreLogic.classify(fireAtMs = now - 60_000L, nowMs = now)
        assertEquals(ReminderBootAction.PostOverdue, action)
    }

    @Test
    fun `fire-time exactly equal to now produces PostOverdue not Schedule`() {
        // Edge case: re-arming an alarm for the current instant would fire
        // immediately anyway, so posting the overdue notification is equivalent.
        val action = ReminderBootRestoreLogic.classify(fireAtMs = now, nowMs = now)
        assertEquals(ReminderBootAction.PostOverdue, action)
    }
}
