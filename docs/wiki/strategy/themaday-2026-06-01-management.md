# ThemaDAY 2026-06-01 — マネジメント / 経営会議 + リーダー陣すり合わせ

## Summary

> 2026-06-09 update: iOS NIP-46 signer references in this historical meeting note are superseded by ADR-0023; iOS signer paths now target internal nsec/Keychain and Passkey/Nosskey.

2026-06-01 (月) 11時頃の ThemaDAY 経営会議。テーマは **「マネジメント」**。
週次振り返り (themaday-2026-05-31-week-review) と 6月ロードマップ (june-2026-roadmap) で
決まった方向を、**経営会議 → リーダー陣すり合わせ** の二段で着地させる回。

新機能の量ではなく、**「誰が・何を・いつまでに・どの品質ゲートで」** を確定するための場。

5/31 の Outcome により、6/1 Google Play release はスキップ。次の Monday Release 列車は
**6/8 月曜 (1.5.5 候補)** に置く。今週 (W23) は ADR 化と未コミット 13+ 件整理、ホーム/ニュース
/ミニアプリの設計着手を並走させる。

## Current behavior

- ぬるぬる本体は maintainer ソロで開発されているが、組織機能としての「リーダー陣」を
  **役割 (hat)** として明示する必要がある。同じ人が複数 hat をかぶることを前提に、
  各 hat の責任境界・週次成果物・GO/HOLD 基準を分けて運用する。
- ThemaDAY は週次の経営/内省アラインメント儀式として既に運用されている
  ([[themaday-2026-05-25]], [[themaday-2026-05-31-week-review]])。
- 本ページはその「経営会議 + リーダー陣すり合わせ」版で、6月 Phase 1 の起点として置く。

## リーダー陣 (role hats) の定義

ソロ運用前提のため、同一人物が複数 hat を持つ。ただし **議論・判断・記録は hat 単位**
で行う。これは Block 記事 *From Hierarchy to Intelligence* の含意 (役割を流動的に運用しつつ、
判断履歴は明示する) に沿う。

| Hat | 主な責任 | 週次成果物 | GO/HOLD 判定 |
|---|---|---|---|
| **Release Conductor** | 月曜リリース列車の最終 GO/HOLD/STOP/HOTFIX | release notes、log.md エントリ、平台公開 | release-quality 5ゲート全通過 |
| **Quality / NPS Lead** | 実機テスト時間割、Andon 整理、Kaizen | platform 別 test 結果、5 Whys 記録 | FAIL 0、BLOCKED は説明付き |
| **Design / UX Lead** | 水曜 Design Crit ファシリ、4ラベル判定 | crit-logs/YYYY-WW.md | 議題が無い週も開催する |
| **Culture / IP Lead** | Charter / 五箇条 / Nuruh IP / 日本語コピー | copy diff、IP 接点状況 | 第二条・第五条への抵触チェック |
| **Android Lead** | Android 実装、Compose / Kotlin / FFI 連携 | PR と AND-01〜08 結果 | AND series PASS |
| **iOS Lead** | iOS 実装、SwiftUI / Keychain / NIP-46 / FFI bridge | PR と IOS-01〜08 結果 | IOS series PASS、guardrails 遵守 |
| **Web Lead** | Web 実装、Next.js / connection-manager / secure-key-store | PR と WEB-01〜04 結果 | secure-key-store 不変、relay limit 維持 |
| **Rust Core Lead** | nurunuru-core / FFI / UniFFI / XCFramework | bindings 同期、build script | Kotlin/Swift 双方で動く |
| **Partnership Lead** | 外部メディア / 開発者向け接点 | 外部接点ログ | 露出より trust surface を優先 |
| **Wiki / Knowledge Lead** | docs/wiki/ 同期、log.md 追記、ADR 起票 | log.md エントリ、index 更新 | AGENTS.md wiki ルール遵守 |

> **Note**: hat は「肩書き」ではない。判断履歴を後から追える形にするための整理。
> ソロ実装でも、PR / commit message / ADR には「どの hat で判断したか」を 1 行入れる。

## 経営会議 — 5議題 (60分)

### 議題 1 — 6月 monthly objective の確定 (10分)

**提案**: 6月 Phase 1 の monthly objective を以下 1 文で固定する。

