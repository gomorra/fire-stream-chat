package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GetCallLogUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: GetCallLogUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = GetCallLogUseCase(messageRepository)
    }

    @Test
    fun `returns flow from repository`() = runTest {
        val messages = listOf(
            Message(id = "c1", type = MessageType.CALL, content = "hangup")
        )
        every { messageRepository.getCallLog() } returns flowOf(messages)

        val result = useCase().first()

        assertEquals(messages, result)
        verify(exactly = 1) { messageRepository.getCallLog() }
    }

    @Test
    fun `returns empty list when no calls`() = runTest {
        every { messageRepository.getCallLog() } returns flowOf(emptyList())

        val result = useCase().first()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `propagates repository flow updates`() = runTest {
        val first = listOf(Message(id = "c1", type = MessageType.CALL, content = "hangup"))
        val second = listOf(
            Message(id = "c1", type = MessageType.CALL, content = "hangup"),
            Message(id = "c2", type = MessageType.CALL, content = "declined")
        )
        every { messageRepository.getCallLog() } returns flowOf(first, second)

        val emissions = mutableListOf<List<Message>>()
        useCase().collect { emissions.add(it) }

        assertEquals(2, emissions.size)
        assertEquals(1, emissions[0].size)
        assertEquals(2, emissions[1].size)
    }
}
