# NIP-23: Long-form Content

## Summary

NIP-23 long-form articles use kind 30023. null--nostr fetches and renders long-form posts in timeline contexts and now uses published long-form articles as the News tab content source.

## Current behavior

- `NostrKind.longForm` / `LONG_FORM` is `30023`.
- Android fast timeline paths include long-form events with text notes, short video, and reposts.
- iOS has a dedicated `LongFormPostItem` renderer.
- News fetches kind 30023 only; kind 30024 drafts are intentionally hidden from News.
- Long-form support is read/render oriented in the documented code paths; publishing details should be checked before claiming composer support.

## Platform notes

### Android

- `NostrRepositoryTimeline.kt` fetches `LONG_FORM` in fast/global/following timeline paths.
- Timeline enrichment treats long-form posts as `ScoredPost` events.

### iOS

- `NostrKind.longForm = 30023` and `draftLongForm = 30024` are defined.
- `LongFormPostItem.swift` renders long-form content.

### Web

- Web support should be checked per UI component before claiming parity.

## Source references

- `android/app/src/main/kotlin/io/nurunuru/app/data/models/NostrModels.kt`
  - `NostrKind.LONG_FORM`
- `android/app/src/main/kotlin/io/nurunuru/app/data/NostrRepositoryTimeline.kt`
  - timeline filters including long-form kind
- `ios/NuruNuru/Models/NostrKind.swift`
  - `longForm`, `draftLongForm`
- `ios/NuruNuru/Views/Components/LongFormPostItem.swift`

## Related pages

- [[features/timeline]]
- [[ui/post-row]]
- [[nips/README]]

## Open questions

- Confirm whether any platform has full long-form publishing UI before documenting publish support.
