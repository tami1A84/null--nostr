package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.theme.*
import io.nurunuru.app.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    repository: io.nurunuru.app.data.NostrRepository,
    onLogout: () -> Unit = {}
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
        if (uiState.profile == null) {
            viewModel.loadMyProfile()
        }
    }

    var showEditProfile by remember { mutableStateOf(false) }
    var showFollowList by remember { mutableStateOf(false) }
    var showPostModal by remember { mutableStateOf(false) }
    var postToDelete by remember { mutableStateOf<String?>(null) }
    var viewingPubkey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("ホーム", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                actions = {
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
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp) // Optimized
            ) {
                item {
                    if (profile == null) {
                        ProfileSkeleton()
                    } else {
                        ProfileHeader(
                            profile = profile,
                            isOwnProfile = uiState.isOwnProfile,
                        isFollowing = uiState.isFollowing,
                        isNip05Verified = uiState.isNip05Verified,
                        followCount = uiState.followCount,
                        badges = uiState.badges,
                        onEditClick = { showEditProfile = true },
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
                }

                item {
                    ProfileTabs(
                        activeTab = uiState.activeTab,
                        onTabSelected = { viewModel.setActiveTab(it) },
                        postCount = uiState.posts.size,
                        likeCount = uiState.likedPosts.size
                    )
                }

                if (uiState.isLoading) {
                    item {
                        FriendlyLoading(message = "プロフィールを読み込んでいます")
                    }
                    items(5) {
                        Surface(Modifier.padding(horizontal = 12.dp), color = bgPrimary) {
                            PostSkeleton()
                        }
                    }
                } else {
                    val displayPosts = if (uiState.activeTab == 0) uiState.posts else uiState.likedPosts
                    if (displayPosts.isEmpty()) {
                        item {
                            EmptyState(
                                icon = if (uiState.activeTab == 0) Icons.Default.EditNote else Icons.Default.FavoriteBorder,
                                text = if (uiState.activeTab == 0) "投稿がありません" else "いいねがありません"
                            )
                        }
                    } else {
                        items(displayPosts, key = { (if (uiState.activeTab == 1) "like_" else "") + it.event.id }) { post ->
                            val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
                            LaunchedEffect(post.event.id) {
                                alpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(300))
                            }
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
                                        modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
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
                                        isOwnPost = post.event.pubkey == viewModel.myPubkeyHex,
                                        isVerified = if (post.event.pubkey == profile?.pubkey) uiState.isNip05Verified else false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PullToRefreshContainer(
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = bgPrimary,
                contentColor = LineGreen
            )
        }
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
            onStartDM = { /* TODO */ }
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
