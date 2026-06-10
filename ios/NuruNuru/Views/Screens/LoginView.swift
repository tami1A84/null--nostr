import CoreLocation
import PhotosUI
import SwiftUI

/// Login screen — mirrors Android LoginScreen.kt pixel-for-pixel.
///
/// Flow:
///   1. Initial → shows 新規登録 / ログイン buttons
///   2. ログイン tapped → shows nsec input + ログインボタン
///   3. パスキーでログイン
///   4. 新規登録 → SignUpSheet
struct LoginView: View {

    @Environment(AuthViewModel.self) private var viewModel
    @Environment(\.nuruTheme) private var theme

    @State private var nsecInput          = ""
    @State private var showKey            = false
    @State private var showSignUp         = false
    @State private var showNsecLogin      = false
    @State private var showTermsAgreement = false
    @State private var pendingTermsAction: TermsStartAction?
    @State private var logoScale: CGFloat = 0.95

    private var isLoading: Bool {
        if case .checking = viewModel.state { return true }
        return false
    }

    private var errorMessage: String? {
        if case .error(let msg) = viewModel.state { return msg }
        return nil
    }

    var body: some View {
        ZStack {
            theme.bgPrimary.ignoresSafeArea()

            if isLoading && !showNsecLogin && !showSignUp {
                loadingView
            } else {
                mainContent
            }
        }
        .sheet(isPresented: $showSignUp) {
            SignUpSheet(isPresented: $showSignUp)
                .environment(viewModel)
        }
        .sheet(isPresented: $showTermsAgreement) {
            TermsAgreementSheet(
                onAgree: {
                    viewModel.prefs.hasAcceptedTerms = true
                    showTermsAgreement = false
                    performPendingTermsAction()
                },
                onCancel: {
                    showTermsAgreement = false
                    pendingTermsAction = nil
                }
            )
        }
    }

    // MARK: - Loading

