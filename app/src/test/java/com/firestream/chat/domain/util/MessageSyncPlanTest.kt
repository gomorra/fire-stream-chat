package com.firestream.chat.domain.util

import com.firestream.chat.domain.util.MessageSyncPlan.Fetch
import com.firestream.chat.domain.util.MessageSyncPlan.RESTORE_GENERATION
import com.firestream.chat.domain.util.MessageSyncPlan.TAIL_OVERLAP_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSyncPlanTest {

    private data class Case(val name: String, val generation: Int?, val cursorMs: Long?, val expected: Fetch)

    @Test
    fun `a chat is fetched whole until it has a row of the current generation`() {
        val cases = listOf(
            Case("no row", null, null, Fetch.Everything),
            Case("a row of an older generation", RESTORE_GENERATION - 1, 10 * TAIL_OVERLAP_MS, Fetch.Everything),
            Case("a cursor", RESTORE_GENERATION, 10 * TAIL_OVERLAP_MS, Fetch.After(9 * TAIL_OVERLAP_MS)),
            Case("a cursor smaller than the overlap", RESTORE_GENERATION, TAIL_OVERLAP_MS - 1, Fetch.After(0L)),
            Case("a cursor of zero", RESTORE_GENERATION, 0L, Fetch.After(0L)),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, MessageSyncPlan.fetchFor(case.generation, case.cursorMs))
        }
    }

    @Test
    fun `the cursor is the highest fetched timestamp, and never later than now`() {
        assertEquals(null, MessageSyncPlan.cursorFrom(emptyList(), nowMs = 1_000L))
        assertEquals(700L, MessageSyncPlan.cursorFrom(listOf(300L, 700L, 500L), nowMs = 1_000L))
        // A sender whose clock runs ahead.
        assertEquals(1_000L, MessageSyncPlan.cursorFrom(listOf(300L, 9_000L), nowMs = 1_000L))
    }
}
