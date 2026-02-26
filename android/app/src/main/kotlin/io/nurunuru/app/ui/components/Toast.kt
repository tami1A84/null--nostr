package io.nurunuru.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ToastType {
    SUCCESS, INFO, WARNING, ERROR
}

data class ToastMessage(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val type: ToastType = ToastType.INFO,
    val durationMs: Long = 3500L
)

class ToastState {
    var toasts by mutableStateOf<List<ToastMessage>>(emptyList())
        private set

    fun show(message: String, type: ToastType = ToastType.INFO, durationMs: Long = 3500L) {
        val toast = ToastMessage(message = message, type = type, durationMs = durationMs)
        toasts = toasts + toast
    }

    fun dismiss(id: Long) {
        toasts = toasts.filter { it.id != id }
    }
}

val LocalToastState = staticCompositionLocalOf { ToastState() }

@Composable
fun ToastProvider(content: @Composable () -> Unit) {
    val state = remember { ToastState() }
    CompositionLocalProvider(LocalToastState provides state) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            ToastContainer(
                toasts = state.toasts,
                onDismiss = { state.dismiss(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
            )
        }
    }
}

@Composable
private fun ToastContainer(
    toasts: List<ToastMessage>,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        toasts.forEach { toast ->
            key(toast.id) {
                ToastItem(toast = toast, onDismiss = { onDismiss(toast.id) })
            }
        }
    }
}

@Composable
private fun ToastItem(
    toast: ToastMessage,
    onDismiss: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(toast) {
        delay(toast.durationMs)
        onDismiss()
    }

    Surface(
        color = nuruColors.bgSecondary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .border(0.5.dp, nuruColors.border, RoundedCornerShape(12.dp))
            .animateContentSize(),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .widthIn(min = 200.dp, max = 340.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = when (toast.type) {
                    ToastType.SUCCESS -> NuruIcons.Like(true)
                    ToastType.WARNING -> NuruIcons.Warning
                    ToastType.ERROR -> NuruIcons.Warning
                    ToastType.INFO -> NuruIcons.Timeline(true)
                },
                contentDescription = null,
                tint = when (toast.type) {
                    ToastType.SUCCESS -> nuruColors.lineGreen
                    ToastType.WARNING -> Color(0xFFFF9800)
                    ToastType.ERROR -> Color.Red
                    ToastType.INFO -> nuruColors.textTertiary
                },
                modifier = Modifier.size(18.dp)
            )

            Text(
                text = toast.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = NuruIcons.Close,
                    contentDescription = "閉じる",
                    tint = nuruColors.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

object ToastMessages {
    const val POST_SUCCESS = "投稿しました"
    const val POST_FAILED = "投稿できませんでした"
    const val LIKE_SUCCESS = "いいねしました"
    const val REPOST_SUCCESS = "リポストしました"
    const val ZAP_SUCCESS = "Zapを送りました"
    const val ZAP_FAILED = "Zapを送れませんでした"
    const val FOLLOW_SUCCESS = "フォローしました"
    const val PROFILE_UPDATED = "プロフィールを更新しました"
    const val COPIED = "コピーしました"
}
