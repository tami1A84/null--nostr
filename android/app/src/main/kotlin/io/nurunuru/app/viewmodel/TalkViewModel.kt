package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.MlsGroup
import io.nurunuru.app.data.models.MlsMessage
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TalkUiState(
    val groups: List<MlsGroup> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeGroupId: String? = null,
    val activeGroup: MlsGroup? = null,
    val messages: List<MlsMessage> = emptyList(),
    val messagesLoading: Boolean = false,
    val sendingMessage: Boolean = false,
    // Legacy (read-only)
    @Suppress("DEPRECATION")
    val legacyConversations: List<DmConversation> = emptyList(),
    val showLegacy: Boolean = false,
    // Group management UI state
    val showGroupInfo: Boolean = false,
    val showCreateGroup: Boolean = false,
    // Following list for member picker (loaded on demand)
    val followingProfiles: List<UserProfile> = emptyList(),
    val followingLoading: Boolean = false
)

class TalkViewModel(
    private val repository: NostrRepository,
    private val nostrClient: NostrClient,
    private val myPubkeyHex: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(TalkUiState(isLoading = true))
    val uiState: StateFlow<TalkUiState> = _uiState.asStateFlow()

    private var messageStreamJob: Job? = null

    init {
        loadGroups()
    }

    /**
     * キャッシュクリア後にUIを即時リセットして再取得する。
     * Rust MLS 状態から再構築されるため、退出済みグループは leftIds フィルタで除外される。
     */
    fun clearStateAfterCacheClear() {
        messageStreamJob?.cancel()
        messageStreamJob = null
        _uiState.update {
            it.copy(
                groups = emptyList(),
                messages = emptyList(),
                activeGroupId = null,
                activeGroup = null,
                showGroupInfo = false
            )
        }
        loadGroups()
    }

    fun loadGroups() {
        viewModelScope.launch {
            // キャッシュファースト: 即時表示
            val cached = repository.getCachedMlsGroups()
            _uiState.update { it.copy(groups = cached, isLoading = true, error = null) }

            // バックグラウンドで最新データを取得してキャッシュを更新
            try {
                val groups = repository.fetchMlsGroups()
                @Suppress("DEPRECATION")
                val legacy = try { repository.fetchDmConversations(myPubkeyHex) } catch (_: Exception) { emptyList() }
                // fetchMlsGroups() は Rust 未接続時にキャッシュを返すため、
                // 空リストは「本当に0グループ」を意味する — そのまま反映する
                _uiState.update {
                    it.copy(
                        groups = groups,
                        legacyConversations = legacy,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "トークの読み込みに失敗しました", isLoading = false) }
            }
        }
    }

    fun openGroup(groupIdHex: String) {
        val group = _uiState.value.groups.find { it.groupIdHex == groupIdHex }
        _uiState.update {
            it.copy(
                activeGroupId = groupIdHex,
                activeGroup = group,
                messagesLoading = true
            )
        }
        viewModelScope.launch {
            // Step 1: Rust ローカル履歴を即時表示（ネットワーク不要）
            val local = repository.getLocalMlsMessages(groupIdHex)
            if (local.isNotEmpty()) {
                _uiState.update { it.copy(messages = local, messagesLoading = true) }
            }
            // Step 2: リレーから差分を取得して更新
            try {
                val messages = repository.fetchMlsMessages(groupIdHex)
                _uiState.update { it.copy(messages = messages, messagesLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(messagesLoading = false, error = "メッセージの読み込みに失敗しました") }
            }
        }
        startMessageStream(groupIdHex)
    }

    fun closeGroup() {
        messageStreamJob?.cancel()
        messageStreamJob = null
        _uiState.update {
            it.copy(
                activeGroupId = null,
                activeGroup = null,
                messages = emptyList(),
                showGroupInfo = false
            )
        }
    }

    fun sendMessage(groupIdHex: String, content: String) {
        if (content.isBlank()) return
        _uiState.update { it.copy(sendingMessage = true) }
        viewModelScope.launch {
            try {
                val success = repository.sendMlsMessage(groupIdHex, content)
                if (success) {
                    val messages = repository.fetchMlsMessages(groupIdHex)
                    _uiState.update { it.copy(messages = messages) }
                } else {
                    _uiState.update { it.copy(error = "送信に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "送信に失敗しました") }
            } finally {
                _uiState.update { it.copy(sendingMessage = false) }
            }
        }
    }

    fun createDmConversation(partnerPubkey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Load groups if not yet fetched so we can check for existing DMs
                val currentGroups = if (_uiState.value.groups.isEmpty()) {
                    val fetched = repository.fetchMlsGroups()
                    _uiState.update { it.copy(groups = fetched) }
                    fetched
                } else {
                    _uiState.value.groups
                }
                // Reuse existing DM group if one already exists with this partner
                val existing = currentGroups.find { g ->
                    g.isDm && g.memberPubkeys.contains(partnerPubkey)
                }
                if (existing != null) {
                    _uiState.update { it.copy(isLoading = false) }
                    openGroup(existing.groupIdHex)
                    return@launch
                }
                // No existing group — create a new one
                val group = repository.createDmGroup(partnerPubkey)
                if (group != null) {
                    _uiState.update { it.copy(groups = currentGroups + group, isLoading = false) }
                    openGroup(group.groupIdHex)
                } else {
                    _uiState.update { it.copy(isLoading = false, error = "相手がNIP-EEに対応していません") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "トークの作成に失敗しました", isLoading = false) }
            }
        }
    }

    fun createGroupChat(name: String, memberPubkeys: List<String>) {
        if (name.isBlank() || memberPubkeys.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, showCreateGroup = false) }
            try {
                val group = repository.createGroupChat(name, memberPubkeys)
                if (group != null) {
                    val groups = _uiState.value.groups + group
                    _uiState.update { it.copy(groups = groups, isLoading = false) }
                    openGroup(group.groupIdHex)
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "グループの作成に失敗しました"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "グループの作成に失敗しました", isLoading = false) }
            }
        }
    }

    fun leaveGroup() {
        val groupIdHex = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            try {
                val success = repository.leaveGroup(groupIdHex)
                if (success) {
                    val groups = _uiState.value.groups.filter { it.groupIdHex != groupIdHex }
                    _uiState.update {
                        it.copy(
                            groups = groups,
                            activeGroupId = null,
                            activeGroup = null,
                            messages = emptyList(),
                            showGroupInfo = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(error = "グループの退出に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "グループの退出に失敗しました") }
            }
        }
    }

    fun addMember(memberPubkey: String) {
        val groupIdHex = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            try {
                val success = repository.addMemberToGroup(groupIdHex, memberPubkey)
                if (success) {
                    val groups = repository.fetchMlsGroups()
                    val activeGroup = groups.find { it.groupIdHex == groupIdHex }
                    _uiState.update { it.copy(groups = groups, activeGroup = activeGroup) }
                } else {
                    _uiState.update { it.copy(error = "メンバーの追加に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "メンバーの追加に失敗しました") }
            }
        }
    }

    fun removeMember(memberPubkey: String) {
        val groupIdHex = _uiState.value.activeGroupId ?: return
        viewModelScope.launch {
            try {
                val success = repository.removeMemberFromGroup(groupIdHex, memberPubkey)
                if (success) {
                    val groups = repository.fetchMlsGroups()
                    val activeGroup = groups.find { it.groupIdHex == groupIdHex }
                    _uiState.update { it.copy(groups = groups, activeGroup = activeGroup) }
                } else {
                    _uiState.update { it.copy(error = "メンバーの削除に失敗しました") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "メンバーの削除に失敗しました") }
            }
        }
    }

    private fun startMessageStream(groupIdHex: String) {
        messageStreamJob?.cancel()
        messageStreamJob = viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (_uiState.value.activeGroupId != groupIdHex) break
                try {
                    val messages = repository.fetchMlsMessages(groupIdHex)
                    // Compare by latest message ID rather than count to catch any list changes
                    if (messages.lastOrNull()?.id != _uiState.value.messages.lastOrNull()?.id) {
                        _uiState.update { it.copy(messages = messages) }
                    }
                } catch (_: Exception) { /* ignore poll errors */ }
            }
        }
    }

    fun ensureKeyPackagePublished() {
        viewModelScope.launch {
            try {
                repository.ensureKeyPackagePublished()
            } catch (_: Exception) { /* non-critical */ }
        }
    }

    fun showGroupInfo() { _uiState.update { it.copy(showGroupInfo = true) } }
    fun hideGroupInfo() { _uiState.update { it.copy(showGroupInfo = false) } }
    fun showCreateGroup() {
        _uiState.update { it.copy(showCreateGroup = true) }
        loadFollowingProfiles()
    }
    fun hideCreateGroup() { _uiState.update { it.copy(showCreateGroup = false) } }

    private fun loadFollowingProfiles() {
        if (_uiState.value.followingLoading || _uiState.value.followingProfiles.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(followingLoading = true) }
            try {
                val pubkeys = repository.fetchFollowList(myPubkeyHex)
                val profiles = if (pubkeys.isNotEmpty()) repository.fetchProfiles(pubkeys.take(200))
                               else emptyMap()
                val profileList = pubkeys.take(200).map { pk ->
                    profiles[pk] ?: UserProfile(pubkey = pk)
                }
                _uiState.update { it.copy(followingProfiles = profileList, followingLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(followingLoading = false, error = "フォローリストの取得に失敗しました") }
            }
        }
    }
    fun toggleLegacy() { _uiState.update { it.copy(showLegacy = !it.showLegacy) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }

    class Factory(
        private val repository: NostrRepository,
        private val nostrClient: NostrClient,
        private val myPubkeyHex: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            TalkViewModel(repository, nostrClient, myPubkeyHex) as T
    }
}
