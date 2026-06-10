import Foundation

/// 通知を表示する送信者の範囲。
enum NotificationSenderScope: String, CaseIterable, Identifiable {
    case all
    case following
    case network

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: return "全員"
        case .following: return "フォロー中のみ"
        case .network: return "ネットワーク"
        }
    }

    var description: String {
        switch self {
        case .all: return "すべてのユーザーからの通知を表示"
        case .following: return "フォローしているユーザーからの通知のみ表示"
        case .network: return "フォローしている人がフォローしているユーザーまで表示"
        }
    }
}

/// UserDefaults-backed application preferences.
/// Private keys are NEVER stored here — use SecureKeyManager.
/// Mirrors Android AppPreferences (non-sensitive fields only).
final class AppPreferences {

    private let defaults = UserDefaults.standard

    private enum Keys {
        static let publicKeyHex       = "nurunuru_pubkey_hex"
        static let isExternalSigner   = "nurunuru_is_external_signer"
        static let selectedRelays     = "nurunuru_relays"
        static let biometricEnabled   = "nurunuru_biometric_enabled"
        static let mainRelay          = "nurunuru_main_relay"
        static let defaultZapAmount   = "nurunuru_default_zap_amount"
        static let autoSignEnabled    = "nurunuru_auto_sign_enabled"
        static let uploadServer       = "nurunuru_upload_server"
        static let customUploadServers = "nurunuru_custom_upload_servers"
        static let elevenLabsApiKey   = "nurunuru_elevenlabs_api_key"
        static let elevenLabsLanguage = "nurunuru_elevenlabs_language"
        static let favoriteApps       = "nurunuru_favorite_apps"
        static let newsSources        = "nurunuru_news_sources"
        static let externalApps       = "nurunuru_external_apps"
        static let userLat            = "nurunuru_user_lat"
        static let userLon            = "nurunuru_user_lon"
        static let userGeohash        = "nurunuru_user_geohash"
        static let selectedRegionId   = "nurunuru_selected_region_id"
        static let nip65Relays                    = "nurunuru_nip65_relays"
        static let notificationEnabledKinds       = "nurunuru_notification_enabled_kinds"
        static let notificationEmojiReactionEnabled = "nurunuru_notification_emoji_reaction_enabled"
        static let notificationSenderScope          = "nurunuru_notification_sender_scope"
        static let notificationKnownFollowerPubkeys = "nurunuru_notification_known_follower_pubkeys"
        static let notificationFollowLastSeenAt     = "nurunuru_notification_follow_last_seen_at"
        static let hiddenMlsGroupIds = "nurunuru_hidden_mls_group_ids"
        static let mlsJoinedAtByGroupId = "nurunuru_mls_joined_at_by_group_id"
        static let mlsSelfUpdateCompletedAtByGroupId = "nurunuru_mls_self_update_completed_at_by_group_id"
        static let mlsKeyPackageEventJsonById = "nurunuru_mls_keypackage_event_json_by_id"
        static let mlsKeyPackageHashRefById = "nurunuru_mls_keypackage_hash_ref_by_id"
        static let mlsConsumedKeyPackageEventIds = "nurunuru_mls_consumed_keypackage_event_ids"
        static let mlsKeyPackageStableDTag = "nurunuru_mls_keypackage_stable_d_tag_v1"
        static let mlsRejectedWelcomeRetryAfterById = "nurunuru_mls_rejected_welcome_retry_after_by_id_v2"
        static let mlsKeyPackageRelays = "nurunuru_mls_key_package_relays"
        static let mlsInboxRelays = "nurunuru_mls_inbox_relays"
        static let hasAcceptedTerms = "nurunuru_has_accepted_terms"
        static let loginMethod    = "nurunuru_login_method"
        static let pendingReferralPubkeyHex = "nurunuru_pending_referral_pubkey_hex"
        static let iosRustFfiKeygenEnabled = "nurunuru_ios_rust_ffi_keygen_enabled"
        static let iosRustFfiSigningEnabled = "nurunuru_ios_rust_ffi_signing_enabled"
        static let iosRustFfiPublishEnabled = "nurunuru_ios_rust_ffi_publish_enabled"
        static let iosRustFfiTalkMlsEnabled = "nurunuru_ios_rust_ffi_talk_mls_enabled"
    }


