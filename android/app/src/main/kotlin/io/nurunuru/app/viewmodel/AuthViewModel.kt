package io.nurunuru.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rust.nostr.sdk.Keys

sealed class AuthState {
    object Checking : AuthState()
    object LoggedOut : AuthState()
    data class LoggedIn(val pubkeyHex: String, val privateKeyHex: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Checking)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkStoredLogin()
    }

    private fun checkStoredLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            val privKey = prefs.privateKeyHex
            val pubKey = prefs.publicKeyHex
            if (privKey != null && pubKey != null) {
                _authState.value = AuthState.LoggedIn(pubKey, privKey)
            } else {
                _authState.value = AuthState.LoggedOut
            }
        }
    }

    fun login(nsecOrHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _authState.value = AuthState.Checking

            try {
                val keys = Keys.parse(nsecOrHex)
                val privKeyHex = keys.secretKey().toHex()
                val pubKeyHex = keys.publicKey().toHex()

                prefs.privateKeyHex = privKeyHex
                prefs.publicKeyHex = pubKeyHex
                _authState.value = AuthState.LoggedIn(pubKeyHex, privKeyHex)
            } catch (e: Exception) {
                _authState.value = AuthState.Error("ログインに失敗しました: ${e.message}")
            }
        }
    }

    fun logout() {
        prefs.clear()
        _authState.value = AuthState.LoggedOut
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.LoggedOut
        }
    }
}
