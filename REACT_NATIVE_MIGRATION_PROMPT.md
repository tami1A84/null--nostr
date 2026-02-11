# React Native 移行実装プロンプト (Opus 4.6 用)

> このプロンプトを Claude Opus 4.6 に渡して、React Native 版「ぬるぬる」Nostr クライアントを実装してください。

---

## プロジェクト概要

**目的**: 既存の Next.js ベース Nostr クライアントを React Native に移行し、Android ネイティブアプリを構築

**既存アーキテクチャ**: Phase 1-5 完了
- プラットフォーム抽象化レイヤー (StorageAdapter, SigningAdapter)
- Zustand 状態管理 (auth, settings, cache)
- コアロジック (lib/nostr.js) - プラットフォーム非依存
- 80% のコードが再利用可能

**リポジトリ**: `/home/user/null--nostr` に既存実装あり

---

## タスク 1: プロジェクトセットアップ

### 1.1 React Native プロジェクト作成

```bash
cd /home/user/null--nostr
npx react-native@latest init NurunuruRN --template react-native-template-typescript
cd NurunuruRN
```

### 1.2 依存関係インストール

```bash
# コア依存関係
npm install nostr-tools zustand immer
npm install @react-native-async-storage/async-storage
npm install react-native-keychain  # Android Keystore 対応
npm install @react-native-clipboard/clipboard

# ナビゲーション
npm install @react-navigation/native @react-navigation/bottom-tabs
npm install react-native-screens react-native-safe-area-context

# UI/アニメーション
npm install react-native-reanimated react-native-gesture-handler
npm install react-native-vector-icons

# 開発依存関係
npm install -D @types/react-native
```

### 1.3 既存コードの移植

```bash
# コアロジックをコピー
mkdir -p src/core src/adapters src/lib
cp -r ../src/core/store ./src/core/
cp -r ../src/adapters ./src/  # インターフェースのみ
cp ../lib/nostr.js ./src/lib/
```

---

## タスク 2: Android Keystore による秘密鍵保護 (最優先)

### 2.1 AndroidKeystoreSigner 実装

**ファイル**: `src/adapters/signing/AndroidKeystoreSigner.ts`

**要件**:
- Nostr 秘密鍵 (nsec) を Android Keystore に保存
- Biometric 認証 (指紋・顔認証) 必須
- 署名時に毎回 Biometric 認証を要求
- TEE (Trusted Execution Environment) または Secure Enclave で秘密鍵を保護
- 秘密鍵を平文で保存しない (AsyncStorage 禁止)

**実装例**:

```typescript
import * as Keychain from 'react-native-keychain'
import { finalizeEvent, getPublicKey, nip04, nip44 } from 'nostr-tools'
import type { SigningAdapter, SignerType, SignerFeature } from './SigningAdapter'

export class AndroidKeystoreSigner implements SigningAdapter {
  readonly type: SignerType = 'keystore'
  private static SERVICE_NAME = 'jp.nurunuru.nostr'
  private static NSEC_KEY = 'nostr_nsec_key'

  /**
   * 秘密鍵を Android Keystore に保存
   * @param nsec - Nostr 秘密鍵 (nsec1... 形式)
   */
  async storePrivateKey(nsec: string): Promise<void> {
    const result = await Keychain.setGenericPassword(
      AndroidKeystoreSigner.NSEC_KEY,
      nsec,
      {
        service: AndroidKeystoreSigner.SERVICE_NAME,
        accessControl: Keychain.ACCESS_CONTROL.BIOMETRY_CURRENT_SET,
        securityLevel: Keychain.SECURITY_LEVEL.SECURE_HARDWARE,
        storage: Keychain.STORAGE_TYPE.AES,
        accessible: Keychain.ACCESSIBLE.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
      }
    )
    if (!result) throw new Error('Failed to store private key in Keystore')
  }

  /**
   * 秘密鍵を Keystore から取得 (Biometric 認証必要)
   */
  private async getPrivateKey(): Promise<string> {
    const credentials = await Keychain.getGenericPassword({
      service: AndroidKeystoreSigner.SERVICE_NAME,
      authenticationPrompt: {
        title: 'Nostr 署名',
        subtitle: '生体認証でイベントへの署名を承認してください',
        description: 'この操作には秘密鍵が必要です',
        cancel: 'キャンセル',
      },
    })
    if (!credentials) {
      throw new Error('Private key not found in Keystore')
    }
    return credentials.password
  }

  async getPublicKey(): Promise<string> {
    const sk = await this.getPrivateKey()
    return getPublicKey(sk)
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    const sk = await this.getPrivateKey()
    return finalizeEvent(event, sk)
  }

  async nip04Encrypt(pubkey: string, plaintext: string): Promise<string> {
    const sk = await this.getPrivateKey()
    return nip04.encrypt(sk, pubkey, plaintext)
  }

  async nip04Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    const sk = await this.getPrivateKey()
    return nip04.decrypt(sk, pubkey, ciphertext)
  }

  async nip44Encrypt(pubkey: string, plaintext: string): Promise<string> {
    const sk = await this.getPrivateKey()
    return nip44.encrypt(sk, pubkey, plaintext)
  }

  async nip44Decrypt(pubkey: string, ciphertext: string): Promise<string> {
    const sk = await this.getPrivateKey()
    return nip44.decrypt(sk, pubkey, ciphertext)
  }

  supports(feature: SignerFeature): boolean {
    return ['nip04', 'nip44'].includes(feature)
  }

  /**
   * Keystore から秘密鍵を削除 (ログアウト時)
   */
  async clearPrivateKey(): Promise<void> {
    await Keychain.resetGenericPassword({
      service: AndroidKeystoreSigner.SERVICE_NAME,
    })
  }
}
```

