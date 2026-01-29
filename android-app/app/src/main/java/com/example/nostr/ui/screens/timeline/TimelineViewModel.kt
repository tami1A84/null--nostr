package com.example.nostr.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nostr.data.database.dao.EventDao
import com.example.nostr.data.database.dao.ProfileDao
import com.example.nostr.data.database.dao.ReactionDao
import com.example.nostr.data.database.entity.EventEntity
import com.example.nostr.data.database.entity.ProfileEntity
import com.example.nostr.data.repository.AuthRepository
import com.example.nostr.network.relay.Filter
import com.example.nostr.network.relay.RelayPool
import com.example.nostr.nostr.event.EventKind
import com.example.nostr.nostr.event.NostrEvent
import com.example.nostr.nostr.event.UnsignedEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class TimelineUiState(
    val events: List<TimelineItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val timelineMode: TimelineMode = TimelineMode.GLOBAL
)

data class TimelineItem(
    val event: NostrEvent,
    val profile: ProfileEntity?,
    val likeCount: Int = 0,
    val repostCount: Int = 0,
    val hasLiked: Boolean = false,
    val hasReposted: Boolean = false
)

enum class TimelineMode {
    GLOBAL,
    FOLLOWING
}

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val relayPool: RelayPool,
    private val eventDao: EventDao,
    private val profileDao: ProfileDao,
    private val reactionDao: ReactionDao,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        // Connect to relays
        relayPool.connectToDefaults()

        // Load cached events
        loadCachedEvents()

        // Fetch fresh events
        refreshTimeline()
    }

    private fun loadCachedEvents() {
        viewModelScope.launch {
            eventDao.getTextNotes(limit = 100)
                .collect { entities ->
                    val items = entities.map { entity ->
                        val profile = profileDao.getByPubkey(entity.pubkey)
                        val event = entity.toNostrEvent()
                        val hasLiked = authRepository.getCurrentPubkey()?.let { pubkey ->
                            reactionDao.hasReacted(event.id, pubkey)
                        } ?: false

                        TimelineItem(
                            event = event,
                            profile = profile,
                            hasLiked = hasLiked
                        )
                    }
                    _uiState.update { it.copy(events = items) }
                }
        }
    }

    fun refreshTimeline() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }

            try {
                val filters = when (_uiState.value.timelineMode) {
                    TimelineMode.GLOBAL -> listOf(
                        Filter(
                            kinds = listOf(EventKind.TEXT_NOTE),
                            limit = 100
                        )
                    )
                    TimelineMode.FOLLOWING -> {
                        val following = authRepository.getFollowing()
                        if (following.isNotEmpty()) {
                            listOf(
                                Filter(
                                    authors = following,
                                    kinds = listOf(EventKind.TEXT_NOTE),
                                    limit = 100
                                )
                            )
                        } else {
                            listOf(
                                Filter(
                                    kinds = listOf(EventKind.TEXT_NOTE),
                                    limit = 100
                                )
                            )
                        }
                    }
                }

                val events = relayPool.fetchEvents(filters)

                // Cache events
                val entities = events.map { EventEntity.fromNostrEvent(it) }
                eventDao.insertAll(entities)

                // Fetch profiles
                val pubkeys = events.map { it.pubkey }.distinct()
                fetchProfiles(pubkeys)

                Timber.d("Fetched ${events.size} events")
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing timeline")
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun fetchProfiles(pubkeys: List<String>) {
        if (pubkeys.isEmpty()) return

        try {
            val filters = listOf(
                Filter(
                    authors = pubkeys,
                    kinds = listOf(EventKind.METADATA)
                )
            )
            val profileEvents = relayPool.fetchEvents(filters)

            val profiles = profileEvents.mapNotNull { event ->
                parseProfile(event)
            }
            profileDao.insertAll(profiles)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching profiles")
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

    fun setTimelineMode(mode: TimelineMode) {
        _uiState.update { it.copy(timelineMode = mode) }
        refreshTimeline()
    }

    fun likeEvent(event: NostrEvent) {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch

            try {
                val unsignedEvent = UnsignedEvent.reaction(pubkey, event)
                val signedEvent = authRepository.signEvent(unsignedEvent)

                if (signedEvent != null) {
                    relayPool.publishEvent(signedEvent)
                    eventDao.insert(EventEntity.fromNostrEvent(signedEvent))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error liking event")
            }
        }
    }

    fun repostEvent(event: NostrEvent) {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch

            try {
                val unsignedEvent = UnsignedEvent.repost(pubkey, event)
                val signedEvent = authRepository.signEvent(unsignedEvent)

                if (signedEvent != null) {
                    relayPool.publishEvent(signedEvent)
                    eventDao.insert(EventEntity.fromNostrEvent(signedEvent))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error reposting event")
            }
        }
    }

    fun postNote(content: String) {
        viewModelScope.launch {
            val pubkey = authRepository.getCurrentPubkey() ?: return@launch

            try {
                val unsignedEvent = UnsignedEvent.textNote(pubkey, content)
                val signedEvent = authRepository.signEvent(unsignedEvent)

                if (signedEvent != null) {
                    relayPool.publishEvent(signedEvent)
                    eventDao.insert(EventEntity.fromNostrEvent(signedEvent))
                    refreshTimeline()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error posting note")
            }
        }
    }
}
