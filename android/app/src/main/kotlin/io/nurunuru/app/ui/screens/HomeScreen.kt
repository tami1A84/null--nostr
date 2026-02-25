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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.PostItem
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.*
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
            .background(Color.Black) // Fixed background as per web
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
                        .height(112.dp)
                        .background(nuruColors.lineGreen)
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
            }

            // Profile section
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset(y = (-48).dp)
                ) {
                    // Content Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp) // Leave space for half of avatar
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black) // Match background color
                            .border(0.5.dp, nuruColors.border, RoundedCornerShape(16.dp)) // Border for separation
                            .padding(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(24.dp)) // Content starts after avatar row

                        // Name
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = profile?.displayedName ?: NostrKeyUtils.shortenPubkey(
                                    uiState.profile?.pubkey ?: ""
                                ),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }

                        // NIP-05
                        if (profile?.nip05 != null && uiState.isNip05Verified) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = LineGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = formatNip05(profile.nip05),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LineGreen,
                                    maxLines = 1
                                )
                            }
                        }

                        // Pubkey (shortened)
                        Text(
                            text = NostrKeyUtils.shortenPubkey(uiState.profile?.pubkey ?: "", 12),
                            style = MaterialTheme.typography.labelSmall,
                            color = nuruColors.textTertiary,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        // Bio
                        if (profile?.about != null && profile.about.isNotBlank()) {
                            Text(
                                text = profile.about,
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textSecondary,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        // Lightning Address
                        if (profile?.lud16 != null && profile.lud16.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = nuruColors.textTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = profile.lud16,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = nuruColors.textTertiary,
                                    maxLines = 1
                                )
                            }
                        }

                        // Website
                        if (profile?.website != null && profile.website.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)
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
                                    color = LineGreen,
                                    maxLines = 1
                                )
                            }
                        }

                        // Birthday
                        if (profile?.birthday != null && profile.birthday.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp)
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
                                    color = nuruColors.textTertiary
                                )
                            }
                        }

                        // Badges
                        if (uiState.badges.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 12.dp)
                            ) {
                                uiState.badges.take(3).forEach { badge ->
                                    val thumb = badge.getTagValue("thumb") ?: badge.getTagValue("image")
                                    if (thumb != null) {
                                        AsyncImage(
                                            model = thumb,
                                            contentDescription = badge.getTagValue("name"),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    }
                                }
                            }
                        }

                        // Follow count
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clickable {
                                    viewModel.loadFollowProfiles()
                                    showFollowList = true
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = uiState.followCount.toString(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "フォロー中",
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textSecondary
                            )
                        }
                    }

                    // Floating Avatar and Action Button - Placed on top to avoid clipping
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color.Black)
                                .padding(4.dp)
                        ) {
                            UserAvatar(
                                pictureUrl = profile?.picture,
                                displayName = profile?.displayedName ?: "",
                                size = 72.dp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 48.dp) // Align with card content start
                        ) {
                            if (uiState.isOwnProfile) {
                                IconButton(
                                    onClick = { showEditProfile = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "編集",
                                        tint = nuruColors.textTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
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
                                        contentColor = if (uiState.isFollowing) Color.White else Color.White
                                    ),
                                    border = if (uiState.isFollowing) androidx.compose.foundation.BorderStroke(1.dp, nuruColors.border) else null,
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (uiState.isFollowing) "解除" else "フォロー",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height((-40).dp))
            }

            // Tabs
            item {
                TabRow(
                    selectedTabIndex = uiState.activeTab,
                    containerColor = Color.Transparent,
                    contentColor = LineGreen,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab]),
                            color = LineGreen
                        )
                    },
                    divider = { HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp) }
                ) {
                    Tab(
                        selected = uiState.activeTab == 0,
                        onClick = { viewModel.setActiveTab(0) },
                        text = {
                            Text(
                                "投稿 (${uiState.posts.size})",
                                color = if (uiState.activeTab == 0) LineGreen else nuruColors.textTertiary,
                                fontWeight = if (uiState.activeTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = uiState.activeTab == 1,
                        onClick = { viewModel.setActiveTab(1) },
                        text = {
                            Text(
                                "いいね (${uiState.likedPosts.size})",
                                color = if (uiState.activeTab == 1) LineGreen else nuruColors.textTertiary,
                                fontWeight = if (uiState.activeTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
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
                            onLike = { viewModel.likePost(post.event.id) },
                            onRepost = { viewModel.repostPost(post.event.id) },
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
            containerColor = BgSecondary,
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
    val nuruColors = LocalNuruColors.current
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
        containerColor = Color.Black, // Fixed BG
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            // Header (Web parity)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("キャンセル", color = TextSecondary, fontSize = 14.sp)
                }
                Text("プロフィール編集", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                TextButton(
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
                    enabled = !isUploading
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = nuruColors.lineGreen)
                    } else {
                        Text("保存", color = nuruColors.lineGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Name
                ProfileEditField(label = "名前", value = name, onValueChange = { name = it }, placeholder = "表示名")

                // Picture
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("アイコン画像", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            NuruTextField(value = picture, onValueChange = { picture = it }, placeholder = "https://...", singleLine = true)
                        }
                        IconButton(
                            onClick = { imageLauncher.launch("image/*") },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgTertiary)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Banner
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("バナー画像", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            NuruTextField(value = banner, onValueChange = { banner = it }, placeholder = "https://...", singleLine = true)
                        }
                        IconButton(
                            onClick = { bannerLauncher.launch("image/*") },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgTertiary)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // About
                ProfileEditField(label = "自己紹介", value = about, onValueChange = { about = it }, placeholder = "自己紹介", minLines = 3)

                // NIP-05
                ProfileEditField(label = "NIP-05", value = nip05, onValueChange = { nip05 = it }, placeholder = "name@example.com")

                // LUD-16
                ProfileEditField(label = "ライトニングアドレス", value = lud16, onValueChange = { lud16 = it }, placeholder = "you@wallet.com")

                // Website
                ProfileEditField(label = "ウェブサイト", value = website, onValueChange = { website = it }, placeholder = "https://example.com")

                // Birthday
                ProfileEditField(label = "誕生日", value = birthday, onValueChange = { birthday = it }, placeholder = "MM-DD または YYYY-MM-DD")

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun ProfileEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        NuruTextField(value = value, onValueChange = onValueChange, placeholder = placeholder, minLines = minLines)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuruTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
    singleLine: Boolean = false
) {
    val nuruColors = LocalNuruColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextTertiary, fontSize = 14.sp) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(0.5.dp, nuruColors.border, RoundedCornerShape(8.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = BgTertiary,
            unfocusedContainerColor = BgSecondary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = LineGreen,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        textStyle = TextStyle(fontSize = 14.sp),
        minLines = minLines,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE
    )
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
        containerColor = Color.Black
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp)) {
            Text(
                "フォロー中",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

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
                            Text(p?.displayedName ?: NostrKeyUtils.shortenPubkey(pk), fontWeight = FontWeight.Bold, color = TextPrimary)
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
                    HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
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
                .background(BgTertiary)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.width(100.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.width(200.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
        }
    }
    HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
}