> **「オンボーディング改善を主軸に、リレーフィードを廃止し、ホーム/ニュース/ミニアプリの 4タブ骨格を、3 プラットフォーム同等で安全に立ち上げる月」**

- 主目的は **オンボーディング改善 + 安全性 + 構造移行**。新機能数や DAU を目的にしない。
- onboarding 改善は Theme 1、ただし「定着の週」の延長として 4タブ骨格と同時並行で進める。
- ろくなな機能は root tab から外し、**6月は UI 移設しない**。コードだけリポジトリに dead-but-preserved としてキープする (2026-06-01 ユーザー決定)。

**論点**:
- (A) 「onboarding 改善」を 6月 monthly objective の主文に入れるか、副文に置くか?
- (B) 「ニュースタブ alpha」は W25 (6/15-21) で正式に α 評価対象にするか、α は 7月送りか?

**確定**: onboarding を monthly objective の主文に置く。ニュース α は W25 内で **内部 dogfood α** に留め、外部告知は 1.6.0 (6/29 月曜列車) で行う。

---

### 議題 2 — 1.5.5 / 6/8 列車のスコープ確定 (12分)

**提案スコープ (GO 候補)**:

1. **Theme 0 (Relay feed removal)** の 3 プラットフォーム同等化を完了。
   - 現状: Android / iOS / Web の Timeline 主導線からリレータブ・relay column は既に除去済み (5/31 commit)。
   - 1.5.5 で確定: relay-wide background prefetch の不要 path も停止確認、回帰なし。
2. 未コミット 13 件 (modified) + untracked 多数 を 3 グループに分けて整理:
   - (a) commit すべき軽微修正 (README、layout、LoginScreen、manifest、zapstore.yaml、TimelineComponents、TimelineScreen、TimelineViewModel、MainTabView、TimelineView、TimelineTab)
   - (b) **必ず commit すべき wiki 同期** (docs/wiki/index.md, docs/wiki/log.md, 6本の新規 ADR, june-2026-roadmap, themaday-2026-05-31-week-review, docs/wiki/copy/)
   - (c) `.gitignore` 候補 (release-artifacts/, fastlane の自動生成物)
3. iOS Rust FFI Phase 1: **1 メソッドだけ** Stub → Live 切替 (例: `generateKeypair` or `signEvent`)。
4. Android: TimelineComponents / TimelineScreen / TimelineViewModel の未コミット差分を整理し、
   フォローフィードが「Home へ移設する前提の Timeline ビュー」として一旦 stable な形でリリースに乗る。
5. iOS: MainTabView / TimelineView の未コミット差分を、同じく 4タブ移行の中間形として整える。

**HOLD 候補 (1.5.5 に乗せない)**:
- ホームタブ刷新 (本格実装) → W25
- ニュースタブ rebrand (NIP-23 + NIP-32) → W25〜W26
- NIP-5A mini app WebView α → W26
- NIP-50 MCP feedback-loop → 当面見送り (5/31 決定)
- ローカル端末計測 → Deferred (ADR-0014)

**論点**:
- (C) Rust FFI Phase 1 は **どのメソッド** から live 化するのが安全か?
  - 候補: (i) `generateKeypair` — 失敗しても既存セッションを壊さない、(ii) `signEvent` — 影響大、(iii) `validateNpub` 系 — 読み取り専用で最も安全
  - **提案デフォルト**: (iii) 読み取り専用ヘルパー 1 本から。`signEvent` は Phase 1.5。
- (D) 未コミット整理は **本日 (月曜)** 中に完了するか、火曜まで滑らせるか?
  - **提案デフォルト**: wiki 同期 (b) と ADR 6本は本日中に commit。コード差分 (a) と .gitignore (c) は火曜午前まで許容。

---

### 議題 3 — 4タブ再編の担当境界 (15分)

| タブ | 主リーダー | 副リーダー | Design Crit 起案者 | 着手 W |
|---|---|---|---|---|
| ホーム | Android Lead + iOS Lead | Design / UX Lead | Design / UX Lead | W25 |
| トーク | (既存維持) | — | — | 触らない |
| ニュース | Web Lead (NIP-23/NIP-32 設計先行) + Android Lead | iOS Lead | Design / UX Lead | W25 〜 W26 |
| ミニアプリ | iOS Lead (WebView 検証先行) + Android Lead | Rust Core Lead (署名境界) | Design / UX Lead | W26 |

