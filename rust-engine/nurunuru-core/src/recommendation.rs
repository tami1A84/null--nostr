//! Recommendation engine — X-algorithm inspired feed ranking.
//!
//! Ported from `lib/recommendation.js`. The core scoring formula is:
//!
//! ```text
//! Score = Engagement × SocialBoost × AuthorQuality × GeohashBoost × AuthorModifier × TimeDecay
//! ```
//!
//! Feed mix targets:
//! - 50% from 2nd-degree network (friends-of-friends discovery)
//! - 30% from out-of-network high-engagement (viral content)
//! - 20% from 1st-degree (important follows)

use std::collections::{HashMap, HashSet};

use crate::config::RecommendationConfig;
use crate::types::{EngagementData, EngagementHistory, ScoredPost, UserProfile};

/// Stateless recommendation engine. All mutable user state
/// (not-interested, author scores, engagement history) is passed in
/// from the platform layer so the engine itself stays pure.
pub struct RecommendationEngine {
    config: RecommendationConfig,
}

impl RecommendationEngine {
    pub fn new(config: RecommendationConfig) -> Self {
        Self { config }
    }

    /// Calculate time-decay factor for a post.
    /// Returns a multiplier in [min_score .. freshness_boost].
    pub fn time_decay(&self, created_at: u64) -> f64 {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let age_hours = (now.saturating_sub(created_at) as f64) / 3600.0;

        let td = &self.config.time_decay;

        if age_hours < 1.0 {
            return td.freshness_boost;
        }
        if age_hours > td.max_age_hours {
            return td.min_score;
        }

        // Exponential decay: 0.5^(age / halfLife)
        0.5_f64.powf(age_hours / td.half_life_hours)
    }

    /// Calculate engagement score from reaction counts.
    pub fn engagement_score(&self, data: &EngagementData) -> f64 {
        let w = &self.config.engagement_weights;
        data.zaps as f64 * w.zap
            + data.replies as f64 * w.reply
            + data.reposts as f64 * w.repost
            + data.likes as f64 * w.like
            + data.quotes as f64 * w.quote
            + 1.0 // minimum base score
    }

    /// Calculate social boost based on network position.
    pub fn social_boost(
        &self,
        author_pubkey: &str,
        follow_list: &HashSet<String>,
        second_degree: &HashSet<String>,
        followers: &HashSet<String>,
        engagement_history: &EngagementHistory,
    ) -> f64 {
        let sb = &self.config.social_boost;

        // Engagement-based personalized boost
        let liked = *engagement_history
            .liked_authors
            .get(author_pubkey)
            .unwrap_or(&0) as f64;
        let reposted = *engagement_history
            .reposted_authors
            .get(author_pubkey)
            .unwrap_or(&0) as f64;
        let replied = *engagement_history
            .replied_authors
            .get(author_pubkey)
            .unwrap_or(&0) as f64;
        let total_engagements = liked + reposted * 2.0 + replied * 3.0;

        let engagement_boost = if total_engagements >= 10.0 {
            sb.high_engagement_author
        } else if total_engagements >= 5.0 {
            1.5
        } else {
            1.0
        };

        // Network position boost
        if follow_list.contains(author_pubkey) {
            if followers.contains(author_pubkey) {
                return sb.mutual_follow * engagement_boost;
            }
            return sb.first_degree * engagement_boost;
        }

        if second_degree.contains(author_pubkey) {
            return sb.second_degree * engagement_boost;
        }

        sb.unknown * engagement_boost
    }

    /// Calculate author quality score (NIP-05, follower count).
    pub fn author_quality(&self, profile: Option<&UserProfile>, follower_count: u64) -> f64 {
        let mut quality = 1.0;

        if let Some(p) = profile {
            if !p.nip05.is_empty() {
                quality *= 1.3;
            }
        }

        if follower_count > 0 {
            let follower_boost = 1.0 + (follower_count as f64).max(1.0).log10() * 0.1;
            quality *= follower_boost.min(1.5);
        }

        quality
    }

    /// Calculate geohash proximity boost.
    pub fn geohash_boost(user_geohash: Option<&str>, author_geohash: Option<&str>) -> f64 {
        match (user_geohash, author_geohash) {
            (Some(u), Some(a)) => {
                let common = u
                    .chars()
                    .zip(a.chars())
                    .take_while(|(uc, ac)| uc == ac)
                    .count();
                if common >= 5 {
                    2.0 // ~5km proximity
                } else if common >= 3 {
                    1.5 // Same region
                } else if common >= 2 {
                    1.2 // Same country
                } else {
                    1.0
                }
            }
            _ => 1.0,
        }
    }

