// region: AGENT-NOTE
// Responsibility: Decides whether a message body travels as Signal ciphertext or
//   plaintext, and hands the write to MessageSource. The one owner of that
//   decision for every 1:1-capable send.
// Owns: the SUPPORTS_SIGNAL / BuildConfig.DEBUG build gate (a constructor value,
//   so tests can take the encrypted branch), the e2e opt-out read, and the types
//   that always travel in plaintext.
// Collaborators: OutboxSender (encode, persist the ciphertext on the row, then
//   write), MessageRepositoryImpl.forwardMessage and the broadcast fan-out (send —
//   no row to keep the ciphertext on), SignalManager, MessageSource,
//   PreferencesDataStore.
// Don't put here: attempts, retries or any Room access — the row belongs to
//   OutboxSender and this class keeps no state.
// endregion

package com.firestream.chat.data.outbox

import com.firestream.chat.BuildConfig
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** LOCATION has always been written in plaintext. */
private val PLAINTEXT_TYPES = setOf(MessageType.LOCATION)

@Singleton
class MessageWriter internal constructor(
    private val messageSource: MessageSource,
    private val signalManager: SignalManager,
    private val preferencesDataStore: PreferencesDataStore,
    private val buildEncrypts: Boolean,
) {

    @Inject
    constructor(
        messageSource: MessageSource,
        signalManager: SignalManager,
        preferencesDataStore: PreferencesDataStore,
    ) : this(
        messageSource,
        signalManager,
        preferencesDataStore,
        // Debug builds send plaintext so a reinstall during development cannot
        // strand history nobody can decrypt; the pocketbase flavor has no Signal.
        buildEncrypts = BuildConfig.SUPPORTS_SIGNAL && !BuildConfig.DEBUG,
    )

    /**
     * [message]'s content encrypted for [recipientId] when this build encrypts,
     * the user has not opted out, the type is not a plaintext one and there is a
     * 1:1 peer (`""` for group and broadcast chats — a Signal session cannot
     * address a group); `null` when it travels in plaintext.
     *
     * Encrypting advances the peer's session. A caller that may write the same
     * message again keeps the result instead of calling this twice.
     */
    suspend fun encode(message: Message, recipientId: String): EncryptedMessage? {
        val encrypts = recipientId.isNotEmpty() &&
            message.type !in PLAINTEXT_TYPES &&
            buildEncrypts &&
            preferencesDataStore.e2eEncryptionEnabledFlow.first()
        if (!encrypts) return null
        signalManager.ensureInitialized()
        return signalManager.encrypt(recipientId, message.content)
    }

    /**
     * Writes [message] under its own id — with [encrypted] in place of its
     * content, or in plaintext when that is `null`.
     *
     * [ifAbsent] = true marks a re-attempt, so a write that landed after its await
     * was cancelled is never duplicated (see [MessageSource]). Returns the id the
     * backend actually used — the same id on Firebase, a server id on PocketBase —
     * which is why callers swap their row to it.
     */
    suspend fun write(message: Message, encrypted: EncryptedMessage?, ifAbsent: Boolean = false): String =
        if (encrypted != null) {
            messageSource.sendMessage(
                chatId = message.chatId,
                senderId = message.senderId,
                messageId = message.id,
                ciphertext = encrypted.ciphertext,
                signalType = encrypted.signalType,
                type = message.type,
                replyToId = message.replyToId,
                timestamp = message.timestamp,
                mediaUrl = message.mediaUrl,
                mediaThumbnailUrl = message.mediaThumbnailUrl,
                isForwarded = message.isForwarded,
                duration = message.duration,
                mentions = message.mentions,
                plainContent = message.content,
                emojiSizes = message.emojiSizes,
                mediaWidth = message.mediaWidth,
                mediaHeight = message.mediaHeight,
                latitude = message.latitude,
                longitude = message.longitude,
                isHd = message.isHd,
                ifAbsent = ifAbsent,
            )
        } else {
            messageSource.sendPlainMessage(
                chatId = message.chatId,
                senderId = message.senderId,
                messageId = message.id,
                content = message.content,
                type = message.type,
                replyToId = message.replyToId,
                timestamp = message.timestamp,
                mediaUrl = message.mediaUrl,
                mediaThumbnailUrl = message.mediaThumbnailUrl,
                isForwarded = message.isForwarded,
                duration = message.duration,
                mentions = message.mentions,
                emojiSizes = message.emojiSizes,
                mediaWidth = message.mediaWidth,
                mediaHeight = message.mediaHeight,
                latitude = message.latitude,
                longitude = message.longitude,
                isHd = message.isHd,
                ifAbsent = ifAbsent,
            )
        }

    /** [encode] then [write], for a send with no row to keep the ciphertext on. */
    suspend fun send(message: Message, recipientId: String): String =
        write(message, encode(message, recipientId))
}
