package io.nurunuru.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.material.icons.outlined.Settings
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.data.models.NotificationItem
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ───────────────────────────────────────────────────────────────────────────
// 通知タイプごとの見た目設定
// ───────────────────────────────────────────────────────────────────────────
private data class NotifStyle(
    val icon: ImageVector,
    val color: Color,
    val label: String
)

private val reactionStyle      = NotifStyle(Icons.Default.Favorite, Color(0xFFEF5350), "リアクション")
private val emojiReactionStyle = NotifStyle(Icons.Default.EmojiEmotions, Color(0xFFFF9800), "絵文字リアクション")
private val zapStyle           = NotifStyle(Icons.Default.ElectricBolt, Color(0xFFFFC107), "Zap")
private val repostStyle        = NotifStyle(Icons.Default.Repeat, Color(0xFF26A69A), "リポスト")
private val replyStyle         = NotifStyle(Icons.AutoMirrored.Filled.Reply, LineGreen, "返信")
private val mentionStyle       = NotifStyle(Icons.Default.AlternateEmail, Color(0xFF42A5F5), "メンション")
private val badgeStyle         = NotifStyle(Icons.Default.EmojiEvents, Color(0xFFFFD700), "バッジ")
private val birthdayStyle      = NotifStyle(Icons.Default.Cake, Color(0xFFAB47BC), "誕生日")

private fun styleFor(type: String) = when (type) {
    "reaction"       -> reactionStyle
    "emoji_reaction" -> emojiReactionStyle
    "zap"            -> zapStyle
    "repost"         -> repostStyle
    "reply"          -> replyStyle
    "mention"        -> mentionStyle
    "badge"          -> badgeStyle
    else             -> birthdayStyle
}

