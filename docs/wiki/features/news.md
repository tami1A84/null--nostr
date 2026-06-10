# News Tab — NIP-23 Articles and null.news.category

## Summary

News is a LINE NEWS-style reading surface for NIP-23 long-form articles, kind 30023, categorized with NIP-32-style self-label tags using the null.news.category namespace, with t tags as fallback. As of ADR-0022, News is **not** part of the iOS zero-base root navigation; treat any News root-tab implementation as historical/pending parity cleanup unless a future ADR restores it.

Current implementation is intentionally simple: no default trusted labeler list, no ranking tab, no ranking score, and no kind 30024 drafts in News.

## Current behavior

- Historical implementation work added News to main navigation on some platforms, but the iOS zero-base root target is now ホーム / トーク / タイムライン / ミニアプリ (ADR-0022).
- News fetches only NIP-23 published long-form articles, kind 30023.
- NIP-23 drafts, kind 30024, are not fetched or displayed.
- Articles are deduplicated by address coordinate 30023:<pubkey>:<d>; latest created_at wins.
- Ordering is newest-first by published_at when present, otherwise created_at.
- Category tabs are: トップ / 国内 / エンタメ / スポーツ / 経済 / テック / Nostr.
- On Android, category content is horizontally swipeable; tapping a category chip and swiping the content pager stay synchronized.
- Category detection reads article self-label tags L=null.news.category and l=<category>,null.news.category, then falls back to t tags.
- News source settings are included. Users can add/remove source pubkeys via npub, hex pubkey, or NIP-05.
- If no sources are configured, News shows latest kind 30023 articles from connected relays.
- If sources are configured, News adds an authors filter and shows only those sources.

## Data model

NewsArticle contains id, address, pubkey, dTag, title, summary, imageUrl, content, publishedAt, createdAt, sourceName, sourcePicture, categories, and rawEvent.

## Category labels

The accepted namespace is null.news.category. Values: top, domestic, entertainment, sports, economy, tech, nostr.

## News source settings

News sources are public pubkeys stored as non-sensitive user preferences. Web uses localStorage key nurunuru_news_sources, Android uses AppPreferences.newsSources, and iOS uses AppPreferences.newsSources. Accepted input formats are npub1, 64-character hex pubkey, and NIP-05.

## Platform notes

### Web

- components/NewsTab.js implements News UI, local search, source settings, kind 30023 fetch, dedupe, category filtering, and article detail overlay.
- components/BottomNav.js includes the News tab and shortens Mini Apps copy to ミニ.
- app/page.js includes News in the desktop sidebar and active-tab content area.

### Android

- NewsViewModel loads source settings and fetches articles through NostrRepository.fetchNewsArticles().
- NostrRepositoryNews.kt fetches NostrKind.LONG_FORM only and never includes DRAFT_LONG_FORM.
- NewsScreen.kt provides the LINE NEWS-style header, search, category tabs, cards, detail dialog, and source settings dialog.
- MainScreen.kt adds BottomTab.NEWS between Timeline and Mini, and changes Mini label to ミニ.

### iOS

- MainTabView.swift currently contains News SwiftUI implementation code, but ADR-0022 says News must not be an iOS root tab.
- AppPreferences.newsSources stores source pubkeys.

## Source references

- components/NewsTab.js
- components/BottomNav.js
- app/page.js
- android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryNews.kt
- android/app/src/main/kotlin/io/nurunuru/app/data/models/NewsModels.kt
- android/app/src/main/kotlin/io/nurunuru/app/viewmodel/NewsViewModel.kt
- android/app/src/main/kotlin/io/nurunuru/app/ui/screens/NewsScreen.kt
- android/app/src/main/kotlin/io/nurunuru/app/ui/screens/MainScreen.kt
- android/app/src/main/kotlin/io/nurunuru/app/data/prefs/AppPreferences.kt
- ios/NuruNuru/Views/Screens/MainTabView.swift
- ios/NuruNuru/Data/AppPreferences.swift

## Related pages

- [[../nips/nip-23]]
- [[../nips/nip-32]]
- [[../strategy/june-2026-roadmap]]
- [[../decisions/adr-0022-ios-four-tab-navigation]]
- [[../culture/copy-style]]

## Open questions

- Should future versions support external kind 1985 labelers after users explicitly add trusted labeler sources?
- Should article body rendering support Markdown beyond plain text while preserving sanitization/security requirements?
