package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageUrlsTest {

    @Test
    fun `finds a URL inside surrounding text`() {
        assertEquals(
            "https://example.com/foo",
            MessageUrls.extractUrl("check this out https://example.com/foo right here"),
        )
    }

    @Test
    fun `returns null for text without a URL`() {
        assertNull(MessageUrls.extractUrl("just some words, no link"))
    }

    @Test
    fun `returns the first URL when several are present`() {
        assertEquals(
            "https://first.example.com",
            MessageUrls.extractUrl("https://first.example.com and https://second.example.com"),
        )
    }

    @Test
    fun `strips trailing sentence punctuation`() {
        // The regex is greedy to the next whitespace, so the period, comma or
        // question mark ending the sentence would otherwise become path.
        assertEquals("https://example.com/p", MessageUrls.extractUrl("read https://example.com/p."))
        assertEquals("https://example.com/p", MessageUrls.extractUrl("https://example.com/p, then"))
        assertEquals("https://example.com/p", MessageUrls.extractUrl("seen https://example.com/p?!"))
    }

    @Test
    fun `strips an unmatched closing bracket`() {
        assertEquals("https://example.com/p", MessageUrls.extractUrl("(see https://example.com/p)"))
        assertEquals("https://example.com/p", MessageUrls.extractUrl("[https://example.com/p]"))
    }

    @Test
    fun `keeps balanced parentheses that belong to the path`() {
        // Wikipedia-style paths legitimately end in ')'.
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            MessageUrls.extractUrl("https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `keeps a short bare host`() {
        assertEquals("http://a.io", MessageUrls.extractUrl("go to http://a.io"))
    }

    @Test
    fun `rejects a scheme with nothing after it`() {
        assertNull(MessageUrls.extractUrl("https:// is a scheme"))
    }

    @Test
    fun `finds a URL with no surrounding whitespace at either end`() {
        assertEquals("https://example.com", MessageUrls.extractUrl("https://example.com"))
    }
}
