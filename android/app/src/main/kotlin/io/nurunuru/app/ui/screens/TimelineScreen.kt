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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
            Column {
                // Tab bar: Global / Following
                TopAppBar(
                    windowInsets = WindowInsets.statusBars,
                    title = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = uiState.feedType == FeedType.GLOBAL,
                                onClick = { viewModel.switchFeed(FeedType.GLOBAL) },
                                label = { Text("おすすめ") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LineGreen,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                            FilterChip(
                                selected = uiState.feedType == FeedType.FOLLOWING,
                                onClick = { viewModel.switchFeed(FeedType.FOLLOWING) },
                                label = { Text("フォロー") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LineGreen,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
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
                        containerColor = MaterialTheme.colorScheme.background
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
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(uiState.error!!, color = nuruColors.textTertiary)
                            Button(
                                onClick = { viewModel.loadTimeline() },
                                colors = ButtonDefaults.buttonColors(containerColor = LineGreen)
                            ) {
                                Text("再読み込み")
                            }
                        }
                    }
                }
                displayPosts.isEmpty() && searchText.isNotBlank() && !uiState.isSearching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("「$searchText」の検索結果がありません", color = nuruColors.textTertiary)
                    }
                }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(displayPosts, key = { it.event.id }) { post ->
                            PostItem(
                                post = post,
                                onLike = { viewModel.likePost(post.event.id) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = { /* TODO: navigate to profile */ },
                                birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList()
                            )
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
