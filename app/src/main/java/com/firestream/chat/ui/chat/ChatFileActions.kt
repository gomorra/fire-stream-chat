// region: AGENT-NOTE
// Responsibility: Open / Share / Save to Downloads for DOCUMENT messages in one
//   chat — a readable local copy first (MessageRepository.ensureLocalFile), then
//   a FileLaunch for the screen or a save.
// Owns: which messages are being fetched for an action (the badge spinner); the
//   launch channel; the type and name a file is offered to other apps under.
// Collaborators: ChatViewModel (constructs it, re-exposes preparing/launches,
//   supplies the save and snackbar lambdas), ChatScreen (launches FileIntents,
//   the risky confirm).
// Don't put here: ChatUiState — it owns no slice, so it is not a Chat*Manager
//   ("Chat*Manager slice-ownership", docs/PATTERNS.md); the intents themselves
//   (they need the Activity — ui/components/FileIntents).
// endregion

package com.firestream.chat.ui.chat

import android.webkit.MimeTypeMap
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.UNNAMED_FILE
import com.firestream.chat.ui.components.ReadyFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

    // A channel, not a SharedFlow: a download that finishes while the chat is off
    // screen (the user opened a profile meanwhile) is held until the screen
    // collects again instead of being dropped. One slot — only the latest counts.
    private val _launches = Channel<FileLaunch>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val launches: Flow<FileLaunch> = _launches.receiveAsFlow()

    /** Runs [action] on [message]'s file. A second tap while the first is still fetching is ignored. */
    fun request(message: Message, action: FileAction) {
        if (message.id in _preparing.value) return
        _preparing.update { it + message.id }
        scope.launch {
            try {
                val path = messageRepository.ensureLocalFile(message).getOrThrow()
                val file = ReadyFile(path = path, mimeType = mimeTypeFor(message, path), displayName = displayNameFor(message))
                if (action == FileAction.SAVE) {
                    saveToDownloads(file)
                } else {
                    val isRisky = FileKind.of(message.mimeType, message.fileName).isRisky
                    _launches.send(FileLaunch(file, action, isRisky))
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

        /**
         * The type other apps are offered the file as: the picked type, else its
         * name's extension's, else that of the local copy at [path] (a document
         * sent before names and types were kept), else any.
         */
        fun mimeTypeFor(message: Message, path: String? = null): String =
            message.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: sequenceOf(message.fileName, path?.substringAfterLast('/'))
                    .mapNotNull { FileKind.extensionOf(it)?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension) }
                    .firstOrNull()
                ?: message.mimeType
                ?: ANY_TYPE

        /** The original file name, or a generic one for a document sent before names were kept. */
        fun displayNameFor(message: Message): String = message.fileName?.takeIf { it.isNotBlank() } ?: UNNAMED_FILE
    }
}
