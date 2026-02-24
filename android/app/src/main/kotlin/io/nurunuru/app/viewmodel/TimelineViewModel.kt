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
    val posts: List<ScoredPost> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val feedType: FeedType = FeedType.GLOBAL,
    val searchQuery: String = "",
    val searchResults: List<ScoredPost> = emptyList(),
    val isSearching: Boolean = false,
    val birdwatchNotes: Map<String, List<io.nurunuru.app.data.models.NostrEvent>> = emptyMap()
)

class TimelineViewModel(
    private val repository: NostrRepository,
    private val pubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState(isLoading = true))
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        loadTimeline()
    }

    fun loadTimeline() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val posts = when (_uiState.value.feedType) {
                    FeedType.GLOBAL -> repository.fetchGlobalTimeline(50)
                    FeedType.FOLLOWING -> repository.fetchFollowTimeline(pubkeyHex, 50)
                }
                _uiState.update { it.copy(posts = posts, isLoading = false) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "タイムラインの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            try {
                val posts = when (_uiState.value.feedType) {
                    FeedType.GLOBAL -> repository.fetchGlobalTimeline(50)
                    FeedType.FOLLOWING -> repository.fetchFollowTimeline(pubkeyHex, 50)
                }
                _uiState.update { it.copy(posts = posts, isRefreshing = false) }
                fetchBirdwatchForPosts(posts)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "更新に失敗しました", isRefreshing = false) }
            }
        }
    }

    private fun fetchBirdwatchForPosts(posts: List<ScoredPost>) {
        viewModelScope.launch {
            try {
                val ids = posts.map { it.event.id }
                val notes = repository.fetchBirdwatchNotes(ids)
                _uiState.update { it.copy(birdwatchNotes = notes) }
            } catch (e: Exception) {
                // Silently ignore
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
                    state.copy(posts = state.posts.map { post ->
                        if (post.event.id == eventId)
                            post.copy(isLiked = true, likeCount = post.likeCount + 1)
                        else post
                    })
                }
            }
        }
    }

    fun repostPost(eventId: String) {
        viewModelScope.launch {
            val success = repository.repostPost(eventId)
            if (success) {
                _uiState.update { state ->
                    state.copy(posts = state.posts.map { post ->
                        if (post.event.id == eventId)
                            post.copy(isReposted = true, repostCount = post.repostCount + 1)
                        else post
                    })
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
