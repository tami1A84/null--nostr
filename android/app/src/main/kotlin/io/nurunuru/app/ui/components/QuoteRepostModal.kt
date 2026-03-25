package io.nurunuru.app.ui.components

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.nurunuru.app.data.*
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rust.nostr.sdk.EventId

private const val QUOTE_MAX_CHARS = 140

@Composable
fun QuoteRepostModal(
    post: ScoredPost,
    myPubkey: String,
    repository: NostrRepository,
    onDismiss: () -> Unit,
    onProfileClick: (String) -> Unit = {}
) {
    val nuruColors = LocalNuruColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val toastState = LocalToastState.current

    var text by remember { mutableStateOf(TextFieldValue("")) }
    var isPosting by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf("") }
    var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedCustomEmojis by remember { mutableStateOf<List<CustomEmoji>>(emptyList()) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showRelayPanel by remember { mutableStateOf(false) }
    var showCWInput by remember { mutableStateOf(false) }
    var contentWarning by remember { mutableStateOf("") }

    val allRelays = remember { repository.getSavedRelayUrls() }
    var selectedRelays by remember { mutableStateOf<Set<String>>(allRelays.toSet()) }

    // Speech to Text
    var isSTTActive by remember { mutableStateOf(false) }
    var hasMicPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasMicPermission = it }
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(speechRecognizer) { onDispose { speechRecognizer.destroy() } }
    val sttIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    val focusRequester = remember { FocusRequester() }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> selectedImages = (selectedImages + uris).take(3) }

    fun buildHighlightedText(raw: String) = buildAnnotatedString {
        val hashtagRegex = Regex("#([\\w\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+)")
        var lastIdx = 0
        hashtagRegex.findAll(raw).forEach { match ->
            append(raw.substring(lastIdx, match.range.first))
            withStyle(SpanStyle(color = nuruColors.lineGreen)) { append(match.value) }
            lastIdx = match.range.last + 1
        }
        append(raw.substring(lastIdx))
    }

    fun handlePost() {
        if (isPosting) return
        isPosting = true
        coroutineScope.launch {
            try {
                var finalContent = text.text
                val tags = mutableListOf<List<String>>()

                if (selectedImages.isNotEmpty()) {
                    uploadProgress = "画像をアップロード中... (0/${selectedImages.size})"
                    val uploadedUrls = withContext(Dispatchers.IO) {
                        val deferreds = selectedImages.mapIndexed { index, uri ->
                            async {
                                try {
                                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                                    if (bytes != null) {
                                        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                                        repository.uploadImage(bytes, mimeType)
                                    } else null
                                } catch (e: Exception) {
                                    android.util.Log.w("QuoteRepostModal", "Image upload failed [$index]: ${e.message}")
                                    null
                                }
                            }
                        }
                        var completed = 0
                        deferreds.mapNotNull { deferred ->
                            deferred.await().also {
                                completed++
                                withContext(Dispatchers.Main) {
                                    uploadProgress = "画像をアップロード中... ($completed/${selectedImages.size})"
                                }
                            }
                        }
                    }
                    if (uploadedUrls.isNotEmpty()) {
                        finalContent += "\n" + uploadedUrls.joinToString("\n")
                    }
                }

                if (showCWInput && contentWarning.isNotBlank()) {
                    tags.add(listOf("content-warning", contentWarning))
                }
                selectedCustomEmojis.forEach { emoji ->
                    tags.add(listOf("emoji", emoji.shortcode, emoji.url))
                }

                val hashtagRegex = Regex("#([\\w\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+)")
                hashtagRegex.findAll(finalContent).map { it.groupValues[1] }.distinct()
                    .forEach { tags.add(listOf("t", it.lowercase())) }

                tags.add(listOf("q", post.event.id, "", ""))

                val note1 = try { EventId.parse(post.event.id).toBech32() } catch (_: Exception) { null }
                val appendedContent = if (note1 != null) {
                    if (finalContent.isBlank()) "nostr:$note1" else "$finalContent\nnostr:$note1"
                } else finalContent

                val targetRelayList = if (selectedRelays.size != allRelays.size) selectedRelays.toList() else null
                repository.publishNote(
                    content = appendedContent,
                    customTags = tags,
                    targetRelays = targetRelayList
                )
                toastState.show(ToastMessages.POST_SUCCESS, ToastType.SUCCESS)
                onDismiss()
            } catch (e: Exception) {
                toastState.show("エラーが発生しました: ${e.message}", ToastType.ERROR)
            } finally {
                isPosting = false
                uploadProgress = ""
            }
        }
    }

    val remaining = QUOTE_MAX_CHARS - text.text.length
    val canPost = (text.text.isNotBlank() || selectedImages.isNotEmpty()) && remaining >= 0 && !isPosting

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
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
                    val spokenText = matches[0]
                    val combined = text.text + (if (text.text.isEmpty()) "" else " ") + spokenText
                    if (combined.length <= QUOTE_MAX_CHARS) {
                        text = TextFieldValue(buildHighlightedText(combined), TextRange(combined.length))
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(16.dp),
            color = nuruColors.bgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = nuruColors.textSecondary)
                    }
                    Text("引用投稿", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = nuruColors.textPrimary)
                    Button(
                        onClick = { handlePost() },
                        enabled = canPost,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LineGreen,
                            disabledContainerColor = LineGreen.copy(alpha = 0.3f)
                        ),
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

                // ── Scrollable body ─────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (showCWInput) {
                        CWInput(contentWarning = contentWarning, onValueChange = { contentWarning = it })
                    }

                    if (uploadProgress.isNotEmpty()) {
                        Text(uploadProgress, fontSize = 12.sp, color = nuruColors.textTertiary)
                    }

                    // テキスト入力
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            if (it.text.length <= QUOTE_MAX_CHARS) {
                                text = if (it.composition != null) {
                                    it
                                } else {
                                    TextFieldValue(
                                        annotatedString = buildHighlightedText(it.text),
                                        selection = it.selection
                                    )
                                }
                            }
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = nuruColors.textPrimary),
                        cursorBrush = SolidColor(LineGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 80.dp)
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            if (text.text.isEmpty()) {
                                Text("コメントを追加...", color = nuruColors.textTertiary, style = MaterialTheme.typography.bodyLarge)
                            }
                            innerTextField()
                        }
                    )

                    // カスタム絵文字プレビュー
                    if (selectedCustomEmojis.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(selectedCustomEmojis) { emoji ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .background(nuruColors.bgSecondary, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    AsyncImage(
                                        model = emoji.url,
                                        contentDescription = emoji.shortcode,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(":${emoji.shortcode}:", fontSize = 11.sp, color = nuruColors.textTertiary)
                                }
                            }
                        }
                    }

                    // 画像プレビュー
                    if (selectedImages.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(selectedImages) { index, uri ->
                                Box {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    IconButton(
                                        onClick = { selectedImages = selectedImages.toMutableList().also { it.removeAt(index) } },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                            .background(Color.Black.copy(0.5f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }

                    // 引用元プレビュー
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
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // ── ツールバー（キーボード上に固定）──────────────────────────
                Column(modifier = Modifier.imePadding().navigationBarsPadding()) {
                    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                    PostToolbar(
                        selectedImages = selectedImages,
                        showCWInput = showCWInput,
                        isSTTActive = isSTTActive,
                        hasMicPermission = hasMicPermission,
                        remaining = remaining,
                        showRelayPanel = showRelayPanel,
                        hasRelayFilter = selectedRelays.size != allRelays.size,
                        onImageClick = { imagePickerLauncher.launch("image/*") },
                        onCWClick = { showCWInput = !showCWInput },
                        onEmojiClick = { showEmojiPicker = !showEmojiPicker; showRelayPanel = false },
                        onMicClick = {
                            if (hasMicPermission) {
                                if (isSTTActive) speechRecognizer.stopListening()
                                else speechRecognizer.startListening(sttIntent)
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onRelayClick = { showRelayPanel = !showRelayPanel; showEmojiPicker = false }
                    )

                    AnimatedVisibility(visible = showRelayPanel) {
                        RelaySelectPanel(
                            relays = allRelays,
                            selectedRelays = selectedRelays,
                            nip70Protected = false,
                            onToggleRelay = { url ->
                                selectedRelays = if (url in selectedRelays) selectedRelays - url else selectedRelays + url
                            },
                            onToggleNip70 = {}
                        )
                    }

                    if (showEmojiPicker) {
                        EmojiPicker(
                            pubkey = myPubkey,
                            onSelect = { emoji ->
                                val newText = text.text + ":${emoji.shortcode}:"
                                if (newText.length <= QUOTE_MAX_CHARS) {
                                    text = TextFieldValue(buildHighlightedText(newText), TextRange(newText.length))
                                    if (!selectedCustomEmojis.any { it.shortcode == emoji.shortcode }) {
                                        selectedCustomEmojis = selectedCustomEmojis + emoji
                                    }
                                }
                                showEmojiPicker = false
                            },
                            onClose = { showEmojiPicker = false },
                            repository = repository,
                            individualOnly = true
                        )
                    }
                }
            }
        }
    }
}
