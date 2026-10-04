package com.firestream.chat.domain.util

import java.net.URI
import java.net.URISyntaxException

/**
 * Which urls are Klipy's media. A message whose `mediaUrl` is one points at
 * Klipy: it is loaded from there by every device that shows it, and no device
 * keeps a copy of the file (Klipy's integration requirements).
 */
object KlipyUrls {

    const val MEDIA_HOST = "static.klipy.com"
    const val MEDIA_HOST_1 = "static1.klipy.com"
    const val MEDIA_HOST_2 = "static2.klipy.com"

    /** The hosts Klipy serves media from. `MessageDao` names the same three in SQL. */
    val MEDIA_HOSTS = setOf(MEDIA_HOST, MEDIA_HOST_1, MEDIA_HOST_2)
    private const val HTTPS_PORT = 443

    /**
     * True for an `https` url whose host is exactly one of Klipy's media hosts,
     * with no user info and no other port. Anything that cannot be parsed is not
     * one.
     */
    fun isMedia(url: String?): Boolean {
        if (url == null) return false
        // A parser may drop or fold these, and another parser may read them differently.
        if (url.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        // A host that is not a plain server name leaves `host` null and the authority set.
        val host = uri.host ?: return false
        if (uri.rawUserInfo != null || uri.rawAuthority != hostWithPort(host, uri.port)) return false
        if (uri.port != -1 && uri.port != HTTPS_PORT) return false
        return host.lowercase() in MEDIA_HOSTS
    }

    private fun hostWithPort(host: String, port: Int) = if (port == -1) host else "$host:$port"
}
