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

    @Query("UPDATE messages SET content = :content, editedAt = :editedAt WHERE id = :messageId")
    suspend fun editMessage(messageId: String, content: String, editedAt: Long)

    @Query("UPDATE messages SET reactions = :reactionsJson WHERE id = :messageId")
    suspend fun updateReactions(messageId: String, reactionsJson: String)

    // Phase 2: starred messages
    @Query("UPDATE messages SET isStarred = :starred WHERE id = :messageId")
    suspend fun setStarred(messageId: String, starred: Boolean)

    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY timestamp DESC")
    fun getStarredMessages(): Flow<List<MessageEntity>>

    // Phase 2: in-app search (LIKE-based; FTS4 virtual table added separately)
    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 100")
    suspend fun searchMessages(query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND content LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 50")
    suspend fun searchMessagesInChat(chatId: String, query: String): List<MessageEntity>

    // Shared media queries
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND mediaUrl IS NOT NULL ORDER BY timestamp DESC")
    fun getSharedMedia(chatId: String): Flow<List<MessageEntity>>

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
