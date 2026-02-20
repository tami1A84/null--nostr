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

    // One-shot Snackbar messages (success / error)
    private val _notification = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val notification: SharedFlow<String> = _notification.asSharedFlow()

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
        // Cancel any in-flight loadTimeline to prevent it overwriting refreshed posts
        loadJob?.cancel()
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
        if (!pendingLikes.add(eventId)) return
        viewModelScope.launch {
            try {
                val post = _uiState.value.posts.find { it.event.id == eventId } ?: return@launch
                val success = repository.likePost(eventId, post.event.pubkey)
                if (success) {
                    _uiState.update { state ->
                        state.copy(posts = state.posts.map { p ->
                            if (p.event.id == eventId && !p.isLiked)
                                p.copy(isLiked = true, likeCount = p.likeCount + 1)
                            else p
                        })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                pendingLikes.remove(eventId)
            }
        }
    }

    fun repostPost(eventId: String) {
        if (!pendingReposts.add(eventId)) return
        viewModelScope.launch {
            try {
                val post = _uiState.value.posts.find { it.event.id == eventId } ?: return@launch
                val success = repository.repostPost(eventId, post.event.pubkey)
                if (success) {
                    _uiState.update { state ->
                        state.copy(posts = state.posts.map { p ->
                            if (p.event.id == eventId && !p.isReposted)
                                p.copy(isReposted = true, repostCount = p.repostCount + 1)
                            else p
                        })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                pendingReposts.remove(eventId)
            }
        }
    }

    fun deletePost(eventId: String) {
        viewModelScope.launch {
            try {
                val success = repository.deleteEvent(eventId)
                if (success) {
                    _uiState.update { state ->
                        state.copy(posts = state.posts.filter { it.event.id != eventId })
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }

    fun publishNote(content: String) {
        viewModelScope.launch {
            try {
                val event = repository.publishNote(content)
                if (event == null) {
                    _notification.emit("投稿がリレーに拒否されました。接続を確認してください。")
                    return@launch
                }
                // Optimistic update: prepend own post immediately
                _uiState.update { state ->
                    state.copy(posts = listOf(ScoredPost(event = event, profile = myProfile)) + state.posts)
                }
                _notification.emit("投稿しました")
                delay(500)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notification.emit("投稿に失敗しました")
            }
        }
    }

    private suspend fun fetchForCurrentFeed(): List<ScoredPost> =
        when (_uiState.value.feedType) {
            FeedType.GLOBAL -> repository.fetchGlobalTimeline(100)
            FeedType.FOLLOWING -> repository.fetchFollowTimeline(pubkeyHex, 100)
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
