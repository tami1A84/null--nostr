package io.nurunuru.app.ui.components

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Router
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.data.*
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_NOTE_LENGTH = 140

@Composable
fun PostModal(
    myPubkey: String,
    pictureUrl: String?,
    displayName: String,
    repository: NostrRepository,
    replyToId: String? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val nuruColors = LocalNuruColors.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var contentWarning by remember { mutableStateOf("") }
    var showCWInput by remember { mutableStateOf(false) }
    var selectedImages by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var posting by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var selectedCustomEmojis by remember { mutableStateOf<List<CustomEmoji>>(emptyList()) }
    // var showRecorder by remember { mutableStateOf(false) }
    // var recordedVideo by remember { mutableStateOf<RecordedVideo?>(null) }

    // リレー選択 + NIP-70
    val allRelays = remember { repository.getSavedRelayUrls() }
    var selectedRelays by remember { mutableStateOf<Set<String>>(allRelays.toSet()) }
    var nip70Protected by remember { mutableStateOf(false) }
    var showRelayPanel by remember { mutableStateOf(false) }

    BackHandler {
        onDismiss()
    }

    // Speech to Text
    var isSTTActive by remember { mutableStateOf(false) }
    var hasMicPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasMicPermission = it }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    DisposableEffect(speechRecognizer) {
        onDispose {
            speechRecognizer.destroy()
        }
    }

    val sttIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    val toastState = LocalToastState.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selectedImages = (selectedImages + uris).take(3)
    }

    val focusRequester = remember { FocusRequester() }
    val currentLength = remember(text.text) { text.text.length }
    val remaining = MAX_NOTE_LENGTH - currentLength
    val canPost = (text.text.isNotBlank() || selectedImages.isNotEmpty()) && remaining >= 0 && !posting

    // Build AnnotatedString with colored hashtags for the input field
    fun buildHighlightedText(raw: String): AnnotatedString = buildAnnotatedString {
        val hashtagRegex = Regex("#([\\w\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+)")
        var lastIdx = 0
        hashtagRegex.findAll(raw).forEach { match ->
            append(raw.substring(lastIdx, match.range.first))
            withStyle(SpanStyle(color = nuruColors.lineGreen)) { append(match.value) }
            lastIdx = match.range.last + 1
        }
        append(raw.substring(lastIdx))
    }

    val scope = rememberCoroutineScope()

    fun handlePost() {
        if (!canPost) return
        scope.launch {
            try {
                posting = true
                var finalContent = text.text
                val tags = mutableListOf<List<String>>()

                if (selectedImages.isNotEmpty()) {
                    uploadProgress = "画像をアップロード中... (0/${selectedImages.size})"
                    val uploadedUrls = withContext(Dispatchers.IO) {
                        // Parallel uploads (web 版と同じ挙動)
                        val deferreds = selectedImages.mapIndexed { index, uri ->
                            async {
                                try {
                                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                                    if (bytes != null) {
                                        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                                        repository.uploadImage(bytes, mimeType)
                                    } else null
                                } catch (e: Exception) {
                                    android.util.Log.w("PostModal", "Image upload failed [${index}]: ${e.message}")
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

                // recordedVideo?.let { video -> ... }  // 動画投稿機能停止中

                if (replyToId != null) {
                    tags.add(listOf("e", replyToId, "", "reply"))
                }
                if (showCWInput && contentWarning.isNotBlank()) {
                    tags.add(listOf("content-warning", contentWarning))
                }
                selectedCustomEmojis.forEach { emoji ->
                    tags.add(listOf("emoji", emoji.shortcode, emoji.url))
                }

                val hashtags = Regex("#([\\w\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\uFF00-\\uFFEF]+)").findAll(finalContent).map { it.groupValues[1] }.distinct()
                hashtags.forEach { tags.add(listOf("t", it.lowercase())) }

                val targetRelayList = if (selectedRelays.size != allRelays.size) selectedRelays.toList() else null
                val success = repository.publishNote(
                    content = finalContent,
                    replyToId = replyToId,
                    contentWarning = contentWarning.takeIf { showCWInput && it.isNotBlank() },
                    customTags = tags,
                    kind = NostrKind.TEXT_NOTE,
                    targetRelays = targetRelayList,
                    nip70Protected = nip70Protected
                )

                if (success != null) {
                    toastState.show(ToastMessages.POST_SUCCESS, ToastType.SUCCESS)
                    onSuccess()
                    onDismiss()
                } else {
                    toastState.show(ToastMessages.POST_FAILED, ToastType.ERROR)
                }
            } catch (e: Exception) {
                toastState.show("エラーが発生しました: ${e.message}", ToastType.ERROR)
            } finally {
                posting = false
                uploadProgress = ""
            }
        }
    }

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
                    val combinedText = text.text + (if (text.text.isEmpty()) "" else " ") + spokenText
                    if (combinedText.length <= MAX_NOTE_LENGTH) {
                        text = TextFieldValue(buildHighlightedText(combinedText), TextRange(combinedText.length))
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = nuruColors.bgPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PostHeader(
                title = if (replyToId != null) "返信" else "新規投稿",
                onDismiss = onDismiss,
                onPost = { handlePost() },
                canPost = canPost,
                posting = posting
            )

            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (showCWInput) {
                    CWInput(
                        contentWarning = contentWarning,
                        onValueChange = { contentWarning = it }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                if (it.text.length <= MAX_NOTE_LENGTH) {
                                    // IME変換中（composition != null）はAnnotatedStringを再構築しない。
                                    // 再構築するとcomposition情報が失われ、変換が強制確定される。
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
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(nuruColors.lineGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 120.dp)
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                if (text.text.isEmpty()) {
                                    Text(
                                        text = if (replyToId != null) "返信を入力..." else "いまどうしてる？",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = nuruColors.textTertiary
                                    )
                                }
                                innerTextField()
                            }
                        )

                        if (selectedImages.isNotEmpty()) {
                            ImagePreviewList(
                                images = selectedImages,
                                onRemove = { index ->
                                    selectedImages = selectedImages.toMutableList().also { it.removeAt(index) }
                                }
                            )
                        }

                        // Custom emoji preview row
                        if (selectedCustomEmojis.isNotEmpty()) {
                            androidx.compose.foundation.lazy.LazyRow(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
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
                                        Text(
                                            text = ":${emoji.shortcode}:",
                                            fontSize = 11.sp,
                                            color = nuruColors.textTertiary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Toolbar + EmojiPicker pinned above keyboard
            Column(modifier = Modifier.imePadding().navigationBarsPadding()) {
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                PostToolbar(
                    selectedImages = selectedImages,
                    showCWInput = showCWInput,
                    isSTTActive = isSTTActive,
                    hasMicPermission = hasMicPermission,
                    remaining = remaining,
                    showRelayPanel = showRelayPanel,
                    hasRelayFilter = selectedRelays.size != allRelays.size || nip70Protected,
                    onImageClick = { imagePickerLauncher.launch("image/*") },
                    onCWClick = { showCWInput = !showCWInput },
                    onEmojiClick = { showEmojiPicker = !showEmojiPicker },
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

                // リレー選択パネル（折りたたみ）
                AnimatedVisibility(
                    visible = showRelayPanel,
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    RelaySelectPanel(
                        relays = allRelays,
                        selectedRelays = selectedRelays,
                        nip70Protected = nip70Protected,
                        onToggleRelay = { url ->
                            selectedRelays = if (url in selectedRelays)
                                selectedRelays - url else selectedRelays + url
                        },
                        onToggleNip70 = { nip70Protected = !nip70Protected }
                    )
                }

                if (showEmojiPicker) {
                    EmojiPicker(
                        pubkey = myPubkey,
                        onSelect = { emoji ->
                            val newText = text.text + ":${emoji.shortcode}:"
                            if (newText.length <= MAX_NOTE_LENGTH) {
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

    // 動画投稿機能停止中
    // if (showRecorder) {
    //     DivineVideoRecorder(
    //         onComplete = { recordedVideo = it; selectedImages = emptyList(); showRecorder = false },
    //         onClose = { showRecorder = false },
    //         repository = repository
    //     )
    // }
}

@Composable
private fun PostHeader(
    title: String,
    onDismiss: () -> Unit,
    onPost: () -> Unit,
    canPost: Boolean,
    posting: Boolean
) {
    val nuruColors = LocalNuruColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "キャンセル",
            color = nuruColors.textSecondary,
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(4.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = nuruColors.textPrimary,
            modifier = Modifier.align(Alignment.Center)
        )

        Button(
            onClick = onPost,
            enabled = canPost,
            modifier = Modifier.align(Alignment.CenterEnd),
            colors = ButtonDefaults.buttonColors(
                containerColor = nuruColors.lineGreen,
                disabledContainerColor = nuruColors.lineGreen.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (posting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("投稿", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
internal fun PostToolbar(
    selectedImages: List<android.net.Uri>,
    showCWInput: Boolean,
    isSTTActive: Boolean,
    hasMicPermission: Boolean,
    remaining: Int,
    showRelayPanel: Boolean,
    hasRelayFilter: Boolean,
    onImageClick: () -> Unit,
    onCWClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onMicClick: () -> Unit,
    onRelayClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // IconButton(onClick = onVideoClick) { ... }  // 動画投稿機能停止中
        IconButton(onClick = onImageClick, enabled = selectedImages.size < 3) {
            Icon(NuruIcons.Image, "画像", tint = if (selectedImages.isNotEmpty()) nuruColors.lineGreen else nuruColors.textTertiary)
        }
        IconButton(onClick = onCWClick) {
            Icon(NuruIcons.Warning, "CW", tint = if (showCWInput) Color(0xFFFF9800) else nuruColors.textTertiary)
        }
        IconButton(onClick = onEmojiClick) {
            Icon(NuruIcons.Emoji, "絵文字", tint = nuruColors.textTertiary)
        }
        IconButton(onClick = onMicClick) {
            Icon(
                NuruIcons.Mic,
                "音声入力",
                tint = if (isSTTActive) Color.Red else if (hasMicPermission) nuruColors.textTertiary else nuruColors.textTertiary.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onRelayClick) {
            Icon(
                Icons.Default.Router,
                "リレー選択",
                tint = if (showRelayPanel || hasRelayFilter) LineGreen else nuruColors.textTertiary
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "$remaining",
            fontSize = 12.sp,
            color = if (remaining < 0) Color.Red else if (remaining < 20) Color(0xFFFF9800) else nuruColors.textTertiary,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
internal fun RelaySelectPanel(
    relays: List<String>,
    selectedRelays: Set<String>,
    nip70Protected: Boolean,
    onToggleRelay: (String) -> Unit,
    onToggleNip70: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(nuruColors.bgSecondary)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("投稿先リレー", fontSize = 11.sp, color = nuruColors.textTertiary, fontWeight = FontWeight.Medium)
        relays.forEach { url ->
            val isSelected = url in selectedRelays
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleRelay(url) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleRelay(url) },
                    modifier = Modifier.size(20.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = LineGreen,
                        checkmarkColor = Color.White,
                        uncheckedColor = nuruColors.textTertiary
                    )
                )
                Text(
                    url.replace("wss://", "").trimEnd('/'),
                    fontSize = 13.sp,
                    color = nuruColors.textPrimary
                )
            }
        }
        // NIP-70 トグル
        androidx.compose.material3.HorizontalDivider(color = nuruColors.border.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleNip70() }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (nip70Protected) Icons.Default.Lock else Icons.Default.LockOpen,
                null,
                tint = if (nip70Protected) LineGreen else nuruColors.textTertiary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Protected (NIP-70)", fontSize = 13.sp, color = nuruColors.textPrimary)
                Text("リレーにリポスト禁止を要求", fontSize = 11.sp, color = nuruColors.textTertiary)
            }
            Switch(
                checked = nip70Protected,
                onCheckedChange = { onToggleNip70() },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LineGreen)
            )
        }
    }
}

@Composable
internal fun CWInput(
    contentWarning: String,
    onValueChange: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(NuruIcons.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = contentWarning,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFFF9800)),
            cursorBrush = SolidColor(Color(0xFFFF9800)),
            decorationBox = { innerTextField ->
                if (contentWarning.isEmpty()) {
                    Text("警告の理由（ネタバレ等）", color = Color(0xFFFF9800).copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                }
                innerTextField()
            }
        )
    }
    HorizontalDivider(color = Color(0xFFFF9800).copy(alpha = 0.2f))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun VideoPreview(
    video: RecordedVideo,
    onRemove: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val verificationLevel = video.proofTags.find { it.getOrNull(0) == "verification" }?.getOrNull(1)
    Box(modifier = Modifier.padding(vertical = 8.dp).size(160.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        VideoPlayer(videoUrl = video.uri.toString(), modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.Black.copy(0.5f), CircleShape)
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        if (verificationLevel != null) {
            val (badgeColor, badgeText) = when (verificationLevel) {
                "verified_mobile" -> Color(0xFF2196F3).copy(alpha = 0.85f) to "🛡"
                "verified_web"    -> Color(0xFF4CAF50).copy(alpha = 0.85f) to "✓"
                else              -> Color(0xFF9E9E9E).copy(alpha = 0.85f) to "P"
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                color = badgeColor,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(badgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = nuruColors.lineGreen,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text("${video.durationSeconds}s LOOP", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun ImagePreviewList(
    images: List<android.net.Uri>,
    onRemove: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        itemsIndexed(images) { index, uri ->
            Box {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = { onRemove(index) },
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp).padding(2.dp).background(Color.Black.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
