use std::time::Duration;

/// Full configuration for the NuruNuru engine.
/// Mirrors `constants.js` values with sensible Rust defaults.
#[derive(Debug, Clone)]
pub struct NuruNuruConfig {
    pub relay: RelayConfig,
    pub cache: CacheConfig,
    pub recommendation: RecommendationConfig,
    pub db_path: String,
}

impl Default for NuruNuruConfig {
    fn default() -> Self {
        Self {
            relay: RelayConfig::default(),
            cache: CacheConfig::default(),
            recommendation: RecommendationConfig::default(),
            db_path: "./nurunuru-db".to_string(),
        }
    }
}

/// Relay connection settings (from `WS_CONFIG` in constants.js)
#[derive(Debug, Clone)]
pub struct RelayConfig {
    /// Primary relay URL
    pub default_relay: String,
    /// Fallback relays when primary fails
    pub fallback_relays: Vec<String>,
    /// NIP-50 search relay
    pub search_relay: String,
    /// Maximum concurrent requests across all relays
    pub max_concurrent_requests: usize,
    /// Request timeout
    pub request_timeout: Duration,
    /// EOSE (End of Stored Events) timeout
    pub eose_timeout: Duration,
    /// Retry configuration
    pub retry: RetryConfig,
}

impl Default for RelayConfig {
    fn default() -> Self {
        Self {
            default_relay: "wss://yabu.me".to_string(),
            fallback_relays: vec![
                "wss://relay-jp.nostr.wirednet.jp".to_string(),
                "wss://r.kojira.io".to_string(),
                "wss://relay.damus.io".to_string(),
            ],
            search_relay: "wss://search.nos.today".to_string(),
            max_concurrent_requests: 4,
            request_timeout: Duration::from_secs(15),
            eose_timeout: Duration::from_secs(15),
            retry: RetryConfig::default(),
        }
    }
}

/// Retry/backoff settings (from `WS_CONFIG.retry`)
#[derive(Debug, Clone)]
pub struct RetryConfig {
    pub max_attempts: u32,
    pub base_delay: Duration,
    pub max_delay: Duration,
    /// Jitter fraction (0.0 - 1.0)
    pub jitter: f64,
}

impl Default for RetryConfig {
    fn default() -> Self {
        Self {
            max_attempts: 3,
            base_delay: Duration::from_millis(500),
            max_delay: Duration::from_secs(10),
            jitter: 0.3,
        }
    }
}

/// Cache TTL configuration (from `CACHE_CONFIG` in constants.js)
/// With nostrdb, most of these become DB-level concerns, but TTLs
/// still control when to re-fetch from relays.
#[derive(Debug, Clone)]
pub struct CacheConfig {
    pub profile_ttl: Duration,
    pub mute_list_ttl: Duration,
    pub follow_list_ttl: Duration,
    pub emoji_ttl: Duration,
    pub timeline_ttl: Duration,
    pub nip05_ttl: Duration,
    pub relay_info_ttl: Duration,
    /// Max profiles to keep in hot LRU (in-memory, on top of nostrdb)
    pub max_hot_profiles: usize,
    /// Max timeline entries in hot cache
    pub max_hot_timeline: usize,
}

impl Default for CacheConfig {
    fn default() -> Self {
        Self {
            profile_ttl: Duration::from_secs(5 * 60),
            mute_list_ttl: Duration::from_secs(10 * 60),
            follow_list_ttl: Duration::from_secs(10 * 60),
            emoji_ttl: Duration::from_secs(30 * 60),
            timeline_ttl: Duration::from_secs(30),
            nip05_ttl: Duration::from_secs(5 * 60),
            relay_info_ttl: Duration::from_secs(60 * 60),
            max_hot_profiles: 500,
            max_hot_timeline: 100,
        }
    }
}

/// Recommendation tuning parameters (from `recommendation.js`)
#[derive(Debug, Clone)]
pub struct RecommendationConfig {
    pub engagement_weights: EngagementWeights,
    pub social_boost: SocialBoostConfig,
    pub time_decay: TimeDecayConfig,
    pub feed_mix: super::types::FeedMixRatio,
}

impl Default for RecommendationConfig {
    fn default() -> Self {
        Self {
            engagement_weights: EngagementWeights::default(),
            social_boost: SocialBoostConfig::default(),
            time_decay: TimeDecayConfig::default(),
            feed_mix: super::types::FeedMixRatio::default(),
        }
    }
}

/// Engagement weights (from `ENGAGEMENT_WEIGHTS` in recommendation.js)
#[derive(Debug, Clone)]
pub struct EngagementWeights {
    pub zap: f64,
    pub reply: f64,
    pub repost: f64,
    pub like: f64,
    pub quote: f64,
    pub bookmark: f64,
}

impl Default for EngagementWeights {
    fn default() -> Self {
        Self {
            zap: 100.0,
            reply: 30.0,
            repost: 25.0,
            like: 5.0,
            quote: 35.0,
            bookmark: 15.0,
        }
    }
}

/// Social boost multipliers (from `SOCIAL_BOOST` in recommendation.js)
#[derive(Debug, Clone)]
pub struct SocialBoostConfig {
    pub second_degree: f64,
    pub mutual_follow: f64,
    pub high_engagement_author: f64,
    pub first_degree: f64,
    pub unknown: f64,
}

impl Default for SocialBoostConfig {
    fn default() -> Self {
        Self {
            second_degree: 3.0,
            mutual_follow: 2.5,
            high_engagement_author: 2.0,
            first_degree: 0.5,
            unknown: 1.0,
        }
    }
}

/// Time decay parameters (from `TIME_DECAY` in recommendation.js)
#[derive(Debug, Clone)]
pub struct TimeDecayConfig {
    /// Hours until score halves
    pub half_life_hours: f64,
    /// Hours after which posts score very low
    pub max_age_hours: f64,
    /// Boost multiplier for posts under 1 hour old
    pub freshness_boost: f64,
    /// Minimum score for posts older than max_age
    pub min_score: f64,
}

impl Default for TimeDecayConfig {
    fn default() -> Self {
        Self {
            half_life_hours: 6.0,
            max_age_hours: 48.0,
            freshness_boost: 1.5,
            min_score: 0.1,
        }
    }
}
