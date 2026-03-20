package io.nurunuru.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import io.nurunuru.app.data.prefs.AppPreferences
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
    prefs: AppPreferences,
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

    val pagerState = rememberPagerState(
        initialPage = 1
    ) { 2 }

    LaunchedEffect(pagerState.currentPage) {
        val targetFeed = if (pagerState.currentPage == 0) FeedType.GLOBAL else FeedType.FOLLOWING
        if (uiState.feedType != targetFeed) {
            viewModel.switchFeed(targetFeed)
        }
    }

    LaunchedEffect(uiState.feedType) {
        val targetPage = if (uiState.feedType == FeedType.GLOBAL) 0 else 1
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TimelineHeader(
                    feedType = uiState.feedType,
                    onFeedTypeChange = { viewModel.switchFeed(it) },
                    showRecommendedDot = uiState.hasNewRecommendations,
                    showFollowingDot = uiState.hasNewFollowing,
                    onSearchClick = { showSearchModal = true },
                    onNotificationsClick = { showNotificationsModal = true },
                    savedRelayUrls = uiState.savedRelayUrls,
                    selectedRelayUrl = uiState.selectedRelayUrl,
                    onSelectRelay = { viewModel.selectRelayFeed(it) }
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
                beyondBoundsPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalAlignment = Alignment.Top
            ) { page ->
                TimelineContent(
                    viewModel = viewModel,
                    repository = repository,
                    feedType = if (page == 0) FeedType.GLOBAL else FeedType.FOLLOWING,
                    onProfileClick = { viewingPubkey = it },
                    onHashtagClick = { tag ->
                        showSearchModal = true
                        viewModel.search(tag)
                    },
                    myPubkey = myPubkey
                )
            }
        }

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
    }

    if (viewingPubkey != null) {
        val homeViewModel: io.nurunuru.app.viewmodel.HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            key = "profile_$viewingPubkey",
            factory = io.nurunuru.app.viewmodel.HomeViewModel.Factory(repository, myPubkey)
        )
        UserProfileModal(
            pubkey = viewingPubkey!!,
            viewModel = homeViewModel,
            repository = repository,
            onDismiss = { viewingPubkey = null },
            onStartDM = { /* TODO */ }
        )
    }

    if (showSearchModal) {
        SearchModal(
            viewModel = viewModel,
            repository = repository,
            onClose = { showSearchModal = false; viewModel.clearSearch() },
            onProfileClick = { viewingPubkey = it },
            myPubkey = myPubkey
        )
    }

    if (showNotificationsModal) {
        NotificationModal(
            repository = repository,
            prefs = prefs,
            myPubkey = myPubkey,
            onClose = { showNotificationsModal = false },
            onProfileClick = { viewingPubkey = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineContent(
    viewModel: TimelineViewModel,
    repository: io.nurunuru.app.data.NostrRepository,
    feedType: FeedType,
    onProfileClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    myPubkey: String
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) {
            val selectedRelay = uiState.selectedRelayUrl
            if (feedType == FeedType.GLOBAL && selectedRelay != null) {
                viewModel.selectRelayFeed(selectedRelay)
            } else {
                viewModel.refresh()
            }
        }
    }
    val isRefreshing = if (feedType == FeedType.GLOBAL) uiState.isGlobalRefreshing else uiState.isFollowingRefreshing
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullRefreshState.endRefresh()
    }

    // 絵文字キャッシュ事前ロード（リアクション長押し時に即表示するため）
    LaunchedEffect(myPubkey) {
        if (myPubkey.isNotEmpty()) {
            fetchAndCacheEmojis(myPubkey, repository)
        }
    }

    val isRelaySelected = feedType == FeedType.GLOBAL && uiState.selectedRelayUrl != null

    // distinctBy は ViewModel 側で保証済みだが、非同期 state 更新の
    // フレーム境界で重複が紛れ込んだ場合の最終防衛として残す。
    val displayPosts = when {
        isRelaySelected -> uiState.relayPosts
        feedType == FeedType.GLOBAL -> uiState.globalPosts
        else -> uiState.followingPosts
    }.distinctBy { it.event.id }
    val pendingPosts = when {
        isRelaySelected -> uiState.pendingRelayPosts
        feedType == FeedType.GLOBAL -> uiState.pendingGlobalPosts
        else -> uiState.pendingFollowingPosts
    }
    val isLoading = when {
        isRelaySelected -> uiState.isRelayFeedLoading
        feedType == FeedType.GLOBAL -> uiState.isGlobalLoading
        else -> uiState.isFollowingLoading
    }
    val error = if (!isRelaySelected && feedType == FeedType.GLOBAL) uiState.globalError
                else if (!isRelaySelected) uiState.followingError
                else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
            .background(nuruColors.bgPrimary)
    ) {
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
                        val notInterestedCallback = if (feedType == FeedType.GLOBAL && !isRelaySelected) {
                            { viewModel.setNotInterested(post.event.id) }
                        } else null

                        if (post.event.kind == 30023) {
                            LongFormPostItem(
                                post = post,
                                onLike = { emoji, tags -> viewModel.likePost(post.event.id, emoji, tags) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = onProfileClick,
                                repository = repository,
                                onDelete = if (post.event.pubkey == myPubkey) { { viewModel.deletePost(post.event.id) } } else null,
                                onMute = { viewModel.muteUser(post.event.pubkey) },
                                onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                onBirdwatch = { type, content, url -> viewModel.submitBirdwatch(post.event.id, post.event.pubkey, type, content, url) },
                                onNotInterested = notInterestedCallback,
                                birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList(),
                                isOwnPost = post.event.pubkey == myPubkey,
                                onHashtagClick = onHashtagClick
                            )
                        } else {
                            PostItem(
                                post = post,
                                onLike = { emoji, tags -> viewModel.likePost(post.event.id, emoji, tags) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = onProfileClick,
                                repository = repository,
                                onDelete = if (post.event.pubkey == myPubkey) { { viewModel.deletePost(post.event.id) } } else null,
                                onMute = { viewModel.muteUser(post.event.pubkey) },
                                onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                onBirdwatch = { type, content, url -> viewModel.submitBirdwatch(post.event.id, post.event.pubkey, type, content, url) },
                                onNotInterested = notInterestedCallback,
                                birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList(),
                                isOwnPost = post.event.pubkey == myPubkey,
                                onHashtagClick = onHashtagClick,
                                myPubkey = myPubkey
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

        // 新着投稿ピル通知
        AnimatedVisibility(
            visible = pendingPosts.isNotEmpty(),
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        ) {
            NewPostsPill(
                pendingPosts = pendingPosts,
                onClick = {
                    viewModel.flushPendingPosts(feedType)
                    coroutineScope.launch { listState.animateScrollToItem(0) }
                }
            )
        }
    }
}
