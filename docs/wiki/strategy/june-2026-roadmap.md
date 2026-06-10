# June 2026 Monthly Roadmap — Network-Scoped Home, News, and NIP-5A Mini Apps

## Summary

2026年6月は、5月に制度化した ThemaDAY / Nuruh IP / Monday Release System を、製品の主要タブ再設計に落とし込む月。当初は News を含む4タブ案があったが、2026-06-09 の iOS zero-base 決定により、iOS root target は **ホーム / トーク / タイムライン / ミニアプリ** の4タブへ更新された。既存の「ろくなな」機能は完全削除せず、**コードはリポジトリにキープしつつ UI 動線を持たない (dead-but-preserved)** 扱いとする (ADR-0018 / ADR-0022)。

## Monthly objective (確定 2026-06-01 ThemaDAY)

> **「オンボーディング改善を主軸に、リレーフィードを廃止し、ホーム/ニュース/ミニアプリの 4タブ骨格を 3 プラットフォーム同等で安全に立ち上げる月」**

- 主文: **オンボーディング改善**。新規ユーザーの最初の 5 分を 3 プラットフォーム同等で磨く。
- 並走: 4タブ骨格 (iOS zero-base: ホーム / トーク / タイムライン / ミニアプリ) + リレーフィード廃止 + 2-hop 信頼グラフ。
- 計測: ADR-0014 に従い local-first metrics は実装しない。manual real-device QA を一次情報とする。
- DAU / WAU / 対応 NIP 数のような短期数値は目的にしない (Charter not-doing 既決)。

| New tab | 役割 | 主な変更 |
|---|---|---|
| ホーム | LINE Home renewal 2026 を参照した中心タブ | Body は **アクティビティ / コンテンツ** の2層。既存 Timeline フォローフィードは **コンテンツ** へ移設。既存プロフィール / 自分の投稿一覧 / いいね一覧は Home header 位置の account/profile icon 内へ移設。**ろくなな入口は6月中は置かない (ADR-0018 amendment)**。 |
| トーク | 会話 | 既存方針を維持。 |
| ニュース | NIP-23 長文記事 + NIP-32 ラベル | 旧タイムラインをリブランディング。2-hop信頼グラフ型で発見。 |
| ミニアプリ | NIP-5A mini apps | WebView で静的サイトを開く。2-hop信頼グラフ型 + curated manifest validation。 |

最重要の安全判断として、**リレーフィードは廃止**する。タイムラインのリレータブにはスパム・違法コンテンツが溢れており、ぬるぬるの文化憲章とストア公開品質に反するため、任意リレーの生フィードを主要導線に置かない。

ニュースタブやミニアプリなどの発見面は、原則として **2-hop 信頼グラフ型**に制限する。初期範囲は「自分がフォローしている人 + その人たちがフォローしている人」(2-hop follow graph) を基本にする。これにより、Nostr の開放性を維持しつつ、初期体験にスパム・違法コンテンツが流入する確率を下げる。

### 2026-06-02 ThemaDAY update

- **リレーフィード削除は完了済み**。Theme 0 は未完了リスクではなく、verification / dead-code cleanup の対象として扱う。
- **iOS Rust FFI は現行スコープ完了済み**。6/8 release train の blocker ではなく、Keychain / Passkey/Nosskey 境界を守った状態で完了済みとして記録する。NIP-46 signer は 2026-06-09 に廃止方針へ更新。
- Home renewal は、LINE Home renewal 2026 を参照し、Home body を **「アクティビティ」/「コンテンツ」** の2層構造に変更する。
- 既存 Home のプロフィール、自分の投稿一覧、いいね一覧は、Home header 位置に置く account/profile icon 内へ移設する。
- 既存 Timeline のフォローフィードは、Home の **コンテンツ** エリアへ移設する。

### 2026-06-09 iOS zero-base update

