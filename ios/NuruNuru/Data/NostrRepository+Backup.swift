import Foundation

// MARK: - Backup / Import / Profile / Relay メソッド群
//
// Android の NostrRepositoryBackup.kt に相当する extension。
//
// ※ requestVanish(relays:reason:) は NostrRepository+Actions.swift に実装済み。
//   NIP-62 Kind 62 の発行はそちらを参照すること。

extension NostrRepository {

    // MARK: - Fetch All User Events (ページネーション付きバッチエクスポート)

    /// 指定 pubkey の全イベントをリレーから時系列ページネーションで取得する。
    ///
    /// - バッチサイズ 500 で `until` を更新しながら繰り返しフェッチ。
    /// - イベント ID による重複排除を行う。
    /// - Android: `NostrRepository.fetchAllUserEvents()` に対応。
    ///
    /// - Parameters:
    ///   - pubkey: エクスポート対象の hex 公開鍵。
    ///   - onProgress: 進捗コールバック (fetched: 取得済み合計件数, batch: 実行バッチ番号)。
    /// - Returns: `createdAt` 昇順でソートされたイベント配列。
    func fetchAllUserEvents(
        pubkey: String,
        onProgress: (Int, Int) -> Void = { _, _ in }
    ) async -> [NostrEvent] {
        var allEvents: [NostrEvent] = []
        var seenIds = Set<String>()
        let batchSize = 500
        var lastTimestamp = Int64(Date().timeIntervalSince1970)
        var hasMore = true
        var batchCount = 0

        while hasMore {
            var filter = NostrFilter()
            filter.authors = [pubkey]
            filter.until   = lastTimestamp
            filter.limit   = batchSize

            let events = await fetchEvents(filters: [filter], timeoutSeconds: 10.0)

            if events.isEmpty {
                hasMore = false
                break
            }

            var newInBatch = 0
            for event in events {
                if seenIds.insert(event.id).inserted {
                    allEvents.append(event)
                    newInBatch += 1
                }
            }

            let minTimestamp = events.map(\.createdAt).min() ?? lastTimestamp
            if minTimestamp >= lastTimestamp {
                hasMore = false
            } else {
                lastTimestamp = minTimestamp - 1
            }

            batchCount += 1
            onProgress(allEvents.count, batchCount)

            if newInBatch == 0 { hasMore = false }
        }

        return allEvents.sorted { $0.createdAt < $1.createdAt }
    }

    // MARK: - Import Events to Relays (NIP-70 チェック付きリパブリッシュ)

    /// 署名済みイベント群を接続中のリレーへ再発行する。
    ///
    /// - NIP-70 保護済みイベント (`isProtected == true`) で自分の pubkey 以外のものはスキップ。
    /// - イベント間に 50 ms のウェイトを挿入してリレーへの負荷を抑制する。
    /// - Android: `NostrRepository.importEventsToRelays()` に対応。
    ///
    /// - Parameters:
    ///   - events: 再発行するイベント配列。
    ///   - onProgress: 進捗コールバック (current, total, success, failed)。
    /// - Returns: 発行結果サマリー `ImportResult`。
    func importEventsToRelays(
        events: [NostrEvent],
        onProgress: (Int, Int, Int, Int) -> Void = { _, _, _, _ in }
    ) async -> ImportResult {
        var success = 0
        var failed  = 0
        var skipped = 0
        let myPubkey = prefs.publicKeyHex ?? ""

        for (index, event) in events.enumerated() {
            // NIP-70: 他者の保護済みイベントはリパブリッシュ不可
            if event.isProtected && event.pubkey != myPubkey {
                skipped += 1
            } else {
                do {
                    try await client.publish(event: event)
                    success += 1
                } catch {
                    failed += 1
                }
            }

            onProgress(index + 1, events.count, success, failed)
            // リレー負荷軽減のため 50 ms 待機 (Android delay(50) と同等)
            try? await Task.sleep(nanoseconds: 50_000_000)
        }

        return ImportResult(
            total:   events.count,
            success: success,
            failed:  failed,
            skipped: skipped
        )
    }

    // MARK: - Upload Image (明示的サーバー指定版)

