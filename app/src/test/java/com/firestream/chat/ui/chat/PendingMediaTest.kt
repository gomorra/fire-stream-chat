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

    // ── landing an edit ───────────────────────────────────────────────────────

    private fun edited(steps: Int, cursor: Int = steps) = PendingMedia(
        originalUri = uri("content://pick/1"),
        mimeType = "image/jpeg",
        editHistory = (1..steps).map { "file:///edits/step$it.jpg" },
        editCursor = cursor,
    )

    @Test
    fun `landing an edit on an untouched pick starts the history`() {
        val landed = PendingMedia(uri("content://pick/1"), "image/jpeg")
            .landEdit(uri("file:///edits/step1.jpg"))

        assertEquals(listOf("file:///edits/step1.jpg"), landed.item.editHistory)
        assertEquals(1, landed.item.editCursor)
        assertEquals("file:///edits/step1.jpg", landed.item.uri.toString())
        assertTrue(landed.abandoned.isEmpty())
    }

    @Test
    fun `landing an edit at the top appends and abandons nothing`() {
        val landed = edited(steps = 3).landEdit(uri("file:///edits/step4.jpg"))

        assertEquals(4, landed.item.editHistory.size)
        assertEquals(4, landed.item.editCursor)
        assertEquals("file:///edits/step4.jpg", landed.item.uri.toString())
        assertTrue(landed.abandoned.isEmpty())
    }

    @Test
    fun `landing an edit mid-history truncates and abandons exactly the tail`() {
        // Four steps, undone back to the first: what redo was holding is
        // discarded, and those are the files nothing can reach any more.
        val landed = edited(steps = 4, cursor = 1).landEdit(uri("file:///edits/new.jpg"))

        assertEquals(
            listOf("file:///edits/step1.jpg", "file:///edits/new.jpg"),
            landed.item.editHistory,
        )
        assertEquals(
            listOf("file:///edits/step2.jpg", "file:///edits/step3.jpg", "file:///edits/step4.jpg"),
            landed.abandoned,
        )
    }

    @Test
    fun `redo is unavailable after an edit truncated the tail`() {
        val landed = edited(steps = 4, cursor = 1).landEdit(uri("file:///edits/new.jpg"))

        // The cursor sits at the top of the new, shorter history, so there is
        // nothing forward of it — linear history, not a tree.
        assertEquals(landed.item.editHistory.size, landed.item.editCursor)
    }

    @Test
    fun `landing an edit from the original abandons the whole history`() {
        val landed = edited(steps = 3, cursor = 0).landEdit(uri("file:///edits/new.jpg"))

        assertEquals(listOf("file:///edits/new.jpg"), landed.item.editHistory)
        assertEquals(1, landed.item.editCursor)
        assertEquals(3, landed.abandoned.size)
    }

    @Test
    fun `the ninth step pushes the oldest one out and hands it back for deletion`() {
        val full = edited(steps = PendingMedia.MAX_EDIT_STEPS)

        val landed = full.landEdit(uri("file:///edits/step9.jpg"))

        assertEquals(PendingMedia.MAX_EDIT_STEPS, landed.item.editHistory.size)
        assertEquals(PendingMedia.MAX_EDIT_STEPS, landed.item.editCursor)
        assertEquals("file:///edits/step2.jpg", landed.item.editHistory.first())
        assertEquals("file:///edits/step9.jpg", landed.item.uri.toString())
        // Undo reaches one step less far, but the step the user is on is intact.
        assertEquals(listOf("file:///edits/step1.jpg"), landed.abandoned)
    }

    // ── the history is a list of files the OS may delete ──────────────────────

    @Test
    fun `a step whose file survives is left alone`() {
        val item = edited(steps = 3, cursor = 2)

        assertEquals(item, item.onSurvivingStep { true })
    }

    @Test
    fun `a vanished step falls back to the nearest surviving one below it`() {
        val item = edited(steps = 4, cursor = 4)
        val gone = setOf("file:///edits/step4.jpg", "file:///edits/step3.jpg")

        val resolved = item.onSurvivingStep { it.toString() !in gone }

        assertEquals(2, resolved.editCursor)
        assertEquals("file:///edits/step2.jpg", resolved.uri.toString())
        // The steps it walked past are dropped, so redo is not left enabled over
        // a hole it could never cross.
        assertEquals(2, resolved.editHistory.size)
    }

    @Test
    fun `an entirely evicted history falls back to the untouched pick`() {
        val item = edited(steps = 3)

        val resolved = item.onSurvivingStep { false }

        assertEquals(0, resolved.editCursor)
        assertEquals("content://pick/1", resolved.uri.toString())
        assertFalse(resolved.hasEdits)
    }

    @Test
    fun `an ordinary undo keeps its redo tail`() {
        // The fallback runs whenever the item changes, so it must not mistake a
        // deliberate undo for an evicted step and eat what redo was holding.
        val item = edited(steps = 3, cursor = 1)

        assertEquals(item, item.onSurvivingStep { true })
    }

    @Test
    fun `showing the original never stats a file`() {
        // At cursor 0 the item is on originalUri, which is the one URI here that
        // is not a cache file — checking it would be asking the wrong question.
        val item = edited(steps = 3, cursor = 0)

        assertEquals(item, item.onSurvivingStep { error("should not be consulted") })
    }
}
