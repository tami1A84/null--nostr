package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Nip05Utils
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

    // ─── Live streaming ───────────────────────────────────────────────────────
    /** Active subscription ID. Null until the initial load completes. */
    private var liveSubId: String? = null
    private var livePollingJob: Job? = null

    /** IDs of posts already in the timeline, used to deduplicate live events. */
    private val seenEventIds = mutableSetOf<String>()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            loadRecentSearches()

            // Parallel load to speed up initial state
            val followListJob = launch { loadFollowList() }
            val globalJob = launch { loadGlobalTimeline() }

            // Cache-first step 1: Try JSON cache (fully-enriched, instant decode)
            var cacheShown = false
            val cachedFollowing = repository.getCachedTimeline()
            if (cachedFollowing != null) {
                try {
                    val posts = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<List<io.nurunuru.app.data.models.ScoredPost>>(cachedFollowing)
                    if (posts.isNotEmpty()) {
                        seenEventIds.addAll(posts.map { it.event.id })
                        _uiState.update { it.copy(followingPosts = posts, isFollowingLoading = false) }
                        cacheShown = true
                        android.util.Log.d("TimelineViewModel", "JSON cache: ${posts.size} posts shown")
                    }
                } catch (_: Exception) { }
            }

            // Cache-first step 2: Fall back to nostrdb queryLocal if JSON cache unavailable
            if (!cacheShown) {
                val nostrdbPosts = repository.fetchCachedFollowTimeline(pubkeyHex, 50)
                if (nostrdbPosts.isNotEmpty()) {
                    seenEventIds.addAll(nostrdbPosts.map { it.event.id })
                    _uiState.update { it.copy(followingPosts = nostrdbPosts, isFollowingLoading = false) }
                    android.util.Log.d("TimelineViewModel", "nostrdb cache: ${nostrdbPosts.size} posts shown")
                }
            }

            followListJob.join()
            // Refresh following timeline from relay (keeps cached posts visible during fetch)
            loadFollowingTimeline()

            // Start live streaming once the initial data is loaded.
            startLiveStreaming()
        }
    }

    /**
     * Kick off a 1-second polling loop that prepends new events to the active
     * feed.  Called automatically after the initial load.
     */
    private fun startLiveStreaming() {
        livePollingJob?.cancel()

        // Seed deduplication set from whatever is already displayed.
        val state = _uiState.value
        seenEventIds.clear()
        seenEventIds.addAll(state.globalPosts.map { it.event.id })
        seenEventIds.addAll(state.followingPosts.map { it.event.id })

        // Subscribe globally so both tabs receive all new events in real time.
        // Using an empty authors list sends a REQ with no author filter, which
        // matches all Kind-1 notes on connected relays.
        val followAuthors = emptyList<String>()

        livePollingJob = viewModelScope.launch(Dispatchers.IO) {
            val subId = repository.startLiveStream(followAuthors)
            if (subId == null) {
                android.util.Log.w("TimelineViewModel", "Live stream unavailable (Rust client not ready)")
                return@launch
            }
            liveSubId = subId

            try {
                while (isActive) {
                    delay(1_000)

                    val newEvents = repository.pollLiveStream(subId)
                    if (newEvents.isEmpty()) continue

                    // Filter out duplicates (relay may echo back our own events).
                    val fresh = newEvents.filter { seenEventIds.add(it.id) }
                    if (fresh.isEmpty()) continue

                    android.util.Log.d("TimelineViewModel",
                        "Live: ${fresh.size} new event(s) received")

                    // Enrich with profile + engagement data on IO, then push to UI.
                    val enriched = repository.enrichPostsDirect(fresh)
                    if (enriched.isEmpty()) continue

                    withContext(Dispatchers.Main) {
                        val followSet = _uiState.value.followList.toSet()
                        _uiState.update { state ->
                            // Deduplicate against the current lists to guard against
                            // posts added after seenEventIds was seeded (e.g. relay
                            // fetch completing after live stream started).
                            val existingGlobalIds = state.globalPosts.mapTo(HashSet()) { it.event.id }
                            val existingFollowIds = state.followingPosts.mapTo(HashSet()) { it.event.id }
                            state.copy(
                                globalPosts = enriched.filter { it.event.id !in existingGlobalIds } + state.globalPosts,
                                followingPosts = enriched.filter {
                                    followSet.contains(it.event.pubkey) && it.event.id !in existingFollowIds
                                } + state.followingPosts
                            )
                        }
                    }
                }
            } finally {
                repository.stopLiveStream(subId)
                liveSubId = null
            }
        }
    }

    /** Stop live polling (e.g. when screen is backgrounded or VM is cleared). */
    fun stopLiveStreaming() {
        livePollingJob?.cancel()
        livePollingJob = null
    }

    private fun loadRecentSearches() {
        _uiState.update { it.copy(recentSearches = repository.getRecentSearches()) }
    }

    private suspend fun loadFollowList() {
        // Pattern A: Use cached follow list if available
        repository.getCachedFollowList(pubkeyHex)?.let { cached ->
            _uiState.update { it.copy(followList = cached) }
        }
        try {
            val follows = repository.fetchFollowList(pubkeyHex)
            _uiState.update { it.copy(followList = follows) }
        } catch (e: Exception) { /* Ignore */ }
    }

    private var globalLoadJob: kotlinx.coroutines.Job? = null
    fun loadGlobalTimeline(isRefresh: Boolean = false) {
        if (!isRefresh && globalLoadJob?.isActive == true) return

        globalLoadJob?.cancel()
        globalLoadJob = viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isGlobalRefreshing = true, globalError = null)
                else it.copy(isGlobalLoading = true, globalError = null)
            }
            try {
                val posts = repository.fetchRecommendedTimeline(50)
                seenEventIds.addAll(posts.map { it.event.id })
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

    fun deletePost(eventId: String) {
        viewModelScope.launch {
            try {
                val success = repository.deleteEvent(eventId)
                if (success) {
                    _uiState.update { state ->
                        state.copy(
                            globalPosts = state.globalPosts.filter { it.event.id != eventId },
                            followingPosts = state.followingPosts.filter { it.event.id != eventId }
                        )
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private var followingLoadJob: kotlinx.coroutines.Job? = null
    fun loadFollowingTimeline(isRefresh: Boolean = false) {
        if (!isRefresh && followingLoadJob?.isActive == true) return

        followingLoadJob?.cancel()
        followingLoadJob = viewModelScope.launch {
            _uiState.update {
                if (isRefresh) it.copy(isFollowingRefreshing = true, followingError = null)
                else it.copy(
                    isFollowingLoading = it.followingPosts.isEmpty(),
                    followingError = null
                )
            }
            try {
                val posts = repository.fetchFollowTimeline(pubkeyHex, 50)
                seenEventIds.addAll(posts.map { it.event.id })
                _uiState.update { it.copy(
                    followingPosts = posts,
                    isFollowingLoading = false,
                    isFollowingRefreshing = false
                ) }
                // Persist to cache
                try {
                    val json = kotlinx.serialization.json.Json { encodeDefaults = true }
                    repository.setCachedTimeline(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.nurunuru.app.data.models.ScoredPost.serializer()), posts))
                } catch (e: Exception) { /* ignore */ }

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

    fun likePost(eventId: String, emoji: String = "+", customTags: List<List<String>> = emptyList()) {
        viewModelScope.launch {
            val post = (_uiState.value.globalPosts + _uiState.value.followingPosts)
                .firstOrNull { it.event.id == eventId }
            val authorPubkey = post?.event?.pubkey ?: ""
            val success = repository.likePost(eventId, authorPubkey, emoji, customTags)
            if (success) {
                updatePostInteraction(eventId, isLike = true)
                if (authorPubkey.isNotEmpty()) repository.recordEngagement("like", authorPubkey)
            }
        }
    }

    fun repostPost(eventId: String) {
        viewModelScope.launch {
            val post = (_uiState.value.globalPosts + _uiState.value.followingPosts)
                .firstOrNull { it.event.id == eventId }
            val eventJson = post?.event?.let {
                try { kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
                    io.nurunuru.app.data.models.NostrEvent.serializer(), it)
                } catch (_: Exception) { null }
            }
            val success = repository.repostPost(eventId, eventJson)
            if (success) {
                updatePostInteraction(eventId, isLike = false)
                val authorPubkey = post?.event?.pubkey
                if (authorPubkey != null) repository.recordEngagement("repost", authorPubkey)
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
                repository.reportEvent(pubkey, eventId, type, content)
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
        // Get the author pubkey before filtering
        val authorPubkey = (_uiState.value.globalPosts + _uiState.value.followingPosts)
            .firstOrNull { it.event.id == eventId }?.event?.pubkey

        _uiState.update { state ->
            state.copy(
                globalPosts = state.globalPosts.filter { it.event.id != eventId },
                followingPosts = state.followingPosts.filter { it.event.id != eventId }
            )
        }

        // Persist to engagement tracker (synced with web lib/recommendation.js markNotInterested)
        if (authorPubkey != null) {
            viewModelScope.launch {
                try {
                    repository.markNotInterested(eventId, authorPubkey)
                } catch (_: Exception) { }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        liveSubId?.let { repository.stopLiveStream(it) }
        liveSubId = null
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
