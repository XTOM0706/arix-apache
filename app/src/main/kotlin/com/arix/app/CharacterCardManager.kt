package com.arix.app

import android.content.Context
import com.arix.data.db.AppDatabase
import com.arix.data.entity.CharacterCardEntity
import com.arix.data.repository.CharacterCardRepository
import kotlinx.coroutines.flow.Flow

class CharacterCardManager(context: Context) {

    private val dao = AppDatabase.getInstance(context).characterCardDao()
    private val repo = CharacterCardRepository(dao)

    val allCards: Flow<List<CharacterCardEntity>> = repo.allCards

    suspend fun getDefault(): CharacterCardEntity? = repo.getDefault()

    suspend fun getById(id: Long): CharacterCardEntity? = repo.getById(id)

    suspend fun create(
        name: String,
        description: String = "",
        characterSetting: String = "",
        openingStatement: String = "",
        avatarPath: String = "",
        tone: String = "",
        length: String = "",
        language: String = "",
        worldBook: String = ""
    ): Long {
        return repo.add(
            CharacterCardEntity(
                name = name, description = description,
                characterSetting = characterSetting, worldBook = worldBook, openingStatement = openingStatement,
                avatarPath = avatarPath, tone = tone, length = length, language = language
            )
        )
    }

    suspend fun update(card: CharacterCardEntity) = repo.update(card)

    suspend fun delete(id: Long) = repo.delete(id)

    suspend fun setDefault(id: Long) = repo.setDefault(id)
}
