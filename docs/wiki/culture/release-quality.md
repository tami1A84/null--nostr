# 月曜リリース列車と Nuru Production System

## Summary

ぬるぬるは、週刊少年ジャンプのように月曜日を定期リリースの文化的リズムとして固定し、トヨタ生産方式 (TPS) から着想した Nuru Production System (NPS) で品質と安定性を守る。

目的は「速く出す」ことではない。Nostr クライアントとして最高品質であること、すなわち署名・鍵・リレー・日本語 UI・Android/iOS/Web parity・配布品質のすべてで、ユーザーの日常を壊さないことを制度化する。

この方針は [[../decisions/adr-0012-monday-release-nuru-production-system|ADR-0012]] で Accepted とする。ただし、CI / scheduler / store automation による完全自動強制は未実装であり、本ページは 2026-05-29 時点では運用規約である。

## Current behavior

- 定期リリースは原則 月曜日 (JST) に集約する。
- 金曜夜・週末の通常リリースは避ける。週末に障害が出たとき、初動対応できず品質を落とすため。
- 月曜に品質ゲートを通らなければ、リリースを強行せず列車をスキップする。
- 実機テストは iOS / Android / Web の dedicated slot に分け、事前定義した test case を一つずつ反復実行する。
- 例外は P0/P1 hotfix (秘密鍵漏洩、署名不能、起動不能、重大クラッシュ、データ損失、Nostr プロトコル互換性の重大破壊) のみ。

## 月曜リリース列車

### 週間リズム

| 曜日 | 目的 | 標準作業 |
|---|---|---|
| 月曜 | Release / Publish | 最終ゲート、release notes、Zapstore / GitHub / Google Play / TestFlight / Web の公開判断 |
| 火曜 | Observe / Triage | Nostr feedback、crash / ANR、store signal、初回投稿・画像投稿・Talk 成功率を確認 |
| 水曜 | Build / Kaizen | 小改善、バグ修正、設計負債の返済、Design Crit への議題形成 |
| 木曜 | Integrate | platform parity 確認、NIP / FFI / design tokens / wiki 整合、release candidate 候補を絞る |
| 金曜 | Freeze / Candidate | 通常機能の取り込み停止、RC 作成、週末 dogfood の観察点を明記 |
| 土日 | Dogfood only | 新機能 merge は原則しない。観察・軽微修正・security hotfix 準備のみ |

## プラットフォーム別 実機テスト時間割

月曜リリース列車では、Web / Android / iOS を「ついでに触る」のではなく、プラットフォームごとに実機テストの時間帯を分ける。同じ人が複数 platform を見る場合でも、時間帯を分けて認知負荷を下げ、観察品質を上げる。

標準の release candidate test day は以下を初期値とする (JST)。

| 時間帯 | Platform | 目的 | 備考 |
|---|---|---|---|
| 09:00-11:30 | iOS 実機 | iPhone / iOS 固有の Keychain、Passkey/Nosskey、NIP-46 廃止後の再ログイン移行、SwiftUI sheet、safe area、push / share 動線を確認 | TestFlight / local build のどちらで確認したかを記録 |
| 11:30-12:00 | iOS Andon 整理 | STOP / HOLD / minor defect を分類 | 午前中に Android を混ぜない |
| 13:00-15:30 | Android 実機 | Android 固有の passkey / nsec fallback、外部署名、CameraX、Media3、Compose、Play build 動線を確認 | debug / release / internal test のどれで確認したかを記録 |
| 15:30-16:00 | Android Andon 整理 | STOP / HOLD / minor defect を分類 | iOS の未整理 defect と混ぜない |
| 16:00-17:00 | Web / cross-platform parity | Web build/runtime、share URL、relay behavior、iOS/Android 差分の最終確認 | platform parity debt を明示 |
| 17:00-18:00 | Release judgment | GO / HOLD / STOP / HOTFIX 判定 | Release conductor が最終宣言 |

曜日・人数・store review の都合で時間帯は調整してよい。ただし、platform ごとの dedicated slot を消してはならない。時間が足りない場合は release scope を削るか HOLD にする。

### 実機テストの原則

