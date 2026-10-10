package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What the message sync knows about one chat. A row exists only for a chat whose
 * whole history was fetched from the server on this install.
 *
 * [restoreGeneration] is the `MessageSyncPlan.RESTORE_GENERATION` that fetch ran
 * under. A row of an older generation counts as no row.
 *
 * [cursorMs] is the highest `timestamp` among the documents a sync fetched for
 * the chat. Only a sync writes it, and only from the server's answer. It never
 * moves back (`MessageSyncStateDao.raiseCursor`).
 *
 * The table sits beside `messages` on purpose. Whatever empties the messages
 * empties the state with them, and the next sync restores the chat. See
 * docs/PATTERNS.md "A sync cursor lives beside the rows it describes".
 */
@Entity(tableName = "message_sync_state")
data class MessageSyncStateEntity(
    @PrimaryKey val chatId: String,
    val restoreGeneration: Int,
    val cursorMs: Long,
)
