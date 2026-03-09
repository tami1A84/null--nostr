package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

@Composable
fun ImageViewerDialog(imageUrl: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }
            var isError by remember { mutableStateOf(false) }

            val transformState = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(0.5f, 5f)
                offsetX += pan.x; offsetY += pan.y
            }

            if (isError) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("画像を読み込めませんでした", color = Color.White)
                }
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onState = { if (it is AsyncImagePainter.State.Error) isError = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = transformState)
                        .graphicsLayer(scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1.5f) 1f else 2f
                                offsetX = 0f; offsetY = 0f
                            })
                        }
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f),
                        androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "閉じる", tint = Color.White)
            }
        }
    }
}
