package io.nurunuru.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.ui.components.PostItem
import io.nurunuru.app.ui.components.PostModal
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.FeedType
import io.nurunuru.app.viewmodel.TimelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    myPictureUrl: String?,
    myDisplayName: String
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val listState = rememberLazyListState()
    var showPostModal by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) {
            viewModel.refresh()
        }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing) pullRefreshState.endRefresh()
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                    .blur(if (android.os.Build.VERSION.SDK_INT >= 31) 20.dp else 0.dp)
            ) {
                Column {
                    // Tab bar: Global / Following
                    TopAppBar(
                        windowInsets = WindowInsets.statusBars,
                        title = {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .background(nuruColors.bgSecondary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                            // Pill-style tabs
                            Button(
                                onClick = { viewModel.switchFeed(FeedType.GLOBAL) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.feedType == FeedType.GLOBAL) LineGreen else Color.Transparent,
                                    contentColor = if (uiState.feedType == FeedType.GLOBAL) Color.White else nuruColors.textTertiary
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(16.dp),
                                elevation = null
                            ) {
                                Text("おすすめ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.switchFeed(FeedType.FOLLOWING) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.feedType == FeedType.FOLLOWING) LineGreen else Color.Transparent,
                                    contentColor = if (uiState.feedType == FeedType.FOLLOWING) Color.White else nuruColors.textTertiary
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(16.dp),
                                elevation = null
                            ) {
                                Text("フォロー", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) {
                                searchText = ""
                                viewModel.clearSearch()
                            }
                        }) {
                            Icon(
                                imageVector = if (showSearch) Icons.Outlined.Close else Icons.Default.Search,
                                contentDescription = "検索",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { /* TODO: Notifications */ }) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "通知",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    )
                )

                // Search bar
                AnimatedVisibility(visible = showSearch) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = {
                            searchText = it
                            if (it.length >= 2) viewModel.search(it)
                            else if (it.isEmpty()) viewModel.clearSearch()
                        },
                        placeholder = { Text("ノートを検索...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LineGreen,
                            cursorColor = LineGreen
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            val displayPosts = if (searchText.isNotBlank()) uiState.searchResults else uiState.posts

            when {
                uiState.isLoading && displayPosts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LineGreen)
                    }
                }
                uiState.error != null && displayPosts.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(nuruColors.bgSecondary, RoundedCornerShape(32.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🌐", fontSize = 32.sp)
                            }
                            Text(
                                "接続エラー",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "通信状態を確認して、もう一度お試しください",
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.loadTimeline() },
                                colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("再試行", fontSize = 14.sp)
                            }
                        }
                    }
                }
                displayPosts.isEmpty() && searchText.isNotBlank() && !uiState.isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("「$searchText」の検索結果がありません", color = nuruColors.textTertiary)
                    }
                }
                displayPosts.isEmpty() && !uiState.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(20.dp)
                        ) {
                            if (uiState.feedType == FeedType.FOLLOWING) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(nuruColors.bgSecondary, RoundedCornerShape(32.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("👥", fontSize = 32.sp)
                                }
                                Text(
                                    if (uiState.followList.isEmpty()) "まだ誰もフォローしていません" else "フォロー中のユーザーの投稿がありません",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = nuruColors.textTertiary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.widthIn(max = 280.dp)
                                )
                                if (uiState.followList.isEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Surface(
                                        color = nuruColors.bgSecondary,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            "💡 プロフィールページでユーザーをフォローしてみましょう",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 10.sp,
                                            color = nuruColors.textTertiary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            } else {
                                // Global/Recommend empty state (empty-friendly style)
                                Text("📭", fontSize = 48.sp)
                                Text(
                                    "まだ投稿がありません\n新しい投稿がまもなく届くかもしれません",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = nuruColors.textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(displayPosts, key = { it.event.id }) { post ->
                            Surface(
                                modifier = Modifier.padding(horizontal = 12.dp),
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
