package com.example.nostr.ui.screens.dm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectMessageScreen(
    viewModel: DirectMessageViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedConversation by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(Unit) {
        viewModel.fetchDMs()
    }

    if (selectedConversation != null) {
        ChatScreen(
            conversation = selectedConversation!!,
            viewModel = viewModel,
            onBack = { selectedConversation = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Messages") },
                    actions = {
                        IconButton(onClick = { /* TODO: New message */ }) {
                            Icon(Icons.Default.Edit, contentDescription = "New Message")
                        }
                        IconButton(onClick = { viewModel.fetchDMs() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (!uiState.isLoggedIn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Not logged in",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Go to Settings to login",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (uiState.isLoading && uiState.conversations.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (uiState.conversations.isEmpty()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "No messages yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = uiState.conversations,
                                key = { it.id }
                            ) { conversation ->
                                ConversationItem(
                                    conversation = conversation,
                                    onClick = {
                                        viewModel.openChat(conversation.otherPubkey)
                                        selectedConversation = conversation
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    if (uiState.isLoading && uiState.conversations.isNotEmpty()) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        AsyncImage(
            model = conversation.profile?.picture,
            contentDescription = "Avatar",
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name and last message
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.profile?.getDisplayNameOrName()
                        ?: shortenPubkey(conversation.otherPubkey),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                conversation.lastMessage?.let { message ->
                    Text(
                        text = formatTimestamp(message.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = conversation.lastMessage?.decryptedContent ?: "Encrypted message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Unread badge
        if (conversation.unreadCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Badge {
                Text(conversation.unreadCount.toString())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversation: Conversation,
    viewModel: DirectMessageViewModel,
    onBack: () -> Unit
) {
    val chatState by viewModel.chatState.collectAsState()
    var messageInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = chatState.otherProfile?.picture ?: conversation.profile?.picture,
                            contentDescription = "Avatar",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = chatState.otherProfile?.getDisplayNameOrName()
                                ?: conversation.profile?.getDisplayNameOrName()
                                ?: shortenPubkey(conversation.otherPubkey),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        placeholder = { Text("Message") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (messageInput.isNotBlank()) {
                                viewModel.sendMessage(messageInput)
                                messageInput = ""
                            }
                        },
                        enabled = messageInput.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (messageInput.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            reverseLayout = true,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = chatState.messages.reversed(),
                key = { it.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    isOutgoing = message.isOutgoing
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: com.example.nostr.data.database.entity.DirectMessageEntity,
    isOutgoing: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isOutgoing)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.decryptedContent ?: "Encrypted",
                    color = if (isOutgoing)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOutgoing)
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
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
        else -> {
            val date = java.util.Date(timestamp * 1000)
            java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(date)
        }
    }
}
