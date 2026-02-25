package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.*
import io.nurunuru.app.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onLogout: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.profile
    val clipboardManager = LocalClipboardManager.current
    val bgPrimary = Color(0xFF0A0A0A)

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing) pullRefreshState.endRefresh()
    }

    var showEditProfile by remember { mutableStateOf(false) }
    var showFollowList by remember { mutableStateOf(false) }
    var showPostModal by remember { mutableStateOf(false) }
    var postToDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
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
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // Unified Profile Card (Banner + Overlapping Surface)
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp)
                                .background(LineGreen)
                        ) {
                            if (profile?.banner != null && profile.banner.isNotBlank()) {
                                AsyncImage(
                                    model = profile.banner,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // The Overlapping Surface (Combined into one card)
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .padding(top = 64.dp), // Height(112) - Overlap(48) = 64
                            shape = RoundedCornerShape(16.dp),
                            color = bgPrimary,
                            shadowElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 0.dp)
                            ) {
                                // Avatar and Name section
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Avatar with border - Overlapping the banner
                                    Box(
                                        modifier = Modifier
                                            .offset(y = (-40).dp)
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .background(bgPrimary)
                                            .padding(4.dp)
                                    ) {
                                        UserAvatar(
                                            pictureUrl = profile?.picture,
                                            displayName = profile?.displayedName ?: "",
                                            size = 72.dp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Name, NIP-05, Pubkey
                                    Column(
                                        modifier = Modifier.weight(1f).padding(top = 8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = profile?.displayedName ?: "Anonymous",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = TextPrimary,
                                                maxLines = 1
                                            )
                                            if (uiState.isOwnProfile) {
                                                Icon(
                                                    NuruIcons.Edit,
                                                    contentDescription = "編集",
                                                    tint = TextTertiary,
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clickable { showEditProfile = true }
                                                )
                                            }
                                        }

                                        // NIP-05 verified badge
                                        if (profile?.nip05 != null && uiState.isNip05Verified) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.padding(top = 2.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = LineGreen,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = formatNip05(profile.nip05),
                                                    fontSize = 13.sp,
                                                    color = LineGreen
                                                )
                                            }
                                        }

                                        // Pubkey with copy button
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier
                                                .padding(top = 4.dp)
                                                .clickable {
                                                    profile?.pubkey?.let {
                                                        clipboardManager.setText(AnnotatedString(NostrKeyUtils.encodeNpub(it) ?: it))
                                                    }
                                                }
                                        ) {
                                            Text(
                                                text = NostrKeyUtils.shortenPubkey(profile?.pubkey ?: "", 12),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextTertiary,
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                            )
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "コピー",
                                                tint = TextTertiary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }

                                    if (!uiState.isOwnProfile) {
                                        Button(
                                            onClick = {
                                                if (uiState.isFollowing) viewModel.unfollowUser(uiState.viewingPubkey!!)
                                                else viewModel.followUser(uiState.viewingPubkey!!)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (uiState.isFollowing) Color.Transparent else LineGreen,
                                                contentColor = if (uiState.isFollowing) TextPrimary else Color.White
                                            ),
                                            border = if (uiState.isFollowing) androidx.compose.foundation.BorderStroke(1.dp, BorderColor) else null,
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier.height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp)
                                        ) {
                                            Text(if (uiState.isFollowing) "解除" else "フォロー", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                // About
                                if (!profile?.about.isNullOrBlank()) {
                                    Text(
                                        text = profile!!.about!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        modifier = Modifier.padding(top = 0.dp), // Adjust padding because of avatar offset
                                        lineHeight = 18.sp
                                    )
                                }

                                // Meta Info (LN, Website, Birthday)
                                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!profile?.lud16.isNullOrBlank()) {
                                        MetaInfoItem(NuruIcons.Zap(false), profile!!.lud16!!)
                                    }
                                    if (!profile?.website.isNullOrBlank()) {
                                        MetaInfoItem(NuruIcons.Website, profile!!.website!!, color = LineGreen)
                                    }
                                    if (!profile?.birthday.isNullOrBlank()) {
                                        MetaInfoItem(NuruIcons.Cake, profile!!.birthday!!)
                                    }
                                }

                                // Badges
                                if (uiState.badges.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(top = 12.dp)
                                    ) {
                                        uiState.badges.take(3).forEach { badge ->
                                            val thumb = badge.getTagValue("thumb") ?: badge.getTagValue("image")
                                            if (thumb != null) {
                                                AsyncImage(
                                                    model = thumb,
                                                    contentDescription = badge.getTagValue("name"),
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                )
                                            }
                                        }
                                    }
                                }

                                // Follow Count
                                Row(
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .clickable {
                                            viewModel.loadFollowProfiles()
                                            showFollowList = true
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.People, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    Text(
                                        text = uiState.followCount.toString(),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "フォロー中",
                                        fontSize = 14.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Tabs
                item {
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth(),
                        color = bgPrimary
                    ) {
                        Column {
                            TabRow(
                                selectedTabIndex = uiState.activeTab,
                                containerColor = bgPrimary,
                                contentColor = LineGreen,
                                indicator = { tabPositions ->
                                    TabRowDefaults.SecondaryIndicator(
                                        modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab]),
                                        color = LineGreen,
                                        height = 2.dp
                                    )
                                },
                                divider = {},
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Tab(
                                    selected = uiState.activeTab == 0,
                                    onClick = { viewModel.setActiveTab(0) },
                                    text = {
                                        Text(
                                            "投稿 (${uiState.posts.size})",
                                            color = if (uiState.activeTab == 0) LineGreen else TextTertiary,
                                            fontWeight = if (uiState.activeTab == 0) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                    }
                                )
                                Tab(
                                    selected = uiState.activeTab == 1,
                                    onClick = { viewModel.setActiveTab(1) },
                                    text = {
                                        Text(
                                            "いいね (${uiState.likedPosts.size})",
                                            color = if (uiState.activeTab == 1) LineGreen else TextTertiary,
                                            fontWeight = if (uiState.activeTab == 1) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                    }
                                )
                            }
                            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
                        }
                    }
                }

                if (uiState.isLoading) {
                    items(5) {
                        Surface(Modifier.padding(horizontal = 12.dp), color = bgPrimary) {
                            SkeletonPostItem()
                        }
                    }
                } else {
                    val displayPosts = if (uiState.activeTab == 0) uiState.posts else uiState.likedPosts
                    if (displayPosts.isEmpty()) {
                        item {
                            Surface(Modifier.padding(horizontal = 12.dp), color = bgPrimary) {
                                Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                                    Text(if (uiState.activeTab == 0) "投稿がありません" else "いいねがありません", color = TextTertiary)
                                }
                            }
                        }
                    } else {
                        items(displayPosts, key = { (if (uiState.activeTab == 1) "like_" else "") + it.event.id }) { post ->
                            Surface(Modifier.padding(horizontal = 12.dp), color = bgPrimary) {
                                PostItem(
                                    post = post,
                                    onLike = { viewModel.likePost(post.event.id) },
                                    onRepost = { viewModel.repostPost(post.event.id) },
                                    onProfileClick = { viewModel.loadProfile(it) },
                                    onDelete = { postToDelete = post.event.id },
                                    isOwnPost = post.event.pubkey == viewModel.myPubkeyHex,
                                    isVerified = if (post.event.pubkey == profile?.pubkey) uiState.isNip05Verified else false
                                )
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
            onProfileClick = { viewModel.loadProfile(it); showFollowList = false }
        )
    }

    if (showPostModal) {
        PostModal(
            pictureUrl = profile?.picture,
            displayName = profile?.displayedName ?: "",
            onDismiss = { showPostModal = false },
            onPublish = { content, cw -> viewModel.publishNote(content, cw); showPostModal = false }
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
            containerColor = bgPrimary,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

@Composable
fun MetaInfoItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = TextTertiary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, color = color, maxLines = 1)
    }
}

private fun formatNip05(nip05: String): String = when {
    nip05.startsWith("_@") -> nip05.drop(1)
    !nip05.contains("@") -> "@$nip05"
    else -> nip05
}

@Composable
private fun SkeletonPostItem() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(BgTertiary))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(100.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth().height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
                Box(Modifier.width(200.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
            }
        }
        HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
    }
}