- 「日常的に使ってみた」だけでは release test と呼ばない。
- 各 platform slot は、事前に作った test case を上から順に実行し、結果を PASS / FAIL / BLOCKED / NOT RUN で残す。
- 同じ重要項目は、最低2周する。1周目は機能確認、2周目は再現性と回帰確認。
- FAIL はその場で直せても記録を消さない。Andon として残し、原因・修正・再確認を紐づける。
- 実機 test は「誰かがなんとなく触る」ではなく、release supply を安定させる標準作業である。

### リリースしない勇気

月曜日固定は「毎週必ず出す」ではない。ユーザーの日常を壊す疑いがあるなら、ジャンプが休載号を明示するように、ぬるぬるも出さない判断を明示する。

- GO: 全ゲート通過。月曜に公開。
- HOLD: P0/P1 ではないが体験品質に疑義。次の月曜列車へ回す。
- STOP: P0/P1 または鍵・署名・データ損失リスク。release line を止める。
- HOTFIX: 例外条件を満たす障害のみ、月曜外でも最小差分で公開。

## Nuru Production System (NPS)

NPS は TPS をソフトウェア / Nostr クライアント向けに翻訳した品質体系である。製造業の用語を飾りとして借りるのではなく、「異常を見える化し、止め、原因を潰す」ために使う。

| TPS 概念 | Nuru 解釈 | 具体運用 |
|---|---|---|
| Jidoka / 自働化 | 異常があれば止める | P0/P1、秘密鍵、署名、クラッシュ、Talk 破壊は release STOP。自動化が通っても人間が止められる |
| Andon | 異常の可視化 | release issue / PR / feedback triage に STOP/HOLD/HOTFIX ラベルを付け、理由を1行で残す |
| Just-in-Time | 必要な価値だけを小さく出す | 月曜列車に載せる差分を絞る。大機能は feature flag / platform parity 計画なしに混ぜない |
| Heijunka / 平準化 | 無理な山を作らない | 金曜夜の駆け込み merge、週末 release、複数巨大機能の同時公開を避ける |
| Standardized Work | 標準作業 | release checklist、build/test commands、wiki/log 更新、release notes、platform 別実機 test slot を固定順序にする |
| Genchi Genbutsu | 現地現物 | 実機、実 relay、実 store、実 Nostr 投稿で確認する。モックだけで「動いた」としない |
| Kaizen | 継続改善 | escaped defect は 5 Whys で原因を残し、次の月曜列車までに1つ標準作業を改善する |

## 標準実機テスト項目

Release candidate ごとに、少なくとも以下の test cases を platform 別に実行する。該当しない項目は N/A ではなく NOT RUN とし、理由を書く。

### iOS 実機

| ID | Test case | 判定基準 |
|---|---|---|
| IOS-01 | 初回起動、ログイン、Keychain 保存 | private key が logs / UserDefaults に出ず、再起動後も復帰できる |
| IOS-02 | passkey / nsec fallback | 成功・失敗・キャンセルの表示が日本語コピー規約に沿う |
| IOS-03 | 投稿作成 140文字制限 | 制限超過が送信不能、境界値 140 が送信可能 |
| IOS-04 | 画像投稿 / 画像閲覧 | upload、grid、fullscreen、pinch、dismiss が破綻しない |
| IOS-05 | タイムライン読み込み / load more | stable identity、no entrance animation、重複や突然の巻き戻りなし |
| IOS-06 | Talk 送受信 / MLS catch-up | optimistic bubble、送信、受信、catch-up、retry が仕様通り |
| IOS-07 | 通知 / sheets / share | fullScreenCover / sheet / share URL が iOS guardrails に沿う |
| IOS-08 | NIP-46 廃止 / signer migration | 旧 NIP-46 セッションが安全に再ログインへ誘導され、secret を露出しない |

### Android 実機

