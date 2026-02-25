package io.nurunuru.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import io.nurunuru.app.ui.components.ConversationItem
import io.nurunuru.app.ui.components.MessageBubble
import io.nurunuru.app.ui.components.NewChatModal
import io.nurunuru.app.ui.icons.NuruIcons
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
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Text(
                        "トーク",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showNewChatModal = true },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(32.dp)
                            .background(LineGreen, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新規トーク",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .background(nuruColors.bgTertiary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = NuruIcons.Talk(filled = false),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = nuruColors.textTertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "トークがありません",
                            style = MaterialTheme.typography.bodyLarge,
                            color = nuruColors.textSecondary
                        )
                        Text(
                            "右上の＋から始めましょう",
                            style = MaterialTheme.typography.bodySmall,
                            color = nuruColors.textTertiary
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(conversations, key = { it.partnerPubkey }) { conversation ->
                            Surface(
                                color = MaterialTheme.colorScheme.background
                            ) {
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
                windowInsets = WindowInsets.statusBars,
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
