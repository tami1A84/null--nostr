package com.example.nostr.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "direct_messages",
    indices = [
        Index(value = ["sender_pubkey"]),
        Index(value = ["recipient_pubkey"]),
        Index(value = ["conversation_id"]),
        Index(value = ["created_at"])
    ]
)
data class DirectMessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "sender_pubkey")
    val senderPubkey: String,

    @ColumnInfo(name = "recipient_pubkey")
    val recipientPubkey: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "encrypted_content")
    val encryptedContent: String,

    @ColumnInfo(name = "decrypted_content")
    val decryptedContent: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean = false
) {
    companion object {
        /**
         * Generate conversation ID from two pubkeys (sorted to be consistent)
         */
        fun generateConversationId(pubkey1: String, pubkey2: String): String {
            return listOf(pubkey1, pubkey2).sorted().joinToString(":")
        }
    }
}
