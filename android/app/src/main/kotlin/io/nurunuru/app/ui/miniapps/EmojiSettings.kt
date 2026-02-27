package io.nurunuru.app.ui.miniapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.EmojiInfo
import io.nurunuru.app.data.models.EmojiSet
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun EmojiSettings(
    pubkey: String,
    repository: NostrRepository
) {
    val nuruColors = LocalNuruColors.current
    val scope = rememberCoroutineScope()

    var userEmojis by remember { mutableStateOf<List<EmojiInfo>>(emptyList()) }
    var userEmojiSets by remember { mutableStateOf<List<EmojiSet>>(emptyList()) }
    var loadingEmojis by remember { mutableStateOf(false) }
    var removingEmojiPointer by remember { mutableStateOf<String?>(null) }
    var emojiSetSearch by remember { mutableStateOf("") }
    var searchedEmojiSets by remember { mutableStateOf<List<EmojiSet>>(emptyList()) }
    var searchingEmoji by remember { mutableStateOf(false) }
    var addingEmojiSetPointer by remember { mutableStateOf<String?>(null) }

    val loadUserEmojis = suspend {
        loadingEmojis = true
        try {
            val event = repository.fetchEmojiList(pubkey)
            val individualEmojis = mutableListOf<EmojiInfo>()
            val setPointers = mutableListOf<String>()

            event?.tags?.forEach { tag ->
                if (tag.getOrNull(0) == "emoji" && tag.getOrNull(1) != null && tag.getOrNull(2) != null) {
                    individualEmojis.add(EmojiInfo(tag[1], tag[2]))
                } else if (tag.getOrNull(0) == "a" && tag.getOrNull(1)?.startsWith("30030:") == true) {
                    setPointers.add(tag[1])
                }
            }

            val loadedSets = coroutineScope {
                setPointers.map { pointer ->
                    async {
                        val parts = pointer.split(":")
                        if (parts.size >= 3) {
                            val author = parts[1]
                            val dTag = parts.drop(2).joinToString(":")
                            repository.fetchEmojiSet(author, dTag)
                        } else null
                    }
                }.awaitAll().filterNotNull()
            }

            userEmojis = individualEmojis
            userEmojiSets = loadedSets
        } catch (e: Exception) {
            android.util.Log.e("EmojiSettings", "Failed to load emojis", e)
        } finally {
            loadingEmojis = false
        }
    }

    LaunchedEffect(pubkey) {
        loadUserEmojis()
    }

    val searchEmojiSets = {
        if (emojiSetSearch.isNotBlank()) {
            searchingEmoji = true
            scope.launch {
                try {
                    val results = repository.searchEmojiSets(emojiSetSearch)
                    searchedEmojiSets = results.filter { s ->
                        userEmojiSets.none { us -> us.pointer == s.pointer }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EmojiSettings", "Failed to search emoji sets", e)
                } finally {
                    searchingEmoji = false
                }
            }
        }
    }

    val handleAddEmojiSet = { set: EmojiSet ->
        if (addingEmojiSetPointer == null) {
            addingEmojiSetPointer = set.pointer
            scope.launch {
                try {
                    val tags = mutableListOf<List<String>>()
                    userEmojis.forEach { tags.add(listOf("emoji", it.shortcode, it.url)) }
                    userEmojiSets.forEach { tags.add(listOf("a", it.pointer)) }
                    tags.add(listOf("a", set.pointer))

                    if (repository.updateEmojiList(tags)) {
                        userEmojiSets = userEmojiSets + set
                        searchedEmojiSets = searchedEmojiSets.filter { it.pointer != set.pointer }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EmojiSettings", "Failed to add emoji set", e)
                } finally {
                    addingEmojiSetPointer = null
                }
            }
        }
    }

    val handleRemoveEmojiSet = { set: EmojiSet ->
        if (removingEmojiPointer == null) {
            removingEmojiPointer = set.pointer
            scope.launch {
                try {
                    val tags = mutableListOf<List<String>>()
                    userEmojis.forEach { tags.add(listOf("emoji", it.shortcode, it.url)) }
                    userEmojiSets.filter { it.pointer != set.pointer }.forEach { tags.add(listOf("a", it.pointer)) }

                    if (repository.updateEmojiList(tags)) {
                        userEmojiSets = userEmojiSets.filter { it.pointer != set.pointer }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EmojiSettings", "Failed to remove emoji set", e)
                } finally {
                    removingEmojiPointer = null
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("カスタム絵文字", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))

                    if (loadingEmojis) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            Text("読み込み中...", color = nuruColors.textTertiary, fontSize = 14.sp)
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (userEmojiSets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("登録済みセット", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium)
                                    userEmojiSets.forEach { set ->
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
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(set.name, fontSize = 14.sp, color = nuruColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("${set.emojiCount}個の絵文字", fontSize = 12.sp, color = nuruColors.textTertiary)
                                                }
                                                TextButton(
                                                    onClick = { handleRemoveEmojiSet(set) },
                                                    enabled = removingEmojiPointer == null,
                                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                                ) {
                                                    Text(
                                                        if (removingEmojiPointer == set.pointer) "..." else "削除",
                                                        color = Color.Red.copy(alpha = 0.8f),
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Column {
                                Text("新しいセットを追加", fontSize = 14.sp, color = nuruColors.textSecondary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = emojiSetSearch,
                                        onValueChange = { emojiSetSearch = it },
                                        placeholder = { Text("検索...", fontSize = 14.sp) },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { searchEmojiSets() }),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            focusedBorderColor = LineGreen,
                                            unfocusedBorderColor = nuruColors.border
                                        )
                                    )
                                    Button(
                                        onClick = { searchEmojiSets() },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                                        modifier = Modifier.height(48.dp),
                                        enabled = !searchingEmoji
                                    ) {
                                        if (searchingEmoji) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("検索", fontSize = 14.sp)
                                        }
                                    }
                                }

                                if (searchedEmojiSets.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        searchedEmojiSets.forEach { set ->
                                            Surface(
                                                color = nuruColors.bgPrimary,
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.fillMaxWidth().border(0.5.dp, nuruColors.border, RoundedCornerShape(12.dp))
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(set.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nuruColors.textPrimary)
                                                        TextButton(
                                                            onClick = { handleAddEmojiSet(set) },
                                                            enabled = addingEmojiSetPointer == null,
                                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                                        ) {
                                                            Text(
                                                                if (addingEmojiSetPointer == set.pointer) "追加中..." else "追加",
                                                                color = LineGreen,
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        set.emojis.forEach { emoji ->
                                                            AsyncImage(
                                                                model = emoji.url,
                                                                contentDescription = emoji.shortcode,
                                                                modifier = Modifier.size(24.dp),
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
                    }
                }
            }
        }
    }
}

// Add extension property to Button to support disabled state easily if needed,
// but here I just used enabled parameter.
// The web version uses "disabled" as a prop, in Compose it's "enabled = !disabled".
