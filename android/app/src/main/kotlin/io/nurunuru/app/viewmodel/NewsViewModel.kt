package io.nurunuru.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.nurunuru.app.data.Nip05Utils
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.fetchNewsArticles
import io.nurunuru.app.data.models.NewsArticle
import io.nurunuru.app.data.models.NewsCategory
import io.nurunuru.app.data.prefs.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewsUiState(
    val articles: List<NewsArticle> = emptyList(),
    val selectedCategory: NewsCategory = NewsCategory.TOP,
    val query: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val sourcePubkeys: List<String> = emptyList(),
    val showSourceSettings: Boolean = false
) {
    val filteredArticles: List<NewsArticle>
        get() {
            val byCategory = selectedCategory.key?.let { key -> articles.filter { it.categories.contains(key) } } ?: articles
            val q = query.trim().lowercase()
            return if (q.isEmpty()) byCategory else byCategory.filter {
                it.title.lowercase().contains(q) || it.summary.lowercase().contains(q) || it.content.lowercase().contains(q) || it.sourceName.lowercase().contains(q)
            }
        }
}

class NewsViewModel(val repository: NostrRepository, private val prefs: AppPreferences) : ViewModel() {
    private val _uiState = MutableStateFlow(NewsUiState(sourcePubkeys = prefs.newsSources))
    val uiState: StateFlow<NewsUiState> = _uiState.asStateFlow()
    init { refresh() }
    fun refresh() { viewModelScope.launch { _uiState.update { it.copy(isLoading = true, error = null, sourcePubkeys = prefs.newsSources) }; try { val articles = repository.fetchNewsArticles(prefs.newsSources); _uiState.update { it.copy(articles = articles, isLoading = false) } } catch (e: Exception) { _uiState.update { it.copy(isLoading = false, error = e.message ?: "ニュースを取得できませんでした") } } } }
    fun selectCategory(category: NewsCategory) { _uiState.update { it.copy(selectedCategory = category) } }
    fun updateQuery(query: String) { _uiState.update { it.copy(query = query) } }
    fun setSourceSettingsVisible(visible: Boolean) { _uiState.update { it.copy(showSourceSettings = visible) } }
    fun addSource(input: String) { val raw = input.trim(); if (raw.isEmpty()) return; viewModelScope.launch { val parsed = NostrKeyUtils.parsePublicKey(raw) ?: Nip05Utils.resolveNip05(raw); if (parsed == null) { _uiState.update { it.copy(error = "ニュースソースを解決できませんでした") }; return@launch }; prefs.newsSources = (prefs.newsSources + parsed).distinct(); refresh() } }
    fun removeSource(pubkey: String) { prefs.newsSources = prefs.newsSources.filterNot { it == pubkey }; refresh() }
    class Factory(private val repository: NostrRepository, private val prefs: AppPreferences) : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = NewsViewModel(repository, prefs) as T }
}
