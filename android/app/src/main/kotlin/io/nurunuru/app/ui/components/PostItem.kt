package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LocalNuruColors

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

    // Internal NIP-05 verification
    var internalVerified by remember { mutableStateOf(isVerified) }
    LaunchedEffect(profile?.nip05, isVerified) {
        if (!isVerified && profile?.nip05 != null) {
            internalVerified = io.nurunuru.app.data.Nip05Utils.verifyNip05(profile.nip05, post.event.pubkey)
        } else {
            internalVerified = isVerified
        }
    }

    // Content Warning state
    val cwReason = post.event.getTagValue("content-warning")
    var isCWExpanded by remember { mutableStateOf(cwReason == null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
    ) {
        PostIndicators(post = post)

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
                PostHeader(
                    post = post,
                    internalVerified = internalVerified,
                    onProfileClick = onProfileClick,
                    onDelete = onDelete,
                    isOwnPost = isOwnPost
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Content Warning
                if (cwReason != null && !isCWExpanded) {
                    PostCWOverlay(reason = cwReason, onExpand = { isCWExpanded = true })
                } else {
                    if (cwReason != null) {
                        PostCWHeader(reason = cwReason, onCollapse = { isCWExpanded = false })
                    }

                    PostContent(post = post)
                    PostMedia(post = post)
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (birdwatchNotes.isNotEmpty()) {
                    BirdwatchDisplay(notes = birdwatchNotes)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                PostActions(
                    post = post,
                    onLike = onLike,
                    onRepost = onRepost
                )
            }
        }

        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }
}

@Composable
private fun PostCWOverlay(reason: String, onExpand: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color(0xFFFF9800).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .clickable { onExpand() }
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
                text = reason,
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
}

@Composable
private fun PostCWHeader(reason: String, onCollapse: () -> Unit) {
    val nuruColors = LocalNuruColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(reason, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
        Spacer(Modifier.weight(1f))
        Text(
            "隠す",
            style = MaterialTheme.typography.labelSmall,
            color = nuruColors.textTertiary,
            modifier = Modifier.clickable { onCollapse() }
        )
    }
}
