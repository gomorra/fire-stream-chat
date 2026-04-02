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
        insertMessage(newMessage)
        deleteMessage(oldId)
    }
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND timestamp = :timestamp AND senderId = :senderId AND status = 'SENDING' LIMIT 1")
    suspend fun getPendingSendingMessage(chatId: String, timestamp: Long, senderId: String): MessageEntity?

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
}
