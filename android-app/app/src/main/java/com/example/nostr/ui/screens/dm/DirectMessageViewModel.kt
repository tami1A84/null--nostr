package com.example.nostr.ui.screens.dm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nostr.data.database.dao.DirectMessageDao
import com.example.nostr.data.database.dao.ProfileDao
import com.example.nostr.data.database.entity.DirectMessageEntity
import com.example.nostr.data.database.entity.ProfileEntity
import com.example.nostr.data.repository.AuthRepository
import com.example.nostr.network.relay.Filter
import com.example.nostr.network.relay.RelayPool
import com.example.nostr.nostr.crypto.Nip44
import com.example.nostr.nostr.crypto.NostrSigner
import com.example.nostr.nostr.event.EventKind
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.hexToByteArray
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class Conversation(
    val id: String,
    val otherPubkey: String,
    val profile: ProfileEntity?,
    val lastMessage: DirectMessageEntity?,
    val unreadCount: Int = 0
)

data class DMUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)

data class ChatUiState(
    val messages: List<DirectMessageEntity> = emptyList(),
    val otherProfile: ProfileEntity? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class DirectMessageViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val directMessageDao: DirectMessageDao,
    private val profileDao: ProfileDao,
    private val relayPool: RelayPool
) : ViewModel() {

    private val _uiState = MutableStateFlow(DMUiState())
    val uiState: StateFlow<DMUiState> = _uiState.asStateFlow()

    private val _chatState = MutableStateFlow(ChatUiState())
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private var currentChatPubkey: String? = null

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.observePubkey().collect { pubkey ->
                if (pubkey != null) {
                    _uiState.update { it.copy(isLoggedIn = true) }
                    loadConversations(pubkey)
                } else {
                    _uiState.update { it.copy(isLoggedIn = false, conversations = emptyList()) }
                }
            }
        }
    }

    private fun loadConversations(userPubkey: String) {
        viewModelScope.launch {
            directMessageDao.getConversationIds(userPubkey).collect { conversationIds ->
                val conversations = conversationIds.mapNotNull { conversationId ->
                    val parts = conversationId.split(":")
                    val otherPubkey = parts.firstOrNull { it != userPubkey } ?: return@mapNotNull null

                    val recentMessages = directMessageDao.getRecentByConversation(conversationId, 1)
                    val lastMessage = recentMessages.firstOrNull()
                    val profile = profileDao.getByPubkey(otherPubkey)

                    Conversation(
                        id = conversationId,
                        otherPubkey = otherPubkey,
                        profile = profile,
                        lastMessage = lastMessage,
                        unreadCount = 0 // TODO: Implement unread count
                    )
                }
                _uiState.update { it.copy(conversations = conversations) }
            }
        }
    }

    fun fetchDMs() {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Fetch DMs from relays (NIP-17 gift wrapped DMs)
                val filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.GIFT_WRAP),
                        tags = mapOf("p" to listOf(pubkey)),
                        limit = 100
                    )
                )
                val events = relayPool.fetchEvents(filters)
                Timber.d("Fetched ${events.size} gift-wrapped DMs")

                // TODO: Decrypt and store DMs
            } catch (e: Exception) {
                Timber.e(e, "Error fetching DMs")
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun openChat(otherPubkey: String) {
        currentChatPubkey = otherPubkey
        viewModelScope.launch {
            val userPubkey = authRepository.getCurrentPubkey() ?: return@launch
            val conversationId = DirectMessageEntity.generateConversationId(userPubkey, otherPubkey)

            // Load profile
            val profile = profileDao.getByPubkey(otherPubkey)
            _chatState.update { it.copy(otherProfile = profile) }

            // Load messages
            directMessageDao.getByConversation(conversationId).collect { messages ->
                _chatState.update { it.copy(messages = messages) }
            }
        }
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            val userPubkey = authRepository.getCurrentPubkey() ?: return@launch
            val otherPubkey = currentChatPubkey ?: return@launch

            try {
                // TODO: Implement NIP-17 gift wrap encryption and send
                Timber.d("Sending message to $otherPubkey: $content")
            } catch (e: Exception) {
                Timber.e(e, "Error sending message")
            }
        }
    }

    fun markAsRead(conversationId: String) {
        viewModelScope.launch {
            directMessageDao.markAsRead(conversationId)
        }
    }
}
