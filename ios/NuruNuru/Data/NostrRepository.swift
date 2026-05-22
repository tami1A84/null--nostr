import Foundation

/// Single data access point for all Nostr operations.
/// Mirrors Android NostrRepository (actor pattern).
///
/// Phase 1: Connection and basic event subscription.
/// Phase 2+: Timeline, profiles, reactions, notifications, talk.
actor NostrRepository {

    // Process-wide shared MLS-FFI client to avoid repeatedly reopening the same DB.
    // IMPORTANT: MLS state is account-bound. Never reuse an FFI/MDK client across
    // Nostr pubkeys; otherwise a freshly generated account can see/decrypt prior
    // account MLS groups from the same process/database.
    nonisolated(unsafe) private static var sharedMlsClient: MlsFFIBridge?
    nonisolated(unsafe) private static var sharedMlsAccountPubkey: String?
    nonisolated(unsafe) private static let sharedFfiLock = NSLock()

    // MARK: - Dependencies

    let client: NostrClient
    private let keyManager: SecureKeyManager
    let prefs: AppPreferences
    let signer: InternalSigner
    /// Optional Rust FFI engine (Phase 5). Lazily initialized for Talk/MLS.
    var mlsClient: MlsFFIBridge?
    /// Two-layer cache: in-memory LRU + UserDefaults persistence.
    let cache = NostrCache()
    /// Persistent retry metadata for Marmot self-update / message / KeyPackage rotation.
    let mlsRetryStore = MlsRetryMetadataStore()

    // MARK: - State

    private var activeSubscriptions: [String] = []
    /// True after connect() has finished setting up relay connections.
    /// Used to prevent fetchEvents from running before any connections exist.
    private var isConnected = false

    /// Bookmark Kind 10003 cache/in-flight de-dupe.
    var bookmarkEventIdCache: [String: (ids: [String], cachedAt: Date)] = [:]
    var bookmarkEventIdFetchTasks: [String: Task<[String], Never>] = [:]

    /// Fast relay selection cache (relay URL -> scored posts, cachedAt).
    var relayTimelineCache: [String: (posts: [ScoredPost], cachedAt: Date)] = [:]

    /// Quote event/profile cache to avoid repeated quote-card loading.
    var quotedPostCache: [String: ScoredPost] = [:]

    // MARK: - MLS Session State (actor-isolated)
    // Rust SQLite (MDK) が single source of truth。アプリ側はセッション内の二重処理防止のみ。

    /// Per-group set of processed Kind-445 event IDs — prevents MLS epoch mismatches on reprocessing.
    var mlsProcessedIds:      [String: Set<String>] = [:]
    /// Processed Welcome event IDs — prevents re-processing already-joined groups.
    var processedWelcomeIds:  Set<String> = []
    /// Failed Welcome retry gate (event id -> next retry unix sec). Prevents repeated
    /// HMAC/process_welcome attempts from blocking Talk UI while still allowing later retry.
    var rejectedWelcomeRetryAfter: [String: Int64] = [:]
    /// Cooldown gate for receiver-side recovery commit attempts (per group, unix sec).
    var mlsRecoveryAttemptAt: [String: Int64] = [:]
    /// Consecutive receiver-side unprocessable streak per group.
    var mlsUnprocessableStreak: [String: Int] = [:]
    /// Per-group per-event unprocessable counter (used for quarantine to avoid infinite retry loops).
    var mlsUnprocessableEventAttempts: [String: [String: Int]] = [:]
    /// Per-group per-sender unprocessable counter.
    var mlsUnprocessableSenderAttempts: [String: [String: Int]] = [:]
    /// Per-group per-epochKey unprocessable counter (epochKey ~= sender+time bucket).
    var mlsUnprocessableEpochAttempts: [String: [String: Int]] = [:]
    /// Per-group retryable unprocessable count from latest fetch pass (e.g. state_not_ready).
    var mlsRetryableStateCount: [String: Int] = [:]
    /// Successfully decrypted application messages keyed by Kind-445 event id.
    /// Keeps peer messages visible even if MDK history persistence lags or a later poll skips
    /// an already-applied event for idempotency.
    var mlsApplicationMessageCache: [String: [String: FfiDecryptedMessage]] = [:]
    /// Kind-445 event ids that were successfully applied (application or state-only).
    /// Unlike the older processedIds bug, application messages are also cached above and
    /// merged into UI results, so skipping them later cannot make peer messages disappear.
    var mlsAppliedEventIds: [String: Set<String>] = [:]
    /// Retry cooldown for state_not_ready/unprocessable events. Without this, foreground
    /// polling replays the same impossible events across 10+ relays forever.
    var mlsRetryableEventCooldownUntil: [String: [String: Int64]] = [:]
    /// True after an initial full catch-up has run for a group in this process.
    /// Normal polling then uses an incremental safety window instead of full replay.
    var mlsDidFullCatchUp: Set<String> = []
    /// Short-lived gossip relay-list caches (author pubkey -> relay urls).
    var mlsKeyPackageRelayCache: [String: (relays: [String], cachedAt: Date)] = [:]
    var mlsInboxRelayCache: [String: (relays: [String], cachedAt: Date)] = [:]
    /// Per-relay hit/ACK score used to prefer relays that actually carry Marmot traffic.
    var mlsRelayHitScores: [String: Int] = [:]
    /// Groups for which this install published a post-Welcome self-update commit in this process.
    /// Until WhiteNoise confirms/interops reliably with post-Welcome self-update, do not create
    /// application messages from the advanced local epoch; sending must stay on peer-visible state.
    var mlsSelfUpdatePublishedThisSession: Set<String> = []
    /// Groups that repeatedly failed MLS receive repair and should no longer accept sends.
    var mlsBrokenGroupIds: Set<String> = []
    /// Per-group failed repair count used to transition to broken/read-only state.
    var mlsRepairFailureCount: [String: Int] = [:]
    /// True if the user's MLS KeyPackage (Kind 30443) has been published this session.
    var keyPackagePublished:  Bool = false


    // MARK: - Init

    init(
        keyManager: SecureKeyManager,
        prefs: AppPreferences,
        mlsClient: MlsFFIBridge? = nil
    ) {
        self.keyManager = keyManager
        self.prefs = prefs
        let signer = InternalSigner(keyManager: keyManager)
        self.signer = signer
        self.client = NostrClient(authEventSigner: { relayUrl, challenge in
            try signer.signEvent(
                kind: 22242,
                tags: [["relay", relayUrl], ["challenge", challenge]],
                content: ""
            )
        })
        self.mlsClient = mlsClient
    }

    // MARK: - FFI (lazy init)

    /// Lazily initialize Rust FFI client when needed (Talk/MLS path).
    /// Returns nil when FFI is unavailable or key material is not unlocked.
    @discardableResult
    func ensureMlsClient() -> MlsFFIBridge? {
#if NURUNURU_FFI_AVAILABLE
        let requestedPubkey = prefs.publicKeyHex?.lowercased()
        if let mlsClient,
           let requestedPubkey,
           Self.sharedMlsAccountPubkey?.lowercased() == requestedPubkey {
            return mlsClient
        }
        if let mlsClient {
            try? mlsClient.disconnect()
            self.mlsClient = nil
        }

        Self.sharedFfiLock.lock()
        if let shared = Self.sharedMlsClient,
           let requestedPubkey,
           Self.sharedMlsAccountPubkey?.lowercased() == requestedPubkey {
            Self.sharedFfiLock.unlock()
            self.mlsClient = shared
            return shared
        }
        if Self.sharedMlsClient != nil,
           let requestedPubkey,
           Self.sharedMlsAccountPubkey?.lowercased() != requestedPubkey {
            let old = Self.sharedMlsClient
            Self.sharedMlsClient = nil
            Self.sharedMlsAccountPubkey = nil
            Self.sharedFfiLock.unlock()
            try? old?.disconnect()
        } else {
            Self.sharedFfiLock.unlock()
        }

        // Default ON. Explicit disable flag only.
        if UserDefaults.standard.bool(forKey: "disable_rust_ffi") {
            AppLogger.log("FFI", "ensureMlsClient: disabled by UserDefaults(disable_rust_ffi=true)")
            return nil
        }

        let appSupport = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dbPathURL = appSupport.appendingPathComponent("nurunuru_ndb", isDirectory: true)
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: dbPathURL.path, isDirectory: &isDir), !isDir.boolValue {
            try? FileManager.default.removeItem(at: dbPathURL)
        }
        try? FileManager.default.createDirectory(at: dbPathURL, withIntermediateDirectories: true)
        let dbPath = dbPathURL.path
        AppLogger.log("FFI", "ensureMlsClient: dbPath=\(dbPath)")

        // Issue #181 M5: purge any pre-#181 plaintext MLS DB *before* we let
        // Rust open a handle to it. Content-based detection (B2) — runs every
        // launch so future regressions can't lock users out permanently.
        MlsLegacyMigration.purgePlaintextDbIfDetected(dbDirectoryPath: dbPath)

        do {
            let ffi: MlsFFILiveClient
            let accountPubkey: String

            if prefs.isExternalSigner {
                guard let pubkey = prefs.publicKeyHex else {
                    AppLogger.log("FFI", "ensureMlsClient: missing publicKeyHex for external signer")
                    return nil
                }
                // Issue #181: random 32-byte key, scoped to pubkey, stored in
                // Keychain (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly).
                // Lock-guarded inside MlsDbKeyStore (B4 race).
                var dbKey = try MlsDbKeyStore.getOrCreateExternalKey(pubkeyHex: pubkey)
                // MlsFFILiveClient zeroizes `dbKey` via `inout` after FFI hand-off (B3).
                ffi = try MlsFFILiveClient(pubkeyHex: pubkey, dbPath: dbPath, mlsDbKey: &dbKey)
                accountPubkey = pubkey
            } else {
                guard let keyHex = keyManager.getKeyHexTemporary() else {
                    AppLogger.log("FFI", "ensureMlsClient: secret key is not unlocked")
                    return nil
                }
                // Deterministic HKDF-SHA256 over the nsec — no persistence
                // required, regenerable as long as the user has their nsec.
                var dbKey = try MlsDbKeyStore.deriveInternalKey(secretKeyHex: keyHex)
                ffi = try MlsFFILiveClient(secretKeyHex: keyHex, dbPath: dbPath, mlsDbKey: &dbKey)
                accountPubkey = keyManager.getStoredPublicKeyHex() ?? requestedPubkey ?? ""
            }

            // Issue #181 M6: exclude DB + WAL/SHM from iCloud/iTunes backup.
            // A restored device with no Keychain entry could not decrypt them.
            MlsLegacyMigration.excludeMlsDbFromBackup(dbDirectoryPath: dbPath)

            ffi.connect()
            self.mlsClient = ffi
            Self.sharedFfiLock.lock()
            Self.sharedMlsClient = ffi
            Self.sharedMlsAccountPubkey = accountPubkey.lowercased()
            Self.sharedFfiLock.unlock()
            AppLogger.log("FFI", "ensureMlsClient: initialized \(prefs.isExternalSigner ? "read-only" : "full") FFI account=\(String(accountPubkey.prefix(8)))… + connected (MLS DB encrypted)")
            return ffi
        } catch {
            AppLogger.log("FFI", "ensureMlsClient failed: \(error)")
            return nil
        }
#else
        return nil
#endif
    }

    // MARK: - Connection

    /// Connect to all saved relays (NIP-65 or selectedRelays fallback) and FFI engine.
    /// Mirrors Android: NIP-65 relays take priority, deduplicates URLs.
    ///
    /// `client.connect()` は非同期でバックグラウンド実行。actor をブロックしない。
    /// MLS ポーリング (10秒間隔) が actor を占有しても connect が飢餓にならない。
    func connect() async {
        let relayUrls = buildRelayConnectionUrls()
        AppLogger.log("Repository", "connect() — \(relayUrls.count) relays: \(relayUrls)")
        // バックグラウンドで接続開始。actor をブロックしない。
        let capturedClient = client
        let capturedRelays = relayUrls
        Task.detached {
            await capturedClient.connect(relayUrls: capturedRelays)
            AppLogger.log("Repository", "✅ relay connect background task finished")
        }
        isConnected = true
        AppLogger.log("Repository", "connect() returned — timeline relays connecting in background")
    }

    /// Build the canonical relay list used for initial connection and lazy fetch recovery.
    private func buildRelayConnectionUrls() -> [String] {
        // First paint must be tiny and deterministic.  Do not let NIP-65 write
        // relays, search relays, or MLS interop relays enter the startup pool.
        // Search/Talk-specific code connects their relays lazily when needed.
        let preferredTimelineRelays = [
            "wss://yabu.me",
            "wss://r.kojira.io",
            "wss://relay-jp.nostr.wirednet.jp"
        ]

        let saved = getSavedRelayUrls()
        var candidates: [String] = []
        candidates.append(contentsOf: preferredTimelineRelays)
        for url in saved where candidates.count < 3 {
            if !candidates.contains(url) { candidates.append(url) }
        }

        var compact: [String] = []
        for url in candidates {
            guard !Self.deadRelays.contains(url),
                  !Self.temporarilyDeprioritizedRelays.contains(url),
                  url != searchRelayUrl else { continue }
            if !compact.contains(url) { compact.append(url) }
            if compact.count >= 3 { break }
        }
        return compact
    }

    /// Disconnect all relays and clean up.
    func disconnect() async {
        isConnected = false
        try? mlsClient?.disconnect()
        await client.disconnect()
    }

    /// Connection state of the underlying WebSocket.
    var connectionState: NostrClient.ConnectionState {
        get async { await client.connectionState }
    }

    /// Per-relay connection states — used by relay settings UI to show individual status.
    func perRelayStates() async -> [String: NostrClient.ConnectionState] {
        await client.perRelayStates()
    }

    // MARK: - Event Fetch (Phase 2)

    /// Fetch events from all connected relays; deduplicates by event ID.
    ///
    /// `isConnected` フラグに依存しない。`client.isEmpty` で実際のコネクション有無を確認。
    /// connect() が遅延/ハングしても actor をブロックしない。
    func fetchEvents(
        filters: [NostrFilter],
        timeoutSeconds: Double = 8.0
    ) async -> [NostrEvent] {
        // Startup race guard:
        // Timeline/Home VM can fetch before MainTabView.task { await repository.connect() } finishes.
        // In that case, proactively establish relay connections here so follow/global timeline queries
        // do not permanently return 0 on first app launch.
        let clientEmpty = await client.isEmpty
        let state = await client.connectionState
        let shouldEnsureConnection: Bool
        switch state {
        case .connected:
            shouldEnsureConnection = clientEmpty
        case .connecting, .disconnected, .failed:
            shouldEnsureConnection = true
        }
        if shouldEnsureConnection {
            let relayUrls = buildRelayConnectionUrls()
            AppLogger.log(
                "Repository",
                "fetchEvents: ensuring relay connections (empty=\(clientEmpty), state=\(state)) relays=\(relayUrls.count)"
            )
            await client.connect(relayUrls: relayUrls)
            isConnected = true
        }
        return await client.fetchEvents(filters: filters, timeoutSeconds: timeoutSeconds)
    }

    // MARK: - Publish

    /// Sign and publish an event to the relay, returning the exact signed event that was sent.
    func publishEventAndReturnSigned(
        kind: Int,
        tags: [[String]],
        content: String,
        waitForAllRelays: Bool = false
    ) async throws -> NostrEvent {
        // Publish paths can be reached from profile sheets before the timeline
        // has finished connecting relays.  Ensure at least the compact write
        // pool exists so follow/profile/reaction events are actually sent.
        let clientEmpty = await client.isEmpty
        let state = await client.connectionState
        let shouldEnsureConnection: Bool
        switch state {
        case .connected:
            shouldEnsureConnection = clientEmpty
        case .connecting, .disconnected, .failed:
            shouldEnsureConnection = true
        }
        if shouldEnsureConnection {
            let relayUrls = buildRelayConnectionUrls()
            AppLogger.log("Repository", "publishEvent: ensuring relay connections (empty=\(clientEmpty), state=\(state)) relays=\(relayUrls.count)")
            await client.connect(relayUrls: relayUrls)
            isConnected = true
        }

        let event = try signer.signEvent(kind: kind, tags: tags, content: content)
        try await client.publish(event: event, waitForAllRelays: waitForAllRelays)
        return event
    }

    /// Publish a signed event to the relay.
    func publishEvent(kind: Int, tags: [[String]], content: String, waitForAllRelays: Bool = false) async throws {
        _ = try await publishEventAndReturnSigned(kind: kind, tags: tags, content: content, waitForAllRelays: waitForAllRelays)
    }

    /// NIP-07 WebBridge 用 — 署名済みイベントを返すのみ（発行しない）。
    /// Webクライアントから渡された created_at を保持するため createdAt を受け取る。
    func signEventForBridge(
        kind: Int,
        tags: [[String]],
        content: String,
        createdAt: Int64? = nil
    ) throws -> NostrEvent {
        try signer.signEvent(
            kind: kind,
            tags: tags,
            content: content,
            createdAt: createdAt ?? Int64(Date().timeIntervalSince1970)
        )
    }

    /// NIP-07 WebBridge 用 — NIP-04 暗号化。nil = 失敗。
    func nip04EncryptForBridge(_ receiverPubkeyHex: String, _ plaintext: String) -> String? {
        signer.nip04Encrypt(receiverPubkeyHex: receiverPubkeyHex, plaintext: plaintext)
    }

    /// NIP-07 WebBridge 用 — NIP-04 復号。nil = 失敗。
    func nip04DecryptForBridge(_ senderPubkeyHex: String, _ ciphertext: String) -> String? {
        signer.nip04Decrypt(senderPubkeyHex: senderPubkeyHex, ciphertext: ciphertext)
    }

    /// NIP-07 WebBridge 用 — NIP-44 暗号化。nil = 失敗。
    func nip44EncryptForBridge(_ receiverPubkeyHex: String, _ plaintext: String) -> String? {
        signer.nip44Encrypt(recipientPubkeyHex: receiverPubkeyHex, plaintext: plaintext)
    }

    /// NIP-07 WebBridge 用 — NIP-44 復号。nil = 失敗。
    func nip44DecryptForBridge(_ senderPubkeyHex: String, _ ciphertext: String) -> String? {
        signer.nip44Decrypt(senderPubkeyHex: senderPubkeyHex, ciphertext: ciphertext)
    }

    /// NIP-07 WebBridge 用 — リレーリストを JSON オブジェクト文字列で返す。
    func getRelaysJson() -> String {
        let relayUrls = getSavedRelayUrls()
        let pairs = relayUrls
            .map { "\"\($0)\": {\"read\": true, \"write\": true}" }
            .joined(separator: ", ")
        return "{\(pairs)}"
    }

    // MARK: - Timeline (Phase 2)
    // fetchFollowList(pubkey:) → NostrRepository+Profiles.swift に移動済み

    /// Known-dead relays that should never be connected to.
    /// These relays have permanent DNS failures or are permanently offline.
    private static let deadRelays: Set<String> = [
        "wss://relay.nostr.bg",
        // Frequently times out on iOS startup and causes noisy retries/logs.
        // Users can still target it explicitly from relay-specific features if re-added later.
        "wss://relay.nostr.band",
    ]

    /// Relays that are not permanently dead, but should not be used for startup fan-out
    /// or background prefetch because recent device logs showed repeated handshake
    /// timeouts. Keeping them out of automatic traffic avoids load on relay operators.
    /// Explicit user-selected relay fetches can still try them through NostrClient's
    /// long cooldown path.
    static let temporarilyDeprioritizedRelays: Set<String> = [
        "wss://relay.nostr.wirednet.jp",
        "wss://relay.0xchat.com",
        "wss://realy.westernbtc.com",
    ]

    /// Return the saved relay URL list from preferences.
    /// Priority: NIP-65 relay URLs → fallback to selectedRelays (mirrors Android getSavedRelayUrls).
    /// Includes both read and write relays for maximum connectivity.
    /// Filters out known-dead relays to avoid wasted reconnect attempts.
    func getSavedRelayUrls() -> [String] {
        let nip65 = prefs.nip65Relays.map(\.url)
        let urls: [String]
        if !nip65.isEmpty {
            urls = nip65
        } else {
            let selected = prefs.selectedRelays
            urls = selected.isEmpty ? defaultRelays : selected
        }
        return urls.filter { !Self.deadRelays.contains($0) }
    }

    /// Return last cached global timeline instantly (for initial render).
    /// `nonisolated` — actor 境界を超えずに即時返却。NostrCache は NSLock で thread-safe。
    nonisolated func getCachedGlobalTimeline() -> [NostrEvent] {
        cache.getCachedTimeline(key: "global") ?? []
    }

    /// Return last cached following timeline instantly (for initial render).
    /// Android: cache.getCachedTimeline("following") に対応。
    nonisolated func getCachedFollowingTimeline() -> [NostrEvent] {
        cache.getCachedTimeline(key: "following") ?? []
    }

    /// Return cached follow list (no relay fetch).
    /// Android: cache.getCachedFollowList() に対応。
    nonisolated func getCachedFollowList(pubkey: String) -> [String]? {
        cache.getCachedFollowList(pubkey: pubkey)
    }

    // MARK: - Profile & Timeline (Phase 3)
    // fetchProfiles / fetchProfile / fetchUserNotes / fetchLikedEvents → NostrRepository+Profiles.swift に移動済み

    // MARK: - Notifications (Phase 3)
    // fetchNotifications / fetchEvent / parseBolt11Amount → NostrRepository+Notifications.swift に移動済み

    // MARK: - Publishing (Phase 3)
    // → NostrRepository+Actions.swift に移動済み

    // MARK: - Search (Phase 4)

    /// Search events using NIP-50 search filter.
    /// Routes to the dedicated NIP-50 search relay (search.nos.today).
    ///
    /// search.nos.today は一時接続だと EOSE 前に切断されることがあるため、
    /// まず持続接続プールに追加してから REQ を送る。
    func searchEvents(query: String, limit: Int = 30) async -> [NostrEvent] {
        // FFI ローカル検索を完全スキップ — NIP-50 リレー検索を直接使用。
        // FFI の nostrdb ローカル検索は全文検索に不向き（キャッシュ済みイベントのみ）。
        // Android も searchNotes() では FFI を使わず直接 SEARCH_RELAY に送信している。
        AppLogger.log("Search", "searchEvents — skipping FFI, using relay NIP-50 for '\(query)'")

        // 検索リレーを持続接続プールに追加（既に接続済みなら何もしない）
        await client.connect(relayUrls: [searchRelayUrl])

        // Android 同様: since 制限なし（search.nos.today がサーバー側で最適化）
        let filter = NostrFilter(kinds: [NostrKind.textNote], limit: limit, search: query)
        AppLogger.log("Search", "Sending NIP-50 REQ to \(searchRelayUrl) — filter: kinds=\(filter.kinds ?? []), search=\(filter.search ?? "nil"), limit=\(filter.limit ?? 0)")
        let raw = await client.fetchEventsFromRelay(
            searchRelayUrl,
            filters: [filter],
            timeoutSeconds: 10.0
        )
        AppLogger.log("Search", "NIP-50 raw results: \(raw.count) events (kinds: \(raw.map(\.kind)))")
        let filtered = raw
            .filter { $0.kind == NostrKind.textNote }
            .sorted { $0.createdAt > $1.createdAt }
        AppLogger.log("Search", "After kind filter: \(filtered.count) text notes")
        return filtered
    }

    /// NIP-50 search relay URL.
    var searchRelayUrl: String { "wss://search.nos.today" }

    /// NIP-50 プロフィール検索用の持続接続確保ヘルパー。
    /// `searchProfiles` からも呼ばれる。
    func ensureSearchRelayConnected() async {
        await client.connect(relayUrls: [searchRelayUrl])
    }

    // MARK: - Zap / LNURL (Phase 4)

    /// Default zap amount from user preferences.
    func getDefaultZapAmount() -> Int {
        prefs.defaultZapAmount
    }

    /// Lightweight LNURL-pay invoice fetch (no zap request / NIP-57).
    /// Used for quick-tap zap where a full zap request is not needed.
    func fetchLightningInvoice(lud16: String, amountSats: Int64) async -> String? {
        let parts = lud16.split(separator: "@")
        guard parts.count == 2 else { return nil }
        let user   = String(parts[0])
        let domain = String(parts[1])
        let endpoint = "https://\(domain)/.well-known/lnurlp/\(user)"
        guard let metaURL = URL(string: endpoint),
              let (metaData, _) = try? await URLSession.shared.data(from: metaURL),
              let meta = try? JSONDecoder().decode(LnurlPayMetadata.self, from: metaData),
              meta.tag == "payRequest" else { return nil }

        let amountMsats = amountSats * 1000
        let callbackBase = meta.callback
        let sep = callbackBase.contains("?") ? "&" : "?"
        let callbackStr = callbackBase + "\(sep)amount=\(amountMsats)"
        guard let callbackURL = URL(string: callbackStr),
              let (invoiceData, _) = try? await URLSession.shared.data(from: callbackURL),
              let invoiceResp = try? JSONDecoder().decode(LnurlInvoiceResponse.self, from: invoiceData),
              let invoice = invoiceResp.pr, !invoice.isEmpty else { return nil }
        return invoice
    }

    /// Upload an image. Routes to nostr.build / yabu.me / Blossom based on prefs.uploadServer.
    /// Uses the shared ImageUploadService so composer/profile uploads share the same endpoint,
    /// Blossom auth, timeout, and response parsing behavior.
    func uploadImage(data: Data, mimeType: String) async throws -> String {
        let service = ImageUploadService(signer: signer)
        return try await service.uploadImage(
            imageData: data,
            server: prefs.uploadServerEnum,
            blossomBaseUrl: prefs.blossomUploadBaseUrl,
            mimeType: mimeType
        )
    }

    // MARK: - Upload Targets

    private func uploadToNostrBuild(data: Data, mimeType: String) async throws -> String {
        let endpoint = "https://nostr.build/api/v2/nip96/upload"
        var request  = try buildMultipartRequest(endpoint: endpoint, data: data, mimeType: mimeType)
        nip98Auth(request: &request, url: endpoint, method: "POST")
        let (resp, http) = try await URLSession.shared.data(for: request)
        guard (http as? HTTPURLResponse)?.statusCode == 200 else { throw UploadError.serverError }
        struct R: Decodable {
            struct Nip94: Decodable { let tags: [[String]] }
            let nip94_event: Nip94?; let url: String?
        }
        let decoded = try JSONDecoder().decode(R.self, from: resp)
        if let u = decoded.url { return u }
        if let u = decoded.nip94_event?.tags.first(where: { $0.first == "url" })?[safe: 1] { return u }
        throw UploadError.noUrl
    }

    private func uploadToYabuMe(data: Data, mimeType: String) async throws -> String {
        let endpoint = "https://share.yabu.me/api/v2/media"
        var request  = try buildMultipartRequest(endpoint: endpoint, data: data, mimeType: mimeType)
        nip98Auth(request: &request, url: endpoint, method: "POST")
        let (resp, http) = try await URLSession.shared.data(for: request)
        guard (http as? HTTPURLResponse)?.statusCode == 200 else { throw UploadError.serverError }
        struct R: Decodable {
            struct Nip94: Decodable { let tags: [[String]] }
            let nip94_event: Nip94?; let status: String?; let url: String?
        }
        let decoded = try JSONDecoder().decode(R.self, from: resp)
        if let u = decoded.url { return u }
        if let u = decoded.nip94_event?.tags.first(where: { $0.first == "url" })?[safe: 1] { return u }
        throw UploadError.noUrl
    }

    private func uploadToBlossom(data: Data, mimeType: String, baseUrl: String) async throws -> String {
        let endpoint = baseUrl.hasSuffix("/") ? baseUrl + "upload" : baseUrl + "/upload"
        var request  = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "PUT"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        nip98Auth(request: &request, url: endpoint, method: "PUT")
        let (resp, http) = try await URLSession.shared.data(for: request)
        guard (http as? HTTPURLResponse)?.statusCode == 200 else { throw UploadError.serverError }
        struct R: Decodable { let url: String? }
        if let u = (try? JSONDecoder().decode(R.self, from: resp))?.url { return u }
        throw UploadError.noUrl
    }

    // MARK: - NIP-98 HTTP Auth (Kind 27235)

    private func nip98Auth(request: inout URLRequest, url: String, method: String) {
        guard let event = try? signer.signEvent(
            kind: 27235,
            tags: [["u", url], ["method", method]],
            content: ""
        ), let json = try? JSONEncoder().encode(event),
           let b64 = String(data: json.base64EncodedData(), encoding: .utf8) else { return }
        request.setValue("Nostr \(b64)", forHTTPHeaderField: "Authorization")
    }

    // MARK: - Multipart Helper

    private func buildMultipartRequest(endpoint: String, data: Data, mimeType: String) throws -> URLRequest {
        var request  = URLRequest(url: URL(string: endpoint)!)
        request.httpMethod = "POST"
        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let ext  = mimeType == "image/png" ? "png" : "jpg"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.\(ext)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body
        return request
    }

    /// Build zap request (kind 9734), hit LNURL-pay endpoint, return bolt11 invoice.
    func generateZapInvoice(
        toEventId:   String,
        toRecipient: String,
        amountSats:  Int,
        comment:     String?,
        myPubkeyHex: String
    ) async throws -> String {
        // Fetch recipient profile to get lud16
        guard let profile = await fetchProfile(pubkey: toRecipient),
              let lud16   = profile.lud16, !lud16.isEmpty else {
            throw ZapError.noLightningAddress
        }

        // Convert lud16 → LNURL endpoint
        let parts = lud16.split(separator: "@")
        guard parts.count == 2 else { throw ZapError.invalidLightningAddress }
        let user   = String(parts[0])
        let domain = String(parts[1])
        let lnurlEndpoint = "https://\(domain)/.well-known/lnurlp/\(user)"

        // Step 1: GET LNURL-pay metadata
        guard let metaURL = URL(string: lnurlEndpoint) else { throw ZapError.invalidLightningAddress }
        let (metaData, _) = try await URLSession.shared.data(from: metaURL)
        let meta = try JSONDecoder().decode(LnurlPayMetadata.self, from: metaData)
        guard meta.tag == "payRequest" else { throw ZapError.invalidLightningAddress }

        let amountMsats = amountSats * 1000

        // Step 2: Build zap request (kind 9734)
        let tags: [[String]] = [
            ["relays", "wss://yabu.me"],
            ["amount", "\(amountMsats)"],
            ["p", toRecipient],
            ["e", toEventId]
        ]
        let zapRequestEvent = try signer.signEvent(
            kind: NostrKind.zapRequest,
            tags: tags,
            content: comment ?? ""
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let zapRequestJSON = (try? encoder.encode(zapRequestEvent))
            .flatMap { String(data: $0, encoding: .utf8) } ?? ""
        let encodedZapRequest = zapRequestJSON.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""

        // Step 3: POST to callback
        let callbackBase = meta.callback
        var callbackStr  = callbackBase
        let separator    = callbackBase.contains("?") ? "&" : "?"
        callbackStr += "\(separator)amount=\(amountMsats)"
        if !encodedZapRequest.isEmpty {
            callbackStr += "&nostr=\(encodedZapRequest)"
        }
        if let c = comment, !c.isEmpty {
            let enc = c.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            callbackStr += "&comment=\(enc)"
        }

        guard let callbackURL = URL(string: callbackStr) else { throw ZapError.requestFailed }
        let (invoiceData, _) = try await URLSession.shared.data(from: callbackURL)
        let invoiceResp = try JSONDecoder().decode(LnurlInvoiceResponse.self, from: invoiceData)
        guard let invoice = invoiceResp.pr, !invoice.isEmpty else { throw ZapError.requestFailed }
        return invoice
    }

    // MARK: - Emoji Sets / Badges (Phase 4)
    // fetchEmojiSets / fetchBadges → NostrRepository+Profiles.swift に移動済み

    // MARK: - Mute List (Phase 4, NIP-51)
    // → NostrRepository+Actions.swift に移動済み

    // MARK: - Talk / MLS (Phase 5: Rust FFI)
    // → NostrRepository+Talk.swift に移動済み

    // MARK: - FFI Helpers
    // signUnsignedEventJson(_:) → NostrRepository+Actions.swift に移動済み
    // bridgeFfiProfile(_:) → NostrRepository+Profiles.swift に移動済み
    // bridgeFfiGroup(_:)    → NostrRepository+Talk.swift に移動済み

    /// Decode a list of JSON event strings returned by the Rust engine into `NostrEvent` values.
    func decodeFFIEvents(_ jsonStrings: [String]) -> [NostrEvent] {
        let decoder = JSONDecoder()
        return jsonStrings.compactMap { json -> NostrEvent? in
            guard let data = json.data(using: .utf8) else { return nil }
            return try? decoder.decode(NostrEvent.self, from: data)
        }
    }

    // MARK: - キャッシュ管理 (mirrors Android NostrRepository cache management)

    nonisolated func getCacheStats() -> NostrCache.CacheStats {
        cache.getCacheStats()
    }

    nonisolated func getCacheEntriesCount(_ typeId: String) -> Int {
        cache.getEntriesCount(typeId)
    }

    nonisolated func clearExpiredCache() -> Int {
        cache.clearExpiredCache()
    }

    func clearCacheByType(_ typeId: String) {
        cache.clearByType(typeId)
        if typeId == "mls_groups" || typeId == "mls_messages" {
            mlsProcessedIds.removeAll()
            processedWelcomeIds.removeAll()
            mlsRecoveryAttemptAt.removeAll()
            mlsUnprocessableStreak.removeAll()
            mlsUnprocessableEventAttempts.removeAll()
            mlsUnprocessableSenderAttempts.removeAll()
            mlsUnprocessableEpochAttempts.removeAll()
        }
    }

    func clearAllCache() {
        cache.clearAll()
        URLCache.shared.removeAllCachedResponses()
        mlsProcessedIds.removeAll()
        processedWelcomeIds.removeAll()
        mlsRecoveryAttemptAt.removeAll()
        mlsUnprocessableStreak.removeAll()
        mlsUnprocessableEventAttempts.removeAll()
        mlsUnprocessableSenderAttempts.removeAll()
        mlsUnprocessableEpochAttempts.removeAll()
    }

    nonisolated func applyCacheSettings() {
        cache.applySettings(prefs)
    }

    /// プロフィールをキャッシュに保存する（Kind 0 publish 後の即時反映用）。
    /// グレース期間も設定し、リレーの古いデータによる上書きを防止する。
    nonisolated func cacheProfile(_ profile: UserProfile) {
        cache.setCachedProfile(profile.pubkey, profile)
        // 60秒間のグレース期間: この間はリレーからの古いプロフィールで上書きしない
        cache.setProfileGraceUntil(profile.pubkey, until: Date().addingTimeInterval(60))
    }

    /// キャッシュからプロフィールを取得する（リレー通信なし、即時返却）。
    /// Android: cache.getCachedProfile() に対応。
    nonisolated func getCachedProfile(pubkey: String) -> UserProfile? {
        cache.getCachedProfile(pubkey)
    }

    /// プロフィールのグレース期間中かどうかを返す。
    /// グレース期間中はリレーからの古いデータで上書きしない。
    nonisolated func isProfileInGracePeriod(pubkey: String) -> Bool {
        cache.isProfileInGracePeriod(pubkey)
    }
}

