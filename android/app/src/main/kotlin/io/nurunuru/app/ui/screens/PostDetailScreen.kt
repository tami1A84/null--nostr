package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.addBookmark
import io.nurunuru.app.data.fetchEvent
import io.nurunuru.app.data.fetchEvents
import io.nurunuru.app.data.models.NostrKind
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    eventId: String,
    repository: NostrRepository,
    myPubkey: String,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {}
) {
    val nuruColors = LocalNuruColors.current

    val coroutineScope = rememberCoroutineScope()
    var post by remember { mutableStateOf<ScoredPost?>(null) }
    var replies by remember { mutableStateOf<List<ScoredPost>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(eventId) {
        isLoading = true
        post = repository.fetchEvent(eventId)
        try {
            val replyFilter = NostrClient.Filter(
                kinds = listOf(NostrKind.TEXT_NOTE),
                tags = mapOf("e" to listOf(eventId)),
                limit = 50
            )
            val replyEvents = repository.fetchEvents(replyFilter, timeoutMs = 5_000)
            val enriched = repository.enrichPostsDirect(replyEvents)
            replies = enriched
                .filter { it.event.id != eventId }
                .sortedBy { it.event.createdAt }
        } catch (_: Exception) {}
        isLoading = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("投稿", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = nuruColors.textPrimary,
                    navigationIconContentColor = nuruColors.textPrimary
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LineGreen)
            }
        } else if (post == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("投稿が見つかりませんでした", color = nuruColors.textSecondary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
            ) {
                item(key = "main_${post!!.event.id}") {
                    PostItem(
                        post = post!!,
                        onLike = { _, _ -> },
                        onRepost = {},
                        onProfileClick = onProfileClick,
                        repository = repository,
                        onBookmark = { coroutineScope.launch { repository.addBookmark(myPubkey, post!!.event.id) } },
                        myPubkey = myPubkey
                    )
                }

                if (replies.isNotEmpty()) {
                    item(key = "replies_header") {
                        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                    }
                    items(replies, key = { it.event.id }) { reply ->
                        PostItem(
                            post = reply,
                            onLike = { _, _ -> },
                            onRepost = {},
                            onProfileClick = onProfileClick,
                            repository = repository,
                            onBookmark = { coroutineScope.launch { repository.addBookmark(myPubkey, reply.event.id) } },
                            myPubkey = myPubkey
                        )
                    }
                }
            }
        }
    }
}