| ID | Test case | 判定基準 |
|---|---|---|
| AND-01 | 初回起動、ログイン、Secure storage | private key が logs に出ず、再起動後も復帰できる |
| AND-02 | passkey / nsec / Amber fallback | 成功・失敗・キャンセルが破綻せず、外部署名 path が動く |
| AND-03 | 投稿作成 140文字制限 | PostModal.kt の境界値が厳密に動き、NIP-70 tag も保持 |
| AND-04 | 画像投稿 / 画像閲覧 | parallel upload、grid、fullscreen pager、pinch/pager conflict なし |
| AND-05 | タイムライン読み込み / load more | no entrance animation、repost / like toggle undo、重複なし |
| AND-06 | Talk 送受信 / MLS catch-up | optimistic bubble、送信 spinner、catch-up、retry が仕様通り |
| AND-07 | 通知 / modals / share | full-screen modal siblings、通知 polling、share URL が破綻しない |
| AND-08 | CameraX / Media3 / short video | camera permission、video playback、tap-to-unmute が動く |

### Web / cross-platform

| ID | Test case | 判定基準 |
|---|---|---|
| WEB-01 | Web build/runtime | npm run build 相当で production runtime error なし |
| WEB-02 | secure-key-store | private key を window に露出しない |
| WEB-03 | relay connection | max connection / rate limit / cooldown が破綻しない |
| WEB-04 | share URL / OGP | /p/<npub> と /e/<event-id> が canonical URL として動く |
| XPF-01 | Android/iOS parity | tab、PostActions、Talk chrome、copy、140文字制限に差分なし |
| XPF-02 | NIP behavior | NIP-25 / 46 / 57 / 65 / 70 / 98 等の変更が docs と一致 |

### 反復テストの型

1. Round 1: 手順通りに通す — test case を順番に実行し、FAIL を Andon 化する。
2. Fix / isolate — 直す、scope から外す、または HOLD にする。
3. Round 2: 同じ手順を再実行 — 同じ端末で再現しないことを確認する。
4. Cross-check — 影響 platform だけでなく parity 相手も確認する。
5. Record — PASS / FAIL / BLOCKED / NOT RUN、端末、OS version、build type、relay 状態を残す。

## 品質ゲート

### 1. Build / Test gate

Release candidate は、対象範囲に応じて以下を満たす。

- Web: npm run test, npm run build, npm run tokens:check, npm run wiki:lint
- Android: cd android && ./gradlew assembleDebug、必要に応じて assembleRelease
- iOS: cd ios && xcodebuild -scheme NuruNuru -destination 'platform=iOS Simulator,name=iPhone 17' -skipPackagePluginValidation build、必要に応じて test
- Rust Engine: cargo check --workspace / relevant cargo test、FFI API 変更時は Kotlin / Swift bindings 再生成

### 2. Nostr protocol gate

Nostr 最高品質の最低条件:

- 秘密鍵を window / UserDefaults / logs に出さない。
- 署名対象 event が NIP と実装意図に一致する。
- relay publish / subscribe / reconnect が単一 relay 依存にならない。
- NIP 対応を変更したら [[../nips/README]] と該当 NIP ページを更新する。
- FFI の tags / event kind / relay-targeted publish が Web / Android / iOS で矛盾しない。

### 3. UX / Japanese quality gate

- [[principles|五箇条]] に反する UI は出さない。
- 日本語コピーは [[copy-style]] に従う。
- Android / iOS の見た目・挙動は [[../ui/android-ios-sync]] を基準にする。
- 140文字制限、LINE Seed JP、PostActions、Talk chrome など既存の採用仕様を崩さない。
- 新機能が1プラットフォームだけ先行する場合は、理由・期限・同期計画を ADR / wiki に残す。

### 4. Stability gate

月曜公開前に最低限見る体験:

- 初回起動 / ログイン / passkey or nsec fallback
- 初回投稿 / 画像投稿 / 投稿共有
- タイムライン読み込み / リレー再接続
- Talk 送受信 / MLS catch-up / retry path
- 通知表示 / 不正 kind の非表示
- Android crash / ANR、iOS crash、Web build/runtime errors

### 5. Release communication gate

- CHANGELOG.md または release notes に、ユーザー影響を短く書く。
- security / data migration / past data loss がある場合は、JP/EN の短文と support FAQ を用意する。
- Wiki に意味のある変更がある場合、[[../log]] に追記する。

## 最高品質 Nostr クライアントの定義

