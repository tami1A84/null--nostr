package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LocalNuruColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PostItem(
    post: ScoredPost,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onReply: () -> Unit,
    onZap: () -> Unit = {},
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current
    val profile = post.profile

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Avatar
            UserAvatar(
                pictureUrl = profile?.picture,
                displayName = profile?.displayedName ?: "",
                size = 42.dp,
                modifier = Modifier.clickable { onProfileClick(post.event.pubkey) }
            )

            Column(modifier = Modifier.weight(1f)) {
                // Header row: name + time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayedName ?: (post.event.pubkey.take(8) + "..."),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1
                        )
                        if (profile?.nip05 != null) {
                            Text(
                                text = formatNip05(profile.nip05),
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.lineGreen,
                                maxLines = 1
                            )
                        }
                    }
                    Text(
                        text = formatTimestamp(post.event.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = nuruColors.textTertiary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Content
                PostContent(content = post.event.content)

                // Images
                extractImages(post.event.content).let { images ->
                    if (images.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ImageGrid(images = images)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reply
                    ActionButton(
                        icon = Icons.Outlined.ChatBubble,
                        count = post.replyCount,
                        onClick = onReply,
                        tint = nuruColors.textTertiary
                    )
                    // Repost
                    ActionButton(
                        icon = Icons.Outlined.Repeat,
                        count = post.repostCount,
                        onClick = onRepost,
                        tint = if (post.isReposted) nuruColors.lineGreen else nuruColors.textTertiary
                    )
                    // Like
                    ActionButton(
                        icon = if (post.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        count = post.likeCount,
                        onClick = onLike,
                        tint = if (post.isLiked) Color(0xFFFF6B6B) else nuruColors.textTertiary
                    )
                    // Zap
                    ActionButton(
                        icon = Icons.Outlined.ElectricBolt,
                        count = 0,
                        onClick = onZap,
                        tint = Color(0xFFFFB74D)
                    )
                    // Spacer for layout balance
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }

        // Divider
        HorizontalDivider(
            color = nuruColors.border,
            thickness = 0.5.dp
        )
    }
}

@Composable
private fun PostContent(content: String) {
    val nuruColors = LocalNuruColors.current
    val cleanContent = removeImageUrls(content).trim()
    if (cleanContent.isBlank()) return

    val annotated = buildAnnotatedString {
        val parts = cleanContent.split(Regex("(#\\w+|@\\w+|nostr:\\w+)"))
        val matches = Regex("(#\\w+|@\\w+|nostr:\\w+)").findAll(cleanContent).toList()
        var idx = 0
        for (i in parts.indices) {
            append(parts[i])
            if (i < matches.size) {
                val match = matches[i].value
                when {
                    match.startsWith("#") -> withStyle(SpanStyle(color = nuruColors.lineGreen, fontWeight = FontWeight.Medium)) {
                        append(match)
                    }
                    else -> withStyle(SpanStyle(color = nuruColors.lineGreen)) {
                        append(match)
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        lineHeight = 22.sp
    )
}

@Composable
private fun ImageGrid(images: List<String>) {
    when (images.size) {
        1 -> AsyncImage(
            model = images[0],
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        else -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                images.take(4).forEachIndexed { index, url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    onClick: () -> Unit,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
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

// ─── Helpers ─────────────────────────────────────────────────────────────────

private val IMAGE_REGEX = Regex(
    "https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|avif)(\\?[^\\s]*)?",
    RegexOption.IGNORE_CASE
)

private fun extractImages(content: String): List<String> =
    IMAGE_REGEX.findAll(content).map { it.value }.distinct().toList()

private fun removeImageUrls(content: String): String =
    IMAGE_REGEX.replace(content, "").trim()

private fun formatTimestamp(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - unixSec
    return when {
        diff < 60 -> "${diff}秒"
        diff < 3600 -> "${diff / 60}分"
        diff < 86400 -> "${diff / 3600}時間"
        diff < 86400 * 7 -> "${diff / 86400}日"
        else -> {
            val sdf = SimpleDateFormat("M/d", Locale.JAPAN)
            sdf.format(Date(unixSec * 1000))
        }
    }
}

private fun formatCount(count: Int): String = when {
    count >= 1000 -> "${count / 1000}K"
    else -> count.toString()
}

private fun formatNip05(nip05: String): String = when {
    nip05.startsWith("_@") -> nip05.drop(1)
    !nip05.contains("@") -> "@$nip05"
    else -> nip05
}