    private var loadingView: some View {
        VStack(spacing: NuruSpacing.space5) {
            Image("logo")
                .resizable()
                .scaledToFill()
                .frame(width: 80, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radius2xl))
                .scaleEffect(logoScale)
                .onAppear {
                    withAnimation(
                        .easeInOut(duration: 1.0).repeatForever(autoreverses: true)
                    ) { logoScale = 1.05 }
                }
            Text("読み込み中...")
                .font(NuruFont.bodyMedium())
                .foregroundStyle(theme.textTertiary)
        }
    }

    // MARK: - Main Content

    private var mainContent: some View {
        VStack(spacing: NuruSpacing.space6) {
            Spacer()
            logoSection
            invitePreviewSection
            buttonSection
            Spacer()
            footerSection
        }
        .padding(.horizontal, NuruSpacing.space6)
    }

    // MARK: - Logo

    private var logoSection: some View {
        VStack(spacing: NuruSpacing.space4) {
            Image("logo")
                .resizable()
                .scaledToFill()
                .frame(width: 112, height: 112)
                .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radius2xl))
                .shadow(color: .black.opacity(0.3), radius: 12, x: 0, y: 4)

            Text("ぬるぬる")
                .font(NuruFont.displayLarge())
                .foregroundStyle(theme.textPrimary)
        }
    }

    // MARK: - Invite Preview

    @ViewBuilder
    private var invitePreviewSection: some View {
        if let invite = viewModel.referralInvitePreview {
            HStack(spacing: NuruSpacing.space3) {
                AsyncImage(url: URL(string: invite.profile?.picture ?? "")) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "person.fill")
                        .foregroundStyle(theme.textTertiary)
                }
                .frame(width: 48, height: 48)
                .clipShape(Circle())
                .background(theme.bgTertiary.clipShape(Circle()))

                VStack(alignment: .leading, spacing: NuruSpacing.space1) {
                    Text("招待されています")
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(NuruColors.lineGreen)
                    Text(invite.profile?.displayedName ?? NostrKeyUtils.shortenPubkey(invite.pubkeyHex))
                        .font(NuruFont.bodyMedium())
                        .foregroundStyle(theme.textPrimary)
                        .lineLimit(1)
                    Text(invite.isLoading ? "プロフィールを読み込み中..." : "はじめるとこのユーザーをフォローします")
                        .font(NuruFont.bodySmall())
                        .foregroundStyle(theme.textSecondary)
                        .lineLimit(2)
                }
                Spacer()
                Button { viewModel.dismissReferralInvite() } label: {
                    Image(systemName: "xmark")
                        .foregroundStyle(theme.textTertiary)
                }
            }
            .padding(NuruSpacing.space4)
            .frame(maxWidth: .infinity)
            .background(theme.bgSecondary)
            .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl))
        }
    }

    // MARK: - Buttons

    @ViewBuilder
    private var buttonSection: some View {
        if !showNsecLogin {
            // Initial state: 新規登録 + ログイン
            VStack(spacing: NuruSpacing.space4) {
                signUpButton
                if NosskeyManager.isPlatformSupported {
                    passkeyLoginButton
                }
                if let msg = errorMessage {
                    inlineErrorMessage(msg)
                    legacyExternalSignerMigrationButton
                }
                loginToggleButton
            }
        } else {
            // nsec login form
            nsecLoginForm
        }
    }

    private var signUpButton: some View {
        Button {
            requestTermsAgreement(for: .signUp)
        } label: {
            HStack(spacing: NuruSpacing.space3) {
                Image(systemName: "person.badge.plus")
                    .font(.system(size: 20))
                Text("新規登録")
                    .font(NuruFont.buttonLarge())
            }
            .frame(maxWidth: .infinity)
            .frame(height: 64)
        }
        .buttonStyle(NuruPrimaryButtonStyle())
        .shadow(color: NuruColors.lineGreen.opacity(0.25), radius: 8, x: 0, y: 4)
    }

    private var passkeyLoginButton: some View {
        Button {
            viewModel.clearError()
            requestTermsAgreement(for: .passkeyLogin)
        } label: {
            HStack(spacing: NuruSpacing.space3) {
                Image(systemName: "faceid")
                    .font(.system(size: 20))
                    .foregroundStyle(NuruColors.lineGreen)
                Text("パスキーでログイン")
                    .font(NuruFont.buttonMedium())
                    .foregroundStyle(NuruColors.lineGreen)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
        }
        .buttonStyle(NuruSecondaryButtonStyle(theme: theme))
    }

    private func inlineErrorMessage(_ msg: String) -> some View {
        Text(msg)
            .font(NuruFont.bodySmall())
            .foregroundStyle(NuruColors.colorError)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, NuruSpacing.space2)
            .transition(.opacity)
    }


    @ViewBuilder
    private var legacyExternalSignerMigrationButton: some View {
        if viewModel.prefs.isExternalSigner {
            Button {
                viewModel.clearLegacyExternalSignerSession()
            } label: {
                Text("旧ログイン情報を消してやり直す")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, NuruSpacing.space2)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("旧Nostr Connectログイン情報を消してやり直す")
        }
    }

    private var loginToggleButton: some View {
        Button {
            requestTermsAgreement(for: .login)
        } label: {
            HStack(spacing: NuruSpacing.space3) {
                Image(systemName: "arrow.right.circle")
                    .font(.system(size: 20))
                    .foregroundStyle(theme.textPrimary)
                Text("ログイン")
                    .font(NuruFont.buttonMedium())
                    .foregroundStyle(theme.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
        }
        .buttonStyle(NuruSecondaryButtonStyle(theme: theme))
    }


    // MARK: - Terms Agreement

    private enum TermsStartAction {
        case signUp
        case login
        case passkeyLogin
    }

    private func requestTermsAgreement(for action: TermsStartAction) {
        pendingTermsAction = action
        switch action {
        case .signUp:
            // 新規登録は毎回利用規約を表示する（同意済みでも再確認）
            showTermsAgreement = true
        case .login, .passkeyLogin:
            if viewModel.prefs.hasAcceptedTerms {
                performPendingTermsAction()
            } else {
                showTermsAgreement = true
            }
        }
    }

    private func performPendingTermsAction() {
        guard let action = pendingTermsAction else { return }
        pendingTermsAction = nil
        switch action {
        case .signUp:
            showSignUp = true
        case .login:
            withAnimation(.easeInOut(duration: NuruSpacing.durationNormal)) {
                showNsecLogin = true
            }
        case .passkeyLogin:
            Task { await viewModel.loginWithPasskey() }
        }
    }

    // MARK: - nsec Login Form

    private var nsecLoginForm: some View {
        VStack(spacing: NuruSpacing.space4) {

            // nsec input
            nsecTextField

            // Error message
            if let msg = errorMessage {
                Text(msg)
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(NuruColors.colorError)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, NuruSpacing.space2)
                    .transition(.opacity)
            }

            // Login button
            loginButton

            // Cancel
            Button("キャンセル") {
                withAnimation(.easeInOut(duration: NuruSpacing.durationNormal)) {
                    showNsecLogin = false
                    nsecInput = ""
                    viewModel.clearError()
                }
            }
            .font(NuruFont.bodyMedium())
            .foregroundStyle(theme.textTertiary)
        }
    }

    private var nsecTextField: some View {
        HStack {
            if showKey {
                TextField("nsec1...", text: $nsecInput)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: nsecInput) { _, _ in viewModel.clearError() }
            } else {
                SecureField("nsec1...", text: $nsecInput)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textPrimary)
                    .onChange(of: nsecInput) { _, _ in viewModel.clearError() }
            }

            Button {
                showKey.toggle()
            } label: {
                Image(systemName: showKey ? "eye.slash" : "eye")
                    .foregroundStyle(theme.textTertiary)
                    .font(.system(size: NuruSpacing.iconMd))
            }
            .accessibilityLabel(showKey ? "隠す" : "表示")
        }
        .padding(NuruSpacing.space4)
        .background(theme.bgSecondary)
        .cornerRadius(NuruSpacing.radiusXl)
        .overlay(
            RoundedRectangle(cornerRadius: NuruSpacing.radiusXl)
                .stroke(
                    errorMessage != nil ? NuruColors.colorError
                        : (nsecInput.isEmpty ? theme.borderColor : NuruColors.lineGreen),
                    lineWidth: 1.5
                )
        )
    }

    private var loginButton: some View {
        Button {
            viewModel.login(nsecOrHex: nsecInput)
        } label: {
            Group {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(.white)
                        .scaleEffect(0.8)
                } else {
                    Text("ログイン")
                        .font(NuruFont.buttonMedium())
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
        }
        .buttonStyle(NuruPrimaryButtonStyle(isDisabled: nsecInput.isEmpty || isLoading))
        .disabled(nsecInput.isEmpty || isLoading)
    }

    // MARK: - Footer

    private var footerSection: some View {
        HStack(spacing: NuruSpacing.space4) {
            Link("利用規約", destination: URL(string: "https://tami1a84.github.io/null--nostr/terms.html")!)
            Link("プライバシーポリシー", destination: URL(string: "https://tami1a84.github.io/null--nostr/privacy.html")!)
            Link("公式サイト", destination: URL(string: "https://tami1a84.github.io/null--nostr/")!)
        }
        .font(NuruFont.bodySmall())
        .foregroundStyle(theme.textTertiary)
        .padding(.bottom, NuruSpacing.space4)
    }
}


// MARK: - Terms Agreement Sheet

private struct TermsAgreementSheet: View {
    let onAgree: () -> Void
    let onCancel: () -> Void

    @Environment(\.nuruTheme) private var theme

