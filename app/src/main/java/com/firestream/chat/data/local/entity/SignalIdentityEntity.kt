package com.firestream.chat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_identity")
data class SignalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val identityKeyPair: ByteArray,
    val registrationId: Int,
    /** Next monotonically increasing one-time pre-key id to allocate. Defaults to 2 on v1→v2 upgrades (v1 used id 1). */
    @ColumnInfo(defaultValue = "2")
    val nextPreKeyId: Int = 2,
    /**
     * Whether the v2 public bundle (without legacy top-level preKeyId) has been
     * published to the backend. Defaults to 0 on v1→v2 upgrades so upgraded
     * installs rewrite `/keyBundles/{uid}` cleanly on next start.
     */
    @ColumnInfo(defaultValue = "0")
    val bundlePublished: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignalIdentityEntity) return false
        return id == other.id &&
            identityKeyPair.contentEquals(other.identityKeyPair) &&
            registrationId == other.registrationId &&
            nextPreKeyId == other.nextPreKeyId &&
            bundlePublished == other.bundlePublished
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + identityKeyPair.contentHashCode()
        result = 31 * result + registrationId
        result = 31 * result + nextPreKeyId
        result = 31 * result + bundlePublished.hashCode()
        return result
    }
}
