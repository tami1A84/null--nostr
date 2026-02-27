package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Nip05Utils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    val badges: List<NostrEvent> = emptyList(),
    val isFollowing: Boolean = false,
    val followList: List<String> = emptyList(),
    val followProfiles: Map<String, UserProfile> = emptyMap(),
    val uploadServer: String = "nostr.build",
    val searchQuery: String = "",
    val searchResults: List<ScoredPost> = emptyList(),
    val isSearching: Boolean = false
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

    init {
        _uiState.update { it.copy(uploadServer = repository.getUploadServer()) }
        // Background prefetch matching web behavior
        loadMyProfile()
    }

    fun loadMyProfile() {
        loadProfile(myPubkeyHex)
    }

    fun loadProfile(pubkeyHex: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, viewingPubkey = pubkeyHex.takeIf { it != myPubkeyHex }) }
            try {
                val profile = repository.fetchProfile(pubkeyHex)
                val posts = repository.fetchUserNotes(pubkeyHex, 50)
                val likedPosts = repository.fetchUserLikes(pubkeyHex, 50)
                val followList = repository.fetchFollowList(pubkeyHex)
                val badges = repository.fetchBadges(pubkeyHex)
                val badgeUrls = badges.mapNotNull { it.getTagValue("thumb") ?: it.getTagValue("image") }

                val enrichedPosts = posts.map { if (it.event.pubkey == pubkeyHex) it.copy(badges = badgeUrls) else it }
                val enrichedLikes = likedPosts.map { if (it.event.pubkey == pubkeyHex) it.copy(badges = badgeUrls) else it }

                val myFollowList = if (pubkeyHex == myPubkeyHex) followList else repository.fetchFollowList(myPubkeyHex)
                val isFollowing = if (pubkeyHex == myPubkeyHex) false else myFollowList.contains(pubkeyHex)

                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = enrichedPosts,
                        likedPosts = enrichedLikes,
                        followCount = followList.size,
                        followList = followList,
                        badges = badges,
                        isFollowing = isFollowing,
                        isLoading = false
                    )
                }

                // Verify NIP-05
                val currentNip05 = profile?.nip05
                if (currentNip05 != null) {
                    viewModelScope.launch {
                        val verified = Nip05Utils.verifyNip05(currentNip05, pubkeyHex)
                        _uiState.update { it.copy(isNip05Verified = verified) }
                    }
                } else {
                    _uiState.update { it.copy(isNip05Verified = false) }
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(error = "プロフィールの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

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
                val success = repository.likePost(eventId, emoji, customTags)
                if (success) {
                    _uiState.update { state ->
                        val updatePost = { post: ScoredPost ->
                            if (post.event.id == eventId) post.copy(
                                isLiked = true,
                                likeCount = post.likeCount + 1
                            ) else post
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
                val success = repository.repostPost(eventId)
                if (success) {
                    _uiState.update { state ->
                        val updatePost = { post: ScoredPost ->
                            if (post.event.id == eventId) post.copy(
                                isReposted = true,
                                repostCount = post.repostCount + 1
                            ) else post
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

    fun refresh() {
        val targetPubkey = _uiState.value.viewingPubkey ?: myPubkeyHex
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val profile = repository.fetchProfile(targetPubkey)
                val posts = repository.fetchUserNotes(targetPubkey, 50)
                val likedPosts = repository.fetchUserLikes(targetPubkey, 50)
                val followList = repository.fetchFollowList(targetPubkey)
                val badges = repository.fetchBadges(targetPubkey)
                val badgeUrls = badges.mapNotNull { it.getTagValue("thumb") ?: it.getTagValue("image") }

                val enrichedPosts = posts.map { if (it.event.pubkey == targetPubkey) it.copy(badges = badgeUrls) else it }
                val enrichedLikes = likedPosts.map { if (it.event.pubkey == targetPubkey) it.copy(badges = badgeUrls) else it }

                val myFollowList = if (targetPubkey == myPubkeyHex) followList else repository.fetchFollowList(myPubkeyHex)
                val isFollowing = if (targetPubkey == myPubkeyHex) false else myFollowList.contains(targetPubkey)

                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = enrichedPosts,
                        likedPosts = enrichedLikes,
                        followCount = followList.size,
                        followList = followList,
                        badges = badges,
                        isFollowing = isFollowing,
                        isRefreshing = false
                    )
                }

                val refreshNip05 = profile?.nip05
                if (refreshNip05 != null) {
                    viewModelScope.launch {
                        val verified = Nip05Utils.verifyNip05(refreshNip05, targetPubkey)
                        _uiState.update { it.copy(isNip05Verified = verified) }
                    }
                } else {
                    _uiState.update { it.copy(isNip05Verified = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false) }
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

    fun deletePost(eventId: String) {
        viewModelScope.launch {
            try {
                val success = repository.deleteEvent(eventId)
                if (success) {
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

    fun loadFollowProfiles() {
        viewModelScope.launch {
            val pubkeys = _uiState.value.followList
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
