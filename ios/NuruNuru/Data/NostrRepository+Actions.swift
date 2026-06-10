import Foundation

/// Action メソッド群 — 投稿・リアクション・フォロー・ミュートなどユーザー操作に対応する。
/// Android の NostrRepositoryActions.kt に相当。
///
/// 制約:
///   - 140 文字制限は UI 層 (PostSheet) で強制。Repository は制限を課さない。
///   - NIP-70 protection: nip70Protected = true のとき ["-"] タグを付与。
///   - Passkey/Nosskey: Rustで未署名イベントを作り、platform signerで署名してpublish_raw_event。
extension NostrRepository {

    // MARK: - Publish Note (Kind 1)

    /// kind-1 テキストノートを発行する。
    ///
    /// - Parameters:
    ///   - content:        本文 (140 文字制限は PostSheet で強制済み)。
    ///   - replyToId:      返信先イベント ID (nil = 新規投稿)。
    ///   - replyToPubkey:  返信先イベントの作者 pubkey。NIP-10 の p タグとして必須。
    ///   - contentWarning: CW ラベル文字列 (nil = なし)。
    ///   - customTags:     追加タグ (NIP-71 imeta 等)。
    ///   - targetRelays:   特定リレーのみに送出する場合に指定 (nil = 全リレー)。
    ///   - nip70Protected: true のとき ["-"] タグ (NIP-70) を付与。
    @discardableResult
    func publishNote(
        content:        String,
        replyToId:      String?    = nil,
        replyToPubkey:  String?    = nil,
        contentWarning: String?    = nil,
        customTags:     [[String]] = [],
        targetRelays:   [String]?  = nil,
        nip70Protected: Bool       = false
    ) async throws -> NostrEvent {
        var tags: [[String]] = []
        if let id = replyToId { tags.append(["e", id, "", "reply"]) }
        if let pk = replyToPubkey?.trimmingCharacters(in: .whitespacesAndNewlines), !pk.isEmpty {
            tags.append(["p", pk])
        }
        if let cw = contentWarning, !cw.isEmpty { tags.append(["content-warning", cw]) }
        if nip70Protected { tags.append(["-"]) }
        // クライアント識別タグ (iOS 版は "ぬるぬるiOS" を付与)
        if !tags.contains(where: { $0.first == "client" }) {
            tags.append(["client", "ぬるぬるiOS"])
        }
        for tag in customTags {
            guard let name = tag.first else { continue }
            if name == "p", tag.count >= 2 {
                let pk = tag[1]
                if !tags.contains(where: { $0.first == "p" && $0[safe: 1] == pk }) {
                    tags.append(tag)
                }
            } else {
                tags.append(tag)
            }
        }
        let event = try await publishEventAndReturnSigned(kind: NostrKind.textNote, tags: tags, content: content)

        // Keep the compose UX fast: return after the first normal relay ACK, then
        // fan out to explicitly selected relays / the parent author's NIP-65 read
        // relays in the background.  Waiting for relay-list fetches and every
        // targeted publish made replies feel stuck after tapping 投稿.
        var fanoutRelays = targetRelays ?? []
        if let replyToPubkey = replyToPubkey?.trimmingCharacters(in: .whitespacesAndNewlines), !replyToPubkey.isEmpty {
            let readRelays = await fetchRelayList(pubkey: replyToPubkey)
                .filter { $0.permission == .read || $0.permission == .readWrite }
                .map(\.url)
            fanoutRelays.append(contentsOf: readRelays)
        }
        let uniqueFanoutRelays = Array(Set(fanoutRelays)).filter { !$0.isEmpty }
        if !uniqueFanoutRelays.isEmpty,
           let data = try? JSONEncoder().encode(event),
           let rawJson = String(data: data, encoding: .utf8) {
            Task {
#if NURUNURU_FFI_AVAILABLE
                try? await self.publishSignedRawEventJSON(rawJson, to: uniqueFanoutRelays)
#else
                try? await client.publishRawEventJSON(rawJson, to: uniqueFanoutRelays)
#endif
            }
        }
        return event
    }

    // MARK: - Follow List (Kind 3)

    /// フォローリストを上書き発行する (kind 3)。
    func publishFollowList(follows: [String]) async throws {
        var seen = Set<String>()
        let tags: [[String]] = follows.compactMap { pk in
            guard seen.insert(pk).inserted else { return nil }
            return ["p", pk]
        }
        try await publishEvent(kind: NostrKind.contactList, tags: tags, content: "")
    }