    /// 画像データを指定サーバーへアップロードし、公開 URL を返す。
    ///
    /// サーバールーティング:
    ///   - `nostr.build`  → NIP-96 マルチパートアップロード
    ///   - `share.yabu.me` → yabu.me NIP-96 エンドポイント
    ///   - それ以外       → Blossom PUT アップロード
    ///
    /// 全ターゲットで NIP-98 HTTP Auth (Kind 27235) ヘッダーを付与する。
    /// Android: `NostrRepository.uploadImage()` に対応。
    ///
    /// - Parameters:
    ///   - data:     アップロードするバイナリデータ。
    ///   - server:   アップロード先サーバー文字列 (例: "nostr.build", "https://blossom.primal.net")。
    ///   - mimeType: MIME タイプ (デフォルト "image/jpeg")。
    /// - Returns: アップロード後の公開 URL。
    func uploadImage(data: Data, server: String, mimeType: String = "image/jpeg") async throws -> String {
        let normalized = server
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            .lowercased()

        let url: String
        if normalized == "nostr.build" || normalized.hasSuffix(".nostr.build") {
            url = try await backupUploadToNostrBuild(data: data, mimeType: mimeType)
        } else if normalized == "share.yabu.me" || normalized.contains("yabu") {
            url = try await backupUploadToYabuMe(data: data, mimeType: mimeType)
        } else {
            // Blossom 互換サーバー
            let base = server.hasSuffix("/") ? String(server.dropLast()) : server
            url = try await backupUploadToBlossom(data: data, mimeType: mimeType, baseUrl: base)
        }

        // TODO: prefs.addUploadedImage(url) — AppPreferences に addUploadedImage(_:) を追加後に有効化
        return url
    }

    // MARK: - Fetch Relay List (Kind 10002, NIP-65)

    /// 指定 pubkey の NIP-65 リレーリスト (Kind 10002) を取得する。
    ///
    /// "r" タグをパースして `[Nip65Relay]` を返す。
    /// マーカーなし → readWrite、"read" → read、"write" → write。
    /// Android: `OutboxModel.fetchUserRelayList()` に対応。
    ///
    /// - Parameter pubkey: 取得対象の hex 公開鍵。
    /// - Returns: リレーリスト。リレーが未設定または取得失敗の場合は空配列。
    func fetchRelayList(pubkey: String) async -> [Nip65Relay] {
        var filter = NostrFilter()
        filter.authors = [pubkey]
        filter.kinds   = [NostrKind.relayList]
        filter.limit   = 1

        let events = await fetchEvents(filters: [filter], timeoutSeconds: 6.0)
        guard let event = events.max(by: { $0.createdAt < $1.createdAt }) else {
            return []
        }

        return event.tags
            .filter { tag in
                tag.count >= 2 && tag[0] == "r" && tag[1].hasPrefix("wss://")
            }
            .map { tag -> Nip65Relay in
                let url    = tag[1]
                let marker = tag.count >= 3 ? tag[2] : nil
                return Nip65Relay(url: url, permission: RelayPermission(marker: marker))
            }
    }

    // MARK: - Update Relay List (Kind 10002, NIP-65)

    /// ユーザーのリレーリストを Kind 10002 イベントとして発行する (NIP-65)。
    ///
    /// - readWrite の場合はマーカーなしの `["r", url]` タグを生成。
    /// - read のみ → `["r", url, "read"]`、write のみ → `["r", url, "write"]`。
    /// - Android: `NostrRepository.updateRelayList()` に対応。
    ///
    /// - Parameter relays: `[Nip65Relay]` 配列。
    func updateRelayList(relays: [Nip65Relay]) async throws {
        let tags: [[String]] = relays.map { relay in
            if let marker = relay.permission.tagMarker {
                return ["r", relay.url, marker]
            } else {
                return ["r", relay.url]     // マーカーなし = 読み書き両用 (readWrite)
            }
        }
        try await publishEvent(kind: NostrKind.relayList, tags: tags, content: "")
    }

    // MARK: - Sync NIP-65 Relays

