package com.firestream.chat.di

import android.content.Context
import androidx.room.Room
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.SignalDatabase
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.MessageSyncStateDao
import com.firestream.chat.data.local.dao.ReminderDao
import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.local.dao.StickerDao
import com.firestream.chat.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "fire_stream_chat.db"
        )
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            // Destructive reset is permitted only for pre-v35 legacy installs.
            // Every bump from v35 onward must ship an explicit Migration so
            // decrypted E2EE history is never wiped on upgrade.
            .fallbackToDestructiveMigrationFrom(*AppDatabase.LEGACY_DESTRUCTIVE_VERSIONS)
            .build()
    }

    @Provides
    @Singleton
    fun provideSignalDatabase(@ApplicationContext context: Context): SignalDatabase {
        return Room.databaseBuilder(
            context,
            SignalDatabase::class.java,
            "signal.db"
        )
            .addMigrations(*SignalDatabase.ALL_MIGRATIONS)
            .build()
    }

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideMessageSyncStateDao(db: AppDatabase): MessageSyncStateDao = db.messageSyncStateDao()

    @Provides
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideSignalDao(db: SignalDatabase): SignalDao = db.signalDao()

    @Provides
    fun provideListDao(db: AppDatabase): ListDao = db.listDao()

    @Provides
    fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideStickerDao(db: AppDatabase): StickerDao = db.stickerDao()
}
