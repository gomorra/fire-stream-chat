package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUrlsTest {

    @Test
    fun `extractMapsPlaceInfo extracts place name and coordinates from Google Maps place URL`() {
        val url = "https://www.google.com/maps/place/Brandenburg+Gate/@52.5162746,13.3777041,17z/data=!3m1!4b1"
        val info = MapUrls.extractMapsPlaceInfo(url)

        assertNotNull(info)
        assertEquals("Brandenburg Gate", info!!.placeName)
        assertEquals(52.5162746, info.latitude!!, 0.0001)
        assertEquals(13.3777041, info.longitude!!, 0.0001)
    }

    @Test
    fun `extractMapsPlaceInfo extracts German place name with special characters from URL path`() {
        val url = "https://www.google.com/maps/place/Restaurant+Bagdad,+Brunnenstra%C3%9Fe+53,+70372+Stuttgart-Bad+Cannstatt/data=!4m2"
        val info = MapUrls.extractMapsPlaceInfo(url)

        assertNotNull(info)
        assertEquals("Restaurant Bagdad, Brunnenstraße 53, 70372 Stuttgart-Bad Cannstatt", info!!.placeName)
    }

    @Test
    fun `extractMapsPlaceInfo extracts place and coordinates from query params`() {
        val url = "https://www.google.com/maps?q=ARTE+E+CERAMICA+TORINO&hl=it&ll=45.065556,7.686121"
        val info = MapUrls.extractMapsPlaceInfo(url)

        assertNotNull(info)
        assertEquals("ARTE E CERAMICA TORINO", info!!.placeName)
        assertEquals(45.065556, info.latitude!!, 0.0001)
        assertEquals(7.686121, info.longitude!!, 0.0001)
    }

    @Test
    fun `extractMapsPlaceInfo extracts coordinates from q query param when it is numeric`() {
        val url = "https://maps.google.com/?q=52.5162,13.3777"
        val info = MapUrls.extractMapsPlaceInfo(url)

        assertNotNull(info)
        assertNull(info!!.placeName)
        assertEquals(52.5162, info.latitude!!, 0.0001)
        assertEquals(13.3777, info.longitude!!, 0.0001)
    }

    @Test
    fun `extractMapsPlaceInfo extracts coordinates from OpenStreetMap URL`() {
        val url = "https://www.openstreetmap.org/#map=16/52.5163/13.3777"
        val info = MapUrls.extractMapsPlaceInfo(url)

        assertNotNull(info)
        assertEquals(52.5163, info!!.latitude!!, 0.0001)
        assertEquals(13.3777, info.longitude!!, 0.0001)
    }

    @Test
    fun `extractMapsPlaceInfo returns null for non-map URLs`() {
        assertNull(MapUrls.extractMapsPlaceInfo("https://example.com/place/foo"))
        assertNull(MapUrls.extractMapsPlaceInfo("https://wikipedia.org"))
    }

    @Test
    fun `extractMapsPlaceInfo ignores non-Maps Google properties`() {
        // google.com also serves Search, Docs and Accounts. Matching "google." as a
        // substring of the whole URL pulled a place name out of every one of these.
        assertNull(MapUrls.extractMapsPlaceInfo("https://www.google.com/search?q=pizza+near+me"))
        assertNull(MapUrls.extractMapsPlaceInfo("https://docs.google.com/document/d/abc/edit?q=notes"))
        assertNull(
            MapUrls.extractMapsPlaceInfo(
                "https://accounts.google.com/signin?continue=https://www.google.com/maps&ll=1.0,2.0"
            )
        )
    }

    @Test
    fun `extractMapsPlaceInfo ignores a URL that merely mentions a map host`() {
        assertNull(MapUrls.extractMapsPlaceInfo("https://example.com/article?ref=google.com&q=Berlin"))
        // Anchored host match: google.evil.com is not Google.
        assertNull(MapUrls.extractMapsPlaceInfo("https://google.evil.com/maps/place/Fake/@1.0,2.0"))
        // A bare goo.gl short link shortens anything; only /maps is a map.
        assertNull(MapUrls.extractMapsPlaceInfo("https://goo.gl/abcdef?q=Somewhere"))
    }

    @Test
    fun `extractMapsPlaceInfo accepts country domains and maps short links`() {
        val german = MapUrls.extractMapsPlaceInfo("https://www.google.de/maps/place/Kölner+Dom/@50.9413,6.9583,17z")
        assertNotNull(german)
        assertEquals("Kölner Dom", german!!.placeName)

        val shortened = MapUrls.extractMapsPlaceInfo("https://goo.gl/maps/xyz?q=Brandenburg+Gate")
        assertNotNull(shortened)
        assertEquals("Brandenburg Gate", shortened!!.placeName)
    }

    @Test
    fun `staticMapUrl generates valid OSM tile URL`() {
        val tileUrl = MapUrls.staticMapUrl(52.5162, 13.3777)
        assertTrue(tileUrl.startsWith("https://tile.openstreetmap.org/15/"))
        assertTrue(tileUrl.endsWith(".png"))
    }
}