// ───────────────────────────────────────────────────────────────────────────
// メインモーダル
// ───────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationModal(
    repository: NostrRepository,
    prefs: AppPreferences,
    myPubkey: String,
    onClose: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var notifications by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, UserProfile>>(emptyMap()) }
    var originalPosts by remember { mutableStateOf<Map<String, NostrEvent>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var pendingNew by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var showKindSettings by remember { mutableStateOf(false) }
    var enabledKinds by remember { mutableStateOf(prefs.notificationEnabledKinds) }
    var emojiReactionEnabled by remember { mutableStateOf(prefs.notificationEmojiReactionEnabled) }

    // 初回フェッチ + ライブポーリング（10秒ごと）
    LaunchedEffect(Unit) {
        try {
            val result = repository.fetchNotifications(myPubkey)
            notifications = result.items
            profiles = result.profiles
            originalPosts = result.originalPosts
        } catch (e: Exception) {
            android.util.Log.w("NotificationModal", "fetch failed: ${e.message}")
        }
        loading = false

        while (true) {
            delay(10_000)
            try {
                val fresh = repository.fetchNotifications(myPubkey)
                val existingIds = notifications.map { it.id }.toSet()
                val newItems = fresh.items.filter { it.id !in existingIds }
                if (newItems.isNotEmpty()) {
                    pendingNew = (newItems + pendingNew).distinctBy { it.id }
                    profiles = fresh.profiles
                    originalPosts = fresh.originalPosts
                }
            } catch (_: Exception) { }
        }
    }

    // 通知kind設定ダイアログ
    if (showKindSettings) {
        AlertDialog(
            onDismissRequest = { showKindSettings = false },
            title = { Text("通知の種類", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                    // リアクション (kind 7, content "+" or "-")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        Text("リアクション (👍)", fontSize = 15.sp)
                        Switch(
                            checked = NostrKind.REACTION in enabledKinds,
                            onCheckedChange = { checked ->
                                val updated = if (checked) enabledKinds + NostrKind.REACTION else enabledKinds - NostrKind.REACTION
                                enabledKinds = updated
                                prefs.notificationEnabledKinds = updated
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LineGreen)
                        )
                    }
                    // 絵文字リアクション (kind 7, custom emoji)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        Text("絵文字リアクション", fontSize = 15.sp)
                        Switch(
                            checked = emojiReactionEnabled,
                            onCheckedChange = { checked ->
                                emojiReactionEnabled = checked
                                prefs.notificationEmojiReactionEnabled = checked
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LineGreen)
                        )
                    }
                    // Zap / リポスト / 返信 / バッジ
                    listOf(
                        Triple("Zap", NostrKind.ZAP_RECEIPT, zapStyle.color),
                        Triple("リポスト", NostrKind.REPOST, repostStyle.color),
                        Triple("返信・メンション", NostrKind.TEXT_NOTE, replyStyle.color),
                        Triple("バッジ", NostrKind.BADGE_AWARD, badgeStyle.color)
                    ).forEach { (label, kind, _) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                        ) {
                            Text(label, fontSize = 15.sp)
                            Switch(
                                checked = kind in enabledKinds,
                                onCheckedChange = { checked ->
                                    val updated = if (checked) enabledKinds + kind else enabledKinds - kind
                                    enabledKinds = updated
                                    prefs.notificationEnabledKinds = updated
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LineGreen)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKindSettings = false }) { Text("閉じる") }
            }
        )
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = nuruColors.bgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // ─── ヘッダー ───────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "閉じる", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Text(
                        "通知",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                    IconButton(onClick = { showKindSettings = true }) {
                        Icon(Icons.Outlined.Settings, "通知設定", tint = nuruColors.textSecondary)
                    }
                }
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                // ─── コンテンツ ─────────────────────────────────────────
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = LineGreen)
                            }
                        }
                        notifications.isEmpty() -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .background(nuruColors.bgSecondary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        null,
                                        modifier = Modifier.size(36.dp),
                                        tint = nuruColors.textTertiary
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                Text("通知はまだありません", color = nuruColors.textSecondary, fontSize = 15.sp)
                                Text(
                                    "リアクションや返信が届くとここに表示されます",
                                    color = nuruColors.textTertiary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(notifications, key = { it.id }) { notification ->
                                    NotificationRow(
                                        notification = notification,
                                        profile = profiles[notification.pubkey],
                                        originalPost = originalPosts[notification.targetEventId],
                                        onProfileClick = onProfileClick
                                    )
                                }
                            }
                        }
                    }

                    // ─── 新着ピル ───────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AnimatedVisibility(
                            visible = pendingNew.isNotEmpty(),
                            enter = fadeIn() + slideInVertically { -it },
                            exit = fadeOut() + slideOutVertically { -it }
                        ) {
                            Surface(
                                onClick = {
                                    notifications = (pendingNew + notifications).distinctBy { it.id }
                                    pendingNew = emptyList()
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = LineGreen,
                                shadowElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val avatarUrls = pendingNew
                                        .mapNotNull { profiles[it.pubkey]?.picture }
                                        .distinct()
                                        .take(3)
                                    if (avatarUrls.isNotEmpty()) {
                                        Box(Modifier.height(22.dp).width((avatarUrls.size * 16 + 6).dp)) {
                                            avatarUrls.forEachIndexed { i, url ->
                                                UserAvatar(
                                                    pictureUrl = url,
                                                    displayName = "",
                                                    size = 22.dp,
                                                    modifier = Modifier.offset(x = (i * 16).dp)
                                                )
                                            }
                                        }
                                    }
                                    Icon(Icons.Default.Notifications, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Text(
                                        "新着 ${pendingNew.size} 件",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────
// 各通知行
// ───────────────────────────────────────────────────────────────────────────
@Composable
fun NotificationRow(
    notification: NotificationItem,
    profile: UserProfile?,
    originalPost: NostrEvent?,
    onProfileClick: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val style = styleFor(notification.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onProfileClick(notification.pubkey) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // ─── 左カラム: アイコン付きアバター ───────────────────────────
        Box(modifier = Modifier.size(46.dp)) {
            AsyncImage(
                model = profile?.picture,
                contentDescription = null,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(nuruColors.bgTertiary)
                    .clickable { onProfileClick(notification.pubkey) },
                contentScale = ContentScale.Crop
            )
            // タイプアイコンバッジ（右下）
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .align(Alignment.BottomEnd)
                    .background(style.color, CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (notification.type == "reaction" && notification.emojiUrl != null) {
                    AsyncImage(
                        model = notification.emojiUrl,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                } else {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

        // ─── 右カラム: テキスト内容 ───────────────────────────────────
        Column(modifier = Modifier.weight(1f)) {
            // 名前 + 相対時刻
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile?.displayedName ?: "Unknown",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = formatPostTimestamp(notification.createdAt),
                    fontSize = 11.sp,
                    color = nuruColors.textTertiary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(3.dp))

            // アクション説明
            when (notification.type) {
                "reaction", "emoji_reaction" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (notification.emojiUrl != null) {
                            AsyncImage(
                                model = notification.emojiUrl,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                text = when (notification.reactionEmoji) {
                                    "+", null -> "👍"
                                    "-" -> "👎"
                                    else -> notification.reactionEmoji!!
                                },
                                fontSize = 15.sp
                            )
                        }
                        Text(style.label, fontSize = 13.sp, color = nuruColors.textSecondary)
                    }
                }
                "badge" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("🏅", fontSize = 15.sp)
                        Text("バッジを授与されました", fontSize = 13.sp, color = nuruColors.textSecondary)
                    }
                    if (!notification.comment.isNullOrBlank()) {
                        Text(
                            notification.comment,
                            fontSize = 12.sp,
                            color = nuruColors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                "zap" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "⚡ ${notification.amount ?: 0} sats",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFC107),
                            fontSize = 13.sp
                        )
                        Text("Zap", fontSize = 13.sp, color = nuruColors.textSecondary)
                    }
                    if (!notification.comment.isNullOrBlank()) {
                        Text(
                            notification.comment,
                            fontSize = 12.sp,
                            color = nuruColors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                "reply", "mention" -> {
                    Text(style.label, fontSize = 13.sp, color = nuruColors.textSecondary)
                    if (!notification.comment.isNullOrBlank()) {
                        Text(
                            notification.comment,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                else -> Text(style.label, fontSize = 13.sp, color = nuruColors.textSecondary)
            }

            // 対象投稿プレビュー（返信/リアクション/リポスト用）
            if (originalPost != null && notification.type in listOf("reaction", "emoji_reaction", "repost", "zap")) {
                val previewText = removeImageUrls(originalPost.content).take(80).trim()
                if (previewText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        previewText,
                        fontSize = 12.sp,
                        color = nuruColors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(nuruColors.bgSecondary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    HorizontalDivider(
        color = nuruColors.border.copy(alpha = 0.5f),
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 74.dp)
    )
}
