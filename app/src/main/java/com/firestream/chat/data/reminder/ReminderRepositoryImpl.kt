// region: AGENT-NOTE
// Responsibility: Message-snooze reminder CRUD — one pending reminder per
//   message, persisted in Room with snapshot columns (message text, sender
//   name) so a fired notification can render even if the source message was
//   since deleted. Local-only, mirrors the star-message feature: no Firestore,
//   no flavor code.
// Owns: ReminderEntity rows. Delegates OS alarm arming/cancellation to
//   ReminderAlarmScheduling (NoOp until Step 2's real ReminderAlarmScheduler
//   lands per .claude/plans/message-snooze-reminders.md).
// Collaborators: ReminderDao, ReminderAlarmScheduling.
// Don't put here: alarm/notification/boot-restore logic (data/reminder/*
//   added in Step 2), snooze-preset time math (domain/reminder/SnoozePresets,
//   Step 3). If you add/rename a column here, bump AppDatabase.version
//   (docs/PATTERNS.md#room-version-bump-rule).
// endregion

package com.firestream.chat.data.reminder

import com.firestream.chat.data.local.dao.ReminderDao
import com.firestream.chat.data.local.entity.ReminderEntity
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.domain.model.Reminder
import com.firestream.chat.domain.repository.ReminderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepositoryImpl @Inject constructor(
    private val reminderDao: ReminderDao,
    private val alarmScheduling: ReminderAlarmScheduling
) : ReminderRepository {

    override fun observePending(): Flow<List<Reminder>> =
        reminderDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observePendingIdsForChat(chatId: String): Flow<Set<String>> =
        reminderDao.observeMessageIdsForChat(chatId).map { it.toSet() }

    override suspend fun getPending(): List<Reminder> =
        reminderDao.getAll().map { it.toDomain() }

    override suspend fun schedule(reminder: Reminder): Result<Unit> = resultOf {
        reminderDao.upsert(ReminderEntity.fromDomain(reminder))
        alarmScheduling.schedule(reminder)
    }

    override suspend fun cancel(messageId: String): Result<Unit> = resultOf {
        alarmScheduling.cancel(messageId)
        reminderDao.deleteByMessageId(messageId)
    }

    override suspend fun reschedule(messageId: String, newFireAtMs: Long): Result<Unit> = resultOf {
        reminderDao.updateFireAt(messageId, newFireAtMs)
        val updated = reminderDao.getAll().firstOrNull { it.messageId == messageId }
            ?: error("No pending reminder for message $messageId")
        alarmScheduling.schedule(updated.toDomain())
    }
}
