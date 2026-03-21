# iOS Design Document — null--nostr (ぬるぬる)

## Overview

iOS native client for null--nostr, a LINE-style Nostr client for the Japanese community.
Must be pixel-identical to Android in layout, color, font, and UX flow.

---

## Platform Stack

| Layer | Technology |
|-------|-----------|
| UI | SwiftUI (iOS 17+) |
| Navigation | NavigationStack + TabView |
| State | @Observable (Observation framework, iOS 17) |
| Networking | URLSession WebSocket / NWConnection |
| Crypto | CryptoKit + secp256k1 (via swift-secp256k1) |
| Image Loading | Nuke (or AsyncImage with cache) |
| Video | AVPlayer |
| Camera | AVFoundation |
| Storage | SwiftData or UserDefaults + Keychain |
| Rust FFI | UniFFI → Swift bindings (nurunuru-core shared with Android) |

---

## Screen Map (mirrors Android exactly)

### Navigation: 4-tab TabView (bottom)

| Tab | Icon | Screen | Android Equivalent |
|-----|------|--------|--------------------|
| ホーム | house | HomeView | HomeScreen.kt |
| トーク | bubble.left.and.bubble.right | TalkView | TalkScreen.kt |
| タイムライン | doc.richtext | TimelineView | TimelineScreen.kt |
| ミニアプリ | square.grid.2x2 | SettingsView | SettingsScreen.kt |

### Screen Details

#### LoginView (→ LoginScreen.kt)
- nsec input field (SecureField)
- Nostr Connect (NIP-46) button — iOS equivalent of Amber/NIP-55
- Sign-up modal sheet
- Deep link: `nurunuru://login?nsec=...`

#### HomeView (→ HomeScreen.kt)
- Banner image + avatar overlay
- Display name, NIP-05 badge, about
- Follow/following counts (tappable → FollowListSheet)
- Tab: 投稿 / いいね
- Post list (reuses PostRow)

#### TimelineView (→ TimelineScreen.kt)
- TabView with .page style: フォロー / おすすめ
- Pull-to-refresh
- Floating compose button (bottom-right)
- Skeleton loading placeholders
- No entrance animations (performance)

#### TalkView (→ TalkScreen.kt)
- Group list (NIP-EE / MLS)
- Active conversation view (message bubbles)
- Create group sheet
- Group info sheet (members, admin controls)

#### SettingsView (→ SettingsScreen.kt)
- Mini-app grid: エンタメ / ツール / その他
- Relay management
- Cache settings
- Biometric toggle
- Badge / Emoji / Zap settings

### Modal Sheets (presented as .sheet or .fullScreenCover)

| Sheet | Android Equivalent |
|-------|--------------------|
| PostSheet | PostModal.kt |
| SearchSheet | SearchModal.kt |
| ZapSheet | ZapModal.kt |
| NotificationSheet | NotificationModal.kt |
| UserProfileSheet | UserProfileModal.kt |
| EditProfileSheet | EditProfileModal.kt |
| EmojiPickerSheet | EmojiPicker.kt |
| ReactionPickerSheet | ReactionEmojiPicker.kt |
| FollowListSheet | FollowListModal.kt |
| ReportSheet | ReportModal.kt |
| BirdwatchSheet | BirdwatchModal.kt |
| ImageViewerView | ImageViewerDialog.kt |
| GroupInfoSheet | GroupInfoModal.kt |
| CreateGroupSheet | CreateGroupModal.kt |

---

## Design Tokens (from design-tokens/constants.json)

### Colors

