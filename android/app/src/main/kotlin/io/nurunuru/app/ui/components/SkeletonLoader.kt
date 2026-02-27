package io.nurunuru.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors

@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp)
) {
    val nuruColors = LocalNuruColors.current
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .clip(shape)
            .graphicsLayer { this.alpha = alpha }
            .background(nuruColors.bgTertiary)
    )
}

@Composable
fun PostSkeleton() {
    val nuruColors = LocalNuruColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Skeleton(
                modifier = Modifier.size(42.dp),
                shape = CircleShape
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Skeleton(modifier = Modifier.height(16.dp).width(120.dp))
                Skeleton(modifier = Modifier.height(14.dp).fillMaxWidth())
                Skeleton(modifier = Modifier.height(14.dp).fillMaxWidth(0.7f))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }
}

@Composable
fun ProfileSkeleton() {
    val nuruColors = LocalNuruColors.current
    val bgPrimary = nuruColors.bgPrimary
    Box(modifier = Modifier.fillMaxWidth()) {
        // Banner
        Skeleton(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
        )

        // The Overlapping Surface
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .padding(top = 64.dp),
            shape = RoundedCornerShape(16.dp),
            color = bgPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Spacer(modifier = Modifier.size(80.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Skeleton(modifier = Modifier.height(18.dp).width(120.dp))
                        Skeleton(modifier = Modifier.height(14.dp).width(160.dp))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Skeleton(modifier = Modifier.height(14.dp).fillMaxWidth())
                Skeleton(modifier = Modifier.height(14.dp).fillMaxWidth(0.6f))
            }
        }

        // Avatar
        Box(
            modifier = Modifier
                .padding(start = 32.dp)
                .offset(y = 24.dp)
                .size(80.dp)
                .clip(CircleShape)
                .background(bgPrimary)
                .padding(4.dp)
        ) {
            Skeleton(modifier = Modifier.fillMaxSize(), shape = CircleShape)
        }
    }
}

@Composable
fun MessageSkeleton(alignRight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 16.dp),
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start
    ) {
        Skeleton(
            modifier = Modifier
                .height(40.dp)
                .fillMaxWidth(0.6f),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (alignRight) 16.dp else 4.dp,
                bottomEnd = if (alignRight) 4.dp else 16.dp
            )
        )
    }
}

@Composable
fun ListItemSkeleton() {
    val nuruColors = LocalNuruColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Skeleton(
                modifier = Modifier.size(40.dp),
                shape = CircleShape
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Skeleton(modifier = Modifier.height(14.dp).width(100.dp))
                Skeleton(modifier = Modifier.height(12.dp).width(180.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }
}

@Composable
fun FriendlyLoading(
    message: String = "読み込み中...",
    hint: String? = null,
    showDots: Boolean = true
) {
    val nuruColors = LocalNuruColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(24.dp)
    ) {
        if (showDots) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val infiniteTransition = rememberInfiniteTransition(label = "dots")
                    val dotScale by infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 1400
                                0.6f at 0 using FastOutSlowInEasing
                                1.0f at 400 using FastOutSlowInEasing
                                0.6f at 800 using FastOutSlowInEasing
                                0.6f at 1400 using FastOutSlowInEasing
                            },
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = StartOffset(index * 160)
                        ),
                        label = "scale"
                    )
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 1400
                                0.5f at 0 using FastOutSlowInEasing
                                1.0f at 400 using FastOutSlowInEasing
                                0.5f at 800 using FastOutSlowInEasing
                                0.5f at 1400 using FastOutSlowInEasing
                            },
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = StartOffset(index * 160)
                        ),
                        label = "alpha"
                    )
                    val dotY by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = -4f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 1400
                                0f at 0 using FastOutSlowInEasing
                                -4f at 400 using FastOutSlowInEasing
                                0f at 800 using FastOutSlowInEasing
                                0f at 1400 using FastOutSlowInEasing
                            },
                            repeatMode = RepeatMode.Restart,
                            initialStartOffset = StartOffset(index * 160)
                        ),
                        label = "translationY"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .graphicsLayer {
                                scaleX = dotScale
                                scaleY = dotScale
                                alpha = dotAlpha
                                translationY = dotY.dp.toPx()
                            }
                            .background(LineGreen, CircleShape)
                    )
                }
            }
        }
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = nuruColors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = nuruColors.textTertiary,
                textAlign = TextAlign.Center,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun TimelineLoadingSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FriendlyLoading(
            message = "タイムラインを読み込んでいます",
            hint = "もう少しお待ちください"
        )
    }
}
