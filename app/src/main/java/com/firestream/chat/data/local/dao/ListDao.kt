package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.firestream.chat.data.local.entity.ListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {

    @Query("SELECT * FROM lists ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE id = :listId")
    fun observeById(listId: String): Flow<ListEntity?>

    @Query("SELECT * FROM lists WHERE id = :listId")
    suspend fun getById(listId: String): ListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(list: ListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lists: List<ListEntity>)

    @Query("DELETE FROM lists WHERE id = :listId")
    suspend fun delete(listId: String)

    @Query("UPDATE lists SET items = :itemsJson, updatedAt = :updatedAt WHERE id = :listId")
    suspend fun updateItems(listId: String, itemsJson: String, updatedAt: Long)

    @Query("UPDATE lists SET title = :title, updatedAt = :updatedAt WHERE id = :listId")
    suspend fun updateTitle(listId: String, title: String, updatedAt: Long)

    @Query("UPDATE lists SET type = :type, updatedAt = :updatedAt WHERE id = :listId")
    suspend fun updateType(listId: String, type: String, updatedAt: Long)

    @Query("UPDATE lists SET sharedChatIds = :sharedChatIdsJson, updatedAt = :updatedAt WHERE id = :listId")
    suspend fun updateSharedChatIds(listId: String, sharedChatIdsJson: String, updatedAt: Long)
}
