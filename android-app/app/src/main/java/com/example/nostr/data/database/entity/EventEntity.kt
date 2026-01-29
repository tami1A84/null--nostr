package com.example.nostr.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.nostr.nostr.event.NostrEvent

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["pubkey"]),
        Index(value = ["kind"]),
        Index(value = ["created_at"]),
        Index(value = ["kind", "pubkey"])
    ]
)
data class EventEntity(
    @PrimaryKey
    val id: String,

    val pubkey: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    val kind: Int,

    val tags: List<List<String>>,

    val content: String,

    val sig: String,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toNostrEvent(): NostrEvent {
        return NostrEvent(
            id = id,
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = sig
        )
    }

    companion object {
        fun fromNostrEvent(event: NostrEvent): EventEntity {
            return EventEntity(
                id = event.id,
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                sig = event.sig
            )
        }
    }
}