    private let termsURL = URL(string: "https://tami1a84.github.io/null--nostr/terms.html")!

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: NuruSpacing.space4) {
                        Text("利用規約への同意")
                            .font(NuruFont.titleLarge())
                            .fontWeight(.bold)
                            .foregroundStyle(theme.textPrimary)

                        Text("ぬるぬるでは、ユーザー投稿コンテンツを安全に利用するため、以下の内容に同意してから開始してください。")
                            .font(NuruFont.bodyMedium())
                            .foregroundStyle(theme.textSecondary)

                        TermsNoticeCard(
                            icon: "exclamationmark.shield",
                            title: "ゼロトレランス方針",
                            bodyText: "不適切なコンテンツ、嫌がらせ、差別、脅迫、スパム、違法行為、迷惑ユーザーを一切許容しません。"
                        )

                        TermsNoticeCard(
                            icon: "flag",
                            title: "通報機能",
                            bodyText: "不適切な投稿やプロフィール、迷惑行為を見つけた場合は、アプリ内の通報機能から報告できます。"
                        )

                        TermsNoticeCard(
                            icon: "person.crop.circle.badge.xmark",
                            title: "ブロック機能",
                            bodyText: "迷惑なユーザーや表示したくないユーザーは、アプリ内のブロック機能でブロックできます。"
                        )

                        Button {
                            UIApplication.shared.open(termsURL)
                        } label: {
                            HStack {
                                Image(systemName: "doc.text")
                                Text("利用規約の全文を開く")
                                Spacer()
                                Image(systemName: "arrow.up.right")
                            }
                            .font(NuruFont.bodyMedium())
                            .foregroundStyle(NuruColors.lineGreen)
                            .padding(NuruSpacing.space4)
                            .background(theme.bgSecondary)
                            .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl))
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(NuruSpacing.space5)
                }

                VStack(spacing: NuruSpacing.space3) {
                    Button(action: onAgree) {
                        Text("利用規約に同意して開始")
                            .font(NuruFont.buttonLarge())
                            .frame(maxWidth: .infinity)
                            .frame(height: 56)
                    }
                    .buttonStyle(NuruPrimaryButtonStyle())

                    Button("同意しない", action: onCancel)
                        .font(NuruFont.bodyMedium())
                        .foregroundStyle(theme.textTertiary)
                }
                .padding(NuruSpacing.space5)
                .background(theme.bgPrimary)
            }
            .background(theme.bgPrimary)
            .navigationTitle("利用規約")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("閉じる", action: onCancel)
                        .foregroundStyle(theme.textSecondary)
                }
            }
        }
        .interactiveDismissDisabled()
    }
}

private struct TermsNoticeCard: View {
    let icon: String
    let title: String
    let bodyText: String

    @Environment(\.nuruTheme) private var theme

    var body: some View {
        HStack(alignment: .top, spacing: NuruSpacing.space3) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(NuruColors.lineGreen)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: NuruSpacing.space1) {
                Text(title)
                    .font(NuruFont.bodyMedium())
                    .fontWeight(.bold)
                    .foregroundStyle(theme.textPrimary)
                Text(bodyText)
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(NuruSpacing.space4)
        .background(theme.bgSecondary)
        .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusXl))
    }
}

// MARK: - Button Styles

struct NuruPrimaryButtonStyle: ButtonStyle {
    var isDisabled = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.white)
            .background(
                RoundedRectangle(cornerRadius: NuruSpacing.radius2xl)
                    .fill(
                        isDisabled
                            ? NuruColors.lineGreen.opacity(0.3)
                            : NuruColors.lineGreen
                    )
                    .scaleEffect(configuration.isPressed ? 0.97 : 1)
                    .animation(.easeInOut(duration: 0.1), value: configuration.isPressed)
            )
    }
}

struct NuruSecondaryButtonStyle: ButtonStyle {
    let theme: NuruTheme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: NuruSpacing.radius2xl)
                    .fill(theme.bgSecondary)
                    .scaleEffect(configuration.isPressed ? 0.97 : 1)
                    .animation(.easeInOut(duration: 0.1), value: configuration.isPressed)
            )
    }
}

struct NuruOutlineButtonStyle: ButtonStyle {
    let theme: NuruTheme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: NuruSpacing.radiusXl)
                    .fill(theme.bgSecondary)
                    .overlay(
                        RoundedRectangle(cornerRadius: NuruSpacing.radiusXl)
                            .stroke(NuruColors.lineGreen, lineWidth: 1)
                    )
                    .scaleEffect(configuration.isPressed ? 0.97 : 1)
                    .animation(.easeInOut(duration: 0.1), value: configuration.isPressed)
            )
    }
}

// チュートリアル投稿で使用する固定ハッシュタグ (Web/Android と同一)。
//
// 既定本文に `\n#nostrはじめました` を pre-fill し、エディタを開いた瞬間から
// ユーザーには常にハッシュタグが見えている (「勝手に付けられた」を回避する規約)。
// 1 行目を空にすることで、ユーザーが先頭にカーソルを置けば
// 「本文 → 改行 → #nostrはじめました」の配置が自然に成立する。
//
// ユーザーが意図的にハッシュタグ行を消した場合は、その状態のまま投稿する
// (`publishTutorialPost` は自動補完を行わない。3 プラットフォーム共通の規約)。
//
// プレースホルダーは本文を全て消した時のガイドとしてのみ表示される
// (pre-fill 時は ZStack オーバーレイの content.isEmpty 条件により非表示)。
private let kTutorialHashtag = "nostrはじめました"
private let kTutorialDefaultContent = "\n#nostrはじめました"
private let kTutorialPlaceholder = "いまどうしてる？\n#nostrはじめました"

// MARK: - Sign Up Sheet (5-step passkey wizard — mirrors Android SignUpModal.kt)

struct SignUpSheet: View {
    @Binding var isPresented: Bool
    @Environment(AuthViewModel.self) private var viewModel
    @Environment(\.nuruTheme) private var theme