```swift
// Brand
static let lineGreen      = Color(hex: "#06C755")
static let lineGreenDark  = Color(hex: "#05A347")
static let lineGreenLight = Color(hex: "#06C755").opacity(0.1)

// Dark Mode (default)
static let bgPrimary       = Color(hex: "#0A0A0A")
static let bgSecondary     = Color(hex: "#1C1C1E")
static let bgTertiary      = Color(hex: "#2C2C2E")
static let textPrimary     = Color(hex: "#F5F5F5")
static let textSecondary   = Color(hex: "#B3B3B3")
static let textTertiary    = Color(hex: "#8A8A8A")
static let borderColor     = Color(hex: "#38383A")
static let borderStrong    = Color(hex: "#48484A")

// Light Mode
static let bgPrimaryLight     = Color(hex: "#FFFFFF")
static let bgSecondaryLight   = Color(hex: "#F7F8FA")
static let bgTertiaryLight    = Color(hex: "#EBEDEF")
static let textPrimaryLight   = Color(hex: "#1A1A1A")
static let textSecondaryLight = Color(hex: "#555555")
static let textTertiaryLight  = Color(hex: "#767676")
static let borderColorLight   = Color(hex: "#E8E8E8")

// Semantic
static let colorError     = Color(hex: "#EF9A9A")
static let colorWarning   = Color(hex: "#FFCC80")
static let colorZap       = Color(hex: "#FFB74D")
static let colorInfo      = Color(hex: "#90CAF9")
static let colorSuccess   = Color(hex: "#06C755")
static let colorEncourage = Color(hex: "#81C784")
static let colorBirdwatch = Color(hex: "#2196F3")
```

### Typography

**Font: LINE Seed JP** (must be bundled in app)
- Regular: LineSeedJP-Rg.ttf
- Bold: LineSeedJP-Bd.ttf

```swift
// Text Styles (matching Android Type.kt)
.bodyLarge:   16pt, regular, lineHeight 24pt
.bodyMedium:  14pt, regular, lineHeight 20pt
.bodySmall:   12pt, regular, lineHeight 16pt
.titleLarge:  20pt, semibold, lineHeight 28pt
.titleMedium: 16pt, semibold, lineHeight 24pt
.labelSmall:  10pt, medium,   lineHeight 14pt
```

### Spacing (8px grid)

```swift
static let space1:  CGFloat = 4
static let space2:  CGFloat = 8
static let space3:  CGFloat = 12
static let space4:  CGFloat = 16
static let space5:  CGFloat = 24
static let space6:  CGFloat = 32
static let space7:  CGFloat = 40
static let space8:  CGFloat = 48
static let space9:  CGFloat = 56
static let space10: CGFloat = 64
```

### Corner Radius

```swift
static let radiusSm:   CGFloat = 4
static let radiusMd:   CGFloat = 8
static let radiusLg:   CGFloat = 12
static let radiusXl:   CGFloat = 16
static let radius2xl:  CGFloat = 24
static let radiusFull: CGFloat = 9999
```

### Animation

```swift
static let durationFast:   Double = 0.15
static let durationNormal: Double = 0.20
static let durationSlow:   Double = 0.30
```

---

## Architecture

### Directory Structure

