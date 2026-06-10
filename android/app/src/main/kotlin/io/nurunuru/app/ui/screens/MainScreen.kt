package io.nurunuru.app.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import io.nurunuru.app.NuruNuruApp
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.SecureKeyManager
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.components.ConnectionStatusBanner
import io.nurunuru.app.ui.components.UserProfileModal
import io.nurunuru.app.ui.components.AccountStatusCard
import io.nurunuru.app.ui.components.AccountSecuritySection
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.*

enum class BottomTab(val label: String) {
    HOME("ホーム"),
    TALK("トーク"),
    TIMELINE("タイムライン"),
    NEWS("ニュース"),
    MINIAPP("ミニ")
}

@Composable
fun BottomTab.getIcon(isSelected: Boolean): ImageVector {
    return when (this) {
        BottomTab.HOME -> io.nurunuru.app.ui.icons.NuruIcons.Home(isSelected)
        BottomTab.TALK -> io.nurunuru.app.ui.icons.NuruIcons.Talk(isSelected)
        BottomTab.TIMELINE -> io.nurunuru.app.ui.icons.NuruIcons.Timeline(isSelected)
        BottomTab.NEWS -> Icons.Default.Article
        BottomTab.MINIAPP -> io.nurunuru.app.ui.icons.NuruIcons.Grid(isSelected)
    }
}

