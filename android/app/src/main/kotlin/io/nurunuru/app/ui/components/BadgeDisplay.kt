package io.nurunuru.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrRepository
import kotlinx.coroutines.launch

private val badgeCache = mutableMapOf<String, List<String>>()

fun clearBadgeCache(pubkey: String) {
    badgeCache.remove(pubkey)
}

@Composable
fun BadgeDisplay(
    pubkey: String,
    repository: NostrRepository,
    maxBadges: Int = 3,
    initialBadges: List<String> = emptyList()
) {
    var badgeUrls by remember { mutableStateOf(if (initialBadges.isNotEmpty()) initialBadges else badgeCache[pubkey] ?: emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pubkey) {
        if (initialBadges.isNotEmpty()) {
            badgeUrls = initialBadges
            badgeCache[pubkey] = initialBadges
            return@LaunchedEffect
        }

        if (badgeCache.containsKey(pubkey)) {
            badgeUrls = badgeCache[pubkey] ?: emptyList()
            return@LaunchedEffect
        }

        scope.launch {
            try {
                val badges = repository.fetchBadges(pubkey)
                val urls = badges.mapNotNull { it.getTagValue("thumb") ?: it.getTagValue("image") }
                badgeUrls = urls.take(maxBadges)
                badgeCache[pubkey] = urls
            } catch (e: Exception) {
                badgeCache[pubkey] = emptyList()
            }
        }
    }

    if (badgeUrls.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            badgeUrls.take(maxBadges).forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Badge",
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
