import Foundation
import Observation

/// Feed type — mirrors Android FeedType enum.
enum FeedType: Equatable {
    case relay      // "リレー" — global feed from connected relay
    case following  // "フォロー" — posts from followed users
}

/// Timeline screen state and logic.
/// @Observable + @MainActor ensures all property updates happen on the main thread.
/// Mirrors Android TimelineViewModel structure.
@Observable
@MainActor
final class TimelineViewModel {

    // MARK: - Published State

    var relayPosts:          [ScoredPost] = []
    var followingPosts:      [ScoredPost] = []
    /// キャッシュが存在する場合は false で初期化（Android 同様、キャッシュファーストでスケルトン表示しない）。
    /// キャッシュが空の場合のみ true でスケルトン表示。
    var isRelayLoading:      Bool = false
    var isFollowingLoading:  Bool = false
    var isRelayRefreshing:   Bool = false
    var isFollowingRefreshing: Bool = false
    var isRelayLoadingMore:  Bool = false
    var isFollowingLoadingMore: Bool = false
    var hasMoreRelayPosts:   Bool = true
    var hasMoreFollowingPosts: Bool = true
    private var relayPageCursor: Int64? = nil
    private var followingPageCursor: Int64? = nil
    private var relayEmptyPageStreak: Int = 0
    private var followingEmptyPageStreak: Int = 0
    var feedType:            FeedType = .following
    var followList:          [String]  = []
    var errorMessage:        String?   = nil
    // Relay selector
    var savedRelayUrls:      [String]  = []
    var selectedRelayUrl:    String?   = nil
    // New-post dot indicators
    var hasNewRelayPosts:    Bool = false
    var hasNewFollowingPosts: Bool = false
    // New-post counts (displayed in pill: "新しい投稿 N件")
    var newRelayPostCount:     Int = 0
    var newFollowingPostCount: Int = 0

    // MARK: - Live Streaming State

    /// アクティブフィードの未読新着投稿数（「新しい投稿」ピルに表示）。
    var pendingLivePostsCount: Int = 0

    // バッファ済み新着投稿（ピルタップで先頭挿入）
    private var pendingRelayPosts:     [ScoredPost] = []
    private var pendingFollowingPosts: [ScoredPost] = []

    // ライブサブスクリプション ID
    private var relayLiveSubId:     String? = nil
    private var followingLiveSubId: String? = nil

    // ポーリングループ Task（画面離脱時にキャンセル）
    private var livePollingTask: Task<Void, Never>? = nil
    // リレー切替リフレッシュの重複実行防止
    private var relaySelectionTask: Task<Void, Never>? = nil
    // SwiftUI may evaluate MainTabView.init multiple times. Keep network side effects
    // out of init and start them exactly once for the retained @State instance.
    private var didStartInitialLoad: Bool = false
    private var inFlightLikeEventIds: Set<String> = []
    private var inFlightRepostEventIds: Set<String> = []
    private var inFlightBookmarkEventIds: Set<String> = []

    // MARK: - Dependencies

    let repository: NostrRepository
    let pubkeyHex: String

    // MARK: - Init

    init(repository: NostrRepository, pubkeyHex: String) {
        self.repository = repository
        self.pubkeyHex  = pubkeyHex
        AppLogger.log("Timeline", "TimelineViewModel init — pubkey: \(pubkeyHex.prefix(16))…")

        // ── Step 0: キャッシュから即時表示（nonisolated — actor hop なし） ──
        // Android: loadData() で getCachedTimeline() を Dispatchers.IO で即時読み取り → UI 更新。
        // iOS: nonisolated メソッドで同期的にキャッシュ読み取り → isLoading 解除。
        let cachedFollows = repository.getCachedFollowList(pubkey: pubkeyHex)

        let cachedGlobalPosts = Self.cachedScoredPosts(
            from: repository.getCachedGlobalTimeline(),
            repository: repository
        )
        let cachedFollowingPosts = Self.cachedScoredPosts(
            from: repository.getCachedFollowingTimeline(),
            repository: repository
        )

        relayPosts = cachedGlobalPosts
        followingPosts = cachedFollowingPosts
        relayPageCursor = olderCursor(for: cachedGlobalPosts)
        followingPageCursor = olderCursor(for: cachedFollowingPosts)

        // Local-first: show the last good timeline immediately and refresh relays
        // in the background. Skeletons are shown only on a truly cold cache.
        isRelayLoading = cachedGlobalPosts.isEmpty
        isFollowingLoading = cachedFollowingPosts.isEmpty
        if let follows = cachedFollows, !follows.isEmpty {
            followList = follows
        }

    }


