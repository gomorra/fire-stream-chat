package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.firestream.chat.data.local.entity.SignalIdentityEntity
import com.firestream.chat.data.local.entity.SignalKyberPreKeyEntity
import com.firestream.chat.data.local.entity.SignalPreKeyEntity
import com.firestream.chat.data.local.entity.SignalSenderKeyEntity
import com.firestream.chat.data.local.entity.SignalSessionEntity
import com.firestream.chat.data.local.entity.SignalSignedPreKeyEntity
import com.firestream.chat.data.local.entity.SignalTrustedIdentityEntity

/**
 * DAO for Signal Protocol key material.
 * Non-suspend methods are intentional — they are called from SignalProtocolStoreImpl which
 * is always accessed on Dispatchers.IO via SignalManager. Room blocks on the calling thread
 * for non-suspend queries (safe on any non-main thread).
 */
@Dao
interface SignalDao {

    // ── Identity ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveIdentity(entity: SignalIdentityEntity)

    @Query("SELECT * FROM signal_identity WHERE id = 1 LIMIT 1")
    fun getIdentity(): SignalIdentityEntity?

    @Query("UPDATE signal_identity SET nextPreKeyId = :nextPreKeyId WHERE id = 1")
    fun updateNextPreKeyId(nextPreKeyId: Int)

    @Query("UPDATE signal_identity SET bundlePublished = 1 WHERE id = 1")
    fun markBundlePublished()

    /**
     * Persists all initial registration key material in one SQLite transaction
     * so a crash mid-init can never leave `signal_identity` present without its
     * signed, Kyber, and one-time pre-keys.
     */
    @Transaction
    fun saveInitialKeys(
        identity: SignalIdentityEntity,
        signedPreKey: SignalSignedPreKeyEntity,
        kyberPreKey: SignalKyberPreKeyEntity,
        preKeys: List<SignalPreKeyEntity>,
    ) {
        saveSignedPreKey(signedPreKey)
        saveKyberPreKey(kyberPreKey)
        savePreKeys(preKeys)
        saveIdentity(identity)
    }

    // ── Pre-keys ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePreKey(entity: SignalPreKeyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePreKeys(entities: List<SignalPreKeyEntity>)

    @Query("SELECT * FROM signal_prekeys WHERE preKeyId = :id LIMIT 1")
    fun getPreKey(id: Int): SignalPreKeyEntity?

    @Query("SELECT * FROM signal_prekeys WHERE uploaded = 0 ORDER BY preKeyId ASC")
    fun getUnuploadedPreKeys(): List<SignalPreKeyEntity>

    @Query("UPDATE signal_prekeys SET uploaded = 1 WHERE preKeyId IN (:ids)")
    fun markPreKeysUploaded(ids: List<Int>)

    @Query("SELECT COUNT(*) FROM signal_prekeys")
    fun countPreKeys(): Int

    @Query("SELECT MAX(preKeyId) FROM signal_prekeys")
    fun getMaxPreKeyId(): Int?

    @Query("DELETE FROM signal_prekeys WHERE preKeyId = :id")
    fun deletePreKey(id: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_prekeys WHERE preKeyId = :id)")
    fun containsPreKey(id: Int): Boolean

    // ── Signed pre-keys ───────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSignedPreKey(entity: SignalSignedPreKeyEntity)

    @Query("SELECT * FROM signal_signed_prekeys WHERE signedPreKeyId = :id LIMIT 1")
    fun getSignedPreKey(id: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys")
    fun getAllSignedPreKeys(): List<SignalSignedPreKeyEntity>

    @Query("DELETE FROM signal_signed_prekeys WHERE signedPreKeyId = :id")
    fun deleteSignedPreKey(id: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_signed_prekeys WHERE signedPreKeyId = :id)")
    fun containsSignedPreKey(id: Int): Boolean

    // ── Sessions ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSession(entity: SignalSessionEntity)

    @Query("SELECT * FROM signal_sessions WHERE address = :address LIMIT 1")
    fun getSession(address: String): SignalSessionEntity?

    @Query("SELECT * FROM signal_sessions WHERE address LIKE :namePrefix || ':%'")
    fun getSessionsByName(namePrefix: String): List<SignalSessionEntity>

    @Query("DELETE FROM signal_sessions WHERE address = :address")
    fun deleteSession(address: String)

    @Query("DELETE FROM signal_sessions WHERE address LIKE :namePrefix || ':%'")
    fun deleteAllSessionsByName(namePrefix: String)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_sessions WHERE address = :address)")
    fun containsSession(address: String): Boolean

    // ── Kyber pre-keys ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveKyberPreKey(entity: SignalKyberPreKeyEntity)

    @Query("SELECT * FROM signal_kyber_prekeys WHERE preKeyId = :id LIMIT 1")
    fun getKyberPreKey(id: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys")
    fun getAllKyberPreKeys(): List<SignalKyberPreKeyEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM signal_kyber_prekeys WHERE preKeyId = :id)")
    fun containsKyberPreKey(id: Int): Boolean

    // ── Sender keys ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSenderKey(entity: SignalSenderKeyEntity)

    @Query("SELECT * FROM signal_sender_keys WHERE `key` = :key LIMIT 1")
    fun getSenderKey(key: String): SignalSenderKeyEntity?

    // ── Trusted identities ────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveTrustedIdentity(entity: SignalTrustedIdentityEntity)

    @Query("SELECT * FROM signal_trusted_identities WHERE address = :address LIMIT 1")
    fun getTrustedIdentity(address: String): SignalTrustedIdentityEntity?

    @Query("DELETE FROM signal_trusted_identities WHERE address = :address")
    fun deleteTrustedIdentity(address: String)
}
