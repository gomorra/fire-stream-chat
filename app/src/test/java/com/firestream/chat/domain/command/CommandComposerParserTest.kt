package com.firestream.chat.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandComposerParserTest {

    @Test
    fun `empty string returns null`() {
        assertNull(parseCommandText(""))
    }

    @Test
    fun `text without leading dot returns null`() {
        assertNull(parseCommandText("hello"))
        assertNull(parseCommandText("hello.timer"))
        assertNull(parseCommandText("3.14 is pi"))
    }

    @Test
    fun `single dot opens root palette with empty filter`() {
        val parsed = parseCommandText(".")!!
        assertEquals(emptyList<String>(), parsed.completedSegments)
        assertEquals("", parsed.pendingFilter)
    }

    @Test
    fun `prefix builds filter at root path`() {
        val parsed = parseCommandText(".tim")!!
        assertEquals(emptyList<String>(), parsed.completedSegments)
        assertEquals("tim", parsed.pendingFilter)
    }

    @Test
    fun `complete segment plus dot navigates one level`() {
        val parsed = parseCommandText(".timer.")!!
        assertEquals(listOf("timer"), parsed.completedSegments)
        assertEquals("", parsed.pendingFilter)
    }

    @Test
    fun `complete segment plus partial child fills filter`() {
        val parsed = parseCommandText(".timer.s")!!
        assertEquals(listOf("timer"), parsed.completedSegments)
        assertEquals("s", parsed.pendingFilter)
    }

    @Test
    fun `exact leaf id appears in pendingFilter`() {
        val parsed = parseCommandText(".timer.set")!!
        assertEquals(listOf("timer"), parsed.completedSegments)
        assertEquals("set", parsed.pendingFilter)
    }

    @Test
    fun `multi-level path with trailing dot navigates deep`() {
        val parsed = parseCommandText(".a.b.c.")!!
        assertEquals(listOf("a", "b", "c"), parsed.completedSegments)
        assertEquals("", parsed.pendingFilter)
    }

    @Test
    fun `pathBeforePending wraps completed segments`() {
        val parsed = parseCommandText(".timer.s")!!
        assertEquals(CommandPath.of("timer"), parsed.pathBeforePending)
    }
}
