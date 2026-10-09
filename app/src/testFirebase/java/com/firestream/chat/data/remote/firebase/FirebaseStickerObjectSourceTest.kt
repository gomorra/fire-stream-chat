package com.firestream.chat.data.remote.firebase

import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Which Storage calls a sticker send makes. The object is shared and
 * create-only, so the rule under test is "look first, upload only when missing".
 */
class FirebaseStickerObjectSourceTest {

    private val storage = mockk<FirebaseStorage>()
    private val ref = mockk<StorageReference>()
    private val file = File("/data/files/stickers/abc.webp")
    private val url = mockk<Uri> { every { this@mockk.toString() } returns "https://storage.example/stickers/abc.webp" }

    private lateinit var source: FirebaseStickerObjectSource

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } returns mockk()
        every { storage.reference.child("stickers/abc.webp") } returns ref
        source = FirebaseStickerObjectSource(storage)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun <T> done(result: T? = null, error: Exception? = null): Task<T> = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns error
        every { this@mockk.result } answers { result as T }
    }

    private fun uploaded(error: Exception? = null): UploadTask = mockk {
        every { isComplete } returns true
        every { isCanceled } returns false
        every { exception } returns error
        every { result } returns mockk()
    }

    private fun storageError(code: Int): StorageException = mockk(relaxed = true) { every { errorCode } returns code }

    @Test
    fun `a sticker the backend holds is not uploaded again`() = runTest {
        every { ref.downloadUrl } returns done(url)

        val result = source.ensureUploaded("abc", "webp", "image/webp", file)

        assertEquals("https://storage.example/stickers/abc.webp", result)
        verify(exactly = 0) { ref.putFile(any(), any<StorageMetadata>()) }
    }

    @Test
    fun `a sticker the backend does not hold is uploaded under its id and type`() = runTest {
        val notFound = storageError(StorageException.ERROR_OBJECT_NOT_FOUND)
        every { ref.downloadUrl } returns done(error = notFound) andThen done(url)
        val metadata = slot<StorageMetadata>()
        every { ref.putFile(any(), capture(metadata)) } returns uploaded()

        val result = source.ensureUploaded("abc", "webp", "image/webp", file)

        assertEquals("https://storage.example/stickers/abc.webp", result)
        assertEquals("image/webp", metadata.captured.contentType)
        verify(exactly = 1) { ref.putFile(any(), any<StorageMetadata>()) }
    }

    // Two devices send a new sticker at once. The rules let only one create the
    // object and refuse the other's upload, and the object that is there is the sticker.
    @Test
    fun `an upload the rules refuse because the object exists by now is not a failure`() = runTest {
        val notFound = storageError(StorageException.ERROR_OBJECT_NOT_FOUND)
        every { ref.downloadUrl } returns done(error = notFound) andThen done(url)
        every { ref.putFile(any(), any<StorageMetadata>()) } returns uploaded(storageError(StorageException.ERROR_NOT_AUTHORIZED))

        val result = source.ensureUploaded("abc", "webp", "image/webp", file)

        assertEquals("https://storage.example/stickers/abc.webp", result)
    }

    @Test
    fun `an upload that fails with the object still missing throws the upload's error`() = runTest {
        val notFound = storageError(StorageException.ERROR_OBJECT_NOT_FOUND)
        val refused = storageError(StorageException.ERROR_QUOTA_EXCEEDED)
        every { ref.downloadUrl } returns done(error = notFound)
        every { ref.putFile(any(), any<StorageMetadata>()) } returns uploaded(refused)

        val thrown = runCatching { source.ensureUploaded("abc", "webp", "image/webp", file) }.exceptionOrNull()

        assertSame(refused, thrown)
    }

    @Test
    fun `the url of a sticker known only by its id is looked up, and a missing object is no url`() = runTest {
        val notFound = storageError(StorageException.ERROR_OBJECT_NOT_FOUND)
        every { ref.downloadUrl } returns done(url) andThen done(error = notFound)

        assertEquals("https://storage.example/stickers/abc.webp", source.urlIfPresent("abc", "webp"))
        assertEquals(null, source.urlIfPresent("abc", "webp"))
        verify(exactly = 0) { ref.putFile(any(), any<StorageMetadata>()) }
    }

    @Test
    fun `a lookup that fails for another reason is not taken for a missing object`() = runTest {
        val offline = storageError(StorageException.ERROR_RETRY_LIMIT_EXCEEDED)
        every { ref.downloadUrl } returns done(error = offline)

        val thrown = runCatching { source.ensureUploaded("abc", "webp", "image/webp", file) }.exceptionOrNull()

        assertSame(offline, thrown)
        verify(exactly = 0) { ref.putFile(any(), any<StorageMetadata>()) }
    }
}
