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

    private val idToName = nameToId.entries.associate { it.value to it.key }

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

    // --- formatMentionText ---

    @Test
    fun `formatMentionText returns plain AnnotatedString when mentions empty`() {
        val text = "Hello world"
        val result = MentionParser.formatMentionText(
            text = text,
            mentions = emptyList(),
            currentUserId = "uid_alice",
            highlightColor = androidx.compose.ui.graphics.Color.Blue,
            userIdToDisplayName = idToName
        )
        assertEquals(text, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `formatMentionText highlights mentioned name`() {
        val result = MentionParser.formatMentionText(
            text = "Hey @Alice",
            mentions = listOf("uid_alice"),
            currentUserId = "uid_bob",
            highlightColor = androidx.compose.ui.graphics.Color.Blue,
            userIdToDisplayName = idToName
        )
        assertEquals("Hey @Alice", result.text)
        assertEquals(1, result.spanStyles.size)
        // Span covers "@Alice"
        assertEquals(4, result.spanStyles[0].start)
        assertEquals(10, result.spanStyles[0].end)
    }

    @Test
    fun `formatMentionText does not highlight unmentioned names`() {
        val result = MentionParser.formatMentionText(
            text = "Hey @Alice and @Charlie",
            mentions = listOf("uid_alice"),
            currentUserId = "uid_bob",
            highlightColor = androidx.compose.ui.graphics.Color.Blue,
            userIdToDisplayName = idToName
        )
        assertEquals(1, result.spanStyles.size)
    }

    @Test
    fun `formatMentionText works with no userIdToDisplayName map`() {
        val result = MentionParser.formatMentionText(
            text = "Hello @everyone",
            mentions = listOf("everyone"),
            currentUserId = "uid_alice",
            highlightColor = androidx.compose.ui.graphics.Color.Blue
        )
        assertEquals("Hello @everyone", result.text)
        assertEquals(1, result.spanStyles.size)
    }
}
