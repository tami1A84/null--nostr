import SwiftUI

/// トーク screen — MLS group list and group chat.
/// Mirrors Android TalkScreen.kt.
struct TalkView: View {

    @Bindable var viewModel: TalkViewModel

    var body: some View {
        if let group = viewModel.activeGroup {
            GroupChatView(viewModel: viewModel, group: group)
        } else {
            GroupListView(viewModel: viewModel)
        }
    }
}

// MARK: - Group List

private struct GroupListView: View {

    @Bindable var viewModel: TalkViewModel
    @Environment(\.nuruTheme) private var theme

    @State private var selectedPage:    Int        = 0
    @State private var showNewChat:    Bool       = false
    @State private var showAddMenu:    Bool       = false
    @State private var newChatPubkey:  String     = ""

    private static let filters: [TalkFilter] = [.all, .friends, .groups]

    private func filteredGroups(for filter: TalkFilter) -> [MlsGroup] {
        switch filter {
        case .all:     return viewModel.groups
        case .friends: return viewModel.groups.filter { $0.isDm }
        case .groups:  return viewModel.groups.filter { !$0.isDm }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Text("トーク")
                    .font(NuruFont.titleLarge())
                    .foregroundStyle(theme.textPrimary)
                Spacer()
                // Add button
                Menu {
                    Button("新しいトーク") { showNewChat = true }
                    Button("グループ作成") {
                        Task { await viewModel.loadFollowingProfiles() }
                        viewModel.showCreateGroupSheet()
                    }
                } label: {
                    ZStack {
                        Circle()
                            .fill(NuruColors.lineGreen)
                            .frame(width: 32, height: 32)
                        Image(systemName: "plus")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
            }
            .frame(height: 56)
            .padding(.horizontal, NuruSpacing.space4)
            .background(theme.bgPrimary)

            // Filter chips (synced with pager)
            filterBar
            Divider().background(theme.borderColor)

            // Swipeable pager (mirrors Android HorizontalPager)
            TabView(selection: $selectedPage) {
                ForEach(Array(Self.filters.enumerated()), id: \.offset) { index, filter in
                    filterPage(filter)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut(duration: 0.25), value: selectedPage)
        }
        .background(theme.bgPrimary)
        // Do not auto-load MLS groups from .task. MainTabView keeps TalkView alive
        // behind the timeline tab, and eager MLS/KeyPackage discovery was one of the
        // largest startup bottlenecks. Groups are loaded explicitly when the user taps
        // the Talk tab, pulls to refresh, opens a DM, or creates a group.
        // New DM sheet
        .sheet(isPresented: $showNewChat) {
            NewChatSheet(onStartChat: { pubkey in
                showNewChat = false
                Task { await viewModel.createDmConversation(pubkey: pubkey) }
            })
        }
        // Create group sheet
        .sheet(isPresented: Binding(
            get: { viewModel.showCreateGroup },
            set: { if !$0 { viewModel.hideCreateGroup() } }
        )) {
            CreateGroupSheet(
                followingProfiles: viewModel.followingProfiles,
                isLoading: viewModel.followingLoading,
                onCreate: { name, members in
                    Task { await viewModel.createGroupChat(name: name, members: members) }
                },
                onDismiss: { viewModel.hideCreateGroup() }
            )
        }
    }

    @ViewBuilder
    private func filterPage(_ filter: TalkFilter) -> some View {
        let groups = filteredGroups(for: filter)
        if groups.isEmpty {
            emptyState
        } else {
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(groups) { group in
                        GroupRow(
                            group: group,
                            myPubkeyHex: viewModel.myPubkeyHex
                        ) {
                            Task { await viewModel.openGroup(group.groupIdHex) }
                        }
                        Divider()
                            .padding(.horizontal, NuruSpacing.space4)
                            .background(theme.borderColor)
                    }
                }
            }
            .background(theme.bgPrimary)
            .refreshable { await viewModel.loadGroups() }
        }
    }

    private var filterBar: some View {
        HStack(spacing: NuruSpacing.space2) {
            ForEach(Array(Self.filters.enumerated()), id: \.offset) { index, filter in
                filterChip(filter.label, index: index)
            }
        }
        .padding(.horizontal, NuruSpacing.space3)
        .padding(.vertical, NuruSpacing.space2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.bgPrimary)
    }

