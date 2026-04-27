package com.firestream.chat.data.remote.pocketbase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseLineParserTest {

    @Test
    fun `accumulates event and data fields, emits on blank line`() {
        val parser = SseLineParser()
        assertNull(parser.feed("event: messages"))
        assertNull(parser.feed("data: {\"action\":\"create\"}"))
        val event = parser.feed("")
        assertEquals(ParsedSseEvent("messages", "{\"action\":\"create\"}"), event)
    }

    @Test
    fun `multi-line data is newline-joined`() {
        val parser = SseLineParser()
        parser.feed("event: x")
        parser.feed("data: line1")
        parser.feed("data: line2")
        val event = parser.feed("")
        assertEquals("line1\nline2", event?.data)
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        val parser = SseLineParser()
        assertNull(parser.feed(": this is a comment"))
        assertNull(parser.feed("id: 42"))
        assertNull(parser.feed("retry: 5000"))
        assertNull(parser.feed("event: ping"))
        val event = parser.feed("")
        assertEquals(ParsedSseEvent("ping", ""), event)
    }

    @Test
    fun `state resets between events`() {
        val parser = SseLineParser()
        parser.feed("event: a")
        parser.feed("data: 1")
        val first = parser.feed("")
        parser.feed("event: b")
        parser.feed("data: 2")
        val second = parser.feed("")
        assertEquals("a", first?.event)
        assertEquals("1", first?.data)
        assertEquals("b", second?.event)
        assertEquals("2", second?.data)
    }

    @Test
    fun `blank line on empty state is a no-op`() {
        val parser = SseLineParser()
        assertNull(parser.feed(""))
    }
}
