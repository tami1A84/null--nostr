package io.nurunuru.app.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Constants
import io.nurunuru.app.data.models.DEFAULT_RELAYS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Connection status monitoring ViewModel.
 * Synced with web version: hooks/useConnectionStatus.js
 */

enum class RelayHealthStatus { HEALTHY, UNHEALTHY, COOLDOWN, DISABLED, UNKNOWN }

data class RelayHealth(
    val url: String,
    val status: RelayHealthStatus = RelayHealthStatus.UNKNOWN,
    val failureCount: Int = 0,
    val lastChecked: Long = 0L
)

data class ConnectionUiState(
    val isOnline: Boolean = true,
    val wasOffline: Boolean = false,
    val relayHealth: Map<String, RelayHealth> = emptyMap(),
    val healthyRelayCount: Int = 0,
    val totalRelayCount: Int = 0,
    val statusMessage: String = Constants.ErrorMessages.STATUS_CONNECTED,
    val isFullyConnected: Boolean = true,
    val lastRefresh: Long = System.currentTimeMillis()
)

class ConnectionViewModel(
    private val context: Context,
    private val relays: List<String> = DEFAULT_RELAYS
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkCallback()
        startHealthMonitoring()
        refreshRelayHealth()
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = _uiState.value.wasOffline || !_uiState.value.isOnline
                _uiState.update {
                    it.copy(
                        isOnline = true,
                        wasOffline = wasOffline,
                        statusMessage = Constants.ErrorMessages.STATUS_CONNECTED,
                        isFullyConnected = true
                    )
                }
                if (wasOffline) {
                    refreshRelayHealth()
                }
            }

            override fun onLost(network: Network) {
                _uiState.update {
                    it.copy(
                        isOnline = false,
                        wasOffline = true,
                        statusMessage = Constants.ErrorMessages.STATUS_OFFLINE,
                        isFullyConnected = false
                    )
                }
            }
        }

        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun startHealthMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(Constants.Connection.HEALTH_CHECK_INTERVAL_MS)
                refreshRelayHealth()
            }
        }
    }

    fun refreshRelayHealth() {
        val healthMap = mutableMapOf<String, RelayHealth>()
        var healthyCount = 0

        for (relay in relays) {
            // In the SDK-backed client, actual health is managed by rust-nostr.
            // Here we track a simplified status based on connectivity.
            val isOnline = _uiState.value.isOnline
            val status = if (isOnline) RelayHealthStatus.HEALTHY else RelayHealthStatus.UNHEALTHY
            if (status == RelayHealthStatus.HEALTHY) healthyCount++

            healthMap[relay] = RelayHealth(
                url = relay,
                status = status,
                lastChecked = System.currentTimeMillis()
            )
        }

        _uiState.update {
            it.copy(
                relayHealth = healthMap,
                healthyRelayCount = healthyCount,
                totalRelayCount = relays.size,
                lastRefresh = System.currentTimeMillis(),
                statusMessage = computeStatusMessage(it.isOnline, healthyCount, relays.size)
            )
        }
    }

    private fun computeStatusMessage(isOnline: Boolean, healthy: Int, total: Int): String {
        return when {
            !isOnline -> Constants.ErrorMessages.STATUS_OFFLINE
            healthy == 0 -> Constants.ErrorMessages.STATUS_ERROR
            healthy < total -> Constants.ErrorMessages.STATUS_RECONNECTING
            else -> Constants.ErrorMessages.STATUS_CONNECTED
        }
    }

    override fun onCleared() {
        super.onCleared()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cm?.unregisterNetworkCallback(it) }
    }

    class Factory(
        private val context: Context,
        private val relays: List<String> = DEFAULT_RELAYS
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConnectionViewModel(context, relays) as T
    }
}
