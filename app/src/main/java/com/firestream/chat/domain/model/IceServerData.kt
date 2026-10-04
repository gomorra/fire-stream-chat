package com.firestream.chat.domain.model

/**
 * One entry of the server list a call's connection is built with: STUN servers, which need no
 * login, or a TURN relay with the short-lived login the backend handed out.
 *
 * @param urls `stun:`, `turn:` or `turns:` URLs. Never empty.
 * @param username the relay's login, or null for STUN.
 * @param credential the relay's password, or null for STUN.
 */
data class IceServerData(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
) {
    /** Without the login, so an entry that ends up in a log line gives nothing away. */
    override fun toString(): String =
        "IceServerData(urls=$urls, login=${if (username != null || credential != null) "set" else "none"})"
}
