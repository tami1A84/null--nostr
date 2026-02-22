package io.nurunuru.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import io.nurunuru.app.data.EngineManager
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "NuruNuru-Auth"

sealed class AuthState {
    object Checking : AuthState()
    object LoggedOut : AuthState()
    data class LoggedIn(val pubkeyHex: String, val privateKeyHex: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(private val application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Checking)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkStoredLogin()
    }

    private fun checkStoredLogin() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val privKey = prefs.privateKeyHex
                val pubKey = prefs.publicKeyHex
                if (privKey != null && pubKey != null) {
                    Log.d(TAG, "Restoring session for $pubKey")
                    val success = EngineManager.init(application, privKey)
                    if (success) {
                        _authState.value = AuthState.LoggedIn(pubKey, privKey)
                    } else {
                        _authState.value = AuthState.Error("エンジンの初期化に失敗しました")
                    }
                } else {
                    _authState.value = AuthState.LoggedOut
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session restoration failed", e)
                _authState.value = AuthState.LoggedOut
            }
        }
    }

    fun login(nsecOrHex: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _authState.value = AuthState.Checking

            try {
                Log.d(TAG, "Attempting login with provided key")

                // Use Rust engine to parse keys because nostr-sdk-jvm crashes on Android
                val parsedKeys = EngineManager.parseKeys(nsecOrHex)

                if (parsedKeys == null) {
                    _authState.value = AuthState.Error("秘密鍵の解析に失敗しました。正しい形式か確認してください。")
                    return@launch
                }

                val privKeyHex = parsedKeys.secretKeyHex
                val pubKeyHex = parsedKeys.publicKeyHex

                val success = EngineManager.init(application, privKeyHex)
                if (success) {
                    Log.d(TAG, "Login successful for $pubKeyHex")
                    prefs.privateKeyHex = privKeyHex
                    prefs.publicKeyHex = pubKeyHex
                    _authState.value = AuthState.LoggedIn(pubKeyHex, privKeyHex)
                } else {
                    Log.e(TAG, "Engine init failed during login")
                    _authState.value = AuthState.Error("エンジンの初期化に失敗しました")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Login failed", e)
                _authState.value = AuthState.Error("ログインに失敗しました: ${e.message}")
            }
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Logging out...")
            EngineManager.cleanup()
            prefs.clear()
            _authState.value = AuthState.LoggedOut
        }
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.LoggedOut
        }
    }
}
