package com.firestream.chat.data.util

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The process-wide ceiling on how many media items may be decoded, compressed or
 * transcoded at once.
 *
 * Each of those operations holds a full decoded bitmap (or a transcoder session)
 * in memory, so the number running concurrently — not the number queued — is
 * what decides whether the app survives a large batch. Multi-select send and the
 * share sheet can both queue many items, and they can run at the same time, so
 * no single caller is in a position to enforce this: a per-batch or per-screen
 * bound leaves two batches jointly unbounded. It therefore lives on a
 * [Singleton] that both [ImageCompressor] and [VideoTranscoder] hold.
 *
 * Callers stay responsible for *ordering* (send a batch sequentially so the
 * items land in the order the user picked them) — that is a per-batch concern
 * and deliberately not enforced here.
 */
@Singleton
class MediaProcessingLimiter @Inject constructor() {

    private val permits = Semaphore(MAX_CONCURRENT_MEDIA_PROCESSING)

    suspend fun <T> withPermit(block: suspend () -> T): T = permits.withPermit { block() }

    private companion object {
        /**
         * Two rather than one so a second item can start while the first is
         * uploading, without ever holding more than two bitmaps at once.
         */
        const val MAX_CONCURRENT_MEDIA_PROCESSING = 2
    }
}