    /// Full recommendation score for a single post.
    /// Returns `None` if the post should be filtered out (muted, not-interested).
    pub fn score_post(
        &self,
        event_id: &str,
        author_pubkey: &str,
        created_at: u64,
        engagement: &EngagementData,
        follow_list: &HashSet<String>,
        second_degree_follows: &HashSet<String>,
        followers: &HashSet<String>,
        engagement_history: &EngagementHistory,
        profiles: &HashMap<String, UserProfile>,
        muted_pubkeys: &HashSet<String>,
        not_interested_posts: &HashSet<String>,
        author_scores: &HashMap<String, f64>,
        user_geohash: Option<&str>,
        follower_count: u64,
    ) -> Option<f64> {
        // Hard filters
        if not_interested_posts.contains(event_id) || muted_pubkeys.contains(author_pubkey) {
            return None;
        }

        let eng_score = self.engagement_score(engagement);

        let social = self.social_boost(
            author_pubkey,
            follow_list,
            second_degree_follows,
            followers,
            engagement_history,
        );

        let profile = profiles.get(author_pubkey);
        let quality = self.author_quality(profile, follower_count);

        let geo = Self::geohash_boost(
            user_geohash,
            profile.and_then(|_p| {
                // Profile geohash would come from profile metadata if available
                None::<&str>
            }),
        );

        let author_modifier = author_scores.get(author_pubkey).copied().unwrap_or(1.0);

        let time = self.time_decay(created_at);

        let final_score = eng_score * social * quality * geo * author_modifier * time;

        if final_score > 0.0 {
            Some(final_score)
        } else {
            None
        }
    }

