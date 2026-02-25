package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.FeedType
import io.nurunuru.app.viewmodel.TimelineViewModel
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    myPictureUrl: String?,
    myDisplayName: String
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var showPostModal by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    // Pager state for Recommended (0) and Following (1)
    val pagerState = rememberPagerState(
        initialPage = if (uiState.feedType == FeedType.GLOBAL) 0 else 1
    ) { 2 }

    // Sync Pager -> ViewModel
    LaunchedEffect(pagerState.currentPage) {
        val targetFeed = if (pagerState.currentPage == 0) FeedType.GLOBAL else FeedType.FOLLOWING
        if (uiState.feedType != targetFeed) {
            viewModel.switchFeed(targetFeed)
        }
    }

    // Sync ViewModel -> Pager
    LaunchedEffect(uiState.feedType) {
        val targetPage = if (uiState.feedType == FeedType.GLOBAL) 0 else 1
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .blur(if (android.os.Build.VERSION.SDK_INT >= 31) 20.dp else 0.dp)
            ) {
                TimelineHeader(
                    feedType = uiState.feedType,
                    onFeedTypeChange = { viewModel.switchFeed(it) },
                    showSearch = showSearch,
                    onShowSearchChange = { showSearch = it },
                    searchText = searchText,
                    onSearchTextChange = {
                        searchText = it
                        if (it.length >= 2) viewModel.search(it)
                        else if (it.isEmpty()) viewModel.clearSearch()
                    },
                    onNotificationsClick = { /* TODO: Notifications */ }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPostModal = true },
                containerColor = LineGreen,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "投稿する")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalAlignment = Alignment.Top
        ) { page ->
            // Note: Each page has its own refresh state and list state
            // But we simplify by using common logic for now
            TimelineContent(
                viewModel = viewModel,
                feedType = if (page == 0) FeedType.GLOBAL else FeedType.FOLLOWING,
                searchText = searchText,
                isSearching = uiState.isSearching
            )
        }
    }

    // Post composition modal
    if (showPostModal) {
        PostModal(
            pictureUrl = myPictureUrl,
            displayName = myDisplayName,
            onDismiss = { showPostModal = false },
            onPublish = { content, cw ->
                viewModel.publishNote(content, cw)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineContent(
    viewModel: TimelineViewModel,
    feedType: FeedType,
    searchText: String,
    isSearching: Boolean
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val listState = rememberLazyListState()

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) {
            viewModel.refresh()
        }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing) pullRefreshState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        // Only display posts relevant to the current feed type if searching is not active
        val displayPosts = if (searchText.isNotBlank()) {
            uiState.searchResults
        } else {
            // Note: TimelineViewModel currently loads only one list at a time.
            // For a better Pager experience, we should ideally have separate lists in UI state.
            // But if feedType matches uiState.feedType, we show uiState.posts.
            if (feedType == uiState.feedType) uiState.posts else emptyList()
        }

        when {
            uiState.isLoading && displayPosts.isEmpty() && feedType == uiState.feedType -> {
                TimelineLoadingState()
            }
            uiState.error != null && displayPosts.isEmpty() && feedType == uiState.feedType -> {
                TimelineErrorState(onRetry = { viewModel.loadTimeline() })
            }
            displayPosts.isEmpty() && searchText.isNotBlank() && !isSearching -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("「$searchText」の検索結果がありません", color = nuruColors.textTertiary)
                }
            }
            displayPosts.isEmpty() && !uiState.isLoading && feedType == uiState.feedType -> {
                TimelineEmptyState(
                    feedType = feedType,
                    isFollowListEmpty = uiState.followList.isEmpty()
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(displayPosts, key = { it.event.id }) { post ->
                        Surface(
                            color = MaterialTheme.colorScheme.background
                        ) {
                            PostItem(
                                post = post,
                                onLike = { viewModel.likePost(post.event.id) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = { /* TODO: navigate to profile */ },
                                birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList()
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = LineGreen
        )
    }
}
