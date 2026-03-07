package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.firestream.chat.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastMessageTimestamp DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("UPDATE chats SET unreadCount = :count WHERE id = :chatId")
    suspend fun updateUnreadCount(chatId: String, count: Int)
}