    private static func cachedScoredPosts(from events: [NostrEvent], repository: NostrRepository) -> [ScoredPost] {
        let displayKinds = Set([NostrKind.textNote, NostrKind.longForm])
        var seen = Set<String>()
        return events
            .filter { displayKinds.contains($0.kind) }
            .sorted { $0.createdAt > $1.createdAt }
            .filter { seen.insert($0.id).inserted }
            .prefix(80)
            .map { event in
                let post = ScoredPost(event: event)
                post.profile = repository.getCachedProfile(pubkey: event.pubkey)
                return post
            }
    }

    /// Starts network work for the retained ViewModel instance.
    /// Do not perform this in init: SwiftUI can recreate View structs and evaluate
    /// @State(initialValue:) arguments repeatedly, which previously caused duplicate
    /// timeline loads, bookmark fetches, and relay reconnects during startup.
    func startInitialLoadIfNeeded() {
        guard !didStartInitialLoad else { return }
        didStartInitialLoad = true

        Task {
            // First paint first. Relay dropdown/NIP-65/prefetch run only after
            // fast posts are visible, otherwise they compete with the hot path.
            await loadFreshData()

            Task {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                savedRelayUrls = await repository.getSavedRelayUrls()
                for url in savedRelayUrls.prefix(2) {
                    Task { await repository.prefetchRelayTimeline(url) }
                }

                // NIP-65 can expand selectedRelays substantially. Keep it out of the
                // first startup minute; compact relays are enough for first paint.
                try? await Task.sleep(nanoseconds: 82_000_000_000)
                await repository.syncNip65Relays()
                savedRelayUrls = await repository.getSavedRelayUrls()
                AppLogger.log("Timeline", "NIP-65 sync complete — relays saved=\(savedRelayUrls.count)")
            }
        }
    }

    // MARK: - Load

