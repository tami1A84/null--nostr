package io.nurunuru.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.location.Location
import kotlin.math.roundToInt
import android.location.LocationManager
import io.nurunuru.app.data.*
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.RelayDiscovery
import io.nurunuru.app.data.models.DEFAULT_RELAYS
import io.nurunuru.app.data.models.Nip65Relay
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.miniapps.BadgeSettings
import io.nurunuru.app.ui.miniapps.ElevenLabsSettings
import io.nurunuru.app.ui.miniapps.EmojiSettings
import io.nurunuru.app.ui.miniapps.EventBackupSettings
import io.nurunuru.app.ui.miniapps.MuteList
import io.nurunuru.app.ui.miniapps.NostrBrowserApp
import io.nurunuru.app.ui.miniapps.CacheSettings
import io.nurunuru.app.ui.miniapps.ZapSettings
import io.nurunuru.app.ui.miniapps.SchedulerApp
import io.nurunuru.app.ui.miniapps.VanishRequest
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class MiniAppData(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val type: String = "internal",
    val url: String? = null
)

@Composable
fun MiniAppData.getIcon(): ImageVector {
    return when (id) {
        "emoji" -> NuruIcons.Emoji
        "badge" -> NuruIcons.Badge
        "scheduler" -> NuruIcons.Scheduler
        "zap" -> NuruIcons.Bitcoin
        "relay" -> NuruIcons.Relay
        "upload" -> NuruIcons.Image
        "mute" -> NuruIcons.Block
        "elevenlabs" -> NuruIcons.Mic
        "backup" -> NuruIcons.Backup
        "vanish" -> NuruIcons.Trash
        "cache"  -> Icons.Outlined.Storage
        else -> if (type == "external") Icons.Outlined.OpenInNew else Icons.Outlined.Extension
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    repository: NostrRepository,
    prefs: AppPreferences,
    pubkeyHex: String,
    pictureUrl: String?,
    onExternalAppOpenChanged: (Boolean) -> Unit = {},
    onMlsCacheCleared: () -> Unit = {}
) {
    val nuruColors = LocalNuruColors.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    val categoryList = listOf("all" to "すべて", "entertainment" to "エンタメ", "tools" to "ツール", "others" to "その他")
    val pagerState = rememberPagerState { categoryList.size }
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<MiniAppData?>(null) }
    var showExternalAdd by remember { mutableStateOf(false) }
    LaunchedEffect(selectedApp) {
        onExternalAppOpenChanged(selectedApp?.type == "external")
    }
    DisposableEffect(Unit) {
        onDispose { onExternalAppOpenChanged(false) }
    }
    var editingApp by remember { mutableStateOf<MiniAppData?>(null) }

    val npub = remember(pubkeyHex) { NostrKeyUtils.encodeNpub(pubkeyHex) ?: pubkeyHex }
    val favorites = remember { mutableStateListOf<String>().apply { addAll(prefs.favoriteApps) } }

    val allApps = remember {
        listOf(
            MiniAppData("emoji", "カスタム絵文字", "投稿やリアクションに使える絵文字を管理・追加", "entertainment"),
            MiniAppData("badge", "プロフィールバッジ", "プロフィールに表示するバッジを設定・管理", "entertainment"),
            MiniAppData("scheduler", "調整くん", "オフ会や会議の予定を簡単に調整", "entertainment"),
            MiniAppData("mute", "ミュートリスト", "不快なユーザーやキーワードを非表示に管理", "tools"),
            MiniAppData("zap", "Zap設定", "デフォルトのZap金額をクイック設定", "tools"),
            MiniAppData("relay", "リレー設定", "地域に基づいた最適なリレーを自動設定", "tools"),
            MiniAppData("upload", "アップロード設定", "画像のアップロード先サーバーを選択", "tools"),
            MiniAppData("elevenlabs", "音声入力設定", "ElevenLabs Scribeによる高精度な音声入力", "tools"),
            MiniAppData("backup", "バックアップ", "自分の投稿データをJSON形式でエクスポート", "tools"),
            MiniAppData("vanish", "削除リクエスト", "リレーに対して全データの削除を要求", "tools"),
            MiniAppData("cache", "キャッシュ設定", "キャッシュするkindと保持日数を管理", "tools")
        )
    }

    val externalApps = remember {
        mutableStateListOf<MiniAppData>().apply {
            try { addAll(Json.decodeFromString<List<MiniAppData>>(prefs.externalApps)) }
            catch (e: Exception) {}
        }
    }

    if (selectedApp != null) {
        MiniAppDetailView(
            app = selectedApp!!,
            repository = repository,
            pubkeyHex = pubkeyHex,
            prefs = prefs,
            onBack = { selectedApp = null },
            onMlsCacheCleared = onMlsCacheCleared
        )
        return
    }

    // 外部ミニアプリ 編集・削除 BottomSheet
    editingApp?.let { target ->
        ExternalAppEditSheet(
            app = target,
            onSave = { newName, newUrl ->
                val idx = externalApps.indexOfFirst { it.id == target.id }
                if (idx >= 0) {
                    externalApps[idx] = target.copy(
                        name = newName,
                        url = newUrl,
                        description = "外部ミニアプリ"
                    )
                    prefs.externalApps = Json.encodeToString(externalApps.toList())
                }
                editingApp = null
            },
            onDelete = {
                externalApps.removeAll { it.id == target.id }
                favorites.remove(target.id)
                prefs.externalApps = Json.encodeToString(externalApps.toList())
                prefs.favoriteApps = favorites.toList()
                editingApp = null
            },
            onDismiss = { editingApp = null }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Text(
                        "ミニアプリ",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val density = LocalDensity.current
        val headerHeightPx = remember { mutableFloatStateOf(0f) }
        val tabsHeightPx = remember { mutableFloatStateOf(0f) }
        val headerOffsetPx = remember { mutableFloatStateOf(0f) }
        val collapsingConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val delta = available.y
                    if (delta < 0f) {
                        val old = headerOffsetPx.floatValue
                        val new = (old + delta).coerceIn(-headerHeightPx.floatValue, 0f)
                        headerOffsetPx.floatValue = new
                        return Offset(0f, new - old)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val delta = available.y
                    if (delta > 0f) {
                        val old = headerOffsetPx.floatValue
                        val new = (old + delta).coerceIn(-headerHeightPx.floatValue, 0f)
                        headerOffsetPx.floatValue = new
                        return Offset(0f, new - old)
                    }
                    return Offset.Zero
                }
            }
        }
        val favoriteAppData = (allApps + externalApps).filter { favorites.contains(it.id) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .nestedScroll(collapsingConnection)
        ) {
            val contentTopPadding = with(density) {
                (headerHeightPx.floatValue + tabsHeightPx.floatValue + headerOffsetPx.floatValue).toDp()
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 1
            ) { page ->
                val activeCategory = categoryList[page].first
                val filteredApps = (allApps + externalApps).filter {
                    (activeCategory == "all" || it.category == activeCategory) &&
                    (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) || it.description.contains(searchQuery, ignoreCase = true))
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = contentTopPadding)
                ) {
                    items(filteredApps, key = { it.id }) { app ->
                        MiniAppRow(
                            app = app,
                            isFavorite = favorites.contains(app.id),
                            onToggleFavorite = {
                                if (favorites.contains(app.id)) favorites.remove(app.id) else favorites.add(app.id)
                                prefs.favoriteApps = favorites.toList()
                            },
                            onClick = { selectedApp = app },
                            onLongClick = if (app.type == "external") ({ editingApp = app }) else null
                        )
                    }
                    item {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                            if (showExternalAdd) {
                                var externalName by remember { mutableStateOf("") }
                                var externalUrl by remember { mutableStateOf("") }
                                Column(
                                    modifier = Modifier.background(nuruColors.bgSecondary, RoundedCornerShape(16.dp)).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedTextField(
                                        value = externalName,
                                        onValueChange = { externalName = it },
                                        placeholder = { Text("アプリ名 (任意)", fontSize = 14.sp) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = externalUrl,
                                            onValueChange = { externalUrl = it },
                                            placeholder = { Text("https://...", fontSize = 14.sp) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        Button(
                                            onClick = {
                                                if (externalUrl.startsWith("http")) {
                                                    val newApp = MiniAppData(
                                                        id = "external_${System.currentTimeMillis()}",
                                                        name = externalName.ifBlank { try { java.net.URL(externalUrl).host } catch(e:Exception) { "外部アプリ" } },
                                                        description = "外部ミニアプリ",
                                                        category = "others",
                                                        type = "external",
                                                        url = externalUrl
                                                    )
                                                    externalApps.add(newApp)
                                                    prefs.externalApps = Json.encodeToString(externalApps.toList())
                                                    showExternalAdd = false
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(56.dp)
                                        ) { Text("追加") }
                                    }
                                    TextButton(onClick = { showExternalAdd = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text("キャンセル", color = nuruColors.textTertiary, fontSize = 12.sp)
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .background(Color.Transparent)
                                        .border(2.dp, nuruColors.border, RoundedCornerShape(16.dp))
                                        .clickable { showExternalAdd = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Add, null, tint = nuruColors.textSecondary)
                                        Text("外部ミニアプリを追加", color = nuruColors.textSecondary, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { uriHandler.openUri("https://tami1A84.github.io/null--nostr/privacy.html") }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "プライバシーポリシー",
                                fontSize = 13.sp,
                                color = nuruColors.textTertiary,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            )
                        }
                    }
                }
            }

            // Header overlay — scrolls up with content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { headerHeightPx.floatValue = it.size.height.toFloat() }
                    .offset { IntOffset(0, headerOffsetPx.floatValue.roundToInt()) }
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        color = nuruColors.bgSecondary,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(color = LineGreen, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(NuruIcons.Lock, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (prefs.isExternalSigner) "外部署名でログイン中" else "ログイン中",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    npub.take(8) + "..." + npub.takeLast(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = nuruColors.textTertiary
                                )
                            }
                            Surface(
                                color = Color.Red.copy(alpha = 0.1f),
                                shape = CircleShape,
                                modifier = Modifier.clickable { showLogoutDialog = true }
                            ) {
                                Text(
                                    "ログアウト",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 12.sp,
                                    color = Color.Red,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    if (!prefs.isExternalSigner) {
                        SecuritySettingsSection(authViewModel = authViewModel, prefs = prefs, pubkeyHex = pubkeyHex)
                    }
                }
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("ミニアプリを検索", fontSize = 14.sp) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = nuruColors.textTertiary, modifier = Modifier.size(20.dp)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = nuruColors.bgSecondary,
                            unfocusedContainerColor = nuruColors.bgSecondary,
                            focusedBorderColor = LineGreen,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                if (favoriteAppData.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("マイミニアプリ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Icon(Icons.Outlined.ChevronRight, null, tint = nuruColors.textTertiary, modifier = Modifier.size(16.dp))
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(favoriteAppData) { app ->
                                Column(
                                    modifier = Modifier.width(64.dp).clickable { selectedApp = app },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        color = nuruColors.bgSecondary,
                                        shape = CircleShape,
                                        modifier = Modifier.size(56.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, nuruColors.border)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(app.getIcon(), null, tint = nuruColors.textSecondary, modifier = Modifier.size(28.dp))
                                        }
                                    }
                                    Text(
                                        app.name,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Category tabs overlay — follows header bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { tabsHeightPx.floatValue = it.size.height.toFloat() }
                    .offset { IntOffset(0, (headerHeightPx.floatValue + headerOffsetPx.floatValue).roundToInt()) }
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    categoryList.forEachIndexed { index, (_, label) ->
                        val isSelected = pagerState.currentPage == index
                        Column(
                            modifier = Modifier
                                .clickable { coroutineScope.launch { pagerState.animateScrollToPage(index) } }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                label,
                                color = if (isSelected) MaterialTheme.colorScheme.onBackground else nuruColors.textTertiary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .height(2.dp)
                                    .width(32.dp)
                                    .background(if (isSelected) MaterialTheme.colorScheme.onBackground else Color.Transparent)
                            )
                        }
                    }
                }
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            }
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("ログアウト") },
            text = { Text("ログアウトします。秘密鍵はこのデバイスから削除されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        authViewModel.logout()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("ログアウト") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("キャンセル") }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun SecuritySettingsSection(authViewModel: AuthViewModel, prefs: AppPreferences, pubkeyHex: String) {
    val nuruColors = LocalNuruColors.current
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var showNsec by remember { mutableStateOf(false) }
    var autoSignEnabled by remember { mutableStateOf(prefs.autoSignEnabled) }

    Surface(
        color = nuruColors.bgSecondary,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.Lock, null, tint = nuruColors.textSecondary, modifier = Modifier.size(20.dp))
                Text("セキュリティ設定", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Icon(
                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                    tint = nuruColors.textTertiary
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Auto Sign Toggle
                    Surface(
                        color = nuruColors.bgTertiary,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自動署名", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    if (autoSignEnabled) "投稿時に認証なし" else "毎回認証を要求",
                                    fontSize = 12.sp,
                                    color = nuruColors.textTertiary
                                )
                            }
                            Switch(
                                checked = autoSignEnabled,
                                onCheckedChange = {
                                    autoSignEnabled = it
                                    prefs.autoSignEnabled = it
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = LineGreen)
                            )
                        }
                    }

                    // Show Nsec Button
                    Button(
                        onClick = { showNsec = !showNsec },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = nuruColors.bgTertiary, contentColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (showNsec) "秘密鍵を隠す" else "秘密鍵を表示", fontSize = 14.sp)
                    }

                    if (showNsec) {
                        val nsec = remember(pubkeyHex) { authViewModel.getNsecTemporary() ?: "取得できません" }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = Color.Red.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("⚠️ 警告: 秘密鍵の取り扱い", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                    Text(
                                        "この鍵はあなたの身元を証明する唯一の手段です。他人に教えたり、安全でない場所に保存したりしないでください。",
                                        color = Color.Red.copy(alpha = 0.8f),
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                            Surface(
                                color = nuruColors.bgTertiary,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(text = nsec, modifier = Modifier.weight(1f), fontSize = 12.sp, color = nuruColors.textPrimary)
                                    IconButton(onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("nsec", nsec))
                                    }) {
                                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp), tint = nuruColors.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniAppRow(
    app: MiniAppData,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = nuruColors.bgSecondary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(app.getIcon(), null, tint = nuruColors.textSecondary, modifier = Modifier.size(28.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                val (badgeBg, badgeText, badgeFg) = when (app.category) {
                    "entertainment" -> Triple(Color(0xFFE1BEE7).copy(alpha = 0.2f), "エンタメ", Color(0xFF9C27B0))
                    "others"        -> Triple(Color(0xFFFFE0B2).copy(alpha = 0.2f), "その他",  Color(0xFFE65100))
                    else            -> Triple(Color(0xFFBBDEFB).copy(alpha = 0.2f), "ツール",   Color(0xFF2196F3))
                }
                Surface(color = badgeBg, shape = RoundedCornerShape(100.dp)) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = badgeFg
                    )
                }
            }
            Text(app.description, style = MaterialTheme.typography.bodySmall, color = nuruColors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = NuruIcons.Star(isFavorite),
                contentDescription = "お気に入り",
                tint = if (isFavorite) Color(0xFFFFD700) else nuruColors.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniAppDetailView(
    app: MiniAppData,
    repository: NostrRepository,
    pubkeyHex: String,
    prefs: AppPreferences,
    onBack: () -> Unit,
    onMlsCacheCleared: () -> Unit = {}
) {
    val nuruColors = LocalNuruColors.current
    val title = when(app.id) {
        "zap" -> "Zap設定"
        "relay" -> "リレー設定"
        "upload" -> "アップロード設定"
        "badge" -> "プロフィールバッジ"
        "emoji" -> "カスタム絵文字"
        "mute" -> "ミュートリスト"
        "elevenlabs" -> "音声入力設定"
        "backup" -> "バックアップ"
        "vanish" -> "削除リクエスト"
        "cache"  -> "キャッシュ設定"
        else -> app.name
    }

    // 外部アプリはトップバーなしでフルスクリーンWebViewで表示
    if (app.type == "external" && app.url != null) {
        NostrBrowserApp(
            appName = app.name,
            pubkey = pubkeyHex,
            repository = repository,
            initialUrl = app.url,
            onBack = onBack
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (app.id) {
                "zap" -> ZapSettings(prefs = prefs)
                "relay" -> RelaySettingsViewContent(prefs = prefs, repository = repository)
                "upload" -> UploadSettingsView(prefs = prefs)
                "badge" -> BadgeSettings(pubkey = pubkeyHex, repository = repository)
                "emoji" -> EmojiSettings(pubkey = pubkeyHex, repository = repository)
                "mute" -> MuteList(pubkey = pubkeyHex, repository = repository)
                "elevenlabs" -> ElevenLabsSettings(prefs = prefs)
                "backup" -> EventBackupSettings(pubkey = pubkeyHex, repository = repository)
                "vanish" -> VanishRequest(pubkey = pubkeyHex, repository = repository)
                "cache" -> CacheSettings(prefs = prefs, repository = repository, onMlsCacheCleared = onMlsCacheCleared)
                "scheduler" -> SchedulerApp(pubkey = pubkeyHex, repository = repository)
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Coming Soon", color = nuruColors.textTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadSettingsView(prefs: AppPreferences) {
    val nuruColors = LocalNuruColors.current
    var uploadServer by remember { mutableStateOf(prefs.uploadServer) }
    var customBlossomUrl by remember { mutableStateOf("") }
    val servers = listOf(
        "nostr.build" to "nostr.build",
        "やぶみ" to "share.yabu.me",
        "Blossom (nostr.build)" to "https://blossom.nostr.build"
    )

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = nuruColors.bgSecondary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("画像アップロード", fontWeight = FontWeight.Bold)
                Text("プロフィール画像のアップロード先", fontSize = 12.sp, color = nuruColors.textTertiary)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    servers.forEach { (name, url) ->
                        val isSelected = uploadServer == url
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(48.dp).clickable {
                                uploadServer = url
                                prefs.uploadServer = url
                            },
                            color = if (isSelected) LineGreen else nuruColors.bgTertiary,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(name, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                if (isSelected) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("カスタムBlossomサーバー", fontSize = 12.sp, color = nuruColors.textTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customBlossomUrl,
                            onValueChange = { customBlossomUrl = it },
                            placeholder = { Text("https://...") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LineGreen,
                                unfocusedBorderColor = nuruColors.border
                            )
                        )
                        Button(
                            onClick = {
                                val url = customBlossomUrl.trim()
                                if (url.startsWith("https://")) {
                                    uploadServer = url
                                    prefs.uploadServer = url
                                    customBlossomUrl = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(56.dp)
                        ) {
                            Text("設定")
                        }
                    }
                }
            }
        }
        // Uploaded images gallery
        val uploadedImages = remember { prefs.uploadedImages }
        if (uploadedImages.isNotEmpty()) {
            Surface(
                color = nuruColors.bgSecondary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("アップロード済み画像", fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(uploadedImages.size) { index ->
                            val url = uploadedImages[index]
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                coil.compose.AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(16.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                        .clickable {
                                            val newList = prefs.uploadedImages.toMutableList()
                                            newList.remove(url)
                                            prefs.uploadedImages = newList
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySettingsViewContent(prefs: AppPreferences, repository: NostrRepository) {
    val context = LocalContext.current
    val nuruColors = LocalNuruColors.current
    val coroutineScope = rememberCoroutineScope()

    var currentRelays by remember { mutableStateOf(prefs.nip65Relays) }
    var userGeohash by remember { mutableStateOf(prefs.userGeohash) }
    var selectedRegionId by remember { mutableStateOf(prefs.selectedRegionId) }
    var nearestRelays by remember { mutableStateOf<List<RelayInfoWithDistance>>(emptyList()) }
    var detectingLocation by remember { mutableStateOf(false) }
    var publishingNip65 by remember { mutableStateOf(false) }
    var loadingNip65 by remember { mutableStateOf(false) }
    var manualRelayUrl by remember { mutableStateOf("") }
    var mainRelayState by remember { mutableStateOf(prefs.mainRelay) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var newRelayRead by remember { mutableStateOf(true) }
    var newRelayWrite by remember { mutableStateOf(true) }

    val selectedRegion = remember(selectedRegionId) {
        RelayDiscovery.REGION_COORDINATES.find { it.id == selectedRegionId }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            detectingLocation = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val providers = locationManager.getProviders(true)
                    var bestLocation: Location? = null

                    for (provider in providers) {
                        try {
                            val l = locationManager.getLastKnownLocation(provider) ?: continue
                            if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                                bestLocation = l
                            }
                        } catch (e: SecurityException) {
                            // Should not happen as we just got permission
                        }
                    }

                    if (bestLocation != null) {
                        val lat = bestLocation.latitude
                        val lon = bestLocation.longitude
                        val geohash = GeohashUtils.encodeGeohash(lat, lon)
                        val config = RelayDiscovery.generateRelayListByLocation(lat, lon)

                        withContext(Dispatchers.Main) {
                            prefs.userLat = lat
                            prefs.userLon = lon
                            prefs.userGeohash = geohash
                            prefs.selectedRegionId = null
                            prefs.nip65Relays = config.combined
                            val newMain = config.combined.firstOrNull { it.read && it.write }?.url
                                ?: config.combined.firstOrNull()?.url ?: "wss://yabu.me"
                            prefs.mainRelay = newMain

                            userGeohash = geohash
                            selectedRegionId = null
                            currentRelays = config.combined
                            mainRelayState = newMain
                            nearestRelays = config.outbox
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "位置情報を取得できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "位置情報の取得に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        detectingLocation = false
                    }
                }
            }
        } else {
            Toast.makeText(context, "位置情報の権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleSelectRegion(regionId: String) {
        val region = RelayDiscovery.REGION_COORDINATES.find { it.id == regionId } ?: return
        val config = if (regionId == "global") {
            val globalRelays = RelayDiscovery.GPS_RELAY_DATABASE
                .filter { it.priority == 1 }
                .take(10)
                .map { RelayInfoWithDistance(it, 0.0) }
            Nip65Config(
                inbox = globalRelays.take(4),
                outbox = globalRelays.take(5),
                discover = RelayDiscovery.DIRECTORY_RELAYS,
                combined = globalRelays.take(5).map { Nip65Relay(it.info.url, true, true) }
            )
        } else {
            RelayDiscovery.generateRelayListByLocation(region.lat, region.lon)
        }

        prefs.selectedRegionId = regionId
        prefs.userLat = region.lat
        prefs.userLon = region.lon
        prefs.userGeohash = if (regionId == "global") "global" else GeohashUtils.encodeGeohash(region.lat, region.lon)
        prefs.nip65Relays = config.combined
        val newMain = config.combined.firstOrNull { it.read && it.write }?.url
            ?: config.combined.firstOrNull()?.url ?: "wss://yabu.me"
        prefs.mainRelay = newMain

        selectedRegionId = regionId
        userGeohash = prefs.userGeohash
        currentRelays = config.combined
        mainRelayState = newMain
        nearestRelays = config.outbox
    }

    LaunchedEffect(Unit) {
        if (selectedRegionId != null) {
            val region = RelayDiscovery.REGION_COORDINATES.find { it.id == selectedRegionId }
            if (region != null) {
                val config = if (selectedRegionId == "global") {
                    val globalRelays = RelayDiscovery.GPS_RELAY_DATABASE.filter { it.priority == 1 }.take(10).map { RelayInfoWithDistance(it, 0.0) }
                    Nip65Config(globalRelays.take(4), globalRelays.take(5), RelayDiscovery.DIRECTORY_RELAYS, globalRelays.take(5).map { Nip65Relay(it.info.url, true, true) })
                } else {
                    RelayDiscovery.generateRelayListByLocation(region.lat, region.lon)
                }
                nearestRelays = config.outbox
            }
        } else if (prefs.userLat != 0.0) {
            val config = RelayDiscovery.generateRelayListByLocation(prefs.userLat, prefs.userLon)
            nearestRelays = config.outbox
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                color = nuruColors.bgSecondary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "リレー設定",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Surface(
                        color = LineGreen.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LineGreen),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("現在の設定", fontSize = 10.sp, color = nuruColors.textTertiary)
                            Text(
                                if (selectedRegion != null) selectedRegion.name else if (userGeohash != null) "GPS検出" else "未設定",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = nuruColors.textPrimary
                            )
                            Text(
                                "おすすめリレー: ${mainRelayState.replace("wss://", "")}",
                                fontSize = 10.sp,
                                color = nuruColors.textTertiary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("地域を選択", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nuruColors.textSecondary)

                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(
                                onClick = { expanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = nuruColors.textPrimary)
                            ) {
                                Text(selectedRegion?.name ?: "地域を選択...")
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Outlined.ArrowDropDown, null)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 400.dp)
                            ) {
                                val groupedRegions = RelayDiscovery.REGION_COORDINATES.groupBy {
                                    when (it.country) {
                                        "JP" -> "日本"
                                        "SG", "TW", "KR", "CN", "IN" -> "アジア"
                                        "US", "CA" -> "北米"
                                        "EU", "UK" -> "ヨーロッパ"
                                        else -> "その他"
                                    }
                                }

                                groupedRegions.forEach { (group, regions) ->
                                    DropdownMenuItem(
                                        text = { Text(group, fontWeight = FontWeight.Bold, color = nuruColors.textTertiary, fontSize = 12.sp) },
                                        onClick = {},
                                        enabled = false
                                    )
                                    regions.forEach { region ->
                                        DropdownMenuItem(
                                            text = { Text(region.name) },
                                            onClick = {
                                                handleSelectRegion(region.id)
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                permissionLauncher.launch(arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ))
                            },
                            enabled = !detectingLocation,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = nuruColors.bgTertiary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (detectingLocation) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = nuruColors.textSecondary)
                                Spacer(Modifier.width(8.dp))
                                Text("位置情報を取得中...", fontSize = 12.sp, color = nuruColors.textSecondary)
                            } else {
                                Icon(Icons.Outlined.MyLocation, null, modifier = Modifier.size(16.dp), tint = nuruColors.textSecondary)
                                Spacer(Modifier.width(8.dp))
                                Text("GPSで自動検出", fontSize = 12.sp, color = nuruColors.textSecondary)
                            }
                        }
                    }

                    if (nearestRelays.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            HorizontalDivider(color = nuruColors.border, modifier = Modifier.padding(vertical = 12.dp))
                            Text("最寄りのリレー", fontSize = 10.sp, color = nuruColors.textTertiary,
                                modifier = Modifier.padding(bottom = 4.dp))

                            nearestRelays.take(5).forEach { relay ->
                                val isInNip65 = currentRelays.any { it.url == relay.info.url }
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    color = nuruColors.bgTertiary,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                relay.info.name,
                                                fontSize = 12.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                                color = nuruColors.textPrimary
                                            )
                                            Text(
                                                "${relay.info.region} · ${RelayDiscovery.formatDistance(relay.distance)}",
                                                fontSize = 10.sp,
                                                color = nuruColors.textTertiary
                                            )
                                        }
                                        androidx.compose.material3.Checkbox(
                                            checked = isInNip65,
                                            onCheckedChange = { checked ->
                                                val newList = if (checked) {
                                                    val newRelay = Nip65Relay(relay.info.url, true, true)
                                                    (listOf(newRelay) + currentRelays.filter { it.url != relay.info.url }).take(5)
                                                } else {
                                                    currentRelays.filter { it.url != relay.info.url }
                                                }
                                                currentRelays = newList
                                                prefs.nip65Relays = newList
                                            },
                                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                                checkedColor = nuruColors.textSecondary.copy(alpha = 0.8f)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 保存済みリレー一覧
                    if (currentRelays.isNotEmpty()) {
                        HorizontalDivider(color = nuruColors.border, modifier = Modifier.padding(vertical = 8.dp))
                        Text("保存済みリレー", fontSize = 10.sp, color = nuruColors.textTertiary,
                            modifier = Modifier.padding(bottom = 4.dp))
                        currentRelays.forEach { relay ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                color = nuruColors.bgTertiary,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            relay.url.replace("wss://", ""),
                                            fontSize = 12.sp,
                                            color = nuruColors.textPrimary
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 2.dp)
                                        ) {
                                            if (relay.read) {
                                                Surface(
                                                    color = LineGreen.copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text("read", fontSize = 10.sp, color = LineGreen,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                }
                                            }
                                            if (relay.write) {
                                                Surface(
                                                    color = Color(0xFF9C27B0).copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text("write", fontSize = 10.sp, color = Color(0xFF9C27B0),
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                                                }
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val newList = currentRelays.filter { it.url != relay.url }
                                            currentRelays = newList
                                            prefs.nip65Relays = newList
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = nuruColors.textTertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                publishingNip65 = true
                                coroutineScope.launch {
                                    val triples = currentRelays.map { Triple(it.url, it.read, it.write) }
                                    val success = repository.updateRelayList(triples)
                                    if (success) {
                                        Toast.makeText(context, "リレーリストを発行しました", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "発行に失敗しました", Toast.LENGTH_SHORT).show()
                                    }
                                    publishingNip65 = false
                                }
                            },
                            enabled = !publishingNip65,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (publishingNip65) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                            } else {
                                Text("リレーリストを発行", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // ── 高度な設定（折りたたみ）────────────────────────────────
                    HorizontalDivider(color = nuruColors.border, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedExpanded = !advancedExpanded }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "高度な設定",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = nuruColors.textSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = nuruColors.textTertiary
                        )
                    }

                    AnimatedVisibility(visible = advancedExpanded) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            // NIP-65 説明
                            Surface(
                                color = nuruColors.bgTertiary,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "アウトボックスモデル (NIP-65)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = nuruColors.textPrimary
                                    )
                                    Text(
                                        "read: 受信用リレー。他のユーザーがあなた宛のメンションをここに送信します。\n" +
                                        "write: 送信用リレー。あなたの投稿がここに発行されます。",
                                        fontSize = 11.sp,
                                        color = nuruColors.textSecondary,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            // 自分のリレーリストを読み込む
                            Button(
                                onClick = {
                                    loadingNip65 = true
                                    coroutineScope.launch {
                                        val pubkey = prefs.publicKeyHex
                                        if (pubkey != null) {
                                            val relays = try { repository.fetchNip65WriteRelays(pubkey) } catch (_: Exception) { emptyList() }
                                            if (relays.isNotEmpty()) {
                                                val newList = relays.take(10).map { Nip65Relay(it, true, true) }
                                                currentRelays = newList
                                                prefs.nip65Relays = newList
                                                Toast.makeText(context, "${newList.size}件のリレーを読み込みました", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "リレーリストが見つかりませんでした", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        loadingNip65 = false
                                    }
                                },
                                enabled = !loadingNip65,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = nuruColors.bgTertiary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (loadingNip65) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = nuruColors.textSecondary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("自分のリレーリストを読み込む", fontSize = 12.sp, color = nuruColors.textSecondary)
                            }

                            // リレーごとの read/write 詳細設定
                            if (currentRelays.isNotEmpty()) {
                                Text(
                                    "リレー詳細設定",
                                    fontSize = 10.sp,
                                    color = nuruColors.textTertiary
                                )
                                currentRelays.forEachIndexed { index, relay ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = nuruColors.bgTertiary,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Text(
                                                relay.url.replace("wss://", ""),
                                                fontSize = 12.sp,
                                                color = nuruColors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                Switch(
                                                    checked = relay.read,
                                                    onCheckedChange = { checked ->
                                                        val updated = currentRelays.toMutableList()
                                                        updated[index] = relay.copy(read = checked)
                                                        currentRelays = updated
                                                        prefs.nip65Relays = updated
                                                    }
                                                )
                                                Text(
                                                    "read",
                                                    fontSize = 12.sp,
                                                    color = if (relay.read) nuruColors.textPrimary else nuruColors.textTertiary,
                                                    modifier = Modifier.padding(start = 6.dp)
                                                )
                                                Spacer(Modifier.width(20.dp))
                                                Switch(
                                                    checked = relay.write,
                                                    onCheckedChange = { checked ->
                                                        val updated = currentRelays.toMutableList()
                                                        updated[index] = relay.copy(write = checked)
                                                        currentRelays = updated
                                                        prefs.nip65Relays = updated
                                                    }
                                                )
                                                Text(
                                                    "write",
                                                    fontSize = 12.sp,
                                                    color = if (relay.write) nuruColors.textPrimary else nuruColors.textTertiary,
                                                    modifier = Modifier.padding(start = 6.dp).weight(1f)
                                                )
                                                IconButton(
                                                    onClick = {
                                                        val newList = currentRelays.filter { it.url != relay.url }
                                                        currentRelays = newList
                                                        prefs.nip65Relays = newList
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete, null,
                                                        tint = nuruColors.textTertiary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // リレーを追加（read/write 指定付き）
                            Text(
                                "リレーを追加",
                                fontSize = 10.sp,
                                color = nuruColors.textTertiary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            OutlinedTextField(
                                value = manualRelayUrl,
                                onValueChange = { manualRelayUrl = it },
                                placeholder = { Text("wss://relay.example.com", fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Switch(
                                    checked = newRelayRead,
                                    onCheckedChange = { newRelayRead = it }
                                )
                                Text("read", fontSize = 12.sp, color = nuruColors.textSecondary)
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = newRelayWrite,
                                    onCheckedChange = { newRelayWrite = it }
                                )
                                Text(
                                    "write",
                                    fontSize = 12.sp,
                                    color = nuruColors.textSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        val url = manualRelayUrl.trim()
                                        if ((url.startsWith("wss://") || url.startsWith("ws://"))
                                            && (newRelayRead || newRelayWrite)) {
                                            val newRelay = Nip65Relay(url, newRelayRead, newRelayWrite)
                                            val newList = (listOf(newRelay) + currentRelays.filter { it.url != url }).take(10)
                                            currentRelays = newList
                                            prefs.nip65Relays = newList
                                            manualRelayUrl = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("追加", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExternalAppEditSheet(
    app: MiniAppData,
    onSave: (name: String, url: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    var name by remember(app.id) { mutableStateOf(app.name) }
    var url by remember(app.id) { mutableStateOf(app.url ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = nuruColors.bgPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(nuruColors.border, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("外部ミニアプリを編集", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("アプリ名") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LineGreen,
                    unfocusedBorderColor = nuruColors.border
                )
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                placeholder = { Text("https://...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LineGreen,
                    unfocusedBorderColor = nuruColors.border
                )
            )

            Button(
                onClick = {
                    val trimmedUrl = url.trim()
                    if (trimmedUrl.startsWith("http")) {
                        onSave(
                            name.ifBlank { try { java.net.URL(trimmedUrl).host } catch (e: Exception) { "外部アプリ" } },
                            trimmedUrl
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
            }

            if (showDeleteConfirm) {
                Surface(
                    color = Color.Red.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "「${app.name}」を削除しますか？",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showDeleteConfirm = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("キャンセル") }
                            Button(
                                onClick = onDelete,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("削除", color = Color.White) }
                        }
                    }
                }
            } else {
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("このミニアプリを削除", color = Color.Red, fontSize = 14.sp)
                }
            }
        }
    }
}