```
ios/NuruNuru/
├── NuruNuruApp.swift              # @main entry point
├── Info.plist
├── Assets.xcassets/               # Colors, icons, app icon
├── Resources/
│   └── Fonts/                     # LineSeedJP-Rg.ttf, LineSeedJP-Bd.ttf
├── Theme/
│   ├── NuruColors.swift           # Color tokens (auto-generated from tokens.json)
│   ├── NuruTypography.swift       # Font definitions
│   ├── NuruSpacing.swift          # Spacing, radius, elevation
│   └── NuruTheme.swift            # Environment-based theme provider
├── Models/
│   ├── NostrEvent.swift
│   ├── UserProfile.swift
│   ├── ScoredPost.swift
│   ├── MlsGroup.swift
│   ├── MlsMessage.swift
│   ├── NotificationItem.swift
│   └── NostrKind.swift
├── Data/
│   ├── NostrRepository.swift      # Single data access point
│   ├── NostrClient.swift          # WebSocket relay connections
│   ├── ConnectionManager.swift    # Pool, rate limiting, cooldowns
│   ├── NostrCache.swift           # Two-layer cache (memory + disk)
│   ├── SecureKeyManager.swift     # Keychain storage
│   ├── ExternalSigner.swift       # NIP-46 (Nostr Connect)
│   ├── InternalSigner.swift       # nsec-based signing
│   ├── AppPreferences.swift       # UserDefaults + Keychain prefs
│   └── RecommendationEngine.swift # Feed scoring (or via Rust FFI)
├── ViewModels/
│   ├── AuthViewModel.swift
│   ├── TimelineViewModel.swift
│   ├── HomeViewModel.swift
│   ├── TalkViewModel.swift
│   └── ConnectionViewModel.swift
├── Views/
│   ├── Screens/
│   │   ├── LoginView.swift
│   │   ├── MainTabView.swift      # Root TabView
│   │   ├── HomeView.swift
│   │   ├── TimelineView.swift
│   │   ├── TalkView.swift
│   │   └── SettingsView.swift
│   ├── Components/
│   │   ├── PostRow.swift           # → PostItem.kt
│   │   ├── PostActions.swift       # → PostActions.kt
│   │   ├── PostContent.swift       # → PostContent.kt
│   │   ├── PostSheet.swift         # → PostModal.kt
│   │   ├── PostImageGrid.swift     # 1/2/3/4+ image layouts
│   │   ├── UserAvatar.swift
│   │   ├── URLPreview.swift
│   │   ├── MarkdownContent.swift
│   │   ├── VideoPlayerView.swift
│   │   ├── ImageViewerView.swift   # Fullscreen pinch-zoom
│   │   ├── SkeletonLoader.swift
│   │   ├── Toast.swift
│   │   └── ErrorBoundary.swift
│   ├── Sheets/
│   │   ├── SearchSheet.swift
│   │   ├── ZapSheet.swift
│   │   ├── NotificationSheet.swift
│   │   ├── UserProfileSheet.swift
│   │   ├── EditProfileSheet.swift
│   │   ├── EmojiPickerSheet.swift
│   │   ├── ReactionPickerSheet.swift
│   │   ├── FollowListSheet.swift
│   │   ├── ReportSheet.swift
│   │   ├── BirdwatchSheet.swift
│   │   ├── GroupInfoSheet.swift
│   │   ├── CreateGroupSheet.swift
│   │   └── SignUpSheet.swift
│   └── MiniApps/
│       ├── BadgeSettingsView.swift
│       ├── EmojiSettingsView.swift
│       ├── ZapSettingsView.swift
│       ├── EventBackupView.swift
│       ├── MuteListView.swift
│       ├── CacheSettingsView.swift
│       └── NostrBrowserView.swift
└── Utilities/
    ├── Extensions/
    │   ├── Color+Hex.swift
    │   ├── Date+Relative.swift
    │   └── String+Nostr.swift
    ├── Security.swift              # Content sanitization
    ├── Validation.swift            # URL, pubkey, NIP-05 validation
    └── Constants.swift             # Auto-generated from tokens.json
```

### Data Flow

```
View (SwiftUI)
  ↓ user action
ViewModel (@Observable)
  ↓ method call
NostrRepository (actor)
  ↓ relay operation
NostrClient (WebSocket)
  ↓ event
Relay → parse → cache → ViewModel state update → View recomposition
```

### State Management Pattern

```swift
@Observable
final class TimelineViewModel {
    var globalPosts: [ScoredPost] = []
    var followingPosts: [ScoredPost] = []
    var isLoading = false

    private let repository: NostrRepository

    func loadFollowingTimeline() async { ... }
    func loadGlobalTimeline() async { ... }
    func likePost(_ post: ScoredPost) async { ... }
}
```

### Concurrency

- Swift Concurrency (async/await, actors) throughout
- `NostrRepository` as an `actor` for thread-safe data access
- `NostrClient` WebSocket on dedicated task
- Image uploads: `TaskGroup` for parallel upload (max 3)

---

## Key Implementation Constraints (iOS-specific)

### UI
- Post length: 140 characters (same as Android, strictly enforced)
- Max images per post: 3
- No entrance animations in timeline (performance)
- Image grid: same layout as Android (1=full, 2=side-by-side, 3=left-tall+right-stacked)
- Full-screen modals: use `.fullScreenCover` for image viewer, `.sheet` for others
- Pull-to-refresh: native `.refreshable` modifier
- Skeleton loading: match Android placeholder count (timeline 5, profile 3)