    /// リレーから最新データを取得する（キャッシュ表示済みの状態で呼ばれる）。
    /// Android: loadData() 内の loadFollowList/loadGlobalTimeline/loadFollowingTimeline に対応。
    ///
    /// フォロータイムラインを最優先で取得・表示する:
    ///   1. フォローリストを取得（必要なら fetchEvents 側で接続を自動確立）
    ///   2. フォロータイムラインを取得 + enrich（ユーザーの最優先フィード）
    ///   3. グローバルタイムラインをバックグラウンドで取得
    private func loadFreshData() async {
        AppLogger.log("Timeline", "loadFreshData start")

        // ── Step 1: cached follow list で即 following fast fetch を開始 ──
        // Fresh follow-list fetch used to cost several seconds before following
        // posts could load. Use the cached list for first paint, then refresh the
        // list in the background and re-run fast following if it changed.
        let initialFollowList = followList
        AppLogger.log("Timeline", "Using cached follow list for first paint: \(initialFollowList.count) follows")
        if !initialFollowList.isEmpty {
            Task {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                await repository.prefetchProfilesAndBadges(pubkeys: initialFollowList, limit: 24)
            }
        }

        // フォロー表示を優先しつつ、リレー取得は fast path で並列先行開始。
        async let globalTask: [ScoredPost] = repository.fetchGlobalTimelineFast()

        // ── Step 2: フォロータイムラインを最優先で取得（fast path） ──
        let freshFollowing: [ScoredPost]
        if initialFollowList.isEmpty {
            freshFollowing = []
        } else {
            freshFollowing = await repository.fetchFollowingTimelineFast(authors: initialFollowList)
        }
        if !freshFollowing.isEmpty {
            followingPosts = mergeTimeline(followingPosts, with: freshFollowing)
            followingPageCursor = mergedCursor(current: followingPageCursor, incoming: freshFollowing)
            hasMoreFollowingPosts = true
        }
        isFollowingLoading = false
        AppLogger.log("Timeline", "Following fast posts loaded: \(freshFollowing.count)")
        if !followList.isEmpty { await restartFollowingLiveTimeline() }

        // ── Step 3: グローバルタイムライン fast 結果を反映 ──
        let freshGlobal = await globalTask
        if !freshGlobal.isEmpty {
            relayPosts = mergeTimeline(relayPosts, with: freshGlobal)
            relayPageCursor = mergedCursor(current: relayPageCursor, incoming: freshGlobal)
            hasMoreRelayPosts = true
        }
        isRelayLoading = false
        AppLogger.log("Timeline", "Relay fast posts loaded: \(freshGlobal.count)")

        startLivePolling()

        Task { [weak self, initialFollowList] in
            guard let self else { return }
            let freshFollows = await repository.refreshFollowList(pubkey: pubkeyHex)
            guard !freshFollows.isEmpty, freshFollows != initialFollowList else { return }
            followList = freshFollows
            AppLogger.log("Timeline", "Fresh follow list applied: \(freshFollows.count) follows")
            Task {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                await repository.prefetchProfilesAndBadges(pubkeys: freshFollows, limit: 24)
            }
            await restartFollowingLiveTimeline()
            let refreshed = await repository.fetchFollowingTimelineFast(authors: freshFollows)
            if !refreshed.isEmpty {
                followingPosts = mergeTimeline(followingPosts, with: refreshed)
                followingPageCursor = mergedCursor(current: followingPageCursor, incoming: refreshed)
                hasMoreFollowingPosts = true
                isFollowingLoading = false
                AppLogger.log("Timeline", "Following fast posts refreshed after follow-list update: \(refreshed.count)")
                let snapshot = refreshed
                Task { [weak self, snapshot] in
                    guard let self else { return }
                    let enriched = await repository.enrichTimelinePosts(snapshot)
                    if self.samePostIds(self.followingPosts, snapshot), !enriched.isEmpty {
                        self.followingPosts = enriched
                        AppLogger.log("Timeline", "Following enrich applied after follow refresh: \(enriched.count)")
                    }
                }
            }
        }

        let followingSnapshot = followingPosts
        let relaySnapshot = relayPosts
        Task { [weak self, followingSnapshot, relaySnapshot] in
            guard let self else { return }
            async let enrichedFollowingTask = repository.enrichTimelinePosts(followingSnapshot)
            async let enrichedRelayTask = repository.enrichTimelinePosts(relaySnapshot)
            let (enrichedFollowing, enrichedRelay) = await (enrichedFollowingTask, enrichedRelayTask)
            if self.samePostIds(self.followingPosts, followingSnapshot), !enrichedFollowing.isEmpty {
                self.followingPosts = enrichedFollowing
                AppLogger.log("Timeline", "Following enrich applied: \(enrichedFollowing.count)")
            }
            if self.samePostIds(self.relayPosts, relaySnapshot), !enrichedRelay.isEmpty {
                self.relayPosts = enrichedRelay
                AppLogger.log("Timeline", "Relay enrich applied: \(enrichedRelay.count)")
            }
        }
    }

    private func samePostIds(_ lhs: [ScoredPost], _ rhs: [ScoredPost]) -> Bool {
        guard lhs.count == rhs.count else { return false }
        return zip(lhs, rhs).allSatisfy { $0.event.id == $1.event.id }
    }

    private func olderCursor(for posts: [ScoredPost]) -> Int64? {
        posts.map { $0.event.createdAt }.min().map { $0 - 1 }
    }

    /// Keep pagination contiguous when fresh posts are merged with much older cache.
    /// An incoming batch entirely older than the current cursor is stale cache and
    /// must not move the cursor backwards over the missing gap.
    private func mergedCursor(current: Int64?, incoming: [ScoredPost]) -> Int64? {
        guard let next = olderCursor(for: incoming),
              let newest = incoming.map({ $0.event.createdAt }).max() else { return current }
        if let current, newest < current { return current }
        return next
    }

    private func mergeTimeline(_ existing: [ScoredPost], with incoming: [ScoredPost]) -> [ScoredPost] {
        var seen = Set<String>()
        return (incoming + existing)
            .sorted { $0.event.createdAt > $1.event.createdAt }
            .filter { seen.insert($0.event.id).inserted }
    }

    private func restartFollowingLiveTimeline() async {
        if let existing = followingLiveSubId {
            await repository.stopLiveTimeline(subId: existing)
            followingLiveSubId = nil
        }
        guard !followList.isEmpty else {
            AppLogger.log("Timeline", "Following live timeline skipped: empty follow list")
            return
        }
        followingLiveSubId = await repository.startLiveTimeline(authors: followList)
        AppLogger.log("Timeline", "Following live timeline restarted — follows=\(followList.count) subId=\(followingLiveSubId ?? "nil")")
    }

