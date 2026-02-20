package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

enum class FeedType { GLOBAL, FOLLOWING }

data class TimelineUiState(
    val posts: List<ScoredPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val feedType: FeedType = FeedType.GLOBAL,
    val searchQuery: String = "",
    val searchResults: List<ScoredPost> = emptyList(),
    val isSearching: Boolean = false
)

class TimelineViewModel(
    private val repository: NostrRepository,
    private val pubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState(isLoading = true))
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    // My own profile for optimistic post display
    private var myProfile: UserProfile? = null

    // Prevent concurrent timeline loads
    private var loadJob: Job? = null

    // Prevent double-tap actions (eventId → in-flight)
    private val pendingLikes = ConcurrentHashMap.newKeySet<String>()
    private val pendingReposts = ConcurrentHashMap.newKeySet<String>()

    init {
        loadTimeline()
    }

    /** Call from MainScreen when own profile is available (for optimistic post display). */
    fun setMyProfile(profile: UserProfile?) {
        myProfile = profile
    }

    fun loadTimeline() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val posts = fetchForCurrentFeed()
                _uiState.update { it.copy(posts = posts, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "タイムラインの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun refresh() {
        // Avoid stacking multiple concurrent refreshes
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val posts = fetchForCurrentFeed()
                _uiState.update { it.copy(posts = posts, isRefreshing = false) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isRefreshing = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "更新に失敗しました", isRefreshing = false) }
            }
        }
    }

    fun switchFeed(feedType: FeedType) {
        if (_uiState.value.feedType == feedType) return
        _uiState.update { it.copy(feedType = feedType, posts = emptyList()) }
        loadTimeline()
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        viewModelScope.launch {
            try {
                val results = repository.searchNotes(query, 30)
                _uiState.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
    }

    fun likePost(eventId: String) {
        // Prevent double-tap: skip if already in-flight for this event
        if (!pendingLikes.add(eventId)) return
        viewModelScope.launch {
            try {
                val success = repository.likePost(eventId)
                if (success) {
                    _uiState.update { state ->
                        state.copy(posts = state.posts.map { post ->
                            if (post.event.id == eventId && !post.isLiked)
                                post.copy(isLiked = true, likeCount = post.likeCount + 1)
                            else post
                        })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent: relay may still accept it next connection
            } finally {
                pendingLikes.remove(eventId)
            }
        }
    }

    fun repostPost(eventId: String) {
        // Prevent double-tap: skip if already in-flight for this event
        if (!pendingReposts.add(eventId)) return
        viewModelScope.launch {
            try {
                val success = repository.repostPost(eventId)
                if (success) {
                    _uiState.update { state ->
                        state.copy(posts = state.posts.map { post ->
                            if (post.event.id == eventId && !post.isReposted)
                                post.copy(isReposted = true, repostCount = post.repostCount + 1)
                            else post
                        })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent
            } finally {
                pendingReposts.remove(eventId)
            }
        }
    }

    fun publishNote(content: String) {
        viewModelScope.launch {
            try {
                val event = repository.publishNote(content) ?: return@launch
                // Optimistically prepend the new post so it appears immediately
                val optimisticPost = ScoredPost(event = event, profile = myProfile)
                _uiState.update { state ->
                    state.copy(posts = listOf(optimisticPost) + state.posts)
                }
                // Delay to give relay time to index, then refresh for real data
                delay(2_500)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Publishing failed silently; optimistic post won't appear if event was null
            }
        }
    }

    private suspend fun fetchForCurrentFeed(): List<ScoredPost> =
        when (_uiState.value.feedType) {
            FeedType.GLOBAL -> repository.fetchGlobalTimeline(50)
            FeedType.FOLLOWING -> repository.fetchFollowTimeline(pubkeyHex, 50)
        }

    class Factory(
        private val repository: NostrRepository,
        private val pubkeyHex: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            TimelineViewModel(repository, pubkeyHex) as T
    }
}
