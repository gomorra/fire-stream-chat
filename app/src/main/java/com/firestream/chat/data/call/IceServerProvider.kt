package com.firestream.chat.data.call

import android.util.Log
import com.firestream.chat.data.remote.source.IceServerSource
import com.firestream.chat.data.util.SingleFlight
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.IceServerData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The servers a call's connection is built with: the relay's, with the login the backend hands
 * out, or public STUN servers alone when the backend gave none.
 *
 * A fetched set is kept for [KEEP_MS]. Its login is good for twice that, so a call that starts on
 * a kept set never outlives it. A caller waits at most [WAIT_MS], and then gets [STUN_ONLY]: a call
 * without a relay still connects on most networks, and a call that waits does not ring.
 *
 * The fetch runs on the application scope and not in the caller. A caller that stops waiting does
 * not cancel it, so the answer still arrives for the next call, and callers that ask at the same
 * time share one request. A fetch that failed is not repeated for [RETRY_AFTER_MS], so the side
 * that answers does not wait a second time for what just failed during the ring.
 *
 * The login is never logged.
 */
@Singleton
class IceServerProvider internal constructor(
    private val source: IceServerSource,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long
) {

    @Inject constructor(
        source: IceServerSource,
        @ApplicationScope scope: CoroutineScope
    ) : this(source, scope, System::currentTimeMillis)

    /** The last answer and how long it stands: a fetched set, or [STUN_ONLY] after a failure. */
    private class Kept(val servers: List<IceServerData>, val atMs: Long, val forMs: Long)

    @Volatile private var kept: Kept? = null
    private val fetches = SingleFlight<Unit, List<IceServerData>>()

    /**
     * What [get] answers without waiting, or null when it would fetch. The side that answers
     * opens its session at once on it.
     */
    // "in 0 until": a clock that was set back must not keep an answer for good.
    fun settled(): List<IceServerData>? =
        kept?.takeIf { nowMs() - it.atMs in 0 until it.forMs }?.servers

    /**
     * The kept set, or [STUN_ONLY] when there is none. Never waits. For the moment between the
     * ring and the offer, where nothing may wait: whoever rings calls [get] before that.
     */
    fun current(): List<IceServerData> = settled() ?: STUN_ONLY

    /** The kept set, or a fetched one, or [STUN_ONLY] after a failure or [WAIT_MS]. Never throws. */
    suspend fun get(): List<IceServerData> {
        settled()?.let { return it }
        val fetch = scope.async { fetches.run(Unit) { fetch() } }
        return withTimeoutOrNull(WAIT_MS) { fetch.await() } ?: run {
            Log.w(TAG, "No relay servers in time. This call uses STUN only.")
            STUN_ONLY
        }
    }

    /** Start a fetch if none is kept, and return at once. */
    fun warm() {
        scope.launch { get() }
    }

    /** Runs once for all callers of the moment. Never throws, cancellation aside. */
    private suspend fun fetch(): List<IceServerData> {
        // A fetch that ended between a caller's look at the kept answer and this one's start.
        settled()?.let { return it }
        val servers = try {
            source.fetchIceServers()
        } catch (e: Exception) {
            // Only when this fetch itself is cancelled. A request the backend's client cancelled
            // is a failure like any other, and must not reach a caller as its own cancellation.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            Log.w(TAG, "Could not fetch the relay servers. Calls use STUN only for now.", e)
            emptyList()
        }
        val answer = if (servers.isEmpty()) {
            Kept(STUN_ONLY, nowMs(), RETRY_AFTER_MS)
        } else {
            Kept(servers, nowMs(), KEEP_MS)
        }
        kept = answer
        return answer.servers
    }


    companion object {
        private const val TAG = "IceServerProvider"

        internal const val KEEP_MS = 12L * 60 * 60 * 1000
        internal const val WAIT_MS = 3_000L
        internal const val RETRY_AFTER_MS = 60_000L

        /** What a call runs on without the relay: it connects wherever a direct path exists. */
        val STUN_ONLY: List<IceServerData> = listOf(
            IceServerData(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"))
        )
    }
}