    private func enrichProfiles(for feed: FeedType) async {
        var posts = feed == .relay ? relayPosts : followingPosts
        guard !posts.isEmpty else { return }
        guard hasMissingProfiles(in: posts) else { return }

        // 全 pubkey を収集（投稿者 + repostedBy の pubkey）
        var pubkeySet = Set(posts.map { $0.event.pubkey })
        for post in posts {
            if let rp = post.repostedBy { pubkeySet.insert(rp.pubkey) }
        }

        // 1. キャッシュ済みプロフィールを即時適用（ネットワーク待ちなし）
        // ただし表示に必要な情報が欠損しているキャッシュは再取得対象にする
        var missingPubkeys: [String] = []
        var profileMap: [String: UserProfile] = [:]
        let requirePicture = (feed == .following)
        for pk in pubkeySet {
            if let cached = repository.getCachedProfile(pubkey: pk),
               isDisplayProfileResolved(cached, requirePicture: requirePicture) {
                profileMap[pk] = cached
            } else {
                missingPubkeys.append(pk)
            }
        }

        // キャッシュ済みを即時反映（アバター即時表示）
        if !profileMap.isEmpty {
            for post in posts {
                if !isDisplayProfileResolved(post.profile, requirePicture: requirePicture),
                   let p = profileMap[post.event.pubkey] {
                    post.profile = p
                }
                if let rp = post.repostedBy,
                   (!isDisplayProfileResolved(rp, requirePicture: requirePicture) || repostDisplayName(rp).hasSuffix("...")),
                   let p = profileMap[rp.pubkey] {
                    post.repostedBy = p
                }
            }
            if feed == .relay { relayPosts = posts }
            else              { followingPosts = posts }
        }

        // 2. 未取得分のみリレーからフェッチ（Android: missing リストのみ fetch に対応）
        if !missingPubkeys.isEmpty {
            let fetched = await repository.fetchProfiles(pubkeys: Array(missingPubkeys.prefix(24)))
            for p in fetched { profileMap[p.pubkey] = p }
            for post in posts {
                if let p = profileMap[post.event.pubkey] { post.profile = p }
                if let rp = post.repostedBy, let fullProfile = profileMap[rp.pubkey] {
                    post.repostedBy = fullProfile
                }
            }
        }

        // 引用投稿（"q" タグ / nostr:note1...）を解決
        await repository.resolveQuotedPosts(&posts)

        // Reassign to trigger @Observable re-render (class mutation not tracked otherwise).
        if feed == .relay { relayPosts = posts }
        else              { followingPosts = posts }
    }

    // MARK: - Refresh (pull-to-refresh)

    func refreshRelay() async {
        isRelayRefreshing = true
        relayPageCursor = nil
        hasMoreRelayPosts = true
        relayEmptyPageStreak = 0
        let fresh: [ScoredPost]
        if let url = selectedRelayUrl {
            fresh = await repository.fetchGlobalTimelineFromRelay(url)
        } else {
            fresh = await repository.fetchGlobalTimeline()
        }

        // 新着なしで空レスポンスでも既存表示は維持（誤って空画面にしない）
        if !fresh.isEmpty {
            relayPosts = mergeTimeline(relayPosts, with: fresh)
            relayPageCursor = mergedCursor(current: relayPageCursor, incoming: fresh)
        }

        await enrichProfiles(for: .relay)
        isRelayRefreshing = false
    }

    func refreshFollowing() async {
        guard !followList.isEmpty else { isFollowingRefreshing = false; return }
        isFollowingRefreshing = true
        followingPageCursor = nil
        hasMoreFollowingPosts = true
        followingEmptyPageStreak = 0
        let fresh = await repository.fetchFollowingTimeline(authors: followList)

        // 新着なしで空レスポンスでも既存表示は維持（誤って空画面にしない）
        if !fresh.isEmpty {
            followingPosts = mergeTimeline(followingPosts, with: fresh)
            followingPageCursor = mergedCursor(current: followingPageCursor, incoming: fresh)
        }

        await enrichProfiles(for: .following)
        isFollowingRefreshing = false
        await restartFollowingLiveTimeline()
    }

    // MARK: - Infinite Scroll

