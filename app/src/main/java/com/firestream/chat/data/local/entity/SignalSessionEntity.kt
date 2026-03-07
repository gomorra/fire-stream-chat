package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** address = "${uid}:${deviceId}" e.g. "abc123:1" */
@Entity(tableName = "signal_sessions")
data class SignalSessionEntity(
    @PrimaryKey val address: String,
    val record: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalSessionEntity) return false
        return address == other.address && record.contentEquals(other.record)
    }

    override fun hashCode(): Int = 31 * address.hashCode() + record.contentHashCode()
}
