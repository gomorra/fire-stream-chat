package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class MessageGroupingTest {

    // Base timestamp: 2026-01-01 12:00:00 UTC
    private val base: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JANUARY, 1, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun msg(
        id: String,
        senderId: String,
        timestamp: Long,
        deletedAt: Long? = null
    ) = Message(id = id, senderId = senderId, timestamp = timestamp, deletedAt = deletedAt)

    // 1. Single message (no prev/next) → ALONE
    @Test
    fun `single message with no neighbors is ALONE`() {
        val m = msg("1", "alice", base)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m, null, null))
    }

    // 2. Two messages same sender within 1 minute → FIRST, LAST
    @Test
    fun `first of two same-sender messages within 1 min is FIRST`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 30_000)
        assertEquals(GroupPosition.FIRST, computeGroupPosition(m1, null, m2))
    }

    @Test
    fun `second of two same-sender messages within 1 min is LAST`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 30_000)
        assertEquals(GroupPosition.LAST, computeGroupPosition(m2, m1, null))
    }

    // 3. Three messages same sender within 1 minute → FIRST, MIDDLE, LAST
    @Test
    fun `first of three same-sender messages is FIRST`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 20_000)
        val m3 = msg("3", "alice", base + 40_000)
        assertEquals(GroupPosition.FIRST, computeGroupPosition(m1, null, m2))
    }

    @Test
    fun `middle of three same-sender messages is MIDDLE`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 20_000)
        val m3 = msg("3", "alice", base + 40_000)
        assertEquals(GroupPosition.MIDDLE, computeGroupPosition(m2, m1, m3))
    }

    @Test
    fun `last of three same-sender messages is LAST`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 20_000)
        val m3 = msg("3", "alice", base + 40_000)
        assertEquals(GroupPosition.LAST, computeGroupPosition(m3, m2, null))
    }

    // 4. Different senders → all ALONE
    @Test
    fun `message between different senders is ALONE`() {
        val prev = msg("1", "alice", base)
        val m    = msg("2", "bob",   base + 10_000)
        val next = msg("3", "alice", base + 20_000)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m, prev, next))
    }

    @Test
    fun `message after different sender is ALONE`() {
        val prev = msg("1", "alice", base)
        val m    = msg("2", "bob",   base + 10_000)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m, prev, null))
    }

    @Test
    fun `message before different sender is ALONE`() {
        val m    = msg("1", "alice", base)
        val next = msg("2", "bob",   base + 10_000)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m, null, next))
    }

    // 5. Same sender but >1 minute apart → ALONE
    @Test
    fun `same sender more than 1 minute apart gives ALONE for both`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 61_000)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m1, null, m2))
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m2, m1, null))
    }

    @Test
    fun `same sender exactly 60000ms apart is ALONE (boundary)`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 60_000)
        // diff == 60_000 is NOT < 60_000, so grouping does not apply
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m1, null, m2))
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m2, m1, null))
    }

    @Test
    fun `same sender 59999ms apart is grouped (boundary)`() {
        val m1 = msg("1", "alice", base)
        val m2 = msg("2", "alice", base + 59_999)
        assertEquals(GroupPosition.FIRST, computeGroupPosition(m1, null, m2))
        assertEquals(GroupPosition.LAST,  computeGroupPosition(m2, m1, null))
    }

    // 6. Same sender but one is deleted → ALONE
    @Test
    fun `deleted current message is ALONE even if neighbor matches`() {
        val prev = msg("1", "alice", base)
        val m    = msg("2", "alice", base + 10_000, deletedAt = base + 15_000)
        val next = msg("3", "alice", base + 20_000)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m, prev, next))
    }

    @Test
    fun `deleted previous message breaks grouping`() {
        val prev = msg("1", "alice", base, deletedAt = base + 5_000)
        val m    = msg("2", "alice", base + 10_000)
        val next = msg("3", "alice", base + 20_000)
        // prev is deleted → sameSenderAsPrev = false; next is fine → FIRST
        assertEquals(GroupPosition.FIRST, computeGroupPosition(m, prev, next))
    }

    @Test
    fun `deleted next message breaks grouping`() {
        val prev = msg("1", "alice", base)
        val m    = msg("2", "alice", base + 10_000)
        val next = msg("3", "alice", base + 20_000, deletedAt = base + 25_000)
        // next is deleted → sameSenderAsNext = false; prev is fine → LAST
        assertEquals(GroupPosition.LAST, computeGroupPosition(m, prev, next))
    }

    // 7. Same sender across midnight boundary → ALONE (different days)
    @Test
    fun `same sender across midnight boundary is ALONE`() {
        val midnight = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 2, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 23:59:30 and 00:00:10 are 40s apart but on different days
        val m1 = msg("1", "alice", midnight - 30_000)
        val m2 = msg("2", "alice", midnight + 10_000)
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m1, null, m2))
        assertEquals(GroupPosition.ALONE, computeGroupPosition(m2, m1, null))
    }
}
