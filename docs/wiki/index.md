# null--nostr LLM Wiki Index

このディレクトリは **null--nostr の LLM Wiki** です。LLM がソースコード・設計文書・明示された意思決定を読み、プロジェクト知識を Markdown として継続的に整理します。

> **重要:** 真実の源泉はソースコード、design tokens、ビルド設定、公式設計文書です。Wiki はそれらから派生したナビゲーション層です。

## Core

| Page | Summary |
|---|---|
| [[overview]] | プロジェクトの目的、対象プラットフォーム、主要な設計原則。 |
| [[architecture]] | Web / Android / iOS / Rust Engine の横断アーキテクチャ。 |
| [[log]] | LLM Wiki の更新履歴。 |
| [[glossary]] | Nostr / Marmot / Blossom / platform parity 用語集。 |
| [[lint-report]] | Wiki の最新ヘルスチェック、修正済み stale claim、残ギャップ。 |

## Culture (Charter v0.1)

| Page | Summary |
|---|---|
| [[culture/principles]] | 北極星 + 五箇条。ぬるぬるの文化的憲章 v0.1。 |
| [[culture/not-doing]] | ぬるぬるが意図的にやらないこと。 |
| [[culture/design-crit]] | Weekly Nuru Design Crit の運用と Crit ルール。 |
| [[culture/crit-logs/2026-W23]] | 2026-W23 Design Crit log。マーケティング成長戦略 v2 の 5決議 (Week-1 復帰率、三声モデル、ICP固定、safe starter graph、ぬるる bunker) を記録。 |
| [[culture/release-quality]] | 月曜リリース列車と Nuru Production System (TPS-inspired quality/stability)。 |
| [[culture/copy-style]] | 日本語コピー規約 (直訳禁止、既存採用語の保護)。 |
| [[culture/llm-onboarding]] | LLM コントリビュータのオンボーディングと出力規約。 |
| [[culture/four-freedoms]] | 4軸自由ドクトリン (言論 / プライバシー / 経済 / 配布)。 |

## Copy / Store Listings

| Page | Summary |
|---|---|
| [[copy/store-listing]] | README / zapstore / Google Play / App Store の表層コピーと用語マッピング。 |

## Strategy / Operations

| Page | Summary |
|---|---|
| [[strategy/themaday-2026-05-25]] | Block の “From Hierarchy to Intelligence” とストアデータを踏まえた ThemaDAY 経営会議・リーダー方向性すり合わせ。 |
| [[strategy/themaday-2026-05-28-partnerships]] | Nostr Compass #24 掲載・公開前レビューを起点に、外部提携 / パートナーシップ / 開発者向け施策の方針を整理。 |
| [[strategy/themaday-2026-05-31-week-review]] | 2026-05-25〜31 の週次振り返りと 6月 Learning Velocity 方針。5/31 Outcome で 6月ロードマップの前提を整理。 |
| [[strategy/themaday-2026-06-01-management]] | ThemaDAY マネジメント経営会議。onboarding 主文、6/8 1.5.5 スコープ、リーダー hat、ろくなな code-only retention、QA テンプレを確定。 |
| [[strategy/themaday-2026-06-02-product-eng-design]] | 製品開発・エンジニアリング・デザインの ThemaDAY すり合わせ。リレーフィード削除/iOS Rust FFI 完了を前提化し、Home を header account icon + アクティビティ/コンテンツ2層構造へ更新。 |
| [[strategy/themaday-2026-06-03-marketing-growth]] | ThemaDAY マーケティング・成長戦略・コミュニケーション統合版。Week-1 復帰率、AARRT v2、三声モデル、safe starter graph、危機対応を整理。 |
| [[strategy/themaday-2026-06-04-partnerships-developers]] | 2人目コントリビュータ受領を契機とした ThemaDAY 外部提携 & 開発者施策。Contributor Entrance MVP、Contribution Ladder L0–L5、private vulnerability intake、dependency-update verification、bounty/CLA/Hacktoberfest 不採用を整理。 |
| [[strategy/themaday-2026-06-06-company-culture]] | ThemaDAY 企業文化 / カルチャー構築。言論の自由を「発言権と表示 / 到達権の分離」として定義し、open protocol + scoped reach + native posting parity を整理。 |
| [[strategy/june-2026-roadmap]] | 2026年6月の主要タブ再設計ロードマップ。onboarding 主文、リレーフィード廃止、ホーム刷新、ニュースタブ化、NIP-5Aミニアプリ、2-hop信頼グラフ制限、ろくななroot tab廃止+code-only retention を整理。 |
| [[strategy/nuruh-ip-2026-05-28]] | ぬるる IP マーケティング & 成長戦略 v1.0。四箇条 / 3層接点 / 5ループ / ライセンス階段 / KPI+ガードレール / 90日と24ヶ月ロードマップ。 |
| [[operations/feedback-loop]] | NIP-50 search relay と nurunuru-mcp による Nostr feedback 収集、Issue draft、autofix 運用。 |
| [[operations/crisis-response]] | 外部露出・ストア審査・Nostr 上の誤解に対応する Trust Surface FAQ と crisis escalation matrix。 |


