package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.SendErrorClassifier
import com.firestream.chat.data.remote.source.SendFailure
import javax.inject.Inject

/**
 * v0 stub: PocketBase has no error types of its own yet, so every attempt is
 * sorted by the backend-neutral rules alone (IO is transient, the rest is
 * permanent). The offline outbox plan keeps this flavor compiling only.
 */
class PocketBaseSendErrorClassifier @Inject constructor() : SendErrorClassifier {
    override fun classifyBackendError(error: Throwable): SendFailure? = null
}
