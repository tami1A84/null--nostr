package com.example.nostr.network.relay

import com.example.nostr.nostr.event.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a pool of relay connections
 */
@Singleton
class RelayPool @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Default relays to connect to
     */
    val defaultRelays = listOf(
        "wss://relay.damus.io",
        "wss://relay.nostr.band",
        "wss://nos.lol",
        "wss://relay.snort.social",
        "wss://nostr.wine",
        "wss://relay.nostr.wirednet.jp"
    )

    /**
     * Get or create a connection to a relay
     */
    fun getConnection(url: String): RelayConnection {
        return connections.getOrPut(url) {
            RelayConnection(url, okHttpClient).also { connection ->
                connection.connect()
            }
        }
    }

    /**
     * Connect to all default relays
     */
    fun connectToDefaults() {
        defaultRelays.forEach { url ->
            getConnection(url)
        }
    }

    /**
     * Connect to specific relays
     */
    fun connectTo(urls: List<String>) {
        urls.forEach { url ->
            getConnection(url)
        }
    }

    /**
     * Disconnect from a relay
     */
    fun disconnect(url: String) {
        connections.remove(url)?.disconnect()
    }

    /**
     * Disconnect from all relays
     */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
    }

    /**
     * Get all connected relays
     */
    fun getConnectedRelays(): List<RelayConnection> {
        return connections.values.filter {
            it.connectionState.value == RelayConnection.ConnectionState.Connected
        }
    }

    /**
     * Publish an event to all connected relays
     */
    suspend fun publishEvent(event: NostrEvent): Map<String, Result<RelayMessage.Ok>> {
        val connectedRelays = getConnectedRelays()
        if (connectedRelays.isEmpty()) {
            return emptyMap()
        }

        return connectedRelays.associate { relay ->
            relay.url to relay.publishEvent(event)
        }
    }

    /**
     * Publish an event to specific relays
     */
    suspend fun publishEventTo(event: NostrEvent, urls: List<String>): Map<String, Result<RelayMessage.Ok>> {
        return urls.mapNotNull { url ->
            connections[url]?.let { relay ->
                url to relay.publishEvent(event)
            }
        }.toMap()
    }

    /**
     * Subscribe to events across all connected relays
     */
    fun subscribe(filters: List<Filter>): Flow<Pair<String, NostrEvent>> = channelFlow {
        val connectedRelays = getConnectedRelays()
        if (connectedRelays.isEmpty()) {
            Timber.w("No connected relays for subscription")
            return@channelFlow
        }

        val seenEvents = ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            connectedRelays.forEach { relay ->
                launch {
                    relay.subscribe(filters).collect { event ->
                        // Deduplicate events across relays
                        if (seenEvents.add(event.id)) {
                            send(relay.url to event)
                        }
                    }
                }
            }
        }
    }

    /**
     * Subscribe to events from specific relays
     */
    fun subscribeFrom(filters: List<Filter>, urls: List<String>): Flow<Pair<String, NostrEvent>> = channelFlow {
        val relays = urls.mapNotNull { connections[it] }
        if (relays.isEmpty()) {
            Timber.w("No relays found for subscription")
            return@channelFlow
        }

        val seenEvents = ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            relays.forEach { relay ->
                launch {
                    relay.subscribe(filters).collect { event ->
                        if (seenEvents.add(event.id)) {
                            send(relay.url to event)
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetch events from all connected relays (one-time query)
     */
    suspend fun fetchEvents(
        filters: List<Filter>,
        timeout: Long = 15_000
    ): List<NostrEvent> {
        val connectedRelays = getConnectedRelays()
        if (connectedRelays.isEmpty()) {
            return emptyList()
        }

        val seenEvents = ConcurrentHashMap.newKeySet<String>()
        val allEvents = mutableListOf<NostrEvent>()

        coroutineScope {
            connectedRelays.map { relay ->
                async {
                    try {
                        relay.fetchEvents(filters, timeout)
                    } catch (e: Exception) {
                        Timber.e(e, "Error fetching from ${relay.url}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().forEach { event ->
                if (seenEvents.add(event.id)) {
                    allEvents.add(event)
                }
            }
        }

        return allEvents
    }

    /**
     * Fetch events from specific relays
     */
    suspend fun fetchEventsFrom(
        filters: List<Filter>,
        urls: List<String>,
        timeout: Long = 15_000
    ): List<NostrEvent> {
        val relays = urls.mapNotNull { connections[it] }
        if (relays.isEmpty()) {
            return emptyList()
        }

        val seenEvents = ConcurrentHashMap.newKeySet<String>()
        val allEvents = mutableListOf<NostrEvent>()

        coroutineScope {
            relays.map { relay ->
                async {
                    try {
                        relay.fetchEvents(filters, timeout)
                    } catch (e: Exception) {
                        Timber.e(e, "Error fetching from ${relay.url}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().forEach { event ->
                if (seenEvents.add(event.id)) {
                    allEvents.add(event)
                }
            }
        }

        return allEvents
    }

    /**
     * Get connection state for all relays
     */
    fun getConnectionStates(): Map<String, RelayConnection.ConnectionState> {
        return connections.mapValues { it.value.connectionState.value }
    }

    /**
     * Observe connection states for all relays
     */
    fun observeConnectionStates(): Flow<Map<String, RelayConnection.ConnectionState>> = flow {
        while (true) {
            emit(getConnectionStates())
            delay(1000)
        }
    }
}
