package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.firestream.chat.data.local.entity.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY COALESCE(lastMessageTimestamp, createdAt) DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id IN (:chatIds)")
    suspend fun getChatsByIds(chatIds: List<String>): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    /**
     * Atomically merges a remote chat snapshot into Room while preserving local-only
     * fields (isPinned, isArchived, muteUntil, cachedAvatarUrl, localAvatarPath).
     *
     * The read-then-write must run inside one Room transaction. Without it, a concurrent
     * setArchived/setPinned/setMuteUntil call can land between the read of `existingMap`
     * and the `insertChats` write, and the merged entity will resurrect the stale
     * pre-flip value — silently undoing the user's action. (Repro: archive a chat,
     * unarchive it, archive again — the second archive disappears because the next
     * Firestore snapshot rewrites isArchived=false from a stale read.)
     *
     * Returns the snapshot of pre-merge entities so callers can drive avatar-cache
     * decisions against the same view of state the merge saw.
     */
    @Transaction
    suspend fun upsertRemote(remote: List<ChatEntity>): Map<String, ChatEntity> {
        val existing = getChatsByIds(remote.map { it.id }).associateBy { it.id }
        val merged = remote.map { r ->
            val local = existing[r.id]
            if (local != null) {
                r.copy(
                    isPinned = local.isPinned,
                    isArchived = local.isArchived,
                    muteUntil = local.muteUntil,
                    cachedAvatarUrl = local.cachedAvatarUrl,
                    localAvatarPath = local.localAvatarPath
                )
            } else r
        }
        insertChats(merged)
        return existing
    }

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("UPDATE chats SET unreadCount = :count WHERE id = :chatId")
    suspend fun updateUnreadCount(chatId: String, count: Int)

    @Query("UPDATE chats SET lastMessageId = :id, lastMessageContent = :content, lastMessageTimestamp = :timestamp WHERE id = :chatId")
    suspend fun updateLastMessage(chatId: String, id: String?, content: String?, timestamp: Long?)

    // Phase 2: chat organisation
    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun setPinned(chatId: String, pinned: Boolean)

    @Query("UPDATE chats SET isArchived = :archived WHERE id = :chatId")
    suspend fun setArchived(chatId: String, archived: Boolean)

    @Query("UPDATE chats SET muteUntil = :muteUntil WHERE id = :chatId")
    suspend fun setMuteUntil(chatId: String, muteUntil: Long)

    @Query("UPDATE chats SET cachedAvatarUrl = :cachedUrl, localAvatarPath = :localPath WHERE id = :chatId")
    suspend fun updateAvatarCache(chatId: String, cachedUrl: String?, localPath: String?)
}