    // Step: welcome → relay → profile → tutorial → success
    @State private var step = "welcome"
    @State private var generatedAccount: AuthViewModel.GeneratedAccount?
    @State private var selectedRelays: [Nip65Relay]?
    @State private var isLoading = false
    @State private var error = ""
    private var progress: CGFloat {
        let total: CGFloat = 5.0
        switch step {
        case "welcome":  return 1.0 / total
        case "relay":    return 2.0 / total
        case "profile":  return 3.0 / total
        case "tutorial": return 4.0 / total
        default:          return 1.0
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                theme.bgPrimary.ignoresSafeArea()

                VStack(spacing: 0) {
                    // Progress bar
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Rectangle()
                                .fill(theme.bgSecondary)
                                .frame(height: 4)
                            Rectangle()
                                .fill(NuruColors.lineGreen)
                                .frame(width: geo.size.width * progress, height: 4)
                                .animation(.easeInOut(duration: 0.3), value: progress)
                        }
                    }
                    .frame(height: 4)

                    ScrollView {
                        VStack(spacing: NuruSpacing.space5) {
                            switch step {
                            case "welcome":
                                SignUpWelcomeStep(
                                    onNext: {},
                                    onNextWithPasskey: {
                                        generateAccountWithPasskey()
                                    },
                                    onClose: { isPresented = false },
                                    isLoading: isLoading,
                                    error: error,
                                    passkeyAvailable: NosskeyManager.isPlatformSupported
                                )
                            case "relay":
                                SignUpRelayStep(onRelaysSelected: { relays in
                                    selectedRelays = relays
                                    step = "profile"
                                })
                            case "profile":
                                SignUpProfileStep(
                                    onFinish: { name, about, picture, banner, nip05, lud16, website, birthday in
                                        publishAndComplete(
                                            name: name, about: about, picture: picture,
                                            banner: banner, nip05: nip05, lud16: lud16,
                                            website: website, birthday: birthday
                                        )
                                    },
                                    isLoading: isLoading
                                )
                            case "tutorial":
                                SignUpTutorialStep(
                                    onPost: { content in
                                        return await viewModel.publishTutorialPost(
                                            content: content,
                                            relays: selectedRelays
                                        )
                                    },
                                    onNext: { step = "success" }
                                )
                            case "success":
                                SignUpSuccessStep(
                                    npub: generatedAccount?.npub ?? "",
                                    onComplete: {
                                        if let hex = generatedAccount?.pubkeyHex {
                                            viewModel.completeRegistration(pubkeyHex: hex)
                                        }
                                        isPresented = false
                                    }
                                )
                            default:
                                EmptyView()
                            }
                        }
                        .padding(NuruSpacing.space6)
                    }
                }
            }
            .navigationTitle("新規登録")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if step != "success" {
                        Button("キャンセル") { isPresented = false }
                            .foregroundStyle(theme.textSecondary)
                    }
                }
            }
        }
        .interactiveDismissDisabled(step == "success")
    }

    // MARK: - Actions
    /// Nosskey "PRF direct" sign-up. Skips the nsec backup step because the
    /// passkey itself acts as the recoverable backup (iCloud Keychain / OS).
    private func generateAccountWithPasskey() {
        isLoading = true
        error = ""
        Task {
            let acc = await viewModel.generateNewAccountWithPasskey(username: "user")
            if let acc {
                generatedAccount = acc
                step = "relay"
            } else {
                if error.isEmpty {
                    error = "パスキーの登録に失敗しました。実機では www.nullnull.app の webcredentials 設定が必要です。"
                }
            }
            isLoading = false
        }
    }

    private func publishAndComplete(
        name: String, about: String, picture: String,
        banner: String, nip05: String, lud16: String,
        website: String, birthday: String
    ) {
        isLoading = true
        Task {
            let relayTriples = selectedRelays
            _ = await viewModel.publishInitialMetadata(
                name: name,
                about: about,
                picture: picture,
                banner: banner,
                nip05: nip05,
                lud16: lud16,
                website: website,
                birthday: birthday,
                relays: relayTriples
            )
            // プロフィール発行後はチュートリアル投稿ステップへ。
            // (発行に失敗してもアカウントは作成済みなので進める。)
            step = "tutorial"
            isLoading = false
        }
    }
}

// MARK: - Step 4.5: Tutorial Post (#nostrはじめました)

/// オンボーディング最終ステップ — `#nostrはじめました` ハッシュタグ付きの kind:1 を投稿する。
/// Android `TutorialStep` と Web `tutorial` ステップに対応。
///
/// - 既定本文: `\n#nostrはじめました` を pre-fill。エディタを開いた瞬間から
///   ユーザーには常時ハッシュタグが見えている (「勝手に付けられた」を回避する規約)。
/// - ユーザーが意図的にハッシュタグ行を削除した場合は、その状態のまま投稿する。
///   `AuthViewModel.publishTutorialPost` は本文への自動補完・末尾付与を一切行わない。
/// - 本文中の `#xxx` のみが `t` タグとして抽出される (PostSheet と同一規約)。
/// - 140 文字制限を強制。本文が空 (trim 後 0 文字) の場合は投稿ボタンを無効化。
/// - 投稿完了時は確認カード → 「次へ進む」で success へ
/// - 「スキップ」で投稿せずに success へ進める
/// - 吹き出しアイコンはぬるぬるブランドカラー (`NuruColors.lineGreen`) に統一。
/// - プレースホルダー (`theme.textTertiary` 薄い灰色 ZStack オーバーレイ) は
///   本文を全て消した時のガイドとしてのみ表示される (`content.isEmpty` のみ重ね描画)。
private struct SignUpTutorialStep: View {
    let onPost: (String) async -> Bool
    let onNext: () -> Void

    @Environment(\.nuruTheme) private var theme
    @State private var content: String = kTutorialDefaultContent
    @State private var isPosting: Bool = false
    @State private var posted: Bool = false
    @State private var errorMessage: String? = nil

