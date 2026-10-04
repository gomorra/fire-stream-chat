package com.firestream.chat.ui.chat

import android.content.ClipDescription
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.inputmethod.EditorInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keyboard's side of [KeyboardContentReceiver], driven through the input
 * connection a value-based `BasicTextField` hands the platform. That is the
 * field the composer uses, and the one `Modifier.contentReceiver` does not reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class KeyboardContentReceiverTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val received = mutableListOf<Pair<Uri, String>>()
    private var releases = 0
    private var enabled by mutableStateOf(true)
    private var text by mutableStateOf(TextFieldValue(""))
    private val editorInfo = EditorInfo()

    /** Shows the field, focuses it, and returns the connection the keyboard would be given. */
    private fun connect(): InputConnection {
        composeTestRule.setContent {
            KeyboardContentReceiver(
                enabled = enabled,
                onContent = { uri, mimeType, release ->
                    received += uri to mimeType
                    release()
                    releases++
                },
            ) {
                BasicTextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag("field"))
            }
        }
        val host = composeTestRule.runOnUiThread {
            // A field starts its input session only in a focused window, which a Robolectric window is not.
            composeHost(composeTestRule.activity.window.decorView)!!.also { it.dispatchWindowFocusChanged(true) }
        }
        composeTestRule.onNodeWithTag("field").performClick()
        composeTestRule.waitForIdle()
        return composeTestRule.runOnUiThread { host.onCreateInputConnection(editorInfo)!! }
    }

    private fun composeHost(view: View): View? = when {
        view.javaClass.simpleName == "AndroidComposeView" -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { composeHost(view.getChildAt(it)) }
        else -> null
    }

    private fun content(mimeType: String) =
        InputContentInfo(Uri.parse("content://keyboard/1"), ClipDescription("from the keyboard", arrayOf(mimeType)))

    @Test
    fun `the field tells the keyboard which pictures it takes`() {
        connect()

        assertEquals(KEYBOARD_CONTENT_MIME_TYPES.toList(), EditorInfoCompat.getContentMimeTypes(editorInfo).toList())
    }

    @Test
    fun `a GIF from the keyboard reaches the callback with its type, and its grant is released by the receiver`() {
        val connection = connect()

        val taken = composeTestRule.runOnUiThread { connection.commitContent(content("image/gif"), 0, null) }

        assertTrue(taken)
        assertEquals(listOf(Uri.parse("content://keyboard/1") to "image/gif"), received)
        assertEquals(1, releases)
    }

    @Test
    fun `something that is no picture is refused`() {
        val connection = connect()

        val taken = composeTestRule.runOnUiThread { connection.commitContent(content("video/mp4"), 0, null) }

        assertFalse(taken)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `a field that is turned off refuses content without a new connection`() {
        val connection = connect()
        enabled = false
        composeTestRule.waitForIdle()

        val taken = composeTestRule.runOnUiThread { connection.commitContent(content("image/png"), 0, null) }

        assertFalse(taken)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `typed text still reaches the field`() {
        val connection = connect()

        composeTestRule.runOnUiThread { connection.commitText("hello", 1) }
        composeTestRule.waitForIdle()

        assertEquals("hello", text.text)
        assertNull(received.firstOrNull())
    }
}
