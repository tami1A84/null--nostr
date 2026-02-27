package io.nurunuru.app.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nurunuru.app.NuruNuruApp
import io.nurunuru.app.data.NostrClient
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.*

enum class BottomTab(val label: String) {
    HOME("ホーム"),
    TALK("トーク"),
    TIMELINE("タイムライン"),
    MINIAPP("ミニアプリ")
}

@Composable
fun BottomTab.getIcon(isSelected: Boolean): ImageVector {
    return when (this) {
        BottomTab.HOME -> io.nurunuru.app.ui.icons.NuruIcons.Home(isSelected)
        BottomTab.TALK -> io.nurunuru.app.ui.icons.NuruIcons.Talk(isSelected)
        BottomTab.TIMELINE -> io.nurunuru.app.ui.icons.NuruIcons.Timeline(isSelected)
        BottomTab.MINIAPP -> io.nurunuru.app.ui.icons.NuruIcons.Grid(isSelected)
    }
}

@Composable
fun MainScreen(
    pubkeyHex: String,
    privateKeyHex: String?,
    authViewModel: AuthViewModel,
    app: NuruNuruApp
) {
    val context = LocalContext.current
    val nuruColors = LocalNuruColors.current
    var activeTab by remember { mutableStateOf(BottomTab.TIMELINE) }

    // Create shared NostrClient and Repository
    val nostrClient = remember {
        val signer = if (privateKeyHex != null) {
            io.nurunuru.app.data.InternalSigner(privateKeyHex)
        } else {
            io.nurunuru.app.data.ExternalSigner.apply {
                setCurrentUser(pubkeyHex)
            }
        }

        NostrClient(
            context = context,
            relays = app.prefs.relays.toList(),
            signer = signer
        ).also { it.connect() }
    }
    val repository = remember { NostrRepository(nostrClient, app.prefs) }

    // ViewModels
    val timelineVM: TimelineViewModel = viewModel(
        factory = TimelineViewModel.Factory(repository, pubkeyHex)
    )
    val talkVM: TalkViewModel = viewModel(
        factory = TalkViewModel.Factory(repository, nostrClient, pubkeyHex)
    )
    val homeVM: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(repository, pubkeyHex)
    )

    // My profile for post modal avatar
    val homeState by homeVM.uiState.collectAsState()
    val myProfile = homeState.profile

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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black,
                tonalElevation = 0.dp
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
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
                                                BottomTab.TALK -> talkVM.loadConversations()
                                                BottomTab.HOME -> homeVM.refresh()
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
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (activeTab) {
                BottomTab.HOME -> HomeScreen(viewModel = homeVM, repository = repository)
                BottomTab.TALK -> TalkScreen(viewModel = talkVM)
                BottomTab.TIMELINE -> TimelineScreen(
                    viewModel = timelineVM,
                    repository = repository,
                    myPubkey = pubkeyHex,
                    myPictureUrl = myProfile?.picture,
                    myDisplayName = myProfile?.displayedName ?: ""
                )
                BottomTab.MINIAPP -> SettingsScreen(
                    authViewModel = authViewModel,
                    repository = repository,
                    prefs = app.prefs,
                    pubkeyHex = pubkeyHex,
                    pictureUrl = myProfile?.picture
                )
            }
        }
    }
}
