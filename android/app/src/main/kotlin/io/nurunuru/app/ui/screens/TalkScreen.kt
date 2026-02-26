package io.nurunuru.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.TalkViewModel

@Composable
fun TalkScreen(viewModel: TalkViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.activeConversation != null) {
        ConversationScreen(
            viewModel = viewModel,
            partnerPubkey = uiState.activeConversation!!,
            messages = uiState.messages,
            isLoading = uiState.messagesLoading,
            isSending = uiState.sendingMessage
        )
    } else {
        ConversationListScreen(
            viewModel = viewModel,
            conversations = uiState.conversations,
            isLoading = uiState.isLoading
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListScreen(
    viewModel: TalkViewModel,
    conversations: List<DmConversation>,
    isLoading: Boolean
) {
    val nuruColors = LocalNuruColors.current
    var showNewChatModal by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("トーク", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = { showNewChatModal = true },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(32.dp)
                            .background(LineGreen, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, "新規トーク", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (showNewChatModal) {
            NewChatModal(
                onDismiss = { showNewChatModal = false },
                onStartChat = { pubkey ->
                    showNewChatModal = false
                    viewModel.openConversation(pubkey)
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LineGreen)
                    }
                }
                conversations.isEmpty() -> TalkEmptyState()
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(conversations, key = { it.partnerPubkey }) { conversation ->
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Column {
                                    ConversationItem(
                                        conversation = conversation,
                                        onClick = { viewModel.openConversation(conversation.partnerPubkey) }
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = nuruColors.border,
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    viewModel: TalkViewModel,
    partnerPubkey: String,
    messages: List<DmMessage>,
    isLoading: Boolean,
    isSending: Boolean
) {
    val listState = rememberLazyListState()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> /* TODO */ }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text(NostrKeyUtils.shortenPubkey(partnerPubkey), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeConversation() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LineGreen)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.event.id }) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }
            }

            HorizontalDivider(color = LocalNuruColors.current.border, thickness = 0.5.dp)
            MessageInputBar(
                onSendMessage = { text, cw ->
                    val finalContent = if (cw != null) "[CW: $cw]\n\n$text" else text
                    viewModel.sendMessage(partnerPubkey, finalContent)
                },
                onImageAttach = { imagePickerLauncher.launch("image/*") },
                isSending = isSending
            )
        }
    }
}