    private var remaining: Int { UI.postMaxLength - content.count }
    // 本文が空 (trim 後 0 文字) の場合は投稿不可。
    // pre-fill された `#nostrはじめました` を残せばそのまま投稿可能。
    // publishTutorialPost は自動補完を行わないため、消したら消えたまま送信される。
    private var canPost: Bool {
        remaining >= 0 && !isPosting && !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: NuruSpacing.space5) {
            // ぬるぬるブランドカラー (LineGreen) に統一。アイコンは吹き出し。
            SignUpIconBox(
                systemName: "bubble.left.and.bubble.right.fill",
                containerColor: NuruColors.lineGreen.opacity(0.1),
                iconColor: NuruColors.lineGreen
            )

            VStack(spacing: NuruSpacing.space2) {
                Text("はじめての投稿")
                    .font(NuruFont.titleMedium())
                    .foregroundStyle(theme.textPrimary)
                Text("まずは、ひとことあいさつしてみましょう。何を書けばいいか迷ったら、例文を使えます。")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            if posted {
                // Posted confirmation card
                HStack(alignment: .top, spacing: NuruSpacing.space3) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(NuruColors.lineGreen)
                    VStack(alignment: .leading, spacing: NuruSpacing.space1) {
                        Text("投稿しました！")
                            .font(NuruFont.bodyMedium())
                            .fontWeight(.bold)
                            .foregroundStyle(theme.textPrimary)
                        Text("Nostr の世界へようこそ。タイムラインで「#nostrはじめました」を検索すると、同じ仲間が見つかります。")
                            .font(NuruFont.labelSmall())
                            .foregroundStyle(theme.textSecondary)
                    }
                }
                .padding(NuruSpacing.space4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(NuruColors.lineGreen.opacity(0.1))
                .cornerRadius(NuruSpacing.radiusXl)

                Button {
                    onNext()
                } label: {
                    Text("次へ進む")
                        .font(NuruFont.buttonMedium())
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                }
                .buttonStyle(NuruPrimaryButtonStyle())
            } else {
                // Editor (TextEditor は placeholder API を持たないため、ZStack で薄い灰色の
                //         オーバーレイ Text を重ねる。content.isEmpty の時だけ表示)。
                Button {
                    content = "はじめまして。ぬるぬるを始めました。よろしくね。\n#\(kTutorialHashtag)"
                } label: {
                    Text("例文を使う")
                        .font(NuruFont.labelSmall())
                        .fontWeight(.bold)
                        .foregroundStyle(NuruColors.lineGreen)
                        .padding(.horizontal, NuruSpacing.space3)
                        .padding(.vertical, NuruSpacing.space2)
                        .background(theme.bgSecondary)
                        .clipShape(Capsule())
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .trailing, spacing: NuruSpacing.space1) {
                    ZStack(alignment: .topLeading) {
                        TextEditor(text: $content)
                            .font(NuruFont.bodyMedium())
                            .frame(minHeight: 120, maxHeight: 160)
                            .padding(NuruSpacing.space2)
                            // TextEditor のデフォルト背景 (システム白) を消して、ダーク/ライト両対応の
                            // theme.bgSecondary を背面に通す。他の TextEditor 使用箇所
                            // (PostSheet / QuoteRepostSheet 等) と同一の扱い。
                            .scrollContentBackground(.hidden)
                            .background(theme.bgSecondary)
                            .cornerRadius(NuruSpacing.radiusMd)
                            .overlay(
                                RoundedRectangle(cornerRadius: NuruSpacing.radiusMd)
                                    .stroke(theme.borderColor, lineWidth: 1)
                            )
                            .onChange(of: content) { _, newValue in
                                // 140 文字制限を強制 (PostSheet と同じ制約)
                                if newValue.count > UI.postMaxLength {
                                    content = String(newValue.prefix(UI.postMaxLength))
                                }
                            }

                        if content.isEmpty {
                            // 薄い灰色のプレースホルダーテキスト。
                            // TextEditor 内側の padding(NuruSpacing.space2) と同じ余白 + 4 を足し、
                            // TextEditor 内のテキスト開始位置とほぼ揃える。allowsHitTesting=false で
                            // タップは下層の TextEditor に通す。
                            Text(kTutorialPlaceholder)
                                .font(NuruFont.bodyMedium())
                                .foregroundStyle(theme.textTertiary)
                                .padding(.horizontal, NuruSpacing.space2 + 4)
                                .padding(.vertical, NuruSpacing.space2 + 8)
                                .allowsHitTesting(false)
                        }
                    }
                    Text("\(content.count)/\(UI.postMaxLength)")
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(remaining < 0 ? NuruColors.colorError : theme.textTertiary)
                }

                if let errorMessage {
                    Text(errorMessage)
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(NuruColors.colorError)
                        .multilineTextAlignment(.center)
                }

                Button {
                    errorMessage = nil
                    isPosting = true
                    Task {
                        let ok = await onPost(content)
                        isPosting = false
                        if ok {
                            posted = true
                        } else {
                            errorMessage = "投稿に失敗しました。通信状況を確認してください。"
                        }
                    }
                } label: {
                    Group {
                        if isPosting {
                            ProgressView().tint(.white).scaleEffect(0.8)
                        } else {
                            Text("投稿する").font(NuruFont.buttonMedium())
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                }
                .buttonStyle(NuruPrimaryButtonStyle(isDisabled: !canPost))
                .disabled(!canPost)

                Button("スキップ") { onNext() }
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textTertiary)
                    .disabled(isPosting)
            }
        }
    }
}

// MARK: - Step 1: Welcome

private struct SignUpWelcomeStep: View {
    let onNext: () -> Void
    var onNextWithPasskey: (() -> Void)? = nil
    let onClose: () -> Void
    let isLoading: Bool
    let error: String
    var passkeyAvailable: Bool = false

    @Environment(\.nuruTheme) private var theme

