package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.firestream.chat.data.local.entity.MessageSyncStateEntity

/**
 * The sync state of each chat. One chat's row leaves together with the chat's
 * messages, through `MessageDao.deleteChatMessages`.
 */
@Dao
interface MessageSyncStateDao {

    @Query("SELECT * FROM message_sync_state WHERE chatId = :chatId")
    suspend fun getState(chatId: String): MessageSyncStateEntity?

    /**
     * Records that [chatId] was fetched whole under [generation]. A row of the
     * same generation keeps the higher cursor, so a restore that finishes after
     * another sync of the chat cannot move the cursor back. A row of another
     * generation is replaced.
     *
     * It writes nothing for a chat that has no row in `chats`. A chat deleted or
     * a sign-out while its restore ran has taken the messages with it, and a row
     * written afterwards would call that chat restored.
     */
    @Query(
        """
        INSERT INTO message_sync_state (chatId, restoreGeneration, cursorMs)
        SELECT :chatId, :generation, :cursorMs
        WHERE EXISTS (SELECT 1 FROM chats WHERE id = :chatId)
        ON CONFLICT(chatId) DO UPDATE SET
            cursorMs = CASE
                WHEN restoreGeneration = excluded.restoreGeneration THEN MAX(cursorMs, excluded.cursorMs)
                ELSE excluded.cursorMs
            END,
            restoreGeneration = excluded.restoreGeneration
        """
    )
    suspend fun writeRestored(chatId: String, generation: Int, cursorMs: Long)

    /**
     * Raises the cursor of a chat restored under [generation] to [cursorMs].
     * It never lowers one. It writes nothing for a chat without a row or with a
     * row of another generation: that chat is restored first.
     */
    @Query(
        """
        UPDATE message_sync_state SET cursorMs = MAX(cursorMs, :cursorMs)
        WHERE chatId = :chatId AND restoreGeneration = :generation
        """
    )
    suspend fun raiseCursor(chatId: String, generation: Int, cursorMs: Long)

    @Query("DELETE FROM message_sync_state")
    suspend fun deleteAll()
}
