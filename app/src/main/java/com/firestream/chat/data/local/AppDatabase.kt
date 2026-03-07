package com.firestream.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.ContactEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ChatEntity::class,
        ContactEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun contactDao(): ContactDao
}
