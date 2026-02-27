package io.nurunuru.app.ui.miniapps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.BadgeInfo
import io.nurunuru.app.ui.components.clearBadgeCache
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

@Composable
fun BadgeSettings(
    pubkey: String,
    repository: NostrRepository
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var profileBadges by remember { mutableStateOf<List<BadgeInfo>>(emptyList()) }
    var awardedBadges by remember { mutableStateOf<List<BadgeInfo>>(emptyList()) }
    var isLoadingCurrent by remember { mutableStateOf(true) }
    var isLoadingAwarded by remember { mutableStateOf(true) }
    var removingBadgeRef by remember { mutableStateOf<String?>(null) }
    var addingBadgeRef by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pubkey) {
        scope.launch {
            isLoadingCurrent = true
            try {
                profileBadges = repository.fetchProfileBadgesInfo(pubkey)
            } catch (e: Exception) {
                android.util.Log.e("BadgeSettings", "Failed to fetch profile badges", e)
            } finally {
                isLoadingCurrent = false
            }
        }

        scope.launch {
            isLoadingAwarded = true
            try {
                // We need profileBadges to filter, but we can also just fetch all and filter in UI or after both finish
                // For better UX, we fetch awarded badges independently
                val current = repository.fetchProfileBadgesInfo(pubkey)
                awardedBadges = repository.fetchAwardedBadges(pubkey, current.map { it.ref }.toSet())
            } catch (e: Exception) {
                android.util.Log.e("BadgeSettings", "Failed to fetch awarded badges", e)
            } finally {
                isLoadingAwarded = false
            }
        }
    }

    val handleAddBadge = { badge: BadgeInfo ->
        if (profileBadges.size < 3 && addingBadgeRef == null) {
            addingBadgeRef = badge.ref
            scope.launch {
                try {
                    val newList = profileBadges + badge
                    if (repository.updateProfileBadges(pubkey, newList)) {
                        clearBadgeCache(pubkey)
                        profileBadges = newList
                        awardedBadges = awardedBadges.filter { it.ref != badge.ref }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BadgeSettings", "Failed to add badge", e)
                } finally {
                    addingBadgeRef = null
                }
            }
        }
    }

    val handleRemoveBadge = { badge: BadgeInfo ->
        if (removingBadgeRef == null) {
            removingBadgeRef = badge.ref
            scope.launch {
                try {
                    val newList = profileBadges.filter { it.ref != badge.ref }
                    if (repository.updateProfileBadges(pubkey, newList)) {
                        clearBadgeCache(pubkey)
                        profileBadges = newList
                        // Only add back to awarded if it was an awarded badge (has awardEventId)
                        if (badge.awardEventId != null) {
                            awardedBadges = (awardedBadges + badge).distinctBy { it.ref }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BadgeSettings", "Failed to remove badge", e)
                } finally {
                    removingBadgeRef = null
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                color = nuruColors.bgSecondary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("プロフィールバッジ", fontWeight = FontWeight.Bold, fontSize = 18.sp)

                    if (isLoadingCurrent) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LineGreen, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("表示中のバッジ (最大3つ)", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                            if (profileBadges.isEmpty()) {
                                Text("設定されていません", fontSize = 14.sp, color = nuruColors.textTertiary)
                            } else {
                                profileBadges.forEach { badge ->
                                    BadgeItemRow(
                                        badge = badge,
                                        actionText = if (removingBadgeRef == badge.ref) "..." else "削除",
                                        actionColor = Color.Red.copy(alpha = 0.8f),
                                        onAction = { handleRemoveBadge(badge) },
                                        isLoading = removingBadgeRef == badge.ref
                                    )
                                }
                            }
                        }
                    }

                    if (isLoadingAwarded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("獲得済みバッジ", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = LineGreen, modifier = Modifier.size(24.dp))
                            }
                        }
                    } else if (awardedBadges.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("獲得済みバッジ", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                            awardedBadges.forEach { badge ->
                                BadgeItemRow(
                                    badge = badge,
                                    actionText = if (addingBadgeRef == badge.ref) "..." else "追加",
                                    actionColor = LineGreen,
                                    onAction = { handleAddBadge(badge) },
                                    isLoading = addingBadgeRef == badge.ref,
                                    disabled = profileBadges.size >= 3
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeItemRow(
    badge: BadgeInfo,
    actionText: String,
    actionColor: Color,
    onAction: () -> Unit,
    isLoading: Boolean = false,
    disabled: Boolean = false
) {
    val nuruColors = LocalNuruColors.current
    Surface(
        color = nuruColors.bgTertiary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (badge.image.isNotBlank()) {
                AsyncImage(
                    model = badge.image,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier.size(32.dp).background(nuruColors.bgSecondary, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = io.nurunuru.app.ui.icons.NuruIcons.Badge,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = nuruColors.textTertiary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    badge.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(
                onClick = onAction,
                enabled = !isLoading && !disabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    actionText,
                    color = if (disabled) nuruColors.textTertiary else actionColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