理由:
- **ホームは LINE Home 模倣の手触り** が肝で、Android/iOS のネイティブ実装が先。
  Web は後追いで OK (parity matrix に追記)。
- **ニュース** は NIP-23 + NIP-32 + 2-hop trust graph の **データ層**が支配的なので、
  Web (= JS の nostr-tools と直結) で設計を先行する。
- **ミニアプリ (NIP-5A)** は WebView 実行環境 + 権限境界 + 鍵分離が肝。iOS の WKWebView と
  Android の WebView は挙動差があるため両 hat 同時に着手し、署名境界の仕様は Rust Core Lead が握る。

**論点**:
- (E) ホームを Android → iOS → Web の順で実装するか、Android と iOS を並走させるか?
  - **提案デフォルト**: 並走。ただし Design Crit (水曜) で **同じモックを両 platform に当てる** ことで pixel-for-pixel sync を維持。
- (F) ろくなな機能の移設先は「ホーム内ショートカット」か「ミニアプリ化」か?
  - **確定**: W25 まではホーム内ショートカットも作らない。コードだけリポジトリに残してキープする。UI 移設判断は post-June に延期。

---

### 議題 4 — 文化 KPI と「曜日偏在」問題 (10分)

5/31 振り返りで指摘された問題:
- コミット曜日偏在 (月火集中 / 水土ゼロ)
- 水曜 Design Crit が実行された痕跡なし

**提案**: 文化 KPI を 5 つに固定する。短期数値で第一条〜第五条を曲げない (not-doing 既決)。

| KPI | 目標 | 計測 |
|---|---|---|
| 月曜列車 GO/HOLD/STOP/HOTFIX 比率 | GO ≥ 50%、STOP/HOTFIX < 10% | log.md |
| 水曜 Design Crit 開催率 | 100% (議題が無い週も開催) | crit-logs/ の有無 |
| Andon (STOP/HOLD) 解消時間 | 平均 < 7日 | issue label の timestamp |
| 五箇条参照率 (PR description) | 100% | grep |
| wiki/log.md 追記の遅延 | 0 日 (当日中に追記) | log.md timestamp vs commit timestamp |

**論点**:
- (G) 水曜 Design Crit を強制するために、**水曜の commit 0 件を Andon 化** するか?
  - **提案デフォルト**: する。水曜 0 commit が 2 週連続したら STOP ラベル相当として ThemaDAY 議題化。
- (H) DAU / WAU を **モニタしない** ことを明文化するか?
  - **提案デフォルト**: 既に not-doing に書かれているので明文化済み。ただし「ストア page view と download 数 (= 既に CSV で受領済み)」は trust signal として継続観察。

---

### 議題 5 — Partnership と外部接点 (8分)

- Nostr Compass #24 follow-up は **5/31 完了済み** (P5 done)。
- 次の外部接点候補 (passive):
  - 国内 Nostr コミュニティ (yabu.me 周辺) の月例集まり
  - and other stuff 経由の他クライアントメンテナとの相互レビュー
  - LINE Seed JP / 日本語組版周辺の同人領域 (Nuruh IP との接続点)
- **やらないこと** (再確認):
  - 「対応 NIP 数を競う」プレスリリース
  - 「Web3 アプリ」「dApp」呼称での露出
  - 短期流入を目的とした提携

**論点**:
- (I) `docs/wiki/strategy/partnership-log.md` の起票タイミングは?
  - **提案デフォルト**: 件数 3 を超えたら起票 (5/31 既決)。現在 1 件。

---

## リーダー陣すり合わせ — hat 別アジェンダ

経営会議で確定した方針を、hat 別に **「来週 (W23 残り) の成果物」** に落とす。

### Release Conductor

- **今週の主成果物**: 6/8 (月) 1.5.5 GO 判定。
- **必須**: 未コミット 13+ 件の triage (本日中)、release notes 草案 (木曜)、
  release-quality.md の標準実機テスト時間割を **W23 から実行**。