    private func filterChip(_ label: String, index: Int) -> some View {
        let selected = selectedPage == index
        return Button {
            withAnimation(.easeInOut(duration: 0.25)) { selectedPage = index }
        } label: {
            Text(label)
                .font(.system(size: 13, weight: selected ? .semibold : .regular))
                .foregroundStyle(selected ? .white : theme.textPrimary)
                .padding(.horizontal, NuruSpacing.space4)
                .frame(height: 32)
                .background(selected ? NuruColors.lineGreen : theme.bgSecondary)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // Loading skeleton intentionally removed: Talk loads in the background and
    // the empty state remains visible when there are no local groups yet.

    private var emptyState: some View {
        VStack(spacing: 0) {
            Spacer()
            ZStack {
                Circle()
                    .fill(theme.bgTertiary)
                    .frame(width: 80, height: 80)
                Image(systemName: "bubble.left.and.bubble.right")
                    .font(.system(size: 40))
                    .foregroundStyle(theme.textTertiary)
            }
            Spacer().frame(height: NuruSpacing.space4)
            Text("トークがありません")
                .font(NuruFont.bodyLarge())
                .foregroundStyle(theme.textSecondary)
            Text("右上の＋から始めましょう")
                .font(NuruFont.bodySmall())
                .foregroundStyle(theme.textTertiary)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .background(theme.bgPrimary)
    }
}

// MARK: - Group Chat

private struct GroupChatView: View {

    @Bindable var viewModel: TalkViewModel
    let group: MlsGroup
    @Environment(\.nuruTheme) private var theme
    @State private var messageText: String = ""

    private var title: String {
        if !group.isDm, !group.name.isEmpty { return group.name }
        let partner = group.memberPubkeys.first(where: { $0 != viewModel.myPubkeyHex }) ?? ""
        return group.memberProfiles[partner]?.displayedName ?? partner.shortenedPubkey
    }

    private var partnerProfile: UserProfile? {
        guard group.isDm else { return nil }
        let partner = group.memberPubkeys.first(where: { $0 != viewModel.myPubkeyHex }) ?? ""
        return group.memberProfiles[partner]
    }

    var body: some View {
        VStack(spacing: 0) {
            // LINE-like top bar
            HStack(spacing: NuruSpacing.space2) {
                Button { viewModel.closeGroup() } label: {
                    Image(systemName: NuruIcons.back)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(theme.textPrimary)
                        .frame(width: 36, height: 36)
                }

                if let url = partnerProfile?.picture.flatMap(URL.init) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image): image.resizable().scaledToFill()
                        default:
                            Circle().fill(theme.bgTertiary)
                        }
                    }
                    .frame(width: 30, height: 30)
                    .clipShape(Circle())
                }

                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(NuruFont.titleMedium())
                        .foregroundStyle(theme.textPrimary)
                        .lineLimit(1)
                    Text("gid:\(String(group.groupIdHex.prefix(12))) msg:\(viewModel.messages.count)")
                        .font(.system(size: 10, weight: .regular))
                        .foregroundStyle(theme.textTertiary)
                        .lineLimit(1)
                    if let np = partnerProfile?.name, !np.isEmpty {
                        Text(np)
                            .font(NuruFont.labelSmall())
                            .foregroundStyle(theme.textTertiary)
                            .lineLimit(1)
                    }
                }

                Spacer()

                HStack(spacing: 2) {
                    Image(systemName: "magnifyingglass")
                    Image(systemName: "phone")
                    Image(systemName: "line.3.horizontal")
                }
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(theme.textSecondary)
                .frame(height: 36)

                Button { viewModel.showGroupInfoSheet() } label: {
                    Image(systemName: NuruIcons.info)
                        .font(.system(size: 16, weight: .regular))
                        .foregroundStyle(theme.textSecondary)
                        .frame(width: 28, height: 28)
                }
            }
            .frame(height: 56)
            .padding(.horizontal, NuruSpacing.space2)
            .background(theme.bgPrimary)

            Divider().background(theme.borderColor)