- iOS root navigation target is **ホーム / トーク / タイムライン / ミニアプリ** (ADR-0022).
- News is no longer an iOS root tab in the zero-base target; any News code is feature/history or future non-root surface until re-decided.
- iOS NIP-46 signer is removed (ADR-0023). NIP-55 remains forbidden.
- Startup relay connection dedupe is a P0 performance/stability follow-up (ADR-0024).

## User decisions recorded on 2026-05-31

1. **6/1 Google Play リリースはしない。** 1.5.5 は次回リリース列車へ延期。
2. **P5 Compass follow-up は完了済み。**
3. **P4 NIP-50 MCP はしばらく見送り。** ある程度リファクタ済みのため、6月の主戦場から外す。
4. **NIP-5A は Nostr Compass の NIP-5A を指す。** WebView で静的サイトを開いてミニアプリとして使えるようにする方向。
5. **ミニアプリ課金は将来余地。** 実際にユーザーが課金コンテンツを公開するかは未確定。6月は課金より NIP-5A 実行環境・発見・安全境界を優先。
6. **ニュースタブやミニアプリなどの発見面は、2-hop 信頼グラフ型で制限し始める。**
7. **ろくなな root tab は不要。ただし機能はコードとしてキープ。** 6月タブ再編で root tab から外す。**6月中は移設も行わず**、コードだけリポジトリに残す dead-but-preserved 扱い (ADR-0018 amendment 2026-06-01)。移設先判断は post-June に持ち越す。
8. **NIP-5A manifest / 起動情報の検証方法は実装側に一任。** 本ロードマップでは安全側の curated + signed manifest 方針を採用する。

## User decisions recorded on 2026-06-02

1. **リレーフィードは削除完了済み。** 以後は未完了 theme ではなく verification / cleanup として扱う。
2. **iOS Rust FFI は現行スコープ完了済み。** 以後は release blocker ではなく、既存 guardrails の維持確認として扱う。
3. **Home は「アクティビティ」/「コンテンツ」の2層構造にする。** LINE Home renewal 2026 を参照する。
4. **プロフィール / 自分の投稿一覧 / いいね一覧は Home header の account/profile icon 内へ移設する。** Home body を personal dashboard 化しない。
5. **既存 Timeline フォローフィードは Home のコンテンツエリアへ移設する。**

## Current behavior

- iOS zero-base root target is 4 tabs: ホーム / トーク / タイムライン / ミニアプリ. Older AGENTS/wiki text mentioned 5-tab or News-root variants and is superseded by ADR-0022.
- リレーフィード削除は完了済み。Android/iOS/Web の主要導線では任意リレー生フィードを扱わない。
- NIP-23 long-form content と NIP-32 labels は既にプロダクト内で扱う前提がある。
- ミニアプリタブは現状、設定系ミニアプリも混ざるハブになっている。

## Theme 0 — Relay feed removal (emergency safety decision)

### Decision

リレーフィード、特に「タイムラインタブのリレータブ」のような任意リレー生フィードは廃止する。

### Status update (2026-06-02)

リレーフィード削除は完了済み。W23/W24 の残作業は、主要導線から復活していないことの確認、不要 prefetch / dead code の整理、上級者向け relay settings との境界確認に限定する。

### Keep / Remove boundary

| Area | Decision | Reason |
|---|---|---|
| リレー生フィード | Remove | スパム・違法コンテンツが主要導線へ入るため |
| フォローフィード | Keep, move to Home | ユーザーの社会的 graph に基づくため |
| 投稿時の relay selection | Keep | ユーザーの投稿配信先選択であり、生フィードとは別 |
| Relay settings | Keep under Home settings | 上級者向け設定として必要 |
| Search relay / NIP-50 | Keep but not prioritized in June | P4 は見送り |

### ADR candidate

- docs/wiki/decisions/adr-0013-relay-feed-removal.md

## Theme 1 — Onboarding improvement with Manual QA

オンボーディング改善は 6月の中心テーマ。ただし、改善前後の差分を見るために P1 manual QA を先に薄く入れる。

### P1: Manual QA as prerequisite

P1 is no longer a code implementation task. The June Phase 1 feedback loop is manual and real-device based.

