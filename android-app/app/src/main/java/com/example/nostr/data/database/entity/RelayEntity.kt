package com.example.nostr.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relays")
data class RelayEntity(
    @PrimaryKey
    val url: String,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = true,

    @ColumnInfo(name = "is_write")
    val isWrite: Boolean = true,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
