import Foundation

extension Notification.Name {
    static let nuruProfileBadgesUpdated = Notification.Name("nuruProfileBadgesUpdated")
}

/// Profiles extension — プロフィール・フォローリスト・バッジ・絵文字セット関連メソッド。
/// Android の NostrRepositoryProfiles.kt に相当。
///
/// 移動済みメソッド:
///   fetchFollowList(pubkey:)
///   fetchProfiles(pubkeys:)
///   fetchProfile(pubkey:)
///   fetchUserNotes(pubkey:limit:)
///   fetchLikedEvents(pubkey:limit:)
///   fetchEmojiSets(pubkeyHex:)
///   fetchBadges(pubkeyHex:)
extension NostrRepository {

    // MARK: - Follow List (Kind 3)

    /// フォローリスト（kind 3）を取得し、フォロー中 pubkey の配列を返す。キャッシュ有効期限 10 分。
    /// Android: NostrRepository.fetchFollowList() に対応。
    func fetchFollowList(pubkey: String) async -> [String] {
        // Stale-while-revalidate: callers that need first paint should never see
        // an empty following tab just because the 10-minute TTL expired.
        if let cached = cache.getCachedFollowList(pubkey: pubkey), !cached.isEmpty { return cached }
        return await refreshFollowList(pubkey: pubkey)
    }

    /// Force a relay refresh of Kind 3. Only overwrites the cache when an actual
    /// contact-list event is found, so transient relay failures cannot poison a
    /// good cached follow list with an empty array.
    func refreshFollowList(pubkey: String, timeoutSeconds: Double = 3.0) async -> [String] {
        let filter = NostrFilter(authors: [pubkey], kinds: [NostrKind.contactList], limit: 1)
        let events = await fetchEvents(filters: [filter], timeoutSeconds: timeoutSeconds)
        guard let latest = events.max(by: { $0.createdAt < $1.createdAt }) else {
            return cache.getCachedFollowList(pubkey: pubkey) ?? []
        }
        let list = latest.tags.filter { $0.first == "p" }.compactMap { $0.dropFirst().first }
        cache.setCachedFollowList(pubkey: pubkey, list: list)
        return list
    }

    // MARK: - Profiles (Kind 0)

    /// 複数 pubkey の kind-0 プロフィールを取得する。キャッシュヒット優先、未取得分のみリレー取得。
    /// Android: NostrRepository.fetchProfiles() に対応。
    func fetchProfiles(pubkeys: [String]) async -> [UserProfile] {
        guard !pubkeys.isEmpty else { return [] }

        // 1. キャッシュ済みプロフィールを返しつつ、表示情報が不完全なものは再取得対象にする
        var result: [UserProfile] = []
        var missing: [String]     = []
        for pk in pubkeys {
            if let cached = cache.getCachedProfile(pk) {
                if isDisplayProfileResolved(cached) {
                    result.append(cached)
                } else {
                    // 不完全キャッシュ（name/displayName/picture が欠損）は再取得
                    missing.append(pk)
                }
            } else {
                missing.append(pk)
            }
        }
        guard !missing.isEmpty else { return result }

        // 一部リレーは limit を全体上限として扱うため、author 数より余裕を持たせる
        let batchLimit = max(missing.count * 3, 300)
        let filter    = NostrFilter(authors: missing, kinds: [NostrKind.metadata], limit: batchLimit)
        let allEvents = await fetchEvents(filters: [filter], timeoutSeconds: 5)
        var latestByPubkey: [String: NostrEvent] = [:]
        for event in allEvents {
            if (latestByPubkey[event.pubkey]?.createdAt ?? 0) < event.createdAt {
                latestByPubkey[event.pubkey] = event
            }
        }
        let decoder = JSONDecoder()
        var fetched = latestByPubkey.values.compactMap { event -> UserProfile? in
            if let data = event.content.data(using: .utf8),
               let c = try? decoder.decode(ProfileContent.self, from: data) {
                let profile = c.toUserProfile(pubkey: event.pubkey)
                let pic = profile.picture?.prefix(40) ?? "nil"
                let ban = profile.banner?.prefix(40) ?? "nil"
                AppLogger.log("Profile", "fetchProfiles decoded pubkey=\(event.pubkey.prefix(12)) picture=\(pic) banner=\(ban)")
                return profile
            }

            // 厳密 decode 失敗時は緩い JSON 抽出で救済
            if let loose = parseLooseProfileContent(event.content, pubkey: event.pubkey) {
                AppLogger.log("Profile", "fetchProfiles loose-decoded pubkey=\(event.pubkey.prefix(12))")
                return loose
            }

            // 壊れた kind0 JSON でも最低限のプロフィールで表示を継続
            AppLogger.log("Profile", "fetchProfiles decode failed pubkey=\(event.pubkey.prefix(12)) — fallback to minimal profile")
            return UserProfile(pubkey: event.pubkey)
        }

        // Do not serially fetch every unresolved pubkey here. Startup has several
        // Timeline/Home/Search enrich callers; the old per-pubkey fallback could turn
        // one batch miss into dozens of Kind-0 requests in the first seconds. Missing
        // profiles keep their cached/minimal display and are refreshed from explicit
        // profile surfaces or later user-driven loads.
        fetched.forEach { cacheProfilePreservingFields($0) }
        return result + fetched
    }