    func loadMoreRelayIfNeeded() async {
        guard !isRelayLoadingMore, hasMoreRelayPosts else { return }
        let until = relayPageCursor ?? relayPosts.map { $0.event.createdAt }.min().map { $0 - 1 }
        guard let until else { return }
        isRelayLoadingMore = true
        let older: [ScoredPost]
        if let selectedRelayUrl {
            older = await repository.fetchGlobalTimelineFromRelayPage(selectedRelayUrl, before: until)
        } else {
            older = await repository.fetchGlobalTimelinePage(before: until)
        }
        if !older.isEmpty {
            relayPosts = mergeTimeline(relayPosts, with: older)
            relayPageCursor = olderCursor(for: older) ?? relayPageCursor
            relayEmptyPageStreak = 0
        } else {
            relayEmptyPageStreak += 1
        }
        hasMoreRelayPosts = !older.isEmpty || relayEmptyPageStreak < 3
        isRelayLoadingMore = false
        if !older.isEmpty { Task { await enrichProfiles(for: .relay) } }
    }

    func loadMoreFollowingIfNeeded() async {
        guard !followList.isEmpty else { return }
        guard !isFollowingLoadingMore, hasMoreFollowingPosts else { return }
        let until = followingPageCursor ?? followingPosts.map { $0.event.createdAt }.min().map { $0 - 1 }
        guard let until else { return }
        isFollowingLoadingMore = true
        let older = await repository.fetchFollowingTimelinePage(authors: followList, before: until)
        if !older.isEmpty {
            followingPosts = mergeTimeline(followingPosts, with: older)
            followingPageCursor = olderCursor(for: older) ?? followingPageCursor
            followingEmptyPageStreak = 0
        } else {
            followingEmptyPageStreak += 1
        }
        hasMoreFollowingPosts = !older.isEmpty || followingEmptyPageStreak < 3
        isFollowingLoadingMore = false
        if !older.isEmpty { Task { await enrichProfiles(for: .following) } }
    }

    // MARK: - Feed Switch

    func switchFeed(_ feed: FeedType) {
        feedType = feed
        if feed == .relay {
            pendingLivePostsCount = pendingRelayPosts.count
        }
        if feed == .following {
            pendingLivePostsCount = pendingFollowingPosts.count
        }
    }

    // MARK: - Relay Selection

    func selectRelay(_ url: String?) {
        // 同一選択時は再取得しない（UI崩れ・無駄通信防止）
        if selectedRelayUrl == url {
            feedType = .relay
            return
        }

        selectedRelayUrl = url
        feedType = .relay

        // 進行中の切替処理をキャンセルして最新選択のみ反映
        relaySelectionTask?.cancel()

        isRelayRefreshing = true

        relaySelectionTask = Task { [weak self] in
            guard let self else { return }
            if let url = selectedRelayUrl,
               let cached = await repository.getCachedRelayTimeline(url),
               !cached.isEmpty {
                guard !Task.isCancelled else { return }
                relayPosts = cached
                isRelayRefreshing = false
                restartRelayLivePolling()
                return
            }

            let fresh: [ScoredPost]
            if let url = selectedRelayUrl {
                fresh = await repository.fetchGlobalTimelineFromRelay(url, fast: true)
            } else {
                fresh = await repository.fetchGlobalTimeline()
            }
            guard !Task.isCancelled else { return }
            if !fresh.isEmpty || relayPosts.isEmpty { relayPosts = fresh }
            isRelayRefreshing = false
            // リレー変更時にライブポーリングを再起動して、選択リレーのみから新着を受信する
            restartRelayLivePolling()
        }
    }

    // MARK: - Interactions

    func toggleLike(post: ScoredPost) async {
        let eventId = post.event.id
        guard !inFlightLikeEventIds.contains(eventId) else { return }
        inFlightLikeEventIds.insert(eventId)
        defer { inFlightLikeEventIds.remove(eventId) }
        let wasLiked = post.isLiked
        post.isLiked   = !wasLiked
        post.likeCount += wasLiked ? -1 : 1
        triggerUpdate()
        if wasLiked {
            NotificationCenter.default.post(name: .nuruHomeUnlikedPost, object: nil, userInfo: ["eventId": post.event.id])
        } else {
            NotificationCenter.default.post(name: .nuruHomeLikedPost, object: nil, userInfo: ["event": post.event])
        }
        do {
            if wasLiked {
                if let likeId = post.myLikeEventId {
                    try await repository.publishDelete(eventId: likeId)
                    post.myLikeEventId = nil
                } else {
                    throw NSError(domain: "NuruNuru", code: 1, userInfo: [NSLocalizedDescriptionKey: "リアクションイベントが見つかりません"])
                }
            } else {
                let likeEvent = try await repository.publishReaction(to: post.event.id, authorPubkey: post.event.pubkey)
                post.myLikeEventId = likeEvent.id
            }
        } catch {
            post.isLiked   = wasLiked
            post.likeCount += wasLiked ? 1 : -1
            if wasLiked {
                NotificationCenter.default.post(name: .nuruHomeLikedPost, object: nil, userInfo: ["event": post.event])
            } else {
                NotificationCenter.default.post(name: .nuruHomeUnlikedPost, object: nil, userInfo: ["eventId": post.event.id])
            }
            triggerUpdate()
        }
    }