### 2.2 Android Manifest 設定

**ファイル**: `android/app/src/main/AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.USE_BIOMETRIC" />
  <uses-permission android:name="android.permission.USE_FINGERPRINT" />

  <application
    android:allowBackup="false"
    android:usesCleartextTraffic="false">

    <!-- Biometric Prompt サポート -->
    <meta-data
      android:name="android.hardware.biometrics.BiometricPrompt"
      android:value="true" />

    <!-- Keystore 強制 -->
    <meta-data
      android:name="android.security.keychain.enable"
      android:value="true" />

  </application>
</manifest>
```

### 2.3 セキュリティテスト

**テストファイル**: `__tests__/adapters/AndroidKeystoreSigner.test.ts`

```typescript
import { AndroidKeystoreSigner } from '@/adapters/signing/AndroidKeystoreSigner'
import * as Keychain from 'react-native-keychain'

jest.mock('react-native-keychain')

describe('AndroidKeystoreSigner', () => {
  const mockNsec = 'nsec1...'  // テスト用秘密鍵
  let signer: AndroidKeystoreSigner

  beforeEach(() => {
    signer = new AndroidKeystoreSigner()
    jest.clearAllMocks()
  })

  test('秘密鍵を Keystore に保存できる', async () => {
    (Keychain.setGenericPassword as jest.Mock).mockResolvedValue(true)
    await expect(signer.storePrivateKey(mockNsec)).resolves.not.toThrow()
    expect(Keychain.setGenericPassword).toHaveBeenCalledWith(
      'nostr_nsec_key',
      mockNsec,
      expect.objectContaining({
        securityLevel: Keychain.SECURITY_LEVEL.SECURE_HARDWARE,
        accessControl: Keychain.ACCESS_CONTROL.BIOMETRY_CURRENT_SET,
      })
    )
  })

  test('署名時に Biometric 認証が要求される', async () => {
    (Keychain.getGenericPassword as jest.Mock).mockResolvedValue({
      username: 'nostr_nsec_key',
      password: mockNsec,
    })

    const event = { kind: 1, content: 'test', created_at: Math.floor(Date.now() / 1000), tags: [] }
    await signer.signEvent(event)

    expect(Keychain.getGenericPassword).toHaveBeenCalledWith(
      expect.objectContaining({
        authenticationPrompt: expect.objectContaining({
          title: 'Nostr 署名',
        }),
      })
    )
  })

  test('秘密鍵が存在しない場合はエラー', async () => {
    (Keychain.getGenericPassword as jest.Mock).mockResolvedValue(false)
    await expect(signer.getPublicKey()).rejects.toThrow('Private key not found')
  })
})
```

---

## タスク 3: Android ネイティブ UI 対応

### 3.1 ステータスバーコンポーネント

**ファイル**: `src/components/AndroidStatusBar.tsx`

