package com.example.nostr.network.relay

import com.example.nostr.nostr.event.NostrEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Represents a connection to a single Nostr relay
 */
class RelayConnection(
    val url: String,
    private val okHttpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private val subscriptions = ConcurrentHashMap<String, Channel<NostrEvent>>()
    private val pendingEvents = ConcurrentHashMap<String, CompletableDeferred<RelayMessage.Ok>>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<RelayMessage>(replay = 0, extraBufferCapacity = 100)
    val messages: SharedFlow<RelayMessage> = _messages.asSharedFlow()

    private val subscriptionCounter = AtomicInteger(0)
    private var reconnectJob: Job? = null

    /**
     * Connection states
     */
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    /**
     * Connect to the relay
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.Connected ||
            _connectionState.value == ConnectionState.Connecting) {
            return
        }

        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("Connected to relay: $url")
                _connectionState.value = ConnectionState.Connected
                reconnectJob?.cancel()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = RelayMessage.fromJson(text)
                if (message != null) {
                    handleMessage(message)
                } else {
                    Timber.w("Failed to parse relay message: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Relay closing: $url, code=$code, reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Relay closed: $url, code=$code, reason=$reason")
                _connectionState.value = ConnectionState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "Relay connection failed: $url")
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                scheduleReconnect()
            }
        })
    }

    /**
     * Disconnect from the relay
     */
    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client closing")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        subscriptions.values.forEach { it.close() }
        subscriptions.clear()
    }

    /**
     * Send a message to the relay
     */
    private fun send(message: ClientMessage): Boolean {
        val json = message.toJson()
        return webSocket?.send(json) ?: false
    }

    /**
     * Publish an event to the relay
     */
    suspend fun publishEvent(event: NostrEvent): Result<RelayMessage.Ok> {
        if (_connectionState.value != ConnectionState.Connected) {
            return Result.failure(IllegalStateException("Not connected to relay"))
        }

        val deferred = CompletableDeferred<RelayMessage.Ok>()
        pendingEvents[event.id] = deferred

        val sent = send(ClientMessage.Event(event))
        if (!sent) {
            pendingEvents.remove(event.id)
            return Result.failure(IllegalStateException("Failed to send event"))
        }

        return try {
            withTimeout(15_000) {
                val ok = deferred.await()
                if (ok.accepted) {
                    Result.success(ok)
                } else {
                    Result.failure(IllegalStateException(ok.message))
                }
            }
        } catch (e: TimeoutCancellationException) {
            pendingEvents.remove(event.id)
            Result.failure(e)
        }
    }

    /**
     * Subscribe to events matching filters
     */
    fun subscribe(filters: List<Filter>): Flow<NostrEvent> = channelFlow {
        val subscriptionId = "sub-${subscriptionCounter.incrementAndGet()}"
        val channel = Channel<NostrEvent>(Channel.BUFFERED)
        subscriptions[subscriptionId] = channel

        // Send subscription request
        send(ClientMessage.Request(subscriptionId, filters))

        try {
            for (event in channel) {
                send(event)
            }
        } finally {
            // Close subscription when flow is cancelled
            send(ClientMessage.Close(subscriptionId))
            subscriptions.remove(subscriptionId)
            channel.close()
        }
    }

    /**
     * Subscribe and collect all events until EOSE
     */
    suspend fun fetchEvents(filters: List<Filter>, timeout: Long = 15_000): List<NostrEvent> {
        val subscriptionId = "fetch-${subscriptionCounter.incrementAndGet()}"
        val events = mutableListOf<NostrEvent>()
        val channel = Channel<NostrEvent>(Channel.BUFFERED)
        subscriptions[subscriptionId] = channel

        send(ClientMessage.Request(subscriptionId, filters))

        return try {
            withTimeout(timeout) {
                for (event in channel) {
                    events.add(event)
                }
                events
            }
        } catch (e: TimeoutCancellationException) {
            events
        } finally {
            send(ClientMessage.Close(subscriptionId))
            subscriptions.remove(subscriptionId)
            channel.close()
        }
    }

    /**
     * Handle incoming relay messages
     */
    private fun handleMessage(message: RelayMessage) {
        CoroutineScope(Dispatchers.Default).launch {
            _messages.emit(message)
        }

        when (message) {
            is RelayMessage.Event -> {
                subscriptions[message.subscriptionId]?.trySend(message.event)
            }
            is RelayMessage.Ok -> {
                pendingEvents.remove(message.eventId)?.complete(message)
            }
            is RelayMessage.EndOfStoredEvents -> {
                // Close the channel to signal EOSE for fetchEvents
                subscriptions[message.subscriptionId]?.close()
            }
            is RelayMessage.Closed -> {
                subscriptions.remove(message.subscriptionId)?.close()
            }
            is RelayMessage.Notice -> {
                Timber.d("Relay notice from $url: ${message.message}")
            }
            is RelayMessage.Auth -> {
                Timber.d("Auth challenge from $url: ${message.challenge}")
                // Handle NIP-42 authentication if needed
            }
            is RelayMessage.Count -> {
                // Handle count response
            }
        }
    }

    /**
     * Schedule reconnection with exponential backoff
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return

        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            var delay = 1000L
            val maxDelay = 60_000L

            while (isActive && _connectionState.value != ConnectionState.Connected) {
                delay(delay)
                Timber.d("Attempting to reconnect to $url")
                connect()

                // Wait a bit to see if connection succeeds
                delay(2000)

                if (_connectionState.value == ConnectionState.Connected) {
                    break
                }

                // Exponential backoff
                delay = minOf(delay * 2, maxDelay)
            }
        }
    }

    companion object {
        /**
         * Create OkHttpClient configured for WebSocket connections
         */
        fun createOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // No timeout for WebSocket
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
