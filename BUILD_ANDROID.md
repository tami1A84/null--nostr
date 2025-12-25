# Android APK ビルド手順

## 必要なもの

- Node.js 18以上
- Java JDK 17以上（推奨: JDK 21）
- Android Studio または Android SDK Command-line Tools
- (オプション) zapstore CLI

## ローカルビルド手順

### 1. 依存関係のインストール

```bash
npm install
```

### 2. Next.js ビルド

```bash
npm run build
```

### 3. Android プロジェクトの同期

```bash
npx cap sync android
```

### 4. APK ビルド

#### Android Studio の場合

1. `android` フォルダを Android Studio で開く
2. Build > Build Bundle(s) / APK(s) > Build APK(s)
3. APK は `android/app/build/outputs/apk/` に生成される

#### コマンドラインの場合

```bash
cd android
./gradlew assembleRelease
```

APK は `android/app/build/outputs/apk/release/app-arm64-v8a-release.apk` に生成されます。

### 5. 署名付きAPKの作成（リリース用）

1. キーストアを生成:

```bash
keytool -genkey -v -keystore nurunuru.keystore -alias nurunuru -keyalg RSA -keysize 2048 -validity 10000
```

2. `android/local.properties` に追加:

```properties
RELEASE_STORE_FILE=/path/to/nurunuru.keystore
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=nurunuru
RELEASE_KEY_PASSWORD=your_key_password
```

3. リリースビルド:

```bash
cd android
./gradlew assembleRelease
```

## ログイン方法

### Android版
- **Amber (NIP-55)**: 推奨。Amberアプリをインストールして署名
- **Nostr Connect**: nsec.appなどのリモート署名サービス

### Web版
- **パスキー**: Face ID / Touch ID / Windows Hello
- **拡張機能**: Alby / nos2x
- **Nostr Connect**: nsec.app

## パスキー設定（Web版）

WebでパスキーをAndroidアプリと連携させるには、Digital Asset Linksの設定が必要です。

1. APKの署名証明書のSHA-256フィンガープリントを取得:

```bash
keytool -list -v -keystore nurunuru.keystore -alias nurunuru | grep SHA256
```

2. `public/.well-known/assetlinks.json` を更新:

```json
[
  {
    "relation": ["delegate_permission/common.get_login_creds"],
    "target": {
      "namespace": "android_app",
      "package_name": "me.yabu.nurunuru",
      "sha256_cert_fingerprints": ["YOUR_SHA256_FINGERPRINT"]
    }
  }
]
```

3. このファイルをWebサーバー（yabu.me）にデプロイ

## GitHub Actions でのビルド

リポジトリにプッシュして `v*` タグを作成すると、自動的にAPKがビルドされてリリースに添付されます。

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Zapstore への公開

### 1. zapstore CLI のインストール

```bash
# macOS
curl -sL https://cdn.zapstore.dev/88c82772eee5c262a0a2553f85a569004ebee54b1636f080ec930f314ac01b1d -o zapstore
chmod +x zapstore

# Linux (amd64)
curl -sL https://cdn.zapstore.dev/6e2c7cf6da53c3f1a78b523a6aacd6316dce3d74ace6f859c2676729ee439990 -o zapstore
chmod +x zapstore
```

### 2. 署名設定

`.env` ファイルを作成:

```
SIGN_WITH=your_nsec_here
```

または NIP-46 bunker URL:

```
SIGN_WITH=bunker://pubkey?relay=wss://relay.example.com&secret=xxx
```

### 3. zapstore.yaml の更新

`zapstore.yaml` の `assets` パスをビルドしたAPKのパスに更新:

```yaml
assets:
  - android/app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

### 4. 公開

```bash
./zapstore publish
```

## トラブルシューティング

### ビルドエラー: SDK not found

Android SDK のパスを設定:

```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
```

### Gradle エラー

Gradle キャッシュをクリア:

```bash
cd android
./gradlew clean
```

### Capacitor の同期エラー

```bash
npx cap sync android --force
```

### Amberが起動しない

- Amberアプリがインストールされているか確認
- Amberの権限設定を確認
- [Amber Releases](https://github.com/greenart7c3/Amber/releases) から最新版をインストール
