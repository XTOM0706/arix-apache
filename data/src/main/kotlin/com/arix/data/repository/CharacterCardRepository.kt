package com.arix.data.repository

import com.arix.data.dao.CharacterCardDao
import com.arix.data.entity.CharacterCardEntity
import kotlinx.coroutines.flow.Flow

class CharacterCardRepository(private val dao: CharacterCardDao) {

    val allCards: Flow<List<CharacterCardEntity>> = dao.getAll()

    suspend fun getDefault(): CharacterCardEntity? = dao.getDefault()

    suspend fun getById(id: Long): CharacterCardEntity? = dao.getById(id)

    suspend fun add(card: CharacterCardEntity): Long = dao.insert(card)

    suspend fun update(card: CharacterCardEntity) = dao.update(card)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setDefault(id: Long) { dao.clearAllDefault(); dao.markDefault(id) }

    suspend fun count(): Int = dao.count()
}
