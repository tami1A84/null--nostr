use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

/// Parsed user profile (mirrors JS `parseProfile`)
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct UserProfile {
    pub name: String,
    pub display_name: String,
    pub about: String,
    pub picture: String,
    pub banner: String,
    pub nip05: String,
    pub lud16: String,
    pub website: String,
    pub birthday: String,
    pub pubkey: String,
}

/// Engagement counts for a single event
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct EngagementData {
    pub likes: u64,
    pub reposts: u64,
    pub replies: u64,
    pub zaps: u64,
    pub quotes: u64,
}

/// Feed category mix targets (percentage-based)
#[derive(Debug, Clone)]
pub struct FeedMixRatio {
    /// Friends-of-friends discovery (default 50%)
    pub second_degree: f64,
    /// High-engagement out-of-network (default 30%)
    pub out_of_network: f64,
    /// Direct follows (default 20%)
    pub first_degree: f64,
}

impl Default for FeedMixRatio {
    fn default() -> Self {
        Self {
            second_degree: 0.50,
            out_of_network: 0.30,
            first_degree: 0.20,
        }
    }
}

/// Social context for recommendation scoring
#[derive(Debug, Clone, Default)]
pub struct SocialContext {
    pub follow_list: HashSet<String>,
    pub second_degree_follows: HashSet<String>,
    pub followers: HashSet<String>,
    pub engagement_history: EngagementHistory,
    pub profiles: HashMap<String, UserProfile>,
    pub muted_pubkeys: HashSet<String>,
    pub user_geohash: Option<String>,
    pub author_stats: HashMap<String, AuthorStats>,
}

/// Per-author statistics
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AuthorStats {
    pub follower_count: u64,
}

/// User's engagement history for personalization
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct EngagementHistory {
    pub liked_authors: HashMap<String, u64>,
    pub reposted_authors: HashMap<String, u64>,
    pub replied_authors: HashMap<String, u64>,
}

/// Scored post for feed ordering
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoredPost {
    pub event_id: String,
    pub pubkey: String,
    pub score: f64,
    pub created_at: u64,
}

/// NIP-58 Badge Definition (kind 30009)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BadgeDefinition {
    pub id: String,
    pub name: String,
    pub description: String,
    pub image: String,
    pub thumbnails: Vec<String>,
}

/// NIP-58 Badge Award (kind 8)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BadgeAward {
    pub badge_id: String,
    pub award_event_id: String,
    pub award_pubkey: String,
}

/// NIP-32 Birdwatch Label (kind 1985)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BirdwatchLabel {
    pub event_id: String,
    pub author: String,
    pub context_type: String,
    pub content: String,
    pub timestamp: u64,
}

/// Timeline fetch result
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimelineResult {
    pub event_ids: Vec<String>,
    pub has_more: bool,
}

/// Connection statistics
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionStats {
    pub connected_relays: usize,
    pub total_relays: usize,
    pub pending_subscriptions: usize,
}

/// Per-relay information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RelayInfo {
    pub url: String,
    pub status: String,
    pub connected: bool,
}

/// Japanese-friendly timestamp display
pub fn format_timestamp_ja(timestamp: u64) -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let diff = now.saturating_sub(timestamp);

    if diff < 60 {
        "たった今".to_string()
    } else if diff < 3600 {
        format!("{}分", diff / 60)
    } else if diff < 86400 {
        format!("{}時間", diff / 3600)
    } else if diff < 604800 {
        format!("{}日", diff / 86400)
    } else {
        // Format as M/D
        let secs = timestamp as i64;
        // Simple formatting without chrono dependency
        let days_since_epoch = secs / 86400;
        let approx_year = 1970 + (days_since_epoch / 365);
        let day_of_year = days_since_epoch % 365;
        let month = (day_of_year / 30) + 1;
        let day = (day_of_year % 30) + 1;
        let _ = approx_year; // suppress unused
        format!("{}月{}日", month.min(12), day.min(31))
    }
}
