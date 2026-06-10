# やらないことリスト (NOT-doing list)

## Summary

[[principles|Charter v0.1]] の裏面。**意図的にやらないこと**を明文化する。新機能 PR / 新 NIP 採用 / 新プラットフォーム判断は、ここに抵触しないかを必ず確認する。抵触する場合は ADR で正当化する。

## 体験 (Daily UX)

- Nostr 用語 (relay, pubkey, NIP, kind, event, signature) を初心者向け画面に浴びせない。
- 機能ボタンで画面を埋めない。1画面に主アクションは原則1〜2個。
- 競合 SNS の中毒的エンゲージメント設計 (無限スクロール演出、赤バッジ濫用、即時通知ハック) を真似ない。
- 「誰でも発言できる」を「誰でも全員のタイムラインに表示される」と解釈しない。発言権と表示 / 到達権は分ける ([[../decisions/adr-0021-open-speech-scoped-reach|ADR-0021]])。
- タイムラインに entrance animation を追加しない (既存規約)。
- 投稿の「いいね」を heart アイコンにしない (thumbs-up / 既存規約)。
- PostActions に reply ボタンを置かない ([[../decisions/adr-0005-postactions-no-reply-button|ADR-0005]] 既決)。

## 日本語 (JP-native)

- 日本語コピーを英語 UI の直訳として扱わない (詳細: [[copy-style]])。
- 「もっと見る / 閉じる」のような既存採用コピーを別表現に差し替えない。
- LINE Seed JP 以外をボディテキストに使わない (Android / iOS とも)。

## 一貫性 (Cross-platform consistency)

- Web / Android / iOS で別アプリのような体験を許さない。
- [[../ui/design-tokens|design-tokens]] 外の色・余白・フォントサイズを置かない。
- 1プラットフォームだけ先行する機能を、ADR なしでマージしない。
- iOS / Android を閲覧専用・投稿不可にしない。ネイティブ投稿制限は採用せず、ストア / 安全リスクはリレーフィード削除、信頼グラフ、ミュート / ブロック / report、コピー審査配慮で扱う ([[../decisions/adr-0021-open-speech-scoped-reach|ADR-0021]])。

## 鍵とセキュリティ (Keys & Security)

- 秘密鍵を `window.*` / UserDefaults / 平文ファイル / ログに出さない。
- 「技術的に正しいが怖い UI」を放置しない (例: 鍵バックアップ確認なしのエクスポート)。
- 外部署名フローを iOS で NIP-55 (Amber) として実装しない。iOS NIP-46 signer も ADR-0023 で廃止し、internal nsec/Keychain と Passkey/Nosskey に整理する。

## 設計判断 (Design discipline)

- 善意の凡庸な PR をマージしない。「動くから」だけでは入れない。
- Design Crit を通っていない新規 UI 画面を main に入れない ([[../decisions/adr-0007-design-crit-ritual|ADR-0007]])。
- 「他の Nostr クライアントもそうしているから」を理由に採用しない。

## NIP / プロトコル (NIP & protocols)

- NIP が出たという理由だけで実装しない。日常体験への寄与を必ず説明する。
- 対応 NIP 数を競わない。広告に NIP 数を書かない。
- 新 NIP ドラフトに反応する形でロードマップを組まない (能動的な10年計画に従う)。
- ZK / AI / MLS / C2PA / Marmot などの単語を表面に出して自慢しない。

## 経済の自由 (Economy)  — 将来規約 (v1.0 で確定)

[[four-freedoms]] の経済軸に関連する将来規約。実装着手と同時に格上げする。

- 決済手段を VISA / Mastercard / 国内銀行に依存させない。
- Lightning / Zap を「投げ銭」「おひねり」「ご祝儀」など日本語日常語に翻訳して表示する。表層で "Lightning" "Bitcoin" "Invoice" "Satoshi" を使わない。
- 法定通貨換算を「義務的に」見せない。ユーザーの選択で見せる。
- 決済 UI で恐怖を煽らない (確認ダイアログ濫用、警告色濫用をしない)。

## 配布の自由 (Distribution) — 将来規約 (v1.0 で確定)

- ミニアプリを「dApp」「Web3 アプリ」と呼ばない。「道具」「あそび」など日常語で提示する。
- 外部ストア (App Store / Play / GitHub Releases) の審査を前提とした機能設計をしない。
- 同時に、ストア審査と意図的に衝突する命名・UI を表面に置かない (本体配布の継続性を守る)。
- 任意のコード実行を許可しない。署名・サンドボックス・パーミッション境界を必ず通す。

## 短期 KPI (Short-term metrics)

- DAU / WAU / 滞在時間といった指標で文化判断を覆さない。
- 数字を理由に第一条〜第五条を曲げない。

## Source references

- `AGENTS.md`
- [[principles]]
- [[../decisions/adr-0003-ios-external-signing-uses-nip46]]
- [[../decisions/adr-0005-postactions-no-reply-button]]
- [[../decisions/adr-0007-design-crit-ritual]]
- [[../decisions/adr-0008-four-freedoms-mission]]
- [[../decisions/adr-0021-open-speech-scoped-reach]]
