# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] - 2025-12-22

### Changed
- Replaced nostr-login library with custom login modal
- New login modal with clear method selection:
  - NIP-07 Browser Extensions (Alby, nos2x)
  - Nostr Connect / NIP-46
  - Read-only mode (npub input)
  - Local key (nsec input)
- Improved login method display in settings (shows specific method)

### Removed
- nostr-login dependency (66 packages removed)
- Simplified codebase without external login library

### Fixed
- Login screen now reliably shows login methods
- No more automatic redirects to connect screen

## [1.0.2] - 2025-12-22

### Changed
- Simplified login system: Passkey (primary) + nostr-login (secondary)
- Removed direct NIP-07 browser extension login (now handled via nostr-login)
- Removed Amber/NIP-55 Android-specific login
- nostr-login now handles all alternative login methods:
  - Nostr Connect (NIP-46 remote signing)
  - Browser extensions (Alby, nos2x, Nostash)
  - Local nsec key input
  - Read-only mode (npub)

### Removed
- Android-specific login detection
- Direct NIP-07 extension login button

### Fixed
- Mobile browser incorrectly showing Android login options

## [1.0.1] - 2025-12-21

### Added
- NIP-55 Amber signer support for Android
- Android detection and optimized login flow
- nostr-login initialization fix (now loads on demand)

### Changed
- Login screen now shows Amber as primary option on Android
- Passkey login skipped on Android (requires Digital Asset Links)
- Login method display updated for Amber/nostr-login

### Fixed
- nostr-login button not responding (initialization timing issue)
- signEvent using window.nostr directly when Amber provides it

## [1.0.0] - 2025-12-21

### Added
- Initial release
- Timeline with relay/follow mode switching
- Fixed header during scroll
- Encrypted DM support (NIP-17/44/59)
- Like, repost, and Zap functionality
- NIP-05 verification badge
- Profile badges display (NIP-58)
- Custom emoji support (NIP-30)
- Follow list management with unfollow
- Mute list support (NIP-51)
- NIP-50 search capability
- Passkey login (Nosskey)
- NIP-46 remote signing (nostr-login)
- PWA support

### Technical
- Request throttling to prevent "too many concurrent REQs"
- Multi-relay badge fetching
- Profile and follow list caching
- Batch profile fetching
