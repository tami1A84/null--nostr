# CLAUDE.md - ぬるぬる Nostr クライアント

このファイルは Claude Code がプロジェクトで作業する際のガイドラインと現在の開発計画を提供します。

---

## プロジェクト概要

「ぬるぬる」は Nostr プロトコルを使用した日本語向けソーシャルクライアントです。

- **フレームワーク**: Next.js 14 (App Router) + React 18
- **スタイル**: Tailwind CSS
- **モバイル**: Capacitor (Android)
- **Nostr**: nostr-tools, rx-nostr, nosskey-sdk

---

## ディレクトリ構造（現状）

```
├── app/                    # Next.js App Router
│   ├── api/nip05/         # NIP-05 CORS プロキシ
│   ├── layout.js          # ルートレイアウト
│   ├── page.js            # メインエントリー
│   └── globals.css        # グローバルスタイル
├── components/            # React コンポーネント
│   ├── TimelineTab.js     # タイムライン (2,112行)
│   ├── TalkTab.js         # DM会話 (1,163行)
│   ├── HomeTab.js         # プロフィール (1,850行)
│   ├── MiniAppTab.js      # 設定/ミニアプリ (2,533行)
│   ├── LoginScreen.js     # 認証UI
│   ├── PostItem.js        # ポスト表示
│   └── ...                # その他モーダル等
├── lib/                   # ユーティリティ
│   ├── nostr.js           # Nostr プロトコル (2,750行)
│   ├── connection-manager.js  # WebSocket管理
│   ├── cache.js           # キャッシュ
│   └── ...
├── hooks/                 # カスタムフック
├── public/                # 静的ファイル + PWA
└── docs/                  # ドキュメント
```

---

## 開発コマンド

```bash
# 開発サーバー
npm run dev

# ビルド
npm run build

# Android ビルド
npm run android:build
```

---

## コーディング規約

### 一般原則
- JavaScript (ES6+) を使用（TypeScript への移行予定）
- Tailwind CSS でスタイリング
- コンポーネントは関数コンポーネント + Hooks

### ファイル命名
- コンポーネント: PascalCase (`PostItem.js`)
- ユーティリティ: kebab-case (`connection-manager.js`)
- フック: camelCase with `use` prefix (`useProfile.js`)

### インポート順序
1. React / Next.js
2. 外部ライブラリ
3. 内部モジュール (lib/)
4. コンポーネント
5. スタイル

---

## 重要な注意事項

### SSR 対応
ブラウザ API を使用する前に必ずチェック:
```javascript
if (typeof window !== 'undefined') {
  // ブラウザ専用コード
}
```

### Nostr 署名
複数の署名方法をサポート:
- `window.nostr` (NIP-07 拡張機能)
- `window.nosskeyManager` (Nosskey/Passkey)
- `window.bunkerSigner` (NIP-46)
- Amber (Android Intent)

### localStorage キー
主要なキー:
- `user_pubkey` - ログイン中のユーザー
- `nurunuru_login_method` - ログイン方法
- `defaultZapAmount` - デフォルト Zap 額
- `profile_${pubkey}` - プロフィールキャッシュ

---

# マルチプラットフォームアーキテクチャ再設計プラン

> **重要**: このプロジェクトはマルチプラットフォーム化に向けて再設計中です。
> 以下の計画に沿って開発を進めてください。

---

## 現状の問題点サマリー

### 致命的な問題（マルチプラットフォーム化を阻害）

| 問題 | 影響箇所 | 深刻度 |
|------|---------|--------|
| **localStorage 直接依存** | 127箇所（20+ファイル） | Critical |
| **window オブジェクト依存** | 66箇所（認証・署名全般） | Critical |
| **DOM API 直接操作** | 30+箇所 | High |
| **巨大コンポーネント** | 4ファイル（各2000行超） | High |
| **状態管理の分散** | 384個のuseState | High |

### 現状のコード量

```
コンポーネント層:  ~12,000行 (26コンポーネント)
ロジック層:        ~8,000行 (lib/*.js)
設定・その他:      ~2,000行
────────────────────────────
合計:             ~22,000行
```

### プラットフォーム依存の具体例

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

## ターゲットプラットフォーム

