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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.PostItem
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.components.PostModal
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.*
import io.nurunuru.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onLogout: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val profile = uiState.profile
    val scope = rememberCoroutineScope()
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
                // Header (Banner + Rounded Sheet Overlap)
                item {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // Banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
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

                        // The Rounded Sheet
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 96.dp), // Starts 24dp from bottom of banner
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                            color = bgPrimary
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 112.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                            ) {
                                // Name and Edit/Follow Button
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = profile?.displayedName ?: NostrKeyUtils.shortenPubkey(profile?.pubkey ?: ""),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 20.sp,
                                                color = TextPrimary,
                                                maxLines = 1
                                            )
                                            if (uiState.isOwnProfile) {
                                                IconButton(
                                                    onClick = { showEditProfile = true },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = "編集",
                                                        tint = TextTertiary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // NIP-05 Badge
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
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = LineGreen
                                                )
                                            }
                                        }
                                    }

                                    // Follow/Unfollow Button for other profiles
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
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Text(if (uiState.isFollowing) "解除" else "フォロー", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Avatar (Absolute positioned over the sheet edge)
                        Box(
                            modifier = Modifier
                                .padding(top = 64.dp, start = 16.dp)
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(bgPrimary)
                                .padding(4.dp)
                        ) {
                            UserAvatar(
                                pictureUrl = profile?.picture,
                                displayName = profile?.displayedName ?: "",
                                size = 80.dp
                            )
                        }
                    }
                }

                // Profile Details
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp)
                    ) {
                        // Pubkey + Copy
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
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

                        // Bio
                        if (!profile?.about.isNullOrBlank()) {
                            Text(
                                text = profile!!.about!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 12.dp),
                                lineHeight = 18.sp
                            )
                        }

                        // Meta Info (LN, Website, Birthday)
                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!profile?.lud16.isNullOrBlank()) {
                                MetaInfoItem(NuruIcons.Zap(false), profile!!.lud16!!)
                            }
                            if (!profile?.website.isNullOrBlank()) {
                                MetaInfoItem(Icons.Default.Language, profile!!.website!!, color = LineGreen)
                            }
                            if (!profile?.birthday.isNullOrBlank()) {
                                MetaInfoItem(Icons.Default.Cake, profile!!.birthday!!)
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

                        // Follow Stats
                        Row(
                            modifier = Modifier
                                .padding(top = 16.dp)
                                .clickable {
                                    viewModel.loadFollowProfiles()
                                    showFollowList = true
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
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

                // Tabs
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
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

                // Feed Content
                if (uiState.isLoading) {
                    items(5) { SkeletonPostItem() }
                } else {
                    val displayPosts = if (uiState.activeTab == 0) uiState.posts else uiState.likedPosts
                    if (displayPosts.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                                Text(if (uiState.activeTab == 0) "投稿がありません" else "いいねがありません", color = TextTertiary)
                            }
                        }
                    } else {
                        items(displayPosts, key = { (if (uiState.activeTab == 1) "like_" else "") + it.event.id }) { post ->
                            PostItem(
                                post = post,
                                onLike = { viewModel.likePost(post.event.id) },
                                onRepost = { viewModel.repostPost(post.event.id) },
                                onProfileClick = { viewModel.loadProfile(it) },
                                onDelete = { viewModel.deletePost(post.event.id) },
                                isOwnPost = post.event.pubkey == viewModel.myPubkeyHex,
                                isVerified = if (post.event.pubkey == profile?.pubkey) uiState.isNip05Verified else false
                            )
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
}

@Composable
fun MetaInfoItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = TextTertiary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, color = color, maxLines = 1)
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

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isUploading = true
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                val url = viewModel.uploadImage(bytes ?: byteArrayOf(), context.contentResolver.getType(it) ?: "image/jpeg")
                if (url != null) picture = url
                isUploading = false
            }
        }
    }

    val bannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isUploading = true
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                val url = viewModel.uploadImage(bytes ?: byteArrayOf(), context.contentResolver.getType(it) ?: "image/jpeg")
                if (url != null) banner = url
                isUploading = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black,
        dragHandle = null
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("キャンセル", color = TextSecondary) }
                Text("プロフィール編集", fontWeight = FontWeight.Bold, color = TextPrimary)
                TextButton(onClick = { onSave(profile.copy(name=name, about=about, picture=picture, banner=banner, nip05=nip05, lud16=lud16, website=website, birthday=birthday)) }, enabled = !isUploading) {
                    if (isUploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LineGreen)
                    else Text("保存", color = LineGreen, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileEditField("名前", name, { name = it }, "表示名")

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("アイコン画像", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { NuruTextField(picture, { picture = it }, "https://...", singleLine = true) }
                        IconButton(onClick = { imageLauncher.launch("image/*") }, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(BgTertiary)) {
                            Icon(Icons.Default.FileUpload, null, tint = TextPrimary)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("バナー画像", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { NuruTextField(banner, { banner = it }, "https://...", singleLine = true) }
                        IconButton(onClick = { bannerLauncher.launch("image/*") }, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(BgTertiary)) {
                            Icon(Icons.Default.FileUpload, null, tint = TextPrimary)
                        }
                    }
                }

                ProfileEditField("自己紹介", about, { about = it }, "自己紹介", minLines = 3)
                ProfileEditField("NIP-05", nip05, { nip05 = it }, "name@example.com")
                ProfileEditField("ライトニングアドレス", lud16, { lud16 = it }, "you@wallet.com")
                ProfileEditField("ウェブサイト", website, { website = it }, "https://example.com")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfileEditField("誕生日", birthday, { birthday = it }, "MM-DD または YYYY-MM-DD")
                    Text("例: 01-15 または 2000-01-15", fontSize = 12.sp, color = TextTertiary)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun ProfileEditField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, minLines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        NuruTextField(value, onValueChange, placeholder, minLines)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuruTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, minLines: Int = 1, singleLine: Boolean = false) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextTertiary, fontSize = 14.sp) },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(0.5.dp, BorderColor, RoundedCornerShape(8.dp)),
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
fun FollowListModal(pubkeys: List<String>, profiles: Map<String, UserProfile>, onDismiss: () -> Unit, onUnfollow: (String) -> Unit, onProfileClick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.Black) {
        Column(Modifier.fillMaxWidth().heightIn(min = 400.dp)) {
            Text("フォロー中 (${pubkeys.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(16.dp))
            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(pubkeys) { pk ->
                    val p = profiles[pk]
                    Row(Modifier.fillMaxWidth().clickable { onProfileClick(pk) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(p?.picture, p?.displayedName ?: "", 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p?.displayedName ?: NostrKeyUtils.shortenPubkey(pk), fontWeight = FontWeight.Bold, color = TextPrimary)
                            if (p?.nip05 != null) Text(p.nip05, style = MaterialTheme.typography.bodySmall, color = LineGreen)
                        }
                        Button(onClick = { onUnfollow(pk) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.Red), border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red), shape = RoundedCornerShape(16.dp), modifier = Modifier.height(32.dp)) {
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
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(BgTertiary))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(100.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth().height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
            Box(Modifier.width(200.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
        }
    }
    HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
}