// MARK: - Upload Error

enum UploadError: LocalizedError {
    case serverError
    case noUrl

    var errorDescription: String? {
        switch self {
        case .serverError: return "画像のアップロードに失敗しました"
        case .noUrl:       return "アップロードURLを取得できませんでした"
        }
    }
}

// MARK: - Zap / LNURL Models

enum ZapError: LocalizedError {
    case noLightningAddress
    case invalidLightningAddress
    case requestFailed

    var errorDescription: String? {
        switch self {
        case .noLightningAddress:    return "Lightning Addressが設定されていません"
        case .invalidLightningAddress: return "Lightning Addressの形式が正しくありません"
        case .requestFailed:         return "インボイスの取得に失敗しました"
        }
    }
}

private struct LnurlPayMetadata: Decodable {
    let tag:      String
    let callback: String
    let minSendable: Int64?
    let maxSendable: Int64?
}

private struct LnurlInvoiceResponse: Decodable {
    let pr:     String?
    let status: String?
    let reason: String?
}

// ProfileContent → NostrRepository+Profiles.swift に移動済み

// MARK: - MLS Retry Metadata Store

import Foundation

/// Persistent, non-sensitive retry metadata for Marmot/MLS publish flows.
///
/// This store intentionally contains scheduling metadata and already-signed public
/// Nostr event JSON only. It must never store private keys or MDK/OpenMLS internal
/// MLS group ids. Every `groupIdHex` value here is the Nostr group id (Kind 445
/// `h` tag value) at the FFI/App boundary.
final class MlsRetryMetadataStore: @unchecked Sendable {

