package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.TimelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchModal(
    viewModel: TimelineViewModel,
    onClose: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val nuruColors = LocalNuruColors.current
    var query by remember { mutableStateOf(uiState.searchQuery) }

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
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = MaterialTheme.colorScheme.onBackground)
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        placeholder = { Text("ノートを検索...", fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(NuruIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = ""; viewModel.clearSearch() }) {
                                    Icon(Icons.Default.Close, contentDescription = "クリア", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = nuruColors.bgSecondary,
                            unfocusedContainerColor = nuruColors.bgSecondary,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            cursorColor = LineGreen
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isNotBlank()) viewModel.search(query)
                        })
                    )

                    TextButton(
                        onClick = { if (query.isNotBlank()) viewModel.search(query) },
                        enabled = query.isNotBlank() && !uiState.isSearching
                    ) {
                        Text("検索", color = if (query.isNotBlank()) LineGreen else nuruColors.textTertiary)
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
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                    } else {
                        // Empty state / Recent searches placeholder
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
