package io.nurunuru.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.*
import io.nurunuru.app.viewmodel.HomeViewModel

@Composable
fun BookmarkListModal(
    viewModel: HomeViewModel,
    repository: io.nurunuru.app.data.NostrRepository,
    onDismiss: () -> Unit,
    onProfileClick: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val bookmarkedPosts = uiState.bookmarkedPosts

    LaunchedEffect(Unit) {
        viewModel.loadBookmarks()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // TopBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary)
                    }
                    Text(
                        "ブックマーク",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

                if (uiState.isBookmarksLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LineGreen)
                    }
                } else if (bookmarkedPosts.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(NuruIcons.Bookmark(false), null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("ブックマークがありません", color = TextSecondary, fontSize = 16.sp)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(bookmarkedPosts, key = { it.event.id }) { post ->
                            Surface(Modifier.padding(horizontal = 12.dp), color = Color.Black) {
                                PostItem(
                                    post = post,
                                    onLike = { emoji, tags -> viewModel.likePost(post.event.id, emoji, tags) },
                                    onRepost = { viewModel.repostPost(post.event.id) },
                                    onProfileClick = onProfileClick,
                                    repository = repository,
                                    onDelete = null,
                                    onMute = { viewModel.muteUser(post.event.pubkey) },
                                    onReport = { type, content -> viewModel.reportEvent(post.event.id, post.event.pubkey, type, content) },
                                    onBirdwatch = null,
                                    onNotInterested = null,
                                    onBookmark = { viewModel.removeBookmark(post.event.id) },
                                    isOwnPost = false,
                                    isVerified = false,
                                    myPubkey = viewModel.myPubkeyHex
                                )
                            }
                            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}