    /// pubkey を指定して単一プロフィールを取得する。
    ///
    /// Startup-safe: multiple callers asking for the same pubkey join one in-flight
    /// Kind-0 request, and a recently refreshed cached profile is reused for a short
    /// window. This prevents Home/Timeline/Profile enrichment from issuing repeated
    /// `fetchProfile Kind 0` requests for the same account during app launch.
    /// Android: NostrRepository.fetchProfile() に対応。
    func fetchProfile(pubkey: String) async -> UserProfile? {
        let normalized = pubkey.lowercased()
        let cached = cache.getCachedProfile(normalized) ?? cache.getCachedProfile(pubkey)
        let now = Date()

        if let cached,
           let last = profileFetchLastAttemptAt[normalized],
           now.timeIntervalSince(last) < 60 {
            AppLogger.log("Profile", "fetchProfile cooldown cache hit — pubkey: \(normalized.prefix(16))…")
            return cached
        }

        if cached == nil,
           let last = profileFetchLastAttemptAt[normalized],
           now.timeIntervalSince(last) < 30 {
            AppLogger.log("Profile", "fetchProfile cooldown miss — pubkey: \(normalized.prefix(16))…")
            return nil
        }

        if let task = profileFetchTasks[normalized] {
            AppLogger.log("Profile", "fetchProfile joined in-flight — pubkey: \(normalized.prefix(16))…")
            return await task.value ?? cached
        }

        profileFetchLastAttemptAt[normalized] = now
        let task = Task { [weak self] () -> UserProfile? in
            guard let self else { return cached }
            return await self.fetchProfileNetworkOnly(pubkey: normalized, cached: cached)
        }
        profileFetchTasks[normalized] = task
        let result = await task.value
        profileFetchTasks[normalized] = nil
        return result ?? cached
    }

    /// Actual relay read for a single Kind-0 profile. Call `fetchProfile(pubkey:)`
    /// from product code so in-flight de-dupe and cooldown are honored.
    private func fetchProfileNetworkOnly(pubkey: String, cached: UserProfile?) async -> UserProfile? {
        // リレーから Kind 0 (user metadata) を直接取得
        // NIP-01: kind 0 = set_metadata (replaceable event)
        let filter = NostrFilter(authors: [pubkey], kinds: [NostrKind.metadata], limit: 5)
        let events = await fetchEvents(filters: [filter], timeoutSeconds: 5)
        AppLogger.log("Profile", "fetchProfile Kind 0 — pubkey: \(pubkey.prefix(16))… events: \(events.count)")

        if let event = events
            .filter({ $0.kind == NostrKind.metadata })
            .max(by: { $0.createdAt < $1.createdAt }) {
            if let data = event.content.data(using: .utf8),
               let c = try? JSONDecoder().decode(ProfileContent.self, from: data) {
                let fetched = c.toUserProfile(pubkey: event.pubkey)
                AppLogger.log("Profile", "fetchProfile decoded — banner=\(fetched.banner ?? "nil"), picture=\(fetched.picture?.prefix(40) ?? "nil")")
                cacheProfilePreservingFields(fetched)
                return fetched
            }

            if let loose = parseLooseProfileContent(event.content, pubkey: event.pubkey) {
                cacheProfilePreservingFields(loose)
                AppLogger.log("Profile", "fetchProfile loose-decoded")
                return loose
            }

            // decode 失敗時も pubkey ベースの最小プロフィールで表示崩れを防ぐ
            let fallback = UserProfile(pubkey: event.pubkey)
            cacheProfilePreservingFields(fallback)
            AppLogger.log("Profile", "fetchProfile decode failed — fallback minimal profile")
            return fallback
        }

        // リレー取得失敗 → キャッシュ fallback
        AppLogger.log("Profile", "fetchProfile relay failed — using cache fallback")
        return cached
    }

    // MARK: - User Notes & Liked Events

    nonisolated func getCachedUserNotes(pubkey: String) -> [NostrEvent] {
        cache.getCachedUserNotes(pubkey: pubkey) ?? []
    }

    nonisolated func getCachedLikedEvents(pubkey: String) -> [NostrEvent] {
        cache.getCachedLikedEvents(pubkey: pubkey) ?? []
    }


    /// ユーザーの kind-1 ノートを取得する（過去 30 日間）。
    /// Android: NostrRepositoryProfiles.fetchUserNotes() に対応。
    func fetchUserNotes(pubkey: String, limit: Int = 50) async -> [NostrEvent] {
        let since = Int64(Date().addingTimeInterval(-86400 * 30).timeIntervalSince1970)
        let filter = NostrFilter(authors: [pubkey], kinds: [NostrKind.textNote], since: since, limit: limit)
        let events = await fetchEvents(filters: [filter], timeoutSeconds: 3.0)
            .filter { $0.kind == NostrKind.textNote }
            .sorted { $0.createdAt > $1.createdAt }
        if !events.isEmpty {
            cache.setCachedUserNotes(pubkey: pubkey, events: events)
            return events
        }
        return cache.getCachedUserNotes(pubkey: pubkey) ?? []
    }

