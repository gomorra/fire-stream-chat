package com.firestream.chat.data.util

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and wraps the outcome in a [Result]. Any thrown [Exception] is
 * caught and returned as [Result.failure]. Replaces the
 * `try { Result.success(...) } catch (e: Exception) { Result.failure(e) }`
 * boilerplate that otherwise appears in every repository method.
 *
 * Declared `inline` so the lambda body is inlined at the call site — suspend
 * callers can invoke suspend functions inside [block] transparently.
 *
 * Note: matches the pre-existing repo error-handling behavior exactly —
 * [Exception] is caught (including [kotlinx.coroutines.CancellationException]),
 * while other [Throwable]s propagate.
 */
internal inline fun <T> resultOf(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * Rethrows this throwable if it is a [CancellationException]. Call it first in
 * catch blocks that swallow-and-log an error, so they don't also swallow
 * coroutine cancellation (which must propagate for structured concurrency).
 */
internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
