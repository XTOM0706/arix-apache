package com.arix.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.arix.data.entity.CharacterCardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterCardDao {

    @Query("SELECT * FROM character_cards ORDER BY isDefault DESC, createdAt DESC")
    fun getAll(): Flow<List<CharacterCardEntity>>

    @Query("SELECT * FROM character_cards WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): CharacterCardEntity?

    @Query("SELECT * FROM character_cards WHERE id = :id")
    suspend fun getById(id: Long): CharacterCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: CharacterCardEntity): Long

    @Update
    suspend fun update(card: CharacterCardEntity)

    @Query("DELETE FROM character_cards WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE character_cards SET isDefault = 0")
    suspend fun clearAllDefault()

    @Query("UPDATE character_cards SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("SELECT COUNT(*) FROM character_cards")
    suspend fun count(): Int
}