    var body: some View {
        VStack(spacing: NuruSpacing.space5) {
            SignUpIconBox(
                systemName: passkeyAvailable ? "faceid" : "person.badge.plus",
                containerColor: NuruColors.lineGreen.opacity(0.1),
                iconColor: NuruColors.lineGreen
            )

            VStack(spacing: NuruSpacing.space2) {
                Text("新規登録")
                    .font(NuruFont.titleLarge())
                    .foregroundStyle(theme.textPrimary)
                Text("Face ID または Touch ID で安全に登録します。\n秘密鍵を保管する必要はありません。")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            if !error.isEmpty {
                Text(error)
                    .font(NuruFont.labelSmall())
                    .foregroundStyle(NuruColors.colorError)
            }

            if passkeyAvailable, let passkeyAction = onNextWithPasskey {
                Button(action: passkeyAction) {
                    Group {
                        if isLoading {
                            ProgressView().tint(.white).scaleEffect(0.8)
                        } else {
                            HStack(spacing: NuruSpacing.space2) {
                                Image(systemName: "faceid")
                                    .font(.system(size: 18, weight: .semibold))
                                Text("パスキーで登録")
                                    .font(NuruFont.buttonLarge())
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                }
                .buttonStyle(NuruPrimaryButtonStyle(isDisabled: isLoading))
                .disabled(isLoading)
            } else {
                Text("この端末ではパスキー登録を利用できません。既存アカウントでログインするか、対応端末で登録してください。")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textTertiary)
                    .multilineTextAlignment(.center)
            }

            Button("キャンセル", action: onClose)
                .font(NuruFont.bodyMedium())
                .foregroundStyle(theme.textTertiary)
        }
    }
}

// MARK: - Step 2: Region


private struct SignUpRelayStep: View {
    let onRelaysSelected: ([Nip65Relay]) -> Void

    @Environment(\.nuruTheme) private var theme
    @State private var selectionMode = "manual"
    @State private var recommendedRelays: [Nip65Relay]
    @State private var regionName = "東京"
    @State private var isLoading = false
    @State private var showRegionPicker = false
    @StateObject private var locationHelper = SignUpLocationHelper()

    init(onRelaysSelected: @escaping ([Nip65Relay]) -> Void) {
        self.onRelaysSelected = onRelaysSelected
        let config = RelayDiscovery.generateRelayListByLocation(userLat: 35.6762, userLon: 139.6503)
        _recommendedRelays = State(initialValue: config.combined)
    }

    var body: some View {
        VStack(spacing: NuruSpacing.space5) {
            SignUpIconBox(
                systemName: "location",
                containerColor: Color.blue.opacity(0.1),
                iconColor: .blue
            )

            VStack(spacing: NuruSpacing.space2) {
                Text("地域の設定")
                    .font(NuruFont.titleMedium())
                    .foregroundStyle(theme.textPrimary)
                Text("地域を選択すると、近くのリレーサーバーを自動セットアップします。")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            // Mode toggle: GPS / Manual
            HStack(spacing: 0) {
                ForEach([("auto", "GPSで自動検出"), ("manual", "手動で選択")], id: \.0) { id, label in
                    Button {
                        selectionMode = id
                    } label: {
                        Text(label)
                            .font(NuruFont.labelSmall())
                            .fontWeight(.bold)
                            .foregroundStyle(selectionMode == id ? NuruColors.lineGreen : theme.textTertiary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, NuruSpacing.space2)
                            .background(selectionMode == id ? theme.bgPrimary : Color.clear)
                            .cornerRadius(NuruSpacing.radiusMd)
                    }
                }
            }
            .padding(4)
            .background(theme.bgSecondary)
            .cornerRadius(NuruSpacing.radiusMd)

            // Manual region selector
            if selectionMode == "manual" {
                Button { showRegionPicker = true } label: {
                    HStack {
                        Text(regionName)
                            .font(NuruFont.bodyMedium())
                            .foregroundStyle(theme.textPrimary)
                        Spacer()
                        Image(systemName: "chevron.down")
                            .foregroundStyle(theme.textTertiary)
                    }
                    .padding(NuruSpacing.space3)
                    .overlay(
                        RoundedRectangle(cornerRadius: NuruSpacing.radiusMd)
                            .stroke(theme.borderColor, lineWidth: 1)
                    )
                }
                .confirmationDialog("地域を選択", isPresented: $showRegionPicker) {
                    ForEach(RelayDiscovery.regionCoordinates) { region in
                        Button(region.name) {
                            regionName = region.name
                            let config = RelayDiscovery.generateRelayListByLocation(
                                userLat: region.lat, userLon: region.lon
                            )
                            recommendedRelays = config.combined
                        }
                    }
                }
            }

            // Relay list card
            VStack(alignment: .leading, spacing: NuruSpacing.space2) {
                Text("推奨リレーサーバー (\(regionName))")
                    .font(NuruFont.labelSmall())
                    .fontWeight(.bold)
                    .foregroundStyle(theme.textTertiary)

                if isLoading {
                    ProgressView().tint(NuruColors.lineGreen)
                        .frame(maxWidth: .infinity)
                } else {
                    ForEach(recommendedRelays) { relay in
                        HStack(spacing: NuruSpacing.space2) {
                            Image(systemName: "server.rack")
                                .font(.system(size: 12))
                                .foregroundStyle(NuruColors.lineGreen)
                            Text(relay.url.replacingOccurrences(of: "wss://", with: ""))
                                .font(NuruFont.bodySmall())
                                .foregroundStyle(theme.textPrimary)
                            Spacer()
                            if relay.permission == .read || relay.permission == .readWrite {
                                Text("R")
                                    .font(.system(size: 8, weight: .bold))
                                    .foregroundStyle(.blue)
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 2)
                                    .background(Color.blue.opacity(0.2))
                                    .cornerRadius(4)
                            }
                            if relay.permission == .write || relay.permission == .readWrite {
                                Text("W")
                                    .font(.system(size: 8, weight: .bold))
                                    .foregroundStyle(NuruColors.lineGreen)
                                    .padding(.horizontal, 4)
                                    .padding(.vertical, 2)
                                    .background(NuruColors.lineGreen.opacity(0.2))
                                    .cornerRadius(4)
                            }
                        }
                    }
                }
            }
            .padding(NuruSpacing.space4)
            .background(theme.bgSecondary)
            .cornerRadius(NuruSpacing.radiusXl)

            Button {
                onRelaysSelected(recommendedRelays)
            } label: {
                Text("次へ進む")
                    .font(NuruFont.buttonMedium())
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
            }
            .buttonStyle(NuruPrimaryButtonStyle(isDisabled: isLoading))
            .disabled(isLoading)
        }
        .onChange(of: selectionMode) { _, newValue in
            if newValue == "auto" {
                requestGPSRelays()
            }
        }
    }

    private func requestGPSRelays() {
        isLoading = true
        locationHelper.requestLocation { result in
            isLoading = false
            switch result {
            case .success(let location):
                let lat = location.coordinate.latitude
                let lon = location.coordinate.longitude
                let config = RelayDiscovery.generateRelayListByLocation(userLat: lat, userLon: lon)
                regionName = "現在地"
                recommendedRelays = config.combined
            case .failure:
                // Keep a graceful fallback so sign-up is never blocked.
                regionName = "東京 (位置情報なし)"
                let config = RelayDiscovery.generateRelayListByLocation(userLat: 35.6762, userLon: 139.6503)
                recommendedRelays = config.combined
            }
        }
    }
}

private final class SignUpLocationHelper: NSObject, ObservableObject, CLLocationManagerDelegate {
    private lazy var manager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        return manager
    }()

    private var completion: ((Result<CLLocation, Error>) -> Void)?
    private var didRespond = false

    func requestLocation(completion: @escaping (Result<CLLocation, Error>) -> Void) {
        self.completion = completion
        self.didRespond = false
        DispatchQueue.main.async {
            switch self.manager.authorizationStatus {
            case .notDetermined:
                self.manager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse, .authorizedAlways:
                self.manager.requestLocation()
            default:
                self.finish(.failure(NSError(domain: "location", code: -1)))
            }
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        case .denied, .restricted:
            finish(.failure(NSError(domain: "location", code: -2)))
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        finish(.success(location))
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<CLLocation, Error>) {
        guard !didRespond else { return }
        didRespond = true
        completion?(result)
        completion = nil
    }
}

// MARK: - Step 4: Profile

private struct SignUpProfileStep: View {
    let onFinish: (String, String, String, String, String, String, String, String) -> Void
    let isLoading: Bool

    @Environment(AuthViewModel.self) private var authViewModel
    @Environment(\.nuruTheme) private var theme
    @State private var name = ""
    @State private var about = ""
    @State private var picture = ""
    @State private var banner = ""
    @State private var nip05 = ""
    @State private var lud16 = ""
    @State private var website = ""
    @State private var birthday = ""
    @State private var showAdvanced = false

    // PhotosPicker
    @State private var picturePickerItem: PhotosPickerItem?
    @State private var bannerPickerItem: PhotosPickerItem?
    @State private var uploadingPicture = false
    @State private var uploadingBanner = false
    @State private var uploadError: String? = nil

    var body: some View {
        VStack(spacing: NuruSpacing.space5) {
            // Avatar upload via PhotosPicker
            VStack(spacing: NuruSpacing.space3) {
                PhotosPicker(selection: $picturePickerItem, matching: .images) {
                    ZStack {
                        Circle()
                            .fill(theme.bgSecondary)
                            .frame(width: 100, height: 100)

                        if picture.isEmpty {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 36))
                                .foregroundStyle(theme.textTertiary)
                        } else {
                            AsyncImage(url: URL(string: picture)) { image in
                                image.resizable().scaledToFill()
                            } placeholder: {
                                ProgressView().tint(NuruColors.lineGreen)
                            }
                            .frame(width: 100, height: 100)
                            .clipShape(Circle())
                        }

                        if uploadingPicture {
                            Circle()
                                .fill(Color.black.opacity(0.5))
                                .frame(width: 100, height: 100)
                            ProgressView().tint(NuruColors.lineGreen)
                        }
                    }
                }
                .disabled(uploadingPicture)

                Text(uploadingPicture ? "アップロード中..." : "アイコン画像をアップロード")
                    .font(NuruFont.labelSmall())
                    .foregroundStyle(uploadingPicture ? NuruColors.lineGreen : theme.textTertiary)

                if let uploadError {
                    Text(uploadError)
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(NuruColors.colorError)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, NuruSpacing.space2)
                }
            }
            .onChange(of: picturePickerItem) { _, item in
                guard let item else { return }
                uploadImage(item: item, target: .picture)
            }

            VStack(spacing: NuruSpacing.space1) {
                Text("プロフィールの設定")
                    .font(NuruFont.titleMedium())
                    .foregroundStyle(theme.textPrimary)
                Text("あなたの情報を入力しましょう。")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
            }

            VStack(spacing: NuruSpacing.space3) {
                signUpTextField("名前", placeholder: "表示名", text: $name)

                // アイコン画像URL + アップロードボタン
                signUpTextFieldWithUpload(
                    "アイコン画像URL",
                    placeholder: "https://...",
                    text: $picture,
                    pickerItem: $picturePickerItem,
                    isUploading: uploadingPicture
                )

                signUpTextField("自己紹介", placeholder: "", text: $about, axis: .vertical)

                Button { showAdvanced.toggle() } label: {
                    Text(showAdvanced ? "詳細設定を隠す" : "詳細設定を表示")
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(NuruColors.lineGreen)
                }

                if showAdvanced {
                    // バナー画像URL + アップロードボタン
                    signUpTextFieldWithUpload(
                        "バナー画像URL",
                        placeholder: "https://...",
                        text: $banner,
                        pickerItem: $bannerPickerItem,
                        isUploading: uploadingBanner
                    )
                    .onChange(of: bannerPickerItem) { _, item in
                        guard let item else { return }
                        uploadImage(item: item, target: .banner)
                    }

                    signUpTextField("NIP-05 (認証)", placeholder: "user@example.com", text: $nip05)
                    signUpTextField("ライトニングアドレス", placeholder: "user@wallet.com", text: $lud16)
                    signUpTextField("ウェブサイト", placeholder: "https://...", text: $website)
                    signUpTextField("誕生日 (MM-DD)", placeholder: "01-01", text: $birthday)
                }
            }

            Button {
                onFinish(name, about, picture, banner, nip05, lud16, website, birthday)
            } label: {
                Group {
                    if isLoading {
                        ProgressView().tint(.white).scaleEffect(0.8)
                    } else {
                        Text("セットアップを完了する")
                            .font(NuruFont.buttonMedium())
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 56)
            }
            .buttonStyle(NuruPrimaryButtonStyle(isDisabled: isLoading))
            .disabled(isLoading)
        }
    }

    // MARK: - Image Upload

    private enum UploadTarget { case picture, banner }

    private func uploadImage(item: PhotosPickerItem, target: UploadTarget) {
        switch target {
        case .picture: uploadingPicture = true
        case .banner:  uploadingBanner = true
        }
        uploadError = nil

        Task {
            defer {
                switch target {
                case .picture: uploadingPicture = false
                case .banner:  uploadingBanner = false
                }
            }

            guard let data = try? await item.loadTransferable(type: Data.self), !data.isEmpty else {
                uploadError = "画像の読み込みに失敗しました"
                return
            }

            // Sign-up can receive HEIC/HEIF with private EXIF/GPS metadata.
            // Always redraw and upload a clean JPEG payload.
            guard let image = UIImage(data: data) else {
                uploadError = "画像形式を読み込めませんでした"
                return
            }
            let renderer = UIGraphicsImageRenderer(size: image.size, format: {
                let fmt = UIGraphicsImageRendererFormat()
                fmt.preferredRange = .standard
                return fmt
            }())
            let redrawnJpeg = renderer.jpegData(withCompressionQuality: 0.92) { _ in
                image.draw(in: CGRect(origin: .zero, size: image.size))
            }

            let signer = InternalSigner(keyManager: authViewModel.keyManager)
            let service = ImageUploadService(signer: signer)
            let compressed = service.compressImage(data: redrawnJpeg, maxSize: 1920, quality: 0.85)

            do {
                let url = try await service.uploadImage(
                    imageData: compressed,
                    server: .nostrBuild,
                    mimeType: "image/jpeg"
                )
                switch target {
                case .picture: picture = url
                case .banner:  banner = url
                }
            } catch {
                let msg = error.localizedDescription
                if msg.contains("401") || msg.lowercased().contains("nip-98") || msg.lowercased().contains("unauthorized") {
                    uploadError = "サーバー認証エラー (NIP-98)。もう一度お試しください"
                } else {
                    uploadError = msg
                }
            }
        }
    }

    // MARK: - Text Fields

    @ViewBuilder
    private func signUpTextField(
        _ label: String,
        placeholder: String,
        text: Binding<String>,
        axis: Axis = .horizontal
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(NuruFont.labelSmall())
                .foregroundStyle(theme.textSecondary)

            if axis == .vertical {
                TextField(placeholder, text: text, axis: .vertical)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(3...5)
                    .padding(NuruSpacing.space3)
                    .background(theme.bgSecondary)
                    .cornerRadius(NuruSpacing.radiusMd)
                    .overlay(
                        RoundedRectangle(cornerRadius: NuruSpacing.radiusMd)
                            .stroke(NuruColors.lineGreen, lineWidth: 1)
                    )
            } else {
                TextField(placeholder, text: text)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(NuruSpacing.space3)
                    .background(theme.bgSecondary)
                    .cornerRadius(NuruSpacing.radiusMd)
                    .overlay(
                        RoundedRectangle(cornerRadius: NuruSpacing.radiusMd)
                            .stroke(NuruColors.lineGreen, lineWidth: 1)
                    )
            }
        }
    }

