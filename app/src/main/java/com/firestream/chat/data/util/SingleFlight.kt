package com.firestream.chat.data.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Collapses concurrent calls for the same key onto one execution: the first
 * caller runs [block], everyone who arrives while it is still running awaits
 * that same result instead of starting their own.
 *
 * The idiom itself — `ConcurrentHashMap<K, CompletableDeferred<V>>` plus
 * `putIfAbsent`/`await`/`complete` — was already hand-written in
 * [MediaFileManager] and [ProfileImageManager]; this is the shared form. It
 * deliberately does *not* retain finished results: whether an answer is worth
 * caching, and for how long, is the caller's policy, not a property of
 * de-duplicating in-flight work.
 *
 * Failures propagate to every waiter and leave nothing behind, so the next
 * caller retries rather than inheriting a stale exception.
 *
 * A cancellation is not a failure of the work. When the first caller is
 * cancelled, a waiter runs [block] itself, or joins the waiter that got there
 * first. A waiter that is cancelled stops waiting and takes nobody with it.
 */
class SingleFlight<K : Any, V> {

    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            val pending = CompletableDeferred<V>()
            val running = inFlight.putIfAbsent(key, pending) ?: return runFirst(key, pending, block)
            try {
                return running.await()
            } catch (e: CancellationException) {
                // This caller's own cancellation goes on. Any other was the first caller's,
                // which says nothing about the work, so it is done here.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun runFirst(key: K, pending: CompletableDeferred<V>, block: suspend () -> V): V {
        val result = try {
            block()
        } catch (e: Throwable) {
            inFlight.remove(key)
            pending.completeExceptionally(e)
            throw e
        }
        inFlight.remove(key)
        pending.complete(result)
        return result
    }
}