    /// ユーザーがいいねしたイベントを取得する（kind 7 → 対象イベントを逆引き）。
    /// Android: NostrRepositoryProfiles.fetchUserLikes() に対応。
    func fetchLikedEvents(pubkey: String, limit: Int = 30) async -> [NostrEvent] {
        let filter = NostrFilter(authors: [pubkey], kinds: [NostrKind.reaction], limit: limit)
        let reactions = await fetchEvents(filters: [filter], timeoutSeconds: 3.0)
            .filter { $0.kind == NostrKind.reaction && $0.content == "+" }
        let eventIds = Array(reactions.compactMap { $0.getTagValue("e") }.prefix(limit))
        guard !eventIds.isEmpty else { return cache.getCachedLikedEvents(pubkey: pubkey) ?? [] }
        let eventFilter = NostrFilter(ids: eventIds, limit: limit)
        let events = await fetchEvents(filters: [eventFilter], timeoutSeconds: 3.0)
            .filter { $0.kind == NostrKind.textNote }
            .sorted { $0.createdAt > $1.createdAt }
        if !events.isEmpty {
            cache.setCachedLikedEvents(pubkey: pubkey, events: events)
            return events
        }
        return cache.getCachedLikedEvents(pubkey: pubkey) ?? []
    }

    // MARK: - Emoji Sets (NIP-30, Kind 10030 / 30030)