## Decisions (recent)

| Page | Summary |
|---|---|
| [[decisions/adr-0020-safe-starter-graph]] | 初回 Home の空白離脱を避ける safe starter graph。任意リレー生フィードを復活させず、公式/ぬるる/日本語 starter accounts を安全候補として扱う Proposed ADR。 |
| [[decisions/adr-0021-open-speech-scoped-reach]] | 言論の自由を発言 / 公開 / 退出として守り、主要 UI の到達範囲は関係性で絞る。iOS / Android / Web の投稿 parity を維持する Accepted ADR。 |
| [[decisions/adr-0022-ios-four-tab-navigation]] | iOS root navigation を ホーム / トーク / タイムライン / ミニアプリ に固定する Accepted ADR。 |
| [[decisions/adr-0023-ios-remove-nip46-signer]] | iOS NIP-46 signer を廃止し、internal nsec/Keychain と Passkey/Nosskey に整理する Accepted ADR。 |
| [[decisions/adr-0024-ios-startup-relay-connection-dedupe]] | iOS startup relay connection の in-flight dedupe を導入する Proposed ADR。 |

## Quality / Release

| Page | Summary |
|---|---|
| [[quality/qa-template]] | ADR-0014 に基づく manual real-device QA 記録テンプレート。platform slot / findings / 5 Whys / follow-up を残す。 |
| [[quality/manual-cohort-observation]] | Week-1 復帰率を製品計測コードなしで観察する manual cohort テンプレート。5人/週、初投稿、反応、7日以内復帰を記録。 |

## Platforms

| Page | Summary |
|---|---|
| [[platforms/web]] | Next.js PWA、Web レイヤー、セキュリティ、接続管理。 |
| [[platforms/android]] | Kotlin / Jetpack Compose / Rust FFI を使う Android 実装。 |
| [[platforms/ios]] | SwiftUI / Observation / Keychain / pixel-perfect sync 方針。 |
| [[platforms/ios-phase0-audit]] | iOS Phase 0 audit: 4tab, NIP-46 signer removal, startup relay dedupe. |
| [[platforms/rust-engine]] | `nurunuru-core`、UniFFI、Android/iOS/Desktop 連携。 |
| [[platforms/parity-matrix]] | Web / Android / iOS / Rust の機能・protocol parity matrix。 |

## Features

