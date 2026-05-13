package com.firestream.chat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResolveAvatarModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `prefers local file when it exists on disk`() {
        val localFile = tempFolder.newFile("avatar.jpg").apply { writeText("x") }

        val model = resolveAvatarModel(localFile.absolutePath, "https://cdn.example.com/avatar.jpg")

        assertEquals(localFile, model)
    }

    @Test
    fun `falls back to remote url when local path is null`() {
        val url = "https://cdn.example.com/avatar.jpg"

        val model = resolveAvatarModel(localAvatarPath = null, avatarUrl = url)

        assertEquals(url, model)
    }

    @Test
    fun `falls back to remote url when local file does not exist`() {
        val missingPath = File(tempFolder.root, "does-not-exist.jpg").absolutePath
        val url = "https://cdn.example.com/avatar.jpg"

        val model = resolveAvatarModel(missingPath, url)

        assertEquals(url, model)
    }

    @Test
    fun `returns null when both inputs are null`() {
        val model = resolveAvatarModel(localAvatarPath = null, avatarUrl = null)

        assertNull(model)
    }
}
