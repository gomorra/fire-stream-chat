package com.firestream.chat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_prekeys")
data class SignalPreKeyEntity(
    @PrimaryKey val preKeyId: Int,
    val record: ByteArray,
    /**
     * Whether this pre-key's public half has been uploaded to the backend's
     * oneTimePreKeys pool. Defaults to 1 on v1→v2 upgrades so v1's already-
     * published preKeyId=1 is not re-uploaded to the new subcollection.
     */
    @ColumnInfo(defaultValue = "1")
    val uploaded: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalPreKeyEntity) return false
        return preKeyId == other.preKeyId && record.contentEquals(other.record) && uploaded == other.uploaded
    }

    override fun hashCode(): Int {
        var result = preKeyId
        result = 31 * result + record.contentHashCode()
        result = 31 * result + uploaded.hashCode()
        return result
    }
}
