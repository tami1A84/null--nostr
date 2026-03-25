package io.nurunuru.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlin.math.roundToInt
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.*
import io.nurunuru.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    repository: io.nurunuru.app.data.NostrRepository,
    onLogout: () -> Unit = {},
    onStartDM: (String) -> Unit = {},
    onNoteClick: ((String) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.profile
    val clipboardManager = LocalClipboardManager.current
    val bgPrimary = Color.Black

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing) pullRefreshState.endRefresh()
    }

    LaunchedEffect(Unit) {
        // MainScreen のバックグラウンドプリフェッチで既にロード中なら二重呼び出しを防ぐ
        if (uiState.profile == null && !uiState.isLoading) {
            viewModel.loadMyProfile()
        }
    }

    // 絵文字キャッシュ事前ロード（リアクション長押し時に即表示するため）
    LaunchedEffect(viewModel.myPubkeyHex) {
        if (viewModel.myPubkeyHex.isNotEmpty()) {
            fetchAndCacheEmojis(viewModel.myPubkeyHex, repository)
        }
    }

    val pagerState = rememberPagerState(initialPage = uiState.activeTab) { 2 }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        viewModel.setActiveTab(pagerState.currentPage)
    }

    var showEditProfile by remember { mutableStateOf(false) }
    var showFollowList by remember { mutableStateOf(false) }
    var showPostModal by remember { mutableStateOf(false) }
    var showBookmarkList by remember { mutableStateOf(false) }
    var showQRCode by remember { mutableStateOf(false) }
    var postToDelete by remember { mutableStateOf<String?>(null) }
    var viewingPubkey by remember { mutableStateOf<String?>(null) }

    // コラプシングヘッダー
    val density = LocalDensity.current
    val profileHeightPx = remember { mutableFloatStateOf(0f) }
    val tabsHeightPx = remember { mutableFloatStateOf(0f) }
    val profileOffsetPx = remember { mutableFloatStateOf(0f) }

    val collapsingConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 上スクロール時のみヘッダーを消費しながら隠す
                val delta = available.y
                if (delta < 0f) {
                    val old = profileOffsetPx.floatValue
                    val new = (old + delta).coerceIn(-profileHeightPx.floatValue, 0f)
                    profileOffsetPx.floatValue = new
                    return Offset(0f, new - old)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 下スクロール時、コンテンツがトップに達したらヘッダーを戻す
                val delta = available.y
                if (delta > 0f) {
                    val old = profileOffsetPx.floatValue
                    val new = (old + delta).coerceIn(-profileHeightPx.floatValue, 0f)
                    profileOffsetPx.floatValue = new
                    return Offset(0f, new - old)
                }
                return Offset.Zero
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text("ホーム", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showBookmarkList = true }) {
                            Icon(NuruIcons.Bookmark(false), contentDescription = "ブックマーク", tint = TextSecondary)
                        }
                        TextButton(onClick = onLogout) {
                            Text("ログアウト", color = TextSecondary, fontSize = 14.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bgPrimary,
                        titleContentColor = TextPrimary
                    ),
                    windowInsets = WindowInsets.statusBars
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showPostModal = true },
                    containerColor = LineGreen,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = "投稿")
                }
            },
            containerColor = bgPrimary
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .nestedScroll(pullRefreshState.nestedScrollConnection)
                    .nestedScroll(collapsingConnection)
            ) {
                val contentTopPadding = with(density) {
                    (profileHeightPx.floatValue + tabsHeightPx.floatValue + profileOffsetPx.floatValue).toDp()
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = 1
                ) { page ->
                    val displayPosts = (if (page == 0) uiState.posts else uiState.likedPosts)
                        .distinctBy { it.event.id }
                    if (uiState.isLoading) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = contentTopPadding)
                        ) {
                            item { FriendlyLoading(message = "プロフィールを読み込んでいます") }
                            items(5) {
                                Surface(Modifier.padding(horizontal = 12.dp), color = bgPrimary) {
                                    PostSkeleton()
                                }
                            }
                        }
                    } else if (displayPosts.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize().padding(top = contentTopPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(
                                icon = if (page == 0) Icons.Default.EditNote else Icons.Default.FavoriteBorder,
                                text = if (page == 0) "投稿がありません" else "いいねがありません"
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = contentTopPadding, bottom = 16.dp)
                        ) {
                            items(
                                items = displayPosts,
                                key = { (if (page == 1) "like_" else "") + it.event.id }
                            ) { post ->
                                Surface(Modifier.padding(horizontal = 12.dp), color = bgPrimary) {
                                    if (post.event.kind == 30023) {
                                        LongFormPostItem(
                                            post = post,
                                            onLike = { emoji, tags -> viewModel.likePost(post.event.id, emoji, tags) },
                                            onRepost = { viewModel.repostPost(post.event.id) },
                                            onProfileClick = { if (it != viewModel.myPubkeyHex) viewingPubkey = it },
                                            repository = repository,
                                            onDelete = { postToDelete = post.event.id },
                                            onMute = { viewModel.muteUser(post.event.pubkey) },
                                            onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                            onBirdwatch = { type, content, url -> viewModel.submitBirdwatch(post.event.id, post.event.pubkey, type, content, url) },
                                            isOwnPost = post.event.pubkey == viewModel.myPubkeyHex
                                        )
                                    } else {
                                        PostItem(
                                            post = post,
                                            onLike = { emoji, tags -> viewModel.likePost(post.event.id, emoji, tags) },
                                            onRepost = { viewModel.repostPost(post.event.id) },
                                            onProfileClick = { if (it != viewModel.myPubkeyHex) viewingPubkey = it },
                                            repository = repository,
                                            onDelete = { postToDelete = post.event.id },
                                            onMute = { viewModel.muteUser(post.event.pubkey) },
                                            onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                            onBirdwatch = { type, content, url -> viewModel.submitBirdwatch(post.event.id, post.event.pubkey, type, content, url) },
                                            onNotInterested = null,
                                            onBookmark = { viewModel.addBookmark(post.event.id) },
                                            isOwnPost = post.event.pubkey == viewModel.myPubkeyHex,
                                            isVerified = if (post.event.pubkey == profile?.pubkey) uiState.isNip05Verified else false,
                                            onNoteClick = onNoteClick,
                                            myPubkey = viewModel.myPubkeyHex
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Profile header overlay — scrolls up with content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { profileHeightPx.floatValue = it.size.height.toFloat() }
                        .offset { IntOffset(0, profileOffsetPx.floatValue.roundToInt()) }
                        .background(Color.Black)
                ) {
                    if (profile == null) ProfileSkeleton()
                    else ProfileHeader(
                        profile = profile,
                        isOwnProfile = uiState.isOwnProfile,
                        isFollowing = uiState.isFollowing,
                        isNip05Verified = uiState.isNip05Verified,
                        followCount = uiState.followCount,
                        badges = uiState.badges,
                        onEditClick = { showEditProfile = true },
                        onQRClick = if (uiState.isOwnProfile) ({ showQRCode = true }) else null,
                        onFollowClick = {
                            if (uiState.isFollowing) viewModel.unfollowUser(uiState.viewingPubkey!!)
                            else viewModel.followUser(uiState.viewingPubkey!!)
                        },
                        onFollowListClick = {
                            viewModel.loadFollowProfiles()
                            showFollowList = true
                        },
                        clipboardManager = clipboardManager
                    )
                }

                // Tabs overlay — follows profile bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { tabsHeightPx.floatValue = it.size.height.toFloat() }
                        .offset { IntOffset(0, (profileHeightPx.floatValue + profileOffsetPx.floatValue).roundToInt()) }
                        .background(Color.Black)
                ) {
                    ProfileTabs(
                        activeTab = pagerState.currentPage,
                        onTabSelected = { page -> coroutineScope.launch { pagerState.animateScrollToPage(page) } },
                        postCount = uiState.posts.size,
                        likeCount = uiState.likedPosts.size
                    )
                }

                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, (profileHeightPx.floatValue + tabsHeightPx.floatValue + profileOffsetPx.floatValue).roundToInt()) },
                    containerColor = bgPrimary,
                    contentColor = LineGreen
                )
            }
        }

        if (showBookmarkList) {
            BookmarkListModal(
                viewModel = viewModel,
                repository = repository,
                onDismiss = { showBookmarkList = false },
                onProfileClick = { viewingPubkey = it; showBookmarkList = false }
            )
        }

        if (showQRCode) {
            QRModal(
                pubkeyHex = viewModel.myPubkeyHex,
                onDismiss = { showQRCode = false }
            )
        }

        if (showEditProfile) {
            EditProfileModal(
                profile = profile ?: UserProfile(pubkey = viewModel.myPubkeyHex),
                onDismiss = { showEditProfile = false },
                onSave = { viewModel.updateProfile(it); showEditProfile = false },
                viewModel = viewModel
            )
        }

        if (showFollowList) {
            FollowListModal(
                pubkeys = uiState.followList,
                profiles = uiState.followProfiles,
                onDismiss = { showFollowList = false },
                onUnfollow = { viewModel.unfollowUser(it) },
                onProfileClick = { viewingPubkey = it; showFollowList = false }
            )
        }

        if (viewingPubkey != null) {
            val homeViewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                key = "profile_$viewingPubkey",
                factory = HomeViewModel.Factory(repository, viewModel.myPubkeyHex)
            )
            UserProfileModal(
                pubkey = viewingPubkey!!,
                viewModel = homeViewModel,
                repository = repository,
                onDismiss = { viewingPubkey = null },
                onStartDM = { pk -> viewingPubkey = null; onStartDM(pk) }
            )
        }

        if (postToDelete != null) {
            AlertDialog(
                onDismissRequest = { postToDelete = null },
                title = { Text("削除の確認") },
                text = { Text("この投稿を削除しますか？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            postToDelete?.let { viewModel.deletePost(it) }
                            postToDelete = null
                        }
                    ) {
                        Text("削除", color = Color.Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { postToDelete = null }) {
                        Text("キャンセル", color = TextSecondary)
                    }
                },
                containerColor = BgSecondary,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary
            )
        }

        if (showPostModal) {
            PostModal(
                myPubkey = viewModel.myPubkeyHex,
                pictureUrl = profile?.picture,
                displayName = profile?.displayedName ?: "",
                repository = repository,
                onDismiss = { showPostModal = false },
                onSuccess = {
                    showPostModal = false
                    viewModel.refresh()
                }
            )
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(BgSecondary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(text, color = TextSecondary, fontSize = 14.sp)
    }
}
