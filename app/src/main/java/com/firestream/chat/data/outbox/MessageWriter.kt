// region: AGENT-NOTE
// Responsibility: Decides whether a message body travels as Signal ciphertext or
//   plaintext, and hands the write to MessageSource. The one owner of that
//   decision for every 1:1-capable send.
// Owns: the SUPPORTS_SIGNAL / BuildConfig.DEBUG build gate (a constructor value,
//   so tests can take the encrypted branch), the e2e opt-out read, and packing
//   1:1 messages into a v=1 MessageEnvelope before encrypting.
// Collaborators: OutboxSender (encode, persist the ciphertext on the row, then
//   write — every queued send), MessageRepositoryImpl's broadcast fan-out (send —
//   no row to keep the ciphertext on), SignalManager, MessageSource,
//   PreferencesDataStore. SendTarget says whether there is a peer to encrypt for.
// Don't put here: attempts, retries or any Room access — the row belongs to
//   OutboxSender and this class keeps no state.
// endregion

package com.firestream.chat.data.outbox

import com.firestream.chat.BuildConfig
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.MessageEnvelope
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.FileMetadata
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.StickerRef
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

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
     * True when a message sent to [target] should be encrypted end-to-end
     * (both its Signal envelope and any uploaded 1:1 media/thumbnail blobs).
     */
    suspend fun willEncrypt(message: Message, target: SendTarget): Boolean =
        target.peerId != null &&
            buildEncrypts &&
            preferencesDataStore.e2eEncryptionEnabledFlow.first()

    /**
     * [message]'s v=1 [MessageEnvelope] encrypted for [target]'s peer when this
     * build encrypts and the user has not opted out; `null` when it travels in
     * plaintext — always for [SendTarget.NoPeer], since a Signal session cannot
     * address a group or a broadcast.
     *
     * Encrypting advances the peer's session. A caller that may write the same
     * message again keeps the result instead of calling this twice.
     */
    suspend fun encode(message: Message, target: SendTarget): EncryptedMessage? {
        if (!willEncrypt(message, target)) return null
        val peer = target.peerId ?: return null
        signalManager.ensureInitialized()
        val envelopeJson = MessageEnvelope.fromMessage(message).encode()
        return signalManager.encrypt(peer, envelopeJson)
    }

    /**
     * Whether [stored], encrypted by an earlier attempt for [peerId], can still be
     * written as is: only while the peer publishes the identity it was encrypted
     * for. After a re-registration the session behind it is gone on their side,
     * so the caller drops the bytes and [encode]s again, which rebuilds the session.
     */
    suspend fun isReusable(peerId: String, stored: EncryptedMessage): Boolean {
        val peerIdentity = stored.peerIdentity ?: return false
        return signalManager.isCurrentIdentity(peerId, peerIdentity)
    }

    /**
     * Writes [message] under its own id — with [encrypted] carrying the full
     * envelope (routing/delivery fields only on the backend), or in plaintext
     * when that is `null`.
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
                timestamp = message.timestamp,
                isForwarded = message.isForwarded,
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
                file = FileMetadata.of(message),
                sticker = StickerRef.of(message),
                ifAbsent = ifAbsent,
            )
        }

    /** [encode] then [write], for a send with no row to keep the ciphertext on. */
    suspend fun send(message: Message, target: SendTarget): String =
        write(message, encode(message, target))
}
