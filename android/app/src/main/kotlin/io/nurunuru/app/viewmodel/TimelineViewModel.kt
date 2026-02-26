package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Nip05Utils
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class FeedType { GLOBAL, FOLLOWING }

sealed class SearchNavigationEvent {
    data class OpenProfile(val pubkey: String) : SearchNavigationEvent()
}

data class TimelineUiState(
    val globalPosts: List<ScoredPost> = emptyList(),
    val followingPosts: List<ScoredPost> = emptyList(),
    val isGlobalLoading: Boolean = false,
    val isFollowingLoading: Boolean = false,
    val isGlobalRefreshing: Boolean = false,
    val isFollowingRefreshing: Boolean = false,
    val globalError: String? = null,
    val followingError: String? = null,
    val feedType: FeedType = FeedType.FOLLOWING,
    val searchQuery: String = "",
    val searchResults: List<ScoredPost> = emptyList(),
    val isSearching: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val hasNewRecommendations: Boolean = false,
    val followList: List<String> = emptyList(),
    val birdwatchNotes: Map<String, List<io.nurunuru.app.data.models.NostrEvent>> = emptyMap()
)

class TimelineViewModel(
    private val repository: NostrRepository,
    private val pubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState(
        isGlobalLoading = true,
        isFollowingLoading = true
    ))
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<SearchNavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    init {
        loadFollowList()
        loadGlobalTimeline()
        loadFollowingTimeline()
        loadRecentSearches()
    }

    private fun loadRecentSearches() {
        _uiState.update { it.copy(recentSearches = repository.getRecentSearches()) }
    }

    private fun loadFollowList() {
        viewModelScope.launch {
            try {
                val follows = repository.fetchFollowList(pubkeyHex)
                _uiState.update { it.copy(followList = follows) }
            } catch (e: Exception) { /* Ignore */ }
        }
    }

    fun loadGlobalTimeline(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isGlobalRefreshing = true, globalError = null)
                else it.copy(isGlobalLoading = true, globalError = null)
            }
            try {
                val posts = repository.fetchGlobalTimeline(50)
                _uiState.update { state ->
                    state.copy(
                        globalPosts = posts,
                        isGlobalLoading = false,
                        isGlobalRefreshing = false,
                        hasNewRecommendations = state.feedType != FeedType.GLOBAL
                    )
                }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        globalError = if (isRefresh) "更新に失敗しました" else "おすすめの読み込みに失敗しました",
                        isGlobalLoading = false,
                        isGlobalRefreshing = false
                    )
                }
            }
        }
    }

    fun loadFollowingTimeline(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isFollowingRefreshing = true, followingError = null)
                else it.copy(isFollowingLoading = true, followingError = null)
            }
            try {
                val posts = repository.fetchFollowTimeline(pubkeyHex, 50)
                _uiState.update { it.copy(
                    followingPosts = posts,
                    isFollowingLoading = false,
                    isFollowingRefreshing = false
                ) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        followingError = if (isRefresh) "更新に失敗しました" else "フォロー中の読み込みに失敗しました",
                        isFollowingLoading = false,
                        isFollowingRefreshing = false
                    )
                }
            }
        }
    }

    fun refresh() {
        when (_uiState.value.feedType) {
            FeedType.GLOBAL -> loadGlobalTimeline(isRefresh = true)
            FeedType.FOLLOWING -> loadFollowingTimeline(isRefresh = true)
        }
    }

    private fun fetchBirdwatchForPosts(posts: List<ScoredPost>) {
        if (posts.isEmpty()) return
        viewModelScope.launch {
            try {
                val ids = posts.map { it.event.id }
                val notes = repository.fetchBirdwatchNotes(ids)
                _uiState.update { state ->
                    val newNotes = state.birdwatchNotes.toMutableMap()
                    newNotes.putAll(notes)
                    state.copy(birdwatchNotes = newNotes)
                }
            } catch (e: Exception) { /* Silently ignore */ }
        }
    }

    fun switchFeed(feedType: FeedType) {
        if (_uiState.value.feedType == feedType) return
        _uiState.update {
            it.copy(
                feedType = feedType,
                hasNewRecommendations = if (feedType == FeedType.GLOBAL) false else it.hasNewRecommendations
            )
        }
        if (feedType == FeedType.GLOBAL && _uiState.value.globalPosts.isEmpty()) {
            loadGlobalTimeline()
        } else if (feedType == FeedType.FOLLOWING && _uiState.value.followingPosts.isEmpty()) {
            loadFollowingTimeline()
        }
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
            return
        }

        val trimmedQuery = query.trim()
        repository.saveRecentSearch(trimmedQuery)
        _uiState.update { it.copy(
            searchQuery = trimmedQuery,
            isSearching = true,
            recentSearches = repository.getRecentSearches()
        ) }

        viewModelScope.launch {
            try {
                // 1. Check for npub or hex pubkey
                val pubkey = NostrKeyUtils.parsePublicKey(trimmedQuery)
                if (pubkey != null && trimmedQuery.startsWith("npub")) {
                    _navigationEvents.emit(SearchNavigationEvent.OpenProfile(pubkey))
                    _uiState.update { it.copy(isSearching = false) }
                    return@launch
                }

                // 2. Check for NIP-05
                if (trimmedQuery.contains("@") && !trimmedQuery.contains(" ")) {
                    val resolved = Nip05Utils.resolveNip05(trimmedQuery)
                    if (resolved != null) {
                        _navigationEvents.emit(SearchNavigationEvent.OpenProfile(resolved))
                        _uiState.update { it.copy(isSearching = false) }
                        return@launch
                    }
                }

                // 3. Check for specific event (note, nevent, or 64-char hex)
                val isHex64 = trimmedQuery.length == 64 && trimmedQuery.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                if (trimmedQuery.startsWith("note") || trimmedQuery.startsWith("nevent") || isHex64) {
                    val event = repository.fetchEvent(trimmedQuery)
                    if (event != null) {
                        _uiState.update { it.copy(searchResults = listOf(event), isSearching = false) }
                        return@launch
                    } else if (isHex64) {
                        // If 64 char hex failed as event, try as pubkey
                        _navigationEvents.emit(SearchNavigationEvent.OpenProfile(trimmedQuery.lowercase()))
                        _uiState.update { it.copy(isSearching = false) }
                        return@launch
                    }
                }

                // 4. Default: Text search
                val results = repository.searchNotes(trimmedQuery, 30)
                _uiState.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
    }

    fun removeRecentSearch(query: String) {
        repository.removeRecentSearch(query)
        loadRecentSearches()
    }

    fun clearRecentSearches() {
        repository.clearRecentSearches()
        loadRecentSearches()
    }

    fun likePost(eventId: String) {
        viewModelScope.launch {
            val success = repository.likePost(eventId)
            if (success) {
                updatePostInteraction(eventId, isLike = true)
            }
        }
    }

    fun repostPost(eventId: String) {
        viewModelScope.launch {
            val success = repository.repostPost(eventId)
            if (success) {
                updatePostInteraction(eventId, isLike = false)
            }
        }
    }

    private fun updatePostInteraction(eventId: String, isLike: Boolean) {
        _uiState.update { state ->
            val updateFunc: (ScoredPost) -> ScoredPost = { post ->
                if (post.event.id == eventId) {
                    if (isLike) post.copy(isLiked = true, likeCount = post.likeCount + 1)
                    else post.copy(isReposted = true, repostCount = post.repostCount + 1)
                } else post
            }
            state.copy(
                globalPosts = state.globalPosts.map(updateFunc),
                followingPosts = state.followingPosts.map(updateFunc),
                searchResults = state.searchResults.map(updateFunc)
            )
        }
    }

    fun publishNote(content: String, contentWarning: String? = null) {
        viewModelScope.launch {
            try {
                repository.publishNote(content, contentWarning = contentWarning)
            } catch (e: Exception) { /* Silently ignore */ }
            refresh()
        }
    }

    fun muteUser(pubkey: String) {
        viewModelScope.launch {
            try {
                repository.muteUser(pubkey)
                _uiState.update { state ->
                    state.copy(
                        globalPosts = state.globalPosts.filter { it.event.pubkey != pubkey },
                        followingPosts = state.followingPosts.filter { it.event.pubkey != pubkey }
                    )
                }
            } catch (e: Exception) { /* Silently ignore */ }
        }
    }

    fun reportEvent(eventId: String?, pubkey: String, type: String, content: String) {
        viewModelScope.launch {
            try {
                repository.reportEvent(eventId, pubkey, type, content)
            } catch (e: Exception) { /* Silently ignore */ }
        }
    }

    fun submitBirdwatch(eventId: String, authorPubkey: String, type: String, content: String, url: String) {
        viewModelScope.launch {
            try {
                repository.publishBirdwatchLabel(eventId, authorPubkey, type, content, url)
                // Refresh birdwatch notes for this post
                fetchBirdwatchForPosts(listOf(_uiState.value.globalPosts.find { it.event.id == eventId } ?: return@launch))
            } catch (e: Exception) { /* Silently ignore */ }
        }
    }

    fun setNotInterested(eventId: String) {
        _uiState.update { state ->
            state.copy(
                globalPosts = state.globalPosts.filter { it.event.id != eventId },
                followingPosts = state.followingPosts.filter { it.event.id != eventId }
            )
        }
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
