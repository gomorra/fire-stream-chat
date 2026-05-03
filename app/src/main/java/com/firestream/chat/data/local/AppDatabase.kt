// region: AGENT-NOTE
// Responsibility: Application-data Room database (`fire_stream_chat.db`).
//   5 entities: Users, Messages, Chats, Contacts, Lists. Signal Protocol tables
//   were split out into SignalDatabase (`signal.db`) at version 19 so destructive
//   migrations on this DB no longer wipe key material.
// Owns: @Database `version` field — bump on any column/table add/remove/rename
//   (Pattern: docs/PATTERNS.md#room-version-bump-rule). Migration registrations.
// Collaborators: DatabaseModule (Hilt provider), all DAOs in dao/, all entities
//   in entity/.
// Don't put here: Signal Protocol entities (SignalDatabase), DataStore
//   preferences (PreferencesDataStore), in-memory caches.
// endregion

package com.firestream.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.ContactEntity
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ChatEntity::class,
        ContactEntity::class,
        ListEntity::class
    ],
    version = 21,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun contactDao(): ContactDao
    abstract fun listDao(): ListDao

    companion object {
        // Signal Protocol tables moved to a dedicated SignalDatabase so that destructive
        // schema migrations on this database no longer wipe key material.
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS signal_identity")
                db.execSQL("DROP TABLE IF EXISTS signal_prekeys")
                db.execSQL("DROP TABLE IF EXISTS signal_signed_prekeys")
                db.execSQL("DROP TABLE IF EXISTS signal_sessions")
                db.execSQL("DROP TABLE IF EXISTS signal_kyber_prekeys")
                db.execSQL("DROP TABLE IF EXISTS signal_sender_keys")
                db.execSQL("DROP TABLE IF EXISTS signal_trusted_identities")
            }
        }
    }
}
