//! Relay URL validation and selection logic.
//!
//! Mirrors `isValidRelayUrl()` and `filterValidRelays()` from `lib/nostr.js`,
//! plus the geohash-based relay selection from `lib/geohash.js`.

use crate::config::RelayConfig;
use nostr::prelude::*;

/// Validate a relay URL.
/// Rules (from JS `isValidRelayUrl`):
/// - Must be `wss://` (secure WebSocket)
/// - Exclude `.onion` (Tor) — not accessible without Tor
/// - Exclude `localhost`/`127.0.0.1` unless in dev mode
pub fn is_valid_relay_url(url: &str, allow_localhost: bool) -> bool {
    let Ok(parsed) = url::Url::parse(url) else {
        return false;
    };

    if parsed.scheme() != "wss" {
        return false;
    }

    if let Some(host) = parsed.host_str() {
        if host.ends_with(".onion") {
            return false;
        }
        if (host == "localhost" || host == "127.0.0.1") && !allow_localhost {
            return false;
        }
    } else {
        return false;
    }

    true
}

/// Filter a list of relay URLs to only valid ones.
/// If all are invalid, returns the default relay.
pub fn filter_valid_relays(relays: &[String], config: &RelayConfig) -> Vec<String> {
    let valid: Vec<String> = relays
        .iter()
        .filter(|r| is_valid_relay_url(r, false))
        .cloned()
        .collect();

    if valid.is_empty() {
        vec![config.default_relay.clone()]
    } else {
        valid
    }
}

/// Build the full relay list: primary + fallbacks (deduped).
pub fn build_relay_list(config: &RelayConfig) -> Vec<String> {
    let mut relays = vec![config.default_relay.clone()];
    for fb in &config.fallback_relays {
        if !relays.contains(fb) {
            relays.push(fb.clone());
        }
    }
    relays
}

/// Parse a relay URL string into an `nostr::RelayUrl` (via Url).
pub fn parse_relay_url(url: &str) -> crate::Result<RelayUrl> {
    RelayUrl::parse(url).map_err(|e| crate::NuruNuruError::InvalidRelayUrl(e.to_string()))
}

/// Known Japanese relay regions with approximate geohash prefixes.
/// Used for proximity-based relay selection.
pub struct RegionalRelay {
    pub url: &'static str,
    pub geohash_prefix: &'static str,
    pub name_ja: &'static str,
}

/// Curated list of Japanese/Asian relays with location hints.
pub const REGIONAL_RELAYS: &[RegionalRelay] = &[
    RegionalRelay {
        url: "wss://yabu.me",
        geohash_prefix: "xn7",
        name_ja: "東京",
    },
    RegionalRelay {
        url: "wss://relay-jp.nostr.wirednet.jp",
        geohash_prefix: "xn7",
        name_ja: "東京",
    },
    RegionalRelay {
        url: "wss://r.kojira.io",
        geohash_prefix: "xn0",
        name_ja: "大阪",
    },
    RegionalRelay {
        url: "wss://relay.damus.io",
        geohash_prefix: "dpz",
        name_ja: "トロント",
    },
    RegionalRelay {
        url: "wss://nos.lol",
        geohash_prefix: "u33",
        name_ja: "ベルリン",
    },
    RegionalRelay {
        url: "wss://relay.snort.social",
        geohash_prefix: "gc7",
        name_ja: "ダブリン",
    },
];

/// Select relays closest to the user's geohash.
/// Returns relays sorted by geohash prefix match length (best first).
pub fn select_relays_by_proximity(user_geohash: &str) -> Vec<&'static str> {
    let mut scored: Vec<(&str, usize)> = REGIONAL_RELAYS
        .iter()
        .map(|r| {
            let common = user_geohash
                .chars()
                .zip(r.geohash_prefix.chars())
                .take_while(|(a, b)| a == b)
                .count();
            (r.url, common)
        })
        .collect();

    scored.sort_by(|a, b| b.1.cmp(&a.1));
    scored.iter().map(|(url, _)| *url).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_wss() {
        assert!(is_valid_relay_url("wss://relay.damus.io", false));
    }

    #[test]
    fn test_invalid_ws() {
        assert!(!is_valid_relay_url("ws://relay.damus.io", false));
    }

    #[test]
    fn test_invalid_onion() {
        assert!(!is_valid_relay_url("wss://relay.onion", false));
    }

    #[test]
    fn test_localhost_blocked() {
        assert!(!is_valid_relay_url("wss://localhost", false));
    }

    #[test]
    fn test_localhost_allowed() {
        assert!(is_valid_relay_url("wss://localhost", true));
    }

    #[test]
    fn test_proximity_japan() {
        let relays = select_relays_by_proximity("xn76u");
        // Japanese relays should come first
        assert!(relays[0] == "wss://yabu.me" || relays[0] == "wss://relay-jp.nostr.wirednet.jp");
    }
}
