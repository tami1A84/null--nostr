# Plurality × Nostr × ぬるぬる 実装提案書

## 概要

本提案書は、オードリー・タン氏とグレン・ワイル氏が提唱する「Plurality（多元性）」の概念を、分散型SNSプロトコルであるNostrおよびそのクライアント「ぬるぬる」に実装する可能性について検討・提案するものです。

---

## 1. Pluralityとは

### 1.1 基本概念

**Plurality（プルラリティ/多元性）**は、「社会的差異を超えたコラボレーションのための技術」として定義されています。これは「Singularity（単一性/特異点）」とは対照的な概念であり、AIやテクノロジーが人間の多様性を尊重しながら協働を促進する未来を提示しています。

#### 主要な原則:
- **多様性の尊重**: 異なる価値観・文化・背景を持つ人々の協働
- **分散型ガバナンス**: 中央集権的な意思決定からの脱却
- **協働テクノロジー**: 対立を創造に変える技術の活用
- **デジタル民主主義**: テクノロジーを活用した市民参加の促進

### 1.2 Pluralityの主要技術

| 技術/概念 | 説明 |
|-----------|------|
| **Quadratic Voting (二次投票)** | 投票コストが票数の二乗に比例する仕組み。少数派の強い意見も反映される |
| **Quadratic Funding** | 多くの小額寄付を受けたプロジェクトにより多くの資金を配分 |
| **Soulbound Tokens (SBT)** | 譲渡不可能なデジタル証明書。学歴、資格、評判などを表現 |
| **Gov4Git** | Git上で動作する分散型ガバナンスプロトコル |
| **Polis** | 意見の類似性に基づいてユーザーをクラスタリングし、合意点を発見 |
| **vTaiwan** | 市民参加型の政策立案プラットフォーム |

### 1.3 台湾での成功事例

台湾では、オードリー・タン氏のリーダーシップの下で：
- **vTaiwan**: 約20万人が参加し、28の政策議論のうち80%が立法に反映
- **Polis**: Uberの規制問題で合意形成に成功
- **COVID-19対応**: 市民と政府の協働により迅速な対策を実現

---

## 2. Nostrの現状分析

### 2.1 Nostrの強み（Plurality親和性）

| 特性 | Pluralityとの親和性 |
|------|---------------------|
| **分散型アーキテクチャ** | 中央集権の排除 ✅ |
| **公開鍵ベースのアイデンティティ** | 自己主権型ID ✅ |
| **NIP-05 ID検証** | 分散型本人確認 ✅ |
| **Zap (Lightning Network)** | 分散型マイクロペイメント ✅ |
| **フォローリスト (NIP-02)** | Web of Trust の基盤 ✅ |
| **カスタムイベントタイプ** | 拡張性 ✅ |

### 2.2 現在のNostr NIPs でPluralityに関連するもの

- **NIP-01**: 基本プロトコル（イベント、署名）
- **NIP-02**: フォローリスト（信頼のグラフ）
- **NIP-05**: DNS-based ID検証
- **NIP-25**: リアクション（いいね/絵文字）
- **NIP-32**: ラベル（タグ付けシステム）
- **NIP-51**: リスト（ミュート、ピン留め等）
- **NIP-57**: Zap（Lightning支払い）
- **NIP-58**: バッジ（実績・認証の表示）

### 2.3 不足している機能

- 投票/ガバナンス用NIP
- Web of Trustの標準化
- Sybil耐性の仕組み
- 意見クラスタリング
- 協調的意思決定ツール

---

## 3. 実装提案

### 3.1 Phase 1: 基盤強化（短期実装可能）

#### 3.1.1 強化されたWeb of Trust

**概要**: フォローグラフを活用した信頼スコアリングシステム

**実装方法**:
```javascript
// lib/web-of-trust.js

/**
 * Web of Trustスコアを計算
 * @param {string} targetPubkey - 評価対象のpubkey
 * @param {string} viewerPubkey - 閲覧者のpubkey
 * @returns {number} 信頼スコア (0-100)
 */
export async function calculateTrustScore(targetPubkey, viewerPubkey) {
  // 1. 直接フォローしているか
  const directFollow = await isFollowing(viewerPubkey, targetPubkey);
  if (directFollow) return 100;

  // 2. 共通フォロワー数
  const mutualFollowers = await getMutualFollowers(targetPubkey, viewerPubkey);

  // 3. 信頼できるフォロワーからの推移的信頼
  const transitiveScore = await calculateTransitiveTrust(
    targetPubkey,
    viewerPubkey,
    maxDepth: 3
  );

  // 4. NIP-05検証ステータス
  const nip05Verified = await verifyNip05(targetPubkey);

  // 5. バッジ保有状況 (NIP-58)
  const badges = await fetchBadges(targetPubkey);

  return computeWeightedScore({
    mutualFollowers,
    transitiveScore,
    nip05Verified,
    badgeCount: badges.length
  });
}
```

