package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.firestream.chat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Transaction
    suspend fun replaceMessage(oldId: String, newMessage: MessageEntity) {
        deleteMessage(oldId)
        insertMessage(newMessage)
    }
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    // Echo-dedupe lookup for an optimistic self-message that hasn't been replaced
    // by its remote row yet. Matches FAILED as well as SENDING so that a row the
    // orphan-recovery flip turned to FAILED (see failStuckSendingMessages) — but
    // which had actually reached the backend before the local replace ran — is
    // still recognised when its remote echo arrives, instead of being inserted a
    // second time as a duplicate.
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp = :timestamp AND senderId = :senderId AND status IN ('SENDING', 'FAILED') LIMIT 1")
    suspend fun getPendingSendingMessage(chatId: String, timestamp: Long, senderId: String): MessageEntity?

    // Orphan recovery: a send whose coroutine was cancelled mid-flight (e.g. the
    // user navigated away from the chat before it completed) leaves its optimistic
    // row stuck at SENDING forever — never retried, never marked FAILED. Flipping
    // it to FAILED restores the existing manual-retry affordance on the bubble.
    // Called on app start (all chats) and on chat (re)entry (one chat); at both
    // points the user has not initiated a new send, so no live SENDING row exists
    // and only genuine orphans are caught. Returns the number of rows recovered.
    // The deferred auto-retry/durable-outbox follow-up is logged in TECH_DEBT.md
    // ("Durable offline-send outbox").
    @Query("UPDATE messages SET status = 'FAILED' WHERE status = 'SENDING'")
    suspend fun failStuckSendingMessages(): Int

    @Query("UPDATE messages SET status = 'FAILED' WHERE chatId = :chatId AND status = 'SENDING'")
    suspend fun failStuckSendingMessagesForChat(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("UPDATE messages SET deletedAt = :deletedAt, content = '' WHERE id = :messageId")
    suspend fun softDeleteMessage(messageId: String, deletedAt: Long)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE id IN (:messageIds)")
    suspend fun updateMessageStatusBatch(messageIds: List<String>, status: String)

    // ── Offline outbox ──────────────────────────────────────────────────────
    // Column updates, not whole-row replaces: the outbox columns are not on the
    // domain Message, so a replace built with MessageEntity.fromDomain wipes them.

    @Query("UPDATE messages SET outboxAttempts = outboxAttempts + 1 WHERE id = :messageId")
    suspend fun incrementOutboxAttempts(messageId: String)

    @Query(
        """
        UPDATE messages SET outboxCiphertext = :ciphertext, outboxSignalType = :signalType,
            outboxPeerIdentity = :peerIdentity
        WHERE id = :messageId
        """
    )
    suspend fun storeOutboxCiphertext(messageId: String, ciphertext: String, signalType: Int, peerIdentity: String?)

    /** A finished pipeline step's output — the encoded file, the thumbnail, the upload. */
    @Query(
        """
        UPDATE messages SET localUri = :localUri, mediaWidth = :mediaWidth, mediaHeight = :mediaHeight,
            duration = :duration, mediaThumbnailUrl = :mediaThumbnailUrl, mediaUrl = :mediaUrl
        WHERE id = :messageId
        """
    )
    suspend fun updateSendProgress(
        messageId: String,
        localUri: String?,
        mediaWidth: Int?,
        mediaHeight: Int?,
        duration: Int?,
        mediaThumbnailUrl: String?,
        mediaUrl: String?,
    )

    @Query("UPDATE messages SET isPinned = :pinned WHERE id = :messageId")
    suspend fun setPinned(messageId: String, pinned: Boolean)

    @Query("UPDATE messages SET content = :content, editedAt = :editedAt, emojiSizes = :emojiSizes WHERE id = :messageId")
    suspend fun editMessage(messageId: String, content: String, editedAt: Long, emojiSizes: Map<Int, Float>)

    @Query("UPDATE messages SET reactions = :reactionsJson WHERE id = :messageId")
    suspend fun updateReactions(messageId: String, reactionsJson: String)

    // Phase 2: starred messages
    @Query("UPDATE messages SET isStarred = :starred WHERE id = :messageId")
    suspend fun setStarred(messageId: String, starred: Boolean)

    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY timestamp DESC")
    fun getStarredMessages(): Flow<List<MessageEntity>>

    // Phase 2: in-app search (LIKE-based; FTS4 virtual table added separately)
    //
    // One query serves both scopes and every prefilter. Each clause is a
    // nullable/zero-valued short-circuit, so it stays compile-time verified —
    // which @RawQuery would have given up — while covering in-chat search,
    // global search, and filter-only "browse" mode (blank query + an active
    // chip).
    //
    // The limit is a parameter, not a literal, so the caller that reports
    // "there may be more" and the query that truncates cannot drift apart.
    //
    // `:chatId IS NULL` is the global scope. There is no index on
    // `messages.chatId` (the `(chatId, timestamp)` index is deliberately
    // deferred), so today the added clause costs no query plan — this is
    // already a full scan behind a `LIKE '%…%'`. Note for whoever adds that
    // index: an OR-term over a nullable bound parameter is not an indexable
    // constraint, so the in-chat scope will not use it in this form. Splitting
    // the query back in two is part of the index work.
    //
    // `:query = ''` is a short-circuit, not a nicety: without it browse mode
    // would LIKE every row's content against '%%'.
    //
    // `deletedAt IS NULL` is mandatory, not polish. softDeleteMessage blanks
    // `content` but leaves `mediaUrl` intact, so a tombstoned image is
    // invisible to any non-blank content LIKE — but a filter-only query has no
    // content predicate at all and would happily return it and render its
    // thumbnail. It also re-aligns "what search can return" with "what the
    // message list renders".
    //
    // `:requireLink` is the LINKS chip: a link lives in the content of a TEXT
    // row, so it is a second parameter rather than a `type` value.
    @Query(
        """
        SELECT * FROM messages
        WHERE (:chatId IS NULL OR chatId = :chatId)
          AND deletedAt IS NULL
          AND (:query = '' OR content LIKE '%' || :query || '%')
          AND (:type IS NULL OR type = :type)
          AND (:requireLink = 0 OR content LIKE '%http%')
          AND (:starredOnly = 0 OR isStarred = 1)
          AND (:from IS NULL OR timestamp >= :from)
          AND (:to IS NULL OR timestamp <= :to)
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchMessages(
        chatId: String?,
        query: String,
        type: String?,
        requireLink: Boolean,
        starredOnly: Boolean,
        from: Long?,
        to: Long?,
        limit: Int,
    ): List<MessageEntity>

    // Shared media queries
    @Query("SELECT * FROM messages WHERE senderId = :userId AND mediaUrl IS NOT NULL ORDER BY timestamp DESC LIMIT 100")
    fun getSharedMediaForUser(userId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageByChatId(chatId: String): MessageEntity?

    // Local media
    @Query("UPDATE messages SET localUri = :localUri WHERE id = :messageId")
    suspend fun updateLocalUri(messageId: String, localUri: String?)

    @Query("SELECT * FROM messages WHERE type IN ('IMAGE', 'VIDEO', 'DOCUMENT') AND localUri IS NULL AND mediaUrl IS NOT NULL")
    suspend fun getMessagesWithoutLocalMedia(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE type IN ('IMAGE', 'VIDEO', 'DOCUMENT')")
    suspend fun getAllMediaMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND type IN ('IMAGE', 'VIDEO', 'DOCUMENT') AND localUri IS NULL AND mediaUrl IS NOT NULL")
    suspend fun getMessagesWithoutLocalMediaForChat(chatId: String): List<MessageEntity>

    // Call log
    @Query("SELECT * FROM messages WHERE type = 'CALL' ORDER BY timestamp DESC")
    fun getCallMessages(): Flow<List<MessageEntity>>

    // Boot-restore: re-arm or auto-complete after device reboot.
    @Query("SELECT * FROM messages WHERE type = 'TIMER' AND timerState = 'RUNNING'")
    suspend fun getRunningTimers(): List<MessageEntity>
}