| プラットフォーム | 技術スタック | 優先度 |
|----------------|-------------|--------|
| **Web (PWA)** | Next.js + Service Worker | Primary |
| **Android** | Capacitor (WebView) | Secondary |
| **iOS** | Capacitor (WebView) | Secondary |
| **Desktop** | Electron / Tauri | Future |
| **React Native** | 将来の選択肢 | Planned |

### アーキテクチャ概要

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

## 新ディレクトリ構造（目標）

```
src/
├── core/                          # プラットフォーム非依存コア
│   ├── nostr/                     # Nostr プロトコル
│   │   ├── events.ts              # イベント作成・検証
│   │   ├── relay.ts               # リレー通信抽象化
│   │   ├── crypto.ts              # 暗号化 (NIP-04/44)
│   │   ├── nips/                  # NIP 別実装
│   │   │   ├── nip-05.ts
│   │   │   ├── nip-17.ts
│   │   │   ├── nip-57.ts
│   │   │   └── ...
│   │   └── types.ts
│   │
│   ├── store/                     # 状態管理 (Zustand)
│   │   ├── index.ts
│   │   ├── slices/
│   │   │   ├── auth.ts
│   │   │   ├── timeline.ts
│   │   │   ├── profile.ts
│   │   │   ├── settings.ts
│   │   │   └── dm.ts
│   │   └── persist.ts
│   │
│   └── utils/
│       ├── validation.ts
│       ├── security.ts
│       └── errors.ts
│
├── adapters/                      # プラットフォーム抽象化
│   ├── storage/
│   │   ├── StorageAdapter.ts      # インターフェース
│   │   ├── WebStorage.ts
│   │   ├── CapacitorStorage.ts
│   │   └── ElectronStorage.ts
│   │
│   ├── signing/
│   │   ├── SigningAdapter.ts      # インターフェース
│   │   ├── Nip07Signer.ts
│   │   ├── NosskeySigner.ts
│   │   ├── AmberSigner.ts
│   │   ├── BunkerSigner.ts
│   │   └── MemorySigner.ts
│   │
│   ├── clipboard/
│   ├── geolocation/
│   └── network/
│
├── platform/
│   ├── detect.ts
│   ├── web.ts
│   ├── capacitor.ts
│   └── electron.ts
│
├── ui/
│   ├── components/
│   │   ├── common/
│   │   ├── post/
│   │   ├── profile/
│   │   └── ...
│   │
│   ├── screens/
│   │   ├── Timeline/
│   │   ├── DirectMessage/
│   │   ├── Profile/
│   │   ├── Settings/
│   │   └── Login/
│   │
│   └── hooks/
│
├── app/                           # Next.js App Router
└── index.ts
```

---

## Phase 1: 抽象化レイヤーの構築

### Storage Adapter

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

### Signing Adapter

```typescript
// src/adapters/signing/SigningAdapter.ts
import type { Event, UnsignedEvent } from 'nostr-tools'

export interface SigningAdapter {
  getPublicKey(): Promise<string>
  signEvent(event: UnsignedEvent): Promise<Event>
  nip04Encrypt(pubkey: string, plaintext: string): Promise<string>
  nip04Decrypt(pubkey: string, ciphertext: string): Promise<string>
  nip44Encrypt(pubkey: string, plaintext: string): Promise<string>
  nip44Decrypt(pubkey: string, ciphertext: string): Promise<string>
  readonly type: SignerType
  supports(feature: SignerFeature): boolean
}

export type SignerType =
  | 'nip07'
  | 'nosskey'
  | 'amber'
  | 'bunker'
  | 'memory'
  | 'nsec-app'

export type SignerFeature =
  | 'nip04'
  | 'nip44'
  | 'delegation'
  | 'getRelays'
```

### Platform Detection

```typescript
// src/platform/detect.ts
export type Platform = 'web' | 'capacitor-android' | 'capacitor-ios' | 'electron' | 'unknown'

export function detectPlatform(): Platform {
  if (typeof window === 'undefined') return 'unknown'

  if (typeof (window as any).Capacitor !== 'undefined') {
    const platform = (window as any).Capacitor.getPlatform()
    if (platform === 'android') return 'capacitor-android'
    if (platform === 'ios') return 'capacitor-ios'
  }

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

### DI Container

```typescript
// src/platform/container.ts
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
      container = initializeWeb()
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
```

---

## Phase 2: 状態管理の統一

### Zustand Store 設計

```typescript
// src/core/store/index.ts
import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { immer } from 'zustand/middleware/immer'

