package io.nurunuru.app.ui.miniapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.MuteListData
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuteList(
    pubkey: String,
    repository: NostrRepository
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var muteList by remember { mutableStateOf(MuteListData()) }
    var mutedProfiles by remember { mutableStateOf<Map<String, UserProfile>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var removingValue by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pubkey) {
        isLoading = true
        try {
            val list = repository.fetchMuteList(pubkey)
            muteList = list
            if (list.pubkeys.isNotEmpty()) {
                mutedProfiles = repository.fetchProfiles(list.pubkeys)
            }
        } catch (e: Exception) {
            android.util.Log.e("MuteList", "Failed to fetch mute list", e)
        } finally {
            isLoading = false
        }
    }

    val handleUnmute = { type: String, value: String ->
        if (removingValue == null) {
            removingValue = value
            scope.launch {
                try {
                    if (repository.removeFromMuteList(pubkey, type, value)) {
                        muteList = when (type) {
                            "pubkey" -> muteList.copy(pubkeys = muteList.pubkeys.filter { it != value })
                            "hashtag" -> muteList.copy(hashtags = muteList.hashtags.filter { it != value })
                            "word" -> muteList.copy(words = muteList.words.filter { it != value })
                            else -> muteList
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MuteList", "Failed to unmute $value", e)
                } finally {
                    removingValue = null
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
                    Text("ミュートリスト", fontWeight = FontWeight.Bold, fontSize = 18.sp)

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Text("読み込み中...", color = nuruColors.textTertiary, fontSize = 14.sp)
                        }
                    } else if (muteList.pubkeys.isEmpty() && muteList.hashtags.isEmpty() && muteList.words.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Text("ミュート項目はありません", color = nuruColors.textTertiary, fontSize = 14.sp)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            // Users Section
                            if (muteList.pubkeys.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ユーザー", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                                    muteList.pubkeys.forEach { pk ->
                                        val profile = mutedProfiles[pk]
                                        MutedUserRow(
                                            pubkey = pk,
                                            profile = profile,
                                            isRemoving = removingValue == pk,
                                            onUnmute = { handleUnmute("pubkey", pk) }
                                        )
                                    }
                                }
                            }

                            // Hashtags Section
                            if (muteList.hashtags.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ハッシュタグ", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        muteList.hashtags.forEach { tag ->
                                            MutedPill(
                                                label = "#$tag",
                                                isRemoving = removingValue == tag,
                                                onUnmute = { handleUnmute("hashtag", tag) }
                                            )
                                        }
                                    }
                                }
                            }

                            // Words Section
                            if (muteList.words.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("キーワード", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        muteList.words.forEach { word ->
                                            MutedPill(
                                                label = word,
                                                isRemoving = removingValue == word,
                                                onUnmute = { handleUnmute("word", word) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MutedUserRow(
    pubkey: String,
    profile: UserProfile?,
    isRemoving: Boolean,
    onUnmute: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Surface(
        color = nuruColors.bgTertiary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UserAvatar(
                pictureUrl = profile?.picture,
                displayName = profile?.displayedName ?: "",
                size = 32.dp
            )

            Text(
                text = profile?.displayedName ?: (pubkey.take(8) + "..."),
                fontSize = 14.sp,
                color = nuruColors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                onClick = onUnmute,
                enabled = !isRemoving,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    if (isRemoving) "..." else "解除",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun MutedPill(
    label: String,
    isRemoving: Boolean,
    onUnmute: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Surface(
        color = nuruColors.bgTertiary,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, fontSize = 14.sp, color = nuruColors.textPrimary)
            IconButton(
                onClick = onUnmute,
                enabled = !isRemoving,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "解除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
