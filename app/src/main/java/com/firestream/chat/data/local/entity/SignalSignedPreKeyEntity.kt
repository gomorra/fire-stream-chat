package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_signed_prekeys")
data class SignalSignedPreKeyEntity(
    @PrimaryKey val signedPreKeyId: Int,
    val record: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalSignedPreKeyEntity) return false
        return signedPreKeyId == other.signedPreKeyId && record.contentEquals(other.record)
    }

    override fun hashCode(): Int = 31 * signedPreKeyId + record.contentHashCode()
}