    /// ログインユーザーの Kind 10002 リレーリストを取得し、AppPreferences に反映する。
    ///
    /// write または readWrite 権限のリレーを `selectedRelays` と `mainRelay` に設定する。
    /// Android: `NostrRepository.syncNip65Relays()` に対応。
    func syncNip65Relays() async {
        guard let pubkey = prefs.publicKeyHex else {
            AppLogger.log("NIP65", "syncNip65Relays skipped — no pubkey")
            return
        }
        let relays = await fetchRelayList(pubkey: pubkey)
        AppLogger.log("NIP65", "Fetched \(relays.count) relays from Kind 10002")

        guard !relays.isEmpty else {
            AppLogger.log("NIP65", "No relays found — keeping current settings")
            return
        }

        // Save full NIP-65 relay list for explicit relay/settings surfaces, but do not
        // rewrite selectedRelays here. Rewriting selectedRelays during background sync
        // made later generic connect calls fan out to 10+ relays after startup.
        prefs.nip65Relays = relays

        let writeUrls = relays
            .filter { $0.permission == .write || $0.permission == .readWrite }
            .map(\.url)

        AppLogger.log("NIP65", "Synced NIP-65 metadata write=\(writeUrls.count) total=\(relays.count); selectedRelays unchanged")
    }

    // MARK: - Update Profile (Kind 0)

    /// プロフィールを Kind 0 メタデータイベントとして発行する。
    ///
    /// 空/nil フィールドは JSON に含めない。
    /// `display_name` が未設定の場合は `name` で補完する (Android 準拠)。
    /// Android: `NostrRepository.updateProfile()` に対応。
    ///
    /// - Parameter profile: 更新するプロフィール。
    func updateProfile(profile: UserProfile) async throws {
        var dict: [String: String] = [:]

        func addIfPresent(_ key: String, _ value: String?) {
            guard let v = value, !v.trimmingCharacters(in: .whitespaces).isEmpty else { return }
            dict[key] = v
        }

        func normalizeBirthdayForKind0(_ value: String?) -> String? {
            guard let raw = value?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
            // このアプリ内表示互換を優先して YYYY-MM-DD / MM-DD をそのまま保持する
            let full = raw.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil
            if full { return raw }
            let short = raw.range(of: #"^\d{2}-\d{2}$"#, options: .regularExpression) != nil
            if short { return raw }
            return nil
        }

        addIfPresent("name",         profile.name)
        addIfPresent("display_name", profile.displayName)
        addIfPresent("about",        profile.about)
        addIfPresent("picture",      profile.picture)
        addIfPresent("banner",       profile.banner)
        addIfPresent("nip05",        profile.nip05)
        addIfPresent("lud16",        profile.lud16)
        addIfPresent("website",      profile.website)
        let normalizedBirthday = normalizeBirthdayForKind0(profile.birthday)
        addIfPresent("birthday",      normalizedBirthday)
        // 互換性: 一部クライアントは birthdate を参照するため同値を出力
        addIfPresent("birthdate",     normalizedBirthday)
        addIfPresent("geohash",      profile.geohash)

        // display_name フォールバック: Android buildJsonObject の動作に合わせる
        if dict["display_name"] == nil, let name = dict["name"] {
            dict["display_name"] = name
        }

        let encoded = try JSONEncoder().encode(dict)
        guard let metadataJson = String(data: encoded, encoding: .utf8) else {
            throw BackupError.encodingFailed
        }
        try await publishEvent(kind: NostrKind.metadata, tags: [], content: metadataJson)
        // 即時キャッシュ更新 + グレース期間設定（リレーの古いデータによる上書きを防止）
        // Android: updateProfile() → cache.setCachedProfile() + grace period に対応。
        cacheProfile(profile)
        AppLogger.log("Backup", "updateProfile: cached + grace period set for \(profile.pubkey.prefix(16))…")
    }
}

// MARK: - Private Upload Helpers (Backup extension 専用)

extension NostrRepository {

    /// nostr.build NIP-96 マルチパートアップロード。
    private func backupUploadToNostrBuild(data: Data, mimeType: String) async throws -> String {
        let endpoint = "https://nostr.build/api/v2/nip96/upload"
        var request  = try backupBuildMultipartRequest(endpoint: endpoint, data: data, mimeType: mimeType)
        backupNip98Auth(request: &request, url: endpoint, method: "POST")

        let (resp, http) = try await URLSession.shared.data(for: request)
        guard (http as? HTTPURLResponse)?.statusCode == 200 else { throw UploadError.serverError }

        struct R: Decodable {
            struct Nip94: Decodable { let tags: [[String]] }
            let nip94_event: Nip94?
            let url: String?
        }
        let decoded = try JSONDecoder().decode(R.self, from: resp)
        if let u = decoded.url { return u }
        if let u = decoded.nip94_event?.tags.first(where: { $0.first == "url" })?[safe: 1] { return u }
        throw UploadError.noUrl
    }

