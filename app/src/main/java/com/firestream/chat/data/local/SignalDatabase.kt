// region: AGENT-NOTE
// Responsibility: Dedicated Room DB for Signal Protocol key material (`signal.db`).
//   7 tables — identities, sessions, prekeys, signed prekeys, kyber prekeys,
//   sender keys, trusted identities. Destructive fallback is disabled so
//   upgrades never wipe cryptographic state.
// Owns: SignalDao, @Database `version` (CURRENT_VERSION), and explicit
//   `Migration`s (ALL_MIGRATIONS). Every schema change bumps `version`, adds an
//   explicit Migration, commits the exported schema JSON under `app/schemas/`,
//   and adds a test in AppDatabaseMigrationTest
//   (Pattern: docs/PATTERNS.md#room-version-bump-rule). Cleared by
//   AuthRepositoryImpl on sign-out so a fresh signed-in user gets fresh keys.
// Collaborators: SignalProtocolStoreImpl (uses the DAO), SignalManager
//   (orchestrates encrypt/decrypt over the store), DatabaseModule (Hilt provider).
// Don't put here: application data of any kind (AppDatabase).
// endregion

package com.firestream.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
    version = SignalDatabase.CURRENT_VERSION,
    exportSchema = true,
)
abstract class SignalDatabase : RoomDatabase() {
    abstract fun signalDao(): SignalDao

    companion object {
        const val CURRENT_VERSION = 1

        val ALL_MIGRATIONS: Array<Migration> = emptyArray()
    }
}
