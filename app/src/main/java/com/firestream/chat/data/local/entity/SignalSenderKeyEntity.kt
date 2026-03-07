package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** key = "${name}:${deviceId}:${distributionId}" */
@Entity(tableName = "signal_sender_keys")
data class SignalSenderKeyEntity(
    @PrimaryKey val key: String,
    val record: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalSenderKeyEntity) return false
        return key == other.key && record.contentEquals(other.record)
    }

    override fun hashCode(): Int = 31 * key.hashCode() + record.contentHashCode()
}
