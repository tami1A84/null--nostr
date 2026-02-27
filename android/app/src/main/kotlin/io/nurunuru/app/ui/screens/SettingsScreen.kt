package io.nurunuru.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.DEFAULT_RELAYS
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.miniapps.BadgeSettings
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.AuthViewModel
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

    val allApps = listOf(
        MiniAppData("emoji", "カスタム絵文字", "投稿やリアクションに使える絵文字を管理・追加", "entertainment"),
        MiniAppData("badge", "プロフィールバッジ", "プロフィールに表示するバッジを設定・管理", "entertainment"),
        MiniAppData("scheduler", "調整くん", "オフ会や会議の予定を簡単に調整", "entertainment"),
        MiniAppData("zap", "Zap設定", "デフォルトのZap金額をクイック設定", "tools"),
        MiniAppData("relay", "リレー設定", "地域に基づいた最適なリレーを自動設定", "tools"),
        MiniAppData("upload", "アップロード設定", "画像のアップロード先サーバーを選択", "tools"),
        MiniAppData("mute", "ミュートリスト", "不快なユーザーやキーワードを非表示に管理", "tools"),
        MiniAppData("elevenlabs", "音声入力設定", "ElevenLabs Scribeによる高精度な音声入力", "tools"),
        MiniAppData("backup", "バックアップ", "自分の投稿データをJSON形式でエクスポート", "tools"),
        MiniAppData("vanish", "削除リクエスト", "リレーに対して全データの削除を要求", "tools")
    )

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
                "relay" -> RelaySettingsViewContent(prefs = prefs)
                "upload" -> UploadSettingsView(prefs = prefs)
                "badge" -> BadgeSettings(pubkey = pubkeyHex, repository = repository)
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
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (customBlossomUrl.startsWith("https://")) {
                                    uploadServer = customBlossomUrl
                                    prefs.uploadServer = customBlossomUrl
                                    customBlossomUrl = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                            shape = RoundedCornerShape(12.dp)
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

@Composable
private fun RelaySettingsViewContent(prefs: AppPreferences) {
    val nuruColors = LocalNuruColors.current
    var relays by remember { mutableStateOf(prefs.relays.toList()) }
    var newRelayInput by remember { mutableStateOf("") }

    LazyColumn {
        items(relays) { relay ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(relay, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    relays = relays - relay
                    prefs.relays = relays.toSet()
                }) {
                    Icon(Icons.Default.Delete, null, tint = nuruColors.textTertiary)
                }
            }
            HorizontalDivider(color = nuruColors.border)
        }
        item {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRelayInput,
                    onValueChange = { newRelayInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("wss://...") },
                    singleLine = true
                )
                IconButton(onClick = {
                    if (newRelayInput.isNotBlank()) {
                        relays = relays + newRelayInput
                        prefs.relays = relays.toSet()
                        newRelayInput = ""
                    }
                }) {
                    Icon(Icons.Default.Add, null, tint = LineGreen)
                }
            }
            TextButton(
                onClick = {
                    relays = DEFAULT_RELAYS
                    prefs.relays = DEFAULT_RELAYS.toSet()
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("デフォルトに戻す", color = nuruColors.textTertiary)
            }
        }
    }
}
