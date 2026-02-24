package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import io.nurunuru.app.data.NostrKeyUtils
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
    var showCWInput by remember { mutableStateOf(false) }
    var cwReason by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        // TODO: Handle image selection and upload
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        NostrKeyUtils.shortenPubkey(partnerPubkey),
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

            // Input bar
            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (showCWInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = cwReason,
                            onValueChange = { cwReason = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFFF9800)),
                            cursorBrush = SolidColor(Color(0xFFFF9800)),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (cwReason.isEmpty()) {
                                        Text("警告の理由", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF9800).copy(alpha = 0.5f))
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    HorizontalDivider(color = Color(0xFFFF9800).copy(alpha = 0.1f), thickness = 0.5.dp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Outlined.Image, "画像", tint = nuruColors.textTertiary)
                    }
                    IconButton(onClick = { showCWInput = !showCWInput }) {
                        Icon(Icons.Outlined.Warning, "CW", tint = if (showCWInput) Color(0xFFFF9800) else nuruColors.textTertiary)
                    }

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
                                val finalContent = if (showCWInput && cwReason.isNotBlank()) {
                                    "[CW: $cwReason]\n\n$inputText"
                                } else inputText
                                viewModel.sendMessage(partnerPubkey, finalContent)
                                inputText = ""
                                cwReason = ""
                                showCWInput = false
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
}

@Composable
private fun MessageBubble(message: DmMessage) {
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
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

private val IMAGE_REGEX = Regex(
    "https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|avif)(\\?[^\\s]*)?",
    RegexOption.IGNORE_CASE
)

private fun formatTime(unixSec: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - unixSec
    return when {
        diff < 3600 -> "${diff / 60}分前"
        diff < 86400 -> "${diff / 3600}時間前"
        else -> SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(unixSec * 1000))
    }
}