ぬるぬるにおける「Nostr 最高品質」は star 数や機能数では測らない。以下7軸で測る。

| 軸 | 品質の意味 |
|---|---|
| Security | 秘密鍵・署名・暗号化ストレージで妥協しない |
| Protocol correctness | NIP を正しく実装し、曖昧な独自挙動を増やさない |
| Relay resilience | relay 障害・遅延・地域差に耐える |
| Daily UX | Nostr 概念を見せず、LINE 的な日常所作で使える |
| Japanese polish | 日本語コピー・組版・絵文字・間合いが翻訳アプリに見えない |
| Platform parity | Web / Android / iOS で体験と制約が割れない |
| Operability | feedback、crash、release、rollback の判断が人間に見える |

## Metrics

数値は品質を守るために使い、文化判断を曲げるために使わない。

- Release train hit rate: 月曜列車の GO / HOLD / STOP / HOTFIX 件数
- Escaped P0/P1 defects: release 後に発覚した重大障害
- Crash-free sessions / ANR rate: Android / iOS / Web
- First post success rate: 初回投稿まで到達できた割合
- Publish success by relay: relay ごとの publish / timeout / error
- Talk send/receive success: MLS 送受信・catch-up・retry の成功率
- Platform parity debt: 1プラットフォームだけ未同期の仕様数
- Real-device test completion: platform 別 test case の PASS / FAIL / BLOCKED / NOT RUN 比率
- Mean time to Andon: 異常を見つけて STOP/HOLD を宣言するまでの時間

## Roles

小規模チームでも、release week では役割を分けて考える。

- Release conductor: 月曜列車の GO/HOLD/STOP を宣言する。
- Quality owner: gate 結果、escaped defect、5 Whys を管理する。
- Platform test owner: iOS / Android / Web の dedicated slot と test case 記録を管理する。
- Protocol owner: NIP / relay / signing / FFI の整合を見る。
- UX/copy owner: 五箇条、日本語コピー、Android/iOS parity を見る。

同一人物が兼務してよい。ただし、役割を曖昧にして release 判断を空中戦にしない。

## Non-goals

- 月曜固定を理由に、不完全なものを毎週出すこと。
- 「日常的に使っているから大丈夫」として test case 記録を省くこと。
- TPS 用語を儀式化し、現場の異常を隠すこと。
- 自動化だけで release 判断を完結させること。
- star 数、DAU、短期売上のために鍵・署名・日本語品質を下げること。

## Open Questions

- 月曜 release window の厳密な時刻 (JST 午前 / 午後 / 夜) をいつ固定するか。
- Release conductor / Platform test owner の輪番表をどこで管理するか。
- GO/HOLD/STOP/HOTFIX ラベルを GitHub labels として作るか。
- Store review の遅延がある Google Play / App Store と「月曜公開」をどう同期するか。
- Web / Android / iOS の release versioning を毎週同期するか、platform ごとに月曜列車へ任意乗車とするか。
- 実機テスト記録を GitHub Issue template、Notion、spreadsheet、docs/wiki のどこに置くか。

## Related pages

- [[principles]]
- [[design-crit]]
- [[not-doing]]
- [[copy-style]]
- [[../operations/feedback-loop]]
- [[../operations/goose-recipes]]
- [[../decisions/adr-0012-monday-release-nuru-production-system]]
- [[../ui/android-ios-sync]]
- [[../platforms/parity-matrix]]

## Source references

- User directive (2026-05-29): Thema DAY 企業文化 / カルチャー構築、週刊少年ジャンプ型の月曜リリース、TPS 導入、Nostr 最高品質。
- User directive (2026-05-29): platform ごとの実機テスト時間割、明示 test case に基づく反復確認、トヨタ級の安定供給。
- AGENTS.md — build / release commands and platform guardrails.
- docs/wiki/culture/principles.md — Charter v0.1.
- docs/wiki/culture/design-crit.md — Weekly Nuru Design Crit.
- docs/wiki/operations/feedback-loop.md — feedback automation boundaries.
- docs/wiki/operations/goose-recipes.md — release recipe context.
- docs/sync/CHECKLIST.md — existing release/checklist precedent.
