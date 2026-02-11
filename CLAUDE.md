# CLAUDE.md - ぬるぬる Nostr クライアント

Nostr プロトコルを使用した日本語向けソーシャルクライアント

**技術スタック**: Next.js 14, React 18, Tailwind CSS, nostr-tools, Zustand

---

## 開発コマンド

```bash
# Web開発
npm run dev
npm run build
npm test

# Android (Capacitor)
npm run android:build

# React Native (移行中)
cd nurunuru-rn
npm run android
npm run ios
```

---

## アーキテクチャ概要

Phase 1-5 完了: プラットフォーム抽象化レイヤー、Zustand 状態管理、コンポーネント分割済み

```
src/
├── core/store/           # Zustand (auth, settings, cache)
├── adapters/             # Storage, Signing, Clipboard, Network
├── platform/             # detect.ts, web.ts, capacitor.ts, electron.ts
├── ui/components/        # 共通コンポーネント
└── lib/                  # Nostr プロトコル実装
```

既存のアダプターインターフェースに従うこと:
- `StorageAdapter`: getItem, setItem, removeItem, clear, keys
- `SigningAdapter`: getPublicKey, signEvent, nip04/44 暗号化

---

## Nostr 署名方法

**Web**:
- `window.nostr` (NIP-07 ブラウザ拡張機能)
- `window.nosskeyManager` (Passkey)

**React Native Android** (ネイティブアプリ):
- **必須**: Android Keystore (TEE/Secure Enclave) - アプリ内署名
- **オプション**: Amber Intent (NIP-55) - 外部アプリ署名

---

## React Native 移行プラン (Phase 6)

**ステータス**: ✅ Phase 1-5 完了 → Phase 6 開始可能

### セットアップ

```bash
npx react-native@latest init NurunuruRN --template react-native-template-typescript
cd NurunuruRN

# 既存コアをコピー
cp -r ../null--nostr/src/core ./src/
cp -r ../null--nostr/src/adapters ./src/  # インターフェースのみ
cp -r ../null--nostr/lib/nostr.js ./src/lib/

# 依存関係
npm install nostr-tools zustand immer @react-native-async-storage/async-storage
npm install @react-navigation/native @react-navigation/bottom-tabs
npm install react-native-reanimated react-native-gesture-handler
npm install react-native-keychain  # Android Keystore 対応
```

### Android Keystore による秘密鍵保護

**最優先**: Nostr 秘密鍵は Android Keystore (TEE/Secure Enclave) に保存

**実装ファイル**: `src/adapters/signing/AndroidKeystoreSigner.ts`

**要件**:
- `react-native-keychain` を使用
- `accessControl: BIOMETRY_CURRENT_SET` (Biometric 必須)
- `securityLevel: SECURE_HARDWARE` (ハードウェア保護)
- `storage: AES` (暗号化)
- 署名時に毎回 Biometric 認証を要求
- AsyncStorage に秘密鍵を保存しない

**AndroidManifest.xml 設定**:
```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<meta-data android:name="android.hardware.biometrics.BiometricPrompt" android:value="true" />
```

### Android ネイティブ UI 対応

**実装ファイル**:
- `src/components/AndroidStatusBar.tsx`: ステータスバー対応
- `src/theme/android.ts`: Material Design テーマ (elevation, ripple, typography)
- `src/components/MaterialButton.tsx`: Material Design Button

**要件**:
- `useSafeAreaInsets()` でステータスバー高さ取得
- `StatusBar` の `translucent` を有効化
- Material Design elevation を適用 (shadow + elevation)
- `android_ripple` で ripple エフェクト

### プラットフォーム初期化

```typescript
// src/platform/react-native.ts
export function initializeReactNative(): AdapterContainer {
  return {
    storage: new RNAsyncStorage(),
    signer: Platform.OS === 'android' ? new AndroidKeystoreSigner() : null,
    clipboard: new RNClipboard(),
    network: new RNNetwork(),
  }
}
```

### テストとビルド

```bash
# 開発ビルド
npm run android

# テスト (Biometric をモック)
npm test -- --testPathPattern=KeystoreSigner

# リリースビルド
cd android && ./gradlew assembleRelease
```

### 実装順序

1. **Week 1**: Keystore Signer 実装 + テスト
2. **Week 2**: Android UI (StatusBar, Theme, Material Button)
3. **Week 3**: Timeline/Profile 画面移植
4. **Week 4**: DM/Zap 機能 + セキュリティ検証

### セキュリティチェックリスト

- [ ] 秘密鍵を平文で保存していないか (AsyncStorage NG)
- [ ] Biometric 認証が必須になっているか
- [ ] Keystore の `SECURE_HARDWARE` レベルを使用しているか
- [ ] ログに秘密鍵を出力していないか
- [ ] コピペで秘密鍵を取得できないか
- [ ] バックグラウンド復帰時の再認証

---

## 参照

- Phase 1-5 実装: `src/adapters/`, `src/core/store/`, `src/platform/`
- React Native: Android Keystore (必須), NIP-55 Amber (オプション)
- 詳細プロンプト: `REACT_NATIVE_MIGRATION_PROMPT.md` (Opus 4.6 用)
