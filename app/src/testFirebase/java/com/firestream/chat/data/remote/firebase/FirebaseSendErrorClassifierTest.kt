package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.SendFailure
import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.domain.model.RecipientBlockedException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.google.firebase.storage.StorageException
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** The verdict table behind `OutboxWorker`'s retry-or-fail decision, backend codes and neutral rules alike. */
class FirebaseSendErrorClassifierTest {

    private val classifier = FirebaseSendErrorClassifier()

    private fun firestore(code: Code) = FirebaseFirestoreException("status", code)

    private fun storage(code: Int) = mockk<StorageException> { every { errorCode } returns code }

    private fun assertTransient(error: Throwable) = assertEquals(error.toString(), SendFailure.TRANSIENT, classifier.classify(error))

    private fun assertPermanent(error: Throwable) = assertEquals(error.toString(), SendFailure.PERMANENT, classifier.classify(error))

    @Test
    fun `an unavailable, overloaded or slow backend is transient`() {
        listOf(Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED, Code.ABORTED, Code.RESOURCE_EXHAUSTED, Code.INTERNAL, Code.UNKNOWN)
            .forEach { assertTransient(firestore(it)) }
    }

    @Test
    fun `a permission, a missing document or a bad argument is permanent`() {
        listOf(Code.PERMISSION_DENIED, Code.NOT_FOUND, Code.INVALID_ARGUMENT, Code.FAILED_PRECONDITION, Code.UNAUTHENTICATED)
            .forEach { assertPermanent(firestore(it)) }
    }

    @Test
    fun `Storage giving up on a flaky link is transient, a refused or missing object is not`() {
        assertTransient(storage(StorageException.ERROR_RETRY_LIMIT_EXCEEDED))
        assertTransient(storage(StorageException.ERROR_UNKNOWN))
        assertPermanent(storage(StorageException.ERROR_NOT_AUTHORIZED))
        assertPermanent(storage(StorageException.ERROR_OBJECT_NOT_FOUND))
        assertPermanent(storage(StorageException.ERROR_QUOTA_EXCEEDED))
    }

    @Test
    fun `plain IO, including a lost acknowledgement, is transient`() {
        assertTransient(IOException("Message msg1 not acknowledged within 30000 ms — offline?"))
        assertTransient(java.net.SocketTimeoutException("timeout"))
    }

    @Test
    fun `an unreadable input is permanent even though it is an IOException`() {
        assertPermanent(FileNotFoundException("/outbox/msg1.jpg"))
    }

    @Test
    fun `a blocked recipient, a media limit and a refused row are permanent`() {
        assertPermanent(RecipientBlockedException())
        assertPermanent(MediaLimitException("too long"))
        assertPermanent(IllegalStateException("Send not supported for message type TIMER"))
    }

    @Test
    fun `a wrapped backend or IO error is judged by its cause`() {
        assertTransient(RuntimeException("upload failed", firestore(Code.UNAVAILABLE)))
        assertTransient(RuntimeException("upload failed", IOException("reset")))
        assertPermanent(RuntimeException("upload failed", firestore(Code.PERMISSION_DENIED)))
    }

    @Test
    fun `an error nobody recognises is permanent`() {
        assertPermanent(RuntimeException("transcode boom"))
    }
}
