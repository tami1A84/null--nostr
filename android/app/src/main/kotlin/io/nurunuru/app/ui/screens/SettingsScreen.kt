package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.DEFAULT_RELAYS
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.AuthViewModel

data class MiniAppData(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    prefs: AppPreferences,
    pubkeyHex: String,
    pictureUrl: String?
) {
    val nuruColors = LocalNuruColors.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    var activeCategory by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var showRelaySettings by remember { mutableStateOf(false) }

    val npub = remember(pubkeyHex) { NostrKeyUtils.encodeNpub(pubkeyHex) ?: pubkeyHex }

    if (showRelaySettings) {
        RelaySettingsView(prefs = prefs, onBack = { showRelaySettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Text(
                        "ミニアプリ",
                        fontWeight = FontWeight.SemiBold,
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Login Status Section
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UserAvatar(pictureUrl = pictureUrl, displayName = "", size = 42.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ログイン中", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(npub.take(8) + "..." + npub.takeLast(8), style = MaterialTheme.typography.bodySmall, color = nuruColors.textTertiary)
                        }
                        TextButton(
                            onClick = { showLogoutDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("ログアウト", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("ミニアプリを検索") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = nuruColors.textTertiary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = LineGreen
                    )
                )
            }

            // Category Tabs
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "すべて", "entertainment" to "エンタメ", "tools" to "ツール").forEach { (id, label) ->
                        FilterChip(
                            selected = activeCategory == id,
                            onClick = { activeCategory = id },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LineGreen,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }

            // App List
            val apps = listOf(
                MiniAppData("emoji", "カスタム絵文字", "投稿やリアクションに使える絵文字を管理", "entertainment", Icons.Outlined.EmojiEmotions),
                MiniAppData("badge", "プロフィールバッジ", "プロフィールに表示するバッジを設定", "entertainment", Icons.Outlined.Badge),
                MiniAppData("scheduler", "調整くん", "オフ会や会議の予定を簡単に調整", "entertainment", Icons.Outlined.CalendarMonth),
                MiniAppData("zap", "Zap設定", "デフォルトのZap金額をクイック設定", "tools", Icons.Default.Bolt),
                MiniAppData("relay", "リレー設定", "最適なリレーを自動設定", "tools", Icons.Outlined.Language),
                MiniAppData("upload", "アップロード設定", "画像のアップロード先サーバーを選択", "tools", Icons.Outlined.CloudUpload),
                MiniAppData("mute", "ミュートリスト", "不快なユーザーを非表示に管理", "tools", Icons.Outlined.Block),
                MiniAppData("elevenlabs", "音声入力設定", "高精度な音声入力の設定", "tools", Icons.Outlined.Mic),
                MiniAppData("backup", "バックアップ", "投稿データをエクスポート", "tools", Icons.Outlined.Backup),
                MiniAppData("vanish", "削除リクエスト", "データの削除を要求", "tools", Icons.Outlined.DeleteForever)
            ).filter {
                (activeCategory == "all" || it.category == activeCategory) &&
                (searchQuery.isBlank() || it.name.contains(searchQuery) || it.description.contains(searchQuery))
            }

            items(apps) { app ->
                MiniAppRow(app = app) {
                    if (app.id == "relay") {
                        showRelaySettings = true
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaySettingsView(prefs: AppPreferences, onBack: () -> Unit) {
    val nuruColors = LocalNuruColors.current
    var relays by remember { mutableStateOf(prefs.relays.toList()) }
    var newRelayInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("リレー設定", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
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
}

@Composable
private fun MiniAppRow(app: MiniAppData, onClick: () -> Unit) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(app.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
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
            Text(app.description, style = MaterialTheme.typography.bodySmall, color = nuruColors.textTertiary)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = nuruColors.textTertiary)
    }
}
