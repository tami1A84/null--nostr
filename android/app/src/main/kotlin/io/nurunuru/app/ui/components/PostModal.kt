package io.nurunuru.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

private const val MAX_NOTE_LENGTH = 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostModal(
    pictureUrl: String?,
    displayName: String,
    replyToContent: String? = null,
    onDismiss: () -> Unit,
    onPublish: (String, String?) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var contentWarning by remember { mutableStateOf("") }
    var showCWInput by remember { mutableStateOf(false) }
    var selectedImages by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selectedImages = (selectedImages + uris).take(3)
    }

    val focusRequester = remember { FocusRequester() }
    val remaining = MAX_NOTE_LENGTH - text.text.length
    val canPost = (text.text.isNotBlank() || selectedImages.isNotEmpty()) && remaining >= 0
    // Hoist sheetState so we can animate hide before removing from composition
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun dismissSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, // swipe-down: framework already animating, just update state
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        sheetState = sheetState
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
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "閉じる",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Button(
                    onClick = {
                        if (canPost) {
                            onPublish(text.text, contentWarning.takeIf { showCWInput && it.isNotBlank() })
                            dismissSheet()
                        }
                    },
                    enabled = canPost,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nuruColors.lineGreen,
                        disabledContainerColor = nuruColors.lineGreen.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("投稿する")
                }
            }

            // Reply context
            if (replyToContent != null) {
                Text(
                    text = "返信先: $replyToContent",
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textTertiary,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            }

            // Input area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                // Content Warning Input
                if (showCWInput) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = contentWarning,
                            onValueChange = { contentWarning = it },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFFF9800)),
                            cursorBrush = SolidColor(Color(0xFFFF9800)),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (contentWarning.isEmpty()) {
                                        Text("警告の理由（ネタバレなど）", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF9800).copy(alpha = 0.5f))
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    HorizontalDivider(color = Color(0xFFFF9800).copy(alpha = 0.2f), thickness = 0.5.dp)
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    UserAvatar(
                        pictureUrl = pictureUrl,
                        displayName = displayName,
                        size = 40.dp
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = text,
                            onValueChange = { if (it.text.length <= MAX_NOTE_LENGTH + 10) text = it },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(nuruColors.lineGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 120.dp)
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (text.text.isEmpty()) {
                                        Text(
                                            text = if (replyToContent != null) "返信を入力..." else "いまどうしてる？",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = nuruColors.textTertiary
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }

                // Image Previews
                if (selectedImages.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        itemsIndexed(selectedImages) { index, uri ->
                            Box {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { selectedImages = selectedImages.toMutableList().also { it.removeAt(index) } },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .padding(2.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Video (Placeholder/Mock as in web desktop hiding it)
                IconButton(onClick = { /* TODO: Video recorder */ }) {
                    Icon(Icons.Outlined.Videocam, "動画", tint = nuruColors.textTertiary)
                }

                // Image
                IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Outlined.Image, "画像", tint = nuruColors.textTertiary)
                }

                // CW
                IconButton(onClick = { showCWInput = !showCWInput }) {
                    Icon(
                        Icons.Outlined.Warning,
                        "CW",
                        tint = if (showCWInput) Color(0xFFFF9800) else nuruColors.textTertiary
                    )
                }

                // Emoji
                IconButton(onClick = { /* TODO: Emoji picker */ }) {
                    Icon(Icons.Outlined.EmojiEmotions, "絵文字", tint = nuruColors.textTertiary)
                }

                // STT
                IconButton(onClick = { /* TODO: STT */ }) {
                    Icon(Icons.Outlined.Mic, "音声入力", tint = nuruColors.textTertiary)
                }

                Spacer(modifier = Modifier.weight(1f))

            // Character count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "$remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        remaining < 0 -> MaterialTheme.colorScheme.error
                        remaining < 50 -> nuruColors.zapColor
                        else -> nuruColors.textTertiary
                    }
                )
            }
        }
    }
}