            if let error = viewModel.error, !error.isEmpty {
                HStack(spacing: NuruSpacing.space2) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(.white)
                    Text(error)
                        .font(NuruFont.bodySmall())
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Spacer(minLength: 0)
                    Button("閉じる") { viewModel.error = nil }
                        .font(NuruFont.labelSmall())
                        .foregroundStyle(.white.opacity(0.9))
                }
                .padding(.horizontal, NuruSpacing.space3)
                .padding(.vertical, NuruSpacing.space2)
                .background(Color.red.opacity(0.85))
            }

            // Issue #183: when the Rust catch-up reports the missing Commit
            // is no longer retrievable from any configured relay AND is not
            // in the local replay cache, surface an actionable prompt so the
            // user is not stuck silently in a state_not_ready loop (AC2).
            if viewModel.recoveryStatus == .notRecoverable {
                MlsRecoveryBanner(
                    isWorking: viewModel.recreatingConversation,
                    onRecreate: { Task { await viewModel.recreateActiveDmConversation() } },
                    onDismiss: { viewModel.dismissRecoveryBanner() }
                )
            }

            // Messages
            if viewModel.messagesLoading && viewModel.messages.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    ForEach(0..<5, id: \.self) { i in
                        MessageBubbleSkeleton(alignRight: i % 2 == 1)
                    }
                    Spacer()
                }
                .frame(maxWidth: .infinity)
                .background(theme.bgPrimary)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            ForEach(viewModel.messages) { msg in
                                MessageBubble(message: msg, myPubkeyHex: viewModel.myPubkeyHex)
                                    .id(msg.id)
                            }
                        }
                        .padding(.horizontal, NuruSpacing.space3)
                        .padding(.vertical, NuruSpacing.space3)
                    }
                    .background(theme.bgPrimary)
                    .onChange(of: viewModel.messages.count) { _, _ in
                        if let last = viewModel.messages.last {
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                    .onChange(of: viewModel.messages.last?.id) { _, _ in
                        if let last = viewModel.messages.last {
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                }
            }

            Divider().background(theme.borderColor)

            // Input bar
            MessageInputBar(
                text: $messageText,
                isSending: viewModel.sendingMessage,
                isDisabled: viewModel.isGroupStuck(group.groupIdHex)
            ) {
                let t = messageText
                messageText = ""
                Task { await viewModel.sendMessage(t) }
            }
        }
        .background(theme.bgPrimary)
        .sheet(isPresented: Binding(
            get: { viewModel.showGroupInfo },
            set: { if !$0 { viewModel.hideGroupInfo() } }
        )) {
            GroupInfoSheet(
                group:        group,
                myPubkeyHex:  viewModel.myPubkeyHex,
                onLeave:      { Task { await viewModel.leaveGroup() } },
                onDismiss:    { viewModel.hideGroupInfo() },
                viewModel:    viewModel
            )
        }
    }

}


// MARK: - Group Row

private struct GroupRow: View {
    let group:        MlsGroup
    let myPubkeyHex:  String
    let onTap:        () -> Void
    @Environment(\.nuruTheme) private var theme

    private var partner: UserProfile? {
        guard group.isDm else { return nil }
        let pk = group.memberPubkeys.first(where: { $0 != myPubkeyHex }) ?? ""
        return group.memberProfiles[pk]
    }

    private var displayName: String {
        if !group.isDm, !group.name.isEmpty { return group.name }
        return partner?.displayedName ?? "DM"
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: NuruSpacing.space3) {
                // Avatar
                groupAvatar

                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(displayName)
                            .font(NuruFont.bodyMedium())
                            .foregroundStyle(theme.textPrimary)
                            .lineLimit(1)
                        Spacer()
                        if group.lastMessageTime > 0 {
                            Text(group.lastMessageTime.relativeTimeString)
                                .font(NuruFont.labelSmall())
                                .foregroundStyle(theme.textTertiary)
                        }
                    }
                    HStack {
                        Text("gid:\(String(group.groupIdHex.prefix(12)))  \(group.lastMessage.isEmpty ? "メッセージはありません" : group.lastMessage)")
                            .font(NuruFont.bodySmall())
                            .foregroundStyle(theme.textSecondary)
                            .lineLimit(1)
                        Spacer()
                        if group.unreadCount > 0 {
                            Text("\(group.unreadCount)")
                                .font(NuruFont.labelSmall())
                                .foregroundStyle(.white)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(NuruColors.lineGreen)
                                .clipShape(Capsule())
                        }
                    }
                }
            }
            .padding(.horizontal, NuruSpacing.space4)
            .frame(minHeight: 72)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var groupAvatar: some View {
        if let url = partner?.picture.flatMap(URL.init) {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let img): img.resizable().scaledToFill()
                default: avatarFallback
                }
            }
            .frame(width: 48, height: 48)
            .clipShape(Circle())
        } else if !group.isDm {
            ZStack {
                Circle().fill(NuruColors.lineGreen.opacity(0.2))
                Image(systemName: "person.3.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(NuruColors.lineGreen)
            }
            .frame(width: 48, height: 48)
        } else {
            avatarFallback
                .frame(width: 48, height: 48)
        }
    }

    private var avatarFallback: some View {
        ZStack {
            Circle().fill(theme.bgTertiary)
            Image(systemName: "person.fill")
                .font(.system(size: 48 * 0.55))
                .foregroundStyle(theme.textTertiary)
        }
    }

}