Why:

- The maintainer will perform direct real-device testing.
- June changes are mostly UI / navigation / safety architecture, where qualitative feedback is faster than counters.
- Adding Android/iOS/Web counters now creates implementation and validation overhead.
- Metrics can be reconsidered after Home / News / Mini Apps are structurally stable.

Manual QA checklist:

| Area | Checks |
|---|---|
| Onboarding | new registration, passkey login, nsec/external signer where supported, first-post guidance |
| Posting | text post, image post, failure message, 140-char limit, reply/quote paths |
| Home | follow feed migration, profile/login state, settings entry (no rokunana entry — code preserved only, per ADR-0018 amendment) |
| News | no relay-wide feed, NIP-23 long-form focus, NIP-32/2-hop trust graph behaviour |
| Mini Apps | NIP-5A static-site open, unsafe navigation handling, no private-key injection, native permission mediation |

Findings should be recorded in ThemaDAY / Design Crit notes instead of product counters.

### ADR reference

- docs/wiki/decisions/adr-0014-local-first-product-metrics.md — Deferred metrics implementation; manual QA adopted for June.


### ADR candidate

- docs/wiki/decisions/adr-0014-local-first-product-metrics.md

## Theme 2 — Home tab renewal (LINE Home inspired)

LINE Home renewal 2026 を参照し、ホームタブを「アプリの中心」にする。2026-06-02 のユーザー決定により、Home body は **アクティビティ / コンテンツ** の2層構造とする。

### Home header account/profile icon

Home header 位置に account/profile icon を置き、既存 Home の以下を icon 内の hub に移設する。

- プロフィール / profile card
- 自分の投稿一覧
- いいね一覧

この hub は「自分の場所」として扱い、Home body を profile dashboard 化しない。秘密鍵 export や signer 状態など sensitive settings を扱う場合は、既存の platform guardrails (iOS fullScreenCover / Android full-screen surface / Web full-screen modal) を守る。

### Two-layer Home body

| Layer | Role | Initial scope |
|---|---|---|
| **アクティビティ** | 日々の動き・戻る理由 | 通知、リアクション、返信、メンション、account activity などの候補。任意リレー生フィードの代替にはしない。 |
| **コンテンツ** | 読む・眺める中心 | 既存 Timeline のフォローフィードをここへ移設する。 |

### Move into Home Content

- 現在の Timeline / タイムラインにある **フォローフィード**を、Home の **コンテンツ** エリアへ移設する。
- Home Content は基本的に **フォロー graph**。リレー生フィードを混ぜない。
- News / Mini Apps discovery を Home Content に混ぜる場合は、別途 Design Crit / ADR で判断する。

### Keep out of Home in June

- ~~ろくなな機能の入口またはショートカット~~ → **6月は移設しない。コードのみリポジトリにキープ (ADR-0018 amendment 2026-06-01)**
- 任意リレー生フィード → 削除完了済み。復活させない。

### ADR reference

- docs/wiki/decisions/adr-0015-home-tab-renewal.md

## Theme 3 — Timeline tab to News tab (NIP-23 + NIP-32)

旧タイムラインタブは「ニュース」タブへリブランディングする。ニュースの発見と表示は、ユーザー決定により **2-hop 信頼グラフ型**とする。

### Content type

- NIP-23 long-form content (kind 30023) を中心にする。
- short notes (kind 1) のリレー生流入は扱わない。

### 2-hop trust-graph curation + NIP-32 おすすめラベル

初期定義:

1. 1-hop: 自分がフォローしている pubkey。
2. 2-hop: 1-hop pubkey がフォローしている pubkey。
3. News candidate author: 1-hop または 2-hop に含まれる著者。
4. Label candidate: 1-hop または 2-hop に含まれる pubkey が付けた NIP-32 label。
5. Display rule: author または labeler のどちらかが 2-hop trust graph 内にある NIP-23 article を候補にする。

2026-06-03 update: NIP-32 の `recommended` / `おすすめ` ラベルを、NIP-23 article discovery の boost signal として使う。