    @ViewBuilder
    private func signUpTextFieldWithUpload(
        _ label: String,
        placeholder: String,
        text: Binding<String>,
        pickerItem: Binding<PhotosPickerItem?>,
        isUploading: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(NuruFont.labelSmall())
                .foregroundStyle(theme.textSecondary)

            HStack(spacing: NuruSpacing.space2) {
                TextField(placeholder, text: text)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textPrimary)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)

                PhotosPicker(selection: pickerItem, matching: .images) {
                    if isUploading {
                        ProgressView()
                            .scaleEffect(0.8)
                            .tint(NuruColors.lineGreen)
                            .frame(width: 24, height: 24)
                    } else {
                        Image(systemName: "icloud.and.arrow.up")
                            .font(.system(size: 18))
                            .foregroundStyle(NuruColors.lineGreen)
                    }
                }
                .disabled(isUploading)
            }
            .padding(NuruSpacing.space3)
            .background(theme.bgSecondary)
            .cornerRadius(NuruSpacing.radiusMd)
            .overlay(
                RoundedRectangle(cornerRadius: NuruSpacing.radiusMd)
                    .stroke(NuruColors.lineGreen, lineWidth: 1)
            )
        }
    }
}

// MARK: - Step 5: Success

private struct SignUpSuccessStep: View {
    let npub: String
    let onComplete: () -> Void