### Security
- Private keys in Keychain only (kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
- Never expose keys to pasteboard or logs
- Content sanitization before rendering any user-generated HTML/markdown
- Face ID / Touch ID optional binding

### Performance
- `LazyVStack` with `.onAppear` pagination (not `.task`)
- `remember(post.event.id)` → use `.id(post.event.id)` for stable identity
- Image caching via Nuke (disk + memory, LRU)
- Avoid `@State` arrays in hot paths; use `@Observable` ViewModel

### Networking
- Same rate limits as Android: 10 req/s, burst 20
- Max 4 global / 2 per-relay concurrent connections
- Relay cooldown: 120s after 3 failures
- EOSE timeout: 15s

### NIP-46 (Nostr Connect) — iOS equivalent of Amber/NIP-55
- No NIP-55 on iOS (Android-only external signer)
- Use NIP-46 remote signer protocol instead
- Connect via WebSocket relay to remote signer

---

## Rust FFI Integration Plan

### Phase 1: Pure Swift (MVP)
- Implement Nostr protocol directly in Swift
- Use swift-secp256k1 for crypto
- Direct WebSocket connections
- Goal: functional app without Rust dependency

### Phase 2: Rust Core via UniFFI
- Build nurunuru-core for iOS targets (aarch64-apple-ios)
- Generate Swift bindings via UniFFI
- Share recommendation engine, relay management, crypto with Android
- Replaces pure Swift Nostr layer

### Build Commands (Phase 2)
```bash
# Cross-compile for iOS
cargo build --release --target aarch64-apple-ios -p nurunuru-ffi

# Generate Swift bindings
cd rust-engine/nurunuru-ffi && bash bindgen/gen_swift.sh

# Create XCFramework
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libuniffi_nurunuru.a \
  -headers rust-engine/nurunuru-ffi/bindgen/swift-out/ \
  -output ios/NuruNuru/Frameworks/NuruNuruFFI.xcframework
```

---

## Supported NIPs (same as Android)

01, 02, 05, 07, 09, 11, 17, 19, 25, 27, 30, 32, 42, 44, 46, 50, 51, 57, 58, 59, 62, 65, 70, 71, 98

---

## Dependencies (Swift Package Manager)

| Package | Purpose |
|---------|---------|
| swift-secp256k1 | Nostr key generation, signing, verification |
| Nuke / NukeUI | Image loading and caching |
| swift-markdown-ui | Markdown rendering |
| KeychainAccess | Simplified Keychain API |

Minimize dependencies. Prefer Apple frameworks (URLSession, AVFoundation, CryptoKit) over third-party.

---

## Development Phases

### Phase 1: Foundation (Week 1-2)
- [ ] Xcode project setup with SPM
- [ ] Theme system (colors, typography, spacing from tokens)
- [ ] LINE Seed JP font integration
- [ ] Nostr key management (generate, import nsec, Keychain storage)
- [ ] WebSocket relay connection (single relay)
- [ ] Basic event model (NostrEvent, kind 0/1)

### Phase 2: Core Screens (Week 3-4)
- [ ] LoginView (nsec input, key generation)
- [ ] MainTabView (4-tab navigation)
- [ ] TimelineView (fetch kind 1, display PostRow)
- [ ] PostRow component (avatar, name, content, timestamp)
- [ ] PostActions (like, repost, reply, zap counts)
- [ ] Pull-to-refresh, pagination

### Phase 3: Profile & Interaction (Week 5-6)
- [ ] HomeView (profile display, user's posts/likes)
- [ ] PostSheet (compose, 140-char limit, image upload)
- [ ] Like/Repost toggle (kind 7 / kind 6, with undo)
- [ ] UserProfileSheet
- [ ] FollowListSheet
- [ ] NotificationSheet

### Phase 4: Messaging & Polish (Week 7-8)
- [ ] TalkView (NIP-EE / MLS groups)
- [ ] SearchSheet
- [ ] EmojiPickerSheet / ReactionPickerSheet
- [ ] ZapSheet (NIP-57)
- [ ] Settings & mini-apps
- [ ] Face ID / biometric auth
- [ ] Image viewer (pinch-zoom)
- [ ] Skeleton loaders, error handling, polish

### Phase 5: Rust FFI & Release (Week 9+)
- [ ] UniFFI Swift binding generation
- [ ] Replace pure Swift layer with Rust core
- [ ] Recommendation engine via FFI
- [ ] TestFlight beta
- [ ] App Store submission