**UI実装（ぬるぬる）**:
- プロフィール画面に信頼スコアインジケーター表示
- フォローしていないユーザーの投稿に信頼度を表示
- 低信頼スコアユーザーの投稿に警告表示オプション

---

#### 3.1.2 コミュニティ投票システム（簡易版）

**新規NIP提案: NIP-XX "Community Polls"**

```json
{
  "kind": 1068,
  "content": "どの機能を優先して実装すべきですか？",
  "tags": [
    ["poll_type", "single"],
    ["poll_option", "0", "ダークモード対応"],
    ["poll_option", "1", "翻訳機能"],
    ["poll_option", "2", "グループDM"],
    ["poll_option", "3", "スレッド表示改善"],
    ["poll_end", "1735689600"],
    ["e", "<parent_event_id>"]
  ]
}
```

**投票イベント**:
```json
{
  "kind": 1069,
  "content": "",
  "tags": [
    ["e", "<poll_event_id>"],
    ["vote", "1"],
    ["vote_weight", "1"]
  ]
}
```

**ぬるぬる実装**:
- PostModalに投票作成オプション追加
- TimelineTab/HomeTabに投票表示・参加UI
- 投票結果のリアルタイム集計表示

---

### 3.2 Phase 2: Plurality コア機能（中期実装）

#### 3.2.1 Quadratic Voting (二次投票)

**概念**:
- 各ユーザーに一定の「投票クレジット」を付与
- 1票=1クレジット、2票=4クレジット、3票=9クレジット（n票=n²クレジット）
- 強い意見を持つ少数派も影響力を持てる

**実装アプローチ**:

```javascript
// lib/quadratic-voting.js

/**
 * Quadratic Voting の投票コストを計算
 */
export function calculateVoteCost(voteCount) {
  return voteCount * voteCount;
}

/**
 * クレジット残高で可能な最大投票数を計算
 */
export function maxVotesWithCredits(credits) {
  return Math.floor(Math.sqrt(credits));
}

/**
 * Quadratic Vote イベントを作成
 */
export function createQuadraticVoteEvent({
  pollEventId,
  optionIndex,
  voteCount,
  creditsUsed
}) {
  return {
    kind: 1070, // Quadratic Vote
    content: "",
    tags: [
      ["e", pollEventId],
      ["option", String(optionIndex)],
      ["votes", String(voteCount)],
      ["credits_used", String(creditsUsed)],
      ["qv", "true"]
    ]
  };
}
```

**クレジット配分の選択肢**:
1. **時間ベース**: 週/月ごとに全ユーザーに同数のクレジットを配布
2. **アクティビティベース**: 投稿数・リアクション数に応じて配布
3. **Web of Trust加重**: 信頼スコアに応じてクレジット量を調整

---

#### 3.2.2 Polis風オピニオンクラスタリング

**概要**: 議論のトピックに対する意見を収集し、賛同/反対パターンに基づいてユーザーをグループ化。対立点だけでなく「合意点」を可視化する。

**データフロー**:
```
1. トピックイベント作成 (kind: 1080)
   └─ 議論のテーマを設定

2. ステートメント投稿 (kind: 1081)
   └─ 参加者が意見を投稿

3. 意見表明 (kind: 1082)
   └─ 他の意見に対して賛成/反対/パス

4. クラスタリング & 可視化
   └─ クライアント側で次元圧縮・クラスタリング
```

**イベント定義**:
```json
// トピック作成
{
  "kind": 1080,
  "content": "SNSにおける実名制について",
  "tags": [
    ["polis", "topic"],
    ["description", "SNSでの実名表示義務化についての議論"]
  ]
}

// ステートメント
{
  "kind": 1081,
  "content": "実名制は誹謗中傷を減らすのに効果的だ",
  "tags": [
    ["e", "<topic_event_id>", "", "root"],
    ["polis", "statement"]
  ]
}

// 意見表明
{
  "kind": 1082,
  "content": "",
  "tags": [
    ["e", "<statement_event_id>"],
    ["e", "<topic_event_id>", "", "root"],
    ["opinion", "agree"], // agree | disagree | pass
    ["polis", "opinion"]
  ]
}
```

**ぬるぬる実装案**:

