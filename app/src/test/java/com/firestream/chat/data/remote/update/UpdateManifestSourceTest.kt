package com.firestream.chat.data.remote.update

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.Assert.assertThrows
import org.json.JSONException

class UpdateManifestSourceTest {

    private val source = UpdateManifestSource(okHttpClient = mockk())

    @Test
    fun `parse extracts every field from a complete manifest`() {
        val json = """
            {
              "versionCode": 142,
              "versionName": "1.5.0",
              "apkUrl": "https://example.com/firestream-1.5.0.apk",
              "sha256": "abc123",
              "minSupportedVersionCode": 100,
              "releaseNotes": "Added X\nFixed Y",
              "publishedAt": "2026-04-29T12:00:00Z",
              "mandatory": true
            }
        """.trimIndent()

        val update = source.parse(json)

        assertEquals(142, update.versionCode)
        assertEquals("1.5.0", update.versionName)
        assertEquals("https://example.com/firestream-1.5.0.apk", update.apkUrl)
        assertEquals("abc123", update.sha256)
        assertEquals(100, update.minSupportedVersionCode)
        assertEquals("Added X\nFixed Y", update.releaseNotes)
        assertEquals("2026-04-29T12:00:00Z", update.publishedAt)
        assertTrue(update.mandatory)
    }

    @Test
    fun `parse fills in defaults when optional fields are missing`() {
        val json = """
            {
              "versionCode": 7,
              "versionName": "0.0.7",
              "apkUrl": "https://example.com/x.apk",
              "sha256": "deadbeef"
            }
        """.trimIndent()

        val update = source.parse(json)

        assertEquals(1, update.minSupportedVersionCode)
        assertEquals("", update.releaseNotes)
        assertEquals("", update.publishedAt)
        assertFalse(update.mandatory)
    }

    @Test
    fun `parse throws on malformed JSON`() {
        assertThrows(JSONException::class.java, ThrowingRunnable {
            source.parse("not json")
        })
    }

    @Test
    fun `parse throws when a required field is absent`() {
        val json = """{ "versionName": "1.0.0" }"""
        assertThrows(JSONException::class.java, ThrowingRunnable {
            source.parse(json)
        })
    }
}
