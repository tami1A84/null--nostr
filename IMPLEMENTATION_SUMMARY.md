# React Native 移行プラン実装サマリー

## 完了した作業

### 1. CLAUDE.md の最適化 (1223行 → 159行)

**適用したベストプラクティス**:
- ✅ Claude が推測できる情報を削除 (コーディング規約、基本的なアーキテクチャ説明)
- ✅ 検証可能なコマンドに焦点 (ビルド、テスト、リリース)
- ✅ プラットフォーム固有の要件を明確化 (Android Keystore、Biometric認証)
- ✅ 実装順序を具体化 (Week 1-4)
- ✅ セキュリティチェックリストを追加

**主な内容**:
- 開発コマンド
- アーキテクチャ概要 (Phase 1-5 完了済み)
- React Native 移行プラン (Phase 6) - 100行程度

### 2. Opus 4.6 用プロンプト作成 (605行)

**ファイル**: `REACT_NATIVE_MIGRATION_PROMPT.md`

**構成**:
1. **タスク 1: プロジェクトセットアップ**
   - React Native プロジェクト作成
   - 依存関係インストール
   - 既存コードの移植

2. **タスク 2: Android Keystore による秘密鍵保護 (最優先)**
   - AndroidKeystoreSigner 実装 (完全なコード例)
   - Biometric 認証の実装
   - Android Manifest 設定
   - セキュリティテスト

3. **タスク 3: Android ネイティブ UI 対応**
   - AndroidStatusBar コンポーネント
   - Material Design テーマ定義
   - Material Design Button 実装

4. **タスク 4: プラットフォーム初期化**
   - react-native.ts の実装

5. **タスク 5: 画面実装 (Timeline)**
   - TimelineScreen の実装例

6. **タスク 6: ビルドとテスト**
   - 開発ビルドコマンド
   - リリースビルドコマンド
   - セキュリティ検証チェックリスト

### 3. セキュリティ重視の設計

**Android Keystore の実装**:
- `react-native-keychain` を使用
- `SECURE_HARDWARE` レベルでハードウェア保護
- `BIOMETRY_CURRENT_SET` で Biometric 認証必須
- TEE (Trusted Execution Environment) で秘密鍵を保護
- AsyncStorage への平文保存を禁止

**検証項目**:
- [ ] 秘密鍵を平文で保存していないか
- [ ] Biometric 認証が必須になっているか
- [ ] Keystore の `SECURE_HARDWARE` レベルを使用しているか
- [ ] ログに秘密鍵を出力していないか
- [ ] コピペで秘密鍵を取得できないか
- [ ] バックグラウンド復帰時の再認証

### 4. Android ネイティブ UI 対応

**実装内容**:
- ステータスバーの透過処理 (`translucent`)
- `useSafeAreaInsets()` でステータスバー高さを取得
- Material Design elevation (shadow + elevation)
- Material Design ripple エフェクト (`android_ripple`)
- Roboto フォントの使用

---

## Opus 4.6 への入力方法

### ステップ 1: プロンプトファイルを開く

```bash
cat REACT_NATIVE_MIGRATION_PROMPT.md
```

### ステップ 2: Claude Opus 4.6 に以下を入力

```
以下のプロンプトに従って、React Native 版「ぬるぬる」Nostr クライアントを実装してください。

既存のコードベースは /home/user/null--nostr にあり、Phase 1-5 (プラットフォーム抽象化、Zustand 状態管理) が完了しています。

特に重要:
1. Android Keystore による秘密鍵保護を最優先で実装
2. Android ステータスバーやネイティブ UI に対応
3. セキュリティチェックリストをすべて検証

---

[REACT_NATIVE_MIGRATION_PROMPT.md の内容をここに貼り付け]
```

### ステップ 3: 実装後の検証

```bash
cd NurunuruRN

# テスト実行
npm test -- --testPathPattern=KeystoreSigner

# 開発ビルド
npm run android

# セキュリティ検証
# - Biometric 認証が動作するか
# - Keystore からの秘密鍵読み取りが安全か
# - ログに秘密鍵が出力されていないか
```

---

## 既存アーキテクチャの活用

**80% のコードが再利用可能**:

```
✅ 再利用可能 (src/core/)
├── store/                  # Zustand Store - 100% 再利用
├── adapters/interfaces/    # Interface 定義 - 100% 再利用
└── lib/nostr.js           # Nostr プロトコル - 95% 再利用

⚠️ 要修正 (src/ui/)
└── components/            # React Native 用に書き換え
    ├── Web: <div>, <button>
    └── RN:  <View>, <TouchableOpacity>

❌ 新規実装
├── adapters/
│   ├── RNAsyncStorage.ts
│   └── AndroidKeystoreSigner.ts
└── platform/react-native.ts
```

---

## 実装タイムライン

- **Week 1**: Keystore Signer 実装 + テスト
- **Week 2**: Android UI 対応 (StatusBar, Theme, Material Button)
- **Week 3**: Timeline/Profile 画面移植
- **Week 4**: DM/Zap 機能 + セキュリティ検証

---

## 参考リソース

- **Claude Code ベストプラクティス**: https://code.claude.com/docs/en/best-practices
- **React Native 公式**: https://reactnative.dev/
- **react-native-keychain**: https://github.com/oblador/react-native-keychain
- **Android Keystore**: https://developer.android.com/training/articles/keystore
- **Material Design 3**: https://m3.material.io/

---

## まとめ

1. ✅ **CLAUDE.md を 159行に簡潔化** (ベストプラクティス準拠)
2. ✅ **Opus 4.6 用の詳細プロンプトを作成** (605行)
3. ✅ **Android Keystore による秘密鍵保護を最優先**
4. ✅ **Android ネイティブ UI 対応 (StatusBar, Material Design)**
5. ✅ **既存アーキテクチャ (Phase 1-5) を最大限活用**

次のステップ: `REACT_NATIVE_MIGRATION_PROMPT.md` を Opus 4.6 に入力し、実装を開始してください。
