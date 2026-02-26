package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

data class NotificationItem(
    val id: String,
    val pubkey: String,
    val type: String, // "reaction", "zap", "birthday"
    val createdAt: Long,
    val amount: Long? = null,
    val comment: String? = null,
    val targetEventId: String? = null,
    val emojiUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationModal(
    repository: NostrRepository,
    myPubkey: String,
    onClose: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var notifications by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var profiles by remember { mutableStateOf<Map<String, UserProfile>>(emptyMap()) }
    var originalPosts by remember { mutableStateOf<Map<String, NostrEvent>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                // Simplified fetching logic mirroring web
                // 1. Fetch reactions and zaps targeting me
                // This is a bit complex for a Composable, but let's do a basic version
                // We'd ideally want to query by #p tag. NostrRepository doesn't have a direct method for this yet that returns everything.
                // For now, let's assume we can fetch them.

                // (Stubbed implementation as NostrRepository would need new methods for efficient notification fetching)
                // In a real scenario, I'd add fetchNotifications to NostrRepository.

                // For the purpose of this task (syncing UI), I'll focus on the layout.
                loading = false
            } catch (e: Exception) {
                loading = false
            }
        }
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
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    Text("通知", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
                    Box(Modifier.width(48.dp)) // Spacer
                }

                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                // Content
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LineGreen)
                    }
                } else if (notifications.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(nuruColors.bgSecondary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(32.dp), tint = nuruColors.textTertiary)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "通知はまだありません",
                            color = nuruColors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
        }
    }
}

@Composable
fun NotificationRow(
    notification: NotificationItem,
    profile: UserProfile?,
    originalPost: NostrEvent?,
    onProfileClick: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* Handle click */ }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar and Type Icon
        Box(contentAlignment = Alignment.BottomEnd) {
            AsyncImage(
                model = profile?.picture,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(nuruColors.bgTertiary)
                    .clickable { onProfileClick(notification.pubkey) },
                contentScale = ContentScale.Crop
            )

            val iconBg = when (notification.type) {
                "reaction" -> Color(0xFFE91E63)
                "zap" -> Color(0xFFFFC107)
                else -> Color(0xFFEF5350)
            }

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .offset(x = 2.dp, y = 2.dp)
                    .background(iconBg, CircleShape)
                    .background(nuruColors.bgPrimary, CircleShape) // pseudo-border
                    .padding(2.dp)
                    .background(iconBg, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Icons could be added here
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    profile?.displayedName ?: "Unknown",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                // Add relative time here if available
            }

            when (notification.type) {
                "reaction" -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("リアクションしました", fontSize = 13.sp, color = nuruColors.textSecondary)
                        if (notification.emojiUrl != null) {
                            AsyncImage(
                                model = notification.emojiUrl,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                "zap" -> {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("⚡ ${notification.amount} sats", fontWeight = FontWeight.Bold, color = Color(0xFFFFC107), fontSize = 13.sp)
                            Text("Zapしました", fontSize = 13.sp, color = nuruColors.textSecondary)
                        }
                        if (!notification.comment.isNullOrBlank()) {
                            Text(
                                notification.comment,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .background(nuruColors.bgSecondary, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
                "birthday" -> {
                    Text(
                        "今日は誕生美です。一緒にお祝いしましょう。",
                        fontSize = 13.sp,
                        color = nuruColors.textSecondary
                    )
                }
            }

            if (originalPost != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    originalPost.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(nuruColors.bgSecondary.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    fontSize = 12.sp,
                    color = nuruColors.textTertiary,
                    maxLines = 2
                )
            }
        }
    }
    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
}
