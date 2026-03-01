package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.EmojiInfo
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

/**
 * Reaction emoji picker for custom reactions (NIP-25 extended).
 * Synced with web version: components/ReactionEmojiPicker.js
 */

data class ReactionSelection(
    val type: String,    // "custom" or "unicode"
    val shortcode: String = "",
    val url: String = "",
    val emoji: String = ""
)

private data class EmojiSetData(
    val name: String,
    val emojis: List<EmojiWithSource>,
    val pointer: String
)

private data class EmojiWithSource(
    val shortcode: String,
    val url: String,
    val source: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionEmojiPicker(
    pubkey: String,
    repository: NostrRepository,
    onSelect: (ReactionSelection) -> Unit,
    onDismiss: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var emojis by remember { mutableStateOf<List<EmojiWithSource>>(emptyList()) }
    var emojiSets by remember { mutableStateOf<List<EmojiSetData>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("all") }

    // Load custom emojis
    LaunchedEffect(pubkey) {
        scope.launch {
            loading = true
            try {
                val events = repository.fetchEvents(
                    kinds = listOf(NostrKind.EMOJI_LIST),
                    authors = listOf(pubkey),
                    limit = 1
                )

                val individualEmojis = mutableListOf<EmojiWithSource>()
                val setPointers = mutableListOf<String>()

                if (events.isNotEmpty()) {
                    for (tag in events[0].tags) {
                        when {
                            tag.firstOrNull() == "emoji" && tag.size >= 3 ->
                                individualEmojis.add(EmojiWithSource(tag[1], tag[2], "個別"))
                            tag.firstOrNull() == "a" && tag.getOrNull(1)?.startsWith("30030:") == true ->
                                setPointers.add(tag[1])
                        }
                    }
                }

                val loadedSets = mutableListOf<EmojiSetData>()
                for (pointer in setPointers) {
                    val parts = pointer.split(":")
                    if (parts.size >= 3) {
                        val author = parts[1]
                        val dTag = parts.drop(2).joinToString(":")

                        val setEvents = repository.fetchEvents(
                            kinds = listOf(NostrKind.EMOJI_SET),
                            authors = listOf(author),
                            dTags = listOf(dTag),
                            limit = 1
                        )

                        if (setEvents.isNotEmpty()) {
                            val setEvent = setEvents[0]
                            val setName = setEvent.tags.firstOrNull { it.firstOrNull() == "title" }?.getOrNull(1)
                                ?: setEvent.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
                                ?: "Emoji Set"

                            val setEmojis = setEvent.tags
                                .filter { it.firstOrNull() == "emoji" && it.size >= 3 }
                                .map { EmojiWithSource(it[1], it[2], setName) }

                            if (setEmojis.isNotEmpty()) {
                                loadedSets.add(EmojiSetData(setName, setEmojis, pointer))
                            }
                        }
                    }
                }

                emojis = individualEmojis
                emojiSets = loadedSets
            } catch (_: Exception) { }
            loading = false
        }
    }

    val allCustomEmojis = emojis + emojiSets.flatMap { it.emojis }

    val filteredEmojis = when {
        searchQuery.isNotEmpty() -> allCustomEmojis.filter {
            it.shortcode.contains(searchQuery, ignoreCase = true)
        }
        activeTab == "all" -> allCustomEmojis
        activeTab == "user" -> emojis
        else -> emojiSets.find { it.name == activeTab }?.emojis ?: emptyList()
    }

    val tabs = buildList {
        add("all" to "すべて")
        if (emojis.isNotEmpty()) add("user" to "個別")
        emojiSets.forEach { add(it.name to it.name) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = nuruColors.bgPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(nuruColors.textTertiary.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            // Header
            Text(
                text = "リアクション",
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = nuruColors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("カスタム絵文字を検索...", fontSize = 14.sp) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = nuruColors.bgSecondary,
                    focusedContainerColor = nuruColors.bgSecondary
                )
            )

            // Tabs
            if (searchQuery.isEmpty() && tabs.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(tabs) { (id, name) ->
                        val isActive = activeTab == id
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isActive) io.nurunuru.app.ui.theme.LineGreen
                                    else nuruColors.bgTertiary,
                            modifier = Modifier.clickable { activeTab = id }
                        ) {
                            Text(
                                text = name,
                                fontSize = 12.sp,
                                color = if (isActive) androidx.compose.ui.graphics.Color.White
                                        else nuruColors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Emoji Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 250.dp)
                    .padding(horizontal = 16.dp)
            ) {
                when {
                    loading -> {
                        Text(
                            text = "読み込み中...",
                            color = nuruColors.textTertiary,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    allCustomEmojis.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("カスタム絵文字がありません", color = nuruColors.textTertiary, fontSize = 14.sp)
                            Text("ミニアプリから絵文字セットを追加できます",
                                color = nuruColors.textTertiary, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    filteredEmojis.isEmpty() -> {
                        Text(
                            text = "該当する絵文字がありません",
                            color = nuruColors.textTertiary,
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(8),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredEmojis) { emoji ->
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            onSelect(ReactionSelection(
                                                type = "custom",
                                                shortcode = emoji.shortcode,
                                                url = emoji.url
                                            ))
                                        }
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = emoji.url,
                                        contentDescription = ":${emoji.shortcode}:",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
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
