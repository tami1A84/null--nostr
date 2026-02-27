package io.nurunuru.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors

@Composable
fun PostActions(
    post: ScoredPost,
    onLike: () -> Unit,
    onLikeLongPress: () -> Unit = {},
    onRepost: () -> Unit,
    onZap: () -> Unit,
    onZapLongPress: () -> Unit = {}
) {
    val nuruColors = LocalNuruColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Like (Thumbs up as per web)
        ActionButton(
            icon = NuruIcons.Like(post.isLiked),
            count = post.likeCount,
            onClick = onLike,
            onLongClick = onLikeLongPress,
            tint = if (post.isLiked) nuruColors.lineGreen else nuruColors.textTertiary,
            animate = post.isLiked
        )
        // Repost
        ActionButton(
            icon = NuruIcons.Repost,
            count = post.repostCount,
            onClick = onRepost,
            tint = if (post.isReposted) nuruColors.lineGreen else nuruColors.textTertiary,
            animate = post.isReposted
        )
        // Zap
        ActionButton(
            icon = NuruIcons.Zap(false),
            count = (post.zapAmount / 1000).toInt(),
            onClick = onZap,
            onLongClick = onZapLongPress,
            tint = nuruColors.textTertiary,
            animate = false
        )

        // Client tag (via)
        val client = post.event.getTagValue("client")
        if (client != null) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "via $client",
                style = MaterialTheme.typography.labelSmall,
                color = nuruColors.textTertiary.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    tint: Color,
    animate: Boolean = false
) {
    val scale = remember { Animatable(1f) }

    LaunchedEffect(animate) {
        if (animate) {
            scale.animateTo(
                targetValue = 1.3f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
            )
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
        )
        if (count > 0) {
            Text(
                text = formatCount(count),
                style = MaterialTheme.typography.bodySmall,
                color = tint
            )
        }
    }
}

private fun formatCount(count: Int): String = when {
    count >= 1000 -> "${count / 1000}K"
    else -> count.toString()
}
