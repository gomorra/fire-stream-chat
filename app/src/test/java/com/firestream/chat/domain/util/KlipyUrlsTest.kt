package com.firestream.chat.domain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KlipyUrlsTest {

    @Test
    fun `an https url on one of the three media hosts is Klipy's media`() {
        listOf(
            "https://static.klipy.com/ii/abc/cat.webp",
            "https://static1.klipy.com/ii/abc/cat.gif",
            "https://static2.klipy.com/ii/abc/cat.png?v=2",
            "https://static.klipy.com:443/ii/abc/cat.webp",
            "HTTPS://STATIC.KLIPY.COM/ii/abc/cat.webp",
        ).forEach { assertTrue(it, KlipyUrls.isMedia(it)) }
    }

    @Test
    fun `another host, however close, is not`() {
        listOf(
            "https://klipy.com/cat.webp",
            "https://api.klipy.com/api/v1/key/gifs/trending",
            "https://static3.klipy.com/cat.webp",
            "https://static.klipy.com.evil.example/cat.webp",
            "https://evil.example/static.klipy.com/cat.webp",
            "https://xstatic.klipy.com/cat.webp",
            "https://static.klipy.com./cat.webp",
            "https://firebasestorage.googleapis.com/v0/b/app/o/cat.webp",
        ).forEach { assertFalse(it, KlipyUrls.isMedia(it)) }
    }

    @Test
    fun `another scheme, a port or user info is not`() {
        listOf(
            "http://static.klipy.com/cat.webp",
            "ftp://static.klipy.com/cat.webp",
            "//static.klipy.com/cat.webp",
            "static.klipy.com/cat.webp",
            "https://static.klipy.com:8443/cat.webp",
            "https://static.klipy.com:80/cat.webp",
            "https://user@static.klipy.com/cat.webp",
            "https://user:pass@static.klipy.com/cat.webp",
            "https://static.klipy.com@evil.example/cat.webp",
            "https://@static.klipy.com/cat.webp",
        ).forEach { assertFalse(it, KlipyUrls.isMedia(it)) }
    }

    @Test
    fun `a url two parsers could read differently is not`() {
        listOf(
            "https://evil.example\\@static.klipy.com/cat.webp",
            "https://static.klipy.com\\.evil.example/cat.webp",
            "https://static.klipy.com /cat.webp",
            " https://static.klipy.com/cat.webp",
            "https://static.klipy.com/cat.webp\n",
            "https://static.klipy.com\t.evil.example/cat.webp",
            "https://evil.example#@static.klipy.com/cat.webp",
            "https://evil.example?@static.klipy.com/cat.webp",
            "https://static.klipy.com%2eevil.example/cat.webp",
            "https://[static.klipy.com]/cat.webp",
        ).forEach { assertFalse(it, KlipyUrls.isMedia(it)) }
    }

    @Test
    fun `nothing, and text that is no url, is not`() {
        listOf(null, "", "null", "cat", "https://", "https:///cat.webp", "content://media/1", "/data/files/cat.webp")
            .forEach { assertFalse("$it", KlipyUrls.isMedia(it)) }
    }
}
