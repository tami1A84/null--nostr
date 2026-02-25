package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PostItem(
    post: ScoredPost,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    isOwnPost: Boolean = false,
    isVerified: Boolean = false,
    birdwatchNotes: List<io.nurunuru.app.data.models.NostrEvent> = emptyList()
) {
    val nuruColors = LocalNuruColors.current

    val profile = post.profile
    // Internal NIP-05 verification for users not verified by the parent
    var internalVerified by remember { mutableStateOf(isVerified) }
    LaunchedEffect(profile?.nip05, isVerified) {
        if (!isVerified && profile?.nip05 != null) {
            internalVerified = io.nurunuru.app.data.Nip05Utils.verifyNip05(profile.nip05, post.event.pubkey)
        } else {
            internalVerified = isVerified
        }
    }
    var showMenu by remember { mutableStateOf(false) }

    // Content Warning state
    val cwReason = post.event.getTagValue("content-warning")
    var isCWExpanded by remember { mutableStateOf(cwReason == null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
    ) {
        // Repost Indicator
        if (post.repostedBy != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, top = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = NuruIcons.Repost,
                    contentDescription = null,
                    tint = nuruColors.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${post.repostedBy.displayedName} がリポスト",
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.textTertiary
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
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
                // Header row: name + verification + badges + time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = profile?.displayedName ?: NostrKeyUtils.shortenPubkey(post.event.pubkey),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1
                        )

                        // Verification badge (Green checkmark + NIP-05 address)
                        if (internalVerified && profile?.nip05 != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = nuruColors.lineGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = formatNip05(profile.nip05),
                                    fontSize = 11.sp,
                                    color = nuruColors.lineGreen,
                                    maxLines = 1,
                                    modifier = Modifier.widthIn(max = 100.dp)
                                )
                            }
                        }

                        // Badges (NIP-58)
                        post.badges.take(3).forEach { badgeUrl ->
                            AsyncImage(
                                model = badgeUrl,
                                contentDescription = "Badge",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }

                    // Time and Menu
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formatTimestamp(post.event.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = nuruColors.textTertiary
                        )
                        if (isOwnPost && onDelete != null) {
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "Menu",
                                        tint = nuruColors.textTertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("削除", color = Color.Red) },
                                        onClick = {
                                            showMenu = false
                                            onDelete()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Content Warning
                if (cwReason != null && !isCWExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFFFF9800).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .clickable { isCWExpanded = true }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = cwReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "表示する",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Content
                    if (cwReason != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(cwReason, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                            Spacer(Modifier.weight(1f))
                            Text(
                                "隠す",
                                style = MaterialTheme.typography.labelSmall,
                                color = nuruColors.textTertiary,
                                modifier = Modifier.clickable { isCWExpanded = false }
                            )
                        }
                    }
                    PostContent(post = post)

                    // Video / Images
                    if (post.event.kind == NostrKind.VIDEO_LOOP) {
                        val videoUrl = post.event.getTagValue("url") ?: post.event.content
                        val isVerified = post.event.getTagValue("verification-level") == "verified_web"

                        if (videoUrl.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Black)
                            ) {
                                VideoPlayer(videoUrl = videoUrl, modifier = Modifier.fillMaxSize())

                                if (isVerified) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "✅ WEB VERIFIED",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                // 6.3s LOOP badge
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp),
                                    color = nuruColors.lineGreen,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "6.3s LOOP",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Images
                        extractImages(post.event.content).let { images ->
                            if (images.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                ImageGrid(images = images)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Birdwatch Display
                if (birdwatchNotes.isNotEmpty()) {
                    BirdwatchDisplay(notes = birdwatchNotes)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Action buttons (Match web order: Like, Repost, Zap)
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
                        tint = if (post.isLiked) nuruColors.lineGreen else nuruColors.textTertiary
                    )
                    // Repost
                    ActionButton(
                        icon = NuruIcons.Repost,
                        count = post.repostCount,
                        onClick = onRepost,
                        tint = if (post.isReposted) nuruColors.lineGreen else nuruColors.textTertiary
                    )
                    // Zap
                    val context = LocalContext.current
                    ActionButton(
                        icon = NuruIcons.Zap(false),
                        count = (post.zapAmount / 1000).toInt(),
                        onClick = {
                            if (profile?.lud16 != null) {
                                android.widget.Toast.makeText(context, "⚡ Zap送信: ${profile.lud16}", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Lightningアドレスが設定されていません", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        tint = nuruColors.textTertiary
                    )
                    // Spacer for layout balance
                    Spacer(modifier = Modifier.width(8.dp))
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
private fun PostContent(post: ScoredPost) {
    val nuruColors = LocalNuruColors.current
    val content = post.event.content
    val cleanContent = removeImageUrls(content).trim()
    if (cleanContent.isBlank()) return

    // Extract custom emojis
    val emojis = post.event.tags
        .filter { it.getOrNull(0) == "emoji" }
        .mapNotNull {
            val shortcode = it.getOrNull(1)
            val url = it.getOrNull(2)
            if (shortcode != null && url != null) shortcode to url else null
        }.toMap()

    val inlineContent = mutableMapOf<String, InlineTextContent>()

    val annotated = buildAnnotatedString {
        var currentText = cleanContent
        val regex = Regex("(:\\w+:|#\\w+|@\\w+|nostr:\\w+)")
        var lastIdx = 0

        regex.findAll(cleanContent).forEach { match ->
            append(cleanContent.substring(lastIdx, match.range.first))
            val value = match.value

            if (value.startsWith(":") && value.endsWith(":")) {
                val shortcode = value.removeSurrounding(":")
                val url = emojis[shortcode]
                if (url != null) {
                    val id = "emoji_$shortcode"
                    inlineContent[id] = InlineTextContent(
                        Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.Center)
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = value,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    appendInlineContent(id, value)
                } else {
                    append(value)
                }
            } else if (value.startsWith("#")) {
                withStyle(SpanStyle(color = nuruColors.lineGreen, fontWeight = FontWeight.Medium)) {
                    append(value)
                }
            } else {
                withStyle(SpanStyle(color = nuruColors.lineGreen)) {
                    append(value)
                }
            }
            lastIdx = match.range.last + 1
        }
        append(cleanContent.substring(lastIdx))
    }

    Text(
        text = annotated,
        inlineContent = inlineContent,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onBackground,
        lineHeight = 22.sp
    )
}

@Composable
private fun ImageGrid(images: List<String>) {
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    val maxVisible = 4
    val visibleImages = if (showAll) images else images.take(maxVisible)
    val hiddenCount = if (images.size > maxVisible && !showAll) images.size - maxVisible else 0

    Column(modifier = Modifier.fillMaxWidth()) {
        if (images.size == 1) {
            AsyncImage(
                model = images[0],
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        // Open in browser
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(images[0]))
                        context.startActivity(intent)
                    }
            )
        } else {
            // Grid layout
            val rows = (visibleImages.size + 1) / 2
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (c in 0 until 2) {
                            val index = r * 2 + c
                            if (index < visibleImages.size) {
                                Box(modifier = Modifier.weight(1f)) {
                                    AsyncImage(
                                        model = visibleImages[index],
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (images.size == 3 && index == 0) 244.dp else 120.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (index == maxVisible - 1 && hiddenCount > 0) {
                                                    showAll = true
                                                } else {
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(visibleImages[index]))
                                                    context.startActivity(intent)
                                                }
                                            }
                                    )
                                    if (index == maxVisible - 1 && hiddenCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black.copy(alpha = 0.6f))
                                                .clickable { showAll = true },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "+$hiddenCount",
                                                color = Color.White,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (showAll) {
                TextButton(onClick = { showAll = false }) {
                    Text("閉じる", color = io.nurunuru.app.ui.theme.LineGreen, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun BirdwatchDisplay(notes: List<io.nurunuru.app.data.models.NostrEvent>) {
    Surface(
        color = Color(0xFF2196F3).copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF2196F3).copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "コミュニティノート",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF2196F3),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            notes.forEach { note ->
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
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
