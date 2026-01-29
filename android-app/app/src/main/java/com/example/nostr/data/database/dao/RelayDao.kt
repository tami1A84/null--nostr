package com.example.nostr.data.database.dao

import androidx.room.*
import com.example.nostr.data.database.entity.RelayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relay: RelayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(relays: List<RelayEntity>)

    @Query("SELECT * FROM relays WHERE url = :url")
    suspend fun getByUrl(url: String): RelayEntity?

    @Query("SELECT * FROM relays ORDER BY added_at ASC")
    fun getAll(): Flow<List<RelayEntity>>

    @Query("SELECT * FROM relays WHERE is_enabled = 1 ORDER BY added_at ASC")
    fun getEnabled(): Flow<List<RelayEntity>>

    @Query("SELECT * FROM relays WHERE is_enabled = 1 AND is_read = 1")
    suspend fun getReadRelays(): List<RelayEntity>

    @Query("SELECT * FROM relays WHERE is_enabled = 1 AND is_write = 1")
    suspend fun getWriteRelays(): List<RelayEntity>

    @Query("SELECT * FROM relays WHERE is_default = 1")
    suspend fun getDefaultRelays(): List<RelayEntity>

    @Update
    suspend fun update(relay: RelayEntity)

    @Query("DELETE FROM relays WHERE url = :url")
    suspend fun delete(url: String)

    @Query("UPDATE relays SET is_enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)
}
