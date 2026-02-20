package io.nurunuru.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

enum class BottomTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME(
        label = "ホーム",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    TALK(
        label = "トーク",
        selectedIcon = Icons.Filled.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubble
    ),
    TIMELINE(
        label = "タイムライン",
        selectedIcon = Icons.Filled.DynamicFeed,
        unselectedIcon = Icons.Outlined.DynamicFeed
    ),
    SETTINGS(
        label = "設定",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
}

@Composable
fun MainScreen(
    pubkeyHex: String,
    privateKeyHex: String,
    authViewModel: AuthViewModel,
    app: NuruNuruApp
) {
    val nuruColors = LocalNuruColors.current
    var activeTab by remember { mutableStateOf(BottomTab.TIMELINE) }

    // Create shared NostrClient and Repository
    val nostrClient = remember {
        NostrClient(
            relays = app.prefs.relays.toList(),
            privateKeyHex = privateKeyHex,
            publicKeyHex = pubkeyHex
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

    // Profile tap: load the tapped user in HomeViewModel and switch to HOME tab
    val onProfileClick: (String) -> Unit = { targetPubkey ->
        homeVM.loadProfile(targetPubkey)
        activeTab = BottomTab.HOME
    }

    // My profile for post modal avatar and optimistic post display
    val homeState by homeVM.uiState.collectAsState()
    val myProfile = homeState.profile

    // Keep TimelineViewModel in sync with own profile for optimistic posts
    LaunchedEffect(myProfile) {
        timelineVM.setMyProfile(myProfile)
    }

    // Disconnect on dispose
    DisposableEffect(nostrClient) {
        onDispose { nostrClient.disconnect() }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                BottomTab.entries.forEach { tab ->
                    val isSelected = activeTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (activeTab == tab) {
                                // Tap same tab = refresh
                                when (tab) {
                                    BottomTab.TIMELINE -> timelineVM.refresh()
                                    BottomTab.TALK -> talkVM.loadConversations()
                                    BottomTab.HOME -> homeVM.refresh()
                                    BottomTab.SETTINGS -> {}
                                }
                            }
                            activeTab = tab
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LineGreen,
                            selectedTextColor = LineGreen,
                            unselectedIconColor = nuruColors.textTertiary,
                            unselectedTextColor = nuruColors.textTertiary,
                            indicatorColor = LineGreen.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (activeTab) {
                BottomTab.HOME -> HomeScreen(
                    viewModel = homeVM,
                    onBack = if (homeState.viewingPubkey != null) {
                        { activeTab = BottomTab.TIMELINE }
                    } else null
                )
                BottomTab.TALK -> TalkScreen(viewModel = talkVM)
                BottomTab.TIMELINE -> TimelineScreen(
                    viewModel = timelineVM,
                    myPictureUrl = myProfile?.picture,
                    myDisplayName = myProfile?.displayedName ?: "",
                    myPubkeyHex = pubkeyHex,
                    onProfileClick = onProfileClick
                )
                BottomTab.SETTINGS -> SettingsScreen(
                    authViewModel = authViewModel,
                    prefs = app.prefs,
                    pubkeyHex = pubkeyHex,
                    pictureUrl = myProfile?.picture
                )
            }
        }
    }
}