    /// Sort and rank a batch of posts, applying category mixing.
    pub fn rank_feed(
        &self,
        posts: &[(String, String, u64)], // (event_id, pubkey, created_at)
        engagements: &HashMap<String, EngagementData>,
        follow_list: &HashSet<String>,
        second_degree_follows: &HashSet<String>,
        followers: &HashSet<String>,
        engagement_history: &EngagementHistory,
        profiles: &HashMap<String, UserProfile>,
        muted_pubkeys: &HashSet<String>,
        not_interested_posts: &HashSet<String>,
        author_scores: &HashMap<String, f64>,
        user_geohash: Option<&str>,
        author_stats: &HashMap<String, u64>,
        limit: usize,
    ) -> Vec<ScoredPost> {
        let empty_engagement = EngagementData::default();

        // Score all posts
        let mut scored: Vec<ScoredPost> = posts
            .iter()
            .filter_map(|(eid, pk, ts)| {
                let eng = engagements.get(eid).unwrap_or(&empty_engagement);
                let fc = author_stats.get(pk).copied().unwrap_or(0);
                self.score_post(
                    eid,
                    pk,
                    *ts,
                    eng,
                    follow_list,
                    second_degree_follows,
                    followers,
                    engagement_history,
                    profiles,
                    muted_pubkeys,
                    not_interested_posts,
                    author_scores,
                    user_geohash,
                    fc,
                )
                .map(|score| ScoredPost {
                    event_id: eid.clone(),
                    pubkey: pk.clone(),
                    score,
                    created_at: *ts,
                })
            })
            .collect();

        // Categorize
        let mut cat_2nd: Vec<&ScoredPost> = Vec::new();
        let mut cat_out: Vec<&ScoredPost> = Vec::new();
        let mut cat_1st: Vec<&ScoredPost> = Vec::new();

        for sp in &scored {
            if second_degree_follows.contains(&sp.pubkey) {
                cat_2nd.push(sp);
            } else if follow_list.contains(&sp.pubkey) {
                cat_1st.push(sp);
            } else {
                cat_out.push(sp);
            }
        }

        // Sort each category by score descending
        cat_2nd.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        cat_out.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        cat_1st.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));

        let mix = &self.config.feed_mix;
        let target_2nd = cat_2nd.len().min((limit as f64 * mix.second_degree) as usize);
        let target_out = cat_out.len().min((limit as f64 * mix.out_of_network) as usize);
        let target_1st = cat_1st.len().min((limit as f64 * mix.first_degree) as usize);

        let mut result_ids: HashSet<String> = HashSet::new();
        let mut result: Vec<ScoredPost> = Vec::with_capacity(limit);

        // Mix categories
        for sp in cat_2nd.iter().take(target_2nd) {
            result_ids.insert(sp.event_id.clone());
            result.push((*sp).clone());
        }
        for sp in cat_out.iter().take(target_out) {
            result_ids.insert(sp.event_id.clone());
            result.push((*sp).clone());
        }
        for sp in cat_1st.iter().take(target_1st) {
            result_ids.insert(sp.event_id.clone());
            result.push((*sp).clone());
        }

        // Fill remaining
        let remaining = limit.saturating_sub(result.len());
        if remaining > 0 {
            scored.sort_by(|a, b| {
                b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal)
            });
            for sp in &scored {
                if result.len() >= limit {
                    break;
                }
                if !result_ids.contains(&sp.event_id) {
                    result_ids.insert(sp.event_id.clone());
                    result.push(sp.clone());
                }
            }
        }

        // Final sort by score
        result.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        result.truncate(limit);
        result
    }

    /// Extract 2nd-degree network from follow lists.
    /// Returns pubkeys followed by your follows but not by you.
    pub fn extract_2nd_degree_network(
        my_follows: &HashSet<String>,
        follows_of_follows: &HashMap<String, Vec<String>>,
    ) -> HashSet<String> {
        let mut second_degree = HashSet::new();

        for (follower, their_follows) in follows_of_follows {
            if !my_follows.contains(follower) {
                continue;
            }
            for pubkey in their_follows {
                if !my_follows.contains(pubkey) {
                    second_degree.insert(pubkey.clone());
                }
            }
        }

        second_degree
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::RecommendationConfig;

    fn make_engine() -> RecommendationEngine {
        RecommendationEngine::new(RecommendationConfig::default())
    }

    #[test]
    fn test_engagement_score_base() {
        let engine = make_engine();
        let data = EngagementData::default();
        assert!((engine.engagement_score(&data) - 1.0).abs() < f64::EPSILON);
    }

    #[test]
    fn test_engagement_score_weighted() {
        let engine = make_engine();
        let data = EngagementData {
            likes: 10,
            zaps: 1,
            replies: 2,
            reposts: 1,
            quotes: 0,
        };
        // 10*5 + 1*100 + 2*30 + 1*25 + 1 = 50 + 100 + 60 + 25 + 1 = 236
        assert!((engine.engagement_score(&data) - 236.0).abs() < f64::EPSILON);
    }

    #[test]
    fn test_geohash_boost_exact() {
        assert!((RecommendationEngine::geohash_boost(Some("xn76u"), Some("xn76u")) - 2.0).abs() < f64::EPSILON);
    }

    #[test]
    fn test_geohash_boost_region() {
        assert!((RecommendationEngine::geohash_boost(Some("xn76u"), Some("xn7ab")) - 1.5).abs() < f64::EPSILON);
    }

    #[test]
    fn test_geohash_boost_none() {
        assert!((RecommendationEngine::geohash_boost(None, Some("xn76u")) - 1.0).abs() < f64::EPSILON);
    }

    #[test]
    fn test_muted_post_filtered() {
        let engine = make_engine();
        let mut muted = HashSet::new();
        muted.insert("bad_author".to_string());

        let result = engine.score_post(
            "event1",
            "bad_author",
            0,
            &EngagementData::default(),
            &HashSet::new(),
            &HashSet::new(),
            &HashSet::new(),
            &EngagementHistory::default(),
            &HashMap::new(),
            &muted,
            &HashSet::new(),
            &HashMap::new(),
            None,
            0,
        );
        assert!(result.is_none());
    }

    #[test]
    fn test_2nd_degree_extraction() {
        let mut my_follows = HashSet::new();
        my_follows.insert("alice".to_string());
        my_follows.insert("bob".to_string());

        let mut fof = HashMap::new();
        fof.insert(
            "alice".to_string(),
            vec!["charlie".to_string(), "bob".to_string()],
        );
        fof.insert("bob".to_string(), vec!["charlie".to_string(), "dave".to_string()]);

        let result = RecommendationEngine::extract_2nd_degree_network(&my_follows, &fof);
        assert!(result.contains("charlie"));
        assert!(result.contains("dave"));
        assert!(!result.contains("alice"));
        assert!(!result.contains("bob"));
    }
}
