package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.*
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PostIndicators(post: ScoredPost) {
    val nuruColors = LocalNuruColors.current
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
    } else if (post.event.getTagValue("e") != null) {
        val replyPubkey = post.event.getTagValue("p")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, top = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = NuruIcons.Talk(false),
                contentDescription = null,
                tint = nuruColors.textTertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (replyPubkey != null) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = nuruColors.lineGreen)) {
                            append("@${NostrKeyUtils.shortenPubkey(replyPubkey)}")
                        }
                        append(" への返信")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.textTertiary
                )
            } else {
                Text(
                    text = "返信",
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.textTertiary
                )
            }
        }
    }
}

@Composable
fun PostHeader(
    post: ScoredPost,
    internalVerified: Boolean,
    onProfileClick: (String) -> Unit,
    repository: io.nurunuru.app.data.NostrRepository,
    onDelete: (() -> Unit)? = null,
    onMute: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    onBirdwatch: (() -> Unit)? = null,
    onNotInterested: (() -> Unit)? = null,
    isOwnPost: Boolean = false
) {
    val nuruColors = LocalNuruColors.current
    val profile = post.profile
    var showMenu by remember { mutableStateOf(false) }

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
                maxLines = 1,
                modifier = Modifier.clickable { onProfileClick(post.event.pubkey) }
            )

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

            BadgeDisplay(
                pubkey = post.event.pubkey,
                repository = repository,
                initialBadges = post.badges
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatPostTimestamp(post.event.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary
            )

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
                    if (onNotInterested != null && !isOwnPost) {
                        DropdownMenuItem(
                            text = { Text("この投稿に興味がない") },
                            onClick = { showMenu = false; onNotInterested() },
                            leadingIcon = { Icon(NuruIcons.Talk(false), null) } // Using Talk as placeholder for not interested
                        )
                    }
                    if (onBirdwatch != null && !isOwnPost) {
                        DropdownMenuItem(
                            text = { Text("Birdwatch", color = Color(0xFF2196F3)) },
                            onClick = { showMenu = false; onBirdwatch() },
                            leadingIcon = { Icon(Icons.Default.Check, null, tint = Color(0xFF2196F3)) }
                        )
                    }
                    if (onReport != null && !isOwnPost) {
                        DropdownMenuItem(
                            text = { Text("通報", color = Color(0xFFFF9800)) },
                            onClick = { showMenu = false; onReport() },
                            leadingIcon = { Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800)) }
                        )
                    }
                    if (onMute != null && !isOwnPost) {
                        DropdownMenuItem(
                            text = { Text("ミュート", color = Color.Red) },
                            onClick = { showMenu = false; onMute() },
                            leadingIcon = { Icon(Icons.Default.Block, null, tint = Color.Red) }
                        )
                    }
                    if (isOwnPost && onDelete != null) {
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
}

