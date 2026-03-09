package com.firestream.chat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.SignalDao
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.local.entity.ContactEntity
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.SignalIdentityEntity
import com.firestream.chat.data.local.entity.SignalKyberPreKeyEntity
import com.firestream.chat.data.local.entity.SignalPreKeyEntity
import com.firestream.chat.data.local.entity.SignalSenderKeyEntity
import com.firestream.chat.data.local.entity.SignalSessionEntity
import com.firestream.chat.data.local.entity.SignalSignedPreKeyEntity
import com.firestream.chat.data.local.entity.SignalTrustedIdentityEntity
import com.firestream.chat.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        MessageEntity::class,
        ChatEntity::class,
        ContactEntity::class,
        SignalIdentityEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalSessionEntity::class,
        SignalKyberPreKeyEntity::class,
        SignalSenderKeyEntity::class,
        SignalTrustedIdentityEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao
    abstract fun contactDao(): ContactDao
    abstract fun signalDao(): SignalDao
}
