package com.firestream.chat.ui.chat

import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.firestream.chat.domain.util.GIF_MIME_TYPE

/** What a picture the keyboard inserted is sent as. */
internal enum class KeyboardContentRoute { GIF, STICKER }

/**
 * The route for keyboard content of [mimeType]: a GIF is sent as it is, and any
 * other picture becomes a sticker. `null` for anything that is not a picture,
 * which the composer does not take.
 */
internal fun keyboardContentRoute(mimeType: String?): KeyboardContentRoute? = when {
    mimeType == null -> null
    mimeType.equals(GIF_MIME_TYPE, ignoreCase = true) -> KeyboardContentRoute.GIF
    mimeType.startsWith("image/", ignoreCase = true) -> KeyboardContentRoute.STICKER
    else -> null
}

/** The types the composer tells the keyboard it takes. A keyboard hides or greys out what is not listed. */
internal val KEYBOARD_CONTENT_MIME_TYPES = arrayOf(GIF_MIME_TYPE, "image/webp", "image/png", "image/jpeg")

/**
 * Lets the keyboard insert a GIF or a sticker into the text field inside
 * [content], and hands each one to [onContent] with its type.
 *
 * The composer is a value-based `BasicTextField`. Its input connection refuses
 * `commitContent`, so `Modifier.contentReceiver` never hears from the keyboard
 * there. This wraps the connection the field makes instead: it names the types
 * in the `EditorInfo` and takes the commit itself. Text input is untouched.
 *
 * The keyboard's grant to read the uri is taken before [onContent] is called
 * and lasts until the `release` it is given is called. The receiver calls it
 * once the bytes are copied. [enabled] is read at each commit, so a field that
 * turns it off refuses content without a new input session.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun KeyboardContentReceiver(
    enabled: Boolean,
    onContent: (uri: Uri, mimeType: String, release: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnContent by rememberUpdatedState(onContent)
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod(
                PlatformTextInputMethodRequest { outAttributes ->
                    acceptingContent(request.createInputConnection(outAttributes), outAttributes) { info, flags ->
                        currentEnabled && takeContent(info, flags, currentOnContent)
                    }
                }
            )
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/** Hands [info] to [onContent] with the keyboard's grant taken. False for content the composer does not send. */
private fun takeContent(
    info: InputContentInfoCompat,
    flags: Int,
    onContent: (uri: Uri, mimeType: String, release: () -> Unit) -> Unit,
): Boolean {
    val mimeType = info.routableMimeType() ?: return false
    if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) {
        try {
            info.requestPermission()
        } catch (e: Exception) {
            return false
        }
    }
    onContent(info.contentUri, mimeType) { info.releasePermission() }
    return true
}

/** [connection] with the content types added to [editorInfo] and `commitContent` answered by [onCommit]. */
private fun acceptingContent(
    connection: InputConnection,
    editorInfo: EditorInfo,
    onCommit: (info: InputContentInfoCompat, flags: Int) -> Boolean,
): InputConnection {
    EditorInfoCompat.setContentMimeTypes(editorInfo, KEYBOARD_CONTENT_MIME_TYPES)
    // The View-based overload sends content to the view's OnReceiveContentListener, which a Compose host does not have.
    @Suppress("DEPRECATION")
    return InputConnectionCompat.createWrapper(connection, editorInfo) { info, flags, _ -> onCommit(info, flags) }
}

/** The first type the content is offered as that the composer sends, or `null`. */
private fun InputContentInfoCompat.routableMimeType(): String? {
    val description = description
    return (0 until description.mimeTypeCount)
        .map { description.getMimeType(it) }
        .firstOrNull { keyboardContentRoute(it) != null }
}