- **GO 基準**: AND-01〜08, IOS-01〜08, WEB-01〜04, XPF-01〜02 が PASS。
- **論点**: 木曜の RC 作成までに ADR 6 本 (0013〜0018) の Accepted 化が終わるか?
  Accepted でない ADR があるなら、当該機能は release scope から外す。

### Quality / NPS Lead

- **今週の主成果物**: 6/8 RC に対する manual QA セッション (火 6/02 → 月 6/08 直前)。
- **必須**: platform 別 dedicated slot を 1 度実行 (iOS 午前 / Android 午後 / Web 夕方)。
- **論点**: ADR-0014 (local-first metrics deferred) を踏まえ、QA findings を「ThemaDAY/Design
  Crit notes」に落とす運用を W23 から開始する。記録テンプレを作るか?
  - **提案デフォルト**: 軽量テンプレ `docs/wiki/quality/qa-YYYY-MM-DD.md` を作る。

### Design / UX Lead

- **今週の主成果物**: 水 6/03 の Nuru Design Crit を **必ず開催**。
- **議題候補** (1 つ選ぶ):
  1. **ホームタブ刷新の方向性レビュー** (LINE Home 参照モック の沈黙批評)
  2. **ニュースタブの空状態 (empty state) と 2-hop graph 説明 UI**
  3. **リレーフィード廃止後の「上級者向け relay settings 動線」**
- **提案デフォルト**: (1) を先行。理由: 6/15 W25 で着手するため、前週 (W23) に方向性を確定しておくと
  W24 で実装、W25 で polish に入れる。

### Culture / IP Lead

- **今週の主成果物**: 4タブの **日本語コピー初稿** (ホーム / トーク / ニュース / ミニアプリ)。
- 「ニュース」の表記は確定だが、補助コピー (タブ説明・空状態) は未確定。
- **Nuruh IP 接点** (おやつ 15時 / おやすみ 23時) は 90日チェックリスト (Phase 0) で **Week 3-4**。
  6月前半に 1 回試運転するか?
  - **提案デフォルト**: W23 中に 1 度だけ「おやすみ 23時」を試運転投稿。反応を観察し、Week 3-4 の本格運用判断材料にする。

### Android Lead

- **今週の主成果物**:
  - 未コミット差分 (TimelineComponents, TimelineScreen, TimelineViewModel) を本日中に commit。
  - relay-wide prefetch 停止箇所の最終確認 (回帰なし)。
  - AND-01〜08 のうち、最低 AND-03 / AND-04 / AND-05 を火曜午後の Android slot で実行。
- **論点**: ホーム移設の準備として「フォローフィードの Composable 分離」を W24 で始めて良いか?
  - **提案デフォルト**: 良い。ただし W23 中の commit には乗せない (列車を太らせない)。

### iOS Lead

- **今週の主成果物**:
  - 未コミット差分 (MainTabView, TimelineView) を本日中に commit。
  - Rust FFI Phase 1 の最初の 1 メソッド (議題 2 (C) で決定) を `NuruNuruFFILiveClient` に差し替え。
  - IOS-01〜08 のうち、最低 IOS-01 / IOS-03 / IOS-05 を火曜午前の iOS slot で実行。
  - `ios/scripts/build-xcframework.sh` を配置 (5/31 P2 で宣言済み)。
- **論点**: ios/GUARDRAILS.md に Phase 1 「動いている範囲だけ」の追記が必要。
  - **提案デフォルト**: live 化したメソッドが PR でマージされたタイミングで GUARDRAILS.md と AGENTS.md を同時更新。

### Web Lead

- **今週の主成果物**:
  - 未コミット差分 (app/layout.js, components/LoginScreen.js, components/TimelineTab.js, public/manifest.json) を本日中に commit。
  - WEB-01〜04 を夕方の Web slot で実行。
  - **ニュース設計の data 層プロトタイピング**: nostr-tools で NIP-23 (kind 30023) + NIP-32 label を 2-hop graph で fetch するクエリを `lib/news.experimental.js` として置く (UI なし)。
- **論点**: secure-key-store / connection-manager に 4 タブ再編で触る必要は出るか?
  - **提案デフォルト**: 出ない。触ったら ADR を切る。

### Rust Core Lead

- **今週の主成果物**:
  - iOS Phase 1 で live 化する 1 メソッドを **Kotlin 側でも動作確認** (回帰なし)。
  - XCFramework build script レビュー (iOS Lead の `ios/scripts/build-xcframework.sh`)。
