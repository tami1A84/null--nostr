import Foundation
import Observation
#if NURUNURU_FFI_AVAILABLE
import NuruNuruFFILib
#endif

struct ReferralInvitePreview {
    let pubkeyHex: String
    var profile: UserProfile?
    var isLoading: Bool
}

/// Authentication state and login logic.
/// Mirrors Android AuthViewModel / AuthState.
/// Uses @Observable (iOS 17 Observation framework — no Combine/ObservableObject).
@Observable
final class AuthViewModel {

    // MARK: - State

    enum State: Equatable {
        case checking
        case loggedOut
        case loggedIn(pubkeyHex: String)
        case error(String)

        static func == (lhs: State, rhs: State) -> Bool {
            switch (lhs, rhs) {
            case (.checking, .checking), (.loggedOut, .loggedOut): return true
            case (.loggedIn(let a), .loggedIn(let b)): return a == b
            case (.error(let a), .error(let b)): return a == b
            default: return false
            }
        }
    }

    private(set) var state: State = .checking
    private(set) var referralInvitePreview: ReferralInvitePreview?
    private(set) var openedProfilePubkey: String?
    private(set) var openedEventId: String?

    // MARK: - Dependencies

    let keyManager: SecureKeyManager
    let prefs: AppPreferences
    /// Passkey / nosskey "PRF direct" manager. Hidden behind `@MainActor` because
    /// `ASAuthorizationController` is UIKit-bound. Only used on iOS 18+.
    let nosskeyManager: NosskeyManager

    /// In-memory signer for the active nosskey session (if any). Re-created on
    /// each login so the PRF cache lifetime is per-session.
    private var nosskeySigner: NosskeySigner?
    private var pendingReferralFollowPubkey: String?

    // MARK: - Init

    init(keyManager: SecureKeyManager = SecureKeyManager(),
         prefs: AppPreferences = AppPreferences(),
         nosskeyManager: NosskeyManager? = nil) {
        self.keyManager = keyManager
        self.prefs = prefs
        // NosskeyManager.init is @MainActor — fall back to MainActor.assumeIsolated
        // when no instance is injected. We're called from the app start-up path
        // which is already main-thread.
        self.nosskeyManager = nosskeyManager ?? MainActor.assumeIsolated { NosskeyManager() }
        if let pending = self.prefs.pendingReferralPubkeyHex { setPendingReferralFollow(pending) }
        checkStoredLogin()
    }

    // MARK: - Startup

    /// ログイン状態を確認する。
    /// 非同期 Task ではなく同期的に実行し、state が .checking → .loggedIn に遷移するまでの
    /// ラグをなくす。これにより RootView の body 再評価が1回で済み、
    /// MainTabView の多重初期化を防止する。
    private func checkStoredLogin() {
        guard let pubkey = prefs.publicKeyHex else {
            state = .loggedOut
            return
        }

        if prefs.isExternalSigner {
            // ADR-0023: iOS NIP-46 / Nostr Connect signer support was removed.
            // Do not silently keep a legacy remote-signer session write-capable.
            // Keep the stored pubkey/flag until the user explicitly logs in again
            // or logs out, so migration copy can be shown safely.
            state = .error("Nostr Connectログインは終了しました。パスキー、またはnsecでログインし直してください。")
            return
        }

        // Nosskey / Passkey path: the secret is never on disk — every signing
        // operation re-prompts the user for Face ID / Touch ID. We only verify
        // that the credential metadata is still around, then mark logged in.
        if prefs.loginMethod == "nosskey" {
            let stored = MainActor.assumeIsolated { nosskeyManager.loadStoredKeyInfo() }
            if let info = stored {
                state = .loggedIn(pubkeyHex: info.pubkey)
                return
            }
            // Metadata vanished (e.g. user wiped UserDefaults) → fall through to
            // the standard logged-out path.
        }

        if keyManager.hasStoredKey() {
            if keyManager.unlockKey() {
                state = .loggedIn(pubkeyHex: pubkey)
            } else {
                state = .error("秘密鍵の読み込みに失敗しました")
            }
        } else {
            state = .loggedOut
        }
    }

