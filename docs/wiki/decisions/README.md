# Decisions

## Summary

This directory stores lightweight Architecture Decision Records (ADRs). Use ADRs for decisions that affect architecture, platform parity, security, protocols, or long-term maintenance.

## Decision index

| ADR | Decision |
|---|---|
| [[adr-0001-ios-observation]] | iOS ViewModels use iOS 17+ Observation. |
| [[adr-0002-native-talk-uses-marmot-mls]] | Native Talk is Marmot MLS-oriented; NIP-17 is legacy/compatibility. |
| [[adr-0003-ios-external-signing-uses-nip46]] | Superseded historical decision: iOS external signing used NIP-46, not NIP-55. |
| [[adr-0004-design-tokens-are-source-of-truth]] | Design tokens are source of truth for generated constants. |
| [[adr-0005-postactions-no-reply-button]] | PostActions has no reply button and may include optional bookmark. |
| [[adr-0006-web-rust-bridge-is-stub]] | Web Rust bridge is currently stubbed; Web Nostr operations use JS modules. |
| [[adr-0007-design-crit-ritual]] | Weekly Nuru Design Crit を制度化する判断。 |
| [[adr-0008-four-freedoms-mission]] | 4軸自由ドクトリンを長期ミッションとして起票する判断。 |
| [[adr-0009-mls-db-encryption]] | MLS storage DB を SQLCipher で暗号化する判断 (issue #181)。 |
| [[adr-0010-passkey-prf-direct-method]] | Passkey PRF を直接署名手段として採用する判断。 |
| [[adr-0011-nuruh-ip-doctrine]] | ぬるる IP 憲章 (四箇条) を起票する判断。 |
| [[adr-0012-monday-release-nuru-production-system]] | 月曜リリース列車と Nuru Production System を採用する判断。 |
| [[adr-0013-relay-feed-removal]] | リレーフィードを主要UIから削除し、設定/投稿/検索用途は維持する判断。 |
| [[adr-0014-local-first-product-metrics]] | 6月 Phase 1 は local-first metrics を見送り、manual QA を一次情報にする判断。 |
| [[adr-0015-home-tab-renewal]] | ホームを中心タブへ刷新し、フォローフィード/設定を集約する判断。 |
| [[adr-0016-news-curation-model]] | News は NIP-23 + NIP-32 を 2-hop 信頼グラフで発見・表示する判断。 |
| [[adr-0017-nip-5a-mini-apps]] | NIP-5A ミニアプリの manifest / permission 境界。 |
| [[adr-0018-rokunana-root-tab-removal]] | ろくなな root tab removal と code-only retention。 |
| [[adr-0019-ios-rust-ffi-write-path]] | iOS Rust FFI write-path migration starts from keygen/sign/publish contracts. |
| [[adr-0020-safe-starter-graph]] | Safe starter graph for first Home experience without reintroducing relay-wide feeds. |
| [[adr-0021-open-speech-scoped-reach]] | Open speech with scoped reach, relationship-scoped primary surfaces, and native posting parity. |
| [[adr-0022-ios-four-tab-navigation]] | iOS root navigation is ホーム / トーク / タイムライン / ミニアプリ. |
| [[adr-0023-ios-remove-nip46-signer]] | iOS NIP-46 signer is removed; use internal nsec/Keychain or Passkey/Nosskey. |
| [[adr-0024-ios-startup-relay-connection-dedupe]] | Proposed startup relay connection dedupe for iOS. |

## ADR convention

Each ADR should include:

- Status
- Context
- Decision
- Consequences
- Source references

## Related pages

- [[../index]]
- [[../architecture]]
## Source references

- `AGENTS.md`
- ADR files in `docs/wiki/decisions/`

