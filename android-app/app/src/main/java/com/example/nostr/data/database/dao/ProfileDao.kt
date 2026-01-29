package com.example.nostr.data.database.dao

import androidx.room.*
import com.example.nostr.data.database.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>)

    @Query("SELECT * FROM profiles WHERE pubkey = :pubkey")
    suspend fun getByPubkey(pubkey: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE pubkey = :pubkey")
    fun observeByPubkey(pubkey: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE pubkey IN (:pubkeys)")
    suspend fun getByPubkeys(pubkeys: List<String>): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE pubkey IN (:pubkeys)")
    fun observeByPubkeys(pubkeys: List<String>): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE name LIKE '%' || :query || '%' OR display_name LIKE '%' || :query || '%' OR nip05 LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun search(query: String, limit: Int = 50): List<ProfileEntity>

    @Query("DELETE FROM profiles WHERE pubkey = :pubkey")
    suspend fun delete(pubkey: String)

    @Query("DELETE FROM profiles WHERE updated_at < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Update
    suspend fun update(profile: ProfileEntity)
}
