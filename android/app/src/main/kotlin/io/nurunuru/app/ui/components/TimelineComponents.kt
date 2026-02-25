package io.nurunuru.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.FeedType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineHeader(
    feedType: FeedType,
    onFeedTypeChange: (FeedType) -> Unit,
    showSearch: Boolean,
    onShowSearchChange: (Boolean) -> Unit,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onNotificationsClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current

    Column {
        TopAppBar(
            windowInsets = WindowInsets.statusBars,
            title = {
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .background(nuruColors.bgSecondary, RoundedCornerShape(20.dp))
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Pill-style tabs
                    TimelineTabButton(
                        text = "おすすめ",
                        selected = feedType == FeedType.GLOBAL,
                        onClick = { onFeedTypeChange(FeedType.GLOBAL) }
                    )

                    TimelineTabButton(
                        text = "フォロー",
                        selected = feedType == FeedType.FOLLOWING,
                        onClick = { onFeedTypeChange(FeedType.FOLLOWING) }
                    )
                }
            },
            actions = {
                IconButton(onClick = {
                    onShowSearchChange(!showSearch)
                    if (showSearch) {
                        onSearchTextChange("")
                    }
                }) {
                    Icon(
                        imageVector = if (showSearch) Icons.Outlined.Close else Icons.Default.Search,
                        contentDescription = "検索",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onNotificationsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = "通知",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            )
        )

        // Search bar
        AnimatedVisibility(visible = showSearch) {
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchTextChange,
                placeholder = { Text("ノートを検索...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LineGreen,
                    cursorColor = LineGreen
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }
}

@Composable
private fun TimelineTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) LineGreen else Color.Transparent,
            contentColor = if (selected) Color.White else nuruColors.textTertiary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = null
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TimelineLoadingState() {
    val nuruColors = LocalNuruColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val infiniteTransition = rememberInfiniteTransition()
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 200),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(LineGreen, RoundedCornerShape(4.dp))
                            .graphicsLayer { alpha = dotAlpha }
                    )
                }
            }
            Text(
                "読み込んでいます...",
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textSecondary
            )
        }
    }
}

@Composable
fun TimelineErrorState(
    onRetry: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(nuruColors.bgSecondary, RoundedCornerShape(32.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🌐", fontSize = 32.sp)
            }
            Text(
                "接続エラー",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "通信状態を確認して、もう一度お試しください",
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("再試行", fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun TimelineEmptyState(
    feedType: FeedType,
    isFollowListEmpty: Boolean
) {
    val nuruColors = LocalNuruColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                "📭",
                fontSize = 48.sp,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .graphicsLayer { alpha = 0.6f }
            )
            if (feedType == FeedType.FOLLOWING) {
                Text(
                    if (isFollowListEmpty) "まだ誰もフォローしていません" else "フォロー中のユーザーの投稿がありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                if (isFollowListEmpty) {
                    Spacer(Modifier.height(24.dp))
                    Surface(
                        color = nuruColors.bgSecondary,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "💡 プロフィールページでユーザーをフォローしてみましょう",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = nuruColors.textTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            } else {
                Text(
                    "まだ投稿がありません\n新しい投稿がまもなく届くかもしれません",
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        }
    }
}
