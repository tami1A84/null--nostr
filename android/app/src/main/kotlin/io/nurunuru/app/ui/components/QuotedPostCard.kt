package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LocalNuruColors

/**
 * Compact embedded card for a quoted post (kind 1 quote-repost).
 * Mirrors the web PostItem's inline quote display.
 */
@Composable
fun QuotedPostCard(
    post: ScoredPost,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current
    val profile = post.profile
    val previewContent = cleanContent(post.event.content).take(200)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, nuruColors.border, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onProfileClick(post.event.pubkey) }
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Header: avatar + name
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                UserAvatar(
                    pictureUrl = profile?.picture,
                    displayName = profile?.displayedName ?: "",
                    size = 20.dp
                )
                Text(
                    text = profile?.displayedName
                        ?: NostrKeyUtils.shortenPubkey(post.event.pubkey),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }

            // Content preview
            if (previewContent.isNotBlank()) {
                Text(
                    text = previewContent + if (post.event.content.length > 200) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    maxLines = 4
                )
            }

            // First image if present
            val images = extractImages(post.event.content)
            if (images.isNotEmpty()) {
                AsyncImage(
                    model = images.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

private val IMAGE_REGEX = Regex(
    "https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|avif)(\\?[^\\s]*)?",
    RegexOption.IGNORE_CASE
)

private fun extractImages(content: String): List<String> =
    IMAGE_REGEX.findAll(content).map { it.value }.distinct().toList()

private fun cleanContent(content: String): String =
    IMAGE_REGEX.replace(content, "").replace(Regex("nostr:\\S+"), "").trim()
