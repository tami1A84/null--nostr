package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.TalkViewModel
import java.text.SimpleDateFormat
import java.util.*

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "トーク",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LineGreen)
                    }
                }
                conversations.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "まだトークがありません",
                            color = nuruColors.textTertiary
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(conversations, key = { it.partnerPubkey }) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                onClick = { viewModel.openConversation(conversation.partnerPubkey) }
                            )
                            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationItem(
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayPk = conversation.partnerPubkey.take(8) + "..."
                Text(
                    text = profile?.displayedName ?: displayPk,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = formatTime(conversation.lastMessageTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textTertiary
                )
            }
            Text(
                text = conversation.lastMessage.ifBlank { "暗号化メッセージ" },
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary,
                maxLines = 1
            )
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
    val nuruColors = LocalNuruColors.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val displayPk = partnerPubkey.take(8) + "..."
                    Text(
                        displayPk,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeConversation() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LineGreen)
                        }
                    }
                    else -> {
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
            }

            // Input bar
            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .background(nuruColors.bgTertiary, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(LineGreen),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        Box {
                            if (inputText.isEmpty()) {
                                Text("メッセージ...", color = nuruColors.textTertiary,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            innerTextField()
                        }
                    }
                )
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !isSending) {
                            viewModel.sendMessage(partnerPubkey, inputText)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = LineGreen, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "送信",
                            tint = if (inputText.isNotBlank()) LineGreen else nuruColors.textTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: DmMessage) {
    val nuruColors = LocalNuruColors.current
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
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isMine) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

private fun formatTime(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - unixSec
    return when {
        diff < 3600 -> "${diff / 60}分前"
        diff < 86400 -> "${diff / 3600}時間前"
        else -> SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(unixSec * 1000))
    }
}
