# Component Map: Web ↔ Android

AI ツールとの作業で参照する CSS → Compose 翻訳辞書。

## トークン参照方法

| Platform | 色 | 余白/角丸 | フォント | アニメーション |
|---|---|---|---|---|
| **Web (CSS)** | `var(--line-green)` | `var(--space-4)` | `var(--font-size-sm)` | `var(--transition-normal)` |
| **Android (Kotlin)** | `NuruTokenColors.LineGreen` | `NuruTokenDimens.Space4` | `NuruTokenDimens.FontSizeSm` | `NuruTokenAnim.DurationNormal` |

## CSS → Compose パターン

| Web (CSS / Tailwind) | Android (Jetpack Compose) | Token Key |
|---|---|---|
| `background: var(--bg-primary)` | `Modifier.background(nuruColors.bgPrimary)` | `color.dark.bgPrimary` |
| `color: var(--text-secondary)` | `color = nuruColors.textSecondary` | `color.dark.textSecondary` |
| `padding: var(--space-4)` | `Modifier.padding(NuruTokenDimens.Space4)` | `spacing.4` |
| `padding: 12px 16px` | `Modifier.padding(horizontal = NuruTokenDimens.Space4, vertical = NuruTokenDimens.Space3)` | — |
| `gap: var(--space-3)` | `Arrangement.spacedBy(NuruTokenDimens.Space3)` | `spacing.3` |
| `border-radius: var(--radius-lg)` | `RoundedCornerShape(NuruTokenDimens.RadiusLg)` | `radius.lg` |
| `border: 1px solid var(--border-color)` | `Modifier.border(1.dp, nuruColors.border, shape)` | `color.dark.border` |
| `font-size: var(--font-size-sm)` | `fontSize = NuruTokenDimens.FontSizeSm` | `typography.fontSize.sm` |
| `box-shadow: var(--shadow-md)` | `Modifier.shadow(NuruTokenDimens.ElevationMd, shape)` | `shadow.md` |
| `transition: var(--transition-normal)` | `animateColorAsState(tween(NuruTokenAnim.DurationNormal))` | `transition.normal` |
| `opacity: 0.7` | `Modifier.alpha(0.7f)` | — |
| `overflow: hidden` | `Modifier.clip(shape)` | — |
| `max-width: 680px` | `Modifier.widthIn(max = 680.dp)` | — |
| `className="btn-line"` | `Button(colors = ButtonDefaults.buttonColors(containerColor = NuruTokenColors.LineGreen))` | `color.brand.lineGreen` |
| `className="action-btn"` `:active { scale(0.9) }` | `Modifier.clickable { }` (Material ripple) | — |

## コンポーネント対応表

| Web | Android | 備考 |
|---|---|---|
| `components/PostItem.js` | `ui/components/PostItem.kt` + `PostContent.kt` + `PostActions.kt` | Android は3ファイルに分割 |
| `components/TimelineTab.js` | `ui/screens/TimelineScreen.kt` + `TimelineComponents.kt` | |
| `components/HomeTab.js` | `ui/screens/HomeScreen.kt` | |
| `components/TalkTab.js` | `ui/screens/TalkScreen.kt` + `TalkComponents.kt` | |
| `components/MiniAppTab.js` | `ui/screens/MainScreen.kt` (settings tab) | |
| `components/BottomNav.js` | `ui/screens/MainScreen.kt` (NavigationBar) | |
| `components/LoginScreen.js` | `ui/screens/LoginScreen.kt` | |
| `components/PostModal.js` | `ui/components/PostModal.kt` | |
| `components/SearchModal.js` | `ui/components/SearchModal.kt` | |
| `components/NotificationModal.js` | `ui/components/NotificationModal.kt` | |
| `components/ZapModal.js` | `ui/components/ZapModal.kt` | |
| `components/UserProfileView.js` | `ui/components/UserProfileModal.kt` + `ProfileComponents.kt` | |
| `components/SignUpModal.js` | `ui/components/SignUpModal.kt` | |
| `components/LongFormPostItem.js` | `ui/components/LongFormPostItem.kt` | |
| `components/URLPreview.js` | `ui/components/URLPreview.kt` | |
| `components/BadgeDisplay.js` | `ui/components/BadgeDisplay.kt` | |
| `components/BirdwatchDisplay.js` | `ui/components/BirdwatchDisplay.kt` | |
| `components/BirdwatchModal.js` | `ui/components/BirdwatchModal.kt` | |
| `components/EmojiPicker.js` | `ui/components/EmojiPicker.kt` | |
| `components/ReactionEmojiPicker.js` | `ui/components/ReactionEmojiPicker.kt` | |
| `components/ReportModal.js` | `ui/components/ReportModal.kt` | |
| `components/DivineVideoRecorder.js` | `ui/components/DivineVideoRecorder.kt` | |
| `components/ErrorBoundary.js` | `ui/components/ErrorBoundary.kt` | |
| `components/SkeletonLoader.js` | `ui/components/SkeletonLoader.kt` | |
| `components/Toast.js` | `ui/components/Toast.kt` | |
| `components/miniapps/*.js` | `ui/miniapps/*.kt` | 1:1 対応 |
| — | `ui/screens/BiometricUnlockScreen.kt` | Android 固有 |
| — | `ui/components/VideoPlayer.kt` | ExoPlayer (Android 固有) |
| — | `ui/components/UserAvatar.kt` | Compose 用に分離 |

## 既知の色ズレ

| 箇所 | Web 値 | Android 値 (現在) | tokens.json |
|---|---|---|---|
| Zap | `--color-zap: #FFB74D` | `Color(0xFFFFC107)` | `#FFB74D` に統一 |
| Warning | `--color-warning: #FFCC80` | `Color(0xFFFF9800)` | `#FFCC80` に統一 |
| Birdwatch | `--color-info: #90CAF9` | `Color(0xFF2196F3)` | 専用トークン `#2196F3` 新設 |
