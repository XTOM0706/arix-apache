package com.arix.data.repository

import com.arix.data.dao.ApiConfigDao
import com.arix.data.entity.ApiConfigEntity
import kotlinx.coroutines.flow.Flow

class ApiConfigRepository(private val dao: ApiConfigDao) {

    val allConfigs: Flow<List<ApiConfigEntity>> = dao.getAll()

    suspend fun getActive(): ApiConfigEntity? = dao.getActive()

    suspend fun getActiveByPurpose(purpose: String): ApiConfigEntity? = dao.getActiveByPurpose(purpose)

    suspend fun add(config: ApiConfigEntity): Long = dao.insert(config)

    suspend fun update(config: ApiConfigEntity) = dao.update(config)

    suspend fun delete(config: ApiConfigEntity) = dao.delete(config)

    suspend fun switchTo(id: Long) {
        val config = dao.getById(id) ?: return
        dao.deactivateByPurpose(config.purpose)
        dao.activate(id)
    }
}
