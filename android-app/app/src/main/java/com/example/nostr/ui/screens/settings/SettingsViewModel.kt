package com.example.nostr.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nostr.data.database.dao.RelayDao
import com.example.nostr.data.database.entity.RelayEntity
import com.example.nostr.data.repository.AuthRepository
import com.example.nostr.network.relay.RelayPool
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class SettingsUiState(
    val isLoggedIn: Boolean = false,
    val pubkey: String? = null,
    val npub: String? = null,
    val nsec: String? = null,
    val relays: List<RelayEntity> = emptyList(),
    val defaultZapAmount: Int = 1000,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val relayDao: RelayDao,
    private val relayPool: RelayPool
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeAuthState()
        loadRelays()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.observePubkey().collect { pubkey ->
                if (pubkey != null) {
                    val npub = authRepository.getNpub()
                    val nsec = authRepository.getNsec()
                    _uiState.update {
                        it.copy(
                            isLoggedIn = true,
                            pubkey = pubkey,
                            npub = npub,
                            nsec = nsec
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = false,
                            pubkey = null,
                            npub = null,
                            nsec = null
                        )
                    }
                }
            }
        }
    }

    private fun loadRelays() {
        viewModelScope.launch {
            relayDao.getAll().collect { relays ->
                _uiState.update { it.copy(relays = relays) }
            }
        }
    }

    fun loginWithNsec(nsec: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authRepository.loginWithNsec(nsec)
            result.fold(
                onSuccess = { pubkey ->
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                    Timber.d("Logged in with pubkey: $pubkey")
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    Timber.e(e, "Login failed")
                }
            )
        }
    }

    fun loginWithNpub(npub: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authRepository.loginWithNpub(npub)
            result.fold(
                onSuccess = { pubkey ->
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                    Timber.d("Logged in read-only with pubkey: $pubkey")
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    Timber.e(e, "Login failed")
                }
            )
        }
    }

    fun generateNewKey() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authRepository.generateAndLogin()
            result.fold(
                onSuccess = { (pubkey, nsec) ->
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                    Timber.d("Generated new key with pubkey: $pubkey")
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                    Timber.e(e, "Key generation failed")
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.update { it.copy(loginSuccess = false) }
        }
    }

    fun addRelay(url: String) {
        viewModelScope.launch {
            val relay = RelayEntity(
                url = url.trim(),
                isRead = true,
                isWrite = true,
                isEnabled = true
            )
            relayDao.insert(relay)
            relayPool.getConnection(url.trim())
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch {
            relayDao.delete(url)
            relayPool.disconnect(url)
        }
    }

    fun toggleRelay(url: String, enabled: Boolean) {
        viewModelScope.launch {
            relayDao.setEnabled(url, enabled)
            if (enabled) {
                relayPool.getConnection(url)
            } else {
                relayPool.disconnect(url)
            }
        }
    }

    fun setDefaultZapAmount(amount: Int) {
        _uiState.update { it.copy(defaultZapAmount = amount) }
        // TODO: Persist to DataStore
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearLoginSuccess() {
        _uiState.update { it.copy(loginSuccess = false) }
    }
}