    // MARK: - Login with nsec

    func login(nsecOrHex: String) {
        NostrRepository.resetSharedRustFfiForAccountSwitch()
        state = .checking

        Task {
            let trimmed = nsecOrHex.trimmingCharacters(in: .whitespacesAndNewlines)

            guard let privBytes = NostrKeyUtils.parsePrivateKey(trimmed) else {
                state = .error("秘密鍵の形式が正しくありません（nsec1... または64桁の16進数）")
                return
            }

            do {
                let pubBytes = try NostrKeyUtils.derivePublicKey(from: privBytes)
                let pubkeyHex = NostrKeyUtils.bytesToHex(pubBytes)

                try keyManager.storeKey(privateKeyBytes: privBytes, publicKeyHex: pubkeyHex)

                // Zero the local copy
                var mutablePriv = privBytes
                mutablePriv.withUnsafeMutableBufferPointer {
                    $0.baseAddress?.initialize(repeating: 0, count: privBytes.count)
                }

                prefs.publicKeyHex = pubkeyHex
                prefs.isExternalSigner = false
                state = .loggedIn(pubkeyHex: pubkeyHex)

            } catch {
                state = .error("ログインに失敗しました: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Sign Up (Generate New Account)

    struct GeneratedAccount {
        let pubkeyHex: String
        let nsec: String
        let npub: String
    }

    func generateNewAccount() async -> GeneratedAccount? {
#if NURUNURU_FFI_AVAILABLE
        if prefs.iosRustFfiKeygenEnabled {
            do {
                let generated = try generateKeypair()
                guard var privBytes = NostrKeyUtils.hexToBytes(generated.privateKeyHex),
                      privBytes.count == 32 else { throw SecureKeyManager.KeyError.invalidKeySize }
                defer {
                    let count = privBytes.count
                    privBytes.withUnsafeMutableBufferPointer {
                        $0.baseAddress?.initialize(repeating: 0, count: count)
                    }
                }
                try keyManager.storeKey(privateKeyBytes: privBytes, publicKeyHex: generated.publicKeyHex)
                AppLogger.log("FFI", "Rust keygen account created pubkey=\(String(generated.publicKeyHex.prefix(8)))…")
                return GeneratedAccount(pubkeyHex: generated.publicKeyHex, nsec: generated.nsec, npub: generated.npub)
            } catch {
                AppLogger.log("FFI", "Rust keygen failed; falling back to Swift keygen: \(error)")
            }
        }
#endif
        do {
            let (privBytes, pubBytes) = try NostrKeyUtils.generateKeys()
            let pubkeyHex = NostrKeyUtils.bytesToHex(pubBytes)
            let nsec = NostrKeyUtils.encodeNsec(privBytes) ?? ""
            let npub = NostrKeyUtils.encodeNpub(pubBytes) ?? ""

            try keyManager.storeKey(privateKeyBytes: privBytes, publicKeyHex: pubkeyHex)

            var mutablePriv = privBytes
            mutablePriv.withUnsafeMutableBufferPointer {
                $0.baseAddress?.initialize(repeating: 0, count: privBytes.count)
            }

            return GeneratedAccount(pubkeyHex: pubkeyHex, nsec: nsec, npub: npub)
        } catch {
            return nil
        }
    }

    func completeRegistration(pubkeyHex: String) {
        NostrRepository.resetSharedRustFfiForAccountSwitch()
        prefs.publicKeyHex = pubkeyHex
        prefs.isExternalSigner = false
        // If the active sign-up path was nsec, normalise loginMethod accordingly.
        // The Passkey path sets `loginMethod = "nosskey"` inside
        // `generateNewAccountWithPasskey` already; we don't overwrite it here.
        if prefs.loginMethod != "nosskey" {
            prefs.loginMethod = "nsec"
        }
        let referral = pendingReferralFollowPubkey
        state = .loggedIn(pubkeyHex: pubkeyHex)
        if let referral, referral != pubkeyHex {
            pendingReferralFollowPubkey = nil
            prefs.pendingReferralPubkeyHex = nil
            referralInvitePreview = nil
            Task { await followPendingReferral(myPubkeyHex: pubkeyHex, targetPubkeyHex: referral) }
        }
    }

    private func setPendingReferralFollow(_ raw: String?) {
        let hex = normalizedReferralPubkey(raw)
        pendingReferralFollowPubkey = hex
        prefs.pendingReferralPubkeyHex = hex
        if let hex { loadReferralInvitePreview(pubkeyHex: hex) }
        else { referralInvitePreview = nil }
    }

    private func normalizedReferralPubkey(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        if let bytes = NostrKeyUtils.parsePublicKey(raw) {
            return NostrKeyUtils.bytesToHex(bytes)
        }
        if let parsed = NostrBech32.decode(raw), parsed.type == .npub || parsed.type == .nprofile {
            return parsed.hex
        }
        if raw.count == 64 {
            return raw.lowercased()
        }
        return nil
    }

    func dismissReferralInvite() {
        pendingReferralFollowPubkey = nil
        prefs.pendingReferralPubkeyHex = nil
        referralInvitePreview = nil
    }

    private func loadReferralInvitePreview(pubkeyHex: String) {
        referralInvitePreview = ReferralInvitePreview(pubkeyHex: pubkeyHex, profile: nil, isLoading: true)
        Task {
            let tempPrefs = AppPreferences()
            tempPrefs.publicKeyHex = pubkeyHex
            tempPrefs.selectedRelays = prefs.selectedRelays.isEmpty ? defaultRelays : prefs.selectedRelays
            tempPrefs.mainRelay = tempPrefs.selectedRelays.first ?? "wss://yabu.me"
            let repo = NostrRepository(
                keyManager: keyManager,
                prefs: tempPrefs
            )
            await repo.client.connect(relayUrls: tempPrefs.selectedRelays)
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            let profile = await repo.fetchProfile(pubkey: pubkeyHex)
            await repo.client.disconnect()
            await MainActor.run {
                self.referralInvitePreview = ReferralInvitePreview(pubkeyHex: pubkeyHex, profile: profile, isLoading: false)
            }
        }
    }

    private func followPendingReferral(myPubkeyHex: String, targetPubkeyHex: String) async {
        do {
            let tempPrefs = AppPreferences()
            tempPrefs.publicKeyHex = myPubkeyHex
            tempPrefs.selectedRelays = prefs.selectedRelays.isEmpty ? defaultRelays : prefs.selectedRelays
            tempPrefs.mainRelay = tempPrefs.selectedRelays.first ?? "wss://yabu.me"
            let repo = NostrRepository(
                keyManager: keyManager,
                prefs: tempPrefs,
                signer: await currentSessionSigner()
            )
            await repo.client.connect(relayUrls: tempPrefs.selectedRelays)
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            try await repo.followUser(targetPubkeyHex: targetPubkeyHex)
            try? await Task.sleep(nanoseconds: 500_000_000)
            await repo.client.disconnect()
            AppLogger.log("Auth", "Referral follow applied: \(String(targetPubkeyHex.prefix(8)))")
        } catch {
            AppLogger.log("Auth", "Referral follow failed: \(error)")
        }
    }

    // MARK: - Sign Up via Passkey (nosskey "PRF Direct Method")

    /// Create a new Nostr account whose private key is the PRF output of a
    /// freshly registered Passkey. The secret is **never** persisted; on every
    /// subsequent signing operation it is re-derived via biometric assertion.
    ///
    /// Requires iOS 18+. Returns `nil` if the platform doesn't support PRF or
    /// the user cancels the system prompt.
    @MainActor
    func generateNewAccountWithPasskey(username: String) async -> GeneratedAccount? {
        guard NosskeyManager.isPlatformSupported else {
            state = .error("パスキー新規登録は iOS 18 以降が必要です")
            return nil
        }

        do {
            var creation = try await nosskeyManager.createPasskeyWithSecret(
                username: username,
                displayName: username.isEmpty ? "ぬるぬるユーザー" : username
            )
            let keyInfo = creation.keyInfo
            // Reuse the PRF secret produced during registration. This avoids the
            // previous 3-4 repeated biometric prompts (create → derive → warmCache).
            var secret = creation.secretKey
            defer {
                let secretCount = secret.count
                secret.withUnsafeMutableBufferPointer {
                    $0.baseAddress?.initialize(repeating: 0, count: secretCount)
                }
            }
            // The nsec/npub are returned to the UI primarily for the optional
            // settings-screen export; the sign-up wizard SKIPS the backup step
            // for nosskey users because the Passkey itself is the backup.
            let nsec = NostrKeyUtils.encodeNsec(secret) ?? ""
            let npub = NostrKeyUtils.encodeNpub(
                NostrKeyUtils.hexToBytes(keyInfo.pubkey) ?? []
            ) ?? ""

            // Persist credential metadata.
            nosskeyManager.saveKeyInfo(keyInfo)
            prefs.loginMethod = "nosskey"
            prefs.publicKeyHex = keyInfo.pubkey
            prefs.isExternalSigner = false

            // Build the session signer and seed its cache with the same secret.
            // This prevents extra prompts during immediate profile / relay-list /
            // tutorial publication.
            let signer = NosskeySigner(nosskeyManager: nosskeyManager, keyInfo: keyInfo)
            signer.primeCache(secret: secret)
            nosskeySigner = signer

            return GeneratedAccount(pubkeyHex: keyInfo.pubkey, nsec: nsec, npub: npub)
        } catch let err as NosskeyError {
            if case .userCancelled = err { return nil }
            state = .error(err.errorDescription ?? "パスキーの登録に失敗しました")
            return nil
        } catch {
            state = .error("パスキーの登録に失敗しました: \(error.localizedDescription)")
            return nil
        }
    }

    /// Verify a Passkey by performing a PRF assertion and move the session to
    /// loggedIn. If local metadata was lost by reinstall, use a discoverable
    /// assertion to restore credentialId / pubkey / salt from iCloud Keychain.
    @MainActor
    func loginWithPasskey() async -> Bool {
        guard NosskeyManager.isPlatformSupported else {
            state = .error("パスキーログインは iOS 18 以降が必要です")
            return false
        }

        state = .checking
        do {
            let keyInfo: NosskeyKeyInfo
            var secret: [UInt8]

            if let stored = nosskeyManager.loadStoredKeyInfo() {
                keyInfo = stored
                secret = try await nosskeyManager.deriveSecretKey(for: stored)
            } else {
                var restored = try await nosskeyManager.discoverPasskeyWithSecret()
                keyInfo = restored.keyInfo
                secret = restored.secretKey
                let restoredSecretCount = restored.secretKey.count
                restored.secretKey.withUnsafeMutableBufferPointer {
                    $0.baseAddress?.initialize(repeating: 0, count: restoredSecretCount)
                }
                nosskeyManager.saveKeyInfo(keyInfo)
            }

            defer {
                let secretCount = secret.count
                secret.withUnsafeMutableBufferPointer {
                    $0.baseAddress?.initialize(repeating: 0, count: secretCount)
                }
            }
            prefs.publicKeyHex = keyInfo.pubkey
            prefs.isExternalSigner = false
            prefs.loginMethod = "nosskey"

            let signer = NosskeySigner(nosskeyManager: nosskeyManager, keyInfo: keyInfo)
            signer.primeCache(secret: secret)
            nosskeySigner = signer

            state = .loggedIn(pubkeyHex: keyInfo.pubkey)
            return true
        } catch let err as NosskeyError {
            if case .userCancelled = err {
                state = .loggedOut
                return false
            }
            state = .error(err.errorDescription ?? "パスキーログインに失敗しました")
            return false
        } catch {
            state = .error("パスキーログインに失敗しました: \(error.localizedDescription)")
            return false
        }
    }

    /// Build / refresh a signer appropriate for the current session.
    /// Used by repositories created on the fly during sign-up.
    @MainActor
    func currentSessionSigner() async -> EventSigner? {
        if prefs.loginMethod == "nosskey",
           let keyInfo = nosskeyManager.loadStoredKeyInfo() {
            if let existing = nosskeySigner { return existing }
            let signer = NosskeySigner(nosskeyManager: nosskeyManager, keyInfo: keyInfo)
            try? await signer.warmCache()
            nosskeySigner = signer
            return signer
        }
        return nil  // caller will default to InternalSigner
    }

    // MARK: - Legacy Signer Migration

    /// Clear the old iOS NIP-46 session after the user has acknowledged the
    /// migration or chosen a new login path. This never creates a replacement
    /// account and never attempts to export remote-signer material.
    func clearLegacyExternalSignerSession() {
        if prefs.isExternalSigner {
            NostrRepository.resetSharedRustFfiForAccountSwitch()
            prefs.isExternalSigner = false
            prefs.publicKeyHex = nil
            state = .loggedOut
        }
    }

    // MARK: - Deep Link Login

    /// Handle `nurunuru://login?nsec=<nsec1...>` and profile referral links.
    func handleDeepLink(url: URL) {
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        if url.scheme == "nurunuru", url.host == "login" {
            guard let nsec = components?.queryItems?.first(where: { $0.name == "nsec" })?.value else { return }
            login(nsecOrHex: nsec)
        } else if (url.scheme == "nurunuru" && url.host == "profile") ||
                    ((url.scheme == "https" || url.scheme == "http") && url.host == "www.nullnull.app" && url.pathComponents.dropFirst().first == "p") {
            let raw = components?.queryItems?.first(where: { ["npub", "pubkey", "ref"].contains($0.name) })?.value
                ?? url.pathComponents.last
            if let hex = normalizedReferralPubkey(raw) {
                if case .loggedIn(let myPubkey) = state {
                    if hex != myPubkey { openedProfilePubkey = hex }
                } else {
                    setPendingReferralFollow(hex)
                }
            }
        } else if (url.scheme == "nurunuru" && url.host == "event") ||
                    ((url.scheme == "https" || url.scheme == "http") && url.host == "www.nullnull.app" && url.pathComponents.dropFirst().first == "e") {
            let raw = components?.queryItems?.first(where: { ["id", "event"].contains($0.name) })?.value
                ?? url.pathComponents.last
            if let raw, raw.range(of: "^[0-9a-fA-F]{64}$", options: .regularExpression) != nil,
               case .loggedIn = state {
                openedEventId = raw.lowercased()
            }
        }
    }

    func consumeOpenedProfilePubkey() {
        openedProfilePubkey = nil
    }

    func consumeOpenedEventId() {
        openedEventId = nil
    }

    // MARK: - Logout

    func logout() {
        // Issue #181: drop the per-pubkey SQLCipher key from Keychain
        // *before* `prefs.clear()` wipes the pubkey we need to scope it.
        // Internal-signer path is deterministic (HKDF over nsec) so no
        // explicit key removal is needed there — `keyManager.deleteAll()`
        // already drops the nsec, which is the root secret.
        if let pubkey = prefs.publicKeyHex, prefs.isExternalSigner || prefs.loginMethod == "nosskey" {
            MlsDbKeyStore.clearExternalKey(pubkeyHex: pubkey)
        }
        NostrRepository.resetSharedRustFfiForAccountSwitch()
        keyManager.deleteAll()
        // Do NOT clear NosskeyKeyInfo on logout. It is non-secret metadata
        // (credentialId/pubkey/salt) required for "パスキーでログイン" after logout.
        // The actual secret remains inside the Passkey and is re-derived only
        // after biometric authentication.
        nosskeySigner?.zeroizeCache()
        nosskeySigner = nil
        prefs.clear()
        state = .loggedOut
    }

    // MARK: - Error Handling

    func clearError() {
        if case .error = state { state = .loggedOut }
    }

    // MARK: - Publish Initial Metadata (Sign-Up)

    /// サインアップ時にプロフィール (Kind 0) とリレーリスト (Kind 10002) を発行する。
    /// Android: `AuthViewModel.publishInitialMetadata()` に対応。
    func publishInitialMetadata(
        name: String,
        about: String,
        picture: String = "",
        banner: String = "",
        nip05: String = "",
        lud16: String = "",
        website: String = "",
        birthday: String = "",
        relays: [Nip65Relay]? = nil
    ) async -> Bool {
        do {
            let targetRelayUrls = relays?.map(\.url) ?? [
                "wss://yabu.me",
                "wss://relay-jp.nostr.wirednet.jp",
                "wss://r.kojira.io"
            ]

            // 一時的な NostrClient + NostrRepository で発行
            let tempKeyManager = keyManager
            let tempPrefs = AppPreferences()
            tempPrefs.selectedRelays = targetRelayUrls
            tempPrefs.mainRelay = targetRelayUrls.first ?? "wss://yabu.me"
            tempPrefs.publicKeyHex = prefs.publicKeyHex

            // Pick the signer for the active session — nosskey when the user just
            // registered with Passkey, otherwise the default Keychain-backed one.
            let sessionSigner = await currentSessionSigner()
            let repo = NostrRepository(
                keyManager: tempKeyManager,
                prefs: tempPrefs,
                signer: sessionSigner
            )

            // 接続 — 一時リポジトリの client に直接接続
            await repo.client.connect(relayUrls: targetRelayUrls)
            try await Task.sleep(nanoseconds: 1_500_000_000)

            // プロフィール発行
            // nosskey 経路では keyManager に何も保存されていないため、署名側 (signer)
            // の pubkey を信頼する。nsec 経路はこれまで通り keyManager 経由。
            let pubkeyHex: String = {
                if prefs.loginMethod == "nosskey", let key = prefs.publicKeyHex { return key }
                return keyManager.getStoredPublicKeyHex() ?? ""
            }()
            let profile = UserProfile(
                pubkey: pubkeyHex,
                name: name,
                displayName: name,
                about: about,
                picture: picture,
                nip05: nip05,
                banner: banner,
                lud16: lud16,
                website: website,
                birthday: birthday
            )
            try await repo.updateProfile(profile: profile)

            // リレーリスト発行
            let relayList = relays ?? targetRelayUrls.map { Nip65Relay(url: $0, permission: .readWrite) }
            try await repo.updateRelayList(relays: relayList)

            try await Task.sleep(nanoseconds: 1_000_000_000)
            await repo.client.disconnect()
            return true
        } catch {
            AppLogger.log("Auth", "publishInitialMetadata failed: \(error)")
            return false
        }
    }

    // MARK: - Tutorial Post (Onboarding)

    /// オンボーディング最終段階で発行する「はじめての投稿」。
    /// Android `AuthViewModel.publishTutorialPost()` と Web `SignUpModal#handlePostTutorial` に対応。
    ///
    /// - 本文は UI 側 (`SignUpTutorialStep`) で既定として `\n#nostrはじめました` が
    ///   pre-fill されており、ユーザーがそのまま投稿すればハッシュタグ付きで送信される。
    /// - ユーザーが意図的にハッシュタグ行を削除した場合は、削除した状態のまま送信する。
    ///   この関数は本文への自動補完・末尾付与を一切行わない (「勝手に付けられた」を回避する規約)。
    /// - 本文中の `#xxx` のみを抽出して `t` タグを生成する (PostSheet と同一規約)。
    /// - `publishInitialMetadata` と同じく、サインアップ用に一時 `NostrClient` + `NostrRepository` を生成して送信する。
    /// - リレーが空の場合は default JP relay 3 つにフォールバック。
    /// - 140 文字超 / 空本文の場合は送信せず `false` を返す。
    ///
    /// - Returns: 送信成功時 `true`。
    @discardableResult
    func publishTutorialPost(
        content: String,
        relays: [Nip65Relay]? = nil
    ) async -> Bool {
        // 本文は UI 側で pre-fill 済み。trim せず原文をそのまま送信し、
        // pre-fill 由来の先頭改行 (本文 → 改行 → ハッシュタグの配置) を尊重する。
        let finalContent = content

        // 本文中の `#xxx` のみを抽出 (PostModal.kt / SignUpModal.js と同じ regex)。
        // ユーザーがハッシュタグを消していれば t タグも付かない (意図尊重)。
        let hashtagRegex = try? NSRegularExpression(
            pattern: #"#([\p{L}\p{N}_\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\uFF00-\uFFEF]+)"#
        )
        var foundTags: [String] = []
        var seen = Set<String>()
        if let regex = hashtagRegex {
            let nsRange = NSRange(finalContent.startIndex..., in: finalContent)
            for match in regex.matches(in: finalContent, range: nsRange) {
                guard match.numberOfRanges >= 2,
                      let range = Range(match.range(at: 1), in: finalContent) else { continue }
                let tag = String(finalContent[range]).lowercased()
                if seen.insert(tag).inserted { foundTags.append(tag) }
            }
        }

        guard finalContent.count <= 140 else {
            AppLogger.log("Auth", "publishTutorialPost rejected: content exceeds 140 chars")
            return false
        }
        guard !finalContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            AppLogger.log("Auth", "publishTutorialPost rejected: content is empty")
            return false
        }
        let customTags: [[String]] = foundTags.map { ["t", $0] }

        let targetRelayUrls: [String] = (relays?.map(\.url))
            ?? (prefs.selectedRelays.isEmpty
                ? ["wss://yabu.me", "wss://relay-jp.nostr.wirednet.jp", "wss://r.kojira.io"]
                : prefs.selectedRelays)

        do {
            let tempPrefs = AppPreferences()
            tempPrefs.selectedRelays = targetRelayUrls
            tempPrefs.mainRelay = targetRelayUrls.first ?? "wss://yabu.me"
            tempPrefs.publicKeyHex = prefs.publicKeyHex

            let sessionSigner = await currentSessionSigner()
            let repo = NostrRepository(
                keyManager: keyManager,
                prefs: tempPrefs,
                signer: sessionSigner
            )

            await repo.client.connect(relayUrls: targetRelayUrls)
            try await Task.sleep(nanoseconds: 1_500_000_000)

            do {
                _ = try await repo.publishNote(
                    content: finalContent,
                    customTags: customTags
                )
                try? await Task.sleep(nanoseconds: 800_000_000)
                await repo.client.disconnect()
                return true
            } catch {
                // 投稿失敗時もリレー接続を閉じる (リーク防止)。
                await repo.client.disconnect()
                throw error
            }
        } catch {
            AppLogger.log("Auth", "publishTutorialPost failed: \(error)")
            return false
        }
    }

    // MARK: - Key Export (for settings screen)

    func getNsecTemporary() -> String? {
        guard let hex = keyManager.getKeyHexTemporary() else { return nil }
        guard let privBytes = NostrKeyUtils.hexToBytes(hex) else { return nil }
        return NostrKeyUtils.encodeNsec(privBytes)
    }

    /// Export nsec for the active account. For Nosskey users this prompts for
    /// Passkey authentication, derives the PRF secret once, encodes it as nsec,
    /// then zeroizes the temporary bytes. This is intentionally explicit: it is
    /// the escape hatch for migration to other Nostr apps.
    @MainActor
    func getNsecForCurrentAccount() async -> String? {
        if prefs.loginMethod == "nosskey" {
            guard let keyInfo = nosskeyManager.loadStoredKeyInfo() else { return nil }
            do {
                var secret = try await nosskeyManager.deriveSecretKey(for: keyInfo)
                defer {
                    let secretCount = secret.count
                    secret.withUnsafeMutableBufferPointer {
                        $0.baseAddress?.initialize(repeating: 0, count: secretCount)
                    }
                }
                return NostrKeyUtils.encodeNsec(secret)
            } catch {
                AppLogger.log("Auth", "getNsecForCurrentAccount(nosskey) failed: \(error)")
                return nil
            }
        }
        return getNsecTemporary()
    }
}