- **論点**: NIP-5A WebView の署名境界仕様は **どの層で握るか**?
  - **提案デフォルト**: Rust Core 側で `sign_for_mini_app(origin, payload, permission_grant)` のような関数を W26 に向けて設計開始。
    秘密鍵は WebView に注入されず、Rust 側で permission_grant を検証して署名する。

### Partnership Lead

- **今週の主成果物**: なし (focal week は 5/31 で締めた)。
- **継続観察**: Nostr Compass 周辺の反応、DM / リプ / ブクマの follow-up 1 件投稿は完了済み。
- **論点**: 次の能動的接点を打つか?
  - **提案デフォルト**: 打たない。6月は内部構造再編に集中。

### Wiki / Knowledge Lead

- **今週の主成果物**:
  - 本ページ (themaday-2026-06-01-management.md) を起票 (= 本コミット)。
  - docs/wiki/index.md に追記。
  - docs/wiki/log.md に追記 (本日)。
  - 未コミット ADR 6 本 (0013〜0018) を本日中に commit (= AGENTS.md wiki ルール遵守)。
- **論点**: hat 別の責任境界を、AGENTS.md 本体にも要約として追記するか?
  - **提案デフォルト**: 追記しない。AGENTS.md は「rules/schema」、本ページは「運営の判断履歴」として
    分離を維持。AGENTS.md からは本ページへのリンクのみ。

---

## 今日 (6/01 月曜) の Andon リスト

| ID | 内容 | Owner hat | 期限 | ステータス |
|---|---|---|---|---|
| AND-W23-01 | 未コミット wiki 同期 (index.md, log.md, ADR 6本, june-roadmap, themaday-2026-05-31-week-review, copy/, 本ページ) | Wiki / Knowledge Lead | 6/01 EOD | TODO |
| AND-W23-02 | 未コミット code 差分 (Android/iOS/Web の Timeline 系 + README, manifest, layout, LoginScreen, zapstore.yaml) | 各 platform lead | 6/02 AM | TODO |
| AND-W23-03 | `.gitignore` 追加 (release-artifacts/, fastlane の自動生成物) | Release Conductor | 6/02 AM | TODO |
| AND-W23-04 | 水曜 Design Crit 議題確定 (3候補から1つ) | Design / UX Lead | 6/02 EOD | TODO |
| AND-W23-05 | iOS Rust FFI Phase 1 の対象メソッド確定 | iOS Lead + Rust Core Lead | 6/02 EOD | TODO |
| AND-W23-06 | platform 別 dedicated slot を W23 中に各 1 回実行 | Quality / NPS Lead | 6/05 EOD | TODO |
| AND-W23-07 | 1.5.5 release notes 草案 | Release Conductor | 6/05 EOD | TODO |
| AND-W23-08 | 6/8 (月) 1.5.5 GO/HOLD/STOP/HOTFIX 判定 | Release Conductor | 6/08 17:00 | SCHEDULED |

## Decisions to record (今日中)

経営会議の **提案デフォルト** に対して、ユーザー (= 全 hat 兼任の maintainer) が
明示の Yes/No を出した時点で、以下を ADR / wiki に確定する。

- [x] 6月 monthly objective の主文 (議題 1): onboarding を主文に置く
- [x] 1.5.5 スコープと HOLD 一覧 (議題 2): 提案デフォルトで確定
- [x] iOS FFI Phase 1 対象メソッド (議題 2 (C)): 読み取り専用ヘルパー 1 本から
- [x] 未コミット整理の期限 (議題 2 (D)): wiki 同期は 6/01 EOD、code 差分は 6/02 AM
- [x] 4タブ担当境界 (議題 3): 提案デフォルトで確定
- [x] ろくなな機能の扱い (議題 3 (F)): 6月は移設せず、コードだけリポジトリに残す
- [x] 水曜 0 commit Andon 化 (議題 4 (G)): 2週連続で STOP 相当
- [x] QA テンプレ `docs/wiki/quality/qa-template.md` 起票 (Quality hat)

## Confirmed decisions (2026-06-01 11:23 JST)

User confirmed the following management decisions:

