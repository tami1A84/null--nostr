package com.example.nostr.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey
    val pubkey: String,

    val name: String? = null,

    @ColumnInfo(name = "display_name")
    val displayName: String? = null,

    val about: String? = null,

    val picture: String? = null,

    val banner: String? = null,

    val nip05: String? = null,

    @ColumnInfo(name = "nip05_verified")
    val nip05Verified: Boolean = false,

    val lud16: String? = null,

    val lud06: String? = null,

    val website: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Get display name with fallbacks
     */
    fun getDisplayNameOrName(): String {
        return displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: shortenPubkey(pubkey)
    }

    companion object {
        fun shortenPubkey(pubkey: String): String {
            return if (pubkey.length > 12) {
                "${pubkey.take(8)}...${pubkey.takeLast(4)}"
            } else pubkey
        }
    }
}