    /// iOS Rust FFI key generation rollout flag. Default ON: falls back to Swift keygen on failure.
    var iosRustFfiKeygenEnabled: Bool {
        get { defaults.object(forKey: Keys.iosRustFfiKeygenEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.iosRustFfiKeygenEnabled) }
    }

    /// iOS Rust FFI internal signing rollout flag. Phase 7 default ON; toggle remains for fallback.
    var iosRustFfiSigningEnabled: Bool {
        get { defaults.object(forKey: Keys.iosRustFfiSigningEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.iosRustFfiSigningEnabled) }
    }

    /// iOS Rust FFI raw publish rollout flag. Phase 7 default ON; toggle remains for fallback.
    var iosRustFfiPublishEnabled: Bool {
        get { defaults.object(forKey: Keys.iosRustFfiPublishEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.iosRustFfiPublishEnabled) }
    }

    /// iOS Rust FFI Talk MLS rollout flag. Phase 7 default ON after Android/iOS interop QA.
    var iosRustFfiTalkMlsEnabled: Bool {
        get { defaults.object(forKey: Keys.iosRustFfiTalkMlsEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.iosRustFfiTalkMlsEnabled) }
    }

    /// Active authentication backend for the current session.
    /// Valid values: `"nsec"`, `"nosskey"`, `"external"`, or `nil` (logged out).
    /// Stored as plain string (non-sensitive metadata only).
    var loginMethod: String? {
        get { defaults.string(forKey: Keys.loginMethod) }
        set {
            if let value = newValue { defaults.set(value, forKey: Keys.loginMethod) }
            else { defaults.removeObject(forKey: Keys.loginMethod) }
        }
    }

    var hasAcceptedTerms: Bool {
        get { defaults.bool(forKey: Keys.hasAcceptedTerms) }
        set { defaults.set(newValue, forKey: Keys.hasAcceptedTerms) }
    }

    var pendingReferralPubkeyHex: String? {
        get { defaults.string(forKey: Keys.pendingReferralPubkeyHex) }
        set {
            if let value = newValue, !value.isEmpty { defaults.set(value.lowercased(), forKey: Keys.pendingReferralPubkeyHex) }
            else { defaults.removeObject(forKey: Keys.pendingReferralPubkeyHex) }
        }
    }

    var publicKeyHex: String? {
        get { defaults.string(forKey: Keys.publicKeyHex) }
        set { defaults.set(newValue, forKey: Keys.publicKeyHex) }
    }

    var isExternalSigner: Bool {
        get { defaults.bool(forKey: Keys.isExternalSigner) }
        set { defaults.set(newValue, forKey: Keys.isExternalSigner) }
    }

    var selectedRelays: [String] {
        get { defaults.stringArray(forKey: Keys.selectedRelays) ?? defaultRelays }
        set { defaults.set(newValue, forKey: Keys.selectedRelays) }
    }

    var mainRelay: String {
        get { defaults.string(forKey: Keys.mainRelay) ?? defaultRelays[0] }
        set { defaults.set(newValue, forKey: Keys.mainRelay) }
    }

    /// Marmot/WhiteNoise MLS KeyPackage discovery relays (kind:10051 + key package publish targets).
    /// Users may change these independently from general NIP-65 relays.
    var mlsKeyPackageRelays: [String] {
        get { defaults.stringArray(forKey: Keys.mlsKeyPackageRelays) ?? [] }
        set { defaults.set(newValue, forKey: Keys.mlsKeyPackageRelays) }
    }

    /// NIP-17/MLS inbox relays (kind:10050) where peers should send Welcomes/DM gift-wraps.
    var mlsInboxRelays: [String] {
        get { defaults.stringArray(forKey: Keys.mlsInboxRelays) ?? [] }
        set { defaults.set(newValue, forKey: Keys.mlsInboxRelays) }
    }

    var biometricEnabled: Bool {
        get { defaults.bool(forKey: Keys.biometricEnabled) }
        set { defaults.set(newValue, forKey: Keys.biometricEnabled) }
    }