const createPlatformStorage = () => ({
  getItem: async (name: string) => getStorage().getItem(name),
  setItem: async (name: string, value: string) => getStorage().setItem(name, value),
  removeItem: async (name: string) => getStorage().removeItem(name),
})

// Auth Slice
export interface AuthState {
  pubkey: string | null
  loginMethod: LoginMethod | null
  isLoggedIn: boolean
  login: (pubkey: string, method: LoginMethod) => void
  logout: () => void
}

// Settings Slice
export interface SettingsState {
  defaultZapAmount: number
  lowBandwidthMode: boolean
  autoSign: boolean
  setDefaultZapAmount: (amount: number) => void
  setLowBandwidthMode: (enabled: boolean) => void
}

// Combined Store
export const useStore = create<AuthState & SettingsState>()(
  persist(
    immer((set) => ({
      // Auth
      pubkey: null,
      loginMethod: null,
      isLoggedIn: false,
      login: (pubkey, method) => set((state) => {
        state.pubkey = pubkey
        state.loginMethod = method
        state.isLoggedIn = true
      }),
      logout: () => set((state) => {
        state.pubkey = null
        state.loginMethod = null
        state.isLoggedIn = false
      }),

      // Settings
      defaultZapAmount: 1000,
      lowBandwidthMode: false,
      autoSign: false,
      setDefaultZapAmount: (amount) => set((state) => { state.defaultZapAmount = amount }),
      setLowBandwidthMode: (enabled) => set((state) => { state.lowBandwidthMode = enabled }),
    })),
    {
      name: 'nurunuru-store',
      storage: createJSONStorage(createPlatformStorage),
      partialize: (state) => ({
        pubkey: state.pubkey,
        loginMethod: state.loginMethod,
        defaultZapAmount: state.defaultZapAmount,
        lowBandwidthMode: state.lowBandwidthMode,
      }),
    }
  )
)
```

### 状態の移行マップ

| 現状 (localStorage key) | 新状態 (Zustand) | 永続化 |
|------------------------|-----------------|--------|
| `user_pubkey` | `auth.pubkey` | Yes |
| `nurunuru_login_method` | `auth.loginMethod` | Yes |
| `defaultZapAmount` | `settings.defaultZapAmount` | Yes |
| `nurunuru_auto_sign` | `settings.autoSign` | Yes |
| `user_geohash` | `settings.userGeohash` | Yes |
| `profile_${pubkey}` | `cache.profiles` | LRU |
| `follow_list_${pubkey}` | `cache.followLists` | LRU |

---

## Phase 3: コンポーネントの分割

### MiniAppTab.js の分割案 (2,533行 → ~10コンポーネント)

```
MiniAppTab.js (2,533行)
  │
  ├── screens/Settings/
  │   ├── index.tsx              (~200行)
  │   ├── SettingsHeader.tsx
  │   ├── AccountSection.tsx
  │   ├── ZapSection.tsx
  │   ├── RelaySection.tsx
  │   ├── PrivacySection.tsx
  │   ├── RegionSection.tsx
  │   └── useSettings.ts
  │
  ├── screens/MiniApps/
  │   ├── index.tsx
  │   ├── SchedulerApp/
  │   └── BackupApp/
  │
  └── screens/Nosskey/
      ├── index.tsx
      ├── PasskeyList.tsx
      └── PasskeySetup.tsx
```

### TimelineTab.js の分割案 (2,112行 → ~8コンポーネント)

```
TimelineTab.js (2,112行)
  │
  ├── screens/Timeline/
  │   ├── index.tsx              (~150行)
  │   ├── TimelineHeader.tsx
  │   ├── TimelineList.tsx
  │   ├── TimelineItem.tsx
  │   ├── TimelineEmpty.tsx
  │   ├── TimelineLoading.tsx
  │   └── useTimeline.ts
  │
  ├── components/post/
  │   ├── PostItem.tsx
  │   ├── PostEditor.tsx
  │   ├── PostReactions.tsx
  │   └── PostActions.tsx
  │
  └── components/search/
      ├── SearchModal.tsx
      └── SearchResults.tsx
```

### コンポーネント分割の原則

```typescript
// Good: 単一責任、200行以下
export function TimelineHeader({ activeTab, onTabChange, onSearchOpen }) {
  return (
    <header>
      <TabBar tabs={TABS} active={activeTab} onChange={onTabChange} />
      <SearchButton onClick={onSearchOpen} />
    </header>
  )
}

