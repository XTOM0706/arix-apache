package com.arix.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.arix.data.entity.ApiConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiConfigDao {

    @Query("SELECT * FROM api_configs ORDER BY id ASC")
    fun getAll(): Flow<List<ApiConfigEntity>>

    @Query("SELECT * FROM api_configs WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ApiConfigEntity?

    @Query("SELECT * FROM api_configs WHERE isActive = 1 AND purpose = :purpose LIMIT 1")
    suspend fun getActiveByPurpose(purpose: String): ApiConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ApiConfigEntity): Long

    @Update
    suspend fun update(config: ApiConfigEntity)

    @Delete
    suspend fun delete(config: ApiConfigEntity)

    @Query("UPDATE api_configs SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE api_configs SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Query("UPDATE api_configs SET isActive = 0 WHERE purpose = :purpose")
    suspend fun deactivateByPurpose(purpose: String)

    @Query("SELECT * FROM api_configs WHERE id = :id")
    suspend fun getById(id: Long): ApiConfigEntity?
}
