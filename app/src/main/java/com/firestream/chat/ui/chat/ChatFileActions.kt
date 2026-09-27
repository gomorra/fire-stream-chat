package com.firestream.chat.ui.chat

import android.webkit.MimeTypeMap
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.ui.components.ReadyFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user asked to do with a file message. */
internal enum class FileAction { OPEN, SHARE, SAVE }

/**
 * A file ready to launch with an intent — [action] is OPEN or SHARE. [isRisky]
 * asks the screen to confirm first (an APK or a script).
 */
internal data class FileLaunch(val file: ReadyFile, val action: FileAction, val isRisky: Boolean)

/**
 * Open / Share / Save to Downloads for DOCUMENT messages: make sure there is a
 * readable local copy (downloading it if needed), then hand the screen a
 * [FileLaunch] — intents need the Activity, so they start there — or, for a
 * save, hand the file to [saveToDownloads], which reports its own outcome.
 *
 * Not a `Chat*Manager`: it owns no `ChatUiState` slice. What it publishes —
 * which messages are downloading, and one-shot events — is transient and read by
 * the screen alone, like the ViewModel's snackbar flow.
 */
internal class ChatFileActions(
    private val messageRepository: MessageRepository,
    private val scope: CoroutineScope,
    private val saveToDownloads: suspend (ReadyFile) -> Unit,
    private val notify: suspend (String) -> Unit,
) {
    private val _preparing = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of messages whose file is being fetched for an action — the bubble shows a spinner. */
    val preparing: StateFlow<Set<String>> = _preparing.asStateFlow()

    private val _launches = MutableSharedFlow<FileLaunch>(extraBufferCapacity = 4)
    val launches: SharedFlow<FileLaunch> = _launches.asSharedFlow()

    /** Runs [action] on [message]'s file. A second tap while the first is still fetching is ignored. */
    fun request(message: Message, action: FileAction) {
        if (message.id in _preparing.value) return
        _preparing.update { it + message.id }
        scope.launch {
            try {
                val path = messageRepository.ensureLocalFile(message).getOrThrow()
                val file = ReadyFile(path = path, mimeType = mimeTypeFor(message), displayName = displayNameFor(message))
                if (action == FileAction.SAVE) {
                    saveToDownloads(file)
                } else {
                    val isRisky = FileKind.of(message.mimeType, message.fileName).isRisky
                    _launches.emit(FileLaunch(file, action, isRisky))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notify(if (action == FileAction.SAVE) "Couldn't save the file" else "Couldn't download the file")
            } finally {
                _preparing.update { it - message.id }
            }
        }
    }

    companion object {
        private const val ANY_TYPE = "*/*"

        /** The type other apps are offered the file as: the picked type, else its extension's, else any. */
        fun mimeTypeFor(message: Message): String =
            message.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: FileKind.extensionOf(message.fileName)?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                ?: message.mimeType
                ?: ANY_TYPE

        /** The original file name, or a generic one for a document sent before names were kept. */
        fun displayNameFor(message: Message): String = message.fileName?.takeIf { it.isNotBlank() } ?: "Document"
    }
}
