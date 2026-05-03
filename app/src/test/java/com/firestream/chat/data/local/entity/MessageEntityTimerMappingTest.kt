package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageEntityTimerMappingTest {

    @Test
    fun `timer fields round-trip through fromDomain and toDomain`() {
        val original = Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "user1",
            content = "Pizza ready in",
            type = MessageType.TIMER,
            timerDurationMs = 30_000L,
            timerStartedAtMs = 1_700_000_000_000L,
            timerState = TimerState.RUNNING,
        )

        val entity = MessageEntity.fromDomain(original)

        assertEquals(30_000L, entity.timerDurationMs)
        assertEquals(1_700_000_000_000L, entity.timerStartedAtMs)
        assertEquals("RUNNING", entity.timerState)

        val roundTripped = entity.toDomain()
        assertEquals(MessageType.TIMER, roundTripped.type)
        assertEquals(30_000L, roundTripped.timerDurationMs)
        assertEquals(1_700_000_000_000L, roundTripped.timerStartedAtMs)
        assertEquals(TimerState.RUNNING, roundTripped.timerState)
    }

    @Test
    fun `null timer fields round-trip cleanly for non-timer messages`() {
        val text = Message(
            id = "msg2",
            chatId = "chat1",
            senderId = "user1",
            content = "Hello",
            type = MessageType.TEXT,
        )

        val entity = MessageEntity.fromDomain(text)
        assertNull(entity.timerDurationMs)
        assertNull(entity.timerStartedAtMs)
        assertNull(entity.timerState)

        val roundTripped = entity.toDomain()
        assertNull(roundTripped.timerDurationMs)
        assertNull(roundTripped.timerStartedAtMs)
        assertNull(roundTripped.timerState)
    }

    @Test
    fun `unknown timer state string maps to null instead of crashing`() {
        val entity = MessageEntity(
            id = "msg3",
            chatId = "chat1",
            senderId = "user1",
            content = "",
            type = MessageType.TIMER.name,
            mediaUrl = null,
            mediaThumbnailUrl = null,
            status = "SENT",
            replyToId = null,
            timestamp = 0L,
            editedAt = null,
            timerState = "BOGUS_STATE",
        )

        assertNull(entity.toDomain().timerState)
    }

    @Test
    fun `each TimerState enum round-trips`() {
        TimerState.entries.forEach { state ->
            val msg = Message(id = "x", chatId = "c", senderId = "u", type = MessageType.TIMER, timerState = state)
            val back = MessageEntity.fromDomain(msg).toDomain()
            assertEquals(state, back.timerState)
        }
    }
}
