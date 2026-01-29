package com.example.nostr.ui.screens.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.nostr.ui.theme.LikeColor
import com.example.nostr.ui.theme.RepostColor
import com.example.nostr.ui.theme.ZapColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showPostDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.timelineMode == TimelineMode.GLOBAL,
                            onClick = { viewModel.setTimelineMode(TimelineMode.GLOBAL) },
                            label = { Text("Global") }
                        )
                        FilterChip(
                            selected = uiState.timelineMode == TimelineMode.FOLLOWING,
                            onClick = { viewModel.setTimelineMode(TimelineMode.FOLLOWING) },
                            label = { Text("Following") }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshTimeline() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPostDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Edit, contentDescription = "New Post")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isRefreshing && uiState.events.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.events.isEmpty()) {
                Text(
                    text = "No posts yet",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = uiState.events,
                        key = { it.event.id }
                    ) { item ->
                        PostItem(
                            item = item,
                            onLike = { viewModel.likeEvent(item.event) },
                            onRepost = { viewModel.repostEvent(item.event) },
                            onReply = { /* TODO: Implement reply */ },
                            onZap = { /* TODO: Implement zap */ }
                        )
                        HorizontalDivider()
                    }
                }
            }

            // Pull to refresh indicator
            if (uiState.isRefreshing && uiState.events.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    // Post dialog
    if (showPostDialog) {
        PostDialog(
            onDismiss = { showPostDialog = false },
            onPost = { content ->
                viewModel.postNote(content)
                showPostDialog = false
            }
        )
    }
}

@Composable
fun PostItem(
    item: TimelineItem,
    onLike: () -> Unit,
    onRepost: () -> Unit,
    onReply: () -> Unit,
    onZap: () -> Unit
) {
    val profile = item.profile
    val event = item.event

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header: Avatar + Name + Time
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            AsyncImage(
                model = profile?.picture,
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Name and time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile?.getDisplayNameOrName() ?: shortenPubkey(event.pubkey),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatTimestamp(event.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // More options
            IconButton(onClick = { /* TODO: Show options */ }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content
        Text(
            text = event.content,
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Reply
            ActionButton(
                icon = Icons.Default.ChatBubbleOutline,
                count = null,
                onClick = onReply,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Repost
            ActionButton(
                icon = if (item.hasReposted) Icons.Default.Repeat else Icons.Outlined.Repeat,
                count = if (item.repostCount > 0) item.repostCount else null,
                onClick = onRepost,
                tint = if (item.hasReposted) RepostColor else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Like
            ActionButton(
                icon = if (item.hasLiked) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                count = if (item.likeCount > 0) item.likeCount else null,
                onClick = onLike,
                tint = if (item.hasLiked) LikeColor else MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Zap
            ActionButton(
                icon = Icons.Default.Bolt,
                count = null,
                onClick = onZap,
                tint = ZapColor
            )
        }
    }
}

@Composable
fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int?,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = tint
            )
        }
    }
}

@Composable
fun PostDialog(
    onDismiss: () -> Unit,
    onPost: (String) -> Unit
) {
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Post") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("What's happening?") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                maxLines = 10
            )
        },
        confirmButton = {
            Button(
                onClick = { onPost(content) },
                enabled = content.isNotBlank()
            ) {
                Text("Post")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun shortenPubkey(pubkey: String): String {
    return if (pubkey.length > 12) {
        "${pubkey.take(8)}...${pubkey.takeLast(4)}"
    } else pubkey
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp

    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> {
            val date = java.util.Date(timestamp * 1000)
            java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(date)
        }
    }
}
