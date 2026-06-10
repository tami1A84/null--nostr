import SwiftUI
import Observation

/// 4-tab navigation shell — ホーム / トーク / タイムライン / ミニアプリ.
/// App Store submission build: ろくなな / diVine short-video related UI is commented out.
/// Current iOS-forward sync target: keep tab content alive and use a black bottom nav.
struct MainTabView: View {

    let pubkeyHex:    String
    let authViewModel: AuthViewModel

    @Environment(\.nuruTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase
    @State private var activeTab:          BottomTab  = .home
    @State private var showPostSheet:      Bool       = false
    @State private var showNotifications:  Bool       = false
    @State private var searchRoute:        SearchSheetRoute? = nil
    @State private var hasNewNotifications: Bool      = false
    @State private var notificationPollTask: Task<Void, Never>? = nil
    @State private var viewingProfile:     ProfileID? = nil
    @State private var deepLinkedEventId:   EventID? = nil
    @State private var zapTarget:          ScoredPost? = nil
    @State private var hideBottomNavForExternalMiniApp: Bool = false
    @State private var didLoadTalkGroups: Bool = false
    @State private var showAppSettings:  Bool       = false

    // Shared repository — created once per session.
    @State private var repository: NostrRepository

    // ViewModels
    @State private var timelineVM:    TimelineViewModel
    @State private var homeVM:        HomeViewModel
    @State private var talkVM:        TalkViewModel
    @State private var connectionVM:  ConnectionViewModel

    init(pubkeyHex: String, authViewModel: AuthViewModel) {
        self.pubkeyHex     = pubkeyHex
        self.authViewModel = authViewModel

        // FFI is initialized lazily inside NostrRepository.ensureMlsClient().
        // Keep startup path stable and avoid early init_engine() lock-in on bad paths.
        let mlsClient: MlsFFIBridge? = nil

        let repo = NostrRepository(
            keyManager:     authViewModel.keyManager,
            prefs:          authViewModel.prefs,
            mlsClient:      mlsClient
        )
        _repository     = State(initialValue: repo)
        _timelineVM     = State(initialValue: TimelineViewModel(repository: repo, pubkeyHex: pubkeyHex))
        _homeVM         = State(initialValue: HomeViewModel(repository: repo, myPubkeyHex: pubkeyHex))
        _talkVM         = State(initialValue: TalkViewModel(repository: repo, myPubkeyHex: pubkeyHex))
        _connectionVM   = State(initialValue: ConnectionViewModel(repository: repo))
    }

    private func performLogout() {
        Task {
            await repository.clearSessionCachesForLogout()
            await repository.disconnect()
            await MainActor.run { authViewModel.logout() }
        }
    }

    var body: some View {
        ZStack {
            // CONNECTION STATUS BANNER (shown when disconnected/offline)
            VStack(spacing: 0) {
                ConnectionStatusBanner(viewModel: connectionVM)
                Spacer(minLength: 0)
            }
            .zIndex(10)
            .animation(.easeInOut(duration: 0.2), value: connectionVM.isFullyConnected)

            // TIMELINE (keep alive)
            tabContent(for: .timeline) {
                TimelineView(
                    viewModel:           timelineVM,
                    onPostTap:           { showPostSheet     = true },
                    onProfileTap:        { viewingProfile    = ProfileID($0) },
                    onNotificationBell:  { showNotifications = true; hasNewNotifications = false },
                    hasNewNotifications: hasNewNotifications,
                    onSearchTap:         {
                        searchRoute = SearchSheetRoute(query: "")
                    },
                    onHashtagTap: { tag in
                        // Use an item-driven sheet so the first presentation is built
                        // with the #tag query already captured. A Bool sheet can render
                        // from the previous state snapshot and require a second tap.
                        searchRoute = SearchSheetRoute(query: "#\(tag)")
                    }
                )
            }

            // HOME (keep alive)
            tabContent(for: .home) {
                HomeView(
                    viewModel:    homeVM,
                    repository:   repository,
                    onSettingsTap: { showAppSettings = true },
                    onPostTap:    { showPostSheet  = true },
                    onProfileTap: { viewingProfile = ProfileID($0) },
                    onMessageTap: { pubkey in
                        // DM ボタン — Android 同様トークタブに遷移 + DM 作成
                        activeTab = .talk
                        if !didLoadTalkGroups { didLoadTalkGroups = true }
                        Task { await talkVM.loadGroups(); await talkVM.createDmConversation(pubkey: pubkey) }
                    }
                )
            }

            // TALK (keep alive)
            tabContent(for: .talk) {
                TalkView(viewModel: talkVM)
            }

            // MINIAPP (recreated on demand)
            if activeTab == .miniapp {
                MiniAppsView(
                    pubkeyHex:    pubkeyHex,
                    repository:   repository,
                    prefs:        authViewModel.prefs,
                    connectionVM: connectionVM,
                    onExternalAppFullscreenChanged: { hidden in
                        hideBottomNavForExternalMiniApp = hidden
                    }
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.opacity.animation(.easeInOut(duration: NuruSpacing.durationFast)))
            }
        }
        // safeAreaInset places the tab bar below content and automatically
        // adjusts child safe areas so FABs and scroll views clear the tab bar.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if shouldShowBottomNav {
                bottomNavBar
            }
        }
        // PostSheet
        .sheet(isPresented: $showPostSheet) {
            PostSheet(
                repository:  repository,
                myPubkeyHex: pubkeyHex,
                myProfile:   homeVM.profile ?? repository.getCachedProfile(pubkey: pubkeyHex),
                onDismiss:   { showPostSheet = false },
                onSuccess:   {
                    showPostSheet = false
                    Task {
                        await timelineVM.refreshFollowing()
                        if homeVM.isOwnProfile { await homeVM.refresh() }
                    }
                }
            )
        }
        // NotificationSheet
        .sheet(isPresented: $showNotifications) {
            NotificationSheet(
                repository:   repository,
                myPubkeyHex:  pubkeyHex,
                prefs:        authViewModel.prefs,
                onProfileTap: { pubkey in
                    showNotifications = false
                    viewingProfile    = ProfileID(pubkey)
                }
            )
        }
        // SearchSheet
        .sheet(item: $searchRoute) { route in
            SearchSheet(
                repository:   repository,
                myPubkeyHex:  pubkeyHex,
                onProfileTap: { pubkey in
                    searchRoute    = nil
                    viewingProfile = ProfileID(pubkey)
                },
                initialQuery: route.query,
                onDismiss: { searchRoute = nil }
            )
        }
        // ZapSheet
        .sheet(item: $zapTarget) { post in
            ZapSheet(
                repository:   repository,
                myPubkeyHex:  pubkeyHex,
                targetPost:   post
            )
        }
        .fullScreenCover(isPresented: $showAppSettings) {
            AppSettingsView(
                pubkeyHex: pubkeyHex,
                authViewModel: authViewModel,
                repository: repository,
                prefs: authViewModel.prefs,
                onDismiss: { showAppSettings = false },
                onLogout: performLogout
            )
        }
        // UserProfileSheet — mirrors Android UserProfileModal with DM button
        .sheet(item: $deepLinkedEventId) { eid in
            PostDetailView(
                eventId: eid.id,
                repository: repository,
                myPubkeyHex: pubkeyHex
            )
        }
        .sheet(item: $viewingProfile) { pid in
            UserProfileSheet(
                pubkey:      pid.id,
                myPubkeyHex: pubkeyHex,
                repository:  repository,
                onStartDM: { pubkey in
                    viewingProfile = nil
                    activeTab = .talk
                    if !didLoadTalkGroups { didLoadTalkGroups = true }
                    Task { await talkVM.loadGroups(); await talkVM.createDmConversation(pubkey: pubkey) }
                }
            )
        }
        .task {
            // Start the compact relay pool first. Timeline/Home are local-first, so
            // this does not block first paint; it prevents their remote refreshes
            // from racing into many fetchRecovery joins during cold startup.
            await repository.connect()
            timelineVM.startInitialLoadIfNeeded()
            startNotificationDotPollingIfNeeded(initialDelaySeconds: 12)
        }
        .onChange(of: showNotifications) { _, showing in
            if !showing { markNotificationsSeen() }
        }
        .onChange(of: authViewModel.openedProfilePubkey) { _, pk in
            guard let pk, pk != pubkeyHex else { return }
            viewingProfile = ProfileID(pk)
            authViewModel.consumeOpenedProfilePubkey()
        }
        .onChange(of: authViewModel.openedEventId) { _, eventId in
            guard let eventId else { return }
            deepLinkedEventId = EventID(eventId)
            authViewModel.consumeOpenedEventId()
        }
        .onChange(of: scenePhase) { _, phase in
            // Keep timeline foreground path clean. MLS retry draining is triggered
            // from Talk-specific actions instead of every app foreground event.
            _ = phase
        }
    }


    private func startNotificationDotPollingIfNeeded(initialDelaySeconds: UInt64 = 0) {
        guard notificationPollTask == nil else { return }
        notificationPollTask = Task {
            if initialDelaySeconds > 0 {
                try? await Task.sleep(nanoseconds: initialDelaySeconds * 1_000_000_000)
            }
            var lastSeen = UserDefaults.standard.integer(forKey: "nuru_last_seen_notification_created_at")
            while !Task.isCancelled {
                let result = await repository.fetchNotificationsWithContext(pubkey: pubkeyHex, skipCache: false)
                let newest = Int(result.items.map(\.createdAt).max() ?? 0)
                if lastSeen == 0 {
                    lastSeen = newest
                    UserDefaults.standard.set(lastSeen, forKey: "nuru_last_seen_notification_created_at")
                } else if newest > lastSeen {
                    await MainActor.run { hasNewNotifications = true }
                }
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func markNotificationsSeen() {
        Task {
            let result = await repository.fetchNotificationsWithContext(pubkey: pubkeyHex, skipCache: false)
            let newest = Int(result.items.map(\.createdAt).max() ?? 0)
            UserDefaults.standard.set(newest, forKey: "nuru_last_seen_notification_created_at")
            hasNewNotifications = false
        }
    }

    // MARK: - Tab Content

    @ViewBuilder
    private func tabContent<V: View>(for tab: BottomTab, @ViewBuilder content: () -> V) -> some View {
        content()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .opacity(activeTab == tab ? 1 : 0)
            .animation(.easeInOut(duration: NuruSpacing.durationFast), value: activeTab)
            .allowsHitTesting(activeTab == tab)
    }

    // MARK: - Bottom Nav Bar

    private var shouldShowBottomNav: Bool {
        if activeTab == .miniapp && hideBottomNavForExternalMiniApp { return false }
        // Match LINE: an open Talk conversation owns the full screen; app tabs are hidden.
        if activeTab == .talk && talkVM.activeGroup != nil { return false }
        return true
    }

    private var bottomNavBar: some View {
        VStack(spacing: 0) {
            Divider()
                .background(theme.borderColor)
                .frame(height: 0.5)
            HStack(spacing: 0) {
                ForEach(BottomTab.allCases, id: \.self) { tab in
                    bottomTabItem(tab)
                }
            }
            .frame(height: 56)
        }
        // Extend the black background into the home-indicator safe area.
        .background(Color.black.ignoresSafeArea(edges: .bottom))
    }

    private func bottomTabItem(_ tab: BottomTab) -> some View {
        let selected = activeTab == tab
        let iconColor = selected ? NuruColors.lineGreen : theme.textTertiary
        return Button {
            if activeTab == tab {
                switch tab {
                case .timeline: Task { await timelineVM.refreshFollowing() }
                case .home:     Task { await homeVM.refresh() }
                default: break
                }
            }
            activeTab = tab
            if tab == .talk, !didLoadTalkGroups {
                didLoadTalkGroups = true
                Task { await talkVM.loadGroups() }
            }
        } label: {
            VStack(spacing: 2) {
                // Custom bottom-nav icons styled consistently with Android/iOS tab glyphs.
                Group {
                    switch tab {
                    case .home:     HomeIcon(filled: selected)
                    case .talk:     TalkIcon(filled: selected)
                    case .timeline: TimelineIcon(filled: selected)
                    case .miniapp:  GridIcon(filled: selected)
                    }
                }
                .frame(width: NuruSpacing.iconLg, height: NuruSpacing.iconLg)
                .foregroundStyle(iconColor)

                Text(tab.label)
                    .font(NuruFont.labelSmall())
                    .fontWeight(selected ? .semibold : .regular)
                    .foregroundStyle(iconColor)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Supporting Types

/// Identifiable wrapper for String, used with .sheet(item:) for profile navigation.
struct ProfileID: Identifiable {
    let id: String
    init(_ id: String) { self.id = id }
}

/// Identifiable wrapper for event ID, used with .sheet(item:) for post deep links.
struct EventID: Identifiable {
    let id: String
    init(_ id: String) { self.id = id }
}

// MARK: - Bottom Tab Enum

enum BottomTab: CaseIterable {
    case home, talk, timeline, miniapp

    var label: String {
        switch self {
        case .home:     return "ホーム"
        case .talk:     return "トーク"
        case .timeline: return "タイムライン"
        case .miniapp:  return "ミニアプリ"
        }
    }

    var iconFilled: String {
        switch self {
        case .home:     return NuruIcons.home(filled: true)
        case .talk:     return NuruIcons.talk(filled: true)
        case .timeline: return NuruIcons.timeline(filled: true)
        case .miniapp:  return NuruIcons.grid(filled: true)
        }
    }

    var iconOutline: String {
        switch self {
        case .home:     return NuruIcons.home(filled: false)
        case .talk:     return NuruIcons.talk(filled: false)
        case .timeline: return NuruIcons.timeline(filled: false)
        case .miniapp:  return NuruIcons.grid(filled: false)
        }
    }
}

// MARK: - Connection Status Banner

/// オフライン・切断時に画面上部に表示するバナー。
/// Mirrors Android のネットワーク状態インジケーター。
private struct ConnectionStatusBanner: View {
    let viewModel: ConnectionViewModel

    var body: some View {
        if !viewModel.isFullyConnected {
            HStack(spacing: 6) {
                Image(systemName: viewModel.isOnline ? "wifi.exclamationmark" : "wifi.slash")
                    .font(.system(size: 12, weight: .semibold))
                Text(viewModel.statusMessage)
                    .font(.system(size: 12, weight: .medium))
                Spacer()
                if viewModel.isOnline && viewModel.connectionState != .connecting {
                    Button {
                        Task { await viewModel.reconnect() }
                    } label: {
                        Text("再接続")
                            .font(.system(size: 12, weight: .bold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 3)
                            .background(Color.white.opacity(0.2))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, NuruSpacing.space4)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(bannerColor)
        }
    }

    private var bannerColor: Color {
        viewModel.isOnline ? Color(red: 0.8, green: 0.4, blue: 0.0) : Color(red: 0.7, green: 0.1, blue: 0.1)
    }
}

// MARK: - Placeholder

private struct PlaceholderTab: View {
    let title:   String
    let message: String
    @Environment(\.nuruTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: NuruSpacing.space3) {
            Text(title).font(NuruFont.titleLarge()).foregroundStyle(theme.textPrimary)
            Text(message).font(NuruFont.bodyMedium()).foregroundStyle(theme.textTertiary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.bgPrimary)
    }
}




private struct SearchSheetRoute: Identifiable, Equatable {
    let id = UUID()
    let query: String
}
