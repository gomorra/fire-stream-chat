package com.firestream.chat.domain.usecase.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParseMentionsUseCaseTest {

    private lateinit var useCase: ParseMentionsUseCase

    @Before
    fun setUp() {
        useCase = ParseMentionsUseCase()
    }

    @Test
    fun `invoke extracts mention from text`() {
        val nameToId = mapOf("Alice" to "uid_alice")
        val result = useCase("Hey @Alice how are you", nameToId)
        assertEquals(listOf("uid_alice"), result)
    }

    @Test
    fun `invoke returns empty list when no mentions`() {
        val result = useCase("Hello world", mapOf("Alice" to "uid_alice"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `invoke handles everyone mention`() {
        val result = useCase("@everyone attention please", emptyMap())
        assertEquals(listOf("everyone"), result)
    }

    @Test
    fun `invoke extracts multiple distinct mentions`() {
        val nameToId = mapOf("Alice" to "uid_alice", "Bob" to "uid_bob")
        val result = useCase("@Alice and @Bob please review", nameToId)
        assertEquals(setOf("uid_alice", "uid_bob"), result.toSet())
    }
}
