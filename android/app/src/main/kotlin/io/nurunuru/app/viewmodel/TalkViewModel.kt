package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class TalkUiState(
    val conversations: List<DmConversation> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeConversation: String? = null,
    val messages: List<DmMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val sendingMessage: Boolean = false
)

class TalkViewModel(
    private val repository: NostrRepository,
    private val nostrClient: NostrClient,
    private val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TalkUiState(isLoading = true))
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    init {
        loadConversations()
    }

    fun loadConversations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val conversations = repository.fetchDmConversations(myPubkeyHex)
                _uiState.update { it.copy(conversations = conversations, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "トークの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun openConversation(partnerPubkey: String) {
        _uiState.update { it.copy(activeConversation = partnerPubkey, messagesLoading = true) }
        viewModelScope.launch {
            try {
                val messages = repository.fetchDmMessages(
                    myPubkeyHex = myPubkeyHex,
                    partnerPubkeyHex = partnerPubkey,
                    decryptFn = { counterparty, encrypted ->
                        runBlocking { nostrClient.decryptNip04(counterparty, encrypted) }
                    }
                )
                _uiState.update { it.copy(messages = messages, messagesLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(messagesLoading = false, error = "メッセージの読み込みに失敗しました") }
            }
        }
    }

    fun sendMessage(recipientPubkey: String, content: String) {
        if (content.isBlank()) return
        _uiState.update { it.copy(sendingMessage = true) }
        viewModelScope.launch {
            try {
                val success = repository.sendDm(recipientPubkey, content)
                if (success) {
                    // Reload messages
                    openConversation(recipientPubkey)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "送信に失敗しました") }
            } finally {
                _uiState.update { it.copy(sendingMessage = false) }
            }
        }
    }

    fun closeConversation() {
        _uiState.update { it.copy(activeConversation = null, messages = emptyList()) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(
        private val repository: NostrRepository,
        private val nostrClient: NostrClient,
        private val myPubkeyHex: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            TalkViewModel(repository, nostrClient, myPubkeyHex) as T
    }
}
