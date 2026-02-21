package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.PostItem
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.components.ZapModal
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.ProfileViewModel

/**
 * Full-screen profile view for any Nostr user.
 * Supports follow/unfollow, DM, Zap, and post browsing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    pubkeyHex: String,
    myPubkeyHex: String,
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit,
    onStartDm: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val isOwnProfile = pubkeyHex == myPubkeyHex

    var zapTargetPost by remember { mutableStateOf<ScoredPost?>(null) }

    // Pull-to-refresh
    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.loadProfile(pubkeyHex) }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) pullRefreshState.endRefresh()
    }

    // Load profile on first composition or pubkey change
    LaunchedEffect(pubkeyHex) {
        viewModel.loadProfile(pubkeyHex)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Top bar
            item {
                TopAppBar(
                    title = {
                        Text(
                            "プロフィール",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        // DM button (only for others)
                        if (!isOwnProfile) {
                            IconButton(onClick = { onStartDm(pubkeyHex) }) {
                                Icon(
                                    Icons.Outlined.ChatBubble,
                                    contentDescription = "DMを送る",
                                    tint = nuruColors.textTertiary
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }

            // Banner
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(nuruColors.bgTertiary)
                ) {
                    uiState.profile?.banner?.let { bannerUrl ->
                        AsyncImage(
                            model = bannerUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Profile card
            item {
                ProfileCard(
                    profile = uiState.profile,
                    pubkeyHex = pubkeyHex,
                    followCount = uiState.followCount,
                    postCount = uiState.posts.size,
                    isOwnProfile = isOwnProfile,
                    isFollowing = uiState.isFollowing,
                    isFollowLoading = uiState.isFollowLoading,
                    isLoading = uiState.isLoading,
                    onToggleFollow = { viewModel.toggleFollow(pubkeyHex) }
                )
            }

            // Posts section header
            item {
                Text(
                    text = "投稿",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = nuruColors.textTertiary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                )
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            }

            // Posts list
            if (uiState.isLoading) {
                items(5) { ProfileSkeletonPostItem() }
            } else if (uiState.posts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("投稿がありません", color = nuruColors.textTertiary)
                    }
                }
            } else {
                items(uiState.posts, key = { it.event.id }) { post ->
                    PostItem(
                        post = post,
                        onLike = {},
                        onRepost = {},
                        onReply = {},
                        onZap = {
                            zapTargetPost = post
                        },
                        onProfileClick = onProfileClick
                    )
                }
            }
        }

        // Pull-to-refresh indicator
        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = LineGreen
        )
    }

    // Zap modal
    zapTargetPost?.let { post ->
        ZapModal(
            post = post,
            onDismiss = { zapTargetPost = null },
            onFetchInvoice = { lud16, amountSats, comment ->
                viewModel.fetchZapInvoiceSync(lud16, amountSats, comment)
            }
        )
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile?,
    pubkeyHex: String,
    followCount: Int,
    postCount: Int,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    isLoading: Boolean,
    onToggleFollow: () -> Unit
) {
    val nuruColors = LocalNuruColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        // Avatar + follow button row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-32).dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Avatar with white border ring
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(3.dp)
            ) {
                UserAvatar(
                    pictureUrl = profile?.picture,
                    displayName = profile?.displayedName ?: "",
                    size = 66.dp
                )
            }

            // Follow / Unfollow button (only for others)
            if (!isOwnProfile) {
                Button(
                    onClick = onToggleFollow,
                    enabled = !isFollowLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFollowing) MaterialTheme.colorScheme.surfaceVariant
                                        else LineGreen,
                        contentColor = if (isFollowing) MaterialTheme.colorScheme.onSurface
                                       else MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    if (isFollowLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isFollowing) "フォロー中" else "フォロー",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height((-20).dp))

        // Display name
        if (isLoading) {
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(20.dp)
                    .background(nuruColors.bgTertiary, RoundedCornerShape(4.dp))
            )
        } else {
            Text(
                text = profile?.displayedName ?: NostrKeyUtils.shortenPubkey(pubkeyHex),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // NIP-05
        if (profile?.nip05 != null) {
            Text(
                text = formatNip05(profile.nip05),
                style = MaterialTheme.typography.bodySmall,
                color = LineGreen
            )
        }

        // Lightning address
        if (profile?.lud16 != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Outlined.ElectricBolt,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = profile.lud16,
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bio
        if (profile?.about != null) {
            Text(
                text = profile.about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Website
        if (profile?.website != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    tint = nuruColors.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = profile.website.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = LineGreen
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Stats: フォロー / ノート
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatItem(value = followCount, label = "フォロー")
            StatItem(value = postCount, label = "ノート")
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }
}

@Composable
private fun StatItem(value: Int, label: String) {
    val nuruColors = LocalNuruColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = nuruColors.textTertiary
        )
    }
}

@Composable
private fun ProfileSkeletonPostItem() {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(nuruColors.bgTertiary)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(100.dp).height(12.dp).background(nuruColors.bgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(12.dp).background(nuruColors.bgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.width(200.dp).height(12.dp).background(nuruColors.bgTertiary, RoundedCornerShape(4.dp)))
        }
    }
    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
}

private fun formatNip05(nip05: String): String = when {
    nip05.startsWith("_@") -> nip05.drop(1)
    !nip05.contains("@") -> "@$nip05"
    else -> nip05
}
