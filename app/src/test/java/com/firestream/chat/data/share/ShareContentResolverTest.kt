package com.firestream.chat.data.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.firestream.chat.domain.model.SharedContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Multi-share resolution. The behaviour under test is partial-failure
 * tolerance: a SEND_MULTIPLE intent routinely carries a URI that has gone stale
 * or was never readable, and losing the other nine images to it is the bug this
 * guards against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ShareContentResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var resolver: ShareContentResolver

    private val good1 = Uri.parse("content://media/external/images/1")
    private val bad = Uri.parse("content://media/external/images/2")
    private val good2 = Uri.parse("content://media/external/images/3")

    @Before
    fun setUp() {
        contentResolver = mockk(relaxed = true)
        context = mockk()
        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns tempFolder.root

        // relaxed = true would hand back a mock, not null, for these nullable
        // returns — stub them explicitly so the real code paths are exercised.
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
        every { contentResolver.getType(any()) } returns "image/jpeg"
        every { contentResolver.openInputStream(good1) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { contentResolver.openInputStream(good2) } returns ByteArrayInputStream(byteArrayOf(4, 5, 6))
        every { contentResolver.openInputStream(bad) } throws SecurityException("permission revoked")

        resolver = ShareContentResolver(context)
    }

    private fun sendMultipleIntent(vararg uris: Uri) = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
    }

    @Test
    fun `an unreadable item is skipped and the rest still resolve`() = runTest {
        val content = resolver.resolve(sendMultipleIntent(good1, bad, good2))

        assertTrue(content is SharedContent.Media)
        val items = (content as SharedContent.Media).items
        assertEquals("only the unreadable item should be dropped", 2, items.size)
        assertTrue("every surviving item must be cached", items.all { it.cachedUri.startsWith("file://") })
    }

    @Test
    fun `resolution order matches the order the items were shared`() = runTest {
        every { contentResolver.openInputStream(bad) } returns ByteArrayInputStream(byteArrayOf(7))

        val items = (resolver.resolve(sendMultipleIntent(good1, bad, good2)) as SharedContent.Media).items

        assertEquals(3, items.size)
        // Each cache file holds its source's bytes, so the payloads prove order.
        assertEquals(
            listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(7), listOf<Byte>(4, 5, 6)),
            items.map { java.io.File(Uri.parse(it.cachedUri).path!!).readBytes().toList() }
        )
    }

    @Test(expected = SecurityException::class)
    fun `a share where nothing is readable still reports the failure`() = runTest {
        every { contentResolver.openInputStream(any()) } throws SecurityException("permission revoked")

        resolver.resolve(sendMultipleIntent(good1, bad, good2))
    }

    @Test
    fun `a wildcard intent type never becomes the item mime type`() = runTest {
        // A provider that reports nothing: the intent's own "image/*" is useless
        // as an upload mime type, so the file name has to carry it.
        every { contentResolver.getType(any()) } returns null
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null

        val items = (resolver.resolve(sendMultipleIntent(good1)) as SharedContent.Media).items

        assertTrue(
            "a wildcard mime type would break the upload: ${items[0].mimeType}",
            !items[0].mimeType.contains('*')
        )
    }

    @Test
    fun `an empty stream list is nothing to share rather than an error`() = runTest {
        val content = resolver.resolve(Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" })

        assertEquals(null, content)
    }

    @Test(expected = IOException::class)
    fun `a null input stream is reported rather than silently dropped`() = runTest {
        every { contentResolver.openInputStream(any()) } returns null

        resolver.resolve(sendMultipleIntent(good1))
    }
}
