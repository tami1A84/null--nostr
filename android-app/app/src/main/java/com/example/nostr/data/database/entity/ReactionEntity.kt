package com.example.nostr.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reactions",
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["pubkey"]),
        Index(value = ["event_id", "pubkey"])
    ]
)
data class ReactionEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "event_id")
    val eventId: String,

    val pubkey: String,

    val content: String = "+",

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
