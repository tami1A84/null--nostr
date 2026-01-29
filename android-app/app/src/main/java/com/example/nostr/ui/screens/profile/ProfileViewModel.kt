package com.example.nostr.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nostr.data.database.dao.ContactDao
import com.example.nostr.data.database.dao.EventDao
import com.example.nostr.data.database.dao.ProfileDao
import com.example.nostr.data.database.entity.ContactEntity
import com.example.nostr.data.database.entity.EventEntity
import com.example.nostr.data.database.entity.ProfileEntity
import com.example.nostr.data.repository.AuthRepository
import com.example.nostr.network.relay.Filter
import com.example.nostr.network.relay.RelayPool
import com.example.nostr.nostr.crypto.NostrSigner
import com.example.nostr.nostr.event.EventKind
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent
import com.example.nostr.nostr.event.hexToByteArray
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ProfileUiState(
    val profile: ProfileEntity? = null,
    val posts: List<NostrEvent> = emptyList(),
    val followingCount: Int = 0,
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isOwnProfile: Boolean = true,
    val npub: String? = null,
    val error: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val profileDao: ProfileDao,
    private val eventDao: EventDao,
    private val contactDao: ContactDao,
    private val relayPool: RelayPool
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        observeAuthState()
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            authRepository.observePubkey().collect { pubkey ->
                if (pubkey != null) {
                    _uiState.update { it.copy(isLoggedIn = true) }
                    loadProfile(pubkey)
                    loadNpub(pubkey)
                    observeFollowingCount(pubkey)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = false,
                            profile = null,
                            posts = emptyList(),
                            npub = null
                        )
                    }
                }
            }
        }
    }

    private fun loadProfile(pubkey: String) {
        viewModelScope.launch {
            // Load from cache first
            profileDao.observeByPubkey(pubkey).collect { cached ->
                _uiState.update { it.copy(profile = cached) }
            }
        }

        // Fetch fresh profile from relays
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val filters = listOf(
                    Filter(
                        authors = listOf(pubkey),
                        kinds = listOf(EventKind.METADATA),
                        limit = 1
                    )
                )
                val events = relayPool.fetchEvents(filters)
                events.firstOrNull()?.let { event ->
                    parseProfile(event)?.let { profile ->
                        profileDao.insert(profile)
                    }
                }

                // Load user's posts
                loadUserPosts(pubkey)
            } catch (e: Exception) {
                Timber.e(e, "Error loading profile")
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun loadNpub(pubkey: String) {
        viewModelScope.launch {
            try {
                val npub = NostrSigner.publicKeyToNpub(pubkey.hexToByteArray())
                _uiState.update { it.copy(npub = npub) }
            } catch (e: Exception) {
                Timber.e(e, "Error generating npub")
            }
        }
    }

    private suspend fun loadUserPosts(pubkey: String) {
        try {
            val filters = listOf(
                Filter(
                    authors = listOf(pubkey),
                    kinds = listOf(EventKind.TEXT_NOTE),
                    limit = 50
                )
            )
            val events = relayPool.fetchEvents(filters)
            _uiState.update { it.copy(posts = events.sortedByDescending { it.createdAt }) }

            // Cache events
            eventDao.insertAll(events.map { EventEntity.fromNostrEvent(it) })
        } catch (e: Exception) {
            Timber.e(e, "Error loading user posts")
        }
    }

    private fun observeFollowingCount(pubkey: String) {
        viewModelScope.launch {
            contactDao.observeFollowingCount(pubkey).collect { count ->
                _uiState.update { it.copy(followingCount = count) }
            }
        }
    }

    private fun parseProfile(event: NostrEvent): ProfileEntity? {
        return try {
            val json = com.google.gson.JsonParser.parseString(event.content).asJsonObject
            ProfileEntity(
                pubkey = event.pubkey,
                name = json.get("name")?.asString,
                displayName = json.get("display_name")?.asString,
                about = json.get("about")?.asString,
                picture = json.get("picture")?.asString,
                banner = json.get("banner")?.asString,
                nip05 = json.get("nip05")?.asString,
                lud16 = json.get("lud16")?.asString,
                lud06 = json.get("lud06")?.asString,
                website = json.get("website")?.asString,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    fun updateProfile(
        name: String?,
        about: String?,
        picture: String?,
        nip05: String?,
        lud16: String?
    ) {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch

            try {
                val unsignedEvent = UnsignedEvent.metadata(
                    pubkey = pubkey,
                    name = name,
                    about = about,
                    picture = picture,
                    nip05 = nip05,
                    lud16 = lud16
                )

                val signedEvent = authRepository.signEvent(unsignedEvent)
                if (signedEvent != null) {
                    relayPool.publishEvent(signedEvent)

                    // Update local cache
                    val profile = ProfileEntity(
                        pubkey = pubkey,
                        name = name,
                        about = about,
                        picture = picture,
                        nip05 = nip05,
                        lud16 = lud16,
                        createdAt = signedEvent.createdAt
                    )
                    profileDao.insert(profile)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error updating profile")
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun loadFollowList() {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch

            try {
                val filters = listOf(
                    Filter(
                        authors = listOf(pubkey),
                        kinds = listOf(EventKind.CONTACT_LIST),
                        limit = 1
                    )
                )
                val events = relayPool.fetchEvents(filters)
                events.firstOrNull()?.let { event ->
                    val contacts = event.tags
                        .filter { it.firstOrNull() == "p" }
                        .mapNotNull { tag ->
                            tag.getOrNull(1)?.let { contactPubkey ->
                                ContactEntity(
                                    ownerPubkey = pubkey,
                                    contactPubkey = contactPubkey,
                                    relay = tag.getOrNull(2),
                                    petname = tag.getOrNull(3)
                                )
                            }
                        }
                    contactDao.replaceAll(pubkey, contacts)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error loading follow list")
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch
            loadProfile(pubkey)
            loadFollowList()
        }
    }
}
