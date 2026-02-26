package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.SearchNavigationEvent
import io.nurunuru.app.viewmodel.TimelineViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchModal(
    viewModel: TimelineViewModel,
    onClose: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    var query by remember { mutableStateOf(uiState.searchQuery) }

    // Handle navigation events from ViewModel
    LaunchedEffect(viewModel.navigationEvents) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SearchNavigationEvent.OpenProfile -> {
                    onClose()
                    onProfileClick(event.pubkey)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = nuruColors.bgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "閉じる",
                            tint = nuruColors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            placeholder = { Text("キーワード / npub / NIP-05 / note", fontSize = 14.sp, color = nuruColors.textTertiary) },
                            leadingIcon = {
                                Icon(NuruIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = nuruColors.textTertiary)
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = ""; viewModel.clearSearch() }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "クリア",
                                            modifier = Modifier.size(18.dp),
                                            tint = nuruColors.textTertiary
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = CircleShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = nuruColors.bgSecondary,
                                unfocusedContainerColor = nuruColors.bgSecondary,
                                focusedBorderColor = LineGreen,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = LineGreen,
                                focusedTextColor = nuruColors.textPrimary,
                                unfocusedTextColor = nuruColors.textPrimary
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                if (query.isNotBlank()) viewModel.search(query)
                            })
                        )
                    }

                    Button(
                        onClick = { if (query.isNotBlank()) viewModel.search(query) },
                        enabled = query.isNotBlank() && !uiState.isSearching,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LineGreen,
                            contentColor = Color.White,
                            disabledContainerColor = LineGreen.copy(alpha = 0.5f)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.height(36.dp),
                        shape = CircleShape
                    ) {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("検索", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                // Content
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.isSearching) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LineGreen)
                        }
                    } else if (uiState.searchResults.isEmpty() && uiState.searchQuery.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(nuruColors.bgSecondary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(NuruIcons.Search, contentDescription = null, modifier = Modifier.size(32.dp), tint = nuruColors.textTertiary)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "「${uiState.searchQuery}」に一致する結果が見つかりませんでした",
                                color = nuruColors.textSecondary,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                        }
                    } else if (uiState.searchResults.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(nuruColors.bgSecondary)
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "${uiState.searchResults.size} 件の結果",
                                        fontSize = 12.sp,
                                        color = nuruColors.textSecondary
                                    )
                                }
                            }
                            items(uiState.searchResults, key = { it.event.id }) { post ->
                                PostItem(
                                    post = post,
                                    onLike = { viewModel.likePost(post.event.id) },
                                    onRepost = { viewModel.repostPost(post.event.id) },
                                    onProfileClick = { onProfileClick(post.event.pubkey) },
                                    birdwatchNotes = uiState.birdwatchNotes[post.event.id] ?: emptyList()
                                )
                            }
                        }
                    } else if (uiState.searchQuery.isEmpty()) {
                        // Empty state / Recent searches
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            if (uiState.recentSearches.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "最近の検索",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = nuruColors.textSecondary
                                        )
                                        TextButton(onClick = { viewModel.clearRecentSearches() }) {
                                            Text("すべてクリア", fontSize = 12.sp, color = nuruColors.textTertiary)
                                        }
                                    }
                                }

                                item {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        uiState.recentSearches.forEach { search ->
                                            RecentSearchChip(
                                                text = search,
                                                onSearch = {
                                                    query = it
                                                    viewModel.search(it)
                                                },
                                                onDelete = { viewModel.removeRecentSearch(it) }
                                            )
                                        }
                                    }
                                }
                            } else {
                                item {
                                    Column(
                                        modifier = Modifier.fillParentMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .background(nuruColors.bgSecondary, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(NuruIcons.Search, contentDescription = null, modifier = Modifier.size(32.dp), tint = nuruColors.textTertiary)
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            "キーワード、npub、note、NIP-05で検索",
                                            color = nuruColors.textSecondary,
                                            fontSize = 14.sp
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

@Composable
fun RecentSearchChip(
    text: String,
    onSearch: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Surface(
        modifier = Modifier
            .height(32.dp)
            .clickable { onSearch(text) },
        shape = CircleShape,
        color = nuruColors.bgSecondary
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = nuruColors.textPrimary,
                modifier = Modifier.weight(1f, false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = { onDelete(text) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "削除",
                    tint = nuruColors.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
