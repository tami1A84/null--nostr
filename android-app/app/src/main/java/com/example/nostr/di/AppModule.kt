package com.example.nostr.di

import android.content.Context
import androidx.room.Room
import com.example.nostr.data.database.NostrDatabase
import com.example.nostr.data.database.dao.*
import com.example.nostr.network.relay.RelayConnection
import com.example.nostr.network.relay.RelayPool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return RelayConnection.createOkHttpClient()
    }

    @Provides
    @Singleton
    fun provideRelayPool(okHttpClient: OkHttpClient): RelayPool {
        return RelayPool(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideNostrDatabase(@ApplicationContext context: Context): NostrDatabase {
        return Room.databaseBuilder(
            context,
            NostrDatabase::class.java,
            "nostr_database"
        ).build()
    }

    @Provides
    fun provideEventDao(database: NostrDatabase): EventDao {
        return database.eventDao()
    }

    @Provides
    fun provideProfileDao(database: NostrDatabase): ProfileDao {
        return database.profileDao()
    }

    @Provides
    fun provideRelayDao(database: NostrDatabase): RelayDao {
        return database.relayDao()
    }

    @Provides
    fun provideContactDao(database: NostrDatabase): ContactDao {
        return database.contactDao()
    }

    @Provides
    fun provideReactionDao(database: NostrDatabase): ReactionDao {
        return database.reactionDao()
    }

    @Provides
    fun provideDirectMessageDao(database: NostrDatabase): DirectMessageDao {
        return database.directMessageDao()
    }
}