    var defaultZapAmount: Int {
        get { defaults.integer(forKey: Keys.defaultZapAmount).nonZero ?? 21 }
        set { defaults.set(newValue, forKey: Keys.defaultZapAmount) }
    }

    var autoSignEnabled: Bool {
        get { defaults.object(forKey: Keys.autoSignEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.autoSignEnabled) }
    }

    /// Raw string（UserDefaults 保存用）。型安全な操作には `uploadServerEnum` を推奨。
    var uploadServer: String {
        // Default to Blossom (nostr.build) per BUD-03-oriented upload settings.
        get { defaults.string(forKey: Keys.uploadServer) ?? UploadServer.defaultBlossomUrl }
        set { defaults.set(newValue, forKey: Keys.uploadServer) }
    }

    /// `uploadServer` は enum rawValue だけでなく、Blossom のベース URL
    /// (例: https://blossom.nostr.build) も保存される。URL 形式なら Blossom として扱う。
    var uploadServerEnum: UploadServer {
        get {
            if let typed = UploadServer(rawValue: uploadServer) { return typed }
            return .blossom
        }
        set { uploadServer = newValue.rawValue }
    }

    var blossomUploadBaseUrl: String {
        let raw = uploadServer.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.hasPrefix("http://") || raw.hasPrefix("https://") { return raw }
        return UploadServer.defaultBlossomUrl
    }

