package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.publishNote
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch
import rust.nostr.sdk.EventId

@Composable
fun QuoteRepostModal(
    post: ScoredPost,
    repository: NostrRepository,
    onDismiss: () -> Unit,
    onProfileClick: (String) -> Unit = {}
) {
    val nuruColors = LocalNuruColors.current
    val coroutineScope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }
    val charCount = text.length
    val MAX_CHARS = 140

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = nuruColors.bgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "閉じる", tint = nuruColors.textSecondary)
                    }
                    Text("引用リツイート", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = nuruColors.textPrimary)
                    Button(
                        onClick = {
                            if (isPosting) return@Button
                            isPosting = true
                            coroutineScope.launch {
                                try {
                                    val note1 = try {
                                        EventId.parse(post.event.id).toBech32()
                                    } catch (_: Exception) { null }
                                    val appendedContent = if (note1 != null) {
                                        if (text.isBlank()) "nostr:$note1"
                                        else "$text\nnostr:$note1"
                                    } else text
                                    repository.publishNote(
                                        content = appendedContent,
                                        customTags = listOf(listOf("q", post.event.id, "", ""))
                                    )
                                    onDismiss()
                                } finally {
                                    isPosting = false
                                }
                            }
                        },
                        enabled = !isPosting && charCount <= MAX_CHARS,
                        colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        if (isPosting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("投稿", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Text input
                    TextField(
                        value = text,
                        onValueChange = { if (it.length <= MAX_CHARS) text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("コメントを追加...", color = nuruColors.textTertiary)
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = nuruColors.textPrimary,
                            unfocusedTextColor = nuruColors.textPrimary,
                            cursorColor = LineGreen
                        ),
                        maxLines = 6
                    )

                    // Char counter
                    val remaining = MAX_CHARS - charCount
                    if (remaining < 20) {
                        Text(
                            text = remaining.toString(),
                            fontSize = 12.sp,
                            color = if (remaining < 0) MaterialTheme.colorScheme.error else nuruColors.textTertiary,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }

                    // Quoted post preview
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = nuruColors.bgSecondary,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, nuruColors.border)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                UserAvatar(
                                    pictureUrl = post.profile?.picture,
                                    displayName = post.profile?.displayedName ?: "",
                                    size = 20.dp
                                )
                                Text(
                                    text = post.profile?.displayedName
                                        ?: NostrKeyUtils.shortenPubkey(post.event.pubkey),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = nuruColors.textPrimary
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = post.event.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textSecondary,
                                maxLines = 5,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