    private struct State: Codable {
        var items: [MlsRetryQueueItem] = []
        var relayCooldowns: [String: MlsRelayRetryState] = [:]
    }

    private let lock = NSLock()
    private let fileURL: URL
    private var state: State

    init(fileURL: URL? = nil) {
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let appSupport = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            try? FileManager.default.createDirectory(at: appSupport, withIntermediateDirectories: true)
            self.fileURL = appSupport.appendingPathComponent("nurunuru_mls_retry_metadata_v1.json")
        }
        self.state = Self.load(from: self.fileURL)
        pruneExpiredLocked(now: Int64(Date().timeIntervalSince1970))
    }

    func upsert(_ item: MlsRetryQueueItem) {
        lock.lock(); defer { lock.unlock() }
        pruneExpiredLocked(now: Int64(Date().timeIntervalSince1970))
        if let idx = state.items.firstIndex(where: { $0.id == item.id }) {
            state.items[idx] = item
        } else {
            state.items.append(item)
        }
        saveLocked()
    }

    func enqueueSelfUpdate(
        accountPubkey: String,
        groupIdHex: String,
        relayUrls: [String],
        lastErrorKind: MlsRetryLastErrorKind,
        now: Int64 = Int64(Date().timeIntervalSince1970)
    ) {
        guard !accountPubkey.isEmpty, !groupIdHex.isEmpty else { return }
        var item = existing(id: "selfUpdate:\(accountPubkey):\(groupIdHex)")
            ?? MlsRetryQueueItem(
                id: "selfUpdate:\(accountPubkey):\(groupIdHex)",
                queueType: .selfUpdate,
                accountPubkey: accountPubkey,
                groupIdHex: groupIdHex,
                eventId: nil,
                relayUrls: relayUrls,
                attemptCount: 0,
                nextAttemptAt: now,
                lastErrorKind: lastErrorKind,
                terminalFailure: nil,
                createdAt: now,
                expiresAt: nil,
                signedEventJSON: nil,
                keyPackageOwnerPubkey: nil,
                keyPackageEventId: nil
            )
        item.relayUrls = relayUrls
        item.lastErrorKind = lastErrorKind
        item.terminalFailure = nil
        item.nextAttemptAt = min(item.nextAttemptAt, now)
        upsert(item)
    }

    func enqueueMessage(
        accountPubkey: String,
        groupIdHex: String,
        eventId: String,
        relayUrls: [String],
        signedEventJSON: String,
        lastErrorKind: MlsRetryLastErrorKind,
        now: Int64 = Int64(Date().timeIntervalSince1970)
    ) {
        guard !accountPubkey.isEmpty, !groupIdHex.isEmpty, !eventId.isEmpty, !signedEventJSON.isEmpty else { return }
        var item = existing(id: "message:\(accountPubkey):\(eventId)")
            ?? MlsRetryQueueItem(
                id: "message:\(accountPubkey):\(eventId)",
                queueType: .message,
                accountPubkey: accountPubkey,
                groupIdHex: groupIdHex,
                eventId: eventId,
                relayUrls: relayUrls,
                attemptCount: 0,
                nextAttemptAt: now,
                lastErrorKind: lastErrorKind,
                terminalFailure: nil,
                createdAt: now,
                expiresAt: now + 7 * 24 * 60 * 60,
                signedEventJSON: signedEventJSON,
                keyPackageOwnerPubkey: nil,
                keyPackageEventId: nil
            )
        item.relayUrls = relayUrls
        item.signedEventJSON = signedEventJSON
        item.lastErrorKind = lastErrorKind
        item.terminalFailure = nil
        item.nextAttemptAt = min(item.nextAttemptAt, now)
        upsert(item)
    }

    /// Enqueue a consumed KeyPackage rotation retry.
    /// `keyPackageOwnerPubkey` is the consumed KeyPackage event owner pubkey.
    /// For Welcome kind 1059 this is the recipient of the Welcome delivery; it is
    /// not the Welcome rumor pubkey.
    func enqueueKeyPackageRotation(
        accountPubkey: String,
        keyPackageOwnerPubkey: String,
        keyPackageEventId: String?,
        relayUrls: [String],
        lastErrorKind: MlsRetryLastErrorKind,
        now: Int64 = Int64(Date().timeIntervalSince1970)
    ) {
        guard !accountPubkey.isEmpty, !keyPackageOwnerPubkey.isEmpty else { return }
        let key = keyPackageEventId?.isEmpty == false ? keyPackageEventId! : keyPackageOwnerPubkey
        var item = existing(id: "keyPackageRotation:\(accountPubkey):\(key)")
            ?? MlsRetryQueueItem(
                id: "keyPackageRotation:\(accountPubkey):\(key)",
                queueType: .keyPackageRotation,
                accountPubkey: accountPubkey,
                groupIdHex: nil,
                eventId: keyPackageEventId,
                relayUrls: relayUrls,
                attemptCount: 0,
                nextAttemptAt: now,
                lastErrorKind: lastErrorKind,
                terminalFailure: nil,
                createdAt: now,
                expiresAt: nil,
                signedEventJSON: nil,
                keyPackageOwnerPubkey: keyPackageOwnerPubkey,
                keyPackageEventId: keyPackageEventId
            )
        item.relayUrls = relayUrls
        item.keyPackageOwnerPubkey = keyPackageOwnerPubkey
        item.keyPackageEventId = keyPackageEventId
        item.eventId = keyPackageEventId
        item.lastErrorKind = lastErrorKind
        item.terminalFailure = nil
        item.nextAttemptAt = min(item.nextAttemptAt, now)
        upsert(item)
    }

    func dueItems(
        accountPubkey: String,
        now: Int64 = Int64(Date().timeIntervalSince1970),
        limit: Int
    ) -> [MlsRetryQueueItem] {
        lock.lock(); defer { lock.unlock() }
        pruneExpiredLocked(now: now)
        saveLocked()
        return state.items
            .filter { $0.accountPubkey == accountPubkey && $0.terminalFailure == nil && $0.nextAttemptAt <= now }
            .sorted { lhs, rhs in
                if lhs.queueType.priority != rhs.queueType.priority { return lhs.queueType.priority < rhs.queueType.priority }
                if lhs.nextAttemptAt != rhs.nextAttemptAt { return lhs.nextAttemptAt < rhs.nextAttemptAt }
                return lhs.createdAt < rhs.createdAt
            }
            .prefix(limit)
            .map { $0 }
    }

    func availableRelays(for item: MlsRetryQueueItem, now: Int64 = Int64(Date().timeIntervalSince1970)) -> [String] {
        lock.lock(); defer { lock.unlock() }
        let relays = item.relayUrls.isEmpty ? [] : item.relayUrls
        return relays.filter { relay in
            (state.relayCooldowns[relay]?.cooldownUntil ?? 0) <= now
        }
    }

    func markSucceeded(itemId: String) {
        lock.lock(); defer { lock.unlock() }
        state.items.removeAll { $0.id == itemId }
        saveLocked()
    }

    func markFailed(itemId: String, relayUrls: [String], errorKind: MlsRetryLastErrorKind, foreground: Bool) {
        lock.lock(); defer { lock.unlock() }
        let now = Int64(Date().timeIntervalSince1970)
        guard let idx = state.items.firstIndex(where: { $0.id == itemId }) else { return }
        var item = state.items[idx]
        item.attemptCount += 1
        item.lastErrorKind = errorKind
        item.nextAttemptAt = Self.nextAttemptAt(attemptCount: item.attemptCount, now: now, foreground: foreground)
        state.items[idx] = item

        for relay in relayUrls {
            var r = state.relayCooldowns[relay] ?? MlsRelayRetryState(relayUrl: relay, attemptCount: 0, cooldownUntil: 0, lastErrorKind: nil)
            r.attemptCount += 1
            r.lastErrorKind = errorKind
            r.cooldownUntil = Self.nextAttemptAt(attemptCount: r.attemptCount, now: now, foreground: foreground)
            state.relayCooldowns[relay] = r
        }
        pruneExpiredLocked(now: now)
        saveLocked()
    }

    func markTerminal(itemId: String, reason: String) {
        lock.lock(); defer { lock.unlock() }
        if let idx = state.items.firstIndex(where: { $0.id == itemId }) {
            state.items[idx].terminalFailure = reason
            saveLocked()
        }
    }

    func summary(accountPubkey: String) -> (queued: Int, due: Int) {
        lock.lock(); defer { lock.unlock() }
        let now = Int64(Date().timeIntervalSince1970)
        let items = state.items.filter { $0.accountPubkey == accountPubkey && $0.terminalFailure == nil }
        return (items.count, items.filter { $0.nextAttemptAt <= now }.count)
    }

    private func existing(id: String) -> MlsRetryQueueItem? {
        lock.lock(); defer { lock.unlock() }
        return state.items.first { $0.id == id }
    }

    static func nextAttemptAt(attemptCount: Int, now: Int64, foreground: Bool) -> Int64 {
        let base: Double = 30.0
        let cap: Double = foreground ? 15 * 60 : 6 * 60 * 60
        let exponent = max(0, min(attemptCount - 1, 12))
        let raw = min(cap, base * pow(2.0, Double(exponent)))
        // Stable jitter in ±20%; avoids requiring random state in tests and spreads retries enough.
        let jitterSteps = [0.82, 0.93, 1.0, 1.11, 1.19]
        let jitter = jitterSteps[abs(attemptCount) % jitterSteps.count]
        return now + Int64(max(1.0, min(cap, raw * jitter)).rounded())
    }

    private static func load(from url: URL) -> State {
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(State.self, from: data) else {
            return State()
        }
        return decoded
    }

    private func saveLocked() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(state)
            try data.write(to: fileURL, options: [.atomic])
            try? (fileURL as NSURL).setResourceValue(URLFileProtection.completeUntilFirstUserAuthentication, forKey: .fileProtectionKey)
        } catch {
            AppLogger.log("MLS", "retry metadata save failed: \(error)")
        }
    }

    private func pruneExpiredLocked(now: Int64) {
        state.items.removeAll { item in
            if let expiresAt = item.expiresAt, expiresAt <= now { return true }
            return false
        }
        state.relayCooldowns = state.relayCooldowns.filter { $0.value.cooldownUntil > now - 24 * 60 * 60 }
    }
}