// MARK: - Message Bubble

private struct MessageBubble: View {
    let message:      MlsMessage
    let myPubkeyHex:  String
    @Environment(\.nuruTheme) private var theme
    @State private var isCWRevealed = false

    private var isMine: Bool { message.senderPubkey == myPubkeyHex }

    /// Parse [CW: reason]\n\nbody format from DM content.
    private var parsedContent: (reason: String?, body: String) {
        let pattern = #"^\[CW:\s*([^\]]*)\]\s*\n+\s*([\s\S]*)"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
              let match = regex.firstMatch(in: message.content,
                  range: NSRange(message.content.startIndex..., in: message.content)) else {
            return (nil, message.content)
        }
        let reason = message.content.range(from: match.range(at: 1)).map { String(message.content[$0]) }
        let body   = message.content.range(from: match.range(at: 2)).map { String(message.content[$0]) } ?? message.content
        return (reason, body)
    }

    /// Extract image URLs from message content.
    private func imageUrls(from content: String) -> [URL] {
        let pattern = #"https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return [] }
        let ns = content as NSString
        return regex.matches(in: content, range: NSRange(location: 0, length: ns.length))
            .compactMap { URL(string: ns.substring(with: $0.range)) }
    }

    /// Content with image URLs stripped.
    private func textWithoutImages(_ content: String) -> String {
        content.replacingOccurrences(
            of: #"https?://\S+\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?"#,
            with: "", options: .regularExpression
        ).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var senderAvatarFallback: some View {
        ZStack {
            Circle().fill(theme.bgTertiary)
            Image(systemName: "person.fill")
                .font(.system(size: 32 * 0.55))
                .foregroundStyle(theme.textTertiary)
        }
        .frame(width: 32, height: 32)
    }

    var body: some View {
        let (cwReason, bodyContent) = parsedContent
        let images = imageUrls(from: bodyContent)
        let displayText = textWithoutImages(bodyContent)
        let timeText = Date(timeIntervalSince1970: TimeInterval(message.timestamp)).formatted(.dateTime.hour().minute())

        if isMine {
            HStack(alignment: .bottom, spacing: 1) {
                Text(timeText)
                    .font(.system(size: 10))
                    .foregroundStyle(theme.textTertiary)
                    .padding(.bottom, 1)

                if let cw = cwReason, !isCWRevealed {
                    cwBanner(reason: cw)
                } else {
                    bubbleContent(text: displayText, images: images, cwReason: cwReason)
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        } else {
            HStack(alignment: .bottom, spacing: 6) {
                if let url = message.senderProfile?.picture.flatMap(URL.init) {
                    AsyncImage(url: url) { phase in
                        if case .success(let img) = phase { img.resizable().scaledToFill() }
                        else { senderAvatarFallback }
                    }
                    .frame(width: 30, height: 30)
                    .clipShape(Circle())
                } else {
                    senderAvatarFallback
                        .frame(width: 30, height: 30)
                }

                VStack(alignment: .leading, spacing: 2) {
                    if let profile = message.senderProfile {
                        Text(profile.displayedName)
                            .font(.system(size: 11))
                            .foregroundStyle(theme.textTertiary)
                            .padding(.leading, 2)
                    }

                    HStack(alignment: .bottom, spacing: 1) {
                        if let cw = cwReason, !isCWRevealed {
                            cwBanner(reason: cw)
                        } else {
                            bubbleContent(text: displayText, images: images, cwReason: cwReason)
                        }

                        Text(timeText)
                            .font(.system(size: 10))
                            .foregroundStyle(theme.textTertiary)
                            .padding(.bottom, 2)
                    }
                }

                Spacer(minLength: 40)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func cwBanner(reason: String) -> some View {
        let amber = Color(red: 0.98, green: 0.67, blue: 0.0)
        return Button { isCWRevealed = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(amber)
                Text(reason.isEmpty ? "センシティブな内容" : reason)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(amber)
                Text("表示する")
                    .font(.system(size: 11))
                    .foregroundStyle(theme.textSecondary)
            }
            .padding(.horizontal, NuruSpacing.space3)
            .padding(.vertical, NuruSpacing.space2)
            .background(amber.opacity(0.1))
            .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg))
            .overlay(RoundedRectangle(cornerRadius: NuruSpacing.radiusLg).stroke(amber.opacity(0.3), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func bubbleContent(text: String, images: [URL], cwReason: String?) -> some View {
        VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
            if let cw = cwReason {
                Button { isCWRevealed = false } label: {
                    Text("隠す (\(cw.isEmpty ? "センシティブ" : cw))")
                        .font(.system(size: 10))
                        .foregroundStyle(Color(red: 0.98, green: 0.67, blue: 0.0))
                }
                .buttonStyle(.plain)
            }

            if !text.isEmpty {
                if text.count <= 10 && !text.contains("\n") {
                    Text(text)
                        .font(.system(size: 16))
                        .foregroundStyle(isMine ? .black : theme.textPrimary)
                        .lineSpacing(1)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: true, vertical: true)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .background(isMine ? NuruColors.lineGreen : theme.bgSecondary)
                        .clipShape(
                            UnevenRoundedRectangle(
                                topLeadingRadius: 16,
                                bottomLeadingRadius: isMine ? 16 : 5,
                                bottomTrailingRadius: isMine ? 5 : 16,
                                topTrailingRadius: 16
                            )
                        )
                } else {
                    Text(text)
                        .font(.system(size: 16))
                        .foregroundStyle(isMine ? .black : theme.textPrimary)
                        .lineSpacing(1)
                        .multilineTextAlignment(.leading)
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 11)
                        .padding(.vertical, 7)
                        .frame(maxWidth: 240, alignment: .leading)
                        .background(isMine ? NuruColors.lineGreen : theme.bgSecondary)
                        .clipShape(
                            UnevenRoundedRectangle(
                                topLeadingRadius: 16,
                                bottomLeadingRadius: isMine ? 16 : 5,
                                bottomTrailingRadius: isMine ? 5 : 16,
                                topTrailingRadius: 16
                            )
                        )
                }
            }

            // Inline images
            ForEach(images, id: \.absoluteString) { url in
                AsyncImage(url: url) { phase in
                    if case .success(let img) = phase {
                        img.resizable().scaledToFit()
                            .frame(maxWidth: 280)
                            .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusMd))
                    }
                }
            }
        }
    }
}

// MARK: - Message Input Bar

private struct MessageInputBar: View {
    @Binding var text:      String
    let isSending:          Bool
    let isDisabled:         Bool
    let onSend:             () -> Void
    var onImageAttach:      (() -> Void)? = nil

    @Environment(\.nuruTheme) private var theme
    @FocusState private var focused: Bool

    private var hasText: Bool { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(spacing: 8) {
            Button(action: { onImageAttach?() }) {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(theme.textSecondary)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)

            HStack(spacing: 8) {
                TextField("メッセージを入力", text: $text, axis: .vertical)
                    .font(.system(size: 16))
                    .foregroundStyle(theme.textPrimary)
                    .lineLimit(1...4)
                    .focused($focused)
                    .disabled(isDisabled)

                Button(action: {}) {
                    Image(systemName: NuruIcons.emoji)
                        .font(.system(size: 18))
                        .foregroundStyle(theme.textTertiary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(theme.bgSecondary)
            .clipShape(Capsule())

            Button(action: onSend) {
                if isSending {
                    ProgressView().tint(.white)
                        .frame(width: 38, height: 38)
                } else {
                    Image(systemName: NuruIcons.send)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 38, height: 38)
                        .background(hasText ? NuruColors.lineGreen : NuruColors.lineGreen.opacity(0.4))
                        .clipShape(Circle())
                        .animation(.easeInOut(duration: 0.15), value: hasText)
                }
            }
            .disabled(!hasText || isSending || isDisabled)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(theme.bgPrimary)
    }
}

// MARK: - New Chat Sheet

private struct NewChatSheet: View {
    let onStartChat: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.nuruTheme) private var theme
    @State private var pubkey = ""

    var body: some View {
        VStack(spacing: 0) {
            SheetNavBar(title: "新しいトーク", onDismiss: { dismiss() }) {
                Color.clear.frame(width: 40, height: 40)
            }

            VStack(alignment: .leading, spacing: NuruSpacing.space2) {
                Text("相手のpubkey (npub / hex)")
                    .font(NuruFont.labelSmall())
                    .foregroundStyle(theme.textTertiary)

                TextField("npub1... または hex pubkey", text: $pubkey)
                    .font(NuruFont.bodyMedium())
                    .foregroundStyle(theme.textPrimary)
                    .padding(NuruSpacing.space3)
                    .background(theme.bgSecondary)
                    .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusMd))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)

                Button("トークを開始") {
                    let raw = pubkey.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !raw.isEmpty else { return }
                    let hexPubkey = NostrKeyUtils.parsePublicKey(raw)
                        .map { NostrKeyUtils.bytesToHex($0) } ?? raw
                    onStartChat(hexPubkey)
                }
                .font(NuruFont.buttonMedium())
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, NuruSpacing.space3)
                .background(pubkey.isEmpty ? NuruColors.lineGreen.opacity(0.4) : NuruColors.lineGreen)
                .clipShape(RoundedRectangle(cornerRadius: NuruSpacing.radiusFull))
                .disabled(pubkey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(NuruSpacing.space4)

            Spacer()
        }
        .background(theme.bgPrimary.ignoresSafeArea())
    }
}

// MARK: - Skeleton Views

private struct GroupRowSkeleton: View {
    @Environment(\.nuruTheme) private var theme
    var body: some View {
        HStack(spacing: NuruSpacing.space3) {
            Circle().fill(theme.bgSecondary).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4).fill(theme.bgSecondary).frame(width: 120, height: 14)
                RoundedRectangle(cornerRadius: 4).fill(theme.bgSecondary).frame(width: 200, height: 12)
            }
            Spacer()
        }
        .padding(.horizontal, NuruSpacing.space4)
        .padding(.vertical, NuruSpacing.space3)
        .redacted(reason: .placeholder)
    }
}

