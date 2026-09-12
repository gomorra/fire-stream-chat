package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.MediaLimitException
import com.firestream.chat.domain.model.RecipientBlockedException
import java.io.FileNotFoundException
import java.io.IOException

/** What `OutboxWorker` does with a failed attempt. */
enum class SendFailure {
    /** Worth another attempt with backoff: the network, the backend's availability, a lost ack. */
    TRANSIENT,

    /** No attempt would fare better: the row is marked FAILED and waits for a manual retry. */
    PERMANENT,
}

/**
 * Sorts the failure of a send attempt into [SendFailure.TRANSIENT] or
 * [SendFailure.PERMANENT].
 *
 * The backend-neutral part lives here in [classify]: input and policy errors
 * are permanent, plain IO is transient. Each backend adds its own error types
 * through [classifyBackendError] — Firestore's status codes, Storage's error
 * codes — and is asked before the neutral rules, walking the cause chain from
 * the outside in so a wrapped backend error is still recognised. Anything
 * nobody recognises is permanent: a retry loop on an exception nobody
 * understands only delays the retry button.
 */
interface SendErrorClassifier {

    /** The backend's verdict on [error] itself — not its causes — or `null` when it is not a backend error. */
    fun classifyBackendError(error: Throwable): SendFailure?

    fun classify(error: Throwable): SendFailure {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            classifyBackendError(current)?.let { return it }
            neutralVerdict(current)?.let { return it }
            current = current.cause
        }
        return SendFailure.PERMANENT
    }

    companion object {
        private const val MAX_CAUSE_DEPTH = 8

        /** The backend-neutral verdicts; `null` when [error] is none of these types. */
        fun neutralVerdict(error: Throwable): SendFailure? = when (error) {
            // The user, the input or the row is the problem, not the connection.
            is RecipientBlockedException, is MediaLimitException, is FileNotFoundException -> SendFailure.PERMANENT
            // No network, a socket that died, an ack that never came (the timeout in MessageSource).
            is IOException -> SendFailure.TRANSIENT
            // A row the pipeline refuses: unsupported type, no recorded target, no local file.
            is IllegalStateException, is IllegalArgumentException -> SendFailure.PERMANENT
            else -> null
        }
    }
}