| Page | Summary |
|---|---|
| [[features/timeline]] | フォロー / おすすめタイムライン、投稿表示、リアクション状態。 |
| [[features/news]] | ニュースタブ。NIP-23 kind 30023 長文記事、null.news.category カテゴリ、ニュースソース設定。ランキングなし。 |
| [[features/onboarding]] | パスキー専用の新規登録 5 ステップ + `#nostrはじめました` チュートリアル投稿。 |
| [[features/post-composer]] | 投稿作成、140文字制限、画像アップロード、リレー指定、NIP-70。 |
| [[features/notifications]] | 通知一覧、Kind 6 / Kind 1 #p、ポーリング、表示仕様。 |
| [[features/search]] | Android の高度検索、NIP-50、検索演算子、クライアント側フィルタ。 |
| [[features/image-upload]] | 画像アップロード、NIP-98、Blossom/BUD-03、NIP-92 media handling。 |
| [[features/talk]] | Talk / messaging、Marmot MLS、legacy NIP-17 境界。 |
| [[features/talk-marmot-mls]] | Native Talk の Marmot MLS protocol details。 |
| [[features/talk-relays]] | Talk 用 KeyPackage / Welcome / message relay strategy。 |
| [[features/talk-debugging]] | Talk/Marmot debugging guidance and raw-log handling。 |
| [[features/talk-ios-android-parity]] | Android/iOS native Talk protocol parity checklist。 |
| [[features/mls-db-encryption]] | MLS storage SQLite encryption (SQLCipher) — issue #181, threat model, migration. |
| [[features/mls-peer-epoch-catch-up]] | Peer-epoch catch-up + replay cache + recovery banner — issue #183. |
| [[features/relay-management]] | NIP-65 relay list、outbox model、target relay publish、接続制限。 |

## UI / Design

| Page | Summary |
|---|---|
| [[ui/android-ios-sync]] | Android と iOS の見た目・挙動を揃えるための重要制約。 |
| [[ui/design-tokens]] | `design-tokens/constants.json` と生成物の関係。 |
| [[ui/post-row]] | 投稿行、PostActions、media、embedded Nostr card、quote/reply rendering。 |

## NIPs

| Page | Summary |
|---|---|
| [[nips/README]] | コード確認に基づく対応 NIP 一覧と規約。 |
| [[nips/kind-registry]] | upstream NIP / registry-of-kinds に基づく kind audit notes。 |
| [[nips/nip-04]] | Legacy encrypted DM compatibility paths。 |
| [[nips/nip-17]] | Private Direct Messages と native Talk の legacy 境界。 |
| [[nips/nip-18]] | Reposts and quote repost behavior。 |
| [[nips/nip-23]] | Long-form content / kind 30023。 |
| [[nips/nip-25]] | Reactions / custom reactions。 |
| [[nips/nip-30]] | Custom emoji lists and sets。 |
| [[nips/nip-44]] | Versioned encryption for DMs, NIP-46, signer helpers。 |
| [[nips/nip-51]] | Lists: mute, bookmarks, emoji list。 |
| [[nips/nip-58]] | Badges: awards, definitions, profile badges。 |
| [[nips/nip-59]] | Gift wrap: NIP-17 DMs and Marmot Welcome delivery。 |
| [[nips/nip-5a]] | Static Websites / nsites。Scroll mini-app kinds 1227/10027 との境界。 |
| [[nips/nip-46]] | Nostr Connect / 外部署名。 |
| [[nips/nip-50]] | Search capability / searchnos / feedback-loop MCP。 |
| [[nips/nip-57]] | Lightning Zaps。 |
| [[nips/nip-65]] | Relay List Metadata / outbox model。 |
| [[nips/nip-70]] | Protected events / `[-]` tag。 |
| [[nips/nip-71]] | ろくなな short video / kind 34236。 |
| [[nips/nip-98]] | Upload HTTP auth / Blossom auth。 |
| [[nips/nip-b7]] | Blossom upload ecosystem、kind 10063 / 24242、NIP-96 legacy 境界。 |

## Decisions

