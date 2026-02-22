package io.nurunuru.app.data

import android.content.Context
import android.util.Log
import io.nurunuru.NuruNuruBridge
import uniffi.nurunuru.NuruNuruClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "NuruNuru-Engine"

/**
 * Singleton manager for the Rust NuruNuru engine.
 * Handles safe initialization and lifecycle of the NuruNuruClient.
 */
object EngineManager {
    private val clientReference = AtomicReference<NuruNuruClient?>(null)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Get the active NuruNuruClient.
     * @throws IllegalStateException if the client is not initialized.
     */
    fun getClient(): NuruNuruClient {
        return clientReference.get() ?: throw IllegalStateException("Rust engine is not initialized. Call init() first.")
    }

    /**
     * Initialize the Rust engine with the given secret key.
     * Runs on a background thread.
     */
    suspend fun init(context: Context, secretKeyHex: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Rust engine...")

            // Clean up existing client if any
            cleanup()

            val client = NuruNuruBridge.create(context.applicationContext, secretKeyHex)
            clientReference.set(client)

            Log.d(TAG, "Rust client created successfully. Connecting...")
            // We don't call connect() here to keep init fast; connection handled by MainScreen or Repository

            _isReady.value = true
            Log.d(TAG, "Rust engine initialization complete.")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "CRITICAL: Failed to initialize Rust engine", e)
            _isReady.value = false
            false
        }
    }

    /**
     * Disconnect and release the Rust engine.
     */
    suspend fun cleanup() = withContext(Dispatchers.IO) {
        val client = clientReference.getAndSet(null)
        if (client != null) {
            try {
                Log.d(TAG, "Disconnecting Rust engine...")
                client.disconnect()
                _isReady.value = false
                Log.d(TAG, "Rust engine disconnected and released.")
            } catch (e: Throwable) {
                Log.e(TAG, "Error during Rust engine cleanup", e)
            }
        }
    }
}
