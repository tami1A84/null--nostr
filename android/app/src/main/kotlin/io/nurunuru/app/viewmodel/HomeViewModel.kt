package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Nip05Utils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomeUiState(
    val profile: UserProfile? = null,
    val posts: List<ScoredPost> = emptyList(),
    val likedPosts: List<ScoredPost> = emptyList(),
    val followCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isActionLoading: Boolean = false,
    val error: String? = null,
    val viewingPubkey: String? = null, // null = own profile
    val activeTab: Int = 0, // 0: Posts, 1: Likes
    val isNip05Verified: Boolean = false,
    val badges: List<String> = emptyList(), // badge image URLs
    val isFollowing: Boolean = false,
    val followList: List<String> = emptyList(),
    val followProfiles: Map<String, UserProfile> = emptyMap(),
    val uploadServer: String = "nostr.build",
    val searchQuery: String = "",
    val searchResults: List<ScoredPost> = emptyList(),
    val isSearching: Boolean = false,
    val bookmarkedPosts: List<ScoredPost> = emptyList(),
    val isBookmarksLoading: Boolean = false
) {
    val isOwnProfile: Boolean
        get() = viewingPubkey == null
}

class HomeViewModel(
    private val repository: NostrRepository,
    val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Locally deleted post IDs — prevents deleted posts from reappearing after relay re-fetch.
    // Cleared only on ViewModel destruction (Activity lifecycle boundary).
    private val deletedPostIds = mutableSetOf<String>()

    init {
        _uiState.update { it.copy(uploadServer = repository.getUploadServer()) }
    }

    fun loadMyProfile() {
        loadProfile(myPubkeyHex)
    }

    fun loadProfile(pubkeyHex: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, isNip05Verified = false,
                    viewingPubkey = pubkeyHex.takeIf { it != myPubkeyHex })
            }

            // Phase 1: キャッシュからの即時表示（ネットワーク不使用）
            val cachedProfile = repository.getCachedProfile(pubkeyHex)
            val cachedFollowList = repository.getCachedFollowList(pubkeyHex)
            val cachedPosts = repository.getCachedUserNotesPosts(pubkeyHex).filterDeleted()
            val cachedLikes = repository.getCachedUserLikesPosts(pubkeyHex).filterDeleted()
            val cachedBadgeUrls = repository.getCachedProfileBadges(pubkeyHex)
                .map { it.image }.filter { it.isNotEmpty() }
            if (cachedProfile != null || cachedPosts.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        profile = cachedProfile ?: it.profile,
                        followList = cachedFollowList ?: it.followList,
                        followCount = cachedFollowList?.size ?: it.followCount,
                        posts = if (cachedPosts.isNotEmpty()) cachedPosts else it.posts,
                        likedPosts = if (cachedLikes.isNotEmpty()) cachedLikes else it.likedPosts,
                        badges = if (cachedBadgeUrls.isNotEmpty()) cachedBadgeUrls else it.badges,
                        isLoading = false
                    )
                }
            }

            // Phase 2: リレーから最新を取得（常に実行）
            fetchAndApply(pubkeyHex, isRefresh = false)
        }
    }

    fun refresh() {
        val targetPubkey = _uiState.value.viewingPubkey ?: myPubkeyHex
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            fetchAndApply(targetPubkey, isRefresh = true)
        }
    }

    private data class FetchResult(
        val profile: UserProfile?,
        val posts: List<ScoredPost>,
        val likedPosts: List<ScoredPost>,
        val followList: List<String>,
        val badgeUrls: List<String>
    )

    /**
     * リレーから最新データを並列取得してUIに反映する共通処理。
     * loadProfile() の Phase 2 と refresh() の両方から呼ばれる。
     * deletedPostIds でフィルタリングするため、削除後のリロードで再出現しない。
     */
    private suspend fun fetchAndApply(pubkeyHex: String, isRefresh: Boolean) {
        try {
            val (profile, posts, likedPosts, followList, badgeUrls) = coroutineScope {
                val profileJob = async { repository.fetchProfile(pubkeyHex) }
                val postsJob   = async { repository.fetchUserNotes(pubkeyHex, 50) }
                val likesJob   = async { repository.fetchUserLikes(pubkeyHex, 50) }
                val followJob  = async { repository.fetchFollowList(pubkeyHex) }
                val badgesJob  = async {
                    repository.fetchProfileBadgesInfo(pubkeyHex)
                        .map { it.image }.filter { it.isNotEmpty() }
                }
                awaitAll(profileJob, postsJob, likesJob, followJob, badgesJob)
                FetchResult(
                    profile    = profileJob.await(),
                    posts      = postsJob.await(),
                    likedPosts = likesJob.await(),
                    followList = followJob.await(),
                    badgeUrls  = badgesJob.await()
                )
            }

            val enrichedPosts = posts.filterDeleted()
                .map { if (it.event.pubkey == pubkeyHex) it.copy(badges = badgeUrls) else it }
            val enrichedLikes = likedPosts.filterDeleted()
                .map { if (it.event.pubkey == pubkeyHex) it.copy(badges = badgeUrls) else it }

            val myFollowList = if (pubkeyHex == myPubkeyHex) followList
                               else repository.fetchFollowList(myPubkeyHex)
            val isFollowing = pubkeyHex != myPubkeyHex && myFollowList.contains(pubkeyHex)

            _uiState.update {
                it.copy(
                    profile = profile,
                    posts = enrichedPosts,
                    likedPosts = enrichedLikes,
                    followCount = followList.size,
                    followList = followList,
                    badges = badgeUrls,
                    isFollowing = isFollowing,
                    isLoading = false,
                    isRefreshing = false
                )
            }

            val nip05 = profile?.nip05
            if (nip05 != null) {
                viewModelScope.launch {
                    val verified = Nip05Utils.verifyNip05(nip05, pubkeyHex)
                    _uiState.update { it.copy(isNip05Verified = verified) }
                }
            } else {
                _uiState.update { it.copy(isNip05Verified = false) }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    error = if (!isRefresh) "プロフィールの読み込みに失敗しました" else null,
                    isLoading = false,
                    isRefreshing = false
                )
            }
        }
    }

    /** 削除済み投稿をフィルタリングする拡張関数 */
    private fun List<ScoredPost>.filterDeleted(): List<ScoredPost> =
        if (deletedPostIds.isEmpty()) this else filter { it.event.id !in deletedPostIds }

    fun muteUser(pubkey: String) {
        viewModelScope.launch {
            try {
                repository.muteUser(pubkey)
                refresh()
            } catch (e: Exception) { /* Ignore */ }
        }
    }

    fun reportEvent(eventId: String?, pubkey: String, type: String, content: String) {
        viewModelScope.launch {
            try {
                repository.reportEvent(pubkey, eventId, type, content)
            } catch (e: Exception) { /* Ignore */ }
        }
    }

    fun submitBirdwatch(eventId: String, authorPubkey: String, type: String, content: String, url: String) {
        viewModelScope.launch {
            try {
                repository.publishBirdwatchLabel(eventId, authorPubkey, type, content, url)
            } catch (e: Exception) { /* Ignore */ }
        }
    }

    fun likePost(eventId: String, emoji: String = "+", customTags: List<List<String>> = emptyList()) {
        viewModelScope.launch {
            try {
                val post = (_uiState.value.posts + _uiState.value.likedPosts)
                    .firstOrNull { it.event.id == eventId }
                if (post?.isLiked == true) {
                    val reactionEventId = post.myLikeEventId ?: return@launch
                    val success = repository.deleteEvent(reactionEventId)
                    if (success) {
                        _uiState.update { state ->
                            val updatePost = { p: ScoredPost ->
                                if (p.event.id == eventId) p.copy(
                                    isLiked = false,
                                    likeCount = maxOf(0, p.likeCount - 1),
                                    myLikeEventId = null
                                ) else p
                            }
                            state.copy(posts = state.posts.map(updatePost), likedPosts = state.likedPosts.map(updatePost))
                        }
                    }
                    return@launch
                }
                val authorPubkey = post?.event?.pubkey ?: ""
                val newEventId = repository.likePost(eventId, authorPubkey, emoji, customTags)
                if (newEventId != null) {
                    _uiState.update { state ->
                        val updatePost = { p: ScoredPost ->
                            if (p.event.id == eventId) p.copy(
                                isLiked = true,
                                likeCount = p.likeCount + 1,
                                myLikeEventId = newEventId
                            ) else p
                        }
                        state.copy(
                            posts = state.posts.map(updatePost),
                            likedPosts = state.likedPosts.map(updatePost)
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "いいねに失敗しました") }
            }
        }
    }

    fun repostPost(eventId: String) {
        viewModelScope.launch {
            try {
                val post = (_uiState.value.posts + _uiState.value.likedPosts)
                    .firstOrNull { it.event.id == eventId }
                if (post?.isReposted == true) {
                    val repostEventId = post.myRepostEventId ?: return@launch
                    val success = repository.deleteEvent(repostEventId)
                    if (success) {
                        _uiState.update { state ->
                            val updatePost = { p: ScoredPost ->
                                if (p.event.id == eventId) p.copy(
                                    isReposted = false,
                                    repostCount = maxOf(0, p.repostCount - 1),
                                    myRepostEventId = null
                                ) else p
                            }
                            state.copy(posts = state.posts.map(updatePost), likedPosts = state.likedPosts.map(updatePost))
                        }
                    }
                    return@launch
                }
                val eventJson = post?.event?.let {
                    try { kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(
                        io.nurunuru.app.data.models.NostrEvent.serializer(), it)
                    } catch (_: Exception) { null }
                }
                val newEventId = repository.repostPost(eventId, eventJson)
                if (newEventId != null) {
                    _uiState.update { state ->
                        val updatePost = { p: ScoredPost ->
                            if (p.event.id == eventId) p.copy(
                                isReposted = true,
                                repostCount = p.repostCount + 1,
                                myRepostEventId = newEventId
                            ) else p
                        }
                        state.copy(
                            posts = state.posts.map(updatePost),
                            likedPosts = state.likedPosts.map(updatePost)
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "リポストに失敗しました") }
            }
        }
    }

    fun deletePost(eventId: String) {
        viewModelScope.launch {
            try {
                val success = repository.deleteEvent(eventId)
                if (success) {
                    deletedPostIds.add(eventId)
                    _uiState.update { state ->
                        state.copy(
                            posts = state.posts.filter { it.event.id != eventId },
                            likedPosts = state.likedPosts.filter { it.event.id != eventId }
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "削除に失敗しました") }
            }
        }
    }

    fun setActiveTab(index: Int) {
        _uiState.update { it.copy(activeTab = index) }
    }

    fun updateProfile(profile: UserProfile) {
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                val success = repository.updateProfile(profile)
                if (success) {
                    _uiState.update { it.copy(profile = profile, isActionLoading = false) }
                } else {
                    _uiState.update { it.copy(error = "プロフィールの更新に失敗しました", isActionLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isActionLoading = false) }
            }
        }
    }

    fun loadFollowProfiles(pubkeyHex: String? = null) {
        viewModelScope.launch {
            val pubkeys = if (pubkeyHex != null) {
                try { repository.fetchFollowList(pubkeyHex) } catch (e: Exception) { emptyList() }
            } else {
                _uiState.value.followList
            }
            if (pubkeys.isEmpty()) return@launch
            try {
                val profiles = repository.fetchProfiles(pubkeys)
                _uiState.update { it.copy(followProfiles = profiles) }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun unfollowUser(targetPubkeyHex: String) {
        viewModelScope.launch {
            try {
                val success = repository.unfollowUser(myPubkeyHex, targetPubkeyHex)
                if (success) {
                    _uiState.update { state ->
                        state.copy(
                            followList = state.followList.filter { it != targetPubkeyHex },
                            followCount = if (state.viewingPubkey == null) state.followCount - 1 else state.followCount,
                            isFollowing = if (state.viewingPubkey == targetPubkeyHex) false else state.isFollowing
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "フォロー解除に失敗しました") }
            }
        }
    }

    fun followUser(targetPubkeyHex: String) {
        viewModelScope.launch {
            try {
                val success = repository.followUser(myPubkeyHex, targetPubkeyHex)
                if (success) {
                    _uiState.update { state ->
                        state.copy(
                            isFollowing = if (state.viewingPubkey == targetPubkeyHex) true else state.isFollowing
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "フォローに失敗しました") }
            }
        }
    }

    fun publishNote(content: String, cw: String? = null) {
        viewModelScope.launch {
            try {
                val event = repository.publishNote(content, contentWarning = cw)
                if (event != null) {
                    refresh()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "投稿に失敗しました") }
            }
        }
    }

    suspend fun uploadImage(fileBytes: ByteArray, mimeType: String): String? {
        return try {
            repository.uploadImage(fileBytes, mimeType)
        } catch (e: Exception) {
            null
        }
    }

    fun setUploadServer(server: String) {
        repository.setUploadServer(server)
        _uiState.update { it.copy(uploadServer = server) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun loadBookmarks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBookmarksLoading = true) }
            try {
                val posts = repository.fetchBookmarkedPosts(myPubkeyHex)
                _uiState.update { it.copy(bookmarkedPosts = posts, isBookmarksLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBookmarksLoading = false) }
            }
        }
    }

    fun toggleBookmark(eventId: String, isCurrentlyBookmarked: Boolean) {
        viewModelScope.launch {
            try {
                if (isCurrentlyBookmarked) {
                    repository.removeBookmark(myPubkeyHex, eventId)
                    _uiState.update { state ->
                        val update = { p: ScoredPost -> if (p.event.id == eventId) p.copy(isBookmarked = false) else p }
                        state.copy(posts = state.posts.map(update), likedPosts = state.likedPosts.map(update),
                            bookmarkedPosts = state.bookmarkedPosts.filter { it.event.id != eventId })
                    }
                } else {
                    repository.addBookmark(myPubkeyHex, eventId)
                    _uiState.update { state ->
                        val update = { p: ScoredPost -> if (p.event.id == eventId) p.copy(isBookmarked = true) else p }
                        state.copy(posts = state.posts.map(update), likedPosts = state.likedPosts.map(update))
                    }
                }
            } catch (e: Exception) { }
        }
    }

    fun addBookmark(eventId: String) {
        viewModelScope.launch {
            try {
                repository.addBookmark(myPubkeyHex, eventId)
                val updated = _uiState.value.posts.find { it.event.id == eventId }
                    ?: _uiState.value.likedPosts.find { it.event.id == eventId }
                if (updated != null) {
                    _uiState.update { it.copy(bookmarkedPosts = it.bookmarkedPosts + updated.copy(isBookmarked = true)) }
                }
            } catch (e: Exception) { }
        }
    }

    fun removeBookmark(eventId: String) {
        viewModelScope.launch {
            try {
                repository.removeBookmark(myPubkeyHex, eventId)
                _uiState.update { it.copy(bookmarkedPosts = it.bookmarkedPosts.filter { p -> p.event.id != eventId }) }
            } catch (e: Exception) { }
        }
    }

    fun searchPosts(query: String) {
        val targetPubkey = _uiState.value.viewingPubkey ?: myPubkeyHex
        if (query.isBlank()) {
            _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(searchQuery = query, isSearching = true) }
            try {
                val results = repository.searchNotes(query, 50)
                val filtered = results.filter { it.event.pubkey == targetPubkey }
                _uiState.update { it.copy(searchResults = filtered, isSearching = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
    }

    class Factory(
        private val repository: NostrRepository,
        private val myPubkeyHex: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository, myPubkeyHex) as T
    }
}
