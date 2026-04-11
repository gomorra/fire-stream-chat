package com.firestream.chat.ui.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionFormatterTest {

    private val idToName = mapOf(
        "uid_alice" to "Alice",
        "uid_bob" to "Bob Smith",
        "uid_charlie" to "Charlie"
    )

    @Test
    fun `formatMentionText returns plain AnnotatedString when mentions empty`() {
        val text = "Hello world"
        val result = MentionFormatter.formatMentionText(
            text = text,
            mentions = emptyList(),
            currentUserId = "uid_alice",
            highlightColor = Color.Blue,
            userIdToDisplayName = idToName
        )
        assertEquals(text, result.text)
        assertTrue(result.spanStyles.isEmpty())
    }

    @Test
    fun `formatMentionText highlights mentioned name`() {
        val result = MentionFormatter.formatMentionText(
            text = "Hey @Alice",
            mentions = listOf("uid_alice"),
            currentUserId = "uid_bob",
            highlightColor = Color.Blue,
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
        val result = MentionFormatter.formatMentionText(
            text = "Hey @Alice and @Charlie",
            mentions = listOf("uid_alice"),
            currentUserId = "uid_bob",
            highlightColor = Color.Blue,
            userIdToDisplayName = idToName
        )
        assertEquals(1, result.spanStyles.size)
    }

    @Test
    fun `formatMentionText works with no userIdToDisplayName map`() {
        val result = MentionFormatter.formatMentionText(
            text = "Hello @everyone",
            mentions = listOf("everyone"),
            currentUserId = "uid_alice",
            highlightColor = Color.Blue
        )
        assertEquals("Hello @everyone", result.text)
        assertEquals(1, result.spanStyles.size)
    }
}
