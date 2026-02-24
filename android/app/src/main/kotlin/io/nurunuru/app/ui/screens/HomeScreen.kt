package io.nurunuru.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Link
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.PostItem
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    val profile = uiState.profile
    val scope = rememberCoroutineScope()

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    LaunchedEffect(uiState.isRefreshing) {
        if (!uiState.isRefreshing) pullRefreshState.endRefresh()
    }

    var showEditProfile by remember { mutableStateOf(false) }
    var showFollowList by remember { mutableStateOf(false) }

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
                    // Avatar and Edit Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-32).dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                                .padding(3.dp)
                        ) {
                            UserAvatar(
                                pictureUrl = profile?.picture,
                                displayName = profile?.displayedName ?: "",
                                size = 74.dp
                            )
                        }

                        if (uiState.isOwnProfile) {
                            Button(
                                onClick = { showEditProfile = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.onBackground
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, nuruColors.border),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("編集", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (uiState.isFollowing) {
                                        viewModel.unfollowUser(uiState.viewingPubkey!!)
                                    } else {
                                        viewModel.followUser(uiState.viewingPubkey!!)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.isFollowing) Color.Transparent else nuruColors.lineGreen,
                                    contentColor = if (uiState.isFollowing) MaterialTheme.colorScheme.onBackground else Color.White
                                ),
                                border = if (uiState.isFollowing) androidx.compose.foundation.BorderStroke(1.dp, nuruColors.border) else null,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    if (uiState.isFollowing) "解除" else "フォロー",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                        Text(
                            text = profile?.displayedName ?: NostrKeyUtils.shortenPubkey(
                                uiState.profile?.pubkey ?: ""
                            ),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    // NIP-05
                    if (profile?.nip05 != null && uiState.isNip05Verified) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = LineGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = formatNip05(profile.nip05),
                                style = MaterialTheme.typography.bodySmall,
                                color = LineGreen
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
                    if (profile?.website != null && profile.website.isNotBlank()) {
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

                    // Birthday
                    if (profile?.birthday != null && profile.birthday.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Cake,
                                contentDescription = null,
                                tint = nuruColors.textTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = profile.birthday,
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Badges
                    if (uiState.badges.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            uiState.badges.take(3).forEach { badge ->
                                val thumb = badge.getTagValue("thumb") ?: badge.getTagValue("image")
                                if (thumb != null) {
                                    AsyncImage(
                                        model = thumb,
                                        contentDescription = badge.getTagValue("name"),
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Stats row
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.clickable { if (uiState.isOwnProfile) {
                            viewModel.loadFollowProfiles()
                            showFollowList = true
                        } }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.followCount.toString(),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "フォロー中",
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textTertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tabs
                    TabRow(
                        selectedTabIndex = uiState.activeTab,
                        containerColor = Color.Transparent,
                        contentColor = LineGreen,
                        divider = { HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp) }
                    ) {
                        Tab(
                            selected = uiState.activeTab == 0,
                            onClick = { viewModel.setActiveTab(0) },
                            text = { Text("投稿 (${uiState.posts.size})") }
                        )
                        Tab(
                            selected = uiState.activeTab == 1,
                            onClick = { viewModel.setActiveTab(1) },
                            text = { Text("いいね (${uiState.likedPosts.size})") }
                        )
                    }
                }
            }

            // Content based on active tab
            if (uiState.isLoading) {
                items(5) {
                    SkeletonPostItem()
                }
            } else {
                val displayPosts = if (uiState.activeTab == 0) uiState.posts else uiState.likedPosts

                if (displayPosts.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.activeTab == 0) "投稿がありません" else "いいねした投稿がありません",
                                color = nuruColors.textTertiary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(displayPosts, key = { if (uiState.activeTab == 1) "like_${it.event.id}" else it.event.id }) { post ->
                        PostItem(
                            post = post,
                            onLike = { /* TODO: Implement in ViewModel if needed or use Global */ },
                            onRepost = { /* TODO: Implement */ },
                            onProfileClick = { pubkey -> viewModel.loadProfile(pubkey) },
                            onDelete = { viewModel.deletePost(post.event.id) },
                            isOwnPost = post.event.pubkey == viewModel.myPubkeyHex
                        )
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

    if (showEditProfile) {
        EditProfileModal(
            profile = profile ?: UserProfile(pubkey = uiState.viewingPubkey ?: ""),
            onDismiss = { showEditProfile = false },
            onSave = { updated ->
                viewModel.updateProfile(updated)
                showEditProfile = false
            },
            viewModel = viewModel
        )
    }

    if (showFollowList) {
        FollowListModal(
            pubkeys = uiState.followList,
            profiles = uiState.followProfiles,
            onDismiss = { showFollowList = false },
            onUnfollow = { viewModel.unfollowUser(it) },
            onProfileClick = {
                viewModel.loadProfile(it)
                showFollowList = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileModal(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit,
    viewModel: HomeViewModel
) {
    var name by remember { mutableStateOf(profile.name ?: "") }
    var about by remember { mutableStateOf(profile.about ?: "") }
    var picture by remember { mutableStateOf(profile.picture ?: "") }
    var banner by remember { mutableStateOf(profile.banner ?: "") }
    var nip05 by remember { mutableStateOf(profile.nip05 ?: "") }
    var lud16 by remember { mutableStateOf(profile.lud16 ?: "") }
    var website by remember { mutableStateOf(profile.website ?: "") }
    var birthday by remember { mutableStateOf(profile.birthday ?: "") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isUploading = true
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                if (bytes != null) {
                    val url = viewModel.uploadImage(bytes, context.contentResolver.getType(it) ?: "image/jpeg")
                    if (url != null) {
                        picture = url
                    }
                }
                isUploading = false
            }
        }
    }

    val bannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isUploading = true
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                if (bytes != null) {
                    val url = viewModel.uploadImage(bytes, context.contentResolver.getType(it) ?: "image/jpeg")
                    if (url != null) {
                        banner = url
                    }
                }
                isUploading = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
        ) {
            Text("プロフィール編集", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = picture, onValueChange = { picture = it }, label = { Text("アイコン画像URL") }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { IconButton(onClick = { imageLauncher.launch("image/*") }) { Icon(Icons.Default.Edit, null) } }
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = banner, onValueChange = { banner = it }, label = { Text("バナー画像URL") }, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { IconButton(onClick = { bannerLauncher.launch("image/*") }) { Icon(Icons.Default.Edit, null) } }
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = about, onValueChange = { about = it }, label = { Text("自己紹介") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = nip05, onValueChange = { nip05 = it }, label = { Text("NIP-05") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = lud16, onValueChange = { lud16 = it }, label = { Text("ライトニングアドレス") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = website, onValueChange = { website = it }, label = { Text("ウェブサイト") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(value = birthday, onValueChange = { birthday = it }, label = { Text("誕生日 (MM-DD)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    onSave(profile.copy(
                        name = name,
                        about = about,
                        picture = picture,
                        banner = banner,
                        nip05 = nip05,
                        lud16 = lud16,
                        website = website,
                        birthday = birthday
                    ))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                enabled = !isUploading
            ) {
                if (isUploading) CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                else Text("保存", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListModal(
    pubkeys: List<String>,
    profiles: Map<String, UserProfile>,
    onDismiss: () -> Unit,
    onUnfollow: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp)) {
            Text(
                "フォロー中",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider(color = LocalNuruColors.current.border, thickness = 0.5.dp)

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(pubkeys) { pk ->
                    val p = profiles[pk]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProfileClick(pk) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(pictureUrl = p?.picture, displayName = p?.displayedName ?: "", size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p?.displayedName ?: NostrKeyUtils.shortenPubkey(pk), fontWeight = FontWeight.Bold)
                            if (p?.nip05 != null) Text(p.nip05, style = MaterialTheme.typography.bodySmall, color = LineGreen)
                        }
                        Button(
                            onClick = { onUnfollow(pk) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Red),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("解除", fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = LocalNuruColors.current.border, thickness = 0.5.dp)
                }
            }
        }
    }
}

private fun formatNip05(nip05: String): String = when {
    nip05.startsWith("_@") -> nip05.drop(1)
    !nip05.contains("@") -> "@$nip05"
    else -> nip05
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