    /// ユーザーをフォローする (kind-3 contact list 追加)。
    /// NIP-02 の kind 3 は「差分」ではなく置換可能な完全リストなので、
    /// 最新の既存フォローリストを取得し、対象 pubkey を追加した全体を発行する。
    func followUser(targetPubkeyHex: String) async throws {
        let myPubkey = prefs.publicKeyHex ?? ""
        guard !myPubkey.isEmpty else { return }
        let latest = await latestContactListEvent(pubkey: myPubkey)
        let fallbackTags = await fetchFollowList(pubkey: myPubkey).map { ["p", $0] }
        var tags = latest?.tags ?? fallbackTags
        guard !tags.contains(where: { $0.first == "p" && $0[safe: 1] == targetPubkeyHex }) else {
            cache.setCachedFollowList(pubkey: myPubkey, list: contactPubkeys(from: tags))
            return
        }
        tags.append(["p", targetPubkeyHex])
        try await publishEvent(kind: NostrKind.contactList, tags: dedupContactTags(tags), content: latest?.content ?? "")
        cache.setCachedFollowList(pubkey: myPubkey, list: contactPubkeys(from: tags))
    }

    /// ユーザーをアンフォローする (kind-3 contact list 削除)。
    func unfollowUser(targetPubkeyHex: String) async throws {
        let myPubkey = prefs.publicKeyHex ?? ""
        guard !myPubkey.isEmpty else { return }
        let latest = await latestContactListEvent(pubkey: myPubkey)
        let fallbackTags = await fetchFollowList(pubkey: myPubkey).map { ["p", $0] }
        let baseTags = latest?.tags ?? fallbackTags
        let tags = baseTags.filter { !($0.first == "p" && $0[safe: 1] == targetPubkeyHex) }
        try await publishEvent(kind: NostrKind.contactList, tags: dedupContactTags(tags), content: latest?.content ?? "")
        cache.setCachedFollowList(pubkey: myPubkey, list: contactPubkeys(from: tags))
    }

    private func latestContactListEvent(pubkey: String) async -> NostrEvent? {
        let filter = NostrFilter(authors: [pubkey], kinds: [NostrKind.contactList], limit: 5)
        let events = await fetchEvents(filters: [filter], timeoutSeconds: 4.0)
        return events.filter { $0.kind == NostrKind.contactList }.max(by: { $0.createdAt < $1.createdAt })
    }

    private func contactPubkeys(from tags: [[String]]) -> [String] {
        var seen = Set<String>()
        return tags.compactMap { tag in
            guard tag.first == "p", let pk = tag[safe: 1], seen.insert(pk).inserted else { return nil }
            return pk
        }
    }

    private func dedupContactTags(_ tags: [[String]]) -> [[String]] {
        var seenP = Set<String>()
        return tags.filter { tag in
            guard tag.first == "p", let pk = tag[safe: 1] else { return true }
            return seenP.insert(pk).inserted
        }
    }

    // MARK: - Delete (Kind 5, NIP-09)

    /// イベントを削除する (kind 5, NIP-09)。
    func publishDelete(eventId: String) async throws {
        let tags: [[String]] = [["e", eventId]]
        try await publishEvent(kind: NostrKind.deletion, tags: tags, content: "削除")
    }

    /// NIP-62 Vanish Request (kind 62) — 全リレーまたは指定リレーへの削除依頼。
    func requestVanish(relays: [String]?, reason: String) async throws {
        var tags: [[String]] = []
        if let relays, !relays.isEmpty {
            relays.forEach { tags.append(["relay", $0]) }
        } else {
            prefs.selectedRelays.forEach { tags.append(["relay", $0]) }
        }
        try await publishEvent(kind: NostrKind.vanishRequest, tags: tags, content: reason)
    }

    // MARK: - Reactions (Kind 7, NIP-25 / Kind 6, NIP-18)

    /// リアクションを発行する (kind 7, NIP-25)。
    ///
    /// - Parameters:
    ///   - eventId:      リアクション対象のイベント ID。
    ///   - authorPubkey: 対象イベントの作者 pubkey。
    ///   - emoji:        絵文字またはカスタム絵文字 (デフォルト "+")。
    @discardableResult
    func publishReaction(to eventId: String, authorPubkey: String, content: String = "+", emojiUrl: String? = nil) async throws -> NostrEvent {
        var tags: [[String]] = [["e", eventId], ["p", authorPubkey]]
        // NIP-30 カスタム絵文字: ":shortcode:" 形式の content + ["emoji", shortcode, url] タグ
        if let url = emojiUrl, content.hasPrefix(":") && content.hasSuffix(":") {
            let shortcode = String(content.dropFirst().dropLast())
            tags.append(["emoji", shortcode, url])
        }
        return try await publishEventAndReturnSigned(kind: NostrKind.reaction, tags: tags, content: content)
    }

