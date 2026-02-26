package io.nurunuru.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlin.random.Random

private val ENCOURAGING_MESSAGES = listOf(
    "ちょっと休憩してから、もう一度試してみましょう",
    "大丈夫、もう一度試せばきっとうまくいきます",
    "一時的な問題かもしれません。少し待ってからお試しください",
    "アプリを更新中かもしれません。少々お待ちください"
)

private val HELPFUL_HINTS = listOf(
    "インターネット接続を確認してみてください",
    "アプリを再起動すると解決することがあります",
    "しばらく待ってから再試行してみてください"
)

@Composable
fun ErrorBoundary(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    content: @Composable () -> Unit
) {
    var hasError by remember { mutableStateOf(false) }
    var error: Throwable? by remember { mutableStateOf(null) }

    if (hasError) {
        val message = remember { ENCOURAGING_MESSAGES[Random.nextInt(ENCOURAGING_MESSAGES.size)] }
        val hint = remember { HELPFUL_HINTS[Random.nextInt(HELPFUL_HINTS.size)] }
        val nuruColors = LocalNuruColors.current

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = NuruIcons.Emoji,
                contentDescription = null,
                tint = nuruColors.textTertiary,
                modifier = Modifier.size(48.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "うまくいきませんでした",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = nuruColors.textTertiary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Surface(
                color = nuruColors.bgSecondary,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "💡 $hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textSecondary,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        hasError = false
                        onRetry()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nuruColors.lineGreen
                    )
                ) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("もう一度試す")
                }
            }
        }
    } else {
        // In a real Android environment, catching all errors in Compose is tricky
        // without wrapping every recomposition. This is a simplified fallback UI.
        // For actual global catching, one would use Thread.setDefaultUncaughtExceptionHandler
        content()
    }
}
