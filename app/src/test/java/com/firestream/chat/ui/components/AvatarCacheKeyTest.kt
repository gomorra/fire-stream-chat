package com.firestream.chat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarCacheKeyTest {

    @Test
    fun `keys on the remote url when present`() {
        val url = "https://cdn.example.com/avatar.jpg?token=abc"

        // Even with a local file, the key must be the URL so the memory-cache
        // entry is shared across the File-vs-URL model flip.
        val key = avatarCacheKey(localAvatarPath = "/data/profile_pictures/uid.jpg", avatarUrl = url)

        assertEquals(url, key)
    }

    @Test
    fun `key is stable across the local-file and url models for the same photo`() {
        val url = "https://cdn.example.com/avatar.jpg?token=abc"

        val whileDownloading = avatarCacheKey(localAvatarPath = null, avatarUrl = url)
        val afterDownload = avatarCacheKey(localAvatarPath = "/data/profile_pictures/uid.jpg", avatarUrl = url)

        assertEquals(whileDownloading, afterDownload)
    }

    @Test
    fun `key changes when the photo changes (rotated storage token)`() {
        // Firebase rotates the download token on re-upload; the local file path
        // (uid.jpg) is reused, so only the URL distinguishes old from new.
        val path = "/data/profile_pictures/uid.jpg"
        val oldKey = avatarCacheKey(path, "https://cdn.example.com/avatar.jpg?token=OLD")
        val newKey = avatarCacheKey(path, "https://cdn.example.com/avatar.jpg?token=NEW")

        assertEquals("https://cdn.example.com/avatar.jpg?token=OLD", oldKey)
        assertEquals("https://cdn.example.com/avatar.jpg?token=NEW", newKey)
        assertNotEquals(oldKey, newKey)
    }

    @Test
    fun `falls back to local path when no url is known`() {
        val path = "/data/profile_pictures/uid.jpg"

        val key = avatarCacheKey(localAvatarPath = path, avatarUrl = null)

        assertEquals(path, key)
    }

    @Test
    fun `returns null when there is no image`() {
        assertNull(avatarCacheKey(localAvatarPath = null, avatarUrl = null))
    }
}
