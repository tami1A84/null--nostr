package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.NewsArticle
import io.nurunuru.app.data.models.NewsCategory
import io.nurunuru.app.ui.components.MarkdownContent
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.NewsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun NewsScreen(viewModel: NewsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalNuruColors.current
    var isPullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) isPullRefreshing = false
    }
    val pullRefreshState = rememberPullRefreshState(
        refreshing = state.isLoading && isPullRefreshing,
        onRefresh = {
            isPullRefreshing = true
            viewModel.refresh()
        }
    )
    var selectedArticle by remember { mutableStateOf<NewsArticle?>(null) }
    val categories = remember { NewsCategory.entries.toList() }
    val pagerState = rememberPagerState(
        initialPage = categories.indexOf(state.selectedCategory).coerceAtLeast(0)
    ) { categories.size }
    val categoryListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        categories.getOrNull(pagerState.currentPage)?.let { category ->
            if (state.selectedCategory != category) viewModel.selectCategory(category)
        }
    }

    LaunchedEffect(state.selectedCategory) {
        val index = categories.indexOf(state.selectedCategory)
        if (index >= 0) {
            if (pagerState.currentPage != index) pagerState.animateScrollToPage(index)
            categoryListState.animateScrollToItem(index)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bgPrimary).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ニュース", color = colors.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.setSourceSettingsVisible(true) }) {
                Icon(Icons.Default.Menu, contentDescription = "ニュースソース設定", tint = colors.textPrimary)
            }
        }

        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = colors.bgSecondary, shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.height(44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = state.query,
                        onValueChange = viewModel::updateQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(colors.lineGreen),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.query.isEmpty()) Text("検索", color = colors.textTertiary, fontSize = 15.sp, lineHeight = 20.sp)
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            state = categoryListState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            items(categories) { category ->
                val selected = state.selectedCategory == category
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        val index = categories.indexOf(category)
                        if (index >= 0) {
                            viewModel.selectCategory(category)
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        }
                    }
                ) {
                    Text(category.label, color = colors.textPrimary, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.height(3.dp).width(if (selected) 38.dp else 0.dp).background(colors.textPrimary, RoundedCornerShape(2.dp)))
                }
            }
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)

        Box(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 1,
                verticalAlignment = Alignment.Top
            ) { page ->
                val pageCategory = categories[page]
                val pageArticles = remember(state.articles, state.query, pageCategory) {
                    filterNewsArticles(state.articles, pageCategory, state.query)
                }
                when {
                    state.isLoading && state.articles.isEmpty() && !isPullRefreshing -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = colors.lineGreen)
                                }
                            }
                        }
                    }
                    pageArticles.isEmpty() -> {
                        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                            item {
                                Box(Modifier.fillParentMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (state.sourcePubkeys.isEmpty()) "NIP-23 の最新記事が見つかりません" else "設定したニュースソースの記事が見つかりません",
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.fillMaxSize()) {
                            items(pageArticles, key = { it.id }) { article ->
                                NewsArticleCard(article) { selectedArticle = article }
                            }
                        }
                    }
                }
            }
            PullRefreshIndicator(
                refreshing = state.isLoading && isPullRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = colors.bgSecondary,
                contentColor = colors.lineGreen
            )
        }
    }

    if (state.showSourceSettings) NewsSourceSettingsDialog(state.sourcePubkeys, viewModel::addSource, viewModel::removeSource) { viewModel.setSourceSettingsVisible(false) }
    selectedArticle?.let { NewsArticleReader(it, viewModel.repository) { selectedArticle = null } }
}

private fun filterNewsArticles(articles: List<NewsArticle>, category: NewsCategory, query: String): List<NewsArticle> {
    val byCategory = category.key?.let { key -> articles.filter { it.categories.contains(key) } } ?: articles
    val q = query.trim().lowercase()
    return if (q.isEmpty()) byCategory else byCategory.filter {
        it.title.lowercase().contains(q) ||
            it.summary.lowercase().contains(q) ||
            it.content.lowercase().contains(q) ||
            it.sourceName.lowercase().contains(q)
    }
}

@Composable
private fun NewsArticleCard(article: NewsArticle, onClick: () -> Unit) {
    val colors = LocalNuruColors.current
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick), color = colors.bgSecondary, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (article.imageUrl != null) {
                AsyncImage(model = article.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
            } else {
                Box(Modifier.fillMaxWidth().height(120.dp).background(colors.bgTertiary), contentAlignment = Alignment.Center) { Text("NEWS", color = colors.textTertiary, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text(article.title, color = colors.textPrimary, fontSize = 20.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Text(article.sourceName + " ・ " + relativeTime(article.publishedAt), color = colors.textTertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NewsArticleReader(article: NewsArticle, repository: io.nurunuru.app.data.NostrRepository, onDismiss: () -> Unit) {
    val colors = LocalNuruColors.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.bgPrimary) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = colors.textPrimary) }
                    Text("ニュース", color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                LazyColumn(contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 128.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
                    item { Text(article.title, color = colors.textPrimary, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold) }
                    item { Text(article.sourceName + " ・ " + relativeTime(article.publishedAt), color = colors.textTertiary, fontSize = 13.sp) }
                    if (article.imageUrl != null) item {
                        AsyncImage(model = article.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)))
                    }
                    item { MarkdownContent(content = article.content, repository = repository, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun NewsSourceSettingsDialog(sources: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalNuruColors.current
    var input by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.bgPrimary) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ニュースソース設定", color = colors.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = colors.textPrimary) }
                }
                Text("npub / hex / NIP-05 を追加できます。未設定時はリレー上の最新記事を表示します。", color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("npub1... / user@example.com") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onAdd(input); input = "" }) { Icon(Icons.Default.Add, null, tint = colors.lineGreen) }
                }
                Spacer(Modifier.height(18.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sources, key = { it }) { source ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(NostrKeyUtils.shortenPubkey(source), color = colors.textPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemove(source) }) { Icon(Icons.Default.Delete, null, tint = Color.Red) }
                        }
                        HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

private fun relativeTime(epochSeconds: Long): String {
    val diff = (System.currentTimeMillis() / 1000 - epochSeconds).coerceAtLeast(0)
    return when {
        diff < 60 -> "たった今"
        diff < 3600 -> (diff / 60).toString() + "分前"
        diff < 86400 -> (diff / 3600).toString() + "時間前"
        else -> (diff / 86400).toString() + "日前"
    }
}
