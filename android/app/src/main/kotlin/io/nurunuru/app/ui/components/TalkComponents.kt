package io.nurunuru.app.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.DmConversation
import io.nurunuru.app.data.models.DmMessage
import io.nurunuru.app.data.models.MlsGroup
import io.nurunuru.app.data.models.MlsMessage
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import java.text.SimpleDateFormat
import java.util.*

private val IMAGE_REGEX = Regex(
    "https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp|avif)(\\?[^\\s]*)?",
    RegexOption.IGNORE_CASE
)

// Matches [CW: reason] followed by blank line(s) and body. Tolerates \r\n and extra whitespace.
private val CW_REGEX = Regex("""^\[CW:\s*([^\]]*)\]\s*[\r\n]+\s*[\r\n]*([\s\S]*)$""")

@Composable
fun TalkEmptyState() {
    val nuruColors = LocalNuruColors.current
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
    val cwMatch = CW_REGEX.find(message.content)
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

@Composable
fun MessageInputBar(
    onSendMessage: (String) -> Unit,
    onImageAttach: () -> Unit,
    isSending: Boolean,
    myPubkeyHex: String = "",
    repository: io.nurunuru.app.data.NostrRepository? = null
) {
    val context = LocalContext.current
    val nuruColors = LocalNuruColors.current
    var inputText by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var isSTTActive by remember { mutableStateOf(false) }
    var hasMicPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val hasText = inputText.isNotBlank()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasMicPermission = it }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val sttIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }
    LaunchedEffect(Unit) {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isSTTActive = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isSTTActive = false }
            override fun onError(error: Int) { isSTTActive = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spoken = matches[0]
                    inputText += (if (inputText.isEmpty()) "" else " ") + spoken
                }
                isSTTActive = false
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
    DisposableEffect(Unit) {
        onDispose { speechRecognizer.destroy() }
    }

    val sendBg by animateColorAsState(
        targetValue = if (hasText) LineGreen else nuruColors.bgTertiary,
        animationSpec = tween(150), label = "sendBg"
    )
    val sendTint by animateColorAsState(
        targetValue = if (hasText) Color.White else nuruColors.textTertiary,
        animationSpec = tween(150), label = "sendTint"
    )

    // カスタム絵文字ピッカー
    if (showEmojiPicker && repository != null) {
        EmojiPicker(
            pubkey = myPubkeyHex,
            repository = repository,
            onSelect = { emoji ->
                inputText += ":${emoji.shortcode}:"
                showEmojiPicker = false
            },
            onClose = { showEmojiPicker = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(nuruColors.bgPrimary)
            .imePadding()
    ) {
        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 画像添付
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onImageAttach),
                contentAlignment = Alignment.Center
            ) {
                Icon(NuruIcons.Image, "画像", tint = nuruColors.textSecondary, modifier = Modifier.size(22.dp))
            }

            // カスタム絵文字
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (showEmojiPicker) LineGreen.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { showEmojiPicker = !showEmojiPicker },
                contentAlignment = Alignment.Center
            ) {
                Icon(NuruIcons.Emoji, "絵文字", tint = nuruColors.textSecondary, modifier = Modifier.size(22.dp))
            }

            // 入力バブル（マイクをピル内右端に配置）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .background(nuruColors.bgSecondary, RoundedCornerShape(20.dp))
                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LineGreen),
                        maxLines = 6,
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
                }
                // マイクボタン（ピル内右端）
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isSTTActive) Color.Red.copy(alpha = 0.1f) else Color.Transparent)
                        .clickable {
                            if (hasMicPermission) {
                                if (isSTTActive) speechRecognizer.stopListening()
                                else speechRecognizer.startListening(sttIntent)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        NuruIcons.Mic,
                        contentDescription = "音声入力",
                        tint = when {
                            isSTTActive -> Color.Red
                            hasMicPermission -> nuruColors.textSecondary
                            else -> nuruColors.textTertiary.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 送信ボタン
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .clickable(enabled = hasText && !isSending) {
                        onSendMessage(inputText)
                        inputText = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(NuruIcons.Send, "送信", tint = sendTint,
                        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 45f })
                }
            }
        }
    }
}

@Composable
fun GroupItem(
    group: MlsGroup,
    myPubkeyHex: String = "",
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    // For DMs, show the partner's avatar; for groups, show first member's avatar
    val partnerPubkey = if (group.isDm)
        group.memberPubkeys.firstOrNull { it != myPubkeyHex } ?: group.memberPubkeys.firstOrNull()
    else null
    val profile = partnerPubkey?.let { group.memberProfiles[it] }
    val displayName = when {
        !group.isDm && group.name.isNotBlank() -> group.name
        profile != null -> profile.displayedName
        partnerPubkey != null -> NostrKeyUtils.shortenPubkey(partnerPubkey)
        !group.isDm -> group.groupIdHex.take(8)
        else -> group.groupIdHex.take(8)
    }

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
            displayName = displayName,
            size = 48.dp
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (group.lastMessageTime > 0) {
                    Text(
                        text = formatTalkTime(group.lastMessageTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = nuruColors.textTertiary
                    )
                }
            }
            Text(
                text = group.lastMessage.ifBlank {
                if (group.isDm) "暗号化メッセージ" else "${group.memberPubkeys.size}人のグループ"
            },
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary,
                maxLines = 1
            )
        }
    }
}

@Composable
fun MlsMessageBubble(
    message: MlsMessage,
    myPubkeyHex: String
) {
    val nuruColors = LocalNuruColors.current
    val isMine = message.senderPubkey == myPubkeyHex

    val cwMatch = CW_REGEX.find(message.content)
    val displayContent = cwMatch?.groupValues?.get(2) ?: message.content
    val cwReason = cwMatch?.groupValues?.get(1)
    var isCwRevealed by remember { mutableStateOf(cwReason == null) }

    val images = IMAGE_REGEX.findAll(displayContent).map { it.value }.toList()
    val cleanText = IMAGE_REGEX.replace(displayContent, "").trim()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            if (!isMine) {
                val senderName = message.senderProfile?.displayedName
                    ?: NostrKeyUtils.shortenPubkey(message.senderPubkey)
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = nuruColors.textTertiary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        if (isMine) LineGreen else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isMine) 16.dp else 4.dp,
                            bottomEnd = if (isMine) 4.dp else 16.dp
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
                                tint = if (isMine) Color.White else Color(0xFFFF9800),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "CW: $cwReason",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMine) Color.White else Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (cwReason != null) {
                            Text(
                                "CW: $cwReason",
                                style = MaterialTheme.typography.labelSmall,
                                color = (if (isMine) Color.White else Color(0xFFFF9800)).copy(alpha = 0.7f),
                                modifier = Modifier.clickable { isCwRevealed = false }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (cleanText.isNotBlank()) {
                            Text(
                                text = cleanText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isMine) MaterialTheme.colorScheme.onPrimary
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