    /// リポストを発行する (kind 6, NIP-18)。
    @discardableResult
    func publishRepostAndReturn(event: NostrEvent) async throws -> NostrEvent {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let eventJSON = (try? encoder.encode(event)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
        let tags: [[String]] = [["e", event.id], ["p", event.pubkey]]
        return try await publishEventAndReturnSigned(kind: NostrKind.repost, tags: tags, content: eventJSON)
    }

    /// リポストを発行する (kind 6, NIP-18)。
    func publishRepost(event: NostrEvent) async throws {
        _ = try await publishRepostAndReturn(event: event)
    }

    // MARK: - Mute List (Kind 10000, NIP-51)

    // MARK: NIP-44 ヘルパー（内部用）

    /// NIP-44 v2 暗号化。
    /// 優先順位: InternalSigner (純 Swift ChaCha20) → Rust FFI → 失敗時プレーンテキスト返却。
    /// プレーンテキストを返す場合は呼び出し元で content="" として発行しないこと。
    private func nip44Encrypt(_ plaintext: String, recipientPubkeyHex: String) -> String {
        // 1. 純 Swift 実装（Phase 1 〜）
        if let encrypted = signer.nip44Encrypt(recipientPubkeyHex: recipientPubkeyHex, plaintext: plaintext) {
            return encrypted
        }
        // 2. 暗号化失敗 — 呼び出し元でハンドリング
        return plaintext
    }

    /// NIP-44 v2 復号。
    /// 優先順位: InternalSigner (純 Swift ChaCha20) → Rust FFI → nil。
    private func nip44Decrypt(_ ciphertext: String, senderPubkeyHex: String) -> String? {
        // 1. 純 Swift 実装
        if let decrypted = signer.nip44Decrypt(senderPubkeyHex: senderPubkeyHex, ciphertext: ciphertext) {
            return decrypted
        }
        return nil
    }

    // MARK: Fetch

    /// Kind 10000 ミュートリストを取得する。
    /// - Returns: (publicMutes: 公開ミュート pubkey 一覧, privateMutes: NIP-44 復号した非公開ミュート pubkey 一覧)
    func fetchMuteList(pubkeyHex: String) async -> (publicMutes: [String], privateMutes: [String]) {
        let filter = NostrFilter(authors: [pubkeyHex], kinds: [NostrKind.muteList], limit: 1)
        let events = await fetchEvents(filters: [filter], timeoutSeconds: 5)
        guard let latest = events.max(by: { $0.createdAt < $1.createdAt }) else {
            return cache.getCachedMuteList(pubkey: pubkeyHex).map {
                (publicMutes: $0.0, privateMutes: [])
            } ?? (publicMutes: [], privateMutes: [])
        }

        // Public mutes — plaintext "p" tags
        let publicMutes = latest.tags.filter { $0.first == "p" }.compactMap { $0.dropFirst().first }

        // Private mutes — NIP-44 encrypted content
        var privateMutes: [String] = []
        if !latest.content.isEmpty,
           let decrypted = nip44Decrypt(latest.content, senderPubkeyHex: pubkeyHex) {
            if let data = decrypted.data(using: .utf8),
               let tagArray = try? JSONDecoder().decode([[String]].self, from: data) {
                privateMutes = tagArray.filter { $0.first == "p" }.compactMap { $0.dropFirst().first }
            }
        }

        // キャッシュ更新（公開ミュートのみ保存）
        cache.setCachedMuteList(pubkey: pubkeyHex, pubkeys: publicMutes, keywords: [])
        return (publicMutes: publicMutes, privateMutes: privateMutes)
    }

    // MARK: Publish

    /// ミュートリストを上書き発行する (Kind 10000)。
    /// - Parameters:
    ///   - publicMutes:  公開ミュート pubkey リスト（"p" タグとして平文発行）
    ///   - privateMutes: 非公開ミュート pubkey リスト（NIP-44 暗号化して content に格納）
    ///   - keywords:     ミュートキーワード（"word" タグとして平文発行）
    func updateMuteList(
        publicMutes:  [String],
        privateMutes: [String],
        keywords:     [String] = []
    ) async throws {
        let myPubkey = prefs.publicKeyHex ?? ""

        // Public mutes → "p" tags（平文）
        var publicTags: [[String]] = publicMutes.map { ["p", $0] }
        // Keywords → "word" tags（平文）
        publicTags += keywords.map { ["word", $0] }

        // Private mutes → NIP-44 暗号化 content
        let encryptedContent: String
        if privateMutes.isEmpty {
            encryptedContent = ""
        } else {
            let privateTags: [[String]] = privateMutes.map { ["p", $0] }
            if let data = try? JSONEncoder().encode(privateTags),
               let json = String(data: data, encoding: .utf8) {
                let encrypted = nip44Encrypt(json, recipientPubkeyHex: myPubkey)
                encryptedContent = encrypted
            } else {
                encryptedContent = ""
            }
        }

        try await publishEvent(kind: NostrKind.muteList, tags: publicTags, content: encryptedContent)
        cache.setCachedMuteList(pubkey: myPubkey, pubkeys: publicMutes, keywords: keywords)
    }

    /// 後方互換: キーワードミュートを含む従来のミュートリスト発行。
    func publishMuteList(pubkeys: [String], keywords: [String]) async throws {
        let tags: [[String]] = pubkeys.map { ["p", $0] } + keywords.map { ["word", $0] }
        try await publishEvent(kind: NostrKind.muteList, tags: tags, content: "")
    }

    // MARK: muteUser / unmuteUser

    /// ユーザーをミュートリストに追加する (NIP-51 Kind 10000)。
    /// - Parameters:
    ///   - pubkeyHex: ミュート対象の pubkey (hex)
    ///   - isPrivate: true = NIP-44 暗号化 content に格納（非公開）、false = "p" タグ（公開）
    func muteUser(pubkeyHex: String, isPrivate: Bool = true) async throws {
        let myPubkey = prefs.publicKeyHex ?? ""
        let current  = await fetchMuteList(pubkeyHex: myPubkey)
        guard !current.publicMutes.contains(pubkeyHex),
              !current.privateMutes.contains(pubkeyHex) else { return }

        if isPrivate {
            try await updateMuteList(
                publicMutes:  current.publicMutes,
                privateMutes: current.privateMutes + [pubkeyHex]
            )
        } else {
            try await updateMuteList(
                publicMutes:  current.publicMutes + [pubkeyHex],
                privateMutes: current.privateMutes
            )
        }
    }

    /// ユーザーをミュートリストから削除する (NIP-51 Kind 10000)。
    ///
    /// 公開・非公開どちらにも含まれていない場合は何もしない（冪等）。
    /// - Parameter pubkeyHex: ミュート解除対象の pubkey (hex)
    func unmuteUser(pubkeyHex: String) async throws {
        let myPubkey = prefs.publicKeyHex ?? ""
        let current  = await fetchMuteList(pubkeyHex: myPubkey)

        let newPublic  = current.publicMutes.filter  { $0 != pubkeyHex }
        let newPrivate = current.privateMutes.filter { $0 != pubkeyHex }

        // 変化がなければ発行しない（冪等）
        guard newPublic.count  != current.publicMutes.count ||
              newPrivate.count != current.privateMutes.count else { return }

        try await updateMuteList(publicMutes: newPublic, privateMutes: newPrivate)
    }

    // MARK: - FFI: Unsigned Event Signing

    /// 未署名イベント JSON (Rust FFI `create_unsigned_*` が返す形式) に内部署名を施して返す。
    ///
    /// - 内部サイナー: `InternalSigner` で秘密鍵による Schnorr 署名。
    /// - Passkey/Nosskey: platform signer側で署名する。iOS NIP-46 signerは廃止済み。
    ///
    /// - Parameter unsignedJson: Rust FFI `create_unsigned_*` が生成した未署名イベント JSON。
    /// - Returns: 署名済みイベント JSON (リレーに発行可能な形式)。
    func signUnsignedEventJson(_ unsignedJson: String) throws -> String {
        struct UnsignedEvent: Decodable {
            let pubkey:    String
            let createdAt: Int64
            let kind:      Int
            let tags:      [[String]]
            let content:   String
            enum CodingKeys: String, CodingKey {
                case pubkey, kind, tags, content
                case createdAt = "created_at"
            }
        }
        guard let data     = unsignedJson.data(using: .utf8),
              let unsigned = try? JSONDecoder().decode(UnsignedEvent.self, from: data) else {
            throw InternalSigner.SignerError.keyNotUnlocked
        }
        let signedEvent = try signer.signEvent(
            kind:      unsigned.kind,
            tags:      unsigned.tags,
            content:   unsigned.content,
            createdAt: unsigned.createdAt)
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        guard let signedData = try? encoder.encode(signedEvent),
              let signedJson = String(data: signedData, encoding: .utf8) else {
            throw InternalSigner.SignerError.keyNotUnlocked
        }
        return signedJson
    }
}