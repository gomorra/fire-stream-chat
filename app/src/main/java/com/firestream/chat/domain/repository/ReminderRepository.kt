package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Reminder
import kotlinx.coroutines.flow.Flow

interface ReminderRepository {
    fun observePending(): Flow<List<Reminder>>
    fun observePendingIdsForChat(chatId: String): Flow<Set<String>>
    suspend fun getPending(): List<Reminder>
    suspend fun schedule(reminder: Reminder): Result<Unit>
    suspend fun cancel(messageId: String): Result<Unit>
    suspend fun reschedule(messageId: String, newFireAtMs: Long): Result<Unit>
}