// Good: カスタムフックでロジック分離
export function useTimeline(filter: NostrFilter) {
  const [posts, setPosts] = useState([])
  const [isLoading, setIsLoading] = useState(false)
  // fetch logic...
  return { posts, isLoading, fetchMore, refresh }
}

// Good: 画面コンポーネントは組み合わせのみ
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

## Phase 4: プラットフォーム固有実装

### Web (PWA)

```typescript
// src/platform/web.ts
export function initializeWeb(): AdapterContainer {
  return {
    storage: new WebStorage(),
    signer: detectWebSigner(),
    clipboard: new WebClipboard(),
    geolocation: new WebGeolocation(),
    network: new WebNetwork(),
  }
}

function detectWebSigner(): SigningAdapter | null {
  if (typeof window === 'undefined') return null
  if (window.nosskeyManager) return new NosskeySigner()
  if (window.nostr) return new Nip07Signer()
  return null
}
```

### Capacitor (Android/iOS)

```typescript
// src/platform/capacitor.ts
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
```

### Electron (Desktop)

```typescript
// src/platform/electron.ts
export function initializeElectron(): AdapterContainer {
  return {
    storage: new ElectronStorage(),
    signer: new ElectronSigner(),
    clipboard: new ElectronClipboard(),
    geolocation: null,
    network: new ElectronNetwork(),
  }
}
```

---

## Phase 5: テスト戦略

### テストレイヤー

```
┌─────────────────────────────────────────────────┐
│              E2E Tests (Playwright)             │
└─────────────────────────────────────────────────┘
                      ▲
┌─────────────────────────────────────────────────┐
│           Integration Tests (Vitest)            │
└─────────────────────────────────────────────────┘
                      ▲
┌─────────────────────────────────────────────────┐
│              Unit Tests (Vitest)                │
└─────────────────────────────────────────────────┘
```

### Mock Adapter

```typescript
export class MockStorageAdapter implements StorageAdapter {
  private store = new Map<string, string>()

  async getItem(key: string) { return this.store.get(key) ?? null }
  async setItem(key: string, value: string) { this.store.set(key, value) }
  async removeItem(key: string) { this.store.delete(key) }
  clear() { this.store.clear() }
}

export class MockSigningAdapter implements SigningAdapter {
  readonly type = 'memory' as const
  private privateKey: string

  constructor(privateKey?: string) {
    this.privateKey = privateKey ?? generatePrivateKey()
  }

  async getPublicKey() { return getPublicKey(this.privateKey) }
  async signEvent(event) { return finalizeEvent(event, this.privateKey) }
}
```

---

## 実装ロードマップ

### タイムライン

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

### 優先順位（実装順序）

| 順位 | タスク | 理由 |
|-----|-------|------|
| 1 | Storage Adapter | 最も依存箇所が多い（127箇所） |
| 2 | Platform Detection | 全 Adapter の前提条件 |
| 3 | Zustand Store | 状態管理の統一が他の作業を楽にする |
| 4 | Signing Adapter | 認証フロー全体に影響 |
| 5 | Component 分割 | 上記完了後に着手 |
| 6 | Capacitor 実装 | 基盤完成後 |
| 7 | テスト追加 | 並行して進行可能 |

### 移行戦略（Breaking Change 最小化）

```typescript
// Step 1: 互換レイヤーを追加（既存コードを壊さない）
// lib/compat/storage.ts
import { getStorage } from '@/platform/container'

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

## リスクと対策

### 技術的リスク

| リスク | 影響度 | 対策 |
|-------|-------|------|
| 移行中の既存機能破壊 | 高 | 互換レイヤー + 段階的移行 |
| Capacitor プラグイン非互換 | 中 | 事前検証 + フォールバック実装 |
| パフォーマンス低下 | 中 | ベンチマーク比較 + 最適化 |
| 状態同期の複雑化 | 中 | Zustand middleware で制御 |

### プロジェクトリスク

| リスク | 影響度 | 対策 |
|-------|-------|------|
| 作業量の見積もり超過 | 高 | 段階的リリース（機能フラグ） |
| 後方互換性の問題 | 中 | 移行ガイド作成 |

---

## 参照 NIP

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