    /// ユーザーの絵文字セットを取得する（kind 10030 参照 → kind 30030 セット）。
    /// Android: NostrRepositoryProfiles.fetchEmojiList() に対応。
    func fetchEmojiSets(pubkeyHex: String) async -> [EmojiSet] {
        AppLogger.log("Emoji", "fetchEmojiSets for \(pubkeyHex.prefix(16))…")
        if let cached = cache.getCachedEmojiSets(pubkey: pubkeyHex), !cached.isEmpty { return cached }

        // kind 10030（ユーザー絵文字リスト）を取得してセット参照を収集
        let listFilter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.emojiList], limit: 1)
        var listEvents = await fetchEvents(filters: [listFilter], timeoutSeconds: 5)

        // リレーで見つからない場合、個別リレーに問い合わせ
        if listEvents.isEmpty {
            let relayUrls = getSavedRelayUrls()
            for url in relayUrls.prefix(3) {
                let relayEvents = await client.fetchEventsFromRelay(url, filters: [listFilter], timeoutSeconds: 4.0)
                if !relayEvents.isEmpty {
                    listEvents = relayEvents
                    AppLogger.log("Emoji", "Found kind-10030 on relay: \(url)")
                    break
                }
            }
        }
        AppLogger.log("Emoji", "kind-10030 events: \(listEvents.count)")

        var setRefs: [String] = [] // "30030:<pubkey>:<d>" 形式の参照
        if let latest = listEvents
            .filter({ $0.kind == NostrKind.emojiList })
            .max(by: { $0.createdAt < $1.createdAt }) {
            // タグ例: ["a", "30030:<pubkey>:<d>"] or ["emoji", "shortcode", "url"]
            setRefs = latest.tags.filter { $0.first == "a" && $0.count >= 2 }.compactMap { $0[safe: 1] }
            AppLogger.log("Emoji", "emoji list 'a' tag refs: \(setRefs)")
        }

        if setRefs.isEmpty {
            // フォールバック: ユーザー自身の 30030 セットのみ取得（author フィルタ付き）
            AppLogger.log("Emoji", "No setRefs, falling back to user's own 30030 sets")
            let setFilter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.emojiSet], limit: 20)
            let setEvents = await fetchEvents(filters: [setFilter], timeoutSeconds: 5)
            AppLogger.log("Emoji", "Fallback kind-30030 events: \(setEvents.count)")
            let sets = setEvents.compactMap { event in
                let dTag = event.tags.first(where: { $0.first == "d" })?.dropFirst().first ?? ""
                let ref = dTag.isEmpty ? event.id : "30030:\(event.pubkey):\(dTag)"
                return parseEmojiSet(event, ref: ref)
            }
            if !sets.isEmpty { cache.setCachedEmojiSets(pubkey: pubkeyHex, sets: sets) }
            return sets
        }

        // "a" タグの参照から author を分解して、参照先の author に限定してフェッチ
        // 形式: "30030:<pubkey>:<d-tag>"
        var authors = Set<String>()
        for ref in setRefs {
            let parts = ref.split(separator: ":", maxSplits: 2)
            if parts.count >= 2 {
                authors.insert(String(parts[1]))
            }
        }

        guard !authors.isEmpty else {
            return []
        }

        let setFilter = NostrFilter(
            authors: Array(authors),
            kinds: [NostrKind.emojiSet],
            limit: setRefs.count + 5
        )
        let setEvents = await fetchEvents(filters: [setFilter], timeoutSeconds: 5)
        AppLogger.log("Emoji", "kind-30030 events from refs: \(setEvents.count)")

        // 参照に含まれるセットのみ返す（関係のないセットを除外）
        let refSet = Set(setRefs)
        let filtered = setEvents.filter { ev in
            let dTag = ev.tags.first(where: { $0.first == "d" })?.dropFirst().first ?? ""
            let ref = "30030:\(ev.pubkey):\(dTag)"
            return refSet.contains(ref)
        }
        AppLogger.log("Emoji", "filtered emoji sets: \(filtered.count)")
        let sets = filtered.compactMap { ev in
            let dTag = ev.tags.first(where: { $0.first == "d" })?.dropFirst().first ?? ""
            let ref = dTag.isEmpty ? ev.id : "30030:\(ev.pubkey):\(dTag)"
            return parseEmojiSet(ev, ref: ref)
        }
        if !sets.isEmpty { cache.setCachedEmojiSets(pubkey: pubkeyHex, sets: sets) }
        return sets
    }

    private func parseEmojiSet(_ event: NostrEvent, ref: String? = nil) -> EmojiSet? {
        let name   = event.tags.first(where: { $0.first == "d" })?.dropFirst().first ?? "セット"
        let emojis = event.tags.filter { $0.first == "emoji" }.compactMap { tag -> CustomEmoji? in
            guard tag.count >= 3 else { return nil }
            return CustomEmoji(shortcode: tag[1], url: tag[2])
        }
        guard !emojis.isEmpty else { return nil }
        let dTag = String(name)
        let normalizedRef = ref ?? (dTag.isEmpty ? event.id : "30030:\(event.pubkey):\(dTag)")
        return EmojiSet(id: normalizedRef, name: String(name), emojis: emojis)
    }



    // MARK: - Emoji Set References Write (Kind 10030)

    /// kind-10030 の a タグ参照に絵文字セットを個別追加する。
    /// 入力: 30030:pubkey:d / nostr:naddr... / URL 内の 30030:...
    func registerEmojiSetReference(pubkeyHex: String, setRefInput: String) async throws {
        let ref = normalizeEmojiSetReference(setRefInput)
        guard ref.hasPrefix("30030:") else {
            throw NSError(domain: "EmojiSet", code: 400, userInfo: [NSLocalizedDescriptionKey: "30030参照を解決できませんでした"])
        }

        var refs = await fetchEmojiSetReferences(pubkeyHex: pubkeyHex)
        if !refs.contains(ref) {
            refs.append(ref)
        }

        let tags = refs.map { ["a", $0] }
        try await publishEvent(kind: NostrKind.emojiList, tags: tags, content: "")
    }

    /// kind-10030 の a タグ参照から絵文字セットを削除する。
    func unregisterEmojiSetReference(pubkeyHex: String, set: EmojiSet) async throws {
        var refs = await fetchEmojiSetReferences(pubkeyHex: pubkeyHex)
        refs.removeAll { $0 == set.id }
        // 互換: 名前(dタグ)による削除も試行
        refs.removeAll { $0.hasSuffix(":" + set.name) }
        let tags = refs.map { ["a", $0] }
        try await publishEvent(kind: NostrKind.emojiList, tags: tags, content: "")
    }

    /// kind-10030 最新イベントから a タグ参照一覧を抽出。
    private func fetchEmojiSetReferences(pubkeyHex: String) async -> [String] {
        let listFilter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.emojiList], limit: 1)
        var listEvents = await fetchEvents(filters: [listFilter], timeoutSeconds: 5)

        if listEvents.isEmpty {
            let relayUrls = getSavedRelayUrls()
            for url in relayUrls.prefix(3) {
                let relayEvents = await client.fetchEventsFromRelay(url, filters: [listFilter], timeoutSeconds: 4.0)
                if !relayEvents.isEmpty {
                    listEvents = relayEvents
                    break
                }
            }
        }

        guard let latest = listEvents
            .filter({ $0.kind == NostrKind.emojiList })
            .max(by: { $0.createdAt < $1.createdAt })
        else { return [] }

        var refs = latest.tags
            .filter { $0.first == "a" && $0.count >= 2 }
            .compactMap { $0[safe: 1] }

        var seen = Set<String>()
        refs = refs.filter { seen.insert($0).inserted }
        return refs
    }

    /// 入力文字列を 30030:pubkey:d 形式に正規化。
    private func normalizeEmojiSetReference(_ rawInput: String) -> String {
        let raw = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)

        if let r = raw.range(of: "30030:") {
            let tail = String(raw[r.lowerBound...])
            return tail.components(separatedBy: CharacterSet(charactersIn: "?&#/")) .first ?? tail
        }

        var stripped = raw
        if stripped.hasPrefix("nostr:") {
            stripped = String(stripped.dropFirst("nostr:".count))
        }

        if stripped.hasPrefix("30030:") { return stripped }
        if stripped.hasPrefix("naddr1"), let decoded = NostrBech32.decodeNaddrToA(stripped) {
            return decoded
        }

        return stripped
    }

    /// kind-10030 の現在値から、個別お気に入り(emojiタグ)を返す。
    func fetchFavoriteEmojis(pubkeyHex: String) async -> [CustomEmoji] {
        if let cached = cache.getCachedFavoriteEmojis(pubkey: pubkeyHex), !cached.isEmpty { return cached }
        let listFilter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.emojiList], limit: 1)
        var listEvents = await fetchEvents(filters: [listFilter], timeoutSeconds: 5)
        if listEvents.isEmpty {
            let relayUrls = getSavedRelayUrls()
            for url in relayUrls.prefix(3) {
                let relayEvents = await client.fetchEventsFromRelay(url, filters: [listFilter], timeoutSeconds: 4.0)
                if !relayEvents.isEmpty { listEvents = relayEvents; break }
            }
        }

        guard let latest = listEvents
            .filter({ $0.kind == NostrKind.emojiList })
            .max(by: { $0.createdAt < $1.createdAt })
        else { return [] }

        var seen = Set<String>()
        let emojis = latest.tags.compactMap { tag -> CustomEmoji? in
            guard tag.count >= 3, tag[0] == "emoji" else { return nil }
            let code = tag[1]
            let url  = tag[2]
            guard seen.insert(code).inserted else { return nil }
            return CustomEmoji(shortcode: code, url: url)
        }
        if !emojis.isEmpty { cache.setCachedFavoriteEmojis(pubkey: pubkeyHex, emojis: emojis) }
        return emojis
    }

    /// Android updateEmojiList(tags) 相当。
    /// favorite 絵文字(emojiタグ) + セット参照(aタグ) をまとめて kind-10030 に保存する。
    func updateEmojiListFavoritesAndSets(
        pubkeyHex: String,
        favorites: [CustomEmoji],
        sets: [EmojiSet]
    ) async throws {
        var tags: [[String]] = []
        favorites.forEach { tags.append(["emoji", $0.shortcode, $0.url]) }
        sets.forEach { tags.append(["a", $0.id]) }
        try await publishEvent(kind: NostrKind.emojiList, tags: tags, content: "")
        cache.setCachedFavoriteEmojis(pubkey: pubkeyHex, emojis: favorites)
        cache.setCachedEmojiSets(pubkey: pubkeyHex, sets: sets)
    }

    // Android 同等: kind-30030 セットを検索して追加候補を返す
    func searchEmojiSets(query: String) async -> [EmojiSet] {
        let filter = NostrFilter(kinds: [NostrKind.emojiSet], limit: 50)
        let events = await fetchEvents(filters: [filter], timeoutSeconds: 5)
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        let mapped: [EmojiSet] = events.compactMap { ev in
            let dTag = ev.tags.first(where: { $0.first == "d" })?.dropFirst().first ?? ""
            let title = ev.tags.first(where: { $0.first == "title" })?.dropFirst().first ?? dTag
            if !q.isEmpty {
                let t = String(title).lowercased()
                let d = String(dTag).lowercased()
                if !t.contains(q) && !d.contains(q) { return nil }
            }

            let emojis = ev.tags.filter { $0.first == "emoji" }.compactMap { tag -> CustomEmoji? in
                guard tag.count >= 3 else { return nil }
                return CustomEmoji(shortcode: tag[1], url: tag[2])
            }
            guard !emojis.isEmpty else { return nil }

            let ref = "30030:\(ev.pubkey):\(dTag)"
            return EmojiSet(id: ref, name: String(title), emojis: Array(emojis.prefix(8)))
        }

        // pointer(ref) 単位で重複排除
        var seen = Set<String>()
        return mapped.filter { seen.insert($0.id).inserted }
    }

    // MARK: - Badges (NIP-58, Kind 30008 / 30009)

    /// バッジを取得する。
    ///   Step 1 — Kind 30008 (PROFILE_BADGES) を pubkeyHex の著者から取得。
    ///   Step 2 — "a" タグ (30009:creator:dTag) からバッジ定義参照を抽出。
    ///   Step 3 — Kind 30009 (BADGE_DEFINITION) を各参照から取得し thumb/image URL を解決。
    /// Android: NostrRepositoryProfiles.fetchBadges() に対応。
    func fetchBadges(pubkeyHex: String) async -> [BadgeItem] {
        AppLogger.log("Badges", "fetchBadges start — pubkey: \(pubkeyHex.prefix(16))…")

        // キャッシュファースト: 名前付き BadgeItem キャッシュから即時返却（Android 同様）
        if let cachedItems = cache.getCachedBadgeItems(pubkey: pubkeyHex) {
            AppLogger.log("Badges", "cache hit (items) — \(cachedItems.count) badges for \(pubkeyHex.prefix(16))…")
            return cachedItems
        }
        // レガシーキャッシュ（URL のみ）は無視 — 名前なしで返すとバグになる

        // Step 1: profile_badges イベント (kind 30008) を取得
        // Web 版は '#d': ['profile_badges'] を追加して replaceable event を絞り込む。
        let profileFilter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.profileBadges], limit: 1, tags: ["#d": ["profile_badges"]])
        let profileEvents = await fetchEvents(filters: [profileFilter], timeoutSeconds: 4)
        AppLogger.log("Badges", "kind-30008 events found: \(profileEvents.count)")

        guard let profileEvent = profileEvents.max(by: { $0.createdAt < $1.createdAt }) else {
            // フォールバック: #d フィルターなしで再試行（一部リレーは replaceable event のタグフィルターに非対応）
            AppLogger.log("Badges", "no kind-30008 event, retrying without #d filter")
            let fallbackFilter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.profileBadges], limit: 1)
            let fallbackEvents = await fetchEvents(filters: [fallbackFilter], timeoutSeconds: 4)
            AppLogger.log("Badges", "fallback kind-30008 events found: \(fallbackEvents.count)")
            guard let fallback = fallbackEvents.max(by: { $0.createdAt < $1.createdAt }) else {
                return []
            }
            let results = await resolveBadgeDefinitions(from: fallback)
            cacheBadgeResults(pubkeyHex: pubkeyHex, badges: results)
            return results
        }

        let results = await resolveBadgeDefinitions(from: profileEvent)
        cacheBadgeResults(pubkeyHex: pubkeyHex, badges: results)
        return results
    }

    private func resolveBadgeDefinitions(from profileEvent: NostrEvent) async -> [BadgeItem] {
        // Step 2: バッジ定義への "a" タグを収集 (30009:creator:dTag)
        let aTags = profileEvent.tags.filter { $0.count >= 2 && $0[0] == "a" && $0[1].hasPrefix("30009:") }
        AppLogger.log("Badges", "'a' tags in profile_badges: \(aTags.count) — \(aTags.map { $0[1] })")

        var results: [BadgeItem] = []
        for tag in aTags {
            if results.count >= 3 { break }
            let ref = tag[1]
            let awardEventId: String? = {
                guard let idx = profileEvent.tags.firstIndex(where: { $0.count >= 2 && $0[0] == "a" && $0[1] == ref }),
                      profileEvent.tags.indices.contains(idx + 1),
                      profileEvent.tags[idx + 1].first == "e" else { return nil }
                return profileEvent.tags[idx + 1][safe: 1]
            }()
            let parts = ref.split(separator: ":", maxSplits: 2).map(String.init)
            guard parts.count == 3 else { continue }
            let creator = parts[1]
            let dTag    = parts[2]

            // Step 3: バッジ定義 (kind 30009) を creator から取得
            let defFilter = NostrFilter(authors: [creator], kinds: [NostrKind.badgeDefinition], limit: 1, tags: ["#d": [dTag]])
            let defEvents = await fetchEvents(filters: [defFilter], timeoutSeconds: 3)
            AppLogger.log("Badges", "kind-30009 for \(dTag): \(defEvents.count) event(s)")

            guard let def = defEvents.max(by: { $0.createdAt < $1.createdAt }) else {
                // このリレーに定義がない場合: 名前のみのプレースホルダーを追加
                results.append(BadgeItem(id: ref, name: dTag, description: nil, imageUrl: nil, awardEventId: awardEventId))
                continue
            }

            func tagValue(_ key: String) -> String? {
                def.tags.first(where: { $0.first == key })?.dropFirst().first.map { String($0) }
            }
            let name = tagValue("name") ?? dTag
            let url  = tagValue("thumb") ?? tagValue("image")
            AppLogger.log("Badges", "badge '\(name)' image: \(url ?? "none")")
            results.append(BadgeItem(id: ref, name: name, description: tagValue("description"), imageUrl: url, awardEventId: awardEventId))
        }
        AppLogger.log("Badges", "total badges resolved: \(results.count)")
        return results
    }

    /// バッジ結果を永続キャッシュに保存する。
    private func cacheBadgeResults(pubkeyHex: String, badges: [BadgeItem]) {
        let urls = badges.compactMap(\.imageUrl)
        cache.setCachedBadges(pubkey: pubkeyHex, urls: urls)
        cache.setCachedBadgeItems(pubkey: pubkeyHex, items: badges)
        clearBadgeCache(pubkey: pubkeyHex)
    }

    // MARK: - Awarded Badges (Kind 8)

    /// ユーザーに授与された全バッジ（Kind 8）を取得してバッジ定義を解決する。
    /// プロフィールバッジ（Kind 30008）に設定されていないものも含む。
    /// Android: BadgeSettings.kt の受け取ったバッジ一覧に対応。
    func fetchAwardedBadges(pubkeyHex: String) async -> [BadgeItem] {
        AppLogger.log("Badges", "fetchAwardedBadges start — pubkey: \(pubkeyHex.prefix(16))…")

        // Kind 8 (badge award) で自分宛のものを取得
        let filter = NostrFilter(
            kinds: [NostrKind.badgeAward],
            limit: 50,
            tags: ["#p": [pubkeyHex]]
        )
        let awardEvents = await fetchEvents(filters: [filter], timeoutSeconds: 5)
        AppLogger.log("Badges", "kind-8 award events: \(awardEvents.count)")

        // 各 award イベントから "a" タグ (30009:creator:dTag) を収集
        var seenRefs = Set<String>()
        var results: [BadgeItem] = []

        for event in awardEvents {
            let aTags = event.tags.filter { $0.count >= 2 && $0[0] == "a" && $0[1].hasPrefix("30009:") }
            for tag in aTags {
                let ref = tag[1]
                guard seenRefs.insert(ref).inserted else { continue }
                let parts = ref.split(separator: ":", maxSplits: 2).map(String.init)
                guard parts.count == 3 else { continue }
                let creator = parts[1]
                let dTag    = parts[2]

                // バッジ定義 (kind 30009) を取得
                let defFilter = NostrFilter(authors: [creator], kinds: [NostrKind.badgeDefinition], limit: 1, tags: ["#d": [dTag]])
                let defEvents = await fetchEvents(filters: [defFilter], timeoutSeconds: 3)

                guard let def = defEvents.max(by: { $0.createdAt < $1.createdAt }) else {
                    results.append(BadgeItem(id: ref, name: dTag, description: nil, imageUrl: nil, awardEventId: event.id))
                    continue
                }

                func tagValue(_ key: String) -> String? {
                    def.tags.first(where: { $0.first == key })?.dropFirst().first.map { String($0) }
                }
                let name = tagValue("name") ?? dTag
                let url  = tagValue("thumb") ?? tagValue("image")
                results.append(BadgeItem(id: ref, name: name, description: tagValue("description"), imageUrl: url, awardEventId: event.id))
            }
        }

        AppLogger.log("Badges", "total awarded badges resolved: \(results.count)")
        return results
    }

    /// プロフィールバッジ（Kind 30008）を更新・公開する。
    /// 選択したバッジの "a" タグリストで replaceable event を発行。
    /// Android: BadgeSettings.kt のバッジ入れ替え機能に対応。
    func publishProfileBadges(badges: [BadgeItem]) async throws {
        // NIP-58 profile_badges (kind 30008): replaceable event with
        // ["d", "profile_badges"], and pairs of ["a", badge-definition]
        // + ["e", award-event-id] when known.
        var seen = Set<String>()
        let selected = badges.prefix(3).filter { seen.insert($0.id).inserted }
        var tags: [[String]] = [["d", "profile_badges"]]
        for badge in selected {
            tags.append(["a", badge.id])
            if let awardEventId = badge.awardEventId, !awardEventId.isEmpty {
                tags.append(["e", awardEventId])
            }
        }
        try await publishEvent(kind: NostrKind.profileBadges, tags: tags, content: "")

        let updated = Array(selected)
        cacheBadgeResults(pubkeyHex: prefs.publicKeyHex ?? "", badges: updated)
        NotificationCenter.default.post(
            name: .nuruProfileBadgesUpdated,
            object: nil,
            userInfo: ["pubkey": prefs.publicKeyHex ?? "", "badges": updated]
        )
        AppLogger.log("Badges", "published kind-30008 profile_badges with \(updated.count) badges")
    }

    // MARK: - NIP-05 Resolution

    /// NIP-05 解決キャッシュ (5分 TTL)。
    /// Android: `Nip05Utils.verifyCache` (ConcurrentHashMap<String, Pair<Boolean, Long>>) に対応。
    /// actor-isolated なので排他制御は不要。
    private static var nip05Cache: [String: (pubkey: String, expiry: Int64)] = [:]

    /// NIP-05 識別子 (name@domain or domain) を解決して pubkey hex を返す。
    /// 5分間のキャッシュ付き。
    /// Android: Nip05Utils.resolveNip05() に対応。
    func resolveNip05(_ identifier: String) async -> String? {
        let lowered = identifier.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // キャッシュヒット (5分 TTL = CacheDuration.nip05)
        if let cached = Self.nip05Cache[lowered], now < cached.expiry {
            return cached.pubkey
        }

        // Normalize: domain-only → _@domain
        let normalized = lowered.contains("@") ? lowered : "_@\(lowered)"
        let parts = normalized.split(separator: "@", maxSplits: 1)
        guard parts.count == 2 else { return nil }

        let name   = String(parts[0])
        let domain = String(parts[1])
        let urlString = "https://\(domain)/.well-known/nostr.json?name=\(name)"
        guard let url = URL(string: urlString) else { return nil }

        do {
            var request = URLRequest(url: url)
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue("NuruNuru-iOS/1.0", forHTTPHeaderField: "User-Agent")
            request.timeoutInterval = 5

            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  httpResponse.statusCode == 200 else { return nil }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let names = json["names"] as? [String: String],
                  let pubkey = names[name] else { return nil }

            // キャッシュに保存 (5分 TTL)
            Self.nip05Cache[lowered] = (pubkey: pubkey, expiry: now + Int64(CacheDuration.nip05))
            return pubkey
        } catch {
            AppLogger.log("NIP05", "Resolution failed for \(identifier): \(error.localizedDescription)")
            return nil
        }
    }

    // MARK: - Profile Search (NIP-50 Kind 0)

    /// NIP-50 を使ってプロフィール (kind 0) を検索する。
    /// Android: searchnos を kind:0 で検索する機能に対応。
    func searchProfiles(query: String, limit: Int = 20) async -> [UserProfile] {
        // 検索リレーへの持続接続を確保
        await ensureSearchRelayConnected()

        let filter = NostrFilter(
            kinds:  [NostrKind.metadata],
            limit:  limit,
            search: query
        )
        let events = await client.fetchEventsFromRelay(
            searchRelayUrl,
            filters: [filter],
            timeoutSeconds: 10.0
        )
        .filter { $0.kind == NostrKind.metadata }
        .sorted { $0.createdAt > $1.createdAt }

        let decoder = JSONDecoder()
        // Deduplicate by pubkey, keeping most recent
        var latestByPubkey: [String: NostrEvent] = [:]
        for event in events {
            if (latestByPubkey[event.pubkey]?.createdAt ?? 0) < event.createdAt {
                latestByPubkey[event.pubkey] = event
            }
        }
        let profiles = latestByPubkey.values.compactMap { event -> UserProfile? in
            guard let data = event.content.data(using: .utf8),
                  let c = try? decoder.decode(ProfileContent.self, from: data) else { return nil }
            return c.toUserProfile(pubkey: event.pubkey)
        }
        // Cache the fetched profiles
        profiles.forEach { cache.setCachedProfile($0.pubkey, $0) }
        return profiles
    }

    // MARK: - Profile Cache Helper

    /// プロフィールをキャッシュに保存する際、nil フィールドを既存キャッシュの値で補完する。
    /// FFI やリレーがバナー/website を返さない場合にキャッシュ内の既存値を失わない。
    private func cacheProfilePreservingFields(_ profile: UserProfile) {
        let cached = cache.getCachedProfile(profile.pubkey)
        let merged = UserProfile(
            pubkey:      profile.pubkey,
            name:        profile.name        ?? cached?.name,
            displayName: profile.displayName ?? cached?.displayName,
            about:       profile.about       ?? cached?.about,
            picture:     profile.picture     ?? cached?.picture,
            nip05:       profile.nip05       ?? cached?.nip05,
            banner:      profile.banner      ?? cached?.banner,
            lud16:       profile.lud16       ?? cached?.lud16,
            website:     profile.website     ?? cached?.website,
            birthday:    profile.birthday    ?? cached?.birthday,
            geohash:     profile.geohash     ?? cached?.geohash
        )
        cache.setCachedProfile(profile.pubkey, merged)
    }

    /// タイムライン表示に必要な最小プロフィール（name/displayName/picture のいずれか）を満たすか。
    private func isDisplayProfileResolved(_ profile: UserProfile) -> Bool {
        let hasName = profile.displayName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            || profile.name?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        let hasPicture = profile.picture?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        return hasName || hasPicture
    }

    /// 緩い kind0 JSON 抽出（型ゆらぎ・余分キー・部分壊れ対策）
    private func parseLooseProfileContent(_ raw: String, pubkey: String) -> UserProfile? {
        guard let data = raw.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }

        func pick(_ keys: [String]) -> String? {
            for k in keys {
                if let v = obj[k] as? String {
                    let t = v.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !t.isEmpty { return t }
                }
            }
            return nil
        }

        let profile = UserProfile(
            pubkey: pubkey,
            name: pick(["name"]),
            displayName: pick(["display_name", "displayName"]),
            about: pick(["about"]),
            picture: pick(["picture", "image", "avatar", "icon"]),
            nip05: pick(["nip05"]),
            banner: pick(["banner"]),
            lud16: pick(["lud16"]),
            website: pick(["website"]),
            birthday: pick(["birthday", "birthdate", "birth"]),
            geohash: pick(["geohash"])
        )

        return isDisplayProfileResolved(profile) ? profile : nil
    }
}

