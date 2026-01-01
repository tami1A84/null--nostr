# Nostr Community Notes 提案書

## 概要

本提案書は、X（旧Twitter）の「Community Notes」（旧Birdwatch）の理念を継承し、Nostrの分散型アーキテクチャ上でコミュニティ駆動型のファクトチェック・補足情報システムを実装する方法を提案します。

---

## 1. Community Notes とは

### 1.1 基本概念

Community Notes は、誤情報や文脈が不足している投稿に対して、ユーザーが補足情報（ノート）を追加できるシステムです。

**主な特徴:**
- **クラウドソース型**: 専門家ではなく一般ユーザーがファクトチェックに参加
- **Bridging Algorithm**: 通常意見が異なるユーザー同士が「役に立つ」と評価したノートのみが表示される
- **オープンソース**: アルゴリズムとデータが公開されており透明性が高い
- **非中央集権的判断**: 特定の機関ではなくコミュニティ全体の合意でノートが表示される

### 1.2 Bridging Algorithm の重要性

Bridging Algorithm は Community Notes の核心です：

```
従来の多数決: 51%が支持 → 表示
Bridging: 異なる意見グループ双方が支持 → 表示
```

**仕組み:**
1. ユーザーの過去の評価履歴から「意見傾向」を推定
2. 政治的に対立する傾向のユーザー群を識別
3. 両方のグループから支持されたノートのみを「有用」と判定
4. これにより、党派的なノートは排除され、客観的なノートが浮上

### 1.3 現行 Community Notes の課題

