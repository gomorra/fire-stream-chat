package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_kyber_prekeys")
data class SignalKyberPreKeyEntity(
    @PrimaryKey val preKeyId: Int,
    val record: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalKyberPreKeyEntity) return false
        return preKeyId == other.preKeyId && record.contentEquals(other.record)
    }

    override fun hashCode(): Int = 31 * preKeyId + record.contentHashCode()
}