```javascript
// components/PolisView.js

/**
 * Polis風の意見可視化コンポーネント
 */
function PolisView({ topicEventId }) {
  const [statements, setStatements] = useState([]);
  const [opinions, setOpinions] = useState([]);
  const [clusters, setClusters] = useState([]);

  useEffect(() => {
    // PCA/t-SNEによる次元圧縮
    // K-means または階層クラスタリング
    const clusteredData = performClustering(opinions);
    setClusters(clusteredData);
  }, [opinions]);

  return (
    <div className="polis-container">
      {/* 2D散布図で意見グループを可視化 */}
      <OpinionScatterPlot clusters={clusters} />

      {/* 各クラスタの代表的意見 */}
      <ClusterSummary clusters={clusters} />

      {/* 全グループが賛成した「合意点」のハイライト */}
      <ConsensusHighlight opinions={opinions} />

      {/* 新しいステートメントへの投票UI */}
      <StatementVoting statements={statements} />
    </div>
  );
}
```

---

#### 3.2.3 Soulbound Token (SBT) / 譲渡不可能クレデンシャル

**概要**: 学歴、資格、実績、評判など譲渡できない「証明書」をNostr上で表現。

**既存のNIP-58 バッジを拡張**:

```json
{
  "kind": 30009, // Badge Definition (NIP-58)
  "content": "",
  "tags": [
    ["d", "plurality-contributor-2024"],
    ["name", "Plurality Contributor 2024"],
    ["description", "Pluralityプロジェクトへの貢献者"],
    ["image", "https://example.com/badge.png"],
    ["sbt", "true"], // Soulbound Token フラグ
    ["issuer", "<issuer_pubkey>"],
    ["verifiable", "true"],
    ["criteria", "https://example.com/contribution-criteria"]
  ]
}
```

**バッジ授与イベント (kind: 8)**:
```json
{
  "kind": 8,
  "content": "",
  "tags": [
    ["a", "30009:<issuer_pubkey>:plurality-contributor-2024"],
    ["p", "<recipient_pubkey>"],
    ["sbt", "true"],
    ["issued_at", "1735689600"],
    ["evidence", "https://github.com/pluralitybook/plurality/pull/XXX"]
  ]
}
```

**実装ポイント**:
- 信頼できる発行者のホワイトリスト管理
- バッジの検証ロジック（発行者の署名検証）
- プロフィール画面でのSBT専用セクション

---

### 3.3 Phase 3: 高度な協働機能（長期ビジョン）

#### 3.3.1 Quadratic Funding for Nostr

**概要**: クリエイターやプロジェクトへのZap（寄付）を、Quadratic Fundingで配分。

**仕組み**:
1. マッチングプール（共同資金プール）を設立
2. 個人からのZapを記録
3. Quadratic Formula で配分計算:
   ```
   配分額 = (Σ√個人Zap額)²
   ```
4. 多くの少額支援を受けた対象により多くの資金を配分

**課題**:
- マッチングプールの資金管理（マルチシグ？）
- Sybil攻撃への対策（複数アカウントでの水増し防止）
- Nostr上での透明な資金追跡

---

#### 3.3.2 分散型ガバナンス (DAO風)

**概要**: Nostrコミュニティやリレー運営の意思決定を分散化。

**ユースケース**:
- リレーのモデレーションポリシー決定
- クライアント機能の優先順位付け
- コミュニティファンドの使途決定

**Gov4Git のNostr適用**:
```
Gov4Git (Git)  →  Gov4Nostr (Nostr Events)

PR → 提案イベント (kind: 1090)
Issue → 議題イベント (kind: 1091)
Vote → 投票イベント (kind: 1070)
Merge → 承認イベント (kind: 1092)
```

---

#### 3.3.3 プライバシー保護投票

**課題**: 現在の提案では投票内容が公開される

**解決策の選択肢**:
1. **Commit-Reveal スキーム**: 投票をハッシュ化して先にコミット、期限後に公開
2. **NIP-17 暗号化**: 投票を集計者に暗号化して送信
3. **ゼロ知識証明**: 投票の正当性を証明しつつ内容を秘匿（技術的に複雑）

---

## 4. ぬるぬる具体的実装提案

### 4.1 優先度高（すぐに実装可能）

| 機能 | 説明 | 実装難易度 |
|------|------|-----------|
| **信頼スコア表示** | フォローグラフベースの簡易Web of Trust | ★★☆☆☆ |
| **簡易投票機能** | 単一選択の投票作成・参加 | ★★☆☆☆ |
| **バッジ拡張** | SBTフラグ対応、検証済みバッジ表示 | ★★★☆☆ |

### 4.2 優先度中（次期開発）

