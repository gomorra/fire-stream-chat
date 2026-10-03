package com.firestream.chat.data.call

/**
 * The network path ICE chose for a connection, read from the selected candidate pair. A path is
 * relayed when either end of the pair is a TURN relay candidate; every other pair (host, server
 * reflexive, peer reflexive) carries media directly between the two phones.
 *
 * @param localType the `typ` of this side's candidate (`host`, `srflx`, `prflx`, `relay`), or null
 *   when the candidate line carries none.
 * @param remoteType the same for the remote side's candidate.
 */
internal data class IcePath(val localType: String?, val remoteType: String?) {

    val isRelayed: Boolean get() = localType == RELAY || remoteType == RELAY

    /**
     * The line [PeerSession] logs, e.g. `relayed (local relay, remote srflx)`. A pair with a type
     * missing and no relay in it reads `unknown`, since the missing end could be the relay.
     */
    fun describe(): String {
        val kind = when {
            isRelayed -> "relayed"
            localType == null || remoteType == null -> UNKNOWN
            else -> "direct"
        }
        return "$kind (local ${localType ?: UNKNOWN}, remote ${remoteType ?: UNKNOWN})"
    }

    companion object {
        private const val RELAY = "relay"
        private const val UNKNOWN = "unknown"
        private val TYPE = Regex("""\btyp (\w+)""")

        /** Reads the path from the two candidate lines of the selected pair. */
        fun of(localSdp: String?, remoteSdp: String?): IcePath =
            IcePath(candidateType(localSdp), candidateType(remoteSdp))

        private fun candidateType(sdp: String?): String? =
            sdp?.let { TYPE.find(it)?.groupValues?.get(1) }
    }
}
