package com.example.nostr.data.database.dao

import androidx.room.*
import com.example.nostr.data.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<ContactEntity>)

    @Query("SELECT * FROM contacts WHERE owner_pubkey = :ownerPubkey")
    fun getContacts(ownerPubkey: String): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE owner_pubkey = :ownerPubkey")
    suspend fun getContactsList(ownerPubkey: String): List<ContactEntity>

    @Query("SELECT contact_pubkey FROM contacts WHERE owner_pubkey = :ownerPubkey")
    suspend fun getContactPubkeys(ownerPubkey: String): List<String>

    @Query("SELECT * FROM contacts WHERE owner_pubkey = :ownerPubkey AND contact_pubkey = :contactPubkey")
    suspend fun getContact(ownerPubkey: String, contactPubkey: String): ContactEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE owner_pubkey = :ownerPubkey AND contact_pubkey = :contactPubkey)")
    suspend fun isFollowing(ownerPubkey: String, contactPubkey: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE owner_pubkey = :ownerPubkey AND contact_pubkey = :contactPubkey)")
    fun observeIsFollowing(ownerPubkey: String, contactPubkey: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM contacts WHERE owner_pubkey = :ownerPubkey")
    suspend fun getFollowingCount(ownerPubkey: String): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE owner_pubkey = :ownerPubkey")
    fun observeFollowingCount(ownerPubkey: String): Flow<Int>

    @Query("DELETE FROM contacts WHERE owner_pubkey = :ownerPubkey AND contact_pubkey = :contactPubkey")
    suspend fun delete(ownerPubkey: String, contactPubkey: String)

    @Query("DELETE FROM contacts WHERE owner_pubkey = :ownerPubkey")
    suspend fun deleteAll(ownerPubkey: String)

    @Transaction
    suspend fun replaceAll(ownerPubkey: String, contacts: List<ContactEntity>) {
        deleteAll(ownerPubkey)
        insertAll(contacts)
    }
}
