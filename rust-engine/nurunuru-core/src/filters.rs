//! Filter factory functions — direct port of `lib/filters.js`.
//!
//! Each function returns a `nostr::Filter` ready for subscription or query.

use nostr::prelude::*;

/// Create timeline filters for notes (kind 1) and reposts (kind 6).
pub fn timeline_filters(
    authors: Option<&[PublicKey]>,
    since: Option<Timestamp>,
    until: Option<Timestamp>,
    notes_limit: usize,
    reposts_limit: usize,
) -> Vec<Filter> {
    let mut notes_filter = Filter::new().kind(Kind::TextNote).limit(notes_limit);
    let mut reposts_filter = Filter::new().kind(Kind::Repost).limit(reposts_limit);

    if let Some(authors) = authors {
        notes_filter = notes_filter.authors(authors.iter().copied());
        reposts_filter = reposts_filter.authors(authors.iter().copied());
    }
    if let Some(since) = since {
        notes_filter = notes_filter.since(since);
        reposts_filter = reposts_filter.since(since);
    }
    if let Some(until) = until {
        notes_filter = notes_filter.until(until);
        reposts_filter = reposts_filter.until(until);
    }

    vec![notes_filter, reposts_filter]
}

/// Fetch user metadata (kind 0).
pub fn profile_filter(pubkeys: &[PublicKey]) -> Filter {
    Filter::new()
        .kind(Kind::Metadata)
        .authors(pubkeys.iter().copied())
}

/// Fetch follow list (kind 3, NIP-02).
pub fn follow_list_filter(pubkey: PublicKey) -> Filter {
    Filter::new()
        .kind(Kind::ContactList)
        .author(pubkey)
        .limit(1)
}

/// Fetch mute list (kind 10000, NIP-51).
pub fn mute_list_filter(pubkey: PublicKey) -> Filter {
    Filter::new()
        .kind(Kind::MuteList)
        .author(pubkey)
        .limit(1)
}

/// Fetch reactions (kind 7, NIP-25) for given event IDs.
pub fn reaction_filter(event_ids: &[EventId], limit: usize) -> Filter {
    Filter::new()
        .kind(Kind::Reaction)
        .events(event_ids.iter().copied())
        .limit(limit)
}

/// Fetch replies (kind 1 referencing event IDs).
pub fn reply_filter(event_ids: &[EventId], limit: usize) -> Filter {
    Filter::new()
        .kind(Kind::TextNote)
        .events(event_ids.iter().copied())
        .limit(limit)
}

/// Fetch DMs (NIP-17, kind 1059 gift-wrapped events).
pub fn dm_filter(pubkey: PublicKey, since: Option<Timestamp>, limit: usize) -> Filter {
    let mut f = Filter::new()
        .kind(Kind::GiftWrap)
        .pubkey(pubkey)
        .limit(limit);
    if let Some(since) = since {
        f = f.since(since);
    }
    f
}

/// Fetch zap receipts (kind 9735, NIP-57).
pub fn zap_filter(event_ids: &[EventId], limit: usize) -> Filter {
    Filter::new()
        .kind(Kind::ZapReceipt)
        .events(event_ids.iter().copied())
        .limit(limit)
}

/// Full-text search (NIP-50).
pub fn search_filter(query: &str, limit: usize) -> Filter {
    Filter::new()
        .kind(Kind::TextNote)
        .search(query)
        .limit(limit)
}

/// Custom emoji sets (kind 10030, NIP-51).
pub fn emoji_filter(pubkey: PublicKey) -> Filter {
    Filter::new()
        .kind(Kind::Custom(10030))
        .author(pubkey)
        .limit(1)
}

/// Profile badges (kind 30008, NIP-58).
pub fn badge_filter(pubkey: PublicKey) -> Filter {
    Filter::new()
        .kind(Kind::Custom(30008))
        .author(pubkey)
        .limit(1)
}

/// Relay list (kind 10002, NIP-65).
pub fn relay_list_filter(pubkey: PublicKey) -> Filter {
    Filter::new()
        .kind(Kind::RelayList)
        .author(pubkey)
        .limit(1)
}

/// DM relay list (kind 10050, NIP-17).
pub fn dm_relay_list_filter(pubkey: PublicKey) -> Filter {
    Filter::new()
        .kind(Kind::Custom(10050))
        .author(pubkey)
        .limit(1)
}

/// Calculate "since" timestamp for N hours ago.
pub fn since_hours_ago(hours: u64) -> Timestamp {
    Timestamp::now() - hours * 3600
}

/// Calculate "since" timestamp for N days ago.
pub fn since_days_ago(days: u64) -> Timestamp {
    Timestamp::now() - days * 86400
}
