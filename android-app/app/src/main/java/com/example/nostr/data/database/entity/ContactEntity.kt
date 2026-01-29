package com.example.nostr.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "contacts",
    primaryKeys = ["owner_pubkey", "contact_pubkey"],
    indices = [
        Index(value = ["owner_pubkey"]),
        Index(value = ["contact_pubkey"])
    ]
)
data class ContactEntity(
    @ColumnInfo(name = "owner_pubkey")
    val ownerPubkey: String,

    @ColumnInfo(name = "contact_pubkey")
    val contactPubkey: String,

    val relay: String? = null,

    val petname: String? = null,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