@Composable
fun MainScreen(
    pubkeyHex: String,
    hasInternalKey: Boolean,
    keyManager: SecureKeyManager,
    authViewModel: AuthViewModel,
    app: NuruNuruApp
) {
    val context = LocalContext.current
    val view = LocalView.current
    val nuruColors = LocalNuruColors.current
    var activeTab by remember { mutableStateOf(BottomTab.HOME) }
    var isExternalAppOpen by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var selectedNoteEventId by remember { mutableStateOf<String?>(null) }
    var selectedNoteInitialPost by remember { mutableStateOf<ScoredPost?>(null) }
    var deepLinkedProfilePubkey by remember { mutableStateOf<String?>(null) }

    // Create shared NostrClient and Repository
    // NostrCache と RecommendationEngine は NuruNuruApp.onCreate() で事前生成済み。
    // remember { } は参照を保持するだけで SharedPreferences I/O は発生しない。
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            window.statusBarColor = android.graphics.Color.BLACK
            window.navigationBarColor = android.graphics.Color.BLACK
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
        onDispose { }
    }

    val activeRelays = remember { app.prefs.nip65Relays.map { it.url }.ifEmpty { app.prefs.relays.toList() } }
    val recommendationEngine = remember { app.recommendationEngine }
    val loginMethod = app.prefs.loginMethod
    val nostrClient = remember(loginMethod, activeRelays) {
        if (loginMethod != "nosskey" && !hasInternalKey && app.prewarmedNostrClient != null && app.prefs.nip65Relays.isEmpty()) {
            // Reuse the startup pre-warmed client only before a NIP-65 relay list is known.
            // Nosskey must NOT reuse this client because it was created with ExternalSigner;
            // it needs a NosskeySigner so startup does not touch SecureKeyManager.
            app.prewarmedNostrClient!!
        } else {
            val activity = context as? Activity
            val signer = authViewModel.buildSigner(activity)
            NostrClient(
                context = context,
                relays = activeRelays,
                signer = signer
            ).also { it.connect() }
        }
    }
    DisposableEffect(nostrClient) {
        onDispose {
            // Logout removes MainScreen from composition. Close relay sockets immediately
            // so old account subscriptions/caches do not survive until process restart.
            try { nostrClient.disconnect() } catch (_: Exception) { }
        }
    }

    val nostrCache = remember { app.nostrCache }
    val repository = remember { NostrRepository(nostrClient, app.prefs, nostrCache, recommendationEngine) }

    LaunchedEffect(authViewModel, pubkeyHex) {
        launch {
            authViewModel.profileNavigationEvents.collect { pk ->
                if (pk != pubkeyHex) deepLinkedProfilePubkey = pk
            }
        }
        launch {
            authViewModel.eventNavigationEvents.collect { eventId ->
                selectedNoteEventId = eventId
                selectedNoteInitialPost = null
            }
        }
    }

    // ViewModels
    val timelineVM: TimelineViewModel = viewModel(
        TimelineViewModel::class.java,
        key = "timeline-$pubkeyHex-${app.prefs.loginMethod ?: "unknown"}",
        factory = TimelineViewModel.Factory(repository, pubkeyHex)
    )
    val talkVM: TalkViewModel = viewModel(
        TalkViewModel::class.java,
        key = "talk-$pubkeyHex-${app.prefs.loginMethod ?: "unknown"}",
        factory = TalkViewModel.Factory(repository, nostrClient, pubkeyHex)
    )
    val homeVM: HomeViewModel = viewModel(
        HomeViewModel::class.java,
        key = "home-$pubkeyHex-${app.prefs.loginMethod ?: "unknown"}",
        factory = HomeViewModel.Factory(repository, pubkeyHex)
    )
    val connectionVM: ConnectionViewModel = viewModel(
        ConnectionViewModel::class.java,
        key = "connection-$pubkeyHex-${app.prefs.loginMethod ?: "unknown"}",
        factory = ConnectionViewModel.Factory(context.applicationContext, activeRelays)
    )
    val newsVM: NewsViewModel = viewModel(
        NewsViewModel::class.java,
        key = "news-$pubkeyHex-${app.prefs.loginMethod ?: "unknown"}",
        factory = NewsViewModel.Factory(repository, app.prefs)
    )

    fun performLogout() {
        // Reset in-process UI/session state before AuthState switches to LoggedOut.
        // Without this, Compose ViewModels can show old timeline/profile/connection
        // state until the whole Android process is restarted.
        try { timelineVM.clearSearch() } catch (_: Exception) { }
        try { homeVM.clearSearch() } catch (_: Exception) { }
        try { talkVM.clearStateAfterCacheClear() } catch (_: Exception) { }
        try { repository.clearAllCache() } catch (_: Exception) { }
        try { nostrClient.disconnect() } catch (_: Exception) { }
        try { app.nostrCache.clearAll() } catch (_: Exception) { }
        try { app.clearPrewarmedClient() } catch (_: Exception) { }
        showAppSettings = false
        authViewModel.logout()
    }

    // My profile for post modal avatar
    val homeState by homeVM.uiState.collectAsState()
    val talkState by talkVM.uiState.collectAsState()
    val myProfile = homeState.profile
    val shouldShowBottomNav = !isExternalAppOpen && !(activeTab == BottomTab.TALK && talkState.activeGroup != null)

    // ── バックグラウンドプリフェッチ ─────────────────────────────────────────
    // タイムライン表示中に他タブのデータをバックグラウンドで取得しておく。
    // talkVM.loadGroups() は TalkViewModel.init 内で既に呼ばれているため不要。
    LaunchedEffect(pubkeyHex) {
        launch { homeVM.loadMyProfile() }
    }

    // Disconnect on dispose
    DisposableEffect(nostrClient) {
        onDispose { nostrClient.disconnect() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Each screen should ideally handle its own top bar to manage internal state
            // but we need to coordinate insets.
            // We'll let child screens provide their top bars or keep them internal for now
            // but coordinate through MainScreen's contentWindowInsets.
        },
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = shouldShowBottomNav,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(120)) { it },
                exit  = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(120)) { it }
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) {
                    // Top border for the nav bar to match web style
                    androidx.compose.material3.HorizontalDivider(
                        color = io.nurunuru.app.ui.theme.BorderColor,
                        thickness = 0.5.dp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BottomTab.entries.forEach { tab ->
                            val isSelected = activeTab == tab
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null // No ripple for a cleaner look matching web
                                    ) {
                                        if (activeTab == tab) {
                                            when (tab) {
                                                BottomTab.TIMELINE -> timelineVM.refresh()
                                                BottomTab.TALK -> talkVM.loadGroups()
                                                BottomTab.HOME -> homeVM.refresh()
                                                BottomTab.NEWS -> newsVM.refresh()
                                                BottomTab.MINIAPP -> {}
                                            }
                                        }
                                        activeTab = tab
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = tab.getIcon(isSelected),
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isSelected) LineGreen else nuruColors.textTertiary
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = tab.label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) LineGreen else nuruColors.textTertiary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
            } // AnimatedVisibility
        },
        containerColor = Color.Black
    ) { paddingValues ->
        // 3タブ（HOME・TALK・TIMELINE）は常時コンポーズして状態（スクロール位置等）を保持する。
        // AnimatedVisibility は非表示時もコンポジションツリーに残るため ViewModel 状態が失われない。
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            ConnectionStatusBanner(
                viewModel = connectionVM,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
            )


            // ── TIMELINE ──────────────────────────────────────────────────────
            androidx.compose.animation.AnimatedVisibility(
                visible = activeTab == BottomTab.TIMELINE,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit  = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                modifier = Modifier.fillMaxSize()
            ) {
                TimelineScreen(
                    viewModel = timelineVM,
                    repository = repository,
                    prefs = app.prefs,
                    myPubkey = pubkeyHex,
                    myPictureUrl = myProfile?.picture,
                    myDisplayName = myProfile?.displayedName ?: "",
                    onStartDM = { partnerPubkey ->
                        talkVM.createDmConversation(partnerPubkey)
                        activeTab = BottomTab.TALK
                    },
                    onNoteClick = { eventId, post ->
                        selectedNoteEventId = eventId
                        selectedNoteInitialPost = post
                    }
                )
            }

            // ── HOME ──────────────────────────────────────────────────────────
            androidx.compose.animation.AnimatedVisibility(
                visible = activeTab == BottomTab.HOME,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit  = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                modifier = Modifier.fillMaxSize()
            ) {
                HomeScreen(
                    viewModel = homeVM,
                    repository = repository,
                    onSettingsTap = { showAppSettings = true },
                    onStartDM = { partnerPubkey ->
                        talkVM.createDmConversation(partnerPubkey)
                        activeTab = BottomTab.TALK
                    },
                    onNoteClick = { eventId, post ->
                        selectedNoteEventId = eventId
                        selectedNoteInitialPost = post
                    }
                )
            }

            if (showAppSettings) {
                AppSettingsDialog(
                    authViewModel = authViewModel,
                    prefs = app.prefs,
                    pubkeyHex = pubkeyHex,
                    onDismiss = { showAppSettings = false },
                    onLogout = { performLogout() }
                )
            }

            // ── TALK ──────────────────────────────────────────────────────────
            androidx.compose.animation.AnimatedVisibility(
                visible = activeTab == BottomTab.TALK,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit  = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                modifier = Modifier.fillMaxSize()
            ) {
                TalkScreen(viewModel = talkVM, myPubkeyHex = pubkeyHex, repository = repository)
            }

            // ── NEWS ─────────────────────────────────────────────────────────
            androidx.compose.animation.AnimatedVisibility(
                visible = activeTab == BottomTab.NEWS,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit  = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                modifier = Modifier.fillMaxSize()
            ) {
                NewsScreen(viewModel = newsVM)
            }

            // ── MINIAPP (Settings) — 軽量なため都度レンダリングで問題なし ────
            if (activeTab == BottomTab.MINIAPP) {
                MiniAppsScreen(
                    repository = repository,
                    prefs = app.prefs,
                    pubkeyHex = pubkeyHex,
                    pictureUrl = myProfile?.picture,
                    onExternalAppOpenChanged = { isExternalAppOpen = it },
                    onMlsCacheCleared = { talkVM.clearStateAfterCacheClear() }
                )
            }

            // ── POST DETAIL ────────────────────────────────────────────────
            if (selectedNoteEventId != null) {
                PostDetailScreen(
                    eventId = selectedNoteEventId!!,
                    initialPost = selectedNoteInitialPost,
                    repository = repository,
                    myPubkey = pubkeyHex,
                    onBack = {
                        selectedNoteEventId = null
                        selectedNoteInitialPost = null
                    },
                    onProfileClick = { pubkey ->
                        selectedNoteEventId = null
                        selectedNoteInitialPost = null
                        homeVM.loadProfile(pubkey)
                        activeTab = BottomTab.HOME
                    }
                )
            }
        }

        if (deepLinkedProfilePubkey != null) {
            val linkedProfileViewModel: HomeViewModel = viewModel(
                HomeViewModel::class.java,
                key = "deeplink_profile_$deepLinkedProfilePubkey",
                factory = HomeViewModel.Factory(repository, pubkeyHex)
            )
            UserProfileModal(
                pubkey = deepLinkedProfilePubkey!!,
                viewModel = linkedProfileViewModel,
                repository = repository,
                onDismiss = { deepLinkedProfilePubkey = null },
                onStartDM = { pk ->
                    deepLinkedProfilePubkey = null
                    activeTab = BottomTab.TALK
                    talkVM.createDmConversation(pk)
                }
            )
        }
    }
}