| 機能 | 説明 | 実装難易度 |
|------|------|-----------|
| **Quadratic Voting** | 二次投票の実装 | ★★★☆☆ |
| **意見クラスタリング** | Polis風の可視化 | ★★★★☆ |
| **投票クレジット管理** | クレジットの配布・消費システム | ★★★☆☆ |

### 4.3 優先度低（将来検討）

| 機能 | 説明 | 実装難易度 |
|------|------|-----------|
| **Quadratic Funding** | Zapの再配分 | ★★★★★ |
| **分散型ガバナンス** | DAO風の意思決定 | ★★★★★ |
| **プライバシー投票** | 秘密投票の実装 | ★★★★★ |

---

## 5. 推奨実装ロードマップ

### Phase 1: 基盤（1-2ヶ月）
1. ✅ コードベース分析完了
2. 🔲 Web of Trustスコアリング実装
3. 🔲 簡易投票NIP草案作成
4. 🔲 投票UI実装（作成・参加・結果表示）

### Phase 2: Plurality Core（2-4ヶ月）
5. 🔲 Quadratic Voting実装
6. 🔲 クレジットシステム実装
7. 🔲 Polis風クラスタリングの基礎実装
8. 🔲 SBT拡張バッジ対応

### Phase 3: 高度機能（6ヶ月以降）
9. 🔲 Quadratic Funding実験
10. 🔲 ガバナンスフレームワーク
11. 🔲 プライバシー保護投票

---

## 6. 技術的考慮事項

### 6.1 Sybil耐性

**問題**: 複数アカウント作成による投票操作

**対策**:
1. **Web of Trust連携**: 信頼スコアが低いアカウントの投票重み付けを下げる
2. **Proof of Work**: 投票イベントにPoW要求（NIP-13）
3. **Stake（預託）**: Zapで一定額を預けないと投票不可
4. **NIP-05 Required**: 検証済みアカウントのみ投票可能

### 6.2 リレー対応

**課題**: 新しいevent kindをサポートするリレーが必要

**対策**:
1. 主要日本語リレー（yabu.me等）への事前連携
2. 専用のPlurality対応リレーの構築検討
3. クライアント側でのフォールバック処理

### 6.3 UX設計

**重要ポイント**:
- 「二次投票」等の概念を直感的に理解できるUI
- クレジット残高の可視化
- 投票のインパクトをわかりやすく表示
- モバイルファーストの設計維持

---

## 7. 参考資料

### Plurality関連
- [Plurality Book (GitHub)](https://github.com/pluralitybook/plurality)
- [Plurality.net (公式サイト)](https://www.plurality.net/)
- [PLURALITY 日本語版 (サイボウズ式ブックス)](https://cybozushiki.cybozu.co.jp/books/2025/04/plurality.html)
- [Gov4Git (Microsoft Research)](https://www.microsoft.com/en-us/research/group/plural-technology-collaboratory/articles/gov4git-a-decentralized-platform-for-community-governance/)
- [RadicalxChange](https://www.radicalxchange.org/)

### Nostr関連
- [Nostr Protocol (GitHub)](https://github.com/nostr-protocol/nostr)
- [NIPs Repository](https://github.com/nostr-protocol/nips)
- [Nostr.Band Trust Rank](https://trust.nostr.band/)

### 技術参考
- [Quadratic Voting (Wikipedia)](https://en.wikipedia.org/wiki/Quadratic_voting)
- [Polis (GitHub)](https://github.com/compdemocracy/polis)
- [vTaiwan](https://vtaiwan.tw/)

---

## 8. 結論

Pluralityの概念は、Nostrの分散型アーキテクチャと非常に相性が良く、以下の点で相互補完的です：

| Plurality | Nostr | 統合効果 |
|-----------|-------|----------|
| 多元的アイデンティティ | 公開鍵ベースID | 自己主権型の多層的ID |
| 協働ガバナンス | リレー分散 | 真の分散型意思決定 |
| 二次投票 | Zap/Lightning | 経済的インセンティブ設計 |
| Web of Trust | フォローグラフ | 有機的な信頼ネットワーク |

**ぬるぬる**は、日本語圏で最も使いやすいNostrクライアントの一つとして、Pluralityの概念を実装する理想的なプラットフォームです。特に日本には台湾と文化的に近い部分もあり、デジタル民主主義の実験場として適しています。

まずは**簡易投票機能**と**Web of Trust表示**から始め、段階的にQuadratic VotingやPolis風機能を追加していくことを推奨します。

---

*本提案書は、Plurality × Nostr × ぬるぬるの可能性を探る第一歩です。コミュニティからのフィードバックを歓迎します。*
