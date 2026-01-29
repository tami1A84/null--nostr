# マルチプラットフォームアーキテクチャ再設計プラン

> **「ぬるぬる」Nostr クライアント - 設計見直し提案書**
>
> 作成日: 2026-01-29

---

## 目次

1. [現状の問題点サマリー](#1-現状の問題点サマリー)
2. [ターゲットプラットフォーム](#2-ターゲットプラットフォーム)
3. [新アーキテクチャ概要](#3-新アーキテクチャ概要)
4. [Phase 1: 抽象化レイヤーの構築](#4-phase-1-抽象化レイヤーの構築)
5. [Phase 2: 状態管理の統一](#5-phase-2-状態管理の統一)
6. [Phase 3: コンポーネントの分割](#6-phase-3-コンポーネントの分割)
7. [Phase 4: プラットフォーム固有実装](#7-phase-4-プラットフォーム固有実装)
8. [Phase 5: テスト戦略](#8-phase-5-テスト戦略)
9. [実装ロードマップ](#9-実装ロードマップ)
10. [リスクと対策](#10-リスクと対策)

---

## 1. 現状の問題点サマリー

### 1.1 致命的な問題（マルチプラットフォーム化を阻害）

| 問題 | 影響箇所 | 深刻度 |
|------|---------|--------|
| **localStorage 直接依存** | 127箇所（20+ファイル） | 🔴 Critical |
| **window オブジェクト依存** | 66箇所（認証・署名全般） | 🔴 Critical |
| **DOM API 直接操作** | 30+箇所 | 🟠 High |
| **巨大コンポーネント** | 4ファイル（各2000行超） | 🟠 High |
| **状態管理の分散** | 384個のuseState | 🟠 High |

### 1.2 現状のコード量

```
コンポーネント層:  ~12,000行 (26コンポーネント)
ロジック層:        ~8,000行 (lib/*.js)
設定・その他:      ~2,000行
────────────────────────────
合計:             ~22,000行
```

### 1.3 プラットフォーム依存の具体例

```javascript
// ❌ 現状: localStorage に直接アクセス
const pubkey = localStorage.getItem('user_pubkey')
localStorage.setItem('profile_' + pubkey, JSON.stringify(profile))

// ❌ 現状: window オブジェクトに依存
const signer = window.nostr || window.nosskeyManager
await signer.signEvent(event)

// ❌ 現状: DOM に直接アクセス
document.body.style.overflow = 'hidden'
createPortal(content, document.body)
```

---

## 2. ターゲットプラットフォーム

### 2.1 対応プラットフォーム

| プラットフォーム | 技術スタック | 優先度 |
|----------------|-------------|--------|
| **Web (PWA)** | Next.js + Service Worker | 🥇 Primary |
| **Android** | Capacitor (WebView) | 🥈 Secondary |
| **iOS** | Capacitor (WebView) | 🥈 Secondary |
| **Desktop** | Electron / Tauri | 🥉 Future |
| **React Native** | 将来の選択肢 | 📋 Planned |

### 2.2 各プラットフォームの特性

```
┌─────────────────────────────────────────────────────────────┐
│                    共通コードベース (80%+)                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           ビジネスロジック / Nostr プロトコル          │   │
│  │     (イベント処理、暗号化、リレー通信、検証)           │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              UIコンポーネント (React)                  │   │
│  │     (フィード、プロフィール、設定画面、モーダル)        │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│   Web Adapter   │ │ Capacitor Adapt │ │ Electron Adapt  │
│  (~5% コード)    │ │  (~10% コード)   │ │  (~5% コード)    │
├─────────────────┤ ├─────────────────┤ ├─────────────────┤
│ - localStorage  │ │ - Preferences   │ │ - electron-store│
│ - window.nostr  │ │ - Amber Intent  │ │ - IPC signing   │
│ - ServiceWorker │ │ - Push Plugin   │ │ - File System   │
│ - WebSocket     │ │ - WebSocket     │ │ - Node WebSocket│
└─────────────────┘ └─────────────────┘ └─────────────────┘
```

---

## 3. 新アーキテクチャ概要

### 3.1 ディレクトリ構造（提案）

```
src/
├── core/                          # プラットフォーム非依存コア
│   ├── nostr/                     # Nostr プロトコル
│   │   ├── events.ts              # イベント作成・検証
│   │   ├── relay.ts               # リレー通信抽象化
│   │   ├── crypto.ts              # 暗号化 (NIP-04/44)
│   │   ├── nips/                  # NIP 別実装
│   │   │   ├── nip-05.ts          # メール認証
│   │   │   ├── nip-17.ts          # 暗号化DM
│   │   │   ├── nip-57.ts          # Zaps
│   │   │   └── ...
│   │   └── types.ts               # 型定義
│   │
│   ├── store/                     # 状態管理 (Zustand)
│   │   ├── index.ts               # Store エクスポート
│   │   ├── slices/
│   │   │   ├── auth.ts            # 認証状態
│   │   │   ├── timeline.ts        # タイムライン
│   │   │   ├── profile.ts         # プロフィール
│   │   │   ├── settings.ts        # 設定
│   │   │   └── dm.ts              # DM
│   │   └── persist.ts             # 永続化アダプター
│   │
│   └── utils/                     # 共通ユーティリティ
│       ├── validation.ts
│       ├── security.ts
│       └── errors.ts
│
├── adapters/                      # プラットフォーム抽象化
│   ├── storage/                   # ストレージ
│   │   ├── StorageAdapter.ts      # インターフェース
│   │   ├── WebStorage.ts          # localStorage
│   │   ├── CapacitorStorage.ts    # Preferences
│   │   └── ElectronStorage.ts     # electron-store
│   │
│   ├── signing/                   # 署名
│   │   ├── SigningAdapter.ts      # インターフェース
│   │   ├── Nip07Signer.ts         # window.nostr
│   │   ├── NosskeyPigner.ts       # Nosskey SDK
│   │   ├── AmberSigner.ts         # Android Amber
│   │   ├── BunkerSigner.ts        # NIP-46
│   │   └── MemorySigner.ts        # 秘密鍵直接保持
│   │
│   ├── clipboard/                 # クリップボード
│   │   ├── ClipboardAdapter.ts
│   │   ├── WebClipboard.ts
│   │   └── CapacitorClipboard.ts
│   │
│   ├── geolocation/               # 位置情報
│   │   ├── GeolocationAdapter.ts
│   │   ├── WebGeolocation.ts
│   │   └── CapacitorGeolocation.ts
│   │
│   └── network/                   # ネットワーク状態
│       ├── NetworkAdapter.ts
│       └── ...
│
├── platform/                      # プラットフォーム検出・初期化
│   ├── detect.ts                  # プラットフォーム判定
│   ├── web.ts                     # Web 初期化
│   ├── capacitor.ts               # Capacitor 初期化
│   └── electron.ts                # Electron 初期化
│
├── ui/                            # UI コンポーネント
│   ├── components/                # 再利用可能コンポーネント
│   │   ├── common/                # 共通UI
│   │   │   ├── Button.tsx
│   │   │   ├── Modal.tsx
│   │   │   ├── Toast.tsx
│   │   │   └── ...
│   │   ├── post/                  # 投稿関連
│   │   │   ├── PostItem.tsx
│   │   │   ├── PostEditor.tsx
│   │   │   └── ...
│   │   ├── profile/               # プロフィール関連
│   │   │   ├── ProfileCard.tsx
│   │   │   ├── ProfileEditor.tsx
│   │   │   └── ...
│   │   └── ...
│   │
│   ├── screens/                   # 画面単位
│   │   ├── Timeline/
│   │   │   ├── index.tsx
│   │   │   ├── TimelineHeader.tsx
│   │   │   ├── TimelineList.tsx
│   │   │   └── useTimeline.ts     # 画面固有フック
│   │   ├── DirectMessage/
│   │   ├── Profile/
│   │   ├── Settings/
│   │   └── Login/
│   │
│   └── hooks/                     # 共通フック
│       ├── useNostrSubscription.ts
│       ├── useProfile.ts
│       └── ...
│
├── app/                           # Next.js App Router (Web)
│   ├── layout.tsx
│   ├── page.tsx
│   └── api/
│
└── index.ts                       # エントリーポイント
```

### 3.2 依存関係の方向

```
                 ┌──────────────────┐
                 │    UI Layer      │
                 │   (React/UI)     │
                 └────────┬─────────┘
                          │ uses
                          ▼
                 ┌──────────────────┐
                 │   Core Layer     │
                 │  (Business Logic)│
                 └────────┬─────────┘
                          │ uses (via interface)
                          ▼
                 ┌──────────────────┐
                 │  Adapter Layer   │◄─── Interface のみ依存
                 │  (Abstractions)  │     実装は注入
                 └────────┬─────────┘
                          │ implements
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
     ┌─────────┐    ┌─────────┐    ┌─────────┐
     │   Web   │    │Capacitor│    │Electron │
     └─────────┘    └─────────┘    └─────────┘
```

---

## 4. Phase 1: 抽象化レイヤーの構築

### 4.1 Storage Adapter

```typescript
// src/adapters/storage/StorageAdapter.ts
export interface StorageAdapter {
  getItem(key: string): Promise<string | null>
  setItem(key: string, value: string): Promise<void>
  removeItem(key: string): Promise<void>
  clear(): Promise<void>
  keys(): Promise<string[]>
}

// src/adapters/storage/WebStorage.ts
export class WebStorage implements StorageAdapter {
  async getItem(key: string): Promise<string | null> {
    if (typeof window === 'undefined') return null
    return localStorage.getItem(key)
  }

  async setItem(key: string, value: string): Promise<void> {
    if (typeof window === 'undefined') return
    localStorage.setItem(key, value)
  }

  async removeItem(key: string): Promise<void> {
    if (typeof window === 'undefined') return
    localStorage.removeItem(key)
  }

  async clear(): Promise<void> {
    if (typeof window === 'undefined') return
    localStorage.clear()
  }

  async keys(): Promise<string[]> {
    if (typeof window === 'undefined') return []
    return Object.keys(localStorage)
  }
}

// src/adapters/storage/CapacitorStorage.ts
import { Preferences } from '@capacitor/preferences'

export class CapacitorStorage implements StorageAdapter {
  async getItem(key: string): Promise<string | null> {
    const { value } = await Preferences.get({ key })
    return value
  }

  async setItem(key: string, value: string): Promise<void> {
    await Preferences.set({ key, value })
  }

  async removeItem(key: string): Promise<void> {
    await Preferences.remove({ key })
  }

  async clear(): Promise<void> {
    await Preferences.clear()
  }

  async keys(): Promise<string[]> {
    const { keys } = await Preferences.keys()
    return keys
  }
}
```

### 4.2 Signing Adapter

```typescript
// src/adapters/signing/SigningAdapter.ts
import type { Event, UnsignedEvent } from 'nostr-tools'

export interface SigningAdapter {
  /** 公開鍵を取得 */
  getPublicKey(): Promise<string>

  /** イベントに署名 */
  signEvent(event: UnsignedEvent): Promise<Event>

  /** NIP-04 暗号化 (レガシー) */
  nip04Encrypt(pubkey: string, plaintext: string): Promise<string>
  nip04Decrypt(pubkey: string, ciphertext: string): Promise<string>

  /** NIP-44 暗号化 (推奨) */
  nip44Encrypt(pubkey: string, plaintext: string): Promise<string>
  nip44Decrypt(pubkey: string, ciphertext: string): Promise<string>

  /** 署名方法の識別子 */
  readonly type: SignerType

  /** 機能サポート確認 */
  supports(feature: SignerFeature): boolean
}

export type SignerType =
  | 'nip07'      // window.nostr (NIP-07)
  | 'nosskey'    // Nosskey (Passkey)
  | 'amber'      // Amber (Android)
  | 'bunker'     // NIP-46 Remote Signer
  | 'memory'     // 秘密鍵メモリ保持
  | 'nsec-app'   // nsec.app

export type SignerFeature =
  | 'nip04'
  | 'nip44'
  | 'delegation'
  | 'getRelays'

// src/adapters/signing/Nip07Signer.ts
export class Nip07Signer implements SigningAdapter {
  readonly type = 'nip07' as const

  private get nostr() {
    if (typeof window === 'undefined' || !window.nostr) {
      throw new SignerNotAvailableError('NIP-07 extension not found')
    }
    return window.nostr
  }

  async getPublicKey(): Promise<string> {
    return this.nostr.getPublicKey()
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    return this.nostr.signEvent(event)
  }

  async nip04Encrypt(pubkey: string, plaintext: string): Promise<string> {
    if (!this.nostr.nip04?.encrypt) {
      throw new FeatureNotSupportedError('NIP-04 encryption')
    }
    return this.nostr.nip04.encrypt(pubkey, plaintext)
  }

  async nip04Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    if (!this.nostr.nip04?.decrypt) {
      throw new FeatureNotSupportedError('NIP-04 decryption')
    }
    return this.nostr.nip04.decrypt(pubkey, ciphertext)
  }

  async nip44Encrypt(pubkey: string, plaintext: string): Promise<string> {
    if (!this.nostr.nip44?.encrypt) {
      throw new FeatureNotSupportedError('NIP-44 encryption')
    }
    return this.nostr.nip44.encrypt(pubkey, plaintext)
  }

  async nip44Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    if (!this.nostr.nip44?.decrypt) {
      throw new FeatureNotSupportedError('NIP-44 decryption')
    }
    return this.nostr.nip44.decrypt(pubkey, ciphertext)
  }

  supports(feature: SignerFeature): boolean {
    switch (feature) {
      case 'nip04':
        return !!this.nostr.nip04
      case 'nip44':
        return !!this.nostr.nip44
      case 'getRelays':
        return !!this.nostr.getRelays
      default:
        return false
    }
  }
}
```

### 4.3 Platform Detection

```typescript
// src/platform/detect.ts
export type Platform = 'web' | 'capacitor-android' | 'capacitor-ios' | 'electron' | 'unknown'

export function detectPlatform(): Platform {
  // SSR check
  if (typeof window === 'undefined') {
    return 'unknown'
  }

  // Capacitor check
  if (typeof (window as any).Capacitor !== 'undefined') {
    const platform = (window as any).Capacitor.getPlatform()
    if (platform === 'android') return 'capacitor-android'
    if (platform === 'ios') return 'capacitor-ios'
  }

  // Electron check
  if (typeof (window as any).electron !== 'undefined') {
    return 'electron'
  }

  return 'web'
}

export const isCapacitor = () => detectPlatform().startsWith('capacitor')
export const isAndroid = () => detectPlatform() === 'capacitor-android'
export const isIOS = () => detectPlatform() === 'capacitor-ios'
export const isElectron = () => detectPlatform() === 'electron'
export const isWeb = () => detectPlatform() === 'web'
export const isSSR = () => typeof window === 'undefined'
```

### 4.4 Dependency Injection Container

```typescript
// src/platform/container.ts
import { StorageAdapter } from '@/adapters/storage/StorageAdapter'
import { SigningAdapter } from '@/adapters/signing/SigningAdapter'

interface AdapterContainer {
  storage: StorageAdapter
  signer: SigningAdapter | null
  clipboard: ClipboardAdapter
  geolocation: GeolocationAdapter
  network: NetworkAdapter
}

let container: AdapterContainer | null = null

export function initializePlatform(): AdapterContainer {
  const platform = detectPlatform()

  switch (platform) {
    case 'web':
      container = initializeWeb()
      break
    case 'capacitor-android':
    case 'capacitor-ios':
      container = initializeCapacitor()
      break
    case 'electron':
      container = initializeElectron()
      break
    default:
      container = initializeWeb() // fallback
  }

  return container
}

export function getStorage(): StorageAdapter {
  if (!container) throw new Error('Platform not initialized')
  return container.storage
}

export function getSigner(): SigningAdapter | null {
  if (!container) throw new Error('Platform not initialized')
  return container.signer
}

// ... other getters
```

---

## 5. Phase 2: 状態管理の統一

### 5.1 Zustand Store 設計

```typescript
// src/core/store/index.ts
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { immer } from 'zustand/middleware/immer'
import { getStorage } from '@/platform/container'

// Custom storage adapter for Zustand
const createPlatformStorage = () => ({
  getItem: async (name: string) => {
    const storage = getStorage()
    return storage.getItem(name)
  },
  setItem: async (name: string, value: string) => {
    const storage = getStorage()
    await storage.setItem(name, value)
  },
  removeItem: async (name: string) => {
    const storage = getStorage()
    await storage.removeItem(name)
  },
})

// src/core/store/slices/auth.ts
export interface AuthState {
  // State
  pubkey: string | null
  loginMethod: LoginMethod | null
  isLoggedIn: boolean

  // Actions
  login: (pubkey: string, method: LoginMethod) => void
  logout: () => void
}

export type LoginMethod = 'nip07' | 'nosskey' | 'amber' | 'bunker' | 'nsec'

export const createAuthSlice = (set, get) => ({
  pubkey: null,
  loginMethod: null,
  isLoggedIn: false,

  login: (pubkey: string, method: LoginMethod) => {
    set((state) => {
      state.pubkey = pubkey
      state.loginMethod = method
      state.isLoggedIn = true
    })
  },

  logout: () => {
    set((state) => {
      state.pubkey = null
      state.loginMethod = null
      state.isLoggedIn = false
    })
  },
})

// src/core/store/slices/settings.ts
export interface SettingsState {
  // Zap settings
  defaultZapAmount: number
  zapComment: string

  // Display settings
  showImages: boolean
  lowBandwidthMode: boolean

  // Privacy settings
  autoSign: boolean

  // Actions
  setDefaultZapAmount: (amount: number) => void
  setLowBandwidthMode: (enabled: boolean) => void
  // ...
}

// src/core/store/slices/timeline.ts
export interface TimelineState {
  posts: Post[]
  isLoading: boolean
  error: Error | null

  // Pagination
  since: number | null
  hasMore: boolean

  // Actions
  fetchPosts: (filter: NostrFilter) => Promise<void>
  addPost: (post: Post) => void
  clearTimeline: () => void
}

// Combined store
export const useStore = create<
  AuthState & SettingsState & TimelineState
>()(
  persist(
    immer((set, get) => ({
      ...createAuthSlice(set, get),
      ...createSettingsSlice(set, get),
      ...createTimelineSlice(set, get),
    })),
    {
      name: 'nurunuru-store',
      storage: createJSONStorage(createPlatformStorage),
      partialize: (state) => ({
        // 永続化する状態のみ選択
        pubkey: state.pubkey,
        loginMethod: state.loginMethod,
        defaultZapAmount: state.defaultZapAmount,
        lowBandwidthMode: state.lowBandwidthMode,
        // posts は永続化しない（大量データ）
      }),
    }
  )
)
```

### 5.2 状態の移行マップ

| 現状 (localStorage key) | 新状態 (Zustand) | 永続化 |
|------------------------|-----------------|--------|
| `user_pubkey` | `auth.pubkey` | ✅ |
| `nurunuru_login_method` | `auth.loginMethod` | ✅ |
| `defaultZapAmount` | `settings.defaultZapAmount` | ✅ |
| `nurunuru_auto_sign` | `settings.autoSign` | ✅ |
| `user_geohash` | `settings.userGeohash` | ✅ |
| `profile_${pubkey}` | `cache.profiles` | ⚠️ (LRU) |
| `follow_list_${pubkey}` | `cache.followLists` | ⚠️ (LRU) |

---

## 6. Phase 3: コンポーネントの分割

### 6.1 MiniAppTab.js の分割案 (2,533行 → ~10コンポーネント)

```
MiniAppTab.js (2,533行)
  │
  ├── screens/Settings/
  │   ├── index.tsx              # エントリーポイント (~200行)
  │   ├── SettingsHeader.tsx     # ヘッダー
  │   ├── AccountSection.tsx     # アカウント設定
  │   ├── ZapSection.tsx         # Zap設定
  │   ├── RelaySection.tsx       # リレー設定
  │   ├── PrivacySection.tsx     # プライバシー設定
  │   ├── RegionSection.tsx      # 地域設定
  │   └── useSettings.ts         # カスタムフック
  │
  ├── screens/MiniApps/
  │   ├── index.tsx              # ミニアプリランチャー
  │   ├── SchedulerApp/          # スケジューラー (独立)
  │   └── BackupApp/             # バックアップ (独立)
  │
  └── screens/Nosskey/
      ├── index.tsx              # Nosskey 管理
      ├── PasskeyList.tsx        # パスキー一覧
      └── PasskeySetup.tsx       # 新規設定
```

### 6.2 TimelineTab.js の分割案 (2,112行 → ~8コンポーネント)

```
TimelineTab.js (2,112行)
  │
  ├── screens/Timeline/
  │   ├── index.tsx              # エントリーポイント (~150行)
  │   ├── TimelineHeader.tsx     # 検索バー・タブ切替
  │   ├── TimelineList.tsx       # 仮想スクロールリスト
  │   ├── TimelineItem.tsx       # 個別ポストラッパー
  │   ├── TimelineEmpty.tsx      # 空状態
  │   ├── TimelineLoading.tsx    # ローディング
  │   └── useTimeline.ts         # データフェッチフック
  │
  ├── components/post/
  │   ├── PostItem.tsx           # (既存の分割)
  │   ├── PostEditor.tsx         # 投稿フォーム
  │   ├── PostReactions.tsx      # リアクション表示
  │   └── PostActions.tsx        # アクションボタン
  │
  └── components/search/
      ├── SearchModal.tsx        # (既存)
      └── SearchResults.tsx      # 検索結果
```

### 6.3 コンポーネント分割の原則

```typescript
// ✅ Good: 単一責任、200行以下
// screens/Timeline/TimelineHeader.tsx
export function TimelineHeader({
  activeTab,
  onTabChange,
  onSearchOpen
}: TimelineHeaderProps) {
  return (
    <header className="...">
      <TabBar tabs={TABS} active={activeTab} onChange={onTabChange} />
      <SearchButton onClick={onSearchOpen} />
    </header>
  )
}

// ✅ Good: カスタムフックでロジック分離
// screens/Timeline/useTimeline.ts
export function useTimeline(filter: NostrFilter) {
  const [posts, setPosts] = useState<Post[]>([])
  const [isLoading, setIsLoading] = useState(false)

  // fetch logic...

  return { posts, isLoading, fetchMore, refresh }
}

// ✅ Good: 画面コンポーネントは組み合わせのみ
// screens/Timeline/index.tsx
export function TimelineScreen() {
  const { posts, isLoading, fetchMore } = useTimeline(filter)

  return (
    <div>
      <TimelineHeader ... />
      <TimelineList posts={posts} onEndReached={fetchMore} />
      {isLoading && <TimelineLoading />}
    </div>
  )
}
```

---

## 7. Phase 4: プラットフォーム固有実装

### 7.1 Web (PWA)

```typescript
// src/platform/web.ts
import { WebStorage } from '@/adapters/storage/WebStorage'
import { Nip07Signer } from '@/adapters/signing/Nip07Signer'
import { NosskeySignner } from '@/adapters/signing/NosskeySigner'
import { WebClipboard } from '@/adapters/clipboard/WebClipboard'

export function initializeWeb(): AdapterContainer {
  return {
    storage: new WebStorage(),
    signer: detectWebSigner(), // NIP-07 or Nosskey
    clipboard: new WebClipboard(),
    geolocation: new WebGeolocation(),
    network: new WebNetwork(),
  }
}

function detectWebSigner(): SigningAdapter | null {
  if (typeof window === 'undefined') return null

  // Nosskey優先（より安全）
  if (window.nosskeyManager) {
    return new NosskeySigner()
  }

  // NIP-07 フォールバック
  if (window.nostr) {
    return new Nip07Signer()
  }

  return null
}

// Service Worker 登録（Web のみ）
export function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js')
  }
}
```

### 7.2 Capacitor (Android/iOS)

```typescript
// src/platform/capacitor.ts
import { CapacitorStorage } from '@/adapters/storage/CapacitorStorage'
import { AmberSigner } from '@/adapters/signing/AmberSigner'
import { CapacitorClipboard } from '@/adapters/clipboard/CapacitorClipboard'

export function initializeCapacitor(): AdapterContainer {
  const platform = Capacitor.getPlatform()

  return {
    storage: new CapacitorStorage(),
    signer: platform === 'android' ? new AmberSigner() : null,
    clipboard: new CapacitorClipboard(),
    geolocation: new CapacitorGeolocation(),
    network: new CapacitorNetwork(),
  }
}

// src/adapters/signing/AmberSigner.ts
import { App } from '@capacitor/app'

export class AmberSigner implements SigningAdapter {
  readonly type = 'amber' as const

  private pendingRequests = new Map<string, PromiseHandlers>()

  constructor() {
    // Deep link callback 登録
    App.addListener('appUrlOpen', ({ url }) => {
      this.handleCallback(url)
    })
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    const requestId = generateId()

    // Amber Intent 呼び出し
    const intentUrl = this.buildAmberIntent('sign_event', {
      event: JSON.stringify(event),
      callback: `nurunuru://amber-callback/${requestId}`,
    })

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(requestId, { resolve, reject })
      window.location.href = intentUrl
    })
  }

  private handleCallback(url: string) {
    const { requestId, result, error } = parseCallbackUrl(url)
    const handlers = this.pendingRequests.get(requestId)

    if (handlers) {
      if (error) {
        handlers.reject(new Error(error))
      } else {
        handlers.resolve(result)
      }
      this.pendingRequests.delete(requestId)
    }
  }
}
```

### 7.3 Electron (Desktop)

```typescript
// src/platform/electron.ts
import { ElectronStorage } from '@/adapters/storage/ElectronStorage'
import { ElectronSigner } from '@/adapters/signing/ElectronSigner'

export function initializeElectron(): AdapterContainer {
  return {
    storage: new ElectronStorage(),
    signer: new ElectronSigner(),
    clipboard: new ElectronClipboard(),
    geolocation: null, // Desktop では GPS 非対応
    network: new ElectronNetwork(),
  }
}

// src/adapters/storage/ElectronStorage.ts
// Main process の electron-store を IPC 経由で使用
export class ElectronStorage implements StorageAdapter {
  async getItem(key: string): Promise<string | null> {
    return window.electron.invoke('storage:get', key)
  }

  async setItem(key: string, value: string): Promise<void> {
    await window.electron.invoke('storage:set', key, value)
  }

  // ...
}
```

---

## 8. Phase 5: テスト戦略

### 8.1 テストレイヤー

```
┌─────────────────────────────────────────────────┐
│              E2E Tests (Playwright)             │
│    - Web ブラウザでの全フロー確認                  │
│    - モバイルエミュレーター (optional)             │
└─────────────────────────────────────────────────┘
                      ▲
┌─────────────────────────────────────────────────┐
│           Integration Tests (Vitest)            │
│    - コンポーネント + Hook + Store 連携           │
│    - Mock Adapter での動作確認                   │
└─────────────────────────────────────────────────┘
                      ▲
┌─────────────────────────────────────────────────┐
│              Unit Tests (Vitest)                │
│    - Core ロジック (Nostr, Crypto, Validation)   │
│    - Adapter 実装 (Mock 外部 API)                │
│    - Store slices                               │
└─────────────────────────────────────────────────┘
```

### 8.2 Mock Adapter

```typescript
// tests/mocks/MockStorageAdapter.ts
export class MockStorageAdapter implements StorageAdapter {
  private store = new Map<string, string>()

  async getItem(key: string): Promise<string | null> {
    return this.store.get(key) ?? null
  }

  async setItem(key: string, value: string): Promise<void> {
    this.store.set(key, value)
  }

  async removeItem(key: string): Promise<void> {
    this.store.delete(key)
  }

  // テスト用ヘルパー
  clear() {
    this.store.clear()
  }

  getAll() {
    return Object.fromEntries(this.store)
  }
}

// tests/mocks/MockSigningAdapter.ts
export class MockSigningAdapter implements SigningAdapter {
  readonly type = 'memory' as const
  private privateKey: string

  constructor(privateKey?: string) {
    this.privateKey = privateKey ?? generatePrivateKey()
  }

  async getPublicKey(): Promise<string> {
    return getPublicKey(this.privateKey)
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    return finalizeEvent(event, this.privateKey)
  }

  // ...
}
```

### 8.3 テスト例

```typescript
// tests/core/store/auth.test.ts
import { describe, it, expect, beforeEach } from 'vitest'
import { useStore } from '@/core/store'
import { MockStorageAdapter } from '../mocks/MockStorageAdapter'

describe('Auth Store', () => {
  beforeEach(() => {
    // Reset store
    useStore.setState({ pubkey: null, loginMethod: null, isLoggedIn: false })
  })

  it('should login with pubkey and method', () => {
    const { login, pubkey, isLoggedIn } = useStore.getState()

    login('npub1...', 'nip07')

    expect(useStore.getState().pubkey).toBe('npub1...')
    expect(useStore.getState().loginMethod).toBe('nip07')
    expect(useStore.getState().isLoggedIn).toBe(true)
  })

  it('should logout and clear state', () => {
    const { login, logout } = useStore.getState()

    login('npub1...', 'nip07')
    logout()

    expect(useStore.getState().pubkey).toBeNull()
    expect(useStore.getState().isLoggedIn).toBe(false)
  })
})

// tests/adapters/storage/WebStorage.test.ts
import { describe, it, expect, vi } from 'vitest'
import { WebStorage } from '@/adapters/storage/WebStorage'

describe('WebStorage', () => {
  it('should store and retrieve values', async () => {
    const storage = new WebStorage()

    await storage.setItem('test-key', 'test-value')
    const value = await storage.getItem('test-key')

    expect(value).toBe('test-value')
  })
})
```

---

## 9. 実装ロードマップ

### 9.1 タイムライン概要

```
Phase 1: 基盤構築 (2-3週間)
├── Week 1: Adapter インターフェース設計・実装
├── Week 2: Platform detection + DI container
└── Week 3: 既存コードの段階的移行開始

Phase 2: 状態管理 (1-2週間)
├── Week 4: Zustand store 設計・実装
└── Week 5: localStorage → Store 移行

Phase 3: コンポーネント分割 (2-3週間)
├── Week 6: MiniAppTab 分割
├── Week 7: TimelineTab 分割
└── Week 8: 残りの大規模コンポーネント

Phase 4: プラットフォーム実装 (2週間)
├── Week 9: Capacitor adapter 実装
└── Week 10: テスト・調整

Phase 5: テスト・安定化 (1-2週間)
├── Week 11: Unit/Integration tests
└── Week 12: E2E tests + バグ修正
```

### 9.2 優先順位（実装順序）

| 順位 | タスク | 理由 |
|-----|-------|------|
| 1 | Storage Adapter | 最も依存箇所が多い（127箇所） |
| 2 | Platform Detection | 全 Adapter の前提条件 |
| 3 | Zustand Store | 状態管理の統一が他の作業を楽にする |
| 4 | Signing Adapter | 認証フロー全体に影響 |
| 5 | Component 分割 | 上記完了後に着手 |
| 6 | Capacitor 実装 | 基盤完成後 |
| 7 | テスト追加 | 並行して進行可能 |

### 9.3 移行戦略（Breaking Change 最小化）

```typescript
// Step 1: 互換レイヤーを追加（既存コードを壊さない）
// lib/compat/storage.ts
import { getStorage } from '@/platform/container'

// 既存の localStorage 呼び出しをラップ
export const storage = {
  getItem: (key: string) => getStorage().getItem(key),
  setItem: (key: string, value: string) => getStorage().setItem(key, value),
  removeItem: (key: string) => getStorage().removeItem(key),
}

// Step 2: 既存コードを段階的に置換
// Before:
localStorage.getItem('user_pubkey')

// After (Phase 1):
import { storage } from '@/lib/compat/storage'
await storage.getItem('user_pubkey')

// After (Phase 2 - 最終形):
import { useStore } from '@/core/store'
const pubkey = useStore((state) => state.pubkey)
```

---

## 10. リスクと対策

### 10.1 技術的リスク

| リスク | 影響度 | 対策 |
|-------|-------|------|
| 移行中の既存機能破壊 | 高 | 互換レイヤー + 段階的移行 |
| Capacitor プラグイン非互換 | 中 | 事前検証 + フォールバック実装 |
| パフォーマンス低下 | 中 | ベンチマーク比較 + 最適化 |
| 状態同期の複雑化 | 中 | Zustand middleware で制御 |

### 10.2 プロジェクトリスク

| リスク | 影響度 | 対策 |
|-------|-------|------|
| 作業量の見積もり超過 | 高 | 段階的リリース（機能フラグ） |
| 後方互換性の問題 | 中 | 移行ガイド作成 |
| チーム学習コスト | 低 | ドキュメント整備 |

---

## 付録: 参考リソース

### ライブラリ選定理由

| ライブラリ | 選定理由 |
|-----------|---------|
| **Zustand** | 軽量（2KB）、TypeScript 親和性、永続化 middleware |
| **Capacitor** | 既存採用済み、Web 技術活用可能 |
| **Vitest** | Vite 互換、Jest 互換 API、高速 |

### 参照 NIP

- NIP-07: window.nostr 標準
- NIP-44: 暗号化標準
- NIP-46: Remote Signer
- NIP-55: Android Intent (Amber)

---

## 結論

このプランにより：

1. **コード再利用率 80%+** - コアロジックはプラットフォーム間で共有
2. **開発効率向上** - 抽象化により各プラットフォームの実装が簡潔に
3. **テスト容易性** - Mock Adapter によりビジネスロジックの独立テスト可能
4. **将来の拡張性** - React Native 等への移行も Adapter 追加のみで対応可能

段階的な移行により、既存の Web アプリを壊すことなく、マルチプラットフォーム対応の基盤を構築できます。
