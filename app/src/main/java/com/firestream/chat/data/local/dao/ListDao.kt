package com.firestream.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.firestream.chat.data.local.entity.ListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {

    @Query("SELECT * FROM lists ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE participants LIKE '%\"' || :userId || '\"%' ORDER BY updatedAt DESC")
    fun getListsForUser(userId: String): Flow<List<ListEntity>>

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

    @Query("DELETE FROM lists WHERE id NOT IN (:ids) AND participants LIKE '%\"' || :userId || '\"%'")
    suspend fun deleteUnlistedForUser(ids: List<String>, userId: String)

    @Query("DELETE FROM lists WHERE participants LIKE '%\"' || :userId || '\"%'")
    suspend fun deleteAllForUser(userId: String)

    /** Upserts the Firestore set and removes any local records the user is no longer a participant of. */
    @Transaction
    suspend fun syncForUser(lists: List<ListEntity>, userId: String) {
        insertAll(lists)
        val ids = lists.map { it.id }
        if (ids.isEmpty()) {
            deleteAllForUser(userId)
        } else {
            deleteUnlistedForUser(ids, userId)
        }
    }
}
