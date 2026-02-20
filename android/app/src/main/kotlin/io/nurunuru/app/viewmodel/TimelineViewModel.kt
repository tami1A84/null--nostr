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

    // NIP-57: emits BOLT-11 invoice for the UI to launch wallet
    private val _zapInvoice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val zapInvoice: SharedFlow<String> = _zapInvoice.asSharedFlow()

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
                // NIP-05: verify badges in background after initial display
                launch { verifyNip05ForPosts(posts) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "タイムラインの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    /** Verify NIP-05 for post authors in background and update badges when done. */
    private suspend fun verifyNip05ForPosts(posts: List<ScoredPost>) {
        val uniqueProfiles = posts
            .mapNotNull { it.profile }
            .filter { it.nip05 != null && !it.nip05Verified }
            .distinctBy { it.pubkey }
            .take(20) // limit to avoid excessive HTTP requests
        for (profile in uniqueProfiles) {
            try {
                val verified = repository.verifyNip05(profile)
                if (verified.nip05Verified) {
                    _uiState.update { state ->
                        state.copy(posts = state.posts.map { post ->
                            if (post.profile?.pubkey == profile.pubkey)
                                post.copy(profile = verified)
                            else post
                        })
                    }
                }
            } catch (_: Exception) { }
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

    fun zapPost(eventId: String, sats: Long, comment: String = "") {
        viewModelScope.launch {
            try {
                val post = _uiState.value.posts.find { it.event.id == eventId } ?: return@launch
                val lud16 = post.profile?.lud16
                if (lud16.isNullOrBlank()) {
                    _notification.emit("このユーザーはZapに対応していません")
                    return@launch
                }
                _notification.emit("Zapを処理中...")
                val msats = sats * 1000L
                val payInfo = repository.fetchLnurlPayInfo(lud16) ?: run {
                    _notification.emit("LNURL情報の取得に失敗しました")
                    return@launch
                }
                if (!payInfo.allowsNostr) {
                    _notification.emit("このユーザーはNostr Zapに対応していません")
                    return@launch
                }
                val zapRequest = repository.createZapRequest(
                    recipientPubkeyHex = post.event.pubkey,
                    eventId = eventId,
                    msats = msats,
                    comment = comment,
                    relays = listOf("wss://yabu.me", "wss://relay.damus.io")
                ) ?: run {
                    _notification.emit("Zapリクエストの作成に失敗しました")
                    return@launch
                }
                val invoice = repository.fetchZapInvoice(payInfo, msats, zapRequest) ?: run {
                    _notification.emit("Lightning請求書の取得に失敗しました")
                    return@launch
                }
                _zapInvoice.emit(invoice)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notification.emit("Zapに失敗しました")
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
