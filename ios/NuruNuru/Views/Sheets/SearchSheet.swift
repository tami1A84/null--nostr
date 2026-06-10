import SwiftUI

/// Full-screen search sheet — mirrors Android SearchModal.kt.
struct SearchSheet: View {

    let repository:   NostrRepository
    let myPubkeyHex:  String
    var onProfileTap: (String) -> Void = { _ in }
    var initialQuery: String = ""
    var onDismiss:    () -> Void       = {}

    @Environment(\.nuruTheme) private var theme
    @State private var query:           String         = ""
    @State private var results:         [ScoredPost]   = []
    @State private var isSearching:     Bool           = false
    @State private var recentSearches:  [String]       = []
    @State private var hasSearched:     Bool           = false

    @FocusState private var focused: Bool

    init(
        repository: NostrRepository,
        myPubkeyHex: String,
        onProfileTap: @escaping (String) -> Void = { _ in },
        initialQuery: String = "",
        onDismiss: @escaping () -> Void = {}
    ) {
        self.repository = repository
        self.myPubkeyHex = myPubkeyHex
        self.onProfileTap = onProfileTap
        self.initialQuery = initialQuery
        self.onDismiss = onDismiss
        _query = State(initialValue: initialQuery)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Search bar + close
            HStack(spacing: NuruSpacing.space2) {
                HStack(spacing: NuruSpacing.space2) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(theme.textTertiary)
                    TextField("検索", text: $query)
                        .font(NuruFont.bodyMedium())
                        .foregroundStyle(theme.textPrimary)
                        .focused($focused)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onSubmit { Task { await doSearch() } }
                    if !query.isEmpty {
                        Button { query = "" } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(theme.textTertiary)
                        }
                    }
                }
                .padding(.horizontal, NuruSpacing.space3)
                .padding(.vertical, NuruSpacing.space2)
                .background(theme.bgSecondary)
                .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusMd))

                Button("キャンセル", action: onDismiss)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textSecondary)
            }
            .padding(.horizontal, NuruSpacing.space4)
            .padding(.vertical, NuruSpacing.space3)
            .background(theme.bgPrimary)

            Divider().background(theme.borderColor)

            if isSearching {
                Spacer()
                ProgressView().tint(NuruColors.lineGreen)
                Spacer()
            } else if !hasSearched {
                // Pre-search state: hints + recent searches
                ScrollView {
                    VStack(alignment: .leading, spacing: NuruSpacing.space4) {
                        // Search operators hint
                        operatorsHint

                        // Recent searches
                        if !recentSearches.isEmpty {
                            recentSearchesSection
                        }
                    }
                    .padding(NuruSpacing.space4)
                }
            } else if results.isEmpty {
                Spacer()
                VStack(spacing: NuruSpacing.space3) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 36))
                        .foregroundStyle(theme.textTertiary)
                    Text("「\(query)」の検索結果はありません")
                        .font(NuruFont.bodyMedium())
                        .foregroundStyle(theme.textTertiary)
                }
                Spacer()
            } else {
                // Search results are post-only for speed. Profile search is intentionally
                // not executed here; tap usernames from posts to open profiles.
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Text("\(results.count)件の結果")
                            .font(NuruFont.labelSmall())
                            .foregroundStyle(theme.textTertiary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, NuruSpacing.space4)
                            .padding(.vertical, NuruSpacing.space2)
                        ForEach(results, id: \.id) { post in
                            PostRow(
                                post:         post,
                                repository:   repository,
                                myPubkeyHex:  myPubkeyHex,
                                onLike: {
                                    try? await repository.publishReaction(
                                        to: post.event.id,
                                        authorPubkey: post.event.pubkey
                                    )
                                    post.isLiked = true
                                    post.likeCount += 1
                                },
                                onRepost: {
                                    try? await repository.publishRepost(event: post.event)
                                    post.isReposted = true
                                    post.repostCount += 1
                                },
                                onProfileTap: onProfileTap,
                                onHashtagTap: { tag in
                                    let q = "#\(tag)"
                                    query = q
                                    Task { await doSearch(q) }
                                }
                            )
                        }
                    }
                }
            }
        }
        .background(theme.bgPrimary.ignoresSafeArea())
        .onAppear {
            focused         = true
            recentSearches  = loadRecentSearches()
            applyInitialQueryIfNeeded(initialQuery)
        }
        .onChange(of: initialQuery) { _, newValue in
            applyInitialQueryIfNeeded(newValue)
        }
    }

    private func applyInitialQueryIfNeeded(_ value: String) {
        let q = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        AppLogger.log("Search", "applyInitialQuery q=\(q)")
        if query != q {
            query = q
        }
        // Do not rely on @State query being synchronously updated here.
        // On the first hashtag tap SwiftUI may still expose the previous
        // value (usually empty) to doSearch(), causing the first tap to open
        // only the pre-search screen. Pass the tag query explicitly.
        Task { await doSearch(q) }
    }

    // MARK: - Operators Hint

    private var operatorsHint: some View {
        VStack(alignment: .leading, spacing: NuruSpacing.space3) {
            HStack(spacing: NuruSpacing.space2) {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(NuruColors.lineGreen)
                VStack(alignment: .leading, spacing: 2) {
                    Text("高度な検索")
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(theme.textPrimary)
                    Text("昔のTwitterのように、演算子を組み合わせて絞り込めます")
                        .font(NuruFont.bodySmall())
                        .foregroundStyle(theme.textTertiary)
                }
                Spacer()
            }

            operatorSection(
                title: "キーワード",
                items: [
                    ("#タグ", "ハッシュタグ", "#"),
                    ("\"完全一致\"", "フレーズ一致", "\"キーワード\""),
                    ("-除外", "含めない語", "-除外したい語")
                ]
            )

            operatorSection(
                title: "アカウント・日付",
                items: [
                    ("from:", "ユーザー指定", "from:npub_or_nip05"),
                    ("since:", "この日付以降", "since:\(dateToken(daysAgo: 7))"),
                    ("until:", "この日付以前", "until:\(dateToken(daysAgo: 0))")
                ]
            )

            operatorSection(
                title: "メディア・種類",
                items: [
                    ("画像", "画像つき", "filter:image"),
                    ("動画", "動画つき", "filter:video"),
                    ("リンク", "URLつき", "filter:link"),
                    ("kind:1", "通常投稿", "kind:1")
                ]
            )

            HStack(spacing: NuruSpacing.space2) {
                Image(systemName: "sparkles")
                    .font(.system(size: 12))
                    .foregroundStyle(theme.textTertiary)
                Text("例: from:user@example.com #nostr since:\(dateToken(daysAgo: 30)) filter:image")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textTertiary)
                    .lineLimit(2)
            }
            .padding(.top, NuruSpacing.space1)
        }
        .padding(NuruSpacing.space3)
        .background(
            RoundedRectangle(cornerRadius: NuruSpacing.radiusLg)
                .fill(theme.bgSecondary)
                .overlay(
                    RoundedRectangle(cornerRadius: NuruSpacing.radiusLg)
                        .stroke(NuruColors.lineGreen.opacity(0.18), lineWidth: 1)
                )
        )
    }

    private func operatorSection(
        title: String,
        items: [(label: String, description: String, insertion: String)]
    ) -> some View {
        VStack(alignment: .leading, spacing: NuruSpacing.space2) {
            Text(title)
                .font(NuruFont.labelSmall())
                .foregroundStyle(theme.textTertiary)

            FlowLayout(spacing: NuruSpacing.space2, rowSpacing: NuruSpacing.space2) {
                ForEach(items, id: \.insertion) { item in
                    Button {
                        insertSearchOperator(item.insertion)
                    } label: {
                        HStack(spacing: 6) {
                            Text(item.label)
                                .font(NuruFont.bodySmall())
                                .foregroundStyle(NuruColors.lineGreen)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(NuruColors.lineGreen.opacity(0.12))
                                .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusSm))
                            Text(item.description)
                                .font(NuruFont.bodySmall())
                                .foregroundStyle(theme.textSecondary)
                        }
                        .padding(.horizontal, NuruSpacing.space2)
                        .padding(.vertical, NuruSpacing.space2)
                        .background(theme.bgPrimary.opacity(0.65))
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("検索演算子 \(item.label) を追加")
                }
            }
        }
    }

    private func insertSearchOperator(_ token: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            query = token
        } else if !trimmed.components(separatedBy: .whitespacesAndNewlines).contains(token) {
            query = trimmed + " " + token
        }
        hasSearched = false
        focused = true
    }

    private func dateToken(daysAgo: Int) -> String {
        let date = Calendar.current.date(byAdding: .day, value: -daysAgo, to: Date()) ?? Date()
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    // MARK: - Recent Searches

    private var recentSearchesSection: some View {
        VStack(alignment: .leading, spacing: NuruSpacing.space2) {
            HStack {
                Text("最近の検索")
                    .font(NuruFont.labelSmall())
                    .foregroundStyle(theme.textTertiary)
                Spacer()
                Button("クリア") {
                    recentSearches = []
                    saveRecentSearches([])
                }
                .font(NuruFont.labelSmall())
                .foregroundStyle(theme.textTertiary)
            }
            ForEach(recentSearches, id: \.self) { recent in
                HStack(spacing: NuruSpacing.space2) {
                    Button {
                        query = recent
                        Task { await doSearch() }
                    } label: {
                        HStack {
                            Image(systemName: "clock")
                                .font(.system(size: 14))
                                .foregroundStyle(theme.textTertiary)
                            Text(recent)
                                .font(NuruFont.bodyMedium())
                                .foregroundStyle(theme.textPrimary)
                            Spacer()
                            Image(systemName: "arrow.up.left")
                                .font(.system(size: 12))
                                .foregroundStyle(theme.textTertiary)
                        }
                        .padding(.vertical, NuruSpacing.space2)
                    }
                    .buttonStyle(.plain)

                    Button {
                        deleteRecentSearch(recent)
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundStyle(theme.textTertiary)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("検索履歴「\(recent)」を削除")
                }
                Divider().background(theme.borderColor)
            }
        }
    }

    // MARK: - Search

    private func doSearch(_ explicitQuery: String? = nil) async {
        let q = (explicitQuery ?? query).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        isSearching = true
        hasSearched = true

        let parsed  = SearchQueryParser.parse(query: q)
        AppLogger.log("Search", "doSearch q=\(q) text=\(parsed.text) hashtags=\(parsed.hashtags.joined(separator: ","))")

        let events = await performSearch(parsed: parsed, rawQuery: q)
        let scored  = events.map { ScoredPost(event: $0) }

        // Cache-first profiles for instant result rendering; fresh profiles fill gaps.
        let pubkeys  = Array(Set(events.map { $0.pubkey }))
        var profileMap: [String: UserProfile] = Dictionary(
            uniqueKeysWithValues: pubkeys.compactMap { pk in
                repository.getCachedProfile(pubkey: pk).map { (pk, $0) }
            }
        )
        let missing = pubkeys.filter { profileMap[$0] == nil }
        if !missing.isEmpty {
            let profiles = await repository.fetchProfiles(pubkeys: missing)
            for p in profiles { profileMap[p.pubkey] = p }
        }
        scored.forEach { $0.profile = profileMap[$0.event.pubkey] }

        // Client-side filters from ParsedQuery
        var filtered = scored.map { $0 }

        // Exclude terms
        if !parsed.excludeTerms.isEmpty {
            filtered = filtered.filter { post in
                let content = post.event.content.lowercased()
                return parsed.excludeTerms.allSatisfy { !content.contains($0.lowercased()) }
            }
        }

        // Exact phrases
        if !parsed.exactPhrases.isEmpty {
            filtered = filtered.filter { post in
                let content = post.event.content.lowercased()
                return parsed.exactPhrases.allSatisfy { content.contains($0.lowercased()) }
            }
        }

        // Media filters (client-side URL pattern matching)
        for mediaFilter in parsed.filters {
            filtered = filtered.filter { post in
                let content = post.event.content.lowercased()
                switch mediaFilter {
                case .image:
                    return content.contains(".jpg") || content.contains(".jpeg") ||
                           content.contains(".png") || content.contains(".gif") ||
                           content.contains(".webp")
                case .video:
                    return content.contains(".mp4") || content.contains(".mov") ||
                           content.contains(".webm")
                case .link:
                    return content.contains("https://") || content.contains("http://")
                }
            }
        }

        results     = filtered
        isSearching = false
        AppLogger.log("Search", "doSearch complete q=\(q) events=\(events.count) results=\(filtered.count) profiles=\(profileMap.count)")

        // Save to recent
        var recent = recentSearches.filter { $0 != q }
        recent.insert(q, at: 0)
        if recent.count > 10 { recent = Array(recent.prefix(10)) }
        recentSearches = recent
        saveRecentSearches(recent)
    }

    /// Build filter and fetch events based on ParsedQuery operators.
    private func performSearch(parsed: ParsedQuery, rawQuery: String) async -> [NostrEvent] {
        let limit = 50

        // Build since/until timestamps
        let sinceTimestamp: Int64? = parsed.sinceDate.map { Int64($0.timeIntervalSince1970) }
        // until:YYYY-MM-DD はその日の終端まで含める（Twitter風の「この日付以前」）
        let untilTimestamp: Int64? = parsed.untilDate.map { Int64($0.timeIntervalSince1970) + 86_399 }

        // Determine kind(s) — default to text note unless overridden
        let kinds: [Int] = parsed.kind.map { [$0] } ?? [NostrKind.textNote]

        // Build tag filter for hashtags (relay-side #t).
        // Important: a query that is only a hashtag must NOT be converted to a
        // NIP-50 search request. On a cold first tap that path waits for the
        // search relay and can return before the sheet has a result list; the
        // second tap then appears to work because the search relay is already
        // connected. Android routes "text absent + #tag" to a normal relay REQ
        // with #t, so mirror that here.
        var tagFilter: [String: [String]]? = nil
        if !parsed.hashtags.isEmpty {
            tagFilter = ["#t": parsed.hashtags]
        }

        // NIP-50 search is used only when there is real free-text. Hashtags are
        // still included as #t filters, not as the search text for tag-only taps.
        let searchString = parsed.text.isEmpty ? nil : parsed.text

        // Resolve authors from fromUser — npub/hex direct + NIP-05 async resolution
        var authors: [String]? = nil
        if !parsed.fromTargets.isEmpty {
            var resolvedAuthors: [String] = []
            for target in parsed.fromTargets {
                if target.hasPrefix("npub") {
                    // bech32 → hex conversion
                    if let pubBytes = NostrKeyUtils.parsePublicKey(target) {
                        resolvedAuthors.append(NostrKeyUtils.bytesToHex(pubBytes))
                    }
                } else if target.count == 64 && target.allSatisfy({ $0.isHexDigit }) {
                    resolvedAuthors.append(target)
                } else if target.contains("@") || target.contains(".") {
                    // NIP-05 resolution
                    if let hex = await repository.resolveNip05(target) {
                        resolvedAuthors.append(hex)
                    }
                }
            }
            if !resolvedAuthors.isEmpty { authors = resolvedAuthors }
        }

        var filter = NostrFilter(
            authors: authors,
            kinds:   kinds,
            since:   sinceTimestamp,
            until:   untilTimestamp,
            limit:   limit,
            tags:    tagFilter,
            search:  searchString
        )

        // If nothing was parsed at all, fall back to raw query search
        if filter.search == nil && !parsed.hasOperators {
            filter.search = rawQuery
        }

        AppLogger.log(
            "Search",
            "performSearch text=\(parsed.text) hashtags=\(parsed.hashtags.joined(separator: ",")) search=\(filter.search ?? "nil") tags=\(tagFilter?.keys.sorted().joined(separator: ",") ?? "nil")"
        )

        // Route NIP-50 search queries to the dedicated search relay with the full structured
        // filter intact. The old path called searchEvents(query:) and accidentally dropped
        // from:/since:/until:/#tag/kind constraints, so chips appeared to do nothing.
        let events: [NostrEvent]
        if filter.search != nil {
            await repository.ensureSearchRelayConnected()
            events = await repository.fetchEventsFromRelay(
                await repository.searchRelayUrl,
                filters: [filter],
                timeoutSeconds: 10.0
            )
        } else {
            events = await repository.fetchEvents(filters: [filter])
        }

        return events
            .filter { kinds.contains($0.kind) }
            .sorted { $0.createdAt > $1.createdAt }
    }

    // MARK: - Persistence

    private func loadRecentSearches() -> [String] {
        UserDefaults.standard.stringArray(forKey: "nurunuru_recent_searches") ?? []
    }

    private func saveRecentSearches(_ list: [String]) {
        UserDefaults.standard.set(list, forKey: "nurunuru_recent_searches")
    }

    private func deleteRecentSearch(_ item: String) {
        let updated = recentSearches.filter { $0 != item }
        recentSearches = updated
        saveRecentSearches(updated)
    }
}

// MARK: - Flow Layout

/// Lightweight wrapping layout for modern search-operator chips.
private struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var rowSpacing: CGFloat = 8

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var widest: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > maxWidth {
                widest = max(widest, x - spacing)
                x = 0
                y += rowHeight + rowSpacing
                rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }

        widest = max(widest, x > 0 ? x - spacing : 0)
        return CGSize(width: min(widest, maxWidth), height: y + rowHeight)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + rowSpacing
                rowHeight = 0
            }
            subview.place(
                at: CGPoint(x: x, y: y),
                proposal: ProposedViewSize(width: size.width, height: size.height)
            )
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
