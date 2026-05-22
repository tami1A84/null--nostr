import Foundation
import Observation

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

    // MARK: - Dependencies

    let keyManager: SecureKeyManager
    let prefs: AppPreferences
    let externalSigner: ExternalSigner

    // MARK: - Init

    init(keyManager: SecureKeyManager = SecureKeyManager(),
         prefs: AppPreferences = AppPreferences(),
         externalSigner: ExternalSigner = ExternalSigner()) {
        self.keyManager = keyManager
        self.prefs = prefs
        self.externalSigner = externalSigner
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
            state = .loggedIn(pubkeyHex: pubkey)
            return
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
        prefs.publicKeyHex = pubkeyHex
        prefs.isExternalSigner = false
        state = .loggedIn(pubkeyHex: pubkeyHex)
    }

    // MARK: - NIP-46 Nostr Connect

    /// Connect to a remote signer via bunker:// URI.
    /// Returns the user's public key hex on success.
    func connectExternalSigner(uri: String) async throws -> String {
        let pubkeyHex = try await externalSigner.connect(uri: uri)
        return pubkeyHex
    }

    /// Complete login after successful NIP-46 connection.
    func loginWithExternalSigner(pubkeyHex: String) {
        prefs.publicKeyHex = pubkeyHex
        prefs.isExternalSigner = true
        state = .loggedIn(pubkeyHex: pubkeyHex)
    }

    // MARK: - Deep Link Login

    /// Handle `nurunuru://login?nsec=<nsec1...>` deep link.
    func handleDeepLink(url: URL) {
        guard url.scheme == "nurunuru" else { return }
        switch url.host {
        case "login":
            guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
                  let nsec = components.queryItems?.first(where: { $0.name == "nsec" })?.value else {
                return
            }
            login(nsecOrHex: nsec)
        default:
            break
        }
    }

    // MARK: - Logout

    func logout() {
        Task {
            await externalSigner.disconnect()
        }
        // Issue #181: drop the per-pubkey SQLCipher key from Keychain
        // *before* `prefs.clear()` wipes the pubkey we need to scope it.
        // Internal-signer path is deterministic (HKDF over nsec) so no
        // explicit key removal is needed there — `keyManager.deleteAll()`
        // already drops the nsec, which is the root secret.
        if let pubkey = prefs.publicKeyHex, prefs.isExternalSigner {
            MlsDbKeyStore.clearExternalKey(pubkeyHex: pubkey)
        }
        keyManager.deleteAll()
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

            let repo = NostrRepository(
                keyManager: tempKeyManager,
                prefs: tempPrefs
            )

            // 接続 — 一時リポジトリの client に直接接続
            await repo.client.connect(relayUrls: targetRelayUrls)
            try await Task.sleep(nanoseconds: 1_500_000_000)

            // プロフィール発行
            let pubkeyHex = keyManager.getStoredPublicKeyHex() ?? ""
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

    // MARK: - Key Export (for settings screen)

    func getNsecTemporary() -> String? {
        guard let hex = keyManager.getKeyHexTemporary() else { return nil }
        guard let privBytes = NostrKeyUtils.hexToBytes(hex) else { return nil }
        return NostrKeyUtils.encodeNsec(privBytes)
    }
}
