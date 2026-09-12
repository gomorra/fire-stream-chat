package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import com.google.firebase.storage.StorageException
import javax.inject.Inject

/**
 * Firestore and Storage failures, by status code. Transient is what a retry
 * with backoff can fix: the service being unavailable or overloaded, a
 * deadline, an aborted transaction, Storage giving up on a flaky link. A
 * permission, a missing document or bucket, a bad argument and a checksum
 * mismatch would fail the same way every time.
 */
class FirebaseSendErrorClassifier @Inject constructor() : SendErrorClassifier {

    override fun classifyBackendError(error: Throwable): SendFailure? = when (error) {
        is FirebaseFirestoreException -> when (error.code) {
            Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED, Code.ABORTED, Code.RESOURCE_EXHAUSTED,
            Code.INTERNAL, Code.UNKNOWN, Code.CANCELLED -> SendFailure.TRANSIENT
            else -> SendFailure.PERMANENT
        }
        is StorageException -> when (error.errorCode) {
            StorageException.ERROR_RETRY_LIMIT_EXCEEDED, StorageException.ERROR_UNKNOWN,
            StorageException.ERROR_CANCELED -> SendFailure.TRANSIENT
            else -> SendFailure.PERMANENT
        }
        is FirebaseNetworkException -> SendFailure.TRANSIENT
        else -> null
    }
}
