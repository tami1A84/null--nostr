import SwiftUI

/// Repost / Like / Zap action row beneath each post.
/// Mirrors Android PostActions.kt layout and interaction model.
/// NOTE: Reply button is intentionally absent (Android PostActions.kt has no reply button).
struct PostActions: View {

    let post: ScoredPost
    var onLike:            () async -> Void    = {}
    var onLikeLongPress:   (() -> Void)?       = nil
    var onRepost:          () async -> Void    = {}
    var onRepostLongPress: (() -> Void)?       = nil   // 長押し → 引用リポスト
    var onZap:             (() -> Void)?       = nil
    var onZapLongPress:    (() -> Void)?       = nil
    var onBookmark:        (() async -> Void)? = nil

    @Environment(\.nuruTheme) private var theme
    @State private var isLikeAnimating = false
    @State private var isLikeInFlight = false
    @State private var isRepostInFlight = false
    @State private var isBookmarkInFlight = false

    var body: some View {
        // Mirrors Android PostActions: Row with spacedBy(24.dp), buttons auto-sized,
        // Spacer(weight(1f)) pushes "via client" to trailing edge.
        HStack(spacing: 0) {
            HStack(spacing: 20) {
                likeButton
                repostButton
                zapButton
                bookmarkButton
            }
            Spacer(minLength: 4)
            viaClientLabel
        }
    }

    // MARK: - Via Client

    @ViewBuilder
    private var viaClientLabel: some View {
        if let client = post.event.getTagValue("client"), !client.isEmpty {
            Text("via \(client)")
                .font(.system(size: 10))
                .foregroundStyle(theme.textTertiary.opacity(0.6))
                .lineLimit(1)
                .fixedSize()   // 省略せず全文表示 — mirrors Android single-line text
        }
    }

    // MARK: - Buttons

    private var repostButton: some View {
        let label = HStack(spacing: 4) {
            RepostIcon()
                .frame(width: NuruSpacing.iconMd, height: NuruSpacing.iconMd)
            if post.repostCount > 0 {
                Text(formatCount(post.repostCount))
                    .font(NuruFont.bodySmall())
            }
        }
        .foregroundStyle(post.isReposted ? NuruColors.lineGreen : theme.textTertiary)

        return label
            .contentShape(Rectangle())
            .opacity(isRepostInFlight ? 0.45 : 1.0)
            .simultaneousGesture(TapGesture().onEnded {
                guard !isRepostInFlight else { return }
                isRepostInFlight = true
                Task {
                    await onRepost()
                    isRepostInFlight = false
                }
            })
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5).onEnded { _ in onRepostLongPress?() }
            )
            .accessibilityLabel(post.isReposted ? "リポストを取り消す" : "リポスト")
            .accessibilityValue(post.repostCount > 0 ? "\(post.repostCount)件" : "")
    }

    private var likeButton: some View {
        let label = HStack(spacing: 4) {
            LikeIcon(filled: post.isLiked)
                .frame(width: NuruSpacing.iconMd, height: NuruSpacing.iconMd)
                .scaleEffect(isLikeAnimating ? 1.3 : 1.0)
            if post.likeCount > 0 {
                Text(formatCount(post.likeCount))
                    .font(NuruFont.bodySmall())
            }
        }
        .foregroundStyle(post.isLiked ? NuruColors.lineGreen : theme.textTertiary)

        return label
            .contentShape(Rectangle())
            .opacity(isLikeInFlight ? 0.45 : 1.0)
            .simultaneousGesture(TapGesture().onEnded {
                guard !isLikeInFlight else { return }
                isLikeInFlight = true
                withAnimation(.spring(response: 0.2, dampingFraction: 0.4)) { isLikeAnimating = true }
                Task {
                    await onLike()
                    try? await Task.sleep(nanoseconds: 300_000_000)
                    withAnimation { isLikeAnimating = false }
                    isLikeInFlight = false
                }
            })
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5).onEnded { _ in onLikeLongPress?() }
            )
            .accessibilityLabel(post.isLiked ? "リアクションを取り消す" : "リアクション")
            .accessibilityValue(post.likeCount > 0 ? "\(post.likeCount)件" : "")
    }

    private var zapButton: some View {
        let zapLabel = HStack(spacing: 4) {
            BitcoinIcon()
                .frame(width: NuruSpacing.iconMd, height: NuruSpacing.iconMd)
            if post.zapAmount > 0 {
                Text(formatZap(post.zapAmount))
                    .font(NuruFont.bodySmall())
            }
        }
        .foregroundStyle(theme.textTertiary)

        return zapLabel
            .contentShape(Rectangle())
            .simultaneousGesture(TapGesture().onEnded { onZap?() })
            .simultaneousGesture(
                LongPressGesture(minimumDuration: 0.5).onEnded { _ in onZapLongPress?() }
            )
            .accessibilityLabel("Zap")
            .accessibilityValue(post.zapAmount > 0 ? "\(post.zapAmount) sats" : "")
    }

    private var bookmarkButton: some View {
        BookmarkIcon(filled: post.isBookmarked)
            .frame(width: NuruSpacing.iconMd, height: NuruSpacing.iconMd)
            .foregroundStyle(post.isBookmarked ? NuruColors.lineGreen : theme.textTertiary)
            .contentShape(Rectangle())
            .opacity(isBookmarkInFlight ? 0.45 : 1.0)
            .onTapGesture {
                guard let handler = onBookmark, !isBookmarkInFlight else { return }
                isBookmarkInFlight = true
                Task {
                    await handler()
                    isBookmarkInFlight = false
                }
            }
            .accessibilityLabel(post.isBookmarked ? "ブックマークを外す" : "ブックマーク")
    }

    // MARK: - Helpers

    private func formatCount(_ n: Int) -> String { n >= 1000 ? "\(n / 1000)K" : "\(n)" }
    private func formatZap(_ n: Int64) -> String { n >= 1000 ? "\(n / 1000)K" : "\(n)" }
}
