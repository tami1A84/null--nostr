package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomeUiState(
    val profile: UserProfile? = null,
    val posts: List<ScoredPost> = emptyList(),
    val followCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val viewingPubkey: String? = null, // null = own profile
    val isFollowing: Boolean = false   // only meaningful when viewingPubkey != null
)

class HomeViewModel(
    private val repository: NostrRepository,
    val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _notification = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val notification: SharedFlow<String> = _notification.asSharedFlow()

    private val pendingLikes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingReposts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        loadMyProfile()
    }

    fun loadMyProfile() {
        loadProfile(myPubkeyHex)
    }

    fun loadProfile(pubkeyHex: String) {
        val isViewingOther = pubkeyHex != myPubkeyHex
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, viewingPubkey = if (isViewingOther) pubkeyHex else null) }
            try {
                val profile = repository.fetchProfile(pubkeyHex)
                val posts = repository.fetchUserNotes(pubkeyHex, 30)
                val followList = repository.fetchFollowList(pubkeyHex)
                val isFollowing = if (isViewingOther) repository.isFollowing(myPubkeyHex, pubkeyHex) else false
                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = posts,
                        followCount = followList.size,
                        isLoading = false,
                        isFollowing = isFollowing
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "プロフィールの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun resetToMyProfile() {
        loadProfile(myPubkeyHex)
    }

    fun followUser(targetPubkeyHex: String) {
        viewModelScope.launch {
            try {
                val success = repository.followUser(myPubkeyHex, targetPubkeyHex)
                if (success) {
                    _uiState.update { it.copy(isFollowing = true) }
                    _notification.emit("フォローしました")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notification.emit("フォローに失敗しました") }
        }
    }

    fun unfollowUser(targetPubkeyHex: String) {
        viewModelScope.launch {
            try {
                val success = repository.unfollowUser(myPubkeyHex, targetPubkeyHex)
                if (success) {
                    _uiState.update { it.copy(isFollowing = false) }
                    _notification.emit("フォロー解除しました")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _notification.emit("フォロー解除に失敗しました") }
        }
    }

    fun refresh() {
        val targetPubkey = _uiState.value.viewingPubkey ?: myPubkeyHex
        val isViewingOther = targetPubkey != myPubkeyHex
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val profile = repository.fetchProfile(targetPubkey)
                val posts = repository.fetchUserNotes(targetPubkey, 30)
                val followList = repository.fetchFollowList(targetPubkey)
                val isFollowing = if (isViewingOther) repository.isFollowing(myPubkeyHex, targetPubkey) else false
                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = posts,
                        followCount = followList.size,
                        isRefreshing = false,
                        isFollowing = isFollowing
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
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
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { pendingLikes.remove(eventId) }
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
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            finally { pendingReposts.remove(eventId) }
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
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
