package io.nurunuru.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.MlsGroup
import io.nurunuru.app.data.models.MlsMessage
import io.nurunuru.app.ui.components.*
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.TalkViewModel
import kotlinx.coroutines.launch

@Composable
fun TalkScreen(viewModel: TalkViewModel, myPubkeyHex: String, repository: NostrRepository) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadGroupsIfNeeded()
        viewModel.ensureKeyPackagePublished()
    }

    if (uiState.activeGroupId != null && uiState.activeGroup != null) {
        GroupChatScreen(
            viewModel = viewModel,
            group = uiState.activeGroup!!,
            messages = uiState.messages,
            isLoading = uiState.messagesLoading,
            isSending = uiState.sendingMessage,
            myPubkeyHex = myPubkeyHex,
            repository = repository
        )
    } else {
        GroupListScreen(
            viewModel = viewModel,
            groups = uiState.groups,
            isLoading = uiState.isLoading,
            myPubkeyHex = myPubkeyHex
        )
    }
}

private enum class TalkFilter { ALL, FRIENDS, GROUPS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupListScreen(
    viewModel: TalkViewModel,
    groups: List<MlsGroup>,
    isLoading: Boolean,
    myPubkeyHex: String
) {
    val nuruColors = LocalNuruColors.current
    val uiState by viewModel.uiState.collectAsState()
    var showNewChatModal by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState { 3 }
    val coroutineScope = rememberCoroutineScope()

    // ── Talk 一覧の pull-to-refresh (iOS Talk リストとパリティ) ─────────
    val listPullRefreshState = rememberPullToRefreshState()
    var listPullInFlight by remember { mutableStateOf(false) }
    var listPullSawLoading by remember { mutableStateOf(false) }

    if (listPullRefreshState.isRefreshing && !listPullInFlight) {
        LaunchedEffect(listPullRefreshState.isRefreshing) {
            listPullInFlight = true
            listPullSawLoading = false
            viewModel.refreshGroupList()
            // Safety timeout: loadGroups() の in-flight ガードや例外パスで
            // isLoading 遷移が見られないケースでも必ずスピナーを解除する。
            kotlinx.coroutines.delay(15_000)
            if (listPullInFlight) {
                listPullRefreshState.endRefresh()
                listPullInFlight = false
                listPullSawLoading = false
            }
        }
    }
    LaunchedEffect(isLoading, listPullInFlight) {
        if (listPullInFlight && isLoading) listPullSawLoading = true
        if (listPullInFlight && listPullSawLoading && !isLoading) {
            listPullRefreshState.endRefresh()
            listPullInFlight = false
            listPullSawLoading = false
        }
    }

    val activeFilter = remember(pagerState.currentPage) {
        listOf(TalkFilter.ALL, TalkFilter.FRIENDS, TalkFilter.GROUPS)[pagerState.currentPage]
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text("トーク", fontWeight = FontWeight.SemiBold) },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showAddMenu = true },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(32.dp)
                                .background(LineGreen, CircleShape)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                "新規トーク",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("新しいトーク") },
                                onClick = { showAddMenu = false; showNewChatModal = true }
                            )
                            DropdownMenuItem(
                                text = { Text("グループ作成") },
                                onClick = { showAddMenu = false; viewModel.showCreateGroup() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // 新しいトーク (1:1 DM) modal
        if (showNewChatModal) {
            NewChatModal(
                onDismiss = { showNewChatModal = false },
                onStartChat = { pubkey ->
                    showNewChatModal = false
                    viewModel.createDmConversation(pubkey)
                }
            )
        }

        // グループ作成 modal
        if (uiState.showCreateGroup) {
            CreateGroupModal(
                followingProfiles = uiState.followingProfiles,
                isLoadingFollowing = uiState.followingLoading,
                onCreate = { name, members ->
                    viewModel.createGroupChat(name, members)
                },
                onDismiss = { viewModel.hideCreateGroup() }
            )
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ── サブヘッダー（フィルタータブ） ────────────────────────────
            TalkFilterBar(
                active = activeFilter,
                onSelect = { filter ->
                    val page = listOf(TalkFilter.ALL, TalkFilter.FRIENDS, TalkFilter.GROUPS).indexOf(filter)
                    if (page >= 0) coroutineScope.launch { pagerState.animateScrollToPage(page) }
                }
            )
            HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

            // ── スワイプページャー ─────────────────────────────────────────
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                val pageFilter = listOf(TalkFilter.ALL, TalkFilter.FRIENDS, TalkFilter.GROUPS)[page]
                val filteredGroups = remember(groups, pageFilter) {
                    when (pageFilter) {
                        TalkFilter.ALL     -> groups
                        TalkFilter.FRIENDS -> groups.filter { it.isDm }
                        TalkFilter.GROUPS  -> groups.filter { !it.isDm }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(listPullRefreshState.nestedScrollConnection)
                ) {
                    when {
                        isLoading && groups.isEmpty() -> Column { repeat(8) { ListItemSkeleton() } }
                        filteredGroups.isEmpty() -> TalkEmptyState()
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredGroups, key = { it.groupIdHex }) { group ->
                                Surface(color = MaterialTheme.colorScheme.background) {
                                    Column {
                                        GroupItem(
                                            group = group,
                                            myPubkeyHex = myPubkeyHex,
                                            onClick = { viewModel.openGroup(group.groupIdHex) }
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = nuruColors.border,
                                            thickness = 0.5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                    PullToRefreshContainer(
                        state = listPullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = if (listPullRefreshState.isRefreshing || listPullRefreshState.progress > 0f) MaterialTheme.colorScheme.surface else Color.Transparent,
                        contentColor = LineGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun TalkFilterBar(active: TalkFilter, onSelect: (TalkFilter) -> Unit) {
    val nuruColors = LocalNuruColors.current
    val filters = listOf(
        TalkFilter.ALL     to "すべて",
        TalkFilter.FRIENDS to "友だち",
        TalkFilter.GROUPS  to "グループ"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { (filter, label) ->
            val selected = active == filter
            Surface(
                onClick = { onSelect(filter) },
                shape = RoundedCornerShape(20.dp),
                color = if (selected) LineGreen else nuruColors.bgSecondary,
                modifier = Modifier.height(32.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) Color.White else nuruColors.textPrimary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatScreen(
    viewModel: TalkViewModel,
    group: MlsGroup,
    messages: List<MlsMessage>,
    isLoading: Boolean,
    isSending: Boolean,
    myPubkeyHex: String,
    repository: NostrRepository
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()
    var pullRefreshInFlight by remember { mutableStateOf(false) }
    var pullRefreshSawLoading by remember { mutableStateOf(false) }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { _ -> /* TODO: image upload */ }

    if (pullRefreshState.isRefreshing && !pullRefreshInFlight) {
        LaunchedEffect(group.groupIdHex, pullRefreshState.isRefreshing) {
            pullRefreshInFlight = true
            pullRefreshSawLoading = false
            viewModel.refreshCurrentGroup()
            // Safety timeout: in-flight dedupe path or any other case where the
            // ViewModel never toggles messagesLoading must still release the
            // spinner so the user is never stuck. ~15s matches the relay fetch
            // timeout in fetchMlsMessages(repairFull=true).
            kotlinx.coroutines.delay(15_000)
            if (pullRefreshInFlight) {
                pullRefreshState.endRefresh()
                pullRefreshInFlight = false
                pullRefreshSawLoading = false
            }
        }
    }
    LaunchedEffect(isLoading, pullRefreshInFlight) {
        if (pullRefreshInFlight && isLoading) pullRefreshSawLoading = true
        if (pullRefreshInFlight && pullRefreshSawLoading && !isLoading) {
            pullRefreshState.endRefresh()
            pullRefreshInFlight = false
            pullRefreshSawLoading = false
        }
    }

    // ユーザがリスト末尾付近 (最後から 3 件以内) にいるときだけ自動スクロールする。
    // 上にスクロールして過去メッセージを読んでいる最中は介入しない。
    // これにより:
    //   * 新着メッセージで自然に末尾追従する (LINE / Discord と同じ挙動)
    //   * 上にスクロールしている間は Material3 PullToRefreshContainer の
    //     nestedScrollConnection が下方向ドラッグを受け取れるようになり、
    //     pull-to-refresh が綺麗に発火する。
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = layoutInfo.totalItemsCount
        val isNearBottom = totalItems == 0 || lastVisibleIndex >= totalItems - 3
        if (isNearBottom) listState.animateScrollToItem(messages.size - 1)
    }

    val title = if (!group.isDm && group.name.isNotBlank()) {
        group.name
    } else {
        val partnerPubkey = group.memberPubkeys.firstOrNull { it != myPubkeyHex } ?: ""
        group.memberProfiles[partnerPubkey]?.displayedName
            ?: NostrKeyUtils.shortenPubkey(partnerPubkey).ifEmpty { group.groupIdHex.take(8) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeGroup() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.showGroupInfo() }) {
                        Icon(Icons.Outlined.Info, contentDescription = "グループ情報")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(pullRefreshState.nestedScrollConnection)
                    // Chat screens normally auto-scroll to the latest message at
                    // the visual bottom. Material3 PullToRefresh only receives a
                    // downward drag after the child LazyColumn is already at
                    // scroll position 0, so a user pulling from the top edge of
                    // the visible message area can otherwise just scroll older
                    // messages without ever reaching PullToRefresh. Observe a
                    // top-edge downward drag as an explicit Talk refresh fallback.
                    .pointerInput(group.groupIdHex, pullRefreshInFlight) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val topEdgeTriggerPx = 200.dp.toPx()
                            val refreshThresholdPx = 40.dp.toPx()
                            if (down.position.y > topEdgeTriggerPx) return@awaitEachGesture

                            var previousY = down.position.y
                            var pulledDown = 0f
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break

                                val dy = change.position.y - previousY
                                previousY = change.position.y
                                pulledDown = maxOf(0f, pulledDown + dy)

                                if (
                                    pulledDown >= refreshThresholdPx &&
                                    !pullRefreshState.isRefreshing &&
                                    !pullRefreshInFlight
                                ) {
                                    android.util.Log.d(
                                        "TalkScreen",
                                        "edgePullRefresh group=" + group.groupIdHex +
                                            " pulled=" + pulledDown.toInt()
                                    )
                                    pullRefreshState.startRefresh()
                                    change.consume()
                                    break
                                }
                            }
                        }
                    }
            ) {
                if (isLoading && messages.isEmpty()) {
                    Column(Modifier.padding(top = 16.dp)) {
                        repeat(5) { i -> MessageSkeleton(alignRight = i % 2 == 1) }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MlsMessageBubble(message = message, myPubkeyHex = myPubkeyHex)
                        }
                    }
                }
                PullToRefreshContainer(
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = if (pullRefreshState.isRefreshing || pullRefreshState.progress > 0f) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = LineGreen
                )
            }

            HorizontalDivider(
                color = LocalNuruColors.current.border,
                thickness = 0.5.dp
            )
            MessageInputBar(
                onSendMessage = { text ->
                    viewModel.sendMessage(uiState.activeGroup?.groupIdHex ?: group.groupIdHex, text)
                },
                onImageAttach = { imagePickerLauncher.launch("image/*") },
                isSending = isSending,
                activeGroupIdHex = uiState.activeGroup?.groupIdHex ?: group.groupIdHex,
                myPubkeyHex = myPubkeyHex,
                repository = repository
            )
        }
    }

    // Group info modal
    if (uiState.showGroupInfo) {
        GroupInfoModal(
            group = group,
            myPubkeyHex = myPubkeyHex,
            onDismiss = { viewModel.hideGroupInfo() },
            onLeave = { viewModel.leaveGroup() },
            onRepair = { viewModel.repairCurrentGroup() },
            isRepairing = uiState.messagesLoading
        )
    }
}
