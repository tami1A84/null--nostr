package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import io.nurunuru.app.ui.theme.LocalNuruColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person

@Composable
fun UserAvatar(
    pictureUrl: String?,
    displayName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(nuruColors.bgTertiary),
        contentAlignment = Alignment.Center
    ) {
        if (!pictureUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = pictureUrl,
                contentDescription = "$displayName のアバター",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                error = {
                    DefaultAvatar(size = size)
                },
                loading = {
                    DefaultAvatar(size = size)
                }
            )
        } else {
            DefaultAvatar(size = size)
        }
    }
}

@Composable
private fun DefaultAvatar(size: Dp) {
    val iconSize = (size.value * 0.55f).dp
    Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        tint = LocalNuruColors.current.textTertiary,
        modifier = Modifier.size(iconSize)
    )
}
