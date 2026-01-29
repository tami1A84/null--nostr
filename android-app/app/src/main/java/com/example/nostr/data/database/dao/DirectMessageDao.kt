package com.example.nostr.data.database.dao

import androidx.room.*
import com.example.nostr.data.database.entity.DirectMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: DirectMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<DirectMessageEntity>)

    @Query("SELECT * FROM direct_messages WHERE id = :id")
    suspend fun getById(id: String): DirectMessageEntity?

    @Query("SELECT * FROM direct_messages WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun getByConversation(conversationId: String): Flow<List<DirectMessageEntity>>

    @Query("SELECT * FROM direct_messages WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentByConversation(conversationId: String, limit: Int = 50): List<DirectMessageEntity>

    @Query("""
        SELECT * FROM direct_messages
        WHERE id IN (
            SELECT id FROM direct_messages
            WHERE conversation_id = conversation_id
            GROUP BY conversation_id
            HAVING created_at = MAX(created_at)
        )
        ORDER BY created_at DESC
    """)
    fun getConversations(): Flow<List<DirectMessageEntity>>

    @Query("""
        SELECT DISTINCT conversation_id FROM direct_messages
        WHERE sender_pubkey = :userPubkey OR recipient_pubkey = :userPubkey
        ORDER BY created_at DESC
    """)
    fun getConversationIds(userPubkey: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM direct_messages WHERE conversation_id = :conversationId AND is_read = 0 AND is_outgoing = 0")
    fun getUnreadCount(conversationId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM direct_messages WHERE is_read = 0 AND is_outgoing = 0")
    fun getTotalUnreadCount(): Flow<Int>

    @Query("UPDATE direct_messages SET is_read = 1 WHERE conversation_id = :conversationId")
    suspend fun markAsRead(conversationId: String)

    @Query("UPDATE direct_messages SET decrypted_content = :decryptedContent WHERE id = :id")
    suspend fun updateDecryptedContent(id: String, decryptedContent: String)

    @Query("DELETE FROM direct_messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM direct_messages WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}
