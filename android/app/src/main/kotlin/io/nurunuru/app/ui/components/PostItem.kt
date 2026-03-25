package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.data.*
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

@Composable
fun PostItem(
    post: ScoredPost,
    onLike: (emoji: String, tags: List<List<String>>) -> Unit,
    onRepost: () -> Unit,
    onProfileClick: (String) -> Unit,
    repository: io.nurunuru.app.data.NostrRepository,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onMute: (() -> Unit)? = null,
    onReport: ((String, String) -> Unit)? = null, // type, content
    onBirdwatch: ((String, String, String) -> Unit)? = null, // type, content, url
    onNotInterested: (() -> Unit)? = null,
    onBookmark: (() -> Unit)? = null,
    isOwnPost: Boolean = false,
    isVerified: Boolean = false,
    birdwatchNotes: List<io.nurunuru.app.data.models.NostrEvent> = emptyList(),
    onHashtagClick: ((String) -> Unit)? = null,
    onNoteClick: ((String) -> Unit)? = null,
    myPubkey: String = ""
) {
    val nuruColors = LocalNuruColors.current
    val profile = post.profile

    // NIP-05 verification is now handled in the ViewModel/Repository
    val internalVerified = post.isVerified || isVerified

    // Content Warning state
    val cwReason = post.event.getTagValue("content-warning")
    var isCWExpanded by remember { mutableStateOf(cwReason == null) }

    // Content length threshold for collapsing
    val COLLAPSE_THRESHOLD = 140
    val shouldCollapse = remember(post.event.content) {
        val textLength = getTextLengthWithoutLinks(post.event.content)
        textLength > COLLAPSE_THRESHOLD
    }
    var isExpanded by remember { mutableStateOf(false) }

    var showReportModal by remember { mutableStateOf(false) }
    var showBirdwatchModal by remember { mutableStateOf(false) }
    var showZapModal by remember { mutableStateOf(false) }
    var showZapCustomModal by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var showQuoteRepost by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
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
            // Avatar
            UserAvatar(
                pictureUrl = profile?.picture,
                displayName = profile?.displayedName ?: "",
                size = 42.dp,
                modifier = Modifier.clickable { onProfileClick(post.event.pubkey) }
            )

            Column(modifier = Modifier.weight(1f)) {
                PostHeader(
                    post = post,
                    internalVerified = internalVerified,
                    onProfileClick = onProfileClick,
                    repository = repository,
                    onDelete = onDelete,
                    onMute = onMute,
                    onReport = { showReportModal = true },
                    onBirdwatch = { showBirdwatchModal = true },
                    onNotInterested = onNotInterested,
                    isOwnPost = isOwnPost
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Content Warning
                if (cwReason != null && !isCWExpanded) {
                    PostCWOverlay(reason = cwReason, onExpand = { isCWExpanded = true })
                } else {
                    if (cwReason != null) {
                        PostCWHeader(reason = cwReason, onCollapse = { isCWExpanded = false })
                    }

                    if (shouldCollapse && !isExpanded) {
                        PostContent(
                            post = post,
                            repository = repository,
                            onProfileClick = onProfileClick,
                            overrideContent = post.event.content.take(140) + "...",
                            onHashtagClick = onHashtagClick,
                            onNoteClick = onNoteClick
                        )
                        PostMedia(post = post, overrideContent = post.event.content.take(140))
                        Text(
                            text = "もっと見る",
                            style = MaterialTheme.typography.labelSmall,
                            color = nuruColors.lineGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { isExpanded = true }
                                .padding(vertical = 4.dp)
                        )
                    } else {
                        PostContent(post = post, repository = repository, onProfileClick = onProfileClick, onHashtagClick = onHashtagClick, onNoteClick = onNoteClick)
                        PostMedia(post = post)
                        if (shouldCollapse && isExpanded) {
                            Text(
                                text = "閉じる",
                                style = MaterialTheme.typography.labelSmall,
                                color = nuruColors.lineGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { isExpanded = false }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                    onQuoteRepost = { showQuoteRepost = true },
                    onBookmark = onBookmark,
                    onZap = {
                        val lud16 = profile?.lud16
                        if (lud16 != null) {
                            coroutineScope.launch {
                                val amount = repository.getDefaultZapAmount().toLong()
                                try {
                                    val invoice = repository.fetchLightningInvoice(lud16, amount)
                                    if (invoice != null) {
                                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(invoice))
                                        toastState.show("⚡ ₿${amount} のインボイスをコピーしました", ToastType.SUCCESS)
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

    if (showZapModal || showZapCustomModal) {
        ZapModal(
            post = post,
            repository = repository,
            onDismiss = {
                showZapModal = false
                showZapCustomModal = false
            },
            onSuccess = { invoice ->
                showZapModal = false
                showZapCustomModal = false
                // handle invoice? (currently copy-only in modal)
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
                pubkey = myPubkey.ifEmpty { post.event.pubkey },
                onSelect = { emoji ->
                    onLike(":${emoji.shortcode}:", listOf(listOf("emoji", emoji.shortcode, emoji.url)))
                    showReactionPicker = false
                },
                onClose = { showReactionPicker = false },
                repository = repository,
                individualOnly = true
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

    if (showQuoteRepost) {
        QuoteRepostModal(
            post = post,
            repository = repository,
            onDismiss = { showQuoteRepost = false },
            onProfileClick = onProfileClick
        )
    }
}

@Composable
private fun PostCWOverlay(reason: String, onExpand: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(Color(0xFFFF9800).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .clickable { onExpand() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = Color(0xFFFF9800),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "表示する",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PostCWHeader(reason: String, onCollapse: () -> Unit) {
    val nuruColors = LocalNuruColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Icon(Icons.Outlined.Warning, null, tint = Color(0xFFFF9800), modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(reason, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
        Spacer(Modifier.weight(1f))
        Text(
            "隠す",
            style = MaterialTheme.typography.labelSmall,
            color = nuruColors.textTertiary,
            modifier = Modifier.clickable { onCollapse() }
        )
    }
}
