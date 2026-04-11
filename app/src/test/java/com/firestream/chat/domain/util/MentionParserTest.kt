package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionParserTest {

    private val nameToId = mapOf(
        "Alice" to "uid_alice",
        "Bob Smith" to "uid_bob",
        "Charlie" to "uid_charlie"
    )

    // --- extractMentions ---

    @Test
    fun `extractMentions returns empty when no at sign`() {
        val result = MentionParser.extractMentions("Hello world", nameToId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractMentions resolves known display name to userId`() {
        val result = MentionParser.extractMentions("Hey @Alice how are you?", nameToId)
        assertEquals(listOf("uid_alice"), result)
    }

    @Test
    fun `extractMentions resolves case-insensitive name`() {
        val result = MentionParser.extractMentions("Hello @alice please check", nameToId)
        assertEquals(listOf("uid_alice"), result)
    }

    @Test
    fun `extractMentions resolves everyone`() {
        val result = MentionParser.extractMentions("@everyone please check this", nameToId)
        assertEquals(listOf("everyone"), result)
    }

    @Test
    fun `extractMentions resolves multiple mentions`() {
        val result = MentionParser.extractMentions("@Alice and @Charlie look at this", nameToId)
        assertEquals(setOf("uid_alice", "uid_charlie"), result.toSet())
    }

    @Test
    fun `extractMentions ignores unknown names`() {
        val result = MentionParser.extractMentions("Hey @UnknownPerson what's up", nameToId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `extractMentions deduplicates repeated mentions`() {
        val result = MentionParser.extractMentions("@Alice then @Alice again", nameToId)
        assertEquals(1, result.size)
        assertEquals("uid_alice", result[0])
    }
}
