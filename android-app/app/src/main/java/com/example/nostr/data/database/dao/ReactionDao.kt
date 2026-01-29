package com.example.nostr.data.database.dao

import androidx.room.*
import com.example.nostr.data.database.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reaction: ReactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reactions: List<ReactionEntity>)

    @Query("SELECT * FROM reactions WHERE event_id = :eventId")
    fun getByEventId(eventId: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE event_id = :eventId")
    suspend fun getByEventIdList(eventId: String): List<ReactionEntity>

    @Query("SELECT COUNT(*) FROM reactions WHERE event_id = :eventId")
    fun getReactionCount(eventId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM reactions WHERE event_id = :eventId AND content = '+'")
    fun getLikeCount(eventId: String): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM reactions WHERE event_id = :eventId AND pubkey = :pubkey)")
    suspend fun hasReacted(eventId: String, pubkey: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM reactions WHERE event_id = :eventId AND pubkey = :pubkey)")
    fun observeHasReacted(eventId: String, pubkey: String): Flow<Boolean>

    @Query("SELECT * FROM reactions WHERE event_id = :eventId AND pubkey = :pubkey LIMIT 1")
    suspend fun getReaction(eventId: String, pubkey: String): ReactionEntity?

    @Query("DELETE FROM reactions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM reactions WHERE event_id = :eventId AND pubkey = :pubkey")
    suspend fun deleteByEventAndPubkey(eventId: String, pubkey: String)
}
