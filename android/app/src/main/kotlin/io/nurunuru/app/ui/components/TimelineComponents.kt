package io.nurunuru.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.FeedType

@Composable
fun TimelineHeader(
    feedType: FeedType,
    onFeedTypeChange: (FeedType) -> Unit,
    showRecommendedDot: Boolean = false,
    showFollowingDot: Boolean = false,
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current

    Column(
        modifier = Modifier
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Pill-style switcher (Mobile layout parity with Web)
            Row(
                modifier = Modifier
                    .background(Color.Black, RoundedCornerShape(20.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TimelineTabButton(
                    text = "おすすめ",
                    selected = feedType == FeedType.GLOBAL,
                    showDot = showRecommendedDot,
                    onClick = { onFeedTypeChange(FeedType.GLOBAL) }
                )

                TimelineTabButton(
                    text = "フォロー",
                    selected = feedType == FeedType.FOLLOWING,
                    showDot = showFollowingDot,
                    onClick = { onFeedTypeChange(FeedType.FOLLOWING) }
                )
            }

            // Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = NuruIcons.Search,
                        contentDescription = "検索",
                        tint = nuruColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = NuruIcons.Notifications,
                        contentDescription = "通知",
                        tint = nuruColors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }
}


/**
 * フローティングピル通知 — バックグラウンドで溜まった新着投稿を知らせる。
 * タップするとタイムライン最上部にスクロールし、pending を反映させる。
 */
@Composable
fun NewPostsPill(
    pendingPosts: List<ScoredPost>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = LineGreen,
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 先頭3人のアバターをオーバーラップ表示
            val avatarUrls = pendingPosts
                .mapNotNull { it.profile?.picture }
                .distinct()
                .take(3)
            if (avatarUrls.isNotEmpty()) {
                Box(modifier = Modifier.height(20.dp).width((avatarUrls.size * 14 + 6).dp)) {
                    avatarUrls.forEachIndexed { i, url ->
                        UserAvatar(
                            pictureUrl = url,
                            displayName = "",
                            size = 20.dp,
                            modifier = Modifier.offset(x = (i * 14).dp)
                        )
                    }
                }
            }
            Text(
                text = "新着 ${pendingPosts.size} 件",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun TimelineLoadingState(
    text: String = "読み込んでいます...",
    modifier: Modifier = Modifier.fillMaxSize()
) {
    TimelineLoadingSkeleton()
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
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "しばらくお待ちいただくか、更新してみてください",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = nuruColors.textTertiary,
                        textAlign = TextAlign.Center
                    )
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
