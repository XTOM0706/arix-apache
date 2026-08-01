package com.arix.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.arix.data.entity.ConversationTagCrossRef
import com.arix.data.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAll(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTagToConversation(crossRef: ConversationTagCrossRef)

    @Query("DELETE FROM conversation_tag_cross_ref WHERE conversationId = :conversationId AND tagId = :tagId")
    suspend fun removeTagFromConversation(conversationId: Long, tagId: Long)

    @Query("DELETE FROM conversation_tag_cross_ref WHERE conversationId = :conversationId")
    suspend fun clearTagsForConversation(conversationId: Long)

    @Query("SELECT tags.* FROM tags INNER JOIN conversation_tag_cross_ref ON tags.id = conversation_tag_cross_ref.tagId WHERE conversation_tag_cross_ref.conversationId = :conversationId")
    suspend fun getTagsForConversation(conversationId: Long): List<TagEntity>

    @Query("SELECT * FROM conversation_tag_cross_ref WHERE tagId = :tagId")
    fun getConversationsForTag(tagId: Long): Flow<List<ConversationTagCrossRef>>
}
