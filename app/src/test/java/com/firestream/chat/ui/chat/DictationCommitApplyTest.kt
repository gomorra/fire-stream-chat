package com.firestream.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DictationCommitApplyTest {

    @Test
    fun `first commit while listening creates anchor at cursor and inserts text`() {
        val apply = applyDictationCommit(
            currentText = "prefix ",
            cursorStart = 7,
            anchor = -1,
            lastLen = 0,
            commitText = "hello",
            isListening = true,
        )

        assertNotNull(apply)
        assertEquals("prefix hello", apply!!.newText)
        assertEquals(7, apply.newAnchor)
        assertEquals(5, apply.newLastLen)
        assertEquals(12, apply.newCursorIndex)
    }

    @Test
    fun `commit creating an anchor while not listening is ignored`() {
        // The bug scenario: a stale or leaked recognizer keeps emitting after
        // the session should be dead. Applying it would overwrite user typing
        // on every keystroke — "one letter then blocked".
        val apply = applyDictationCommit(
            currentText = "user typed this",
            cursorStart = 15,
            anchor = -1,
            lastLen = 0,
            commitText = "rogue dictation",
            isListening = false,
        )

        assertNull(apply)
    }

    @Test
    fun `commit extending an existing anchor after listening ended still applies`() {
        // The refined trailing Final on a normal stop is emitted before the
        // isListening flip but processed after it — it must not be dropped.
        val apply = applyDictationCommit(
            currentText = "helo",
            cursorStart = 4,
            anchor = 0,
            lastLen = 4,
            commitText = "hello",
            isListening = false,
        )

        assertNotNull(apply)
        assertEquals("hello", apply!!.newText)
    }

    @Test
    fun `subsequent partial replaces the dictated region and preserves surrounding text`() {
        val apply = applyDictationCommit(
            currentText = "say hello world end",
            cursorStart = 15,
            anchor = 4,
            lastLen = 11, // "hello world"
            commitText = "hello there world",
            isListening = true,
        )

        assertNotNull(apply)
        assertEquals("say hello there world end", apply!!.newText)
        assertEquals(4, apply.newAnchor)
        assertEquals(17, apply.newLastLen)
        assertEquals(21, apply.newCursorIndex)
    }

    @Test
    fun `out-of-range anchor from shrunken text is clamped safely`() {
        val apply = applyDictationCommit(
            currentText = "ab",
            cursorStart = 2,
            anchor = 10,
            lastLen = 5,
            commitText = "x",
            isListening = true,
        )

        assertNotNull(apply)
        assertEquals("abx", apply!!.newText)
        assertEquals(3, apply.newCursorIndex)
    }
}