private struct MessageBubbleSkeleton: View {
    let alignRight: Bool
    @Environment(\.nuruTheme) private var theme

    // Deterministic widths cycle through fixed values to avoid re-render flicker.
    private static let widths: [CGFloat] = [140, 200, 120, 180, 160]
    private static var counter = 0
    private let width: CGFloat

    init(alignRight: Bool) {
        self.alignRight = alignRight
        self.width = Self.widths[Self.counter % Self.widths.count]
        Self.counter += 1
    }

    var body: some View {
        HStack {
            if alignRight { Spacer() }
            RoundedRectangle(cornerRadius: NuruSpacing.radiusLg)
                .fill(theme.bgSecondary)
                .frame(width: width, height: 40)
            if !alignRight { Spacer() }
        }
        .padding(.horizontal, NuruSpacing.space4)
        .redacted(reason: .placeholder)
    }
}

// MARK: - MLS Recovery Banner (Issue #183)

/// SwiftUI mirror of Android's `MlsRecoveryBanner` (TalkScreen.kt).
/// Surfaces when the Rust deep catch-up reports `notRecoverable`: the
/// missing Commit is no longer retrievable from any configured relay AND
/// is not in the local replay cache. Copy text must match Android
/// pixel-for-pixel per docs/wiki/ui/android-ios-sync.md.
private struct MlsRecoveryBanner: View {
    let isWorking: Bool
    let onRecreate: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("メッセージを完全に復元できません")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color(red: 0x6B/255, green: 0x55/255, blue: 0x00/255))
            Text(
                "相手の最新メッセージを取り戻すために必要なデータがリレーから取得できません。" +
                "会話を作り直すと、相手と再び新しいメッセージをやり取りできます。"
            )
            .font(.system(size: 12))
            .lineSpacing(2)
            .foregroundStyle(Color(red: 0x6B/255, green: 0x55/255, blue: 0x00/255))
            HStack(spacing: 8) {
                Spacer()
                Button(action: onDismiss) {
                    Text("後で")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Color(red: 0x6B/255, green: 0x55/255, blue: 0x00/255))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                }
                .disabled(isWorking)
                Button(action: onRecreate) {
                    Text(isWorking ? "作り直し中…" : "作り直す")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color(red: 0x6B/255, green: 0x55/255, blue: 0x00/255))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(
                            RoundedRectangle(cornerRadius: 16)
                                .fill(Color(red: 0xFF/255, green: 0xE6/255, blue: 0x9C/255))
                        )
                }
                .disabled(isWorking)
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0xFF/255, green: 0xF7/255, blue: 0xE0/255))
    }
}

// MARK: - Filter Enum

private enum TalkFilter {
    case all, friends, groups

    var label: String {
        switch self {
        case .all:     return "すべて"
        case .friends: return "友だち"
        case .groups:  return "グループ"
        }
    }
}
