package io.nurunuru.app.ui.miniapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.nurunuru.app.ui.components.EmojiPickerCache
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
    // お気に入りトグル中のshortcode（多重タップ防止）
    var togglingShortcode by remember { mutableStateOf<String?>(null) }

    val loadUserEmojis: suspend () -> Unit = {
        loadingEmojis = true
        try {
            // キャッシュファースト
            val cached = EmojiPickerCache.get(pubkey)
            if (cached != null) {
                userEmojis = cached.emojis.map { EmojiInfo(it.shortcode, it.url) }
                userEmojiSets = cached.sets.map { set ->
                    val parts = set.pointer.split(":")
                    EmojiSet(
                        pointer = set.pointer,
                        name = set.name,
                        author = if (parts.size >= 2) parts[1] else "",
                        dTag = if (parts.size >= 3) parts.drop(2).joinToString(":") else "",
                        emojiCount = set.emojis.size,
                        emojis = set.emojis.map { EmojiInfo(it.shortcode, it.url) }
                    )
                }
            } else {
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
            }
        } catch (e: Exception) {
            android.util.Log.e("EmojiSettings", "Failed to load emojis", e)
        } finally {
            loadingEmojis = false
        }
    }

    LaunchedEffect(pubkey) { loadUserEmojis() }

    // ── ハンドラ ────────────────────────────────────────────────────────────

    /** セット内の絵文字をお気に入りトグル */
    val handleToggleFavorite = { emoji: EmojiInfo ->
        if (togglingShortcode == null) {
            togglingShortcode = emoji.shortcode
            scope.launch {
                try {
                    val isFav = userEmojis.any { it.shortcode == emoji.shortcode }
                    val newFavs = if (isFav)
                        userEmojis.filter { it.shortcode != emoji.shortcode }
                    else
                        userEmojis + emoji
                    val tags = mutableListOf<List<String>>()
                    newFavs.forEach { tags.add(listOf("emoji", it.shortcode, it.url)) }
                    userEmojiSets.forEach { tags.add(listOf("a", it.pointer)) }
                    if (repository.updateEmojiList(tags)) {
                        userEmojis = newFavs
                        EmojiPickerCache.invalidate(pubkey)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EmojiSettings", "toggleFavorite failed", e)
                } finally {
                    togglingShortcode = null
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
                        EmojiPickerCache.invalidate(pubkey)
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
                    // セット削除時はそのセット内のお気に入りも一緒に除去
                    val setShortcodes = set.emojis.map { it.shortcode }.toSet()
                    val newFavs = userEmojis.filter { it.shortcode !in setShortcodes }
                    val tags = mutableListOf<List<String>>()
                    newFavs.forEach { tags.add(listOf("emoji", it.shortcode, it.url)) }
                    userEmojiSets.filter { it.pointer != set.pointer }.forEach { tags.add(listOf("a", it.pointer)) }
                    if (repository.updateEmojiList(tags)) {
                        userEmojis = newFavs
                        userEmojiSets = userEmojiSets.filter { it.pointer != set.pointer }
                        EmojiPickerCache.invalidate(pubkey)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EmojiSettings", "Failed to remove emoji set", e)
                } finally {
                    removingEmojiPointer = null
                }
            }
        }
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

    // ── UI ─────────────────────────────────────────────────────────────────

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
                    Text(
                        "カスタム絵文字",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (loadingEmojis) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = LineGreen, modifier = Modifier.size(24.dp))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {

                            // ── お気に入り絵文字（登録済み個別） ─────────────
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Favorite,
                                        null,
                                        tint = Color(0xFFEF5350),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "お気に入り絵文字",
                                        fontSize = 14.sp,
                                        color = nuruColors.textSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${userEmojis.size}個",
                                        fontSize = 12.sp,
                                        color = nuruColors.textTertiary
                                    )
                                }

                                if (userEmojis.isEmpty()) {
                                    Text(
                                        "下のセットから ♥ をタップしてお気に入り登録できます",
                                        fontSize = 12.sp,
                                        color = nuruColors.textTertiary,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else {
                                    // お気に入りをグリッド表示（1行6列）
                                    val rows = userEmojis.chunked(6)
                                    rows.forEach { rowEmojis ->
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            rowEmojis.forEach { emoji ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(nuruColors.bgTertiary)
                                                        .clickable { handleToggleFavorite(emoji) },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    AsyncImage(
                                                        model = emoji.url,
                                                        contentDescription = emoji.shortcode,
                                                        modifier = Modifier.size(30.dp).padding(2.dp),
                                                        contentScale = ContentScale.Fit
                                                    )
                                                    // ♥ バッジ（右上）
                                                    Box(
                                                        modifier = Modifier
                                                            .size(14.dp)
                                                            .align(Alignment.TopEnd)
                                                            .background(Color(0xFFEF5350), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Favorite,
                                                            null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(8.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            // 残り空白埋め
                                            repeat(6 - rowEmojis.size) {
                                                Spacer(modifier = Modifier.size(44.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            // ── セット別絵文字ブラウザ ────────────────────────
                            if (userEmojiSets.isNotEmpty()) {
                                userEmojiSets.forEach { set ->
                                    EmojiSetBrowser(
                                        set = set,
                                        favorites = userEmojis,
                                        togglingShortcode = togglingShortcode,
                                        onToggle = handleToggleFavorite,
                                        nuruColors = nuruColors
                                    )
                                }
                            } else if (!loadingEmojis) {
                                Text(
                                    "セットを追加するとここに絵文字が表示されます",
                                    fontSize = 12.sp,
                                    color = nuruColors.textTertiary
                                )
                            }

                            // ── 新しいセットを追加 ──────────────────────────
                            Column {
                                Text(
                                    "新しいセットを追加",
                                    fontSize = 14.sp,
                                    color = nuruColors.textSecondary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
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
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
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
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .border(0.5.dp, nuruColors.border, RoundedCornerShape(12.dp))
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            set.name,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = nuruColors.textPrimary
                                                        )
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
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(top = 8.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        set.emojis.take(8).forEach { emoji ->
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

                            // ── 登録済みセット一覧 ──────────────────────────
                            if (userEmojiSets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "登録済みセット",
                                        fontSize = 14.sp,
                                        color = nuruColors.textSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
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
                                                    Text(
                                                        set.name,
                                                        fontSize = 14.sp,
                                                        color = nuruColors.textPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val favCount = userEmojis.count { fav ->
                                                        set.emojis.any { it.shortcode == fav.shortcode }
                                                    }
                                                    Text(
                                                        "${set.emojiCount}個 ・ ♥ $favCount",
                                                        fontSize = 12.sp,
                                                        color = nuruColors.textTertiary
                                                    )
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
                        }
                    }
                }
            }
        }
    }
}

/** セット内の全絵文字をグリッド表示し、お気に入りトグルができるコンポーネント */
@Composable
private fun EmojiSetBrowser(
    set: EmojiSet,
    favorites: List<EmojiInfo>,
    togglingShortcode: String?,
    onToggle: (EmojiInfo) -> Unit,
    nuruColors: io.nurunuru.app.ui.theme.NuruColors
) {
    val favoriteShortcodes = remember(favorites) { favorites.map { it.shortcode }.toSet() }
    var expanded by remember { mutableStateOf(false) }
    val displayEmojis = if (expanded) set.emojis else set.emojis.take(18)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // セットヘッダー
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                set.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = nuruColors.textSecondary,
                modifier = Modifier.weight(1f)
            )
            val favInSet = favorites.count { fav -> set.emojis.any { it.shortcode == fav.shortcode } }
            if (favInSet > 0) {
                Text("♥ $favInSet", fontSize = 11.sp, color = Color(0xFFEF5350))
            }
            Text(
                if (expanded) "▲" else "▼",
                fontSize = 11.sp,
                color = nuruColors.textTertiary
            )
        }

        // 絵文字グリッド（1行6列）
        val rows = displayEmojis.chunked(6)
        rows.forEach { rowEmojis ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowEmojis.forEach { emoji ->
                    val isFav = emoji.shortcode in favoriteShortcodes
                    val isToggling = togglingShortcode == emoji.shortcode
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isFav) LineGreen.copy(alpha = 0.12f)
                                else nuruColors.bgTertiary
                            )
                            .clickable(enabled = !isToggling) {
                                onToggle(EmojiInfo(emoji.shortcode, emoji.url))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isToggling) {
                            CircularProgressIndicator(
                                color = LineGreen,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            AsyncImage(
                                model = emoji.url,
                                contentDescription = emoji.shortcode,
                                modifier = Modifier.size(30.dp).padding(2.dp),
                                contentScale = ContentScale.Fit
                            )
                            // お気に入りバッジ
                            if (isFav) {
                                Icon(
                                    Icons.Default.Favorite,
                                    null,
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .align(Alignment.TopEnd)
                                )
                            } else {
                                Icon(
                                    Icons.Default.FavoriteBorder,
                                    null,
                                    tint = nuruColors.textTertiary.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(12.dp)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                }
                // 端数の空白
                repeat(6 - rowEmojis.size) {
                    Spacer(modifier = Modifier.size(44.dp))
                }
            }
        }

        // 「もっと見る」ボタン
        if (!expanded && set.emojis.size > 18) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                Text(
                    "残り ${set.emojis.size - 18} 個を表示",
                    fontSize = 12.sp,
                    color = nuruColors.textTertiary
                )
            }
        }
    }
}