    func toggleRepost(post: ScoredPost) async {
        let eventId = post.event.id
        guard !inFlightRepostEventIds.contains(eventId) else { return }
        inFlightRepostEventIds.insert(eventId)
        defer { inFlightRepostEventIds.remove(eventId) }
        let wasReposted = post.isReposted
        post.isReposted = !wasReposted
        post.repostCount += wasReposted ? -1 : 1
        triggerUpdate()
        do {
            if wasReposted {
                if let repostId = post.myRepostEventId {
                    try await repository.publishDelete(eventId: repostId)
                    post.myRepostEventId = nil
                } else {
                    throw NSError(domain: "NuruNuru", code: 1, userInfo: [NSLocalizedDescriptionKey: "リポストイベントが見つかりません"])
                }
            } else {
                let repostEvent = try await repository.publishRepostAndReturn(event: post.event)
                post.myRepostEventId = repostEvent.id
            }
        } catch {
            post.isReposted = wasReposted
            post.repostCount += wasReposted ? 1 : -1
            triggerUpdate()
        }
    }

    // MARK: - Bookmark (NIP-51, Kind 10003) — mirrors Android toggleBookmark

    func toggleBookmark(post: ScoredPost) async {
        let eventId = post.event.id
        guard !inFlightBookmarkEventIds.contains(eventId) else { return }
        inFlightBookmarkEventIds.insert(eventId)
        defer { inFlightBookmarkEventIds.remove(eventId) }
        let wasBookmarked = post.isBookmarked
        post.isBookmarked = !wasBookmarked
        triggerUpdate()
        do {
            if wasBookmarked {
                try await repository.removeBookmark(pubkeyHex: pubkeyHex, eventId: post.event.id)
            } else {
                try await repository.addBookmark(pubkeyHex: pubkeyHex, eventId: post.event.id)
            }
        } catch {
            post.isBookmarked = wasBookmarked
            triggerUpdate()
        }
    }

    // MARK: - Post Actions

    func deletePost(_ post: ScoredPost) async {
        relayPosts     = relayPosts.filter     { $0.event.id != post.event.id }
        followingPosts = followingPosts.filter { $0.event.id != post.event.id }
        try? await repository.publishDelete(eventId: post.event.id)
    }

    func muteUser(_ pubkeyHex: String) async {
        relayPosts     = relayPosts.filter     { $0.event.pubkey != pubkeyHex }
        followingPosts = followingPosts.filter { $0.event.pubkey != pubkeyHex }
        try? await repository.muteUser(pubkeyHex: pubkeyHex, isPrivate: true)
    }

    func reportEvent(post: ScoredPost, type: String, content: String) async {
        let tags: [[String]] = [["e", post.event.id, type], ["p", post.event.pubkey]]
        try? await repository.publishEvent(kind: NostrKind.report, tags: tags, content: content)
    }

    func submitBirdwatch(post: ScoredPost, type: String, content: String, url: String) async {
        if let signed = try? await repository.publishBirdwatchNote(
            targetEventId: post.event.id,
            content:       content,
            contextType:   type,
            sourceUrl:     url.isEmpty ? nil : url
        ) {
            appendBirdwatchNote(signed, to: post.event.id)
        }
    }

    private func appendBirdwatchNote(_ note: NostrEvent, to eventId: String) {
        for item in relayPosts where item.event.id == eventId {
            if !item.birdwatchNotes.contains(where: { $0.id == note.id }) {
                item.birdwatchNotes.append(note)
            }
        }
        for item in followingPosts where item.event.id == eventId {
            if !item.birdwatchNotes.contains(where: { $0.id == note.id }) {
                item.birdwatchNotes.append(note)
            }
        }
        triggerUpdate()
    }

    // Forces @Observable to re-render views that read the arrays.
    private func triggerUpdate() {
        relayPosts     = relayPosts
        followingPosts = followingPosts
    }

