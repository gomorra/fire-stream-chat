package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.firebase.FirebaseIceServerSource.Companion.parseIceServers
import com.firestream.chat.domain.model.IceServerData
import org.junit.Assert.assertEquals
import org.junit.Test

/** What `getTurnCredentials` returns, as the functions SDK decodes it: maps, lists and strings. */
class FirebaseIceServerSourceTest {

    @Test
    fun `Cloudflare's list of a STUN entry and a relay entry is read as it is`() {
        val data = mapOf(
            "iceServers" to listOf(
                mapOf("urls" to listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53")),
                mapOf(
                    "urls" to listOf(
                        "turn:turn.cloudflare.com:3478?transport=udp",
                        "turns:turn.cloudflare.com:443?transport=tcp"
                    ),
                    "username" to "user",
                    "credential" to "secret"
                )
            )
        )

        assertEquals(
            listOf(
                IceServerData(listOf("stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53")),
                IceServerData(
                    listOf("turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:443?transport=tcp"),
                    "user",
                    "secret"
                )
            ),
            parseIceServers(data)
        )
    }

    @Test
    fun `an entry with one url as a string is read too`() {
        val data = mapOf(
            "iceServers" to listOf(
                mapOf(
                    "urls" to "turn:turn.cloudflare.com:3478?transport=udp",
                    "username" to "user",
                    "credential" to "secret"
                )
            )
        )

        assertEquals(
            listOf(IceServerData(listOf("turn:turn.cloudflare.com:3478?transport=udp"), "user", "secret")),
            parseIceServers(data)
        )
    }

    // WebRTC refuses the whole connection over one URL it cannot read.
    @Test
    fun `a url that is not STUN or TURN is dropped, and an entry left without one goes with it`() {
        val data = mapOf(
            "iceServers" to listOf(
                mapOf("urls" to listOf("https://example.com", 7, "stun:stun.cloudflare.com:3478")),
                mapOf("urls" to listOf("https://example.com"), "username" to "user", "credential" to "secret"),
                mapOf("username" to "user", "credential" to "secret"),
                "not an entry"
            )
        )

        assertEquals(listOf(IceServerData(listOf("stun:stun.cloudflare.com:3478"))), parseIceServers(data))
    }

    // WebRTC builds no connection on a relay without a login, and the set would be kept for hours.
    @Test
    fun `a relay entry without a full login is dropped, and the STUN entry beside it stays`() {
        val turn = listOf("turn:turn.cloudflare.com:3478?transport=udp")
        val data = mapOf(
            "iceServers" to listOf(
                mapOf("urls" to listOf("stun:stun.cloudflare.com:3478")),
                mapOf("urls" to turn),
                mapOf("urls" to turn, "username" to "user"),
                mapOf("urls" to turn, "username" to "", "credential" to "secret"),
                mapOf("urls" to turn, "username" to "user", "credential" to 7)
            )
        )

        assertEquals(listOf(IceServerData(listOf("stun:stun.cloudflare.com:3478"))), parseIceServers(data))
    }

    @Test
    fun `an answer of another shape gives nothing`() {
        assertEquals(emptyList<IceServerData>(), parseIceServers(null))
        assertEquals(emptyList<IceServerData>(), parseIceServers("iceServers"))
        assertEquals(emptyList<IceServerData>(), parseIceServers(mapOf("servers" to emptyList<Any>())))
        assertEquals(emptyList<IceServerData>(), parseIceServers(mapOf("iceServers" to 7)))
        assertEquals(
            emptyList<IceServerData>(),
            parseIceServers(mapOf("iceServers" to mapOf("urls" to "stun:stun.cloudflare.com:3478")))
        )
    }
}
