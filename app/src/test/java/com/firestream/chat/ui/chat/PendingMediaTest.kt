package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The edit cursor and the saver that has to carry it through process death.
 *
 * Both are cheap to get subtly wrong and expensive to notice: a saver that
 * drops the history makes undo look broken exactly after a rotation, and one
 * that saves the history but not the cursor silently re-applies edits the user
 * had just undone (`.claude/plans/image-editor.md` §4).
 *
 * Robolectric rather than a mocked `Uri`: the saver's whole job is a
 * `Uri`→`String`→`Uri` round-trip, and stubbing the very parse and `toString`
 * it leans on would test the stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class PendingMediaTest {

    private fun uri(value: String): Uri = Uri.parse(value)

    private val saverScope = SaverScope { true }

    private fun roundTrip(items: List<PendingMedia>): List<PendingMedia> {
        val saved = with(PendingMedia.ListSaver) { saverScope.save(items) }
        return checkNotNull(PendingMedia.ListSaver.restore(checkNotNull(saved)))
    }

    // ── uri derivation ────────────────────────────────────────────────────────

    @Test
    fun `cursor at zero yields the original`() {
        val item = PendingMedia(
            originalUri = uri("content://pick/1"),
            mimeType = "image/jpeg",
            editHistory = listOf("file:///edits/a.jpg", "file:///edits/b.jpg"),
            editCursor = 0,
        )
        assertEquals("content://pick/1", item.uri.toString())
    }

    @Test
    fun `cursor mid-history yields that step, not the newest`() {
        val item = PendingMedia(
            originalUri = uri("content://pick/1"),
            mimeType = "image/jpeg",
            editHistory = listOf("file:///edits/a.jpg", "file:///edits/b.jpg", "file:///edits/c.jpg"),
            editCursor = 2,
        )
        assertEquals("file:///edits/b.jpg", item.uri.toString())
    }

    @Test
    fun `cursor at the top yields the newest step`() {
        val item = PendingMedia(
            originalUri = uri("content://pick/1"),
            mimeType = "image/jpeg",
            editHistory = listOf("file:///edits/a.jpg", "file:///edits/b.jpg"),
            editCursor = 2,
        )
        assertEquals("file:///edits/b.jpg", item.uri.toString())
    }

    @Test
    fun `a cursor past the end of a trimmed history clamps to the newest surviving step`() {
        // cacheDir can be reclaimed between a rotation and its restore, and our
        // own eviction trims the oldest steps deliberately. A cursor pointing at
        // nothing must not blank the page.
        val item = PendingMedia(
            originalUri = uri("content://pick/1"),
            mimeType = "image/jpeg",
            editHistory = listOf("file:///edits/a.jpg"),
            editCursor = 5,
        )
        assertEquals("file:///edits/a.jpg", item.uri.toString())
    }

    @Test
    fun `hasEdits is false for a fresh pick and true once a step exists`() {
        val fresh = PendingMedia(uri("content://pick/1"), "image/jpeg")
        assertFalse(fresh.hasEdits)
        assertTrue(fresh.copy(editHistory = listOf("file:///edits/a.jpg")).hasEdits)
    }

    // ── saver round-trip ──────────────────────────────────────────────────────

    @Test
    fun `saver round-trips a fresh pick unchanged`() {
        val items = listOf(
            PendingMedia(uri("content://pick/1"), "image/jpeg", caption = "hello"),
            PendingMedia(uri("content://pick/2"), "video/mp4"),
        )
        val restored = roundTrip(items)

        assertEquals(2, restored.size)
        assertEquals("content://pick/1", restored[0].originalUri.toString())
        assertEquals("hello", restored[0].caption)
        assertNull(restored[0].isHd)
        assertEquals(emptyList<String>(), restored[0].editHistory)
        assertEquals(0, restored[0].editCursor)
        assertTrue(restored[1].isVideo)
    }

    @Test
    fun `saver round-trips a multi-entry history and its cursor`() {
        val items = listOf(
            PendingMedia(
                originalUri = uri("content://pick/1"),
                mimeType = "image/jpeg",
                caption = "after three edits",
                isHd = true,
                editHistory = listOf(
                    "file:///edits/a.jpg",
                    "file:///edits/b.jpg",
                    "file:///edits/c.jpg",
                ),
                editCursor = 2,
            )
        )
        val restored = roundTrip(items).single()

        assertEquals(3, restored.editHistory.size)
        assertEquals("file:///edits/b.jpg", restored.editHistory[1])
        assertEquals(2, restored.editCursor)
        assertEquals("file:///edits/b.jpg", restored.uri.toString())
        assertEquals(true, restored.isHd)
    }

    @Test
    fun `saver keeps items separable when one has history and the next does not`() {
        // The history is newline-joined into ONE field so the per-item field
        // count stays fixed; an empty history must not eat the following item.
        val items = listOf(
            PendingMedia(
                originalUri = uri("content://pick/1"),
                mimeType = "image/jpeg",
                editHistory = listOf("file:///edits/a.jpg", "file:///edits/b.jpg"),
                editCursor = 1,
            ),
            PendingMedia(uri("content://pick/2"), "image/png", caption = "untouched"),
        )
        val restored = roundTrip(items)

        assertEquals(2, restored.size)
        assertEquals(2, restored[0].editHistory.size)
        assertEquals(emptyList<String>(), restored[1].editHistory)
        assertEquals("untouched", restored[1].caption)
        assertEquals("content://pick/2", restored[1].uri.toString())
    }

    @Test
    fun `saver round-trips a caption containing newlines alongside a history`() {
        // The newline is the history's own separator, so a multi-line caption is
        // the case that would break a naive single-string encoding.
        val items = listOf(
            PendingMedia(
                originalUri = uri("content://pick/1"),
                mimeType = "image/jpeg",
                caption = "line one\nline two\nline three",
                editHistory = listOf("file:///edits/a.jpg"),
                editCursor = 1,
            )
        )
        val restored = roundTrip(items).single()

        assertEquals("line one\nline two\nline three", restored.caption)
        assertEquals(listOf("file:///edits/a.jpg"), restored.editHistory)
        assertEquals(1, restored.editCursor)
    }

    @Test
    fun `saver round-trips all three isHd states`() {
        val items = listOf(
            PendingMedia(uri("content://pick/1"), "image/jpeg", isHd = null),
            PendingMedia(uri("content://pick/2"), "image/jpeg", isHd = true),
            PendingMedia(uri("content://pick/3"), "image/jpeg", isHd = false),
        )
        val restored = roundTrip(items)

        assertNull(restored[0].isHd)
        assertEquals(true, restored[1].isHd)
        assertEquals(false, restored[2].isHd)
    }

    @Test
    fun `restore clamps a cursor the saved history can no longer support`() {
        val flat = listOf(
            "content://pick/1", "image/jpeg", "", "", "file:///edits/a.jpg", "7",
        )
        val restored = checkNotNull(PendingMedia.ListSaver.restore(flat)).single()

        assertEquals(1, restored.editCursor)
        assertEquals("file:///edits/a.jpg", restored.uri.toString())
    }
}
