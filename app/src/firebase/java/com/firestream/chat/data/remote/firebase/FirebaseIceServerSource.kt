package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.IceServerSource
import com.firestream.chat.domain.model.IceServerData
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the `getTurnCredentials` function for the Cloudflare relay's servers and a login that is
 * good for a day. The function answers only a signed-in user. Nothing here is logged: the answer
 * is a credential.
 */
@Singleton
class FirebaseIceServerSource @Inject constructor(
    private val functions: FirebaseFunctions
) : IceServerSource {

    override suspend fun fetchIceServers(): List<IceServerData> {
        val result = functions.getHttpsCallable(FUNCTION)
            .withTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .call()
            .await()
        return parseIceServers(result.getData())
    }

    internal companion object {
        const val FUNCTION = "getTurnCredentials"

        /** A caller stops waiting long before this. It only keeps a dead request from lingering. */
        const val CALL_TIMEOUT_SECONDS = 15L

        private val SCHEMES = listOf("stun:", "turn:", "turns:")

        /**
         * Reads `{ iceServers: [ … ] }` as the function returns it. `urls` is a string or a list.
         * Anything that is not a STUN or TURN URL is dropped, and so is an entry left without one,
         * and a relay entry without a login. WebRTC refuses the whole connection over one entry it
         * cannot use, and the provider would keep such a set for hours.
         */
        fun parseIceServers(data: Any?): List<IceServerData> {
            val entries = (data as? Map<*, *>)?.get("iceServers") as? List<*> ?: return emptyList()
            return entries.mapNotNull { entry ->
                val fields = entry as? Map<*, *> ?: return@mapNotNull null
                val urls = when (val raw = fields["urls"]) {
                    is String -> listOf(raw)
                    is List<*> -> raw.filterIsInstance<String>()
                    else -> emptyList()
                }.filter { url -> SCHEMES.any { url.startsWith(it) } }
                if (urls.isEmpty()) return@mapNotNull null
                val username = (fields["username"] as? String)?.takeIf { it.isNotEmpty() }
                val credential = (fields["credential"] as? String)?.takeIf { it.isNotEmpty() }
                // A relay without a login is one more thing WebRTC refuses the connection over.
                val needsLogin = urls.any { !it.startsWith("stun:") }
                if (needsLogin && (username == null || credential == null)) return@mapNotNull null
                IceServerData(urls, username, credential)
            }
        }
    }
}
