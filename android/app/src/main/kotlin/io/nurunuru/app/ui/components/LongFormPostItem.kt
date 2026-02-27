package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LocalNuruColors

@Composable
fun LongFormPostItem(
    post: ScoredPost,
    onLike: (emoji: String, tags: List<List<String>>) -> Unit,
    onRepost: () -> Unit,
    onProfileClick: (String) -> Unit,
    repository: io.nurunuru.app.data.NostrRepository,
    onDelete: (() -> Unit)? = null,
    onMute: (() -> Unit)? = null,
    onReport: ((String, String) -> Unit)? = null,
    onBirdwatch: ((String, String, String) -> Unit)? = null,
    onNotInterested: (() -> Unit)? = null,
    isOwnPost: Boolean = false,
    birdwatchNotes: List<io.nurunuru.app.data.models.NostrEvent> = emptyList()
) {
    val nuruColors = LocalNuruColors.current
    val profile = post.profile
    var showReader by remember { mutableStateOf(false) }

    var showReportModal by remember { mutableStateOf(false) }
    var showBirdwatchModal by remember { mutableStateOf(false) }
    var showZapModal by remember { mutableStateOf(false) }
    var showZapCustomModal by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }

    val title = post.event.getTagValue("title")
    val image = post.event.getTagValue("image")
    val summary = post.event.getTagValue("summary") ?: post.event.content.take(200)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(nuruColors.bgPrimary)
    ) {
        PostIndicators(post = post, onProfileClick = onProfileClick)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            UserAvatar(
                pictureUrl = profile?.picture,
                displayName = profile?.displayedName ?: "",
                size = 42.dp,
                modifier = Modifier.clickable { onProfileClick(post.event.pubkey) }
            )

            Column(modifier = Modifier.weight(1f)) {
                PostHeader(
                    post = post,
                    internalVerified = false,
                    onProfileClick = onProfileClick,
                    repository = repository,
                    onDelete = onDelete,
                    onMute = onMute,
                    onReport = { showReportModal = true },
                    onBirdwatch = { showBirdwatchModal = true },
                    onNotInterested = onNotInterested,
                    isOwnPost = isOwnPost
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Article Badge
                Surface(
                    color = nuruColors.lineGreen.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    Icon(Icons.AutoMirrored.Filled.Article, null, tint = nuruColors.lineGreen, modifier = Modifier.size(12.dp))
                        Text("長文記事", color = nuruColors.lineGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Article Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showReader = true },
                    color = nuruColors.bgSecondary,
                    border = androidx.compose.foundation.BorderStroke(1.dp, nuruColors.border)
                ) {
                    Column {
                        if (image != null) {
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(140.dp)
                            )
                        }
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (title != null) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = nuruColors.textSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "記事を読む →",
                                style = MaterialTheme.typography.labelSmall,
                                color = nuruColors.lineGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (birdwatchNotes.isNotEmpty()) {
                    BirdwatchDisplay(notes = birdwatchNotes, onAuthorClick = onProfileClick)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val coroutineScope = rememberCoroutineScope()
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                val toastState = LocalToastState.current

                PostActions(
                    post = post,
                    onLike = { onLike("+", emptyList()) },
                    onLikeLongPress = { showReactionPicker = true },
                    onRepost = onRepost,
                    onZap = {
                        val lud16 = profile?.lud16
                        if (lud16 != null) {
                            coroutineScope.launch {
                                val amount = repository.getDefaultZapAmount().toLong()
                                try {
                                    val invoice = repository.fetchLightningInvoice(lud16, amount)
                                    if (invoice != null) {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(invoice))
                                        toastState.show("⚡ ${amount} sats のインボイスをコピーしました", ToastType.SUCCESS)
                                    } else {
                                        toastState.show("インボイスの作成に失敗しました", ToastType.ERROR)
                                    }
                                } catch (e: Exception) {
                                    toastState.show("エラー: ${e.message}", ToastType.ERROR)
                                }
                            }
                        } else {
                            toastState.show("Lightningアドレスが設定されていません", ToastType.ERROR)
                        }
                    },
                    onZapLongPress = { showZapCustomModal = true }
                )
            }
        }
        HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
    }

    if (showReader) {
        ArticleReaderModal(
            post = post,
            title = title,
            image = image,
            onDismiss = { showReader = false },
            onProfileClick = onProfileClick
        )
    }

    if (showZapModal || showZapCustomModal) {
        ZapModal(
            post = post,
            repository = repository,
            onDismiss = {
                showZapModal = false
                showZapCustomModal = false
            },
            onSuccess = {
                showZapModal = false
                showZapCustomModal = false
            }
        )
    }

    if (showReactionPicker) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            EmojiPicker(
                pubkey = post.event.pubkey,
                onSelect = { emoji ->
                    onLike(":${emoji.shortcode}:", listOf(listOf("emoji", emoji.shortcode, emoji.url)))
                    showReactionPicker = false
                },
                onClose = { showReactionPicker = false },
                repository = repository
            )
        }
    }

    if (showReportModal) {
        ReportModal(
            onDismiss = { showReportModal = false },
            onReport = { type, content ->
                showReportModal = false
                onReport?.invoke(type, content)
            }
        )
    }

    if (showBirdwatchModal) {
        BirdwatchModal(
            onDismiss = { showBirdwatchModal = false },
            onSubmit = { type, content, url ->
                showBirdwatchModal = false
                onBirdwatch?.invoke(type, content, url)
            },
            existingNotes = birdwatchNotes
        )
    }
}

@Composable
fun ArticleReaderModal(
    post: ScoredPost,
    title: String?,
    image: String?,
    onDismiss: () -> Unit,
    onProfileClick: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                Surface(
                    color = nuruColors.bgPrimary,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, nuruColors.border)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .height(56.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = nuruColors.textPrimary)
                        }

                        Surface(
                            color = nuruColors.lineGreen.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                            Icon(Icons.AutoMirrored.Filled.Article, null, tint = nuruColors.lineGreen, modifier = Modifier.size(14.dp))
                                Text("長文記事", color = nuruColors.lineGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Text(
                            text = formatPostTimestamp(post.event.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = nuruColors.textTertiary
                        )
                    }
                }
            },
            containerColor = nuruColors.bgPrimary
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                }

                Column(modifier = Modifier.padding(20.dp)) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 32.sp
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Author info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                            .clickable {
                                onDismiss()
                                onProfileClick(post.event.pubkey)
                            }
                    ) {
                        UserAvatar(
                            pictureUrl = post.profile?.picture,
                            displayName = post.profile?.displayedName ?: "",
                            size = 40.dp
                        )
                        Column {
                            Text(
                                text = post.profile?.displayedName ?: "Anonymous",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = formatPostTimestamp(post.event.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = nuruColors.textTertiary
                            )
                        }
                    }

                    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                    Spacer(Modifier.height(20.dp))

                    // Content
                    Text(
                        text = post.event.content,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 24.sp,
                        color = nuruColors.textPrimary
                    )

                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}