```typescript
import React from 'react'
import { StatusBar, Platform, View, StyleSheet } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'

interface AndroidStatusBarProps {
  backgroundColor?: string
  barStyle?: 'dark-content' | 'light-content'
}

export function AndroidStatusBar({
  backgroundColor = '#ffffff',
  barStyle = 'dark-content'
}: AndroidStatusBarProps) {
  const insets = useSafeAreaInsets()

  return (
    <>
      <StatusBar
        barStyle={barStyle}
        backgroundColor="transparent"
        translucent
      />
      {Platform.OS === 'android' && (
        <View style={[styles.statusBar, { height: insets.top, backgroundColor }]} />
      )}
    </>
  )
}

const styles = StyleSheet.create({
  statusBar: {
    width: '100%',
  },
})
```

### 3.2 Android テーマ定義

**ファイル**: `src/theme/android.ts`

```typescript
import { Platform, StatusBar } from 'react-native'

export const androidTheme = {
  // ステータスバー高さ (Android のみ)
  statusBarHeight: Platform.OS === 'android' ? StatusBar.currentHeight || 24 : 0,

  // ナビゲーションバー高さ
  navigationBarHeight: 48,

  // Material Design Elevation
  elevation: {
    0: {},
    1: {
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 1 },
      shadowOpacity: 0.12,
      shadowRadius: 1,
      elevation: 1,
    },
    2: {
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 1 },
      shadowOpacity: 0.14,
      shadowRadius: 2,
      elevation: 2,
    },
    4: {
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 2 },
      shadowOpacity: 0.16,
      shadowRadius: 4,
      elevation: 4,
    },
    8: {
      shadowColor: '#000',
      shadowOffset: { width: 0, height: 4 },
      shadowOpacity: 0.18,
      shadowRadius: 8,
      elevation: 8,
    },
  },

  // Material Design Ripple Effect
  rippleColor: 'rgba(0, 0, 0, 0.12)',

  // タイポグラフィ (Roboto ベース)
  typography: {
    h1: { fontSize: 24, fontWeight: '700', fontFamily: 'Roboto' },
    h2: { fontSize: 20, fontWeight: '700', fontFamily: 'Roboto' },
    body1: { fontSize: 16, fontWeight: '400', fontFamily: 'Roboto' },
    body2: { fontSize: 14, fontWeight: '400', fontFamily: 'Roboto' },
    caption: { fontSize: 12, fontWeight: '400', fontFamily: 'Roboto' },
  },

  // カラーパレット
  colors: {
    primary: '#6200EE',
    primaryVariant: '#3700B3',
    secondary: '#03DAC6',
    background: '#FFFFFF',
    surface: '#FFFFFF',
    error: '#B00020',
    onPrimary: '#FFFFFF',
    onSecondary: '#000000',
    onBackground: '#000000',
    onSurface: '#000000',
    onError: '#FFFFFF',
  },
}
```

### 3.3 Material Design Button

**ファイル**: `src/components/MaterialButton.tsx`

```typescript
import React from 'react'
import { TouchableOpacity, Text, StyleSheet, ViewStyle, TextStyle } from 'react-native'
import { androidTheme } from '@/theme/android'

interface MaterialButtonProps {
  title: string
  onPress: () => void
  variant?: 'contained' | 'outlined' | 'text'
  disabled?: boolean
  style?: ViewStyle
  textStyle?: TextStyle
}

export function MaterialButton({
  title,
  onPress,
  variant = 'contained',
  disabled = false,
  style,
  textStyle,
}: MaterialButtonProps) {
  const buttonStyle = [
    styles.button,
    variant === 'contained' && styles.contained,
    variant === 'outlined' && styles.outlined,
    variant === 'text' && styles.text,
    disabled && styles.disabled,
    style,
  ]

  const textStyleCombined = [
    styles.buttonText,
    variant === 'contained' && styles.containedText,
    variant === 'outlined' && styles.outlinedText,
    variant === 'text' && styles.textText,
    disabled && styles.disabledText,
    textStyle,
  ]

  return (
    <TouchableOpacity
      style={buttonStyle}
      onPress={onPress}
      disabled={disabled}
      activeOpacity={0.7}
      android_ripple={{ color: androidTheme.rippleColor }}
    >
      <Text style={textStyleCombined}>{title}</Text>
    </TouchableOpacity>
  )
}

const styles = StyleSheet.create({
  button: {
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 4,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 48,
  },
  contained: {
    backgroundColor: androidTheme.colors.primary,
    ...androidTheme.elevation[2],
  },
  outlined: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: androidTheme.colors.primary,
  },
  text: {
    backgroundColor: 'transparent',
  },
  disabled: {
    opacity: 0.38,
  },
  buttonText: {
    fontSize: 14,
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 1.25,
  },
  containedText: {
    color: androidTheme.colors.onPrimary,
  },
  outlinedText: {
    color: androidTheme.colors.primary,
  },
  textText: {
    color: androidTheme.colors.primary,
  },
  disabledText: {
    opacity: 0.38,
  },
})
```