@Composable
fun PostContent(post: ScoredPost, repository: io.nurunuru.app.data.NostrRepository) {
    val nuruColors = LocalNuruColors.current
    val content = post.event.content
    val cleanContent = removeImageUrls(content).trim()
    if (cleanContent.isBlank()) return

    val emojis = post.event.tags
        .filter { it.getOrNull(0) == "emoji" }
        .mapNotNull {
            val shortcode = it.getOrNull(1)
            val url = it.getOrNull(2)
            if (shortcode != null && url != null) shortcode to url else null
        }.toMap()

    val inlineContent = mutableMapOf<String, InlineTextContent>()

    val parts = mutableListOf<ContentPart>()
    val regex = Regex("(https?://[^\\s]+|nostr:(?:note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]{58,}|#\\w+|:\\w+:)")
    var lastIdx = 0

    regex.findAll(cleanContent).forEach { match ->
        if (match.range.first > lastIdx) {
            parts.add(ContentPart.Text(cleanContent.substring(lastIdx, match.range.first)))
        }
        val value = match.value
        when {
            value.startsWith("http") -> parts.add(ContentPart.Link(value))
            value.startsWith("nostr:") -> parts.add(ContentPart.Nostr(value))
            value.startsWith("#") -> parts.add(ContentPart.Hashtag(value))
            value.startsWith(":") && value.endsWith(":") -> parts.add(ContentPart.Emoji(value))
        }
        lastIdx = match.range.last + 1
    }
    if (lastIdx < cleanContent.length) {
        parts.add(ContentPart.Text(cleanContent.substring(lastIdx)))
    }

    val annotated = buildAnnotatedString {
        parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> append(part.text)
                is ContentPart.Hashtag -> withStyle(SpanStyle(color = nuruColors.lineGreen, fontWeight = FontWeight.Medium)) { append(part.tag) }
                is ContentPart.Emoji -> {
                    val shortcode = part.code.removeSurrounding(":")
                    val url = emojis[shortcode]
                    if (url != null) {
                        val id = "emoji_$shortcode"
                        inlineContent[id] = InlineTextContent(
                            Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.Center)
                        ) {
                            AsyncImage(model = url, contentDescription = part.code, modifier = Modifier.fillMaxSize())
                        }
                        appendInlineContent(id, part.code)
                    } else {
                        append(part.code)
                    }
                }
                is ContentPart.Link -> withStyle(SpanStyle(color = nuruColors.lineGreen)) { append(part.url.take(40) + if (part.url.length > 40) "..." else "") }
                is ContentPart.Nostr -> withStyle(SpanStyle(color = nuruColors.lineGreen)) { append(part.link.take(30) + "...") }
            }
        }
    }

    Column {
        Text(
            text = annotated,
            inlineContent = inlineContent,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = 22.sp
        )

        // Render URL previews and embedded notes
        parts.forEach { part ->
            when (part) {
                is ContentPart.Link -> URLPreview(url = part.url)
                is ContentPart.Nostr -> EmbeddedNostrContent(link = part.link, repository = repository)
                else -> {}
            }
        }
    }
}

sealed class ContentPart {
    data class Text(val text: String) : ContentPart()
    data class Link(val url: String) : ContentPart()
    data class Nostr(val link: String) : ContentPart()
    data class Hashtag(val tag: String) : ContentPart()
    data class Emoji(val code: String) : ContentPart()
}

@Composable
fun EmbeddedNostrContent(link: String, repository: io.nurunuru.app.data.NostrRepository) {
    val bech32 = link.removePrefix("nostr:")
    var note by remember { mutableStateOf<ScoredPost?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(link) {
        val parsed = io.nurunuru.app.data.NostrKeyUtils.parseNostrLink(bech32)
        if (parsed != null && (parsed.type == "note" || parsed.type == "nevent")) {
            note = repository.fetchEvent(parsed.id)
        }
        isLoading = false
    }

    if (isLoading) {
        PostSkeleton()
    } else if (note != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { /* TODO: Navigate to note */ },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserAvatar(pictureUrl = note!!.profile?.picture, displayName = note!!.profile?.displayedName ?: "", size = 20.dp)
                    Text(text = note!!.profile?.displayedName ?: NostrKeyUtils.shortenPubkey(note!!.event.pubkey), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(text = note!!.event.content, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    } else {
        Text(text = link, color = LocalNuruColors.current.lineGreen, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PostMedia(post: ScoredPost) {
    val nuruColors = LocalNuruColors.current
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
        extractPostImages(post.event.content).let { images ->
            if (images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                PostImageGrid(images = images)
            }
        }
    }
}


@Composable
fun PostImageGrid(images: List<String>) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(images[0]))
                        context.startActivity(intent)
                    }
            )
        } else {
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

// Helpers
private val POST_IMAGE_REGEX = Regex(
    "https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|avif)(\\?[^\\s]*)?",
    RegexOption.IGNORE_CASE
)

fun extractPostImages(content: String): List<String> =
    POST_IMAGE_REGEX.findAll(content).map { it.value }.distinct().toList()

fun removeImageUrls(content: String): String =
    POST_IMAGE_REGEX.replace(content, "").trim()

fun formatPostTimestamp(unixSec: Long): String {
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

fun formatNip05(nip05: String): String = when {
    nip05.startsWith("_@") -> nip05.drop(1)
    !nip05.contains("@") -> "@$nip05"
    else -> nip05
}
