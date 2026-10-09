package com.firestream.chat.data.call

/**
 * The network path ICE chose for a connection, read from the selected candidate pair. A path is
 * relayed when either end of the pair is a TURN relay candidate; every other pair (host, server
 * reflexive, peer reflexive) carries media directly between the two phones.
 *
 * @param localType the `typ` of this side's candidate (`host`, `srflx`, `prflx`, `relay`), or null
 *   when the candidate line carries none.
 * @param remoteType the same for the remote side's candidate.
 * @param localRelay the URL of the server that gave this side its relay candidate, such as
 *   `turn:turn.cloudflare.com:3478?transport=udp`. Null when this side's candidate is no relay,
 *   or WebRTC did not say. The other side's relay is never known here. A URL carries no login.
 */
internal data class IcePath(
    val localType: String?,
    val remoteType: String?,
    val localRelay: String?
) {

    val isRelayed: Boolean get() = localType == RELAY || remoteType == RELAY

    /**
     * The line [PeerSession] logs, e.g. `relayed (local relay, remote srflx) through
     * turn:turn.cloudflare.com:3478?transport=udp`. A pair with a type missing and no relay in it
     * reads `unknown`, since the missing end could be the relay.
     */
    fun describe(): String {
        val kind = when {
            isRelayed -> "relayed"
            localType == null || remoteType == null -> UNKNOWN
            else -> "direct"
        }
        val through = localRelay?.let { " through $it" }.orEmpty()
        return "$kind (local ${localType ?: UNKNOWN}, remote ${remoteType ?: UNKNOWN})$through"
    }

    companion object {
        private const val RELAY = "relay"
        private const val UNKNOWN = "unknown"
        private val TYPE = Regex("""\btyp (\w+)""")

        /**
         * Reads the path from the two candidate lines of the selected pair.
         *
         * @param localServerUrl the server that gathered this side's candidate, as WebRTC reports
         *   it. Kept only for a relay candidate: a server-reflexive one names its STUN server.
         */
        fun of(localSdp: String?, remoteSdp: String?, localServerUrl: String? = null): IcePath {
            val localType = candidateType(localSdp)
            val relay = localServerUrl?.takeIf { localType == RELAY && it.isNotBlank() }
            return IcePath(localType, candidateType(remoteSdp), relay)
        }

        private fun candidateType(sdp: String?): String? =
            sdp?.let { TYPE.find(it)?.groupValues?.get(1) }
    }
}
