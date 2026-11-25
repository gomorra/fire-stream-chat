// region: AGENT-NOTE
// Responsibility: Application-data Room database (`fire_stream_chat.db`).
//   10 entities: Users, Messages, Chats, Contacts, Lists, Reminders, the sticker
//   library's Stickers, StickerPacks and StickerPackItems, and the message sync's
//   state per chat (MessageSyncState). Signal Protocol tables live in
//   SignalDatabase (`signal.db`).
// Owns: @Database `version` (CURRENT_VERSION) and explicit `Migration`s
//   (ALL_MIGRATIONS). Every schema change from v35 onward bumps `version`, adds
//   an explicit Migration, commits the exported schema JSON under `app/schemas/`,
//   and adds a test in AppDatabaseMigrationTest
//   (Pattern: docs/PATTERNS.md#room-version-bump-rule).
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
import com.firestream.chat.data.local.dao.MessageSyncStateDao
import com.firestream.chat.data.local.dao.ReminderDao
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.ContactEntity
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageSyncStateEntity
import com.firestream.chat.data.local.entity.ReminderEntity
import com.firestream.chat.data.local.entity.StickerEntity
import com.firestream.chat.data.local.entity.StickerPackEntity
import com.firestream.chat.data.local.entity.StickerPackItemEntity
import com.firestream.chat.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ChatEntity::class,
        ContactEntity::class,
        ListEntity::class,
        ReminderEntity::class,
        StickerEntity::class,
        StickerPackEntity::class,
        StickerPackItemEntity::class,
        MessageSyncStateEntity::class
    ],
    version = AppDatabase.CURRENT_VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun messageSyncStateDao(): MessageSyncStateDao
    abstract fun chatDao(): ChatDao
    abstract fun contactDao(): ContactDao
    abstract fun listDao(): ListDao
    abstract fun reminderDao(): ReminderDao
    abstract fun stickerDao(): StickerDao

    companion object {
        const val CURRENT_VERSION = 36

        /** Pre-v35 versions allowed to upgrade destructively. Installs at v35 or newer keep their decrypted history. */
        val LEGACY_DESTRUCTIVE_VERSIONS: IntArray = (1..34).toList().toIntArray()

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN localThumbUri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaKey TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaIv TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaSha256 TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thumbKey TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thumbIv TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thumbSha256 TEXT")
            }
        }

        /**
         * Explicit migrations from v35 onward. Legacy pre-v35 installs upgrade via
         * [LEGACY_DESTRUCTIVE_VERSIONS] (Room forbids overlapping migration
         * endpoints with fallbackToDestructiveMigrationFrom).
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_35_36)
    }
}