参考: [Community Notes - Wikipedia](https://en.wikipedia.org/wiki/Community_Notes)

| 課題 | 説明 |
|------|------|
| **遅延** | ノート表示まで平均15-75時間、投稿リーチの80-97%に間に合わない |
| **政治的分極化** | 分極化が激しいほど「有用」判定されにくい |
| **中央集権的登録** | 電話番号認証が必要、参加にハードルがある |
| **プラットフォーム依存** | X社のインフラに依存 |

---

## 2. Nostr で Community Notes を実現する利点

### 2.1 分散型アーキテクチャとの親和性

| Nostr の特性 | Community Notes への貢献 |
|-------------|------------------------|
| **公開鍵ID** | 電話番号認証不要、即座に参加可能 |
| **リレー分散** | 単一障害点なし、検閲耐性 |
| **NIP-32 ラベル** | 既存の標準でノート添付が可能 |
| **Web of Trust** | フォローグラフで信頼性を担保 |
| **オープンプロトコル** | 誰でも検証・監査可能 |

### 2.2 既存NIPの活用

- **NIP-32 (Labeling)**: ノートを投稿に添付するための標準
- **NIP-02 (Follow List)**: Web of Trust の基盤
- **NIP-05 (DNS ID)**: 本人確認
- **NIP-58 (Badges)**: 貢献者バッジ

---

## 3. 技術設計

### 3.1 イベント構造

#### 3.1.1 コミュニティノート (NIP-32 Label Event: kind 1985)

```json
{
  "kind": 1985,
  "content": "この投稿で言及されている統計は2019年のデータであり、最新の2024年調査では状況が異なります。詳細: https://example.com/report",
  "tags": [
    ["L", "community-notes"],
    ["l", "context", "community-notes"],
    ["e", "<target_event_id>", "", "labeled"],
    ["p", "<target_author_pubkey>"],
    ["source", "https://example.com/report", "公式統計レポート"],
    ["source", "https://example2.com/article", "解説記事"],
    ["note-type", "misleading-statistics"],
    ["lang", "ja"]
  ],
  "pubkey": "<note_author_pubkey>",
  "created_at": 1735689600
}
```

**タグ説明:**
- `["L", "community-notes"]`: ラベル名前空間
- `["l", "context", "community-notes"]`: ラベルタイプ（context/correction/satire/outdated等）
- `["e", ...]`: 対象イベントへの参照
- `["source", url, description]`: 情報源（複数可）
- `["note-type", type]`: ノートの分類

**ラベルタイプ:**
| タイプ | 説明 |
|--------|------|
| `context` | 追加の文脈情報 |
| `correction` | 事実の訂正 |
| `outdated` | 古い情報の指摘 |
| `satire` | 風刺・ジョークの説明 |
| `misleading-statistics` | 統計の誤用 |
| `missing-source` | 出典不足の指摘 |

#### 3.1.2 ノート評価イベント (kind: 1986 - 新規提案)

```json
{
  "kind": 1986,
  "content": "",
  "tags": [
    ["e", "<community_note_event_id>", "", "rated"],
    ["e", "<original_post_event_id>", "", "context"],
    ["rating", "helpful"],
    ["quality", "0.85"],
    ["reason", "accurate-sources", "clear-explanation"]
  ],
  "pubkey": "<rater_pubkey>",
  "created_at": 1735689700
}
```

**評価タイプ:**
| 評価 | 説明 |
|------|------|
| `helpful` | 有用なノート |
| `not-helpful` | 有用でないノート |
| `somewhat-helpful` | やや有用 |

**理由タグ（複数選択可）:**
- `accurate-sources`: 正確な情報源
- `clear-explanation`: 分かりやすい説明
- `addresses-claim`: 主張に適切に対応
- `biased`: 偏った内容
- `incorrect`: 不正確
- `off-topic`: 的外れ
- `opinion-not-fact`: 事実ではなく意見

### 3.2 Bridging Algorithm の分散型実装

#### 3.2.1 Opinion Vector（意見ベクトル）の計算

中央サーバーなしで Bridging Algorithm を実現するため、**クライアント側で計算**します。

```javascript
// lib/community-notes.js

/**
 * ユーザーの意見ベクトルを計算
 * 過去の評価履歴から意見傾向を推定
 */
export async function calculateOpinionVector(pubkey, recentRatings) {
  // 1. 過去N件のノート評価を取得
  const ratings = await fetchUserRatings(pubkey, { limit: 100 });

  // 2. 評価したノートの内容を分析
  const ratedNotes = await Promise.all(
    ratings.map(r => fetchEvent(r.tags.find(t => t[0] === 'e')[1]))
  );

  // 3. 主成分分析（PCA）で次元削減
  // 評価パターンの類似性から意見空間での位置を推定
  const opinionVector = performPCA(ratings, ratedNotes);

  return opinionVector; // [-1, 1] の範囲のベクトル
}

/**
 * ノートの Bridging スコアを計算
 * 異なる意見を持つユーザー群から支持されているかを評価
 */
export async function calculateBridgingScore(noteEventId) {
  // 1. このノートへの全評価を取得
  const ratings = await fetchNoteRatings(noteEventId);

  // 2. 各評価者の意見ベクトルを取得/計算
  const ratersWithVectors = await Promise.all(
    ratings.map(async (rating) => ({
      rating: rating.tags.find(t => t[0] === 'rating')[1],
      vector: await calculateOpinionVector(rating.pubkey),
      trustScore: await calculateTrustScore(rating.pubkey)
    }))
  );

  // 3. 意見ベクトルでクラスタリング
  const clusters = kMeansClustering(
    ratersWithVectors.map(r => r.vector),
    2 // 2つのグループに分割
  );

  // 4. 各クラスタ内での「helpful」率を計算
  const cluster0HelpfulRate = calculateHelpfulRate(
    ratersWithVectors.filter((_, i) => clusters.labels[i] === 0)
  );
  const cluster1HelpfulRate = calculateHelpfulRate(
    ratersWithVectors.filter((_, i) => clusters.labels[i] === 1)
  );

  // 5. Bridging Score = 両クラスタでの支持率の最小値
  // 両方のグループから支持されている場合のみ高スコア
  const bridgingScore = Math.min(cluster0HelpfulRate, cluster1HelpfulRate);

  return {
    bridgingScore,
    totalRatings: ratings.length,
    cluster0Rate: cluster0HelpfulRate,
    cluster1Rate: cluster1HelpfulRate,
    isShown: bridgingScore >= 0.6 && ratings.length >= 5
  };
}
```

#### 3.2.2 信頼性の担保: Web of Trust 連携

```javascript
/**
 * Web of Trust を活用した評価の重み付け
 */
export async function getWeightedBridgingScore(noteEventId, viewerPubkey) {
  const ratings = await fetchNoteRatings(noteEventId);

  const weightedRatings = await Promise.all(
    ratings.map(async (rating) => {
      // 閲覧者からの信頼スコアで重み付け
      const trustScore = await calculateTrustScore(
        rating.pubkey,
        viewerPubkey
      );

      // NIP-05 検証ボーナス
      const nip05Verified = await verifyNip05(rating.pubkey);
      const nip05Bonus = nip05Verified ? 1.2 : 1.0;

      // 評価履歴の信頼性（過去の評価がどれだけ「正しかった」か）
      const raterAccuracy = await calculateRaterAccuracy(rating.pubkey);

      return {
        ...rating,
        weight: trustScore * nip05Bonus * raterAccuracy
      };
    })
  );

  return calculateBridgingScoreWithWeights(weightedRatings);
}
```

### 3.3 Sybil 耐性

分散型システムでの複数アカウント攻撃への対策：

#### 3.3.1 多層防御

```javascript
/**
 * 評価の有効性を判定
 */
export async function isValidRating(raterPubkey, noteEventId) {
  // 1. Web of Trust スコアチェック
  const trustScore = await calculateGlobalTrustScore(raterPubkey);
  if (trustScore < 10) {
    return { valid: false, reason: 'low-trust-score' };
  }

  // 2. アカウント年齢チェック
  const accountAge = await getAccountAge(raterPubkey);
  if (accountAge < 7 * 24 * 60 * 60) { // 7日未満
    return { valid: false, reason: 'account-too-new' };
  }

  // 3. 評価履歴チェック（新規アカウントの大量評価を排除）
  const recentRatings = await fetchRecentRatings(raterPubkey, { hours: 24 });
  if (recentRatings.length > 50) {
    return { valid: false, reason: 'rate-limit-exceeded' };
  }

  // 4. NIP-05 優遇（オプション）
  const nip05Verified = await verifyNip05(raterPubkey);

  return {
    valid: true,
    weight: nip05Verified ? 1.5 : 1.0
  };
}
```

#### 3.3.2 Proof of Work オプション (NIP-13)

高価値な評価には PoW を要求：

```json
{
  "kind": 1986,
  "id": "000000000e9d97...", // 先頭にゼロが含まれる
  "tags": [
    ["nonce", "12345678", "16"], // 16bit PoW
    ["e", "<note_id>", "", "rated"],
    ["rating", "helpful"]
  ]
}
```

### 3.4 ノート表示ロジック

```javascript
/**
 * コミュニティノートを表示すべきか判定
 */
export async function shouldShowNote(noteEventId, viewerPubkey) {
  // 1. Bridging スコアを計算
  const { bridgingScore, totalRatings } = await calculateBridgingScore(noteEventId);

  // 2. 最低評価数の閾値
  if (totalRatings < 5) {
    return { show: false, reason: 'insufficient-ratings' };
  }

  // 3. Bridging スコアの閾値
  if (bridgingScore < 0.6) {
    return { show: false, reason: 'low-bridging-score' };
  }

  // 4. 閲覧者の WoT で調整（オプション）
  const wotAdjustedScore = await getWeightedBridgingScore(
    noteEventId,
    viewerPubkey
  );

  return {
    show: true,
    score: bridgingScore,
    wotScore: wotAdjustedScore
  };
}
```

---

## 4. ぬるぬる実装案

### 4.1 UI コンポーネント

#### 4.1.1 ノート表示 (PostItem に統合)

```jsx
// components/CommunityNote.js

function CommunityNote({ note, originalPost }) {
  const { bridgingScore, totalRatings } = note.metadata;

  return (
    <div className="community-note">
      <div className="note-header">
        <span className="note-icon">📝</span>
        <span className="note-label">コミュニティノート</span>
        <span className="note-score">
          スコア: {(bridgingScore * 100).toFixed(0)}%
        </span>
      </div>

      <div className="note-content">
        {note.content}
      </div>

      {note.sources && (
        <div className="note-sources">
          <span className="sources-label">情報源:</span>
          {note.sources.map((source, i) => (
            <a key={i} href={source.url} target="_blank">
              {source.description}
            </a>
          ))}
        </div>
      )}

      <div className="note-actions">
        <button onClick={() => rateNote('helpful')}>
          👍 役に立つ ({note.helpfulCount})
        </button>
        <button onClick={() => rateNote('not-helpful')}>
          👎 役に立たない ({note.notHelpfulCount})
        </button>
      </div>

      <div className="note-meta">
        <span>{totalRatings}件の評価</span>
        <span>•</span>
        <UserLink pubkey={note.pubkey} />
      </div>
    </div>
  );
}
```

#### 4.1.2 ノート作成モーダル

```jsx
// components/CommunityNoteModal.js

function CommunityNoteModal({ targetEvent, onClose }) {
  const [content, setContent] = useState('');
  const [noteType, setNoteType] = useState('context');
  const [sources, setSources] = useState([{ url: '', description: '' }]);

  const noteTypes = [
    { value: 'context', label: '追加の文脈' },
    { value: 'correction', label: '事実の訂正' },
    { value: 'outdated', label: '古い情報' },
    { value: 'satire', label: '風刺・ジョーク' },
    { value: 'misleading-statistics', label: '統計の誤用' },
  ];

  const handleSubmit = async () => {
    const event = {
      kind: 1985,
      content,
      tags: [
        ['L', 'community-notes'],
        ['l', noteType, 'community-notes'],
        ['e', targetEvent.id, '', 'labeled'],
        ['p', targetEvent.pubkey],
        ...sources.filter(s => s.url).map(s =>
          ['source', s.url, s.description]
        ),
        ['note-type', noteType],
      ]
    };

    await publishEvent(event);
    onClose();
  };

  return (
    <div className="modal">
      <h3>コミュニティノートを追加</h3>

      <div className="target-preview">
        <PostItem event={targetEvent} compact />
      </div>

      <select value={noteType} onChange={e => setNoteType(e.target.value)}>
        {noteTypes.map(t => (
          <option key={t.value} value={t.value}>{t.label}</option>
        ))}
      </select>

      <textarea
        value={content}
        onChange={e => setContent(e.target.value)}
        placeholder="この投稿に補足情報を追加してください..."
        maxLength={1000}
      />

      <div className="sources-section">
        <label>情報源（任意）</label>
        {sources.map((source, i) => (
          <div key={i} className="source-input">
            <input
              type="url"
              value={source.url}
              onChange={e => updateSource(i, 'url', e.target.value)}
              placeholder="https://..."
            />
            <input
              type="text"
              value={source.description}
              onChange={e => updateSource(i, 'description', e.target.value)}
              placeholder="説明"
            />
          </div>
        ))}
        <button onClick={addSource}>+ 情報源を追加</button>
      </div>

      <div className="modal-actions">
        <button onClick={onClose}>キャンセル</button>
        <button onClick={handleSubmit} disabled={!content}>
          ノートを送信
        </button>
      </div>
    </div>
  );
}
```

### 4.2 ライブラリ実装

#### 4.2.1 フィルター (lib/filters.js)

```javascript
/**
 * コミュニティノートのフィルターを作成
 */
export function createCommunityNoteFilter(eventIds) {
  return {
    kinds: [1985],
    '#e': eventIds,
    '#L': ['community-notes']
  };
}

/**
 * ノート評価のフィルターを作成
 */
export function createNoteRatingFilter(noteEventIds) {
  return {
    kinds: [1986],
    '#e': noteEventIds
  };
}
```

#### 4.2.2 コア関数 (lib/nostr.js に追加)

```javascript
/**
 * コミュニティノートを作成
 */
export async function createCommunityNote({
  targetEventId,
  targetPubkey,
  content,
  noteType,
  sources = []
}) {
  const event = {
    kind: 1985,
    content,
    tags: [
      ['L', 'community-notes'],
      ['l', noteType, 'community-notes'],
      ['e', targetEventId, '', 'labeled'],
      ['p', targetPubkey],
      ...sources.map(s => ['source', s.url, s.description]),
      ['note-type', noteType],
      ['lang', navigator.language.split('-')[0]]
    ]
  };

  return await signAndPublishEvent(event);
}

/**
 * コミュニティノートを評価
 */
export async function rateCommuni tyNote({
  noteEventId,
  originalPostId,
  rating, // 'helpful' | 'not-helpful' | 'somewhat-helpful'
  reasons = []
}) {
  const event = {
    kind: 1986,
    content: '',
    tags: [
      ['e', noteEventId, '', 'rated'],
      ['e', originalPostId, '', 'context'],
      ['rating', rating],
      ...reasons.map(r => ['reason', r])
    ]
  };

  return await signAndPublishEvent(event);
}

/**
 * イベントに対するコミュニティノートを取得
 */
export async function fetchCommunityNotes(eventIds) {
  const filter = createCommunityNoteFilter(eventIds);
  const notes = await fetchEventsWithCache(filter);

  // 各ノートの評価を取得し、Bridging スコアを計算
  const notesWithScores = await Promise.all(
    notes.map(async (note) => {
      const { bridgingScore, totalRatings, isShown } =
        await calculateBridgingScore(note.id);

      return {
        ...note,
        metadata: { bridgingScore, totalRatings, isShown }
      };
    })
  );

  // 表示すべきノートのみフィルタリング
  return notesWithScores.filter(n => n.metadata.isShown);
}
```

---

## 5. 設計上の考慮事項

### 5.1 プライバシー

| 問題 | 対策 |
|------|------|
| 評価履歴が公開される | 意見ベクトルの計算はローカルで実施 |
| 政治的傾向が推定可能 | 生の評価データを表示せず、スコアのみ表示 |

### 5.2 スケーラビリティ

| 問題 | 対策 |
|------|------|
| 全評価の取得は重い | 専用リレーで集計済みスコアをキャッシュ |
| クラスタリング計算コスト | クライアント側で軽量な近似アルゴリズムを使用 |
| リレー負荷 | 評価イベントは専用リレーに集約 |

### 5.3 リレー対応

```javascript
// 推奨リレー構成
const COMMUNITY_NOTES_RELAYS = [
  'wss://notes.nostr.community', // 専用リレー（仮）
  'wss://yabu.me',               // 汎用リレー
  'wss://relay.damus.io'         // フォールバック
];
```

---

## 6. 段階的実装ロードマップ

### Phase 1: 基礎実装（1-2ヶ月）

- [ ] NIP-32 ベースのノートイベント実装
- [ ] ノート作成モーダル
- [ ] ノート表示コンポーネント
- [ ] シンプルな多数決評価（Bridging なし）

### Phase 2: Bridging Algorithm（2-3ヶ月）

- [ ] 意見ベクトル計算
- [ ] K-means クラスタリング実装
- [ ] Bridging スコア計算
- [ ] Web of Trust 連携

### Phase 3: 最適化・拡張（3-4ヶ月）

- [ ] 専用リレー構築
- [ ] スコアキャッシング
- [ ] 貢献者バッジ（NIP-58）
- [ ] 多言語対応

### Phase 4: 高度機能（6ヶ月以降）

- [ ] 機械学習による品質スコアリング
- [ ] 分散型ガバナンス統合
- [ ] クロスプラットフォーム互換性

---

## 7. 他のNostrクライアントとの互換性

### 7.1 NIP-32 準拠

本提案は NIP-32（Labeling）を基盤としているため：

- **読み取り互換性**: NIP-32 対応クライアントでノートが表示可能
- **書き込み互換性**: 他クライアントからのノート作成も受け入れ可能

### 7.2 未対応クライアントでの表示

NIP-32 未対応クライアントでは kind:1985 イベントは無視されるため、
既存のタイムライン体験を損なわない。

---

## 8. Community Notes vs Nostr Community Notes

| 項目 | X Community Notes | Nostr Community Notes |
|------|-------------------|----------------------|
| **登録** | 電話番号認証必要 | 公開鍵のみで即参加 |
| **データ所有** | X社が管理 | ユーザー自身が所有 |
| **検閲耐性** | プラットフォーム次第 | リレー分散で耐性あり |
| **アルゴリズム** | オープンソース | オープンソース（クライアント実装） |
| **Sybil耐性** | 電話番号認証 | Web of Trust + PoW |
| **表示までの遅延** | 15-75時間 | リアルタイム可能（閾値次第） |

---

## 9. まとめ

Nostr上でCommunity Notesを実装することで、Birdwatchの理想をより分散的で検閲耐性のある形で実現できます。

**主なメリット:**
1. **即座の参加**: 電話番号認証なしで誰でも参加可能
2. **データ主権**: ノートと評価はユーザー自身が所有
3. **検閲耐性**: 特定のプラットフォームに依存しない
4. **Web of Trust**: 既存のフォローグラフを活用した信頼性担保
5. **互換性**: NIP-32 準拠で他クライアントとも互換

**課題と対策:**
1. **Sybil攻撃**: Web of Trust + PoW + NIP-05 で多層防御
2. **計算コスト**: 軽量アルゴリズムとキャッシングで対応
3. **リレー対応**: 段階的な普及と専用リレー構築

---

## 10. 参考資料

### Community Notes 関連
- [Community Notes - Wikipedia](https://en.wikipedia.org/wiki/Community_Notes)
- [The Making of Community Notes - Asterisk Magazine](https://asteriskmag.com/issues/08/the-making-of-community-notes)
- [From Birdwatch to Community Notes - arXiv](https://arxiv.org/html/2510.09585v2)

### Nostr 関連
- [NIP-32: Labeling](https://nips.nostr.com/32)
- [NIP-32 GitHub](https://github.com/nostr-protocol/nips/blob/master/32.md)
- [Nostr NIPs Repository](https://github.com/nostr-protocol/nips)

### 技術参考
- [Web of Trust](https://trust.nostr.band/)
- [K-means Clustering](https://en.wikipedia.org/wiki/K-means_clustering)

---

*本提案書は、Birdwatchの理想を継承しつつ、Nostrの分散型アーキテクチャを活かしたコミュニティ駆動型ファクトチェックシステムの実現を目指すものです。コミュニティからのフィードバックを歓迎します。*
