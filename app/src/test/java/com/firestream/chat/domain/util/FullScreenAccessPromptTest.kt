package com.firestream.chat.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FullScreenAccessPromptTest {

    private data class Row(val apiLevel: Int, val granted: Boolean, val dismissed: Boolean, val prompt: Boolean)

    @Test
    fun `the prompt shows only where the access exists, is off and was not put off`() {
        val rows = listOf(
            // Below Android 14 the access does not exist, whatever the other two say.
            Row(apiLevel = 31, granted = true, dismissed = false, prompt = false),
            Row(apiLevel = 31, granted = false, dismissed = false, prompt = false),
            Row(apiLevel = 33, granted = false, dismissed = false, prompt = false),
            Row(apiLevel = 33, granted = false, dismissed = true, prompt = false),
            // Android 14 is the first version that asks.
            Row(apiLevel = 34, granted = false, dismissed = false, prompt = true),
            Row(apiLevel = 34, granted = true, dismissed = false, prompt = false),
            Row(apiLevel = 34, granted = false, dismissed = true, prompt = false),
            Row(apiLevel = 34, granted = true, dismissed = true, prompt = false),
            Row(apiLevel = 36, granted = false, dismissed = false, prompt = true),
            Row(apiLevel = 36, granted = true, dismissed = false, prompt = false),
            Row(apiLevel = 36, granted = false, dismissed = true, prompt = false),
        )

        for (row in rows) {
            assertEquals(
                "$row",
                row.prompt,
                shouldPromptForFullScreenAccess(row.apiLevel, row.granted, row.dismissed),
            )
        }
    }
}
