package com.example.nostr.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.nostr.data.database.dao.*
import com.example.nostr.data.database.entity.*

@Database(
    entities = [
        EventEntity::class,
        ProfileEntity::class,
        RelayEntity::class,
        ContactEntity::class,
        ReactionEntity::class,
        DirectMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NostrDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun profileDao(): ProfileDao
    abstract fun relayDao(): RelayDao
    abstract fun contactDao(): ContactDao
    abstract fun reactionDao(): ReactionDao
    abstract fun directMessageDao(): DirectMessageDao
}