    // User-defined upload destination base URLs.
    var customUploadServers: [String] {
        get {
            guard let data = defaults.data(forKey: Keys.customUploadServers),
                  let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
            return list
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.customUploadServers)
            }
        }
    }

    var elevenLabsApiKey: String {
        get { defaults.string(forKey: Keys.elevenLabsApiKey) ?? "" }
        set { defaults.set(newValue, forKey: Keys.elevenLabsApiKey) }
    }

    var elevenLabsLanguage: String {
        get { defaults.string(forKey: Keys.elevenLabsLanguage) ?? "jpn" }
        set { defaults.set(newValue, forKey: Keys.elevenLabsLanguage) }
    }

    /// Public news source pubkeys for the News tab. Non-sensitive user preference.
    var newsSources: [String] {
        get {
            guard let data = defaults.data(forKey: Keys.newsSources),
                  let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
            return list
        }
        set {
            let unique = Array(NSOrderedSet(array: newValue)) as? [String] ?? newValue
            if let data = try? JSONEncoder().encode(unique) { defaults.set(data, forKey: Keys.newsSources) }
        }
    }

    /// IDs of favourite mini-apps (shown in マイミニアプリ horizontal row).
    var favoriteApps: [String] {
        get {
            guard let data = defaults.data(forKey: Keys.favoriteApps),
                  let list = try? JSONDecoder().decode([String].self, from: data) else { return [] }
            return list
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) { defaults.set(data, forKey: Keys.favoriteApps) }
        }
    }

    /// JSON-encoded external mini-app list.
    var externalApps: String {
        get { defaults.string(forKey: Keys.externalApps) ?? "[]" }
        set { defaults.set(newValue, forKey: Keys.externalApps) }
    }

    var userLat: Double {
        get { defaults.double(forKey: Keys.userLat) }
        set { defaults.set(newValue, forKey: Keys.userLat) }
    }

    var userLon: Double {
        get { defaults.double(forKey: Keys.userLon) }
        set { defaults.set(newValue, forKey: Keys.userLon) }
    }

    var userGeohash: String? {
        get { defaults.string(forKey: Keys.userGeohash) }
        set { defaults.set(newValue, forKey: Keys.userGeohash) }
    }

    var selectedRegionId: String? {
        get { defaults.string(forKey: Keys.selectedRegionId) }
        set { defaults.set(newValue, forKey: Keys.selectedRegionId) }
    }

    /// NIP-65 relay list stored as JSON array of `{url, read, write}`.
    var nip65Relays: [Nip65Relay] {
        get {
            guard let data = defaults.data(forKey: Keys.nip65Relays),
                  let list = try? JSONDecoder().decode([Nip65Relay].self, from: data) else { return [] }
            return list
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) { defaults.set(data, forKey: Keys.nip65Relays) }
        }
    }

    /// 通知で有効な Kind のセット (Android: notificationEnabledKinds)
    /// デフォルト: reaction(7), zapReceipt(9735), repost(6), textNote(1), contactList(3), badgeAward(8)
    var notificationEnabledKinds: Set<Int> {
        get {
            guard let data = defaults.data(forKey: Keys.notificationEnabledKinds),
                  let arr = try? JSONDecoder().decode([Int].self, from: data) else {
                return [NostrKind.reaction, NostrKind.zapReceipt, NostrKind.repost,
                        NostrKind.textNote, NostrKind.contactList, NostrKind.badgeAward]
            }
            return Set(arr)
        }
        set {
            if let data = try? JSONEncoder().encode(Array(newValue)) {
                defaults.set(data, forKey: Keys.notificationEnabledKinds)
            }
        }
    }

    /// 絵文字リアクション通知の有効/無効 (Android: notificationEmojiReactionEnabled)
    var notificationEmojiReactionEnabled: Bool {
        get { defaults.object(forKey: Keys.notificationEmojiReactionEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.notificationEmojiReactionEnabled) }
    }

    /// 通知の送信者フィルタ（全員 / フォロー中 / ネットワーク）。
    var notificationSenderScope: NotificationSenderScope {
        get {
            NotificationSenderScope(rawValue: defaults.string(forKey: Keys.notificationSenderScope) ?? "") ?? .all
        }
        set { defaults.set(newValue.rawValue, forKey: Keys.notificationSenderScope) }
    }

    /// フォロー通知の重複抑止用: 既知フォロワー pubkey。
    var notificationKnownFollowerPubkeys: Set<String> {
        get { Set(defaults.stringArray(forKey: Keys.notificationKnownFollowerPubkeys) ?? []) }
        set { defaults.set(Array(newValue), forKey: Keys.notificationKnownFollowerPubkeys) }
    }

    /// フォロー通知の重複抑止用: 処理済み Kind 3 の最大 created_at。
    var notificationFollowLastSeenAt: Int64 {
        get { Int64(defaults.integer(forKey: Keys.notificationFollowLastSeenAt)) }
        set { defaults.set(Int(newValue), forKey: Keys.notificationFollowLastSeenAt) }
    }

    /// Hidden/stale MLS group IDs (persisted across app relaunch).
    var hiddenMlsGroupIds: Set<String> {
        get {
            guard let arr = defaults.stringArray(forKey: Keys.hiddenMlsGroupIds) else { return [] }
            return Set(arr)
        }
        set {
            defaults.set(Array(newValue), forKey: Keys.hiddenMlsGroupIds)
        }
    }

    /// Per-group join timestamp (unix seconds) used for MIP-02 self-update 24h requirement.
    var mlsJoinedAtByGroupId: [String: Int64] {
        get {
            guard let data = defaults.data(forKey: Keys.mlsJoinedAtByGroupId),
                  let map = try? JSONDecoder().decode([String: Int64].self, from: data) else { return [:] }
            return map
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.mlsJoinedAtByGroupId)
            }
        }
    }

    /// Per-group self-update completion timestamp (unix seconds).
    var mlsSelfUpdateCompletedAtByGroupId: [String: Int64] {
        get {
            guard let data = defaults.data(forKey: Keys.mlsSelfUpdateCompletedAtByGroupId),
                  let map = try? JSONDecoder().decode([String: Int64].self, from: data) else { return [:] }
            return map
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.mlsSelfUpdateCompletedAtByGroupId)
            }
        }
    }

    /// Locally published KeyPackage event JSONs keyed by event id (hex).
    /// Used to ensure MIP-02 consumed-keypackage deletion/rotation even when relay fetch misses.
    var mlsKeyPackageEventJsonById: [String: String] {
        get {
            guard let data = defaults.data(forKey: Keys.mlsKeyPackageEventJsonById),
                  let map = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
            return map
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.mlsKeyPackageEventJsonById)
            }
        }
    }

    /// Locally published KeyPackage hash_ref bytes keyed by signed event id (hex).
    /// This lets MIP-02 cleanup delete the exact local init-key material after Welcome accept.
    var mlsKeyPackageHashRefById: [String: [UInt8]] {
        get {
            guard let data = defaults.data(forKey: Keys.mlsKeyPackageHashRefById),
                  let map = try? JSONDecoder().decode([String: [UInt8]].self, from: data) else { return [:] }
            return map
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.mlsKeyPackageHashRefById)
            }
        }
    }

    /// Stable d tag for Marmot kind:30443 KeyPackage.
    /// Keep exactly one replaceable KeyPackage slot per install/account so debug reinstall
    /// churn does not leave hundreds of active KPs on relays.
    var mlsKeyPackageStableDTag: String? {
        get { defaults.string(forKey: Keys.mlsKeyPackageStableDTag) }
        set {
            if let newValue, !newValue.isEmpty { defaults.set(newValue, forKey: Keys.mlsKeyPackageStableDTag) }
            else { defaults.removeObject(forKey: Keys.mlsKeyPackageStableDTag) }
        }
    }

    /// KeyPackage event ids already consumed by MLS Welcome processing.
    /// Relay deletion is best-effort, so this local set is the source of truth
    /// for avoiding KeyPackage reuse after app relaunch.
    var mlsConsumedKeyPackageEventIds: Set<String> {
        get {
            guard let arr = defaults.stringArray(forKey: Keys.mlsConsumedKeyPackageEventIds) else { return [] }
            return Set(arr)
        }
        set {
            defaults.set(Array(newValue), forKey: Keys.mlsConsumedKeyPackageEventIds)
        }
    }

    /// Rejected Welcome event retry gate (event id -> next retry unix seconds).
    /// Persisted so concurrent repository instances / app lifecycle churn do not
    /// repeatedly unwrap/process the same invalid or non-matching kind:1059 event.
    var mlsRejectedWelcomeRetryAfterById: [String: Int64] {
        get {
            guard let data = defaults.data(forKey: Keys.mlsRejectedWelcomeRetryAfterById),
                  let map = try? JSONDecoder().decode([String: Int64].self, from: data) else { return [:] }
            return map
        }
        set {
            if let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Keys.mlsRejectedWelcomeRetryAfterById)
            }
        }
    }

    // MARK: - Cache Settings (per-type enable/TTL — mirrors Android AppPreferences)

    /// キャッシュ種別ごとの有効/無効フラグ。デフォルト: true（有効）。
    func isCacheEnabled(_ typeId: String) -> Bool {
        defaults.object(forKey: "nurunuru_cache_\(typeId)_enabled") as? Bool ?? true
    }

    func setCacheEnabled(_ typeId: String, _ enabled: Bool) {
        defaults.set(enabled, forKey: "nurunuru_cache_\(typeId)_enabled")
    }

    /// キャッシュ種別ごとの TTL（ミリ秒）。未設定時は `defaultMs` を返す。
    func getCacheTtlMs(_ typeId: String, _ defaultMs: Int) -> Int {
        let v = defaults.integer(forKey: "nurunuru_cache_\(typeId)_ttl")
        return v > 0 ? v : defaultMs
    }

    func setCacheTtlMs(_ typeId: String, _ ttlMs: Int) {
        defaults.set(ttlMs, forKey: "nurunuru_cache_\(typeId)_ttl")
    }

    func clear() {
        [Keys.publicKeyHex,
         Keys.isExternalSigner,
         Keys.selectedRelays,
         Keys.biometricEnabled,
         Keys.mainRelay,
         Keys.defaultZapAmount,
         Keys.autoSignEnabled,
         Keys.uploadServer,
         Keys.customUploadServers,
         Keys.favoriteApps,
         Keys.externalApps,
         Keys.hiddenMlsGroupIds,
         Keys.mlsJoinedAtByGroupId,
         Keys.mlsSelfUpdateCompletedAtByGroupId,
         Keys.mlsKeyPackageEventJsonById,
         Keys.mlsKeyPackageHashRefById,
         Keys.mlsConsumedKeyPackageEventIds,
         Keys.mlsKeyPackageStableDTag,
         Keys.mlsRejectedWelcomeRetryAfterById,
         Keys.mlsKeyPackageRelays,
         Keys.mlsInboxRelays,
         Keys.loginMethod].forEach { defaults.removeObject(forKey: $0) }
    }
}

private extension Int {
    var nonZero: Int? { self == 0 ? nil : self }
}