    @Environment(\.nuruTheme) private var theme

    var body: some View {
        VStack(spacing: NuruSpacing.space5) {
            SignUpIconBox(
                systemName: "checkmark.circle.fill",
                containerColor: NuruColors.lineGreen.opacity(0.1),
                iconColor: NuruColors.lineGreen
            )

            VStack(spacing: NuruSpacing.space2) {
                Text("準備完了！")
                    .font(NuruFont.titleLarge())
                    .foregroundStyle(theme.textPrimary)
                Text("アカウントが作成されました。ぬるぬるの世界へようこそ！")
                    .font(NuruFont.bodySmall())
                    .foregroundStyle(theme.textSecondary)
                    .multilineTextAlignment(.center)
            }

            Button(action: onComplete) {
                Text("はじめる")
                    .font(NuruFont.buttonMedium())
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
            }
            .buttonStyle(NuruPrimaryButtonStyle())
        }
    }
}

// MARK: - Shared Icon Box

private struct SignUpIconBox: View {
    let systemName: String
    let containerColor: Color
    let iconColor: Color

    var body: some View {
        ZStack {
            Circle()
                .fill(containerColor)
                .frame(width: 64, height: 64)
            Image(systemName: systemName)
                .font(.system(size: 28))
                .foregroundStyle(iconColor)
        }
    }
}

// MARK: - Preview

#Preview {
    LoginView()
        .environment(AuthViewModel())
        .environment(\.nuruTheme, .dark)
}
