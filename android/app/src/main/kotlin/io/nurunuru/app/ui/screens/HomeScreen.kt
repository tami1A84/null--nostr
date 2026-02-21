package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.ui.components.PostItem
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val profile = uiState.profile

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing) pullRefreshState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Banner
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(nuruColors.bgTertiary)
                ) {
                    if (profile?.banner != null) {
                        AsyncImage(
                            model = profile.banner,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Profile section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp)
                ) {
                    // Avatar overlapping banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-32).dp)
                    ) {
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
                    }

                    Spacer(modifier = Modifier.height((-24).dp))

                    // Name
                    if (uiState.isLoading) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(20.dp)
                                .background(nuruColors.bgTertiary, RoundedCornerShape(4.dp))
                        )
                    } else {
                        val displayPk = uiState.profile?.pubkey?.take(8)?.let { "$it..." } ?: ""
                        Text(
                            text = profile?.displayedName ?: displayPk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    // NIP-05
                    if (profile?.nip05 != null) {
                        Text(
                            text = profile.nip05,
                            style = MaterialTheme.typography.bodySmall,
                            color = LineGreen
                        )
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
                                text = profile.website,
                                style = MaterialTheme.typography.bodySmall,
                                color = LineGreen
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Stats row
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.followCount.toString(),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "フォロー",
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textTertiary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.posts.size.toString(),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "ノート",
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textTertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                }
            }

            // Posts
            if (uiState.isLoading) {
                items(5) {
                    SkeletonPostItem()
                }
            } else {
                items(uiState.posts, key = { it.event.id }) { post ->
                    PostItem(
                        post = post,
                        onLike = {},
                        onRepost = {},
                        onReply = {},
                        onProfileClick = {}
                    )
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

@Composable
private fun SkeletonPostItem() {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(nuruColors.bgTertiary)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.width(100.dp).height(12.dp).background(nuruColors.bgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(12.dp).background(nuruColors.bgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.width(200.dp).height(12.dp).background(nuruColors.bgTertiary, RoundedCornerShape(4.dp)))
        }
    }
    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
}
