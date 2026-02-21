package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: UserProfile? = null,
    val posts: List<ScoredPost> = emptyList(),
    val followCount: Int = 0,
    val isLoading: Boolean = true,
    val isFollowing: Boolean = false,
    val isFollowLoading: Boolean = false,
    val error: String? = null,
    val zapInvoice: String? = null,
    val zapLoading: Boolean = false
)

class ProfileViewModel(
    private val repository: NostrRepository,
    private val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    fun loadProfile(pubkeyHex: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val profile = repository.fetchProfile(pubkeyHex)
                val posts = repository.fetchUserNotes(pubkeyHex, 30)
                val followList = repository.fetchFollowList(pubkeyHex)
                val isFollowing = if (pubkeyHex != myPubkeyHex) {
                    repository.isFollowing(pubkeyHex, myPubkeyHex)
                } else false
                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = posts,
                        followCount = followList.size,
                        isFollowing = isFollowing,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "プロフィールの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun toggleFollow(targetPubkey: String) {
        val currentlyFollowing = _uiState.value.isFollowing
        viewModelScope.launch {
            _uiState.update { it.copy(isFollowLoading = true) }
            try {
                val success = if (currentlyFollowing) {
                    repository.unfollowUser(targetPubkey, myPubkeyHex)
                } else {
                    repository.followUser(targetPubkey, myPubkeyHex)
                }
                if (success) {
                    _uiState.update {
                        it.copy(isFollowing = !currentlyFollowing, isFollowLoading = false)
                    }
                } else {
                    _uiState.update { it.copy(isFollowLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isFollowLoading = false) }
            }
        }
    }

    fun fetchZapInvoice(lud16: String, amountSats: Long, comment: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(zapLoading = true, zapInvoice = null) }
            val invoice = repository.fetchLightningInvoice(lud16, amountSats, comment)
            _uiState.update { it.copy(zapInvoice = invoice, zapLoading = false) }
        }
    }

    fun clearZapInvoice() {
        _uiState.update { it.copy(zapInvoice = null) }
    }

    /** Direct suspend call for ZapModal – returns invoice string or null. */
    suspend fun fetchZapInvoiceSync(lud16: String, amountSats: Long, comment: String): String? =
        repository.fetchLightningInvoice(lud16, amountSats, comment)

    class Factory(
        private val repository: NostrRepository,
        private val myPubkeyHex: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileViewModel(repository, myPubkeyHex) as T
    }
}