@Composable
private fun AppSettingsDialog(
    authViewModel: AuthViewModel,
    prefs: AppPreferences,
    pubkeyHex: String,
    onDismiss: () -> Unit,
    onLogout: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val nuruColors = LocalNuruColors.current
    var showLogoutConfirm by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = nuruColors.bgPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("設定", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "閉じる") }
                }
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AccountStatusCard(prefs = prefs, pubkeyHex = pubkeyHex, onLogoutClick = { showLogoutConfirm = true })
                    if (!prefs.isExternalSigner) {
                        AccountSecuritySection(authViewModel = authViewModel, prefs = prefs)
                    }
                    AppSettingsRow(Icons.Default.Share, "招待", "友だちを招待リンクで共有") {
                        val npub = io.nurunuru.app.data.NostrKeyUtils.encodeNpub(pubkeyHex) ?: pubkeyHex
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "https://www.nullnull.app/p/$npub")
                            putExtra(Intent.EXTRA_TITLE, "ぬるぬるに招待")
                        }
                        context.startActivity(Intent.createChooser(intent, "招待リンクを共有"))
                    }
                    AppSettingsRow(Icons.Default.PanTool, "プライバシーポリシー", "個人情報とデータの取り扱いを確認") { uriHandler.openUri("https://tami1A84.github.io/null--nostr/privacy.html") }
                    AppSettingsRow(Icons.Default.Description, "利用規約", "禁止事項、通報、ブロックについて確認") { uriHandler.openUri("https://tami1A84.github.io/null--nostr/terms.html") }
                }
            }
        }
    }
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("ログアウト") },
            text = { Text("ログアウトします。秘密鍵はこのデバイスから削除されます。") },
            confirmButton = { TextButton(onClick = onLogout) { Text("ログアウト", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("キャンセル") } }
        )
    }
}

@Composable
private fun AppSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    titleColor: Color? = null,
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = nuruColors.bgSecondary, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = titleColor ?: nuruColors.textSecondary) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = titleColor ?: nuruColors.textPrimary)
            Text(subtitle, fontSize = 12.sp, color = nuruColors.textTertiary)
        }
        if (titleColor == null) Icon(Icons.Default.ChevronRight, null, tint = nuruColors.textTertiary)
    }
}
