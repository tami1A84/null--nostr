package com.example.nostr.data.database.dao

import androidx.room.*
import com.example.nostr.data.database.entity.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE pubkey = :pubkey ORDER BY created_at DESC")
    fun getByPubkey(pubkey: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind = :kind ORDER BY created_at DESC LIMIT :limit")
    fun getByKind(kind: Int, limit: Int = 100): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind = 1 ORDER BY created_at DESC LIMIT :limit")
    fun getTextNotes(limit: Int = 100): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind = 1 AND pubkey IN (:pubkeys) ORDER BY created_at DESC LIMIT :limit")
    fun getTextNotesFromAuthors(pubkeys: List<String>, limit: Int = 100): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind = 1 AND created_at > :since ORDER BY created_at DESC LIMIT :limit")
    fun getTextNotesSince(since: Long, limit: Int = 100): Flow<List<EventEntity>>

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM events WHERE pubkey = :pubkey")
    suspend fun deleteByPubkey(pubkey: String)

    @Query("DELETE FROM events WHERE cached_at < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM events WHERE kind = :kind")
    suspend fun getCountByKind(kind: Int): Int
}
