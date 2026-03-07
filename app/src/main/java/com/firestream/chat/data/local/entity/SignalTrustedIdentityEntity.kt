package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** address = UID of the remote user */
@Entity(tableName = "signal_trusted_identities")
data class SignalTrustedIdentityEntity(
    @PrimaryKey val address: String,
    val identityKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalTrustedIdentityEntity) return false
        return address == other.address && identityKey.contentEquals(other.identityKey)
    }

    override fun hashCode(): Int = 31 * address.hashCode() + identityKey.contentHashCode()
}
