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
import androidx.sqlite.db.SupportSQLiteDatabase
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
        const val CURRENT_VERSION = 2

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE signal_identity ADD COLUMN nextPreKeyId INTEGER NOT NULL DEFAULT 2")
                // Force upgraded v1 installs to rewrite /keyBundles/{uid} without legacy preKeyId=1.
                db.execSQL("ALTER TABLE signal_identity ADD COLUMN bundlePublished INTEGER NOT NULL DEFAULT 0")
                // Mark v1's existing preKeyId=1 as uploaded so it is never re-published to oneTimePreKeys.
                db.execSQL("ALTER TABLE signal_prekeys ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 1")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
        )
    }
}
