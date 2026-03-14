package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Nip05Utils
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.SearchQueryParser
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
    val pendingGlobalPosts: List<ScoredPost> = emptyList(),
    val pendingFollowingPosts: List<ScoredPost> = emptyList(),
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
    val hasNewFollowing: Boolean = false,
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

    /**
     * IDs of events already processed by the live stream.
     * Uses ConcurrentHashMap.newKeySet() for thread safety — the live polling
     * loop runs on Dispatchers.IO while loadFollowingTimeline/loadGlobalTimeline
     * add to this set on Dispatchers.Main after suspension.
     */
    private val seenEventIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Deduplicates a post list by event ID, keeping the first occurrence. */
    private fun List<ScoredPost>.deduped() = distinctBy { it.event.id }

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            loadRecentSearches()

            // Parallel load to speed up initial state
            val followListJob = launch { loadFollowList() }
            val globalJob = launch { loadGlobalTimeline() }

            // バックグラウンドで設定をプリフェッチ（UIをブロックしない）
            launch(Dispatchers.IO) {
                val pk = pubkeyHex.ifEmpty { return@launch }
                listOf(
                    suspend { repository.fetchMuteList(pk) },
                    suspend { repository.fetchEmojiList(pk) },
                    suspend { repository.fetchProfileBadgesInfo(pk) }
                ).forEach { fetch ->
                    try { fetch() }
                    catch (e: Exception) {
                        android.util.Log.w("TimelineViewModel", "Settings prefetch failed: ${e.message}")
                    }
                }
                // NIP-65リレー同期
                try {
                    repository.syncNip65Relays(pk)
                } catch (e: Exception) {
                    android.util.Log.w("TimelineViewModel", "NIP-65 relay sync failed: ${e.message}")
                }
            }

            // Cache-first step 1: Try JSON cache (fully-enriched, instant decode)
            // JSON decode and DB query are CPU/IO-bound — run off main thread.
            var cacheShown = false
            val cachedFollowing = withContext(Dispatchers.IO) { repository.getCachedTimeline() }
            if (cachedFollowing != null) {
                try {
                    val posts = withContext(Dispatchers.Default) {
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .decodeFromString<List<io.nurunuru.app.data.models.ScoredPost>>(cachedFollowing)
                    }
                    if (posts.isNotEmpty()) {
                        val deduped = posts.deduped()
                        seenEventIds.addAll(deduped.map { it.event.id })
                        _uiState.update { it.copy(followingPosts = deduped, isFollowingLoading = false) }
                        cacheShown = true
                        android.util.Log.d("TimelineViewModel", "JSON cache: ${deduped.size} posts shown")
                    }
                } catch (_: Exception) { }
            }

            // Cache-first step 2: Fall back to nostrdb queryLocal if JSON cache unavailable
            if (!cacheShown) {
                val nostrdbPosts = withContext(Dispatchers.IO) {
                    repository.fetchCachedFollowTimeline(pubkeyHex, 50)
                }.deduped()
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

        // おすすめタブ用の内部バッファ。50件たまったらピルを表示する。
        val liveGlobalBuffer = mutableListOf<ScoredPost>()

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

                    // Enrich with profile + engagement data on IO, then push to pending buffer.
                    val enriched = repository.enrichPostsDirect(fresh)
                    if (enriched.isEmpty()) continue

                    // おすすめタブ: フルアルゴリズム適用（ミュート・品質・言語ブースト・スコアリング）
                    val scoredForGlobal = repository.scoreForRecommended(enriched)

                    // フォロータブ: ミュートフィルターのみ適用（IO スレッド上、cheap）
                    val mutedPubkeys = repository.getCachedMuteList(pubkeyHex)
                        ?.pubkeys?.toSet() ?: emptySet()

                    // リレータブ: 表示済みでない投稿のみバッファに追加
                    val existingGlobalIds = _uiState.value.globalPosts.mapTo(HashSet()) { it.event.id }
                    val pendingGlobalIds = _uiState.value.pendingGlobalPosts.mapTo(HashSet()) { it.event.id }
                    val newGlobal = scoredForGlobal.filter {
                        it.event.id !in existingGlobalIds && it.event.id !in pendingGlobalIds
                    }
                    if (newGlobal.isNotEmpty()) liveGlobalBuffer.addAll(newGlobal)

                    withContext(Dispatchers.Main) {
                        val followSet = _uiState.value.followList.toSet()
                        _uiState.update { state ->
                            val existingFollowIds = state.followingPosts.mapTo(HashSet()) { it.event.id }
                            val pendingFollowIds = state.pendingFollowingPosts.mapTo(HashSet()) { it.event.id }

                            val newFollow = enriched.filter {
                                followSet.contains(it.event.pubkey) &&
                                it.event.id !in existingFollowIds &&
                                it.event.id !in pendingFollowIds &&
                                it.event.pubkey !in mutedPubkeys
                            }

                            // リレータブ: 3件たまったらピルを表示してバッファをクリア
                            val showGlobalPill = liveGlobalBuffer.size >= 3
                            if (showGlobalPill) {
                                val buffered = liveGlobalBuffer.sortedByDescending { it.event.createdAt }.deduped()
                                liveGlobalBuffer.clear()
                                android.util.Log.d("TimelineViewModel",
                                    "Relay pill ready: ${buffered.size} posts pending")
                                state.copy(
                                    pendingGlobalPosts = (buffered + state.pendingGlobalPosts).deduped(),
                                    pendingFollowingPosts = (newFollow + state.pendingFollowingPosts).deduped(),
                                    hasNewFollowing = state.hasNewFollowing ||
                                        (state.feedType != FeedType.FOLLOWING && newFollow.isNotEmpty())
                                )
                            } else {
                                state.copy(
                                    pendingFollowingPosts = (newFollow + state.pendingFollowingPosts).deduped(),
                                    hasNewFollowing = state.hasNewFollowing ||
                                        (state.feedType != FeedType.FOLLOWING && newFollow.isNotEmpty())
                                )
                            }
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
                val posts = repository.fetchRecommendedTimeline(50).deduped()
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
                val posts = repository.fetchFollowTimeline(pubkeyHex, 50).deduped()
                seenEventIds.addAll(posts.map { it.event.id })
                _uiState.update { state ->
                    state.copy(
                        followingPosts = posts,
                        isFollowingLoading = false,
                        isFollowingRefreshing = false,
                        hasNewFollowing = state.feedType != FeedType.FOLLOWING && posts.isNotEmpty()
                    )
                }
                // Persist to cache (encode and write on IO thread)
                launch(Dispatchers.IO) {
                    try {
                        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
                        repository.setCachedTimeline(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.nurunuru.app.data.models.ScoredPost.serializer()), posts))
                    } catch (e: Exception) { /* ignore */ }
                }

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
            FeedType.GLOBAL -> {
                _uiState.update { it.copy(pendingGlobalPosts = emptyList()) }
                loadGlobalTimeline(isRefresh = true)
            }
            FeedType.FOLLOWING -> {
                _uiState.update { it.copy(pendingFollowingPosts = emptyList()) }
                loadFollowingTimeline(isRefresh = true)
            }
        }
    }

    /**
     * 新着ピルのタップ処理。
     * リレー/フォロータブともに pending を先頭に prepend する。
     */
    fun flushPendingPosts(feedType: FeedType = _uiState.value.feedType) {
        when (feedType) {
            FeedType.GLOBAL -> _uiState.update { state ->
                val added = state.pendingGlobalPosts
                android.util.Log.d("TimelineViewModel",
                    "Relay pill tapped: prepending ${added.size} posts → total ${added.size + state.globalPosts.size}")
                state.copy(
                    globalPosts = (added + state.globalPosts).deduped(),
                    pendingGlobalPosts = emptyList(),
                    hasNewRecommendations = false
                )
            }
            FeedType.FOLLOWING -> _uiState.update { state ->
                android.util.Log.d("TimelineViewModel",
                    "Follow pill tapped: prepending ${state.pendingFollowingPosts.size} posts")
                state.copy(
                    followingPosts = (state.pendingFollowingPosts + state.followingPosts).deduped(),
                    pendingFollowingPosts = emptyList()
                )
            }
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
                hasNewRecommendations = if (feedType == FeedType.GLOBAL) false else it.hasNewRecommendations,
                hasNewFollowing = if (feedType == FeedType.FOLLOWING) false else it.hasNewFollowing
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

                // 4. オペレータ付き高度検索 or 通常テキスト検索
                val parsed = SearchQueryParser.parse(trimmedQuery)
                val results = if (parsed.hasOperators) {
                    val resolvedNip05 = parsed.fromNip05.mapNotNull { Nip05Utils.resolveNip05(it) }
                    repository.advancedSearch(parsed, resolvedNip05, 30)
                } else {
                    repository.searchNotes(trimmedQuery, 30)
                }
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
            // Toggle: if already liked, unlike (delete reaction event)
            if (post?.isLiked == true) {
                val reactionEventId = post.myLikeEventId ?: return@launch
                val success = repository.deleteEvent(reactionEventId)
                if (success) updatePostInteraction(eventId, isLike = true, undo = true)
                return@launch
            }
            val authorPubkey = post?.event?.pubkey ?: ""
            val newEventId = repository.likePost(eventId, authorPubkey, emoji, customTags)
            if (newEventId != null) {
                updatePostInteraction(eventId, isLike = true, newEventId = newEventId)
                if (authorPubkey.isNotEmpty()) repository.recordEngagement("like", authorPubkey)
            }
        }
    }

    fun repostPost(eventId: String) {
        viewModelScope.launch {
            val post = (_uiState.value.globalPosts + _uiState.value.followingPosts)
                .firstOrNull { it.event.id == eventId }
            // Toggle: if already reposted, unrepost (delete repost event)
            if (post?.isReposted == true) {
                val repostEventId = post.myRepostEventId ?: return@launch
                val success = repository.deleteEvent(repostEventId)
                if (success) updatePostInteraction(eventId, isLike = false, undo = true)
                return@launch
            }
            val eventJson = post?.event?.let {
                try { kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
                    io.nurunuru.app.data.models.NostrEvent.serializer(), it)
                } catch (_: Exception) { null }
            }
            val newEventId = repository.repostPost(eventId, eventJson)
            if (newEventId != null) {
                updatePostInteraction(eventId, isLike = false, newEventId = newEventId)
                val authorPubkey = post?.event?.pubkey
                if (authorPubkey != null) repository.recordEngagement("repost", authorPubkey)
            }
        }
    }

    private fun updatePostInteraction(eventId: String, isLike: Boolean, undo: Boolean = false, newEventId: String? = null) {
        _uiState.update { state ->
            val updateFunc: (ScoredPost) -> ScoredPost = { post ->
                if (post.event.id == eventId) {
                    if (isLike) {
                        if (undo) post.copy(isLiked = false, likeCount = maxOf(0, post.likeCount - 1), myLikeEventId = null)
                        else post.copy(isLiked = true, likeCount = post.likeCount + 1, myLikeEventId = newEventId ?: post.myLikeEventId)
                    } else {
                        if (undo) post.copy(isReposted = false, repostCount = maxOf(0, post.repostCount - 1), myRepostEventId = null)
                        else post.copy(isReposted = true, repostCount = post.repostCount + 1, myRepostEventId = newEventId ?: post.myRepostEventId)
                    }
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