1. **Monthly objective**: onboarding is the leading clause. The June objective is onboarding improvement + relay-feed removal + safe 4-tab skeleton across Web/Android/iOS.
2. **News alpha**: W25 internal dogfood alpha; external communication deferred to 1.6.0 (6/29) if stable.
3. **iOS Rust FFI Phase 1**: start from one read-only helper method, not `signEvent`.
4. **Uncommitted work triage**: wiki sync by 6/01 EOD; code diffs by 6/02 AM.
5. **Home implementation**: Android/iOS parallel implementation using the same Design Crit mock.
6. **Rokunana**: remove root tab and do **not** create a Home/Mini App/Settings entry in June. Keep code only in the repository as dead-but-preserved.
7. **Wednesday 0-commit Andon**: two consecutive zero-commit Wednesdays become STOP-equivalent ThemaDAY agenda.
8. **Quality template**: create `docs/wiki/quality/qa-template.md` for manual real-device QA notes.

## Risks

- **Risk-1**: hat 多重保有で、判断記録が個人化し、Block の "intelligence" モデルから逸脱する。
  - 防御: PR / commit message / ADR に「どの hat 判断か」を 1 行で残す。
- **Risk-2**: 6月の monthly objective は onboarding を主文に置くが、構造移行タスクが肥大してユーザー体験品質が落ちる。
  - 防御: 水曜 Design Crit で必ず「実機の手触り」を議題にする。
- **Risk-3**: 未コミット山積みの再発。
  - 防御: 月曜 EOD = wiki 同期、火曜 AM = code 差分整理を **標準作業**化 (release-quality.md に追記)。
- **Risk-4**: NIP-5A WebView の署名境界が後付けで歪む。
  - 防御: Rust Core 側で関数シグネチャを W26 前に確定。

## Open Questions

- 水曜 Design Crit の議題が複数候補から1つに絞れない場合、PARK ラベルで翌週送りにする運用で良いか?
- ホームタブの「LINE Home っぽさ」をどこまで模倣するか — 模倣度の上限を Design Crit で決める。
- ニュースタブの 2-hop graph 取得を「タブを開いた時に遅延取得」にした場合、初回タップ時の体感速度は許容範囲か? → W25 dogfood で測定。
- ろくなな機能の post-June 扱いをどうするか: Home shortcut / NIP-5A Mini App / Settings entry / permanent code-only retention。6月中は判断しない。



## 2026-06-02 correction / superseding notes

The 2026-06-02 ThemaDAY product/engineering/design alignment supersedes several planning assumptions in this 2026-06-01 page:

- Relay feed removal is already complete; remaining work is verification / cleanup, not initial removal.
- iOS Rust FFI current release-planning scope is complete; it should not remain a 1.5.5 blocker.
- Home renewal direction is tightened: Home header gets an account/profile icon containing existing profile, my posts, and likes; Home body becomes **アクティビティ** / **コンテンツ**; existing Timeline following feed moves to **コンテンツ**.

See [[themaday-2026-06-02-product-eng-design]].

## Source references

- [[themaday-2026-05-25]]
- [[themaday-2026-05-28-partnerships]]
- [[themaday-2026-05-31-week-review]]
- [[june-2026-roadmap]]
- [[nuruh-ip-2026-05-28]]
- [[../culture/principles]]
- [[../culture/not-doing]]
- [[../culture/design-crit]]
- [[../culture/release-quality]]
- [[../culture/four-freedoms]]
- [[../decisions/adr-0007-design-crit-ritual]]
- [[../decisions/adr-0008-four-freedoms-mission]]
- [[../decisions/adr-0011-nuruh-ip-doctrine]]
- [[../decisions/adr-0012-monday-release-nuru-production-system]]
- [[../decisions/adr-0013-relay-feed-removal]]
- [[../decisions/adr-0014-local-first-product-metrics]]
- [[../decisions/adr-0015-home-tab-renewal]]
- [[../decisions/adr-0016-news-curation-model]]
- [[../decisions/adr-0017-nip-5a-mini-apps]]
- [[../decisions/adr-0018-rokunana-root-tab-removal]]
- `AGENTS.md` (LLM Wiki rules)

## Related pages

- [[themaday-2026-05-31-week-review]]
- [[june-2026-roadmap]]
- [[../culture/release-quality]]
- [[../culture/design-crit]]
