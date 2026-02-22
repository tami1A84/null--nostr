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
import kotlinx.coroutines.launch
import android.util.Log
import io.nurunuru.*
import uniffi.nurunuru.*
import io.nurunuru.app.NuruNuruApp
import io.nurunuru.app.data.EngineManager
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.*

private const val TAG = "NuruNuru-Main"

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
    MINIAPP(
        label = "ミニアプリ",
        selectedIcon = Icons.Filled.Apps,
        unselectedIcon = Icons.Outlined.Apps
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

    val isEngineReady by EngineManager.isReady.collectAsState()

    if (!isEngineReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(color = LineGreen)
        }
        return
    }

    // Get shared client from manager
    val nuruClient = EngineManager.getClient()
    val repository = remember(nuruClient) { NostrRepository(nuruClient, app.prefs) }

    // Connect on start
    LaunchedEffect(nuruClient) {
        try {
            Log.d(TAG, "Connecting to relays...")
            nuruClient.connectAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Initial connection failed", e)
        }
    }

    // ViewModels
    val timelineVM: TimelineViewModel = viewModel(
        factory = TimelineViewModel.Factory(repository, pubkeyHex)
    )
    val talkVM: TalkViewModel = viewModel(
        factory = TalkViewModel.Factory(repository, pubkeyHex)
    )
    val homeVM: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(repository, pubkeyHex)
    )

    // My profile for post modal avatar
    val homeState by homeVM.uiState.collectAsState()
    val myProfile = homeState.profile

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
                                    BottomTab.MINIAPP -> {}
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
                BottomTab.HOME -> HomeScreen(viewModel = homeVM)
                BottomTab.TALK -> TalkScreen(viewModel = talkVM)
                BottomTab.TIMELINE -> TimelineScreen(
                    viewModel = timelineVM,
                    myPictureUrl = myProfile?.picture,
                    myDisplayName = myProfile?.displayedName ?: ""
                )
                BottomTab.MINIAPP -> SettingsScreen(
                    authViewModel = authViewModel,
                    prefs = app.prefs,
                    pubkeyHex = pubkeyHex,
                    pictureUrl = myProfile?.picture
                )
            }
        }
    }
}
