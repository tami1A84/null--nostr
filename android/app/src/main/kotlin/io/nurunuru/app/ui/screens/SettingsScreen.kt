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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.location.Location
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
        "zap" -> NuruIcons.Zap(false)
        "relay" -> NuruIcons.Relay
        "upload" -> NuruIcons.Image
        "mute" -> NuruIcons.Block
        "elevenlabs" -> NuruIcons.Mic
        "backup" -> NuruIcons.Backup
        "vanish" -> NuruIcons.Trash
        else -> if (type == "external") Icons.Outlined.OpenInNew else Icons.Outlined.Extension
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    repository: NostrRepository,
    prefs: AppPreferences,
    pubkeyHex: String,
    pictureUrl: String?
) {
    val nuruColors = LocalNuruColors.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    var activeCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedAppId by remember { mutableStateOf<String?>(null) }
    var showExternalAdd by remember { mutableStateOf(false) }

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
            MiniAppData("vanish", "削除リクエスト", "リレーに対して全データの削除を要求", "tools")
        )
    }

    val externalAppsJson = prefs.externalApps
    val externalApps = remember(externalAppsJson) {
        try {
            Json.decodeFromString<List<MiniAppData>>(externalAppsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    if (selectedAppId != null) {
        MiniAppDetailView(
            appId = selectedAppId!!,
            repository = repository,
            pubkeyHex = pubkeyHex,
            prefs = prefs,
            onBack = { selectedAppId = null }
        )
        return
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Login Status Section
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                            Surface(
                                color = LineGreen,
                                shape = CircleShape,
                                modifier = Modifier.size(40.dp)
                            ) {
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

                    // Passkey Settings (only for internal signer)
                    if (!prefs.isExternalSigner) {
                        PasskeySettingsSection(prefs = prefs, pubkeyHex = pubkeyHex)
                    }
                }
            }

            // Search Bar
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
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
            }

            // Favorites Section
            val favoriteAppData = (allApps + externalApps).filter { favorites.contains(it.id) }
            if (favoriteAppData.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("履歴・おすすめ", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Icon(Icons.Outlined.ChevronRight, null, tint = nuruColors.textTertiary, modifier = Modifier.size(16.dp))
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(favoriteAppData) { app ->
                                Column(
                                    modifier = Modifier.width(64.dp).clickable { selectedAppId = app.id },
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

            // Category Tabs
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        listOf("all" to "すべて", "entertainment" to "エンタメ", "tools" to "ツール").forEach { (id, label) ->
                            val isSelected = activeCategory == id
                            Column(
                                modifier = Modifier
                                    .clickable { activeCategory = id }
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

            // App List
            val filteredApps = (allApps + externalApps).filter {
                (activeCategory == "all" || it.category == activeCategory) &&
                (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) || it.description.contains(searchQuery, ignoreCase = true))
            }

            items(filteredApps) { app ->
                MiniAppRow(
                    app = app,
                    isFavorite = favorites.contains(app.id),
                    onToggleFavorite = {
                        if (favorites.contains(app.id)) {
                            favorites.remove(app.id)
                        } else {
                            favorites.add(app.id)
                        }
                        prefs.favoriteApps = favorites.toList()
                    },
                    onClick = { selectedAppId = app.id }
                )
            }

            // External App Add Button
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
                                                category = "tools",
                                                type = "external",
                                                url = externalUrl
                                            )
                                            val newList = externalApps + newApp
                                            prefs.externalApps = Json.encodeToString(newList)
                                            showExternalAdd = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(56.dp)
                                ) {
                                    Text("追加")
                                }
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
private fun PasskeySettingsSection(prefs: AppPreferences, pubkeyHex: String) {
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
                Text("パスキー設定", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                        val nsec = remember(pubkeyHex) { prefs.privateKeyHex?.let { NostrKeyUtils.encodeNsec(it) } ?: "取得できません" }
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
                                    Text(nsec, modifier = Modifier.weight(1f), fontSize = 12.sp, color = nuruColors.textPrimary)
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

@Composable
private fun MiniAppRow(
    app: MiniAppData,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
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
                Surface(
                    color = if (app.category == "entertainment") Color(0xFFE1BEE7).copy(alpha = 0.2f) else Color(0xFFBBDEFB).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        if (app.category == "entertainment") "エンタメ" else "ツール",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = if (app.category == "entertainment") Color(0xFF9C27B0) else Color(0xFF2196F3)
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
    appId: String,
    repository: NostrRepository,
    pubkeyHex: String,
    prefs: AppPreferences,
    onBack: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val title = when(appId) {
        "zap" -> "Zap設定"
        "relay" -> "リレー設定"
        "upload" -> "アップロード設定"
        "badge" -> "プロフィールバッジ"
        "emoji" -> "カスタム絵文字"
        "mute" -> "ミュートリスト"
        "elevenlabs" -> "音声入力設定"
        "backup" -> "バックアップ"
        "vanish" -> "削除リクエスト"
        else -> "ミニアプリ"
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
            when (appId) {
                "zap" -> ZapSettingsView(prefs = prefs)
                "relay" -> RelaySettingsViewContent(prefs = prefs, repository = repository)
                "upload" -> UploadSettingsView(prefs = prefs)
                "badge" -> BadgeSettings(pubkey = pubkeyHex, repository = repository)
                "emoji" -> EmojiSettings(pubkey = pubkeyHex, repository = repository)
                "mute" -> MuteList(pubkey = pubkeyHex, repository = repository)
                "elevenlabs" -> ElevenLabsSettings(prefs = prefs)
                "backup" -> EventBackupSettings(pubkey = pubkeyHex, repository = repository)
                "vanish" -> VanishRequest(pubkey = pubkeyHex, repository = repository)
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
private fun ZapSettingsView(prefs: AppPreferences) {
    val nuruColors = LocalNuruColors.current
    var defaultZap by remember { mutableStateOf(prefs.defaultZapAmount) }
    var showZapInput by remember { mutableStateOf(false) }
    var customZap by remember { mutableStateOf("") }
    val presets = listOf(21, 100, 500, 1000, 5000, 10000)

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = nuruColors.bgSecondary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("デフォルトZap金額", fontWeight = FontWeight.Bold)

                // Grid of presets
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rows = presets.chunked(3)
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { amount ->
                                val isSelected = defaultZap == amount
                                Surface(
                                    modifier = Modifier.weight(1f).height(48.dp).clickable {
                                        defaultZap = amount
                                        prefs.defaultZapAmount = amount
                                        showZapInput = false
                                    },
                                    color = if (isSelected) LineGreen else nuruColors.bgTertiary,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(amount.toString(), color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                }

                if (showZapInput) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customZap,
                            onValueChange = { customZap = it },
                            placeholder = { Text("金額") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                customZap.toIntOrNull()?.let {
                                    defaultZap = it
                                    prefs.defaultZapAmount = it
                                    showZapInput = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("設定")
                        }
                    }
                } else {
                    TextButton(onClick = { showZapInput = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("カスタム金額を設定", color = LineGreen)
                    }
                }
            }
        }
        Text("現在の設定: $defaultZap sats", fontSize = 12.sp, color = nuruColors.textTertiary, modifier = Modifier.padding(horizontal = 8.dp))
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
        Text("現在: $uploadServer", fontSize = 12.sp, color = nuruColors.textTertiary, modifier = Modifier.padding(horizontal = 8.dp))
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

                            userGeohash = geohash
                            selectedRegionId = null
                            currentRelays = config.combined
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

        selectedRegionId = regionId
        userGeohash = prefs.userGeohash
        currentRelays = config.combined
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
                            if (currentRelays.isNotEmpty()) {
                                Text(
                                    "メインリレー: ${currentRelays.first().url.replace("wss://", "")}",
                                    fontSize = 10.sp,
                                    color = nuruColors.textTertiary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
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
                            Text("最寄りのリレー:", fontSize = 10.sp, color = nuruColors.textTertiary, modifier = Modifier.padding(bottom = 8.dp))

                            nearestRelays.take(5).forEach { relay ->
                                val isSelected = currentRelays.any { it.url == relay.info.url }
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        val newRelay = Nip65Relay(relay.info.url, true, true)
                                        val newList = listOf(newRelay) + currentRelays.filter { it.url != relay.info.url }.take(4)
                                        currentRelays = newList
                                        prefs.nip65Relays = newList
                                    },
                                    color = if (isSelected) LineGreen else nuruColors.bgTertiary,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${relay.info.name} (${relay.info.region})",
                                            fontSize = 12.sp,
                                            color = if (isSelected) Color.White else nuruColors.textPrimary
                                        )
                                        Text(
                                            RelayDiscovery.formatDistance(relay.distance),
                                            fontSize = 10.sp,
                                            color = if (isSelected) Color.White.copy(alpha = 0.7f) else nuruColors.textTertiary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (currentRelays.isNotEmpty()) {
                        Button(
                            onClick = {
                                publishingNip65 = true
                                coroutineScope.launch {
                                    val triples = currentRelays.map { Triple(it.url, it.read, it.write) }
                                    val success = repository.updateRelayList(triples)
                                    if (success) {
                                        Toast.makeText(context, "リレーリストを発行しました (NIP-65)", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "発行に失敗しました", Toast.LENGTH_SHORT).show()
                                    }
                                    publishingNip65 = false
                                }
                            },
                            enabled = !publishingNip65,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)), // Purple
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (publishingNip65) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                            } else {
                                Text("リレーリストを発行 (NIP-65)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
