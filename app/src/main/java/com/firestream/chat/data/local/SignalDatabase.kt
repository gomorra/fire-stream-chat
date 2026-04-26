// region: AGENT-NOTE
// Responsibility: Dedicated Room DB for Signal Protocol key material (`signal.db`).
//   7 tables — identities, sessions, prekeys, signed prekeys, kyber prekeys,
//   sender keys, trusted identities. Lives separately from AppDatabase so a
//   destructive migration on application data cannot wipe cryptographic state.
// Owns: SignalDao + Signal Protocol persistence. Cleared by AuthRepositoryImpl
//   on sign-out so a fresh signed-in user gets fresh keys.
// Collaborators: SignalProtocolStoreImpl (uses the DAO), SignalManager
//   (orchestrates encrypt/decrypt over the store), DatabaseModule (Hilt provider).
// Don't put here: application data of any kind (AppDatabase). On schema change,
//   bump version (Pattern: docs/PATTERNS.md#room-version-bump-rule).
// endregion

package com.firestream.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.local.entity.SignalIdentityEntity
import com.firestream.chat.data.local.entity.SignalKyberPreKeyEntity
import com.firestream.chat.data.local.entity.SignalPreKeyEntity
import com.firestream.chat.data.local.entity.SignalSenderKeyEntity
import com.firestream.chat.data.local.entity.SignalSessionEntity
import com.firestream.chat.data.local.entity.SignalSignedPreKeyEntity
import com.firestream.chat.data.local.entity.SignalTrustedIdentityEntity

@Database(
    entities = [
        SignalIdentityEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalSessionEntity::class,
        SignalKyberPreKeyEntity::class,
        SignalSenderKeyEntity::class,
        SignalTrustedIdentityEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao
}
