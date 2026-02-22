package io.nurunuru.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "NuruNuru-Home"

data class HomeUiState(
    val profile: UserProfile? = null,
    val posts: List<ScoredPost> = emptyList(),
    val followCount: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val viewingPubkey: String? = null // null = own profile
)

class HomeViewModel(
    private val repository: NostrRepository,
    private val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadMyProfile()
    }

    fun loadMyProfile() {
        loadProfile(myPubkeyHex)
    }

    fun loadProfile(pubkeyHex: String) {
        viewModelScope.launch {
            Log.d(TAG, "Loading profile for $pubkeyHex")
            _uiState.update { it.copy(isLoading = true, error = null, viewingPubkey = pubkeyHex.takeIf { it != myPubkeyHex }) }
            try {
                val profile = repository.fetchProfile(pubkeyHex)
                val posts = repository.fetchUserNotes(pubkeyHex, 30)
                val followList = repository.fetchFollowList(pubkeyHex)
                Log.d(TAG, "Profile loaded: ${profile?.displayName}, posts: ${posts.size}")
                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = posts,
                        followCount = followList.size,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading profile", e)
                _uiState.update { it.copy(error = "プロフィールの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun refresh() {
        val targetPubkey = _uiState.value.viewingPubkey ?: myPubkeyHex
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val profile = repository.fetchProfile(targetPubkey)
                val posts = repository.fetchUserNotes(targetPubkey, 30)
                val followList = repository.fetchFollowList(targetPubkey)
                _uiState.update {
                    it.copy(
                        profile = profile,
                        posts = posts,
                        followCount = followList.size,
                        isRefreshing = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false) }
            }
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