| Page | Summary |
|---|---|
| [[decisions/README]] | ADR 形式で設計判断を蓄積する場所。 |
| [[decisions/_template]] | 新規 ADR を起票するためのテンプレート。 |
| [[decisions/adr-0001-ios-observation]] | iOS ViewModel に iOS 17+ Observation を使う判断。 |
| [[decisions/adr-0002-native-talk-uses-marmot-mls]] | Native Talk は Marmot MLS 中心。 |
| [[decisions/adr-0003-ios-external-signing-uses-nip46]] | Superseded: iOS external signing used NIP-46 before ADR-0023. |
| [[decisions/adr-0004-design-tokens-are-source-of-truth]] | Design tokens を source of truth とする判断。 |
| [[decisions/adr-0005-postactions-no-reply-button]] | PostActions に reply button を置かない判断。 |
| [[decisions/adr-0006-web-rust-bridge-is-stub]] | Web Rust bridge は現状 stub。 |
| [[decisions/adr-0007-design-crit-ritual]] | Weekly Nuru Design Crit を制度化する判断。 |
| [[decisions/adr-0008-four-freedoms-mission]] | 4軸自由ドクトリンを長期ミッションとして起票する判断。 |
| [[decisions/adr-0009-mls-db-encryption]] | MLS storage DB を SQLCipher で暗号化する判断 (issue #181)。 |
| [[decisions/adr-0010-passkey-prf-direct-method]] | Passkey PRF を直接署名手段として採用する判断。 |
| [[decisions/adr-0011-nuruh-ip-doctrine]] | ぬるる IP 憲章 (四箇条)。「商品ではなく住人」として育てる長期方針。 |
| [[decisions/adr-0012-monday-release-nuru-production-system]] | 月曜リリース列車と Nuru Production System を採用する判断。 |
| [[decisions/adr-0015-home-tab-renewal]] | LINE Home風にホームを中心タブへ刷新し、フォローフィード/設定を集約する判断。ろくななは6月 Home scope から除外。 |
| [[decisions/adr-0014-local-first-product-metrics]] | local-first metrics 実装は6月 Phase 1では見送り。実機 manual QA を一次情報としてオンボーディング改善を回す判断。 |
| [[decisions/adr-0013-relay-feed-removal]] | スパム・違法コンテンツ流入経路になったリレーフィードを主要UIから削除し、リレー設定/投稿/検索用途は維持する判断。 |
| [[decisions/adr-0018-rokunana-root-tab-removal]] | ろくななは root tab から外し、6月は UI 移設せずコードだけリポジトリに dead-but-preserved として残す。 |
| [[decisions/adr-0019-ios-rust-ffi-write-path]] | iOS Rust FFI write-path migration starts from keygen/sign/publish contracts; Passkey/Nosskey remains platform signer path and NIP-46 signer is superseded by ADR-0023. |
| [[decisions/adr-0017-nip-5a-mini-apps]] | NIP-5A ミニアプリを WebView/static-site として安全に起動する manifest / permission 境界。 |
| [[decisions/adr-0016-news-curation-model]] | ニュースタブは NIP-23 + NIP-32 を 2-hop 信頼グラフで発見・表示する。 |
| [[decisions/adr-0021-open-speech-scoped-reach]] | Open speech with scoped reach。発言権と表示 / 到達権を分け、ネイティブ投稿 parity を維持する判断。 |
| [[decisions/adr-0022-ios-four-tab-navigation]] | iOS root navigation は ホーム / トーク / タイムライン / ミニアプリ。 |
| [[decisions/adr-0023-ios-remove-nip46-signer]] | iOS NIP-46 signer を廃止。 |
| [[decisions/adr-0024-ios-startup-relay-connection-dedupe]] | iOS startup relay connection dedupe。 |

## Maintenance checklist for agents

意味のある実装変更をしたら、以下を確認してください。

- 関連する Wiki ページを更新したか。
- 新規ページを追加した場合、この `index.md` に追記したか。
- `log.md` に `## [YYYY-MM-DD] type | title` 形式で追記したか。
- Wiki の主張に根拠ファイルを付けたか。
- 不明点や推測を `Open Questions` に分離したか。
- 文化判断 ([[culture/principles|五箇条]] / [[culture/not-doing|やらないこと]]) に抵触しないか確認したか。

## Source references

- `AGENTS.md`
- `docs/wiki/log.md`
- Source references listed in each linked wiki page.

## Quality

| [[quality/qa-2026-06-01]] | iOS Rust FFI Phase 1.1 read-only diagnostics の manual QA PASS 記録。 |

| [[nips/nip-32]] | NIP-32 labels。Birdwatch/context label support と News recommended / おすすめ label discovery design。 |
