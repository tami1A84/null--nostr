import SwiftUI

/// Shared account/security block for the Home settings screen.
/// PR #1 introduces the component without moving the Mini Apps call sites yet.
struct AccountSecuritySettingsView: View {
    let pubkeyHex: String
    let authViewModel: AuthViewModel
    let repository: NostrRepository
    let prefs: AppPreferences
    var onLogout: () -> Void = {}

    @Environment(\.nuruTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase

    @State private var securityExpanded: Bool = false
    @State private var autoSignEnabled:  Bool = true
    @State private var showNsec:         Bool = false
    @State private var exportedNsec:     String? = nil
    @State private var isExportingNsec:  Bool = false

    @State private var rustFfiMlsEncryptionState: Bool? = nil
    @State private var rustFfiGroupCount:          Int? = nil
    @State private var rustFfiSelfUpdateCount:     Int? = nil
    @State private var rustFfiGroupStatus:         String = "unavailable"
    @State private var rustFfiSelfUpdateStatus:    String = "unavailable"
    @State private var rustFfiCheckedAt:           Date? = nil
    @State private var rustFfiDiagnosticLoaded:    Bool = false
    @State private var rustFfiDiagnosticLoading:   Bool = false
    @State private var rustFfiKeygenEnabled:       Bool = true
    @State private var rustFfiSigningEnabled:      Bool = false
    @State private var rustFfiPublishEnabled:      Bool = false
    @State private var showLogout:                 Bool = false

    private var npub: String { NostrKeyUtils.shortenPubkey(pubkeyHex, chars: 8) }

    var body: some View {
        VStack(spacing: NuruSpacing.space4) {
            profileCard
            securitySection
            if prefs.isExternalSigner {
                legacyExternalSignerNotice
            }
        }
        .task {
            autoSignEnabled = prefs.autoSignEnabled
            rustFfiKeygenEnabled = prefs.iosRustFfiKeygenEnabled
            rustFfiSigningEnabled = prefs.iosRustFfiSigningEnabled
            rustFfiPublishEnabled = prefs.iosRustFfiPublishEnabled
            await loadRustFfiDiagnostic()
        }
        .onChange(of: scenePhase) { _, phase in
            // Passkey / Nosskey export presents ASAuthorization UI, which may
            // temporarily move the app scene out of active. Do not clear the
            // pending nsec state while biometric/passkey authentication is in
            // progress, or the result returns with exportedNsec set but the
            // display collapsed.
            if phase != .active && !isExportingNsec { clearExportedNsec() }
        }
        .onDisappear { clearExportedNsec() }
        .alert("ログアウト", isPresented: $showLogout) {
            Button("ログアウト", role: .destructive) { onLogout() }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("ログアウトします。秘密鍵はこのデバイスから削除されます。")
        }
    }

    private func clearExportedNsec() {
        exportedNsec = nil
        showNsec = false
        isExportingNsec = false
    }

    private var profileCard: some View {
        HStack(spacing: NuruSpacing.space3) {
            ZStack {
                Circle().fill(NuruColors.lineGreen).frame(width: 40, height: 40)
                Image(systemName: NuruIcons.lock).font(.system(size: 20)).foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("ログイン中")
                    .font(NuruFont.bodyMedium()).fontWeight(.bold).foregroundStyle(theme.textPrimary).lineLimit(1)
                Text(npub).font(NuruFont.bodySmall()).foregroundStyle(theme.textTertiary).lineLimit(1)
            }
            Spacer()
            Button { showLogout = true } label: {
                Text("ログアウト")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Color.red)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.red.opacity(0.1))
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(NuruSpacing.space4)
        .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl).fill(theme.bgSecondary))
    }

    private var legacyExternalSignerNotice: some View {
        HStack(spacing: NuruSpacing.space3) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(NuruColors.colorWarning)
                .frame(width: 32, height: 32)
                .background(theme.bgTertiary)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text("Nostr Connectログインは終了しました")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(theme.textPrimary)
                Text("パスキー、またはnsecでログインし直してください。")
                    .font(.system(size: 12))
                    .foregroundStyle(theme.textTertiary)
                    .lineLimit(2)
            }
            Spacer()
        }
        .padding(NuruSpacing.space4)
        .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl).fill(theme.bgSecondary))
    }

    private var rustFfiDiagnosticSection: some View {
        HStack(spacing: NuruSpacing.space3) {
            Image(systemName: "checkmark.shield")
                .font(.system(size: 20))
                .foregroundStyle(rustFfiDiagnosticColor)
                .frame(width: 32, height: 32)
                .background(rustFfiDiagnosticColor.opacity(0.12))
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text("Rust FFI診断").font(NuruFont.bodyMedium()).fontWeight(.semibold).foregroundStyle(theme.textPrimary)
                Text(rustFfiDiagnosticMessage).font(NuruFont.bodySmall()).foregroundStyle(theme.textTertiary).lineLimit(4)
            }
            Spacer()
            Button { Task { await loadRustFfiDiagnostic() } } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(theme.textTertiary)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .disabled(rustFfiDiagnosticLoading)
            .opacity(rustFfiDiagnosticLoading ? 0.4 : 1.0)
        }
        .padding(NuruSpacing.space4)
        .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl).fill(theme.bgSecondary))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Rust FFI診断")
        .accessibilityValue(rustFfiDiagnosticMessage)
    }

    private var rustFfiDiagnosticMessage: String {
        if rustFfiDiagnosticLoading { return "確認中…" }
        guard rustFfiDiagnosticLoaded else { return "確認中…" }
        switch rustFfiMlsEncryptionState {
        case .some(true):
            let groupText = rustFfiCountText(label: "グループ", count: rustFfiGroupCount, status: rustFfiGroupStatus)
            let updateText = rustFfiCountText(label: "更新待ち", count: rustFfiSelfUpdateCount, status: rustFfiSelfUpdateStatus)
            let checkedText = rustFfiCheckedAt.map { " / \(Self.rustFfiTimeFormatter.string(from: $0))確認" } ?? ""
            return "MLS DB: 暗号化済み / \(groupText) / \(updateText)\(checkedText)"
        case .some(false): return "MLS DB: 未暗号化 — Talkを停止中"
        case .none: return "Rust FFI: 未接続"
        }
    }

    private var rustFfiDiagnosticColor: Color {
        guard rustFfiDiagnosticLoaded else { return theme.textTertiary }
        switch rustFfiMlsEncryptionState {
        case .some(true): return NuruColors.lineGreen
        case .some(false): return Color.red
        case .none: return theme.textTertiary
        }
    }

    private static let rustFfiTimeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "ja_JP")
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    private func rustFfiCountText(label: String, count: Int?, status: String) -> String {
        if let count { return "\(label) \(count)件" }
        switch status {
        case "unavailable": return "\(label) 未接続"
        case "failed": return "\(label) 確認失敗"
        default: return "\(label) 未確認"
        }
    }

    private func loadRustFfiDiagnostic() async {
        rustFfiDiagnosticLoading = true
        let snapshot = await repository.mlsReadOnlyDiagnosticSnapshot()
        rustFfiMlsEncryptionState = snapshot.encryptionState
        rustFfiGroupCount = snapshot.groupCount
        rustFfiSelfUpdateCount = snapshot.groupsNeedingSelfUpdateCount
        rustFfiGroupStatus = snapshot.groupCountStatus
        rustFfiSelfUpdateStatus = snapshot.selfUpdateStatus
        rustFfiCheckedAt = snapshot.checkedAt
        rustFfiDiagnosticLoaded = true
        rustFfiDiagnosticLoading = false
        let label = snapshot.encryptionState.map { $0 ? "encrypted" : "plaintext" } ?? "unavailable"
        let groupLabel = snapshot.groupCount.map(String.init) ?? snapshot.groupCountStatus
        let updateLabel = snapshot.groupsNeedingSelfUpdateCount.map(String.init) ?? snapshot.selfUpdateStatus
        AppLogger.log("FFI", "Phase 1.2 MLS read-only diagnostic: state=\(label), groups=\(groupLabel), selfUpdate=\(updateLabel)")
    }

    private var rustFfiWritePathSection: some View {
        VStack(spacing: NuruSpacing.space3) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Rust FFI書き込み").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.textPrimary)
                    Text("keygenは既定ON。署名/投稿はQA用の段階ロールアウトです。")
                        .font(.system(size: 12)).foregroundStyle(theme.textTertiary).lineLimit(2)
                }
                Spacer()
            }
            rustFfiToggleRow(title: "Rust keygen", subtitle: "新規nsec作成をRust優先", isOn: $rustFfiKeygenEnabled) { prefs.iosRustFfiKeygenEnabled = $0 }
            rustFfiToggleRow(title: "Rust signing", subtitle: "nsec投稿/NIP-98/NIP-44をRust signerへ", isOn: $rustFfiSigningEnabled) { prefs.iosRustFfiSigningEnabled = $0 }
            rustFfiToggleRow(title: "Rust publish", subtitle: "署名済みJSONをRust経由で送信", isOn: $rustFfiPublishEnabled) { prefs.iosRustFfiPublishEnabled = $0 }
        }
        .padding(NuruSpacing.space3)
        .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg).fill(theme.bgTertiary))
    }

    private func rustFfiToggleRow(title: String, subtitle: String, isOn: Binding<Bool>, onChange: @escaping (Bool) -> Void) -> some View {
        HStack(spacing: NuruSpacing.space3) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(theme.textPrimary)
                Text(subtitle).font(.system(size: 11)).foregroundStyle(theme.textTertiary).lineLimit(2)
            }
            Spacer()
            Toggle("", isOn: isOn).tint(NuruColors.lineGreen).labelsHidden().onChange(of: isOn.wrappedValue) { _, value in onChange(value) }
        }
    }

    private var securitySection: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: NuruSpacing.durationFast)) { securityExpanded.toggle() }
            } label: {
                HStack(spacing: NuruSpacing.space3) {
                    Image(systemName: NuruIcons.lock).font(.system(size: 20)).foregroundStyle(theme.textSecondary)
                    Text("セキュリティ設定").font(.system(size: 14, weight: .semibold)).foregroundStyle(theme.textPrimary)
                    Spacer()
                    Image(systemName: securityExpanded ? "chevron.up" : "chevron.down").foregroundStyle(theme.textTertiary)
                }
                .padding(NuruSpacing.space4)
            }
            .buttonStyle(.plain)

            if securityExpanded {
                VStack(spacing: NuruSpacing.space4) {
                    rustFfiDiagnosticSection
                    rustFfiToggleRow(
                        title: "Rust Talk MLS",
                        subtitle: prefs.iosRustFfiTalkMlsEnabled ? "MLS publish/subscriptionをRust経由" : "Talk MLSは従来経路",
                        isOn: Binding(get: { prefs.iosRustFfiTalkMlsEnabled }, set: { prefs.iosRustFfiTalkMlsEnabled = $0 }),
                        onChange: { prefs.iosRustFfiTalkMlsEnabled = $0 }
                    )
                    rustFfiWritePathSection

                    HStack(spacing: NuruSpacing.space3) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("自動署名").font(.system(size: 14, weight: .medium)).foregroundStyle(theme.textPrimary)
                            Text(autoSignEnabled ? "投稿時に認証なし" : "毎回認証を要求").font(.system(size: 12)).foregroundStyle(theme.textTertiary)
                        }
                        Spacer()
                        Toggle("", isOn: $autoSignEnabled)
                            .tint(NuruColors.lineGreen)
                            .labelsHidden()
                            .onChange(of: autoSignEnabled) { _, val in prefs.autoSignEnabled = val }
                    }
                    .padding(NuruSpacing.space3)
                    .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg).fill(theme.bgTertiary))

                    Button {
                        if showNsec {
                            clearExportedNsec()
                        } else {
                            showNsec = true
                            isExportingNsec = true
                            Task {
                                let nsec = await authViewModel.getNsecForCurrentAccount()
                                await MainActor.run {
                                    exportedNsec = nsec
                                    showNsec = true
                                    isExportingNsec = false
                                }
                            }
                        }
                    } label: {
                        Text(showNsec ? "秘密鍵を隠す" : "秘密鍵を表示")
                            .font(.system(size: 14))
                            .foregroundStyle(theme.textPrimary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, NuruSpacing.space3)
                            .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg).fill(theme.bgTertiary))
                    }
                    .buttonStyle(.plain)

                    if showNsec { nsecDisplay }
                }
                .padding(.horizontal, NuruSpacing.space4)
                .padding(.bottom, NuruSpacing.space4)
            }
        }
        .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl).fill(theme.bgSecondary))
    }

    private var nsecDisplay: some View {
        let nsec = isExportingNsec ? "取得中…" : (exportedNsec ?? "取得できません")
        return VStack(spacing: NuruSpacing.space2) {
            VStack(alignment: .leading, spacing: 4) {
                Text("⚠️ 警告: 秘密鍵の取り扱い").font(.system(size: 10, weight: .bold)).foregroundStyle(Color.red)
                Text("この鍵はあなたの身元を証明する唯一の手段です。他人に教えたり、安全でない場所に保存したりしないでください。")
                    .font(.system(size: 10)).foregroundStyle(Color.red.opacity(0.8)).lineSpacing(2)
            }
            .padding(NuruSpacing.space3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg).fill(Color.red.opacity(0.1)))

            HStack(spacing: NuruSpacing.space2) {
                Text(nsec)
                    .font(.system(size: 12))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(1)
                    .privacySensitive()
                Spacer()
                Button { UIPasteboard.general.string = nsec } label: {
                    Image(systemName: "doc.on.doc").font(.system(size: 16)).foregroundStyle(theme.textSecondary)
                }
                .buttonStyle(.plain)
            }
            .padding(NuruSpacing.space3)
            .background(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg).fill(theme.bgTertiary))
        }
    }
}