enum MlsRetryQueueType: String, Codable, Sendable {
    case selfUpdate
    case keyPackageRotation
    case message

    var priority: Int {
        switch self {
        case .selfUpdate: return 0
        case .keyPackageRotation: return 1
        case .message: return 2
        }
    }
}

enum MlsRetryLastErrorKind: String, Codable, Sendable {
    case transientNetwork
    case relayRejected
    case rateLimited
    case authRequired
    case notConnected
    case signingKeyMissing
    case invalidPayload
    case groupUnavailable
    case unknown
}

struct MlsRetryQueueItem: Codable, Identifiable, Sendable {
    let id: String
    let queueType: MlsRetryQueueType
    let accountPubkey: String
    /// Nostr group id hex (Kind 445 `h` tag). Never an internal MLS group id.
    var groupIdHex: String?
    var eventId: String?
    var relayUrls: [String]
    var attemptCount: Int
    var nextAttemptAt: Int64
    var lastErrorKind: MlsRetryLastErrorKind
    var terminalFailure: String?
    var createdAt: Int64
    var expiresAt: Int64?
    /// Already-signed outer event JSON for message retry. Regeneration would mutate MLS state, so keep exact JSON.
    var signedEventJSON: String?
    /// KeyPackage event owner pubkey, not Welcome rumor pubkey.
    var keyPackageOwnerPubkey: String?
    var keyPackageEventId: String?
}

struct MlsRelayRetryState: Codable, Sendable {
    let relayUrl: String
    var attemptCount: Int
    var cooldownUntil: Int64
    var lastErrorKind: MlsRetryLastErrorKind?
}

