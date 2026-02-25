package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class FeedType { GLOBAL, FOLLOWING }

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

    init {
        loadFollowList()
        loadGlobalTimeline()
        loadFollowingTimeline()
    }

    private fun loadFollowList() {
        viewModelScope.launch {
            try {
                val follows = repository.fetchFollowList(pubkeyHex)
                _uiState.update { it.copy(followList = follows) }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun loadGlobalTimeline() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGlobalLoading = true, globalError = null) }
            try {
                val posts = repository.fetchGlobalTimeline(50)
                _uiState.update { it.copy(globalPosts = posts, isGlobalLoading = false) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update { it.copy(globalError = "おすすめの読み込みに失敗しました", isGlobalLoading = false) }
            }
        }
    }

    fun loadFollowingTimeline() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFollowingLoading = true, followingError = null) }
            try {
                val posts = repository.fetchFollowTimeline(pubkeyHex, 50)
                _uiState.update { it.copy(followingPosts = posts, isFollowingLoading = false) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update { it.copy(followingError = "フォロー中の読み込みに失敗しました", isFollowingLoading = false) }
            }
        }
    }

    fun refresh() {
        when (_uiState.value.feedType) {
            FeedType.GLOBAL -> refreshGlobal()
            FeedType.FOLLOWING -> refreshFollowing()
        }
    }

    private fun refreshGlobal() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGlobalRefreshing = true, globalError = null) }
            try {
                val posts = repository.fetchGlobalTimeline(50)
                _uiState.update { it.copy(globalPosts = posts, isGlobalRefreshing = false) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update { it.copy(globalError = "更新に失敗しました", isGlobalRefreshing = false) }
            }
        }
    }

    private fun refreshFollowing() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFollowingRefreshing = true, followingError = null) }
            try {
                val posts = repository.fetchFollowTimeline(pubkeyHex, 50)
                _uiState.update { it.copy(followingPosts = posts, isFollowingRefreshing = false) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update { it.copy(followingError = "更新に失敗しました", isFollowingRefreshing = false) }
            }
        }
    }

    private fun fetchBirdwatchForPosts(posts: List<ScoredPost>) {
        viewModelScope.launch {
            try {
                val ids = posts.map { it.event.id }
                val notes = repository.fetchBirdwatchNotes(ids)
                _uiState.update { state ->
                    val newNotes = state.birdwatchNotes.toMutableMap()
                    newNotes.putAll(notes)
                    state.copy(birdwatchNotes = newNotes)
                }
            } catch (e: Exception) {
                // Silently ignore
            }
        }
    }

    fun switchFeed(feedType: FeedType) {
        if (_uiState.value.feedType == feedType) return
        _uiState.update { it.copy(feedType = feedType) }
        // Feeds are loaded independently in init and keep their state,
        // but we might want to refresh when switching if they are empty
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
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        viewModelScope.launch {
            try {
                val results = repository.searchNotes(query, 30)
                _uiState.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
    }

    fun likePost(eventId: String) {
        viewModelScope.launch {
            val success = repository.likePost(eventId)
            if (success) {
                _uiState.update { state ->
                    state.copy(
                        globalPosts = state.globalPosts.map { post ->
                            if (post.event.id == eventId) post.copy(isLiked = true, likeCount = post.likeCount + 1)
                            else post
                        },
                        followingPosts = state.followingPosts.map { post ->
                            if (post.event.id == eventId) post.copy(isLiked = true, likeCount = post.likeCount + 1)
                            else post
                        },
                        searchResults = state.searchResults.map { post ->
                            if (post.event.id == eventId) post.copy(isLiked = true, likeCount = post.likeCount + 1)
                            else post
                        }
                    )
                }
            }
        }
    }

    fun repostPost(eventId: String) {
        viewModelScope.launch {
            val success = repository.repostPost(eventId)
            if (success) {
                _uiState.update { state ->
                    state.copy(
                        globalPosts = state.globalPosts.map { post ->
                            if (post.event.id == eventId) post.copy(isReposted = true, repostCount = post.repostCount + 1)
                            else post
                        },
                        followingPosts = state.followingPosts.map { post ->
                            if (post.event.id == eventId) post.copy(isReposted = true, repostCount = post.repostCount + 1)
                            else post
                        },
                        searchResults = state.searchResults.map { post ->
                            if (post.event.id == eventId) post.copy(isReposted = true, repostCount = post.repostCount + 1)
                            else post
                        }
                    )
                }
            }
        }
    }

    fun publishNote(content: String, contentWarning: String? = null) {
        viewModelScope.launch {
            try {
                repository.publishNote(content, contentWarning = contentWarning)
            } catch (e: Exception) {
                // Publishing failed silently; do not crash
            }
            refresh()
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
