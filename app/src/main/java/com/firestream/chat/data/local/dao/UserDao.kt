package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.firestream.chat.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE uid = :uid")
    suspend fun getUserById(uid: String): UserEntity?

    @Query("SELECT * FROM users WHERE uid = :uid")
    fun observeUser(uid: String): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Query("DELETE FROM users WHERE uid = :uid")
    suspend fun deleteUser(uid: String)

    @Query("UPDATE users SET cachedAvatarUrl = :cachedUrl, localAvatarPath = :localPath WHERE uid = :uid")
    suspend fun updateAvatarCache(uid: String, cachedUrl: String?, localPath: String?)
}