Accepted recommendation label candidates:

| Namespace | Value | Meaning |
|---|---|---|
| `news` | `recommended` | News article recommendation |
| `news` | `おすすめ` | Japanese News recommendation |
| `content` | `recommended` | General content recommendation |
| `content` | `おすすめ` | Japanese general recommendation |

Ranking rules:

- 1-hop author は最も強い signal。
- 1-hop labeler のおすすめは強い boost。
- 2-hop author / 2-hop labeler のおすすめは中程度の boost。
- 複数 labeler のおすすめは加点してよいが cap を置く。
- `birdwatch` / context labels は correction/context として扱い、おすすめ boost には使わない。

Safety rules:

- 任意リレーからの NIP-23 全件流入はしない。
- 初回は 2-hop graph + default safe relays に限定。
- mute/block/report label は常に優先して除外。
- おすすめラベルは表示許可ではなく boost signal。安全フィルタを上書きしない。
- 将来、公式 curated account は boost signal として扱えるが、単独必須にはしない。

### Monetization note

長文コンテンツには note 的な課金/支援文化があるが、6月の Phase 1 では「課金実装」よりも、ニュース面の安全な発見・表示・ラベル設計を優先する。

### ADR candidate

- docs/wiki/decisions/adr-0016-news-curation-model.md

## Theme 4 — Mini app tab renewal with NIP-5A

### NIP-5A understanding

ユーザー指定の NIP-5A は Nostr Compass の NIP-5A ページを参照する。6月ロードマップでは、NIP-5A を次のように扱う。

> 静的サイトを WebView で開き、ぬるぬる内でミニアプリとして使えるようにする仕組み。

重要: 6月時点では **「ミニアプリでユーザーが課金コンテンツを公開する」ことまでは前提にしない**。まずは安全な WebView 実行環境、発見、起動、権限境界、ネットワーク制限を作る。

### Mini app discovery scope

- 原則として 2-hop follow graph から発見する。
- 任意リレーの全件探索はしない。
- 将来的に公式 curated list を追加可能。

### Manifest / launch validation (implementation-owned decision)

ユーザー判断「おまかせ」に基づき、NIP-5A mini app の起動情報検証は安全側に倒す。初期実装は **curated + signed manifest + WebView allowlist** を採用する。

Minimum validation:

1. Mini app entry must come from 2-hop trust graph or curated default list.
2. URL must be HTTPS.
3. URL host must match manifest host.
4. Redirects to unknown host are blocked unless user confirms external browser open.
5. Manifest must contain name, icon URL, start URL, origin, version, and declared permissions.
6. Private keys are never injected into WebView.
7. Signing/account access requires explicit permission sheet and native bridge mediation.
8. Local storage/cookies are isolated per mini app origin when platform allows.
9. Unknown URL schemes are blocked by default.
10. Payment / paid unlock permission is disabled in Phase 1.

### WebView guardrails

- HTTPS only
- static-site first
- external navigation confirmation
- no private key injection into WebView
- signing / account access is explicit permission only
- unsafe URL / unknown scheme blocked
- storage boundary per mini app

### Payment stance

LINE ミニアプリのデジタルコンテンツ課金のような方向性は参考にするが、ぬるぬるの 6月 Phase 1 では **課金レールを固定しない**。

Reasons:

- NIP-5A mini apps are simply WebView/static-site execution surfaces at first.
- 実際にユーザーが課金コンテンツを公開するかは不明。
- iOS/Google Play の IAP 規約リスクがあるため、課金による機能解放は ADR なしで実装しない。
- Zap / Lightning は当面「支援・投げ銭」扱いに留めるのが安全。

### ADR candidates

- docs/wiki/decisions/adr-0017-nip-5a-mini-apps.md
- docs/wiki/decisions/adr-0018-mini-app-payment-rails.md (課金実装が現実化した時点で起票)

## Root tab target

### Proposed June target

