package com.firestream.chat.domain.util

import java.net.URLDecoder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

data class MapsPlaceInfo(
    val placeName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

object MapUrls {
    private const val MAP_ZOOM = 15

    /** Returns the OSM tile URL for the tile containing the given coordinates. */
    fun staticMapUrl(lat: Double, lng: Double, zoom: Int = MAP_ZOOM): String {
        val n = 1 shl zoom
        val xTile = ((lng + 180.0) / 360.0 * n).toInt()
        val latRad = Math.toRadians(lat)
        val yTile = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return "https://tile.openstreetmap.org/$zoom/$xTile/$yTile.png"
    }

    /**
     * Hosts that only ever serve maps, so any path on them is a map link.
     * `maps.app.goo.gl` is what the Google Maps app's share sheet produces.
     */
    private val MAP_ONLY_HOSTS = setOf(
        "maps.app.goo.gl", "maps.apple.com", "openstreetmap.org", "osm.org"
    )

    /**
     * `google.<tld>` and `maps.google.<tld>`, anchored so `google.example.com` — or a
     * URL that merely *mentions* a Google domain in a query parameter — cannot pass.
     * A bare `google.com` host still has to carry a `/maps` path, because the same
     * host serves Search, Docs and Accounts.
     */
    private val GOOGLE_HOST = Regex("""^(?:www\.)?google\.[a-z]{2,3}(?:\.[a-z]{2})?$""")
    private val GOOGLE_MAPS_HOST = Regex("""^maps\.google\.[a-z]{2,3}(?:\.[a-z]{2})?$""")

    /** Splits "scheme://host[:port]/path?query" into its host and path. */
    private val URL_PARTS = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://([^/?#]*)([^?#]*)""")

    private const val MAPS_PATH_PREFIX = "/maps"

    /**
     * True when [url] actually points at a map.
     *
     * Matched against the parsed host and path, never as a substring of the whole URL:
     * `contains("google.")` was true for `docs.google.com`, for `accounts.google.com`,
     * and for any link carrying `google.com` in a `?ref=` parameter, all of which would
     * then have had a place name or coordinates read out of their query string.
     */
    private fun isMapUrl(url: String): Boolean {
        val parts = URL_PARTS.find(url) ?: return false
        val host = parts.groupValues[1]
            .substringAfterLast('@')   // strip any userinfo
            .substringBefore(':')      // strip any port
            .lowercase()
            .removeSuffix(".")
        val path = parts.groupValues[2]

        if (host in MAP_ONLY_HOSTS) return true
        if (MAP_ONLY_HOSTS.any { host.endsWith(".$it") }) return true
        if (GOOGLE_MAPS_HOST.matches(host)) return true
        if (GOOGLE_HOST.matches(host) || host == "goo.gl") {
            return path.startsWith(MAPS_PATH_PREFIX)
        }
        return false
    }

    private val PATH_PLACE_REGEX = Regex("""/maps/place/([^/@?#]+)""")
    private val PATH_COORDS_REGEX = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val OSM_HASH_COORDS_REGEX = Regex("""map=\d+/(-?\d+\.\d+)/(-?\d+\.\d+)""")
    private val COORDS_STRING_REGEX = Regex("""^(-?\d+\.\d+),(-?\d+\.\d+)$""")

    /**
     * Extracts place name and/or coordinates from common map URLs
     * (Google Maps, Apple Maps, OpenStreetMap).
     */
    fun extractMapsPlaceInfo(url: String): MapsPlaceInfo? {
        if (!isMapUrl(url)) return null

        var placeName: String? = null
        var lat: Double? = null
        var lng: Double? = null

        // 1. Google Maps path place: /maps/place/{name}
        PATH_PLACE_REGEX.find(url)?.groupValues?.getOrNull(1)?.let { rawName ->
            val decoded = runCatching { URLDecoder.decode(rawName.replace("+", " "), "UTF-8") }.getOrNull()
            placeName = decoded?.trim()?.takeIf { it.isNotEmpty() }
        }

        // 2. Google Maps path coordinates: /@{lat},{lng}
        PATH_COORDS_REGEX.find(url)?.let { match ->
            lat = match.groupValues.getOrNull(1)?.toDoubleOrNull()
            lng = match.groupValues.getOrNull(2)?.toDoubleOrNull()
        }

        // 3. OpenStreetMap hash/path coordinates: map=16/lat/lng
        if (lat == null || lng == null) {
            OSM_HASH_COORDS_REGEX.find(url)?.let { match ->
                lat = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                lng = match.groupValues.getOrNull(2)?.toDoubleOrNull()
            }
        }

        // 4. Query parameters (?q=... or ?ll=...)
        val queryStart = url.indexOf('?')
        if (queryStart >= 0) {
            val query = url.substring(queryStart + 1).substringBefore('#')
            val params = query.split('&')
            for (param in params) {
                val eq = param.indexOf('=')
                if (eq < 0) continue
                val key = param.substring(0, eq).trim()
                val value = param.substring(eq + 1).trim()
                val decodedVal = runCatching { URLDecoder.decode(value.replace("+", " "), "UTF-8") }.getOrNull() ?: value

                when (key.lowercase()) {
                    "q" -> {
                        val coordsMatch = COORDS_STRING_REGEX.find(decodedVal)
                        if (coordsMatch != null) {
                            if (lat == null) lat = coordsMatch.groupValues[1].toDoubleOrNull()
                            if (lng == null) lng = coordsMatch.groupValues[2].toDoubleOrNull()
                        } else if (placeName == null) {
                            placeName = decodedVal.trim().takeIf { it.isNotEmpty() }
                        }
                    }
                    "ll", "cbll" -> {
                        if (lat == null || lng == null) {
                            val parts = decodedVal.split(',')
                            if (parts.size == 2) {
                                lat = parts[0].toDoubleOrNull()
                                lng = parts[1].toDoubleOrNull()
                            }
                        }
                    }
                }
            }
        }

        return if (placeName != null || (lat != null && lng != null)) {
            MapsPlaceInfo(placeName = placeName, latitude = lat, longitude = lng)
        } else {
            null
        }
    }
}