    /// 画像/表示名/名前が未解決の投稿が含まれるかを判定。
    /// 無駄な profile fetch を避けるためのガード。
    private func hasMissingProfiles(in posts: [ScoredPost]) -> Bool {
        let requirePicture = (feedType == .following)
        return posts.contains {
            if !isDisplayProfileResolved($0.profile, requirePicture: requirePicture) { return true }
            if let rp = $0.repostedBy {
                return !isDisplayProfileResolved(rp, requirePicture: requirePicture)
                    || repostDisplayName(rp).hasSuffix("...")
            }
            return false
        }
    }

    private func repostDisplayName(_ profile: UserProfile) -> String {
        profile.displayedName
    }

    private func isDisplayProfileResolved(_ profile: UserProfile?, requirePicture: Bool = false) -> Bool {
        guard let profile else { return false }
        let hasName = profile.displayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            || profile.name?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        let hasPicture = profile.picture?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        return requirePicture ? (hasName && hasPicture) : (hasName || hasPicture)
    }

    // MARK: - Live Streaming

    /// ライブポーリングを開始する。
    ///
    /// 初回データ取得完了後に `loadInitialData()` から呼び出される。
    /// 既存のポーリングタスクがあればキャンセルしてから再起動する。
    /// フォロー・リレー両フィードを同時に購読し、5 秒間隔でポーリングする。
    /// リレーフィードのライブポーリングのみ再起動する（リレー選択変更時）。
    private func restartRelayLivePolling() {
        // 既存のリレーサブスクリプションを停止
        if let sid = relayLiveSubId {
            let oldSid = sid
            relayLiveSubId = nil
            pendingRelayPosts = []
            newRelayPostCount = 0
            hasNewRelayPosts = false
            Task { await repository.stopLiveTimeline(subId: oldSid) }
        }
        // 選択リレーで新しいサブスクリプションを開始
        Task { [weak self] in
            guard let self else { return }
            let newSubId = await repository.startLiveTimeline(authors: [], relayUrl: selectedRelayUrl)
            relayLiveSubId = newSubId
        }
    }