| Position | Tab | Notes |
|---:|---|---|
| 1 | ホーム | LINE Home renewal 2026 inspired. Header account/profile icon contains profile / my posts / likes. Body has **アクティビティ** and **コンテンツ**; following feed moves into Content. No rokunana entry in June. |
| 2 | トーク | Existing Talk. |
| 3 | ニュース | Replaces Timeline. NIP-23 + NIP-32 with 2-hop trust graph. |
| 4 | ミニアプリ | NIP-5A WebView apps with 2-hop trust graph. |

### Removed from root tab

- ろくなな: root tab からは外す。**6月はホーム/ミニアプリ/設定への移設を行わず、コードだけリポジトリに dead-but-preserved として残す** (ADR-0018 amendment 2026-06-01)。
- リレーフィード: 廃止。上級者向け relay settings は残す。

## Suggested schedule

| Week | Focus | Output |
|---|---|---|
| W23 6/01-07 | ADRs + completed relay-feed/iOS-FFI verification + Home two-layer Design Crit | Relay feed removal and current-scope iOS Rust FFI recorded as complete; Home header icon + アクティビティ/コンテンツ design fixed |
| W24 6/08-14 | 1.5.5 release train + onboarding improvement | 1.5.5 includes safety/tab groundwork + QA findings applied |
| W25 6/15-21 | Onboarding polish + Home renewal + News internal dogfood alpha | Follow feed moved to Home コンテンツ; Activity layer dogfooded; NIP-23 News internal dogfood alpha |
| W26 6/22-28 | NIP-5A mini app WebView alpha | Mini app static site can open safely in WebView |
| W27 6/29-07/05 | 1.6.0 release + June ThemaDAY | 4-tab architecture review + first external News communication if dogfood is stable |

## Open Questions

- Home のアクティビティ層の最小初期データ源: notifications / reactions / replies / mentions / account activity のどこから始めるか。
- Header account/profile icon に settings まで含めるか、profile / my posts / likes に限定して settings gear を別に残すか。
- 2-hop graph の取得コスト: 起動時に全部取るか、News/Mini Apps タブを開いた時に遅延取得するか。
- 2-hop graph の重みづけ: 1-hop author > 2-hop author > 1-hop labeler > 2-hop labeler のような rank を入れるか。
- NIP-5A manifest の canonical event / metadata 仕様は Nostr Compass の仕様更新に追随する必要がある。
- ろくなな機能の post-June 扱い: Home shortcut / Mini App / Settings 内の実験機能 / permanent code-only retention のどれにするか。6月中は判断しない。

## Source references

- User decision, 2026-05-31: local-first metrics implementation is deferred; manual real-device QA is sufficient for June Phase 1.
- User decision, 2026-05-31: NIP-5A URL, 2-hop trust graph, rokunana root-tab removal with initial keep-and-move direction.
- User decision, 2026-06-01: rokunana is tightened to code-only retention in June (no Home/Mini App/Settings entry); onboarding is the leading clause of the June monthly objective; News alpha is internal dogfood in W25 with external communication deferred to 1.6.0 if stable.
- User decision, 2026-06-02: relay feed deletion completed; iOS Rust FFI current scope completed; Home body changed to アクティビティ / コンテンツ; profile / my posts / likes move behind Home header account/profile icon; Timeline following feed moves to Home Content.
- Nostr Compass NIP-5A: https://nostrcompass.org/ja/topics/nip-5a/
- LINE Home renewal 2026: https://guide.line.me/ja/update/home-renewal2026.html
- LINE mini app digital content payment release: https://www.lycorp.co.jp/ja/news/release/020192/
- AGENTS.md root tab sync target and wiki update rules.
- docs/wiki/strategy/themaday-2026-05-31-week-review.md

## Related pages

- [[strategy/themaday-2026-06-02-product-eng-design]]
- [[strategy/themaday-2026-05-31-week-review]]
- [[strategy/nuruh-ip-2026-05-28]]
- [[decisions/adr-0012-monday-release-nuru-production-system]]
- [[nips/nip-23]]
- [[nips/nip-50]]
