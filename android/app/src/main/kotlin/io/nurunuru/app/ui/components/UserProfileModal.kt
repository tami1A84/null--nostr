package io.nurunuru.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileModal(
    pubkey: String,
    viewModel: HomeViewModel,
    repository: io.nurunuru.app.data.NostrRepository,
    onDismiss: () -> Unit,
    onStartDM: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = io.nurunuru.app.ui.theme.LocalNuruColors.current
    val clipboardManager = LocalClipboardManager.current

    // Create a dedicated ViewModel scope or use LaunchedEffect to load profile
    LaunchedEffect(pubkey) {
        viewModel.loadProfile(pubkey)
    }

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showBirthdayAnimation by remember { mutableStateOf(false) }

    // Check for birthday
    LaunchedEffect(uiState.profile) {
        val p = uiState.profile ?: return@LaunchedEffect
        if (p.birthday != null) {
            val today = java.util.Calendar.getInstance()
            val month = today.get(java.util.Calendar.MONTH) + 1
            val day = today.get(java.util.Calendar.DAY_OF_MONTH)

            // Format is YYYY-MM-DD or MM-DD
            val parts = p.birthday.split("-")
            val isMatch = if (parts.size == 3) {
                parts[1].toIntOrNull() == month && parts[2].toIntOrNull() == day
            } else if (parts.size == 2) {
                parts[0].toIntOrNull() == month && parts[1].toIntOrNull() == day
            } else false

            if (isMatch) {
                showBirthdayAnimation = true
                delay(5000)
                showBirthdayAnimation = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Surface(color = Color.Black, shadowElevation = 4.dp) {
                    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Color.White)
                            }
                            Text(
                                text = "プロフィール",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            IconButton(onClick = { showSearch = !showSearch }) {
                                Icon(Icons.Default.Search, "検索", tint = if (showSearch) io.nurunuru.app.ui.theme.LineGreen else Color.White)
                            }

                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, "メニュー", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(nuruColors.bgSecondary)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("ミュート", color = Color.Red) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.muteUser(pubkey)
                                            onDismiss()
                                        },
                                        leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) }
                                    )
                                }
                            }
                        }

                        if (showSearch) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("投稿を検索...", fontSize = 14.sp) },
                                    singleLine = true,
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        focusedBorderColor = io.nurunuru.app.ui.theme.LineGreen,
                                        unfocusedBorderColor = nuruColors.border
                                    )
                                )
                                Button(
                                    onClick = { viewModel.searchPosts(searchQuery) },
                                    colors = ButtonDefaults.buttonColors(containerColor = io.nurunuru.app.ui.theme.LineGreen),
                                    enabled = !uiState.isSearching && searchQuery.isNotBlank()
                                ) {
                                    if (uiState.isSearching) {
                                        CircularProgressIndicator(size = 18.dp, color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Text("検索")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            containerColor = Color.Black
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                val pullRefreshState = rememberPullToRefreshState()
                if (pullRefreshState.isRefreshing) {
                    LaunchedEffect(Unit) { viewModel.refresh() }
                }
                LaunchedEffect(uiState.isRefreshing) {
                    if (!uiState.isRefreshing) pullRefreshState.endRefresh()
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(pullRefreshState.nestedScrollConnection)
                ) {
                    item {
                        ProfileHeader(
                            profile = uiState.profile,
                            isOwnProfile = uiState.isOwnProfile,
                            isFollowing = uiState.isFollowing,
                            isNip05Verified = uiState.isNip05Verified,
                            followCount = uiState.followCount,
                            badges = uiState.badges,
                            onEditClick = { /* N/A for other profile */ },
                            onFollowClick = {
                                if (uiState.isFollowing) viewModel.unfollowUser(pubkey)
                                else viewModel.followUser(pubkey)
                            },
                            onFollowListClick = {
                                viewModel.loadFollowProfiles()
                            },
                            clipboardManager = clipboardManager
                        )

                        if (!uiState.isOwnProfile) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onStartDM(pubkey) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = nuruColors.bgSecondary, contentColor = Color.White),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Mail, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("メッセージ")
                                }
                            }
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
                        items(5) {
                            PostSkeleton()
                        }
                    } else {
                        if (uiState.searchResults.isNotEmpty() || uiState.searchQuery.isNotBlank()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (uiState.searchResults.isEmpty()) "検索結果がありません" else "検索結果 (${uiState.searchResults.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = nuruColors.textSecondary
                                    )
                                    Text(
                                        "クリア",
                                        color = io.nurunuru.app.ui.theme.LineGreen,
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable { viewModel.clearSearch(); searchQuery = "" }
                                    )
                                }
                            }
                        }

                        val displayPosts = if (uiState.searchResults.isNotEmpty()) uiState.searchResults
                                          else if (uiState.activeTab == 0) uiState.posts
                                          else uiState.likedPosts

                        items(displayPosts, key = { (if (uiState.searchResults.isNotEmpty()) "search_" else "") + it.event.id }) { post ->
                            if (post.event.kind == 30023) {
                                LongFormPostItem(
                                    post = post,
                                    onLike = { viewModel.likePost(post.event.id) },
                                    onRepost = { viewModel.repostPost(post.event.id) },
                                    onProfileClick = { /* Stay or navigate? */ },
                                    repository = repository
                                )
                            } else {
                                PostItem(
                                    post = post,
                                    onLike = { viewModel.likePost(post.event.id) },
                                    onRepost = { viewModel.repostPost(post.event.id) },
                                    onProfileClick = { viewModel.loadProfile(it) },
                                    repository = repository,
                                    onDelete = { /* N/A */ },
                                    onMute = { viewModel.muteUser(it) },
                                    isOwnPost = post.event.pubkey == viewModel.myPubkeyHex
                                )
                            }
                        }
                    }
                }

                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = Color.Black,
                    contentColor = io.nurunuru.app.ui.theme.LineGreen
                )

                // Birthday Animation Overlay
                if (showBirthdayAnimation) {
                    BirthdayAnimationOverlay(name = uiState.profile?.name ?: "Anonymous")
                }
            }
        }
    }
}

@Composable
fun BirthdayAnimationOverlay(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFE1BEE7).copy(alpha = 0.8f),
                        Color(0xFFFCE4EC).copy(alpha = 0.8f),
                        Color(0xFFE1F5FE).copy(alpha = 0.8f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Confetti
        repeat(20) { i ->
            val infiniteTransition = rememberInfiniteTransition()
            val yPos by infiniteTransition.animateFloat(
                initialValue = -50f,
                targetValue = 1000f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3000 + (i * 100), easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
            val xOffset = (i * 50) % 400

            Text(
                text = listOf("*", "+", "★", "✦", "◆", "●", "♦")[i % 7],
                modifier = Modifier
                    .offset(x = xOffset.dp - 200.dp, y = yPos.dp)
                    .graphicsLayer { alpha = 0.6f },
                fontSize = 24.sp,
                color = listOf(Color.Magenta, Color.Cyan, Color.Yellow, Color.Red)[i % 4]
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = 1.1f
                scaleY = 1.1f
            }
        ) {
            AsyncImage(
                model = "/birthday-character.jpg", // Needs to be in android assets or a valid URL
                contentDescription = "Happy Birthday",
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.height(16.dp))

            Surface(
                color = Color.White.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🎂", fontSize = 20.sp)
                        Text("Happy Birthday!", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                        Text("🎂", fontSize = 20.sp)
                    }
                    Text(text = name, fontSize = 14.sp, color = Color.Gray)
                }
            }
        }
    }
}
