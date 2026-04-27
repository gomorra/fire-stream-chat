// region: AGENT-NOTE
// PocketBase SSE realtime gateway. ONE shared SSE connection per process; each
// `*Source` adds a topic via [subscribe] and gets back a hot [Flow]<RealtimeEvent>
// scoped to that topic. Subscriptions are managed server-side by re-POSTing the
// full topic set whenever it changes.
//
// Don't put here:
//   - REST verbs — those live in PocketBaseClient.
//   - Mapping events to domain types — that's the Source impls' job (step 6).
//   - AppLifecycleObserver wiring (start on foreground, stop on background) —
//     deferred to step 6 per plan.
// endregion
package com.firestream.chat.data.remote.pocketbase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoded line-event from PocketBase's SSE stream. The two cases the rest of
 * the app cares about:
 *  - [Connected]: server has issued a `clientId`; from now on we may POST
 *    `/api/realtime` to add/remove topics.
 *  - [Record]: a topic event for a subscribed collection / record-id.
 */
sealed interface RealtimeEvent {
    val topic: String

    data class Connected(val clientId: String) : RealtimeEvent {
        override val topic: String get() = META_CONNECT_TOPIC
    }

    /** Generic record-mutation event; [data] is the parsed JSON payload. */
    data class Record(
        override val topic: String,
        val action: String?,
        val data: JSONObject
    ) : RealtimeEvent
}

internal const val META_CONNECT_TOPIC = "__connect__"

@Singleton
class PocketBaseRealtime @Inject constructor(
    private val client: PocketBaseClient
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    private val events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private val subscriptions = mutableSetOf<String>()
    private val subscriptionLock = Mutex()
    private val clientId = MutableStateFlow<String?>(null)

    private var streamJob: Job? = null

    /** Idempotent. Starts (or re-uses) the SSE connection. */
    @Synchronized
    fun connect() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch { runStreamWithBackoff() }
    }

    /** Tear down the SSE connection. Subscribers' Flows complete naturally. */
    @Synchronized
    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        clientId.value = null
        subscriptions.clear()
    }

    /**
     * Subscribe to a PocketBase realtime topic (e.g. `messages` for the whole
     * collection, `messages/abc123` for one record, or `messages?filter=...`
     * for a server-side-filtered slice). Returns a hot [Flow] of events
     * matching that topic.
     */
    fun subscribe(topic: String): Flow<RealtimeEvent> {
        scope.launch {
            subscriptionLock.withLock {
                if (subscriptions.add(topic)) {
                    pushSubscriptionsIfConnected()
                }
            }
        }
        return events.asSharedFlow().filter { it.topic == topic }
    }

    suspend fun unsubscribe(topic: String) {
        subscriptionLock.withLock {
            if (subscriptions.remove(topic)) {
                pushSubscriptionsIfConnected()
            }
        }
    }

    /** POST current subscription set to PB. Caller must hold [subscriptionLock]. */
    private suspend fun pushSubscriptionsIfConnected() {
        val cid = clientId.value ?: return
        val body = JSONObject().apply {
            put("clientId", cid)
            put("subscriptions", subscriptions.toList())
        }
        runCatching { client.post("/api/realtime", body) }
    }

    private suspend fun runStreamWithBackoff() {
        var attempt = 0
        while (true) {
            try {
                openStream()
                attempt = 0
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // backoff: 1s, 2s, 4s, 8s, capped at 30s
                val delayMs = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
                delay(delayMs)
                attempt++
            }
        }
    }

    private suspend fun openStream() {
        val request = Request.Builder()
            .url("${client.baseUrl}/api/realtime")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        withContext(Dispatchers.IO) {
            client.streamingClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw PocketBaseHttpException(response.code, response.message)
                }
                val source = response.body?.source() ?: error("empty SSE body")
                val parser = SseLineParser()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    parser.feed(line)?.let { handleEvent(it) }
                }
            }
        }
    }

    private suspend fun handleEvent(parsed: ParsedSseEvent) {
        // PB encodes the connect handshake as event="PB_CONNECT" data={"clientId":...}
        if (parsed.event == "PB_CONNECT") {
            val cid = runCatching { JSONObject(parsed.data).optString("clientId") }.getOrNull()
            if (!cid.isNullOrEmpty()) {
                clientId.value = cid
                events.emit(RealtimeEvent.Connected(cid))
                subscriptionLock.withLock { pushSubscriptionsIfConnected() }
            }
            return
        }
        // PB record events: event=<topic>, data={"action":"create|update|delete","record":{...}}
        val topic = parsed.event ?: return
        val data = runCatching { JSONObject(parsed.data) }.getOrNull() ?: return
        val action = data.optString("action").takeIf { it.isNotEmpty() }
        events.emit(RealtimeEvent.Record(topic, action, data))
    }
}

/** What a single SSE event looks like once we've assembled its lines. */
internal data class ParsedSseEvent(val event: String?, val data: String)

/**
 * Streaming parser for SSE wire format (per WHATWG EventSource spec, scoped to
 * what PocketBase actually sends): blank line terminates an event, `event:`
 * sets the type, `data:` accumulates payload (newline-joined for multi-line).
 * Comments (`:` prefix), `id:`, and `retry:` are ignored — PB doesn't use them.
 */
internal class SseLineParser {
    private var event: String? = null
    private val data = StringBuilder()

    /** Feed one decoded line. Returns the assembled event when terminated. */
    fun feed(line: String): ParsedSseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event == null) return null
            val out = ParsedSseEvent(event, data.toString())
            event = null
            data.clear()
            return out
        }
        if (line.startsWith(":")) return null
        val sep = line.indexOf(':')
        val (field, value) = if (sep < 0) {
            line to ""
        } else {
            line.substring(0, sep) to line.substring(sep + 1).trimStart(' ')
        }
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            // id/retry intentionally ignored.
        }
        return null
    }
}
