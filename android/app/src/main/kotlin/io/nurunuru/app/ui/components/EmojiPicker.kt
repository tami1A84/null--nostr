package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

data class CustomEmoji(
    val shortcode: String,
    val url: String,
    val source: String
)

data class EmojiSet(
    val name: String,
    val emojis: List<CustomEmoji>,
    val pointer: String
)

@Composable
fun EmojiPicker(
    pubkey: String,
    onSelect: (CustomEmoji) -> Unit,
    onClose: () -> Unit,
    repository: io.nurunuru.app.data.NostrRepository
) {
    val nuruColors = LocalNuruColors.current
    var searchQuery by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(true) }
    var emojis by remember { mutableStateOf<List<CustomEmoji>>(emptyList()) }
    var emojiSets by remember { mutableStateOf<List<EmojiSet>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pubkey) {
        scope.launch {
            loading = true
            try {
                // Fetch kind 10030 (Emoji List)
                val filter = NostrClient.Filter(
                    kinds = listOf(10030),
                    authors = listOf(pubkey),
                    limit = 1
                )
                val events = repository.fetchEvents(filter) // Assuming fetchEvents exists or I need to add it

                val individualEmojis = mutableListOf<CustomEmoji>()
                val setPointers = mutableListOf<String>()

                if (events.isNotEmpty()) {
                    val tags = events.first().tags
                    tags.forEach { tag ->
                        if (tag.getOrNull(0) == "emoji" && tag.size >= 3) {
                            individualEmojis.add(CustomEmoji(tag[1], tag[2], "user"))
                        } else if (tag.getOrNull(0) == "a" && tag.getOrNull(1)?.startsWith("30030:") == true) {
                            setPointers.add(tag[1])
                        }
                    }
                }

                // Load emoji sets (Kind 30030)
                val loadedSets = mutableListOf<EmojiSet>()
                setPointers.forEach { pointer ->
                    val parts = pointer.split(":")
                    if (parts.size >= 3) {
                        val author = parts[1]
                        val dTag = parts.drop(2).joinToString(":")
                        val setFilter = NostrClient.Filter(
                            kinds = listOf(30030),
                            authors = listOf(author),
                            tags = mapOf("d" to listOf(dTag)),
                            limit = 1
                        )
                        val setEvents = repository.fetchEvents(setFilter)
                        if (setEvents.isNotEmpty()) {
                            val setEvent = setEvents.first()
                            val setName = setEvent.getTagValue("title") ?: setEvent.getTagValue("d") ?: "Emoji Set"
                            val setEmojis = setEvent.tags
                                .filter { it.getOrNull(0) == "emoji" && it.size >= 3 }
                                .map { CustomEmoji(it[1], it[2], setName) }
                            if (setEmojis.isNotEmpty()) {
                                loadedSets.add(EmojiSet(setName, setEmojis, pointer))
                            }
                        }
                    }
                }

                emojis = individualEmojis
                emojiSets = loadedSets
            } catch (e: Exception) {
                // Handle error
            } finally {
                loading = false
            }
        }
    }

    val allEmojis = (emojis + emojiSets.flatMap { it.emojis }).distinctBy { it.shortcode }
    val filteredEmojis = allEmojis.filter {
        it.shortcode.contains(searchQuery, ignoreCase = true) &&
        (activeTab == "all" || (activeTab == "user" && emojis.contains(it)) ||
         emojiSets.find { s -> s.name == activeTab }?.emojis?.contains(it) == true)
    }

    val tabs = mutableListOf("all")
    if (emojis.isNotEmpty()) tabs.add("user")
    tabs.addAll(emojiSets.map { it.name })

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(nuruColors.bgSecondary, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(8.dp)
    ) {
        // Search
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .background(nuruColors.bgPrimary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(nuruColors.lineGreen),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) {
                        Text("絵文字を検索...", color = nuruColors.textTertiary, style = MaterialTheme.typography.bodyMedium)
                    }
                    innerTextField()
                }
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(NuruIcons.Close, null, tint = nuruColors.textTertiary, modifier = Modifier.size(18.dp))
            }
        }

        // Tabs
        if (searchQuery.isEmpty() && tabs.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(activeTab).coerceAtLeast(0),
                containerColor = Color.Transparent,
                contentColor = nuruColors.lineGreen,
                divider = {},
                edgePadding = 0.dp,
                modifier = Modifier.height(40.dp)
            ) {
                tabs.forEach { tab ->
                    Tab(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        text = {
                            Text(
                                when(tab) {
                                    "all" -> "すべて"
                                    "user" -> "個別"
                                    else -> tab
                                },
                                fontSize = 12.sp,
                                fontWeight = if (activeTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        // Grid
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = nuruColors.lineGreen, modifier = Modifier.size(24.dp))
            }
        } else if (filteredEmojis.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (allEmojis.isEmpty()) "カスタム絵文字がありません" else "該当する絵文字がありません",
                    color = nuruColors.textTertiary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredEmojis) { emoji ->
                    AsyncImage(
                        model = emoji.url,
                        contentDescription = emoji.shortcode,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(nuruColors.bgPrimary)
                            .clickable { onSelect(emoji) }
                            .padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
