package com.firestream.chat.di

import android.content.Context
import androidx.room.Room
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.SignalDao
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
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideChatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides
    fun provideContactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides
    fun provideSignalDao(db: AppDatabase): SignalDao = db.signalDao()
}
