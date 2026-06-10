# NIP-32: Labels

## Summary

NIP-32 labels are represented by kind 1985 label events in the protocol. null--nostr also reads NIP-32-style label tags on NIP-23 article events for the News tab category model.

The current News implementation does not ship a trusted labeler list and does not rank articles by labels. Instead, it uses self-label tags with the null.news.category namespace for category filtering, with t tags as fallback.

## Current behavior

- Android defines NostrKind.LABEL = 1985 and has Birdwatch/context label helper paths.
- iOS defines NostrKind.label = 1985 and has Birdwatch/context label helper paths.
- Web lib/nostr.js has Birdwatch label helpers.
- News category filtering reads article tags using null.news.category; external labeler trust/ranking is future work.

## Label namespaces in null--nostr

- birdwatch: context/correction labels.
- social.birdwatch: compatibility context labels.
- null.news.category: top / domestic / entertainment / sports / economy / tech / nostr for News category self-labels.

## Platform notes

### Android

- NostrRepositoryNews.kt extracts l tags whose third element is null.news.category.
- NewsCategory defines the supported category tabs.

### iOS

- MainTabView.swift includes the News category extraction implementation.
- AppPreferences.newsSources stores source pubkeys, not labeler trust settings.

### Web

- components/NewsTab.js extracts category self-labels and t fallback tags.
- Existing Birdwatch helpers in lib/nostr.js remain separate.

## Source references

- components/NewsTab.js
- android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryNews.kt
- android/app/src/main/kotlin/io/nurunuru/app/data/models/NewsModels.kt
- ios/NuruNuru/Views/Screens/MainTabView.swift
- android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt
- ios/NuruNuru/Models/NostrKind.swift
- lib/nostr.js

## Related pages

- [[README]]
- [[nip-23]]
- [[../features/news]]

## Open questions

- Should future external labeler support use kind 1985 address labels after users explicitly add trusted labeler pubkeys?
- Should category label values be standardized with other Nostr clients?
