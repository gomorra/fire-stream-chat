package com.firestream.chat.data.local.entity

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.PollOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollSerializationTest {

    @Test
    fun `poll roundtrips through entity serialization`() {
        val poll = Poll(
            question = "Best language?",
            options = listOf(
                PollOption(id = "opt_0", text = "Kotlin", voterIds = listOf("user1", "user2")),
                PollOption(id = "opt_1", text = "Java", voterIds = listOf("user3")),
                PollOption(id = "opt_2", text = "Scala", voterIds = emptyList())
            ),
            isMultipleChoice = true,
            isAnonymous = false,
            isClosed = false
        )

        val message = Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "user1",
            content = "📊 Poll",
            type = MessageType.POLL,
            status = MessageStatus.SENT,
            timestamp = 1000L,
            pollData = poll
        )

        val entity = MessageEntity.fromDomain(message)
        assertNotNull(entity.pollData)

        val restored = entity.toDomain()
        assertNotNull(restored.pollData)
        assertEquals("Best language?", restored.pollData!!.question)
        assertEquals(3, restored.pollData!!.options.size)
        assertEquals(true, restored.pollData!!.isMultipleChoice)
        assertEquals(false, restored.pollData!!.isAnonymous)
        assertEquals(false, restored.pollData!!.isClosed)

        val opt0 = restored.pollData!!.options[0]
        assertEquals("opt_0", opt0.id)
        assertEquals("Kotlin", opt0.text)
        assertEquals(listOf("user1", "user2"), opt0.voterIds)

        val opt1 = restored.pollData!!.options[1]
        assertEquals("Java", opt1.text)
        assertEquals(listOf("user3"), opt1.voterIds)

        val opt2 = restored.pollData!!.options[2]
        assertEquals("Scala", opt2.text)
        assertTrue(opt2.voterIds.isEmpty())
    }

    @Test
    fun `message without poll has null pollData`() {
        val message = Message(
            id = "msg2",
            chatId = "chat1",
            senderId = "user1",
            content = "Hello",
            type = MessageType.TEXT,
            status = MessageStatus.SENT,
            timestamp = 1000L
        )

        val entity = MessageEntity.fromDomain(message)
        assertNull(entity.pollData)

        val restored = entity.toDomain()
        assertNull(restored.pollData)
    }

    @Test
    fun `closed poll roundtrips correctly`() {
        val poll = Poll(
            question = "Done?",
            options = listOf(
                PollOption(id = "opt_0", text = "Yes", voterIds = listOf("user1")),
                PollOption(id = "opt_1", text = "No", voterIds = emptyList())
            ),
            isMultipleChoice = false,
            isAnonymous = true,
            isClosed = true
        )

        val message = Message(
            id = "msg3",
            chatId = "chat1",
            senderId = "user1",
            content = "📊 Poll",
            type = MessageType.POLL,
            pollData = poll
        )

        val restored = MessageEntity.fromDomain(message).toDomain()
        assertEquals(true, restored.pollData!!.isClosed)
        assertEquals(true, restored.pollData!!.isAnonymous)
    }
}