---

## タスク 4: プラットフォーム初期化

**ファイル**: `src/platform/react-native.ts`

```typescript
import { Platform } from 'react-native'
import type { AdapterContainer } from './container'
import { RNAsyncStorage } from '@/adapters/storage/RNAsyncStorage'
import { AndroidKeystoreSigner } from '@/adapters/signing/AndroidKeystoreSigner'
import { RNClipboard } from '@/adapters/clipboard/RNClipboard'
import { RNNetwork } from '@/adapters/network/RNNetwork'

export function initializeReactNative(): AdapterContainer {
  return {
    storage: new RNAsyncStorage(),
    signer: Platform.OS === 'android' ? new AndroidKeystoreSigner() : null,
    clipboard: new RNClipboard(),
    network: new RNNetwork(),
  }
}
```

---

## タスク 5: 画面実装 (Timeline)

**ファイル**: `src/screens/Timeline/TimelineScreen.tsx`

```typescript
import React from 'react'
import { View, FlatList, StyleSheet } from 'react-native'
import { AndroidStatusBar } from '@/components/AndroidStatusBar'
import { useTimeline } from '@/hooks/useTimeline'
import { PostItem } from '@/components/PostItem'
import { androidTheme } from '@/theme/android'

export function TimelineScreen() {
  const { posts, isLoading, fetchMore, refresh } = useTimeline()

  return (
    <View style={styles.container}>
      <AndroidStatusBar />
      <FlatList
        data={posts}
        keyExtractor={(item) => item.id}
        renderItem={({ item }) => <PostItem post={item} />}
        onEndReached={fetchMore}
        onEndReachedThreshold={0.5}
        refreshing={isLoading}
        onRefresh={refresh}
        contentContainerStyle={styles.list}
      />
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: androidTheme.colors.background,
  },
  list: {
    paddingTop: androidTheme.statusBarHeight,
  },
})
```

---

## タスク 6: ビルドとテスト

### 6.1 開発ビルド

```bash
# Android エミュレータ起動
npx react-native run-android

# ログ確認
npx react-native log-android
```

### 6.2 リリースビルド

```bash
cd android
./gradlew assembleRelease

# APK 確認
ls app/build/outputs/apk/release/app-release.apk
```

### 6.3 セキュリティ検証チェックリスト

実装後、以下を確認してください:

- [ ] 秘密鍵が平文で保存されていないか (AsyncStorage を使用していないか)
- [ ] Biometric 認証が毎回要求されるか
- [ ] Keystore の `SECURE_HARDWARE` レベルを使用しているか
- [ ] ログに秘密鍵が出力されていないか
- [ ] コピペで秘密鍵を取得できないか
- [ ] バックグラウンド復帰時に再認証が必要か
- [ ] スクリーンショットで秘密鍵が映らないか

---

## 完了基準

1. ✅ Android Keystore で秘密鍵を保護している
2. ✅ Biometric 認証が動作する
3. ✅ Android ステータスバーが正しく表示される
4. ✅ Material Design に準拠している
5. ✅ Timeline が表示され、投稿できる
6. ✅ すべてのセキュリティチェックを通過している

---

## 参考リソース

- React Native 公式: https://reactnative.dev/
- react-native-keychain: https://github.com/oblador/react-native-keychain
- Android Keystore: https://developer.android.com/training/articles/keystore
- Material Design: https://m3.material.io/

**既存実装参照**: `/home/user/null--nostr/src/adapters/`, `/home/user/null--nostr/src/core/store/`
