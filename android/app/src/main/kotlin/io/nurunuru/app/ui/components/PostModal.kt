package io.nurunuru.app.ui.components

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
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
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

private const val MAX_NOTE_LENGTH = 140
private val URL_REGEX = Regex("https?://[^\\s]+|nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalMaterial3Api::class)
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
    var showRecorder by remember { mutableStateOf(false) }
    var recordedVideo by remember { mutableStateOf<RecordedVideo?>(null) }

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
    val currentLength = remember(text.text) { text.text.replace(URL_REGEX, "").length }
    val remaining = MAX_NOTE_LENGTH - currentLength
    val canPost = (text.text.isNotBlank() || selectedImages.isNotEmpty() || recordedVideo != null) && remaining >= 0 && !posting

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismissSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun handlePost() {
        if (!canPost) return
        scope.launch {
            try {
                posting = true
                var finalContent = text.text
                val tags = mutableListOf<List<String>>()

                // Parallel Upload Images
                if (selectedImages.isNotEmpty() && recordedVideo == null) {
                    uploadProgress = "画像をアップロード中..."
                    val uploadedUrls = mutableListOf<String>()
                    selectedImages.forEachIndexed { index, uri ->
                        uploadProgress = "画像をアップロード中... (${index + 1}/${selectedImages.size})"
                        val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
                        if (bytes != null) {
                            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                            val url = repository.uploadImage(bytes, mimeType)
                            if (url != null) uploadedUrls.add(url)
                        }
                    }
                    if (uploadedUrls.isNotEmpty()) {
                        finalContent += "\n" + uploadedUrls.joinToString("\n")
                    }
                }

                // Video tags
                recordedVideo?.let { video ->
                    tags.add(listOf("url", video.url))
                    tags.add(listOf("m", video.mimeType))
                    tags.add(listOf("imeta", "url ${video.url}", "m ${video.mimeType}", "size ${video.size}", "dim 720x720"))
                }

                // Tags
                if (replyToId != null) {
                    tags.add(listOf("e", replyToId, "", "reply"))
                }
                if (showCWInput && contentWarning.isNotBlank()) {
                    tags.add(listOf("content-warning", contentWarning))
                }
                selectedCustomEmojis.forEach { emoji ->
                    tags.add(listOf("emoji", emoji.shortcode, emoji.url))
                }

                val hashtags = Regex("#([\\w\\u3000]+)").findAll(finalContent).map { it.groupValues[1] }.distinct()
                hashtags.forEach { tags.add(listOf("t", it.lowercase())) }

                val success = repository.publishNote(
                    content = finalContent,
                    replyToId = replyToId,
                    contentWarning = contentWarning.takeIf { showCWInput && it.isNotBlank() },
                    customTags = tags,
                    kind = if (recordedVideo != null) NostrKind.VIDEO_LOOP else NostrKind.TEXT_NOTE
                )

                if (success != null) {
                    toastState.show(ToastMessages.POST_SUCCESS, ToastType.SUCCESS)
                    onSuccess()
                    dismissSheet()
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
                    text = TextFieldValue(text.text + (if (text.text.isEmpty()) "" else " ") + spokenText, TextRange(text.text.length + spokenText.length + (if (text.text.isEmpty()) 0 else 1)))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = nuruColors.bgPrimary,
        tonalElevation = 0.dp,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = nuruColors.border) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { dismissSheet() }) {
                    Icon(NuruIcons.Close, "閉じる", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }

                Text(
                    text = if (replyToId != null) "返信" else "新規投稿",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = { handlePost() },
                    enabled = canPost,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nuruColors.lineGreen,
                        disabledContainerColor = nuruColors.lineGreen.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    if (posting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("投稿する", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                // CW Input
                if (showCWInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(NuruIcons.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = contentWarning,
                            onValueChange = { contentWarning = it },
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

                Row(modifier = Modifier.fillMaxWidth()) {
                    UserAvatar(pictureUrl = pictureUrl, displayName = displayName, size = 40.dp)
                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        val inlineContent = mutableMapOf<String, InlineTextContent>()
                        val annotatedText = buildAnnotatedString {
                            val parts = text.text.split(Regex("(:\\w+:|#\\w+|https?://[^\\s]+)")).filter { it.isNotEmpty() }
                            // This is a simplified preview. For a real editor, it's better to use a custom VisualTransformation.
                            append(text.text)
                        }

                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
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

                        // Media Previews
                        if (recordedVideo != null) {
                            Box(modifier = Modifier.padding(vertical = 8.dp).size(160.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                                VideoPlayer(videoUrl = recordedVideo!!.uri.toString(), modifier = Modifier.fillMaxSize())
                                IconButton(
                                    onClick = { recordedVideo = null },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).background(Color.Black.copy(0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                                Surface(
                                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                                    color = nuruColors.lineGreen,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("6.3s LOOP", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                        } else if (selectedImages.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                                itemsIndexed(selectedImages) { index, uri ->
                                    Box {
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = null,
                                            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                        IconButton(
                                            onClick = { selectedImages = selectedImages.toMutableList().also { it.removeAt(index) } },
                                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp).padding(2.dp).background(Color.Black.copy(0.5f), CircleShape)
                                        ) {
                                            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { showRecorder = true }, enabled = selectedImages.isEmpty()) {
                    Icon(NuruIcons.Video, "動画", tint = if (recordedVideo != null) nuruColors.lineGreen else nuruColors.textTertiary)
                }
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }, enabled = recordedVideo == null && selectedImages.size < 3) {
                    Icon(NuruIcons.Image, "画像", tint = if (selectedImages.isNotEmpty()) nuruColors.lineGreen else nuruColors.textTertiary)
                }
                IconButton(onClick = { showCWInput = !showCWInput }) {
                    Icon(NuruIcons.Warning, "CW", tint = if (showCWInput) Color(0xFFFF9800) else nuruColors.textTertiary)
                }
                IconButton(onClick = { showEmojiPicker = !showEmojiPicker }) {
                    Icon(NuruIcons.Emoji, "絵文字", tint = nuruColors.textTertiary)
                }
                IconButton(
                    onClick = {
                        if (hasMicPermission) {
                            if (isSTTActive) speechRecognizer.stopListening()
                            else speechRecognizer.startListening(sttIntent)
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) {
                    Icon(
                        NuruIcons.Mic,
                        "音声入力",
                        tint = if (isSTTActive) Color.Red else if (hasMicPermission) nuruColors.textTertiary else nuruColors.textTertiary.copy(alpha = 0.3f)
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

            if (showEmojiPicker) {
                EmojiPicker(
                    pubkey = myPubkey,
                    onSelect = { emoji ->
                        val newText = text.text + ":${emoji.shortcode}:"
                        text = TextFieldValue(newText, TextRange(newText.length))
                        if (!selectedCustomEmojis.any { it.shortcode == emoji.shortcode }) {
                            selectedCustomEmojis = selectedCustomEmojis + emoji
                        }
                        showEmojiPicker = false
                    },
                    onClose = { showEmojiPicker = false },
                    repository = repository
                )
            }
        }
    }

    if (showRecorder) {
        DivineVideoRecorder(
            onComplete = {
                recordedVideo = it
                selectedImages = emptyList()
                showRecorder = false
            },
            onClose = { showRecorder = false },
            repository = repository
        )
    }
}
