package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
    onPublish: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val remaining = MAX_NOTE_LENGTH - text.text.length
    val canPost = text.text.isNotBlank() && remaining >= 0
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
                            onPublish(text.text)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                        text = "いまどうしてる？",
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
