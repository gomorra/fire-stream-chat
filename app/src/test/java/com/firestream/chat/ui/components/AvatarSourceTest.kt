package com.firestream.chat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarSourceTest {

    @Test
    fun `a failed local file falls back to the url`() {
        assertEquals(
            AvatarSource.REMOTE,
            avatarSourceAfterError(failedOnLocalFile = true, avatarUrl = URL),
        )
    }

    @Test
    fun `a failed local file without a url gives up`() {
        assertEquals(
            AvatarSource.NONE,
            avatarSourceAfterError(failedOnLocalFile = true, avatarUrl = null),
        )
    }

    @Test
    fun `a failed url gives up`() {
        assertEquals(
            AvatarSource.NONE,
            avatarSourceAfterError(failedOnLocalFile = false, avatarUrl = URL),
        )
    }

    private companion object {
        const val URL = "https://cdn.example.com/avatar.jpg?token=abc"
    }
}
