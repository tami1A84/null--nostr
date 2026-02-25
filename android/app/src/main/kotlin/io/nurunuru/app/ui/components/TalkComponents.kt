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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import java.text.SimpleDateFormat
import java.util.*

private val IMAGE_REGEX = Regex(
    "https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|avif)(\\?[^\\s]*)?",
    RegexOption.IGNORE_CASE
)

@Composable
fun ConversationItem(
    conversation: DmConversation,
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val profile = conversation.partnerProfile

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            pictureUrl = profile?.picture,
            displayName = profile?.displayedName ?: "",
            size = 48.dp
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile?.displayedName ?: NostrKeyUtils.shortenPubkey(conversation.partnerPubkey),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                Text(
                    text = formatTalkTime(conversation.lastMessageTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textTertiary
                )
            }
            Text(
                text = conversation.lastMessage.ifBlank { "メッセージなし" },
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MessageBubble(message: DmMessage) {
    val nuruColors = LocalNuruColors.current

    // Extract CW
    val cwRegex = Regex("""^\[CW:\s*([^\]]*)\]\s*\n\n([\s\S]*)$""")
    val cwMatch = cwRegex.find(message.content)
    val displayContent = cwMatch?.groupValues?.get(2) ?: message.content
    val cwReason = cwMatch?.groupValues?.get(1)
    var isCwRevealed by remember { mutableStateOf(cwReason == null) }

    // Extract images
    val images = IMAGE_REGEX.findAll(displayContent).map { it.value }.toList()
    val cleanText = IMAGE_REGEX.replace(displayContent, "").trim()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .background(
                        if (message.isMine) LineGreen else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (message.isMine) 16.dp else 4.dp,
                            bottomEnd = if (message.isMine) 4.dp else 16.dp
                        )
                    )
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    if (cwReason != null && !isCwRevealed) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { isCwRevealed = true }
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                null,
                                tint = if (message.isMine) Color.White else Color(0xFFFF9800),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "CW: $cwReason",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (message.isMine) Color.White else Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (cwReason != null) {
                            Text(
                                "CW: $cwReason",
                                style = MaterialTheme.typography.labelSmall,
                                color = (if (message.isMine) Color.White else Color(0xFFFF9800)).copy(alpha = 0.7f),
                                modifier = Modifier.clickable { isCwRevealed = false }
                            )
                            Spacer(Modifier.height(4.dp))
                        }

                        if (cleanText.isNotBlank()) {
                            Text(
                                text = cleanText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (message.isMine) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        images.forEach { url ->
                            Spacer(Modifier.height(4.dp))
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                }
            }
            Text(
                text = formatTalkTime(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

fun formatTalkTime(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - unixSec
    return when {
        diff < 60 -> "たった今"
        diff < 3600 -> "${diff / 60}分前"
        diff < 86400 -> "${diff / 3600}時間前"
        else -> SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(unixSec * 1000))
    }
}
