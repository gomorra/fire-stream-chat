package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_identity")
data class SignalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val identityKeyPair: ByteArray,
    val registrationId: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalIdentityEntity) return false
        return id == other.id && identityKeyPair.contentEquals(other.identityKeyPair) && registrationId == other.registrationId
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + identityKeyPair.contentHashCode()
        result = 31 * result + registrationId
        return result
    }
}
