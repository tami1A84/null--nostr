package io.nurunuru.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    repository: io.nurunuru.app.data.NostrRepository,
    myPubkey: String,
    myPictureUrl: String?,
    myDisplayName: String
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current

    var showPostModal by remember { mutableStateOf(false) }
    var showSearchModal by remember { mutableStateOf(false) }
    var showNotificationsModal by remember { mutableStateOf(false) }
    var viewingPubkey by remember { mutableStateOf<String?>(null) }

    // Pager state for Recommended (0) and Following (1)
    // Default to Following (index 1)
    val pagerState = rememberPagerState(
        initialPage = 1
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TimelineHeader(
                feedType = uiState.feedType,
                onFeedTypeChange = { viewModel.switchFeed(it) },
                showRecommendedDot = uiState.hasNewRecommendations,
                onSearchClick = { showSearchModal = true },
                onNotificationsClick = { showNotificationsModal = true }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPostModal = true },
                containerColor = LineGreen,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "投稿する")
            }
        },
        containerColor = nuruColors.bgPrimary
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
                feedType = if (page == 0) FeedType.GLOBAL else FeedType.FOLLOWING
            )
        }
    }

    // Post composition modal
    if (showPostModal) {
        PostModal(
            myPubkey = myPubkey,
            pictureUrl = myPictureUrl,
            displayName = myDisplayName,
            repository = repository,
            onDismiss = { showPostModal = false },
            onSuccess = {
                showPostModal = false
                viewModel.refresh()
            }
        )
    }

    if (showSearchModal) {
        SearchModal(
            viewModel = viewModel,
            onClose = { showSearchModal = false; viewModel.clearSearch() },
            onProfileClick = { /* TODO */ }
        )
    }

    if (showNotificationsModal) {
        NotificationModal(
            repository = repository,
            myPubkey = myPubkey,
            onClose = { showNotificationsModal = false },
            onProfileClick = { /* TODO */ }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineContent(
    viewModel: TimelineViewModel,
    feedType: FeedType
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
    val isRefreshing = if (feedType == FeedType.GLOBAL) uiState.isGlobalRefreshing else uiState.isFollowingRefreshing
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullRefreshState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
            .background(nuruColors.bgPrimary)
    ) {
        val displayPosts = if (feedType == FeedType.GLOBAL) uiState.globalPosts else uiState.followingPosts

        val isLoading = if (feedType == FeedType.GLOBAL) uiState.isGlobalLoading else uiState.isFollowingLoading
        val error = if (feedType == FeedType.GLOBAL) uiState.globalError else uiState.followingError

        when {
            isLoading && displayPosts.isEmpty() -> {
                TimelineLoadingSkeleton()
            }
            error != null && displayPosts.isEmpty() -> {
                TimelineErrorState(onRetry = {
                    if (feedType == FeedType.GLOBAL) viewModel.loadGlobalTimeline()
                    else viewModel.loadFollowingTimeline()
                })
            }
            displayPosts.isEmpty() && !isLoading -> {
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
                        val alpha = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            alpha.animateTo(1f, animationSpec = tween(300))
                        }

                        if (post.event.kind == 30023) {
                            LongFormPostItem(
                                post = post,
                                onLike = { viewModel.likePost(post.event.id) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = { viewingPubkey = it },
                                repository = repository,
                                onMute = { viewModel.muteUser(post.event.pubkey) },
                                onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                onBirdwatch = { type, content, url -> viewModel.submitBirdwatch(post.event.id, post.event.pubkey, type, content, url) },
                                onNotInterested = { viewModel.setNotInterested(post.event.id) },
                                birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList()
                            )
                        } else {
                            PostItem(
                                modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
                                post = post,
                                onLike = { viewModel.likePost(post.event.id) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = { viewingPubkey = it },
                                repository = repository,
                                onMute = { viewModel.muteUser(post.event.pubkey) },
                                onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                onBirdwatch = { type, content, url -> viewModel.submitBirdwatch(post.event.id, post.event.pubkey, type, content, url) },
                                onNotInterested = { viewModel.setNotInterested(post.event.id) },
                                birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList()
                            )
                        }
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