    func startLivePolling() {
        livePollingTask?.cancel()
        livePollingTask = Task { [weak self] in
            guard let self else { return }

            // 両フィードのサブスクリプションを並行開始（リレーフィードは選択リレーを指定）
            async let relayStart:     String? = repository.startLiveTimeline(authors: [], relayUrl: selectedRelayUrl)
            async let followingStart: String? = repository.startLiveTimeline(authors: followList)
            let (relayId, followId) = await (relayStart, followingStart)

            relayLiveSubId     = relayId
            followingLiveSubId = followId

            // ミュート一覧（公開 + 非公開）を先に取得
            let muteResult = await repository.fetchMuteList(pubkeyHex: pubkeyHex)
            let mutedPubkeys = Set(muteResult.publicMutes).union(muteResult.privateMutes)

            // 5 秒間隔ポーリングループ
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                guard !Task.isCancelled else { break }

                // リレーフィードのポーリング
                if let sid = relayLiveSubId {
                    let newEvents = await repository.pollNewPosts(subId: sid)
                    if !newEvents.isEmpty {
                        var existingIds = Set(relayPosts.map(\.event.id) + pendingRelayPosts.map(\.event.id))
                        var unique: [NostrEvent] = []
                        for ev in newEvents where existingIds.insert(ev.id).inserted {
                            guard !mutedPubkeys.contains(ev.pubkey) else { continue }
                            unique.append(ev)
                        }
                        if !unique.isEmpty {
                            // プロフィールを事前取得（Android enrichPostsDirect 同等）
                            let pubkeys = Array(Set(unique.map(\.pubkey)))
                            let profiles = await repository.fetchProfiles(pubkeys: pubkeys)
                            let profileMap = Dictionary(profiles.map { ($0.pubkey, $0) }, uniquingKeysWith: { a, _ in a })
                            let posts = unique.map { ev -> ScoredPost in
                                let p = ScoredPost(event: ev)
                                p.profile = profileMap[ev.pubkey]
                                return p
                            }
                            pendingRelayPosts.append(contentsOf: posts)
                            hasNewRelayPosts    = true
                            newRelayPostCount   = pendingRelayPosts.count
                        }
                    }
                }

                // フォローフィードのポーリング
                if let sid = followingLiveSubId {
                    let newEvents = await repository.pollNewPosts(subId: sid)
                    if !newEvents.isEmpty {
                        var existingIds = Set(followingPosts.map(\.event.id) + pendingFollowingPosts.map(\.event.id))
                        var unique: [NostrEvent] = []
                        for ev in newEvents where existingIds.insert(ev.id).inserted {
                            guard !mutedPubkeys.contains(ev.pubkey) else { continue }
                            unique.append(ev)
                        }
                        if !unique.isEmpty {
                            // プロフィールを事前取得（Android enrichPostsDirect 同等）
                            let pubkeys = Array(Set(unique.map(\.pubkey)))
                            let profiles = await repository.fetchProfiles(pubkeys: pubkeys)
                            let profileMap = Dictionary(profiles.map { ($0.pubkey, $0) }, uniquingKeysWith: { a, _ in a })
                            let posts = unique.map { ev -> ScoredPost in
                                let p = ScoredPost(event: ev)
                                p.profile = profileMap[ev.pubkey]
                                return p
                            }
                            pendingFollowingPosts.append(contentsOf: posts)
                            hasNewFollowingPosts    = true
                            newFollowingPostCount   = pendingFollowingPosts.count
                        }
                    }
                }

                // アクティブフィードのピルカウントを更新
                let active = feedType == .relay ? pendingRelayPosts : pendingFollowingPosts
                pendingLivePostsCount = active.count
            }
        }
    }

    /// ライブポーリングを停止してリソースを解放する。
    ///
    /// 画面離脱時（View の `onDisappear` または `.task` キャンセル時）に呼び出す。
    func stopLivePolling() {
        livePollingTask?.cancel()
        livePollingTask = nil

        // サブスクリプション ID をローカルコピーしてからクリア（Task クロージャ内で使うため）
        let relayId    = relayLiveSubId
        let followId   = followingLiveSubId
        relayLiveSubId     = nil
        followingLiveSubId = nil

        pendingRelayPosts     = []
        pendingFollowingPosts = []
        pendingLivePostsCount = 0
        newRelayPostCount     = 0
        newFollowingPostCount = 0

        Task { [weak self] in
            guard let self else { return }
            if let sid = relayId    { await repository.stopLiveTimeline(subId: sid) }
            if let sid = followId   { await repository.stopLiveTimeline(subId: sid) }
        }
    }

    /// 「新しい投稿」ピルがタップされたとき、バッファ済み投稿を現在のフィードの先頭に挿入する。
    ///
    /// 挿入後、ピルカウントとドット indicator をリセットする。
    /// Android の flushPendingPosts() + reEnrichMissingProfiles() に対応。
    func insertPendingPosts() {
        var inserted: [ScoredPost] = []
        if feedType == .relay {
            let existingIds = Set(relayPosts.map(\.event.id))
            let unique = pendingRelayPosts.filter { !existingIds.contains($0.event.id) }
            inserted = unique
            relayPosts          = unique + relayPosts
            pendingRelayPosts   = []
            hasNewRelayPosts    = false
            newRelayPostCount   = 0
        } else {
            let existingIds = Set(followingPosts.map(\.event.id))
            let unique = pendingFollowingPosts.filter { !existingIds.contains($0.event.id) }
            inserted = unique
            followingPosts          = unique + followingPosts
            pendingFollowingPosts   = []
            hasNewFollowingPosts    = false
            newFollowingPostCount   = 0
        }
        pendingLivePostsCount = 0

        // Android reEnrichMissingProfiles 同等: プロフィール未取得の投稿を再取得
        reEnrichMissingProfiles(inserted)
    }

    /// プロフィールが不完全な投稿のプロフィールをバックグラウンドで再取得する。
    /// Android TimelineViewModel.reEnrichMissingProfiles() に対応。
    private func reEnrichMissingProfiles(_ posts: [ScoredPost]) {
        let missing = posts.filter { p in
            p.profile?.picture == nil && p.profile?.displayName == nil && p.profile?.name == nil
        }
        guard !missing.isEmpty else { return }
        let pubkeys = Array(Set(missing.map(\.event.pubkey)))
        Task {
            let profiles = await repository.fetchProfiles(pubkeys: pubkeys)
            let profileMap = Dictionary(profiles.map { ($0.pubkey, $0) }, uniquingKeysWith: { a, _ in a })
            let resolved = profileMap.filter { (_, v) in
                v.picture != nil || v.displayName != nil || v.name != nil
            }
            guard !resolved.isEmpty else { return }
            // ScoredPost は @Observable class なのでプロパティ変更で自動再描画
            for post in missing {
                if let p = resolved[post.event.pubkey] {
                    post.profile = p
                }
            }
            // 配列の再代入で @Observable の変更を確実に通知
            relayPosts     = relayPosts
            followingPosts = followingPosts
        }
    }
}