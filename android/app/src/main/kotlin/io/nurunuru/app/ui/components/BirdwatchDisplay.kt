package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.ui.theme.LocalNuruColors

private val CONTEXT_TYPE_LABELS = mapOf(
    "misleading" to "誤解を招く情報",
    "missing_context" to "背景情報が不足",
    "factual_error" to "事実誤認",
    "outdated" to "古い情報",
    "satire" to "風刺・ジョーク"
)

@Composable
fun BirdwatchDisplay(
    notes: List<NostrEvent>,
    onRate: (String, String) -> Unit = { _, _ -> },
    onAuthorClick: (String) -> Unit = {}
) {
    if (notes.isEmpty()) return
    val nuruColors = LocalNuruColors.current
    var isExpanded by remember { mutableStateOf(false) }
    val sortedNotes = notes.sortedByDescending { it.createdAt }
    val displayNotes = if (isExpanded) sortedNotes else sortedNotes.take(1)
    val hasMore = sortedNotes.size > 1

    Surface(
        color = Color(0xFF2196F3).copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF2196F3).copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2196F3).copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = io.nurunuru.app.ui.icons.NuruIcons.BirdwatchCheck,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "読者からの追加情報",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF2196F3),
                    fontWeight = FontWeight.Bold
                )
                if (hasMore && !isExpanded) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "+${sortedNotes.size - 1}件",
                        style = MaterialTheme.typography.labelSmall,
                        color = nuruColors.textTertiary
                    )
                }
            }

            // Notes
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                displayNotes.forEachIndexed { index, note ->
                    BirdwatchNoteItem(note = note, onRate = onRate, onAuthorClick = onAuthorClick)
                    if (index < displayNotes.size - 1) {
                        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                    }
                }
            }

            // Footer / Toggle
            if (hasMore) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isExpanded) "折りたたむ" else "他 ${sortedNotes.size - 1}件のコンテキストを表示",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2196F3),
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BirdwatchNoteItem(
    note: NostrEvent,
    onRate: (String, String) -> Unit,
    onAuthorClick: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val contextType = note.getTagValue("l") ?: "missing_context"
    val label = CONTEXT_TYPE_LABELS[contextType]
    val sourceUrl = extractSourceUrl(note.content)
    val cleanContent = note.content.replace(Regex("https?://[^\\s]+"), "").trim()
    var ratedByMe by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Surface(
                color = Color(0xFF2196F3).copy(alpha = 0.1f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = label,
                    color = Color(0xFF2196F3),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = cleanContent,
            style = MaterialTheme.typography.bodySmall,
            color = nuruColors.textPrimary,
            lineHeight = 18.sp
        )

        if (sourceUrl != null) {
            val context = androidx.compose.ui.platform.LocalContext.current
            Row(
                modifier = Modifier.clickable {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sourceUrl))
                        context.startActivity(intent)
                    } catch (e: Exception) {}
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color(0xFF2196F3), modifier = Modifier.size(12.dp))
                Text("ソースを表示", color = Color(0xFF2196F3), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { onAuthorClick(note.pubkey) }
            ) {
                Text(
                    text = NostrKeyUtils.shortenPubkey(note.pubkey),
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.textSecondary,
                    fontWeight = FontWeight.Medium
                )
                Text("·", color = nuruColors.textTertiary)
                Text(
                    text = formatBirdwatchTimestamp(note.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.textSecondary
                )
            }

            if (ratedByMe == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("役に立ちましたか？", style = MaterialTheme.typography.labelSmall, color = nuruColors.textTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BirdwatchRateButton(text = "はい", color = Color(0xFF4CAF50)) {
                            ratedByMe = "helpful"
                            onRate(note.id, "helpful")
                        }
                        BirdwatchRateButton(text = "いいえ", color = Color(0xFFF44336)) {
                            ratedByMe = "not_helpful"
                            onRate(note.id, "not_helpful")
                        }
                    }
                }
            } else {
                Text(
                    text = "評価済み",
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.lineGreen,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BirdwatchRateButton(text: String, color: Color, onClick: () -> Unit) {
    val nuruColors = LocalNuruColors.current
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, nuruColors.border),
        color = Color.Transparent
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = nuruColors.textSecondary
        )
    }
}

private fun extractSourceUrl(content: String): String? {
    val regex = Regex("https?://[^\\s]+")
    return regex.find(content)?.value
}

private fun formatBirdwatchTimestamp(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - unixSec
    return when {
        diff < 60 -> "たった今"
        diff < 3600 -> "${diff / 60}分前"
        diff < 86400 -> "${diff / 3600}時間前"
        else -> "${diff / 86400}日前"
    }
}