    /// share.yabu.me NIP-96 マルチパートアップロード。
    private func backupUploadToYabuMe(data: Data, mimeType: String) async throws -> String {
        let endpoint = "https://share.yabu.me/api/v2/media"
        var request  = try backupBuildMultipartRequest(endpoint: endpoint, data: data, mimeType: mimeType)
        backupNip98Auth(request: &request, url: endpoint, method: "POST")

        let (resp, http) = try await URLSession.shared.data(for: request)
        guard (http as? HTTPURLResponse)?.statusCode == 200 else { throw UploadError.serverError }

        struct R: Decodable {
            struct Nip94: Decodable { let tags: [[String]] }
            let nip94_event: Nip94?
            let status: String?
            let url: String?
        }
        let decoded = try JSONDecoder().decode(R.self, from: resp)
        if let u = decoded.url { return u }
        if let u = decoded.nip94_event?.tags.first(where: { $0.first == "url" })?[safe: 1] { return u }
        throw UploadError.noUrl
    }

    /// Blossom 互換サーバーへ PUT アップロード。
    private func backupUploadToBlossom(data: Data, mimeType: String, baseUrl: String) async throws -> String {
        let endpoint = baseUrl + "/upload"
        guard let url = URL(string: endpoint) else { throw UploadError.serverError }

        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.httpBody   = data
        backupNip98Auth(request: &request, url: endpoint, method: "PUT")

        let (resp, http) = try await URLSession.shared.data(for: request)
        guard (http as? HTTPURLResponse)?.statusCode == 200 else { throw UploadError.serverError }

        struct R: Decodable { let url: String? }
        if let u = (try? JSONDecoder().decode(R.self, from: resp))?.url { return u }
        throw UploadError.noUrl
    }

    /// NIP-98 HTTP Auth ヘッダー (Kind 27235) を request に付与する。
    /// 署名に失敗した場合はヘッダーを付与しない (サイレント無視)。
    private func backupNip98Auth(request: inout URLRequest, url: String, method: String) {
        guard let event = try? signer.signEvent(
            kind:    27235,
            tags:    [["u", url], ["method", method]],
            content: ""
        ),
              let json = try? JSONEncoder().encode(event),
              let b64  = String(data: json.base64EncodedData(), encoding: .utf8)
        else { return }

        request.setValue("Nostr \(b64)", forHTTPHeaderField: "Authorization")
    }

    /// multipart/form-data リクエストを構築する。
    private func backupBuildMultipartRequest(
        endpoint: String,
        data: Data,
        mimeType: String
    ) throws -> URLRequest {
        guard let url = URL(string: endpoint) else { throw UploadError.serverError }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"

        let boundary = "NuruNuruBackup-\(UUID().uuidString)"
        request.setValue(
            "multipart/form-data; boundary=\(boundary)",
            forHTTPHeaderField: "Content-Type"
        )

        let ext = mimeType == "image/png" ? "png" : "jpg"
        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.\(ext)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        return request
    }
}

// MARK: - ImportResult

/// イベントインポート処理の結果サマリー。
/// Android の `ImportResult` data class に対応。
struct ImportResult {
    /// 処理対象イベント総数。
    let total: Int
    /// 正常に発行できたイベント数。
    let success: Int
    /// 発行に失敗したイベント数。
    let failed: Int
    /// NIP-70 保護等によりスキップされたイベント数。
    let skipped: Int

    /// 成功率 (0.0〜1.0)。total が 0 の場合は 1.0 を返す。
    var successRate: Double {
        guard total > 0 else { return 1.0 }
        return Double(success) / Double(total)
    }
}

// MARK: - BackupError

enum BackupError: LocalizedError {
    case encodingFailed

    var errorDescription: String? {
        switch self {
        case .encodingFailed: return "データのエンコードに失敗しました"
        }
    }
}
