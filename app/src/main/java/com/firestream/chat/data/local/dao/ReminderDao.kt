package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.firestream.chat.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY fireAtMs ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT messageId FROM reminders WHERE chatId = :chatId")
    fun observeMessageIdsForChat(chatId: String): Flow<List<String>>

    @Query("SELECT * FROM reminders ORDER BY fireAtMs ASC")
    suspend fun getAll(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity)

    @Query("DELETE FROM reminders WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: String)

    @Query("UPDATE reminders SET fireAtMs = :fireAtMs WHERE messageId = :messageId")
    suspend fun updateFireAt(messageId: String, fireAtMs: Long)
}