// MARK: - Profile Content (kind-0 JSON — content フィールドのデシリアライズ用)

private struct ProfileContent: Decodable {
    var name:        String?
    var displayName: String?
    var about:       String?
    var picture:     String?
    var nip05:       String?
    var banner:      String?
    var lud16:       String?
    var website:     String?
    var birthday:    String?
    var geohash:     String?

    private func normalized(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func toUserProfile(pubkey: String) -> UserProfile {
        UserProfile(
            pubkey: pubkey,
            name: normalized(name),
            displayName: normalized(displayName),
            about: normalized(about),
            picture: normalized(picture),
            nip05: normalized(nip05),
            banner: normalized(banner),
            lud16: normalized(lud16),
            website: normalized(website),
            birthday: normalized(birthday),
            geohash: normalized(geohash)
        )
    }

    private struct BirthdayObject: Decodable {
        let month: Int?
        let day:   Int?
        let year:  Int?
    }

    enum CodingKeys: String, CodingKey {
        case name, about, picture, nip05, banner, lud16, website, birthday, geohash
        case displayName = "display_name"
        // Compatibility aliases seen in some clients
        case image, avatar, icon
        // Birthday aliases
        case birthdate, birth
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName)
        about = try c.decodeIfPresent(String.self, forKey: .about)

        let p1 = try c.decodeIfPresent(String.self, forKey: .picture)
        let p2 = try c.decodeIfPresent(String.self, forKey: .image)
        let p3 = try c.decodeIfPresent(String.self, forKey: .avatar)
        let p4 = try c.decodeIfPresent(String.self, forKey: .icon)
        picture = p1 ?? p2 ?? p3 ?? p4

        nip05 = try c.decodeIfPresent(String.self, forKey: .nip05)
        banner = try c.decodeIfPresent(String.self, forKey: .banner)
        lud16 = try c.decodeIfPresent(String.self, forKey: .lud16)
        website = try c.decodeIfPresent(String.self, forKey: .website)

        func normalizeBirthdayObject(_ obj: BirthdayObject?) -> String? {
            guard let obj,
                  let m = obj.month,
                  let d = obj.day,
                  (1...12).contains(m),
                  (1...31).contains(d) else { return nil }
            if let y = obj.year, y > 0 {
                return String(format: "%04d-%02d-%02d", y, m, d)
            }
            return String(format: "%02d-%02d", m, d)
        }

        let b1 = try c.decodeIfPresent(String.self, forKey: .birthday)
        let b2 = try c.decodeIfPresent(String.self, forKey: .birthdate)
        let b3 = try c.decodeIfPresent(String.self, forKey: .birth)

        let bo1 = try c.decodeIfPresent(BirthdayObject.self, forKey: .birthday)
        let bo2 = try c.decodeIfPresent(BirthdayObject.self, forKey: .birthdate)
        let bo3 = try c.decodeIfPresent(BirthdayObject.self, forKey: .birth)

        birthday = b1 ?? b2 ?? b3 ?? normalizeBirthdayObject(bo1) ?? normalizeBirthdayObject(bo2) ?? normalizeBirthdayObject(bo3)
        geohash = try c.decodeIfPresent(String.self, forKey: .geohash)
    }
}


extension NostrRepository {
    func prefetchProfilesAndBadges(pubkeys: [String], limit: Int = 32) async {
        // Startup-friendly profile warmup. Badges are intentionally cache-only here:
        // fetching NIP-58 for many authors during launch created a large request storm.
        let unique = Array(NSOrderedSet(array: pubkeys).compactMap { $0 as? String })
        let targets = unique.filter { pk in
            guard let cached = cache.getCachedProfile(pk) else { return true }
            return !isDisplayProfileResolved(cached)
        }.prefix(limit)
        guard !targets.isEmpty else { return }
        _ = await fetchProfiles(pubkeys: Array(targets))
    }
}
