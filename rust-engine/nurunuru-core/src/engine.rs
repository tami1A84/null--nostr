//! NuruNuru Engine — the main entry point.
//!
//! Wraps `nostr_sdk::Client` (with nostrdb backend) and the recommendation
//! engine into a single, FFI-friendly interface.
//!
//! ## Mapping from JS to Rust
//!
//! | JS function (lib/)            | Rust method                          |
//! |-------------------------------|--------------------------------------|
//! | `fetchEvents`                 | `fetch_events`                       |
//! | `subscribeToEvents`           | `subscribe`                          |
//! | `publishEvent`                | `publish_event`                      |
//! | `fetchFollowList`             | `fetch_follow_list`                  |
//! | `followUser` / `unfollowUser` | `follow_user` / `unfollow_user`      |
//! | `sendEncryptedDM`             | `send_dm`                            |
//! | `fetchProfile` / `parseProfile` | `fetch_profile`                    |
//! | `signEventNip07`              | handled by `NostrSigner` trait       |
//! | `encryptNip44` / `decryptNip44` | handled by `NostrSigner` trait     |
//! | `sortByRecommendation`        | `get_recommended_feed`               |
//! | `getRecommendedPosts`         | `get_recommended_feed`               |
//! | `fetchEngagementData`         | `fetch_engagement_data`              |
//! | `createGiftWrap`              | handled by `client.send_private_msg` |

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

use nostr::nips::nip09::EventDeletionRequest;
use nostr::nips::nip25::ReactionTarget;
use nostr::prelude::*;
use nostr_ndb::NdbDatabase;
use nostr_sdk::prelude::*;
use tokio::sync::RwLock;

use crate::config::NuruNuruConfig;
use crate::error::{NuruNuruError, Result};
use crate::filters;
use crate::recommendation::RecommendationEngine;
use crate::relay;
use crate::types::*;

/// The main NuruNuru engine.
///
/// Thread-safe (`Send + Sync`), designed to be held as a singleton
/// behind `Arc` on the platform side.
pub struct NuruNuruEngine {
    client: Client,
    config: NuruNuruConfig,
    recommendation: RecommendationEngine,

    // User state (persisted via platform storage, loaded at init)
    user_pubkey: RwLock<Option<PublicKey>>,
    follow_list: RwLock<HashSet<String>>,
    muted_pubkeys: RwLock<HashSet<String>>,
    second_degree_follows: RwLock<HashSet<String>>,
    engagement_history: RwLock<EngagementHistory>,
    not_interested_posts: RwLock<HashSet<String>>,
    author_scores: RwLock<HashMap<String, f64>>,
}

impl NuruNuruEngine {
    /// Create a new engine with the given signer and configuration.
    ///
    /// The signer can be `Keys` (private key), or a custom `NostrSigner`
    /// implementation for NIP-07/NIP-46 bridges.
    pub async fn new(
        signer: impl IntoNostrSigner,
        config: NuruNuruConfig,
    ) -> Result<Arc<Self>> {
        // Open nostrdb at the configured path
        let ndb = NdbDatabase::open(&config.db_path)
            .map_err(|e| NuruNuruError::DatabaseError(e.to_string()))?;

        // Build the nostr-sdk Client with nostrdb backend
        let client = Client::builder()
            .signer(signer)
            .database(ndb)
            .build();

        // Add relays
        let relay_urls = relay::build_relay_list(&config.relay);
        for url in &relay_urls {
            if let Ok(relay_url) = RelayUrl::parse(url) {
                let _ = client.add_relay(relay_url).await;
            }
        }

        // Add search relay separately
        if let Ok(search_url) = RelayUrl::parse(&config.relay.search_relay) {
            let _ = client.add_relay(search_url).await;
        }

        let recommendation = RecommendationEngine::new(config.recommendation.clone());

        let engine = Arc::new(Self {
            client,
            config,
            recommendation,
            user_pubkey: RwLock::new(None),
            follow_list: RwLock::new(HashSet::new()),
            muted_pubkeys: RwLock::new(HashSet::new()),
            second_degree_follows: RwLock::new(HashSet::new()),
            engagement_history: RwLock::new(EngagementHistory::default()),
            not_interested_posts: RwLock::new(HashSet::new()),
            author_scores: RwLock::new(HashMap::new()),
        });

        Ok(engine)
    }

    /// Connect to all configured relays (spawns background reconnect tasks).
    pub async fn connect(&self) {
        self.client.connect().await;
    }

    /// Disconnect from all relays.
    pub async fn disconnect(&self) -> Result<()> {
        self.client.disconnect().await;
        Ok(())
    }

    /// Set the current user's public key and load their data.
    pub async fn login(&self, pubkey: PublicKey) -> Result<()> {
        {
            let mut pk = self.user_pubkey.write().await;
            *pk = Some(pubkey);
        }

        // Load follow list, mute list in parallel
        let (follows, mutes) = tokio::join!(
            self.fetch_follow_list(pubkey),
            self.fetch_mute_list(pubkey),
        );

        if let Ok(follows) = follows {
            let mut fl = self.follow_list.write().await;
            *fl = follows.into_iter().collect();
        }
        if let Ok(mutes) = mutes {
            let mut ml = self.muted_pubkeys.write().await;
            *ml = mutes.into_iter().collect();
        }

        Ok(())
    }

    /// Get the current user's public key.
    pub async fn current_pubkey(&self) -> Option<PublicKey> {
        *self.user_pubkey.read().await
    }

    // ─── Profile ──────────────────────────────────────────────

    /// Fetch and parse a user profile (kind 0).
    pub async fn fetch_profile(&self, pubkey: PublicKey) -> Result<Option<UserProfile>> {
        let filter = filters::profile_filter(&[pubkey]);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(10))
            .await?;

        let profile = events
            .into_iter()
            .next()
            .and_then(|e| Self::parse_profile_event(&e));

        Ok(profile)
    }

    /// Batch-fetch profiles.
    pub async fn fetch_profiles(
        &self,
        pubkeys: &[PublicKey],
    ) -> Result<HashMap<String, UserProfile>> {
        if pubkeys.is_empty() {
            return Ok(HashMap::new());
        }

        let filter = filters::profile_filter(pubkeys);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(15))
            .await?;

        let mut profiles = HashMap::new();
        for event in events {
            if let Some(p) = Self::parse_profile_event(&event) {
                profiles.insert(p.pubkey.clone(), p);
            }
        }

        Ok(profiles)
    }

    /// Parse a kind-0 metadata event into a `UserProfile`.
    fn parse_profile_event(event: &Event) -> Option<UserProfile> {
        if event.kind != Kind::Metadata {
            return None;
        }
        let content: serde_json::Value = serde_json::from_str(&event.content).ok()?;
        Some(UserProfile {
            name: content["name"]
                .as_str()
                .or(content["display_name"].as_str())
                .unwrap_or("")
                .to_string(),
            display_name: content["display_name"]
                .as_str()
                .or(content["name"].as_str())
                .unwrap_or("")
                .to_string(),
            about: content["about"].as_str().unwrap_or("").to_string(),
            picture: content["picture"].as_str().unwrap_or("").to_string(),
            banner: content["banner"].as_str().unwrap_or("").to_string(),
            nip05: content["nip05"].as_str().unwrap_or("").to_string(),
            lud16: content["lud16"].as_str().unwrap_or("").to_string(),
            website: content["website"].as_str().unwrap_or("").to_string(),
            birthday: content["birthday"].as_str().unwrap_or("").to_string(),
            pubkey: event.pubkey.to_hex(),
        })
    }

    // ─── Follow List (NIP-02) ──────────────────────────────────

    /// Fetch the follow list for a user.
    pub async fn fetch_follow_list(&self, pubkey: PublicKey) -> Result<Vec<String>> {
        let filter = filters::follow_list_filter(pubkey);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(10))
            .await?;

        let follows = events
            .into_iter()
            .next()
            .map(|e| {
                e.tags
                    .iter()
                    .filter(|t| t.kind() == TagKind::p())
                    .filter_map(|t| t.content().map(|s| s.to_string()))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();

        Ok(follows)
    }

    /// Follow a user (publish updated kind 3).
    pub async fn follow_user(
        &self,
        target_pubkey: PublicKey,
    ) -> Result<()> {
        let my_pk = self
            .current_pubkey()
            .await
            .ok_or(NuruNuruError::NoSigningMethod)?;

        let current_follows = self.fetch_follow_list(my_pk).await?;

        if current_follows.contains(&target_pubkey.to_hex()) {
            return Err(NuruNuruError::AlreadyFollowing);
        }

        // Build new contact list
        let mut contacts: Vec<Contact> = current_follows
            .iter()
            .filter_map(|hex| PublicKey::from_hex(hex).ok())
            .map(|pk| Contact::new(pk))
            .collect();
        contacts.push(Contact::new(target_pubkey));

        let builder = EventBuilder::contact_list(contacts);
        self.client.send_event_builder(builder).await?;

        // Update local state
        let mut fl = self.follow_list.write().await;
        fl.insert(target_pubkey.to_hex());

        Ok(())
    }

    /// Unfollow a user (publish updated kind 3).
    pub async fn unfollow_user(
        &self,
        target_pubkey: PublicKey,
    ) -> Result<()> {
        let my_pk = self
            .current_pubkey()
            .await
            .ok_or(NuruNuruError::NoSigningMethod)?;

        let current_follows = self.fetch_follow_list(my_pk).await?;

        let target_hex = target_pubkey.to_hex();
        let contacts: Vec<Contact> = current_follows
            .iter()
            .filter(|hex| *hex != &target_hex)
            .filter_map(|hex| PublicKey::from_hex(hex).ok())
            .map(|pk| Contact::new(pk))
            .collect();

        let builder = EventBuilder::contact_list(contacts);
        self.client.send_event_builder(builder).await?;

        // Update local state
        let mut fl = self.follow_list.write().await;
        fl.remove(&target_hex);

        Ok(())
    }

    // ─── Mute List (NIP-51) ────────────────────────────────────

    /// Fetch mute list (kind 10000).
    pub async fn fetch_mute_list(&self, pubkey: PublicKey) -> Result<Vec<String>> {
        let filter = filters::mute_list_filter(pubkey);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(10))
            .await?;

        let muted = events
            .into_iter()
            .next()
            .map(|e| {
                e.tags
                    .iter()
                    .filter(|t| t.kind() == TagKind::p())
                    .filter_map(|t| t.content().map(|s| s.to_string()))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();

        Ok(muted)
    }

    // ─── Timeline ──────────────────────────────────────────────

    /// Fetch timeline events (notes + reposts) for the given authors.
    pub async fn fetch_timeline(
        &self,
        authors: Option<&[PublicKey]>,
        since: Option<Timestamp>,
        limit: usize,
    ) -> Result<Vec<Event>> {
        let tl_filters = filters::timeline_filters(
            authors,
            since,
            None,
            limit,
            limit / 2,
        );

        let mut all_events = Vec::new();
        for f in tl_filters {
            let events = self
                .client
                .fetch_events(f, Duration::from_secs(15))
                .await?;
            all_events.extend(events);
        }

        // Sort by created_at descending
        all_events.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        all_events.truncate(limit);

        Ok(all_events)
    }

    /// Fetch engagement data (reactions, reposts, replies, zaps) for events.
    pub async fn fetch_engagement_data(
        &self,
        event_ids: &[EventId],
    ) -> Result<HashMap<String, EngagementData>> {
        if event_ids.is_empty() {
            return Ok(HashMap::new());
        }

        let reactions_filter = filters::reaction_filter(event_ids, 1000);
        let reposts_filter = Filter::new()
            .kind(Kind::Repost)
            .events(event_ids.iter().copied())
            .limit(500);
        let replies_filter = filters::reply_filter(event_ids, 500);
        let zaps_filter = filters::zap_filter(event_ids, 500);

        let timeout = Duration::from_secs(10);

        let (reactions, reposts, replies, zaps) = tokio::join!(
            self.client.fetch_events(reactions_filter, timeout),
            self.client.fetch_events(reposts_filter, timeout),
            self.client.fetch_events(replies_filter, timeout),
            self.client.fetch_events(zaps_filter, timeout),
        );

        let mut engagement: HashMap<String, EngagementData> = HashMap::new();

        // Initialize
        for eid in event_ids {
            engagement.insert(eid.to_hex(), EngagementData::default());
        }

        // Count reactions (likes)
        if let Ok(events) = reactions {
            for event in events {
                if let Some(target) = event
                    .tags
                    .iter()
                    .find(|t| t.kind() == TagKind::e())
                    .and_then(|t| t.content().map(|s| s.to_string()))
                {
                    if let Some(data) = engagement.get_mut(&target) {
                        data.likes += 1;
                    }
                }
            }
        }

        // Count reposts
        if let Ok(events) = reposts {
            for event in events {
                if let Some(target) = event
                    .tags
                    .iter()
                    .find(|t| t.kind() == TagKind::e())
                    .and_then(|t| t.content().map(|s| s.to_string()))
                {
                    if let Some(data) = engagement.get_mut(&target) {
                        data.reposts += 1;
                    }
                }
            }
        }

        // Count replies
        if let Ok(events) = replies {
            for event in events {
                let target = event
                    .tags
                    .iter()
                    .find(|t| t.kind() == TagKind::e())
                    .and_then(|t| t.content().map(|s| s.to_string()));
                if let Some(target) = target {
                    if let Some(data) = engagement.get_mut(&target) {
                        data.replies += 1;
                    }
                }
            }
        }

        // Count zaps
        if let Ok(events) = zaps {
            for event in events {
                if let Some(target) = event
                    .tags
                    .iter()
                    .find(|t| t.kind() == TagKind::e())
                    .and_then(|t| t.content().map(|s| s.to_string()))
                {
                    if let Some(data) = engagement.get_mut(&target) {
                        data.zaps += 1;
                    }
                }
            }
        }

        Ok(engagement)
    }

    // ─── Recommended Feed ──────────────────────────────────────

    /// Get a recommended feed using the X-algorithm-inspired ranking.
    pub async fn get_recommended_feed(
        &self,
        limit: usize,
    ) -> Result<Vec<ScoredPost>> {
        let follow_list = self.follow_list.read().await.clone();
        let muted = self.muted_pubkeys.read().await.clone();
        let second_degree = self.second_degree_follows.read().await.clone();
        let engagement_history = self.engagement_history.read().await.clone();
        let not_interested = self.not_interested_posts.read().await.clone();
        let author_scores = self.author_scores.read().await.clone();

        // Fetch from follow list + 2nd degree
        let mut author_pks: Vec<PublicKey> = Vec::new();
        for hex in follow_list.iter().chain(second_degree.iter()).take(200) {
            if let Ok(pk) = PublicKey::from_hex(hex) {
                author_pks.push(pk);
            }
        }

        let since = filters::since_hours_ago(48);
        let events = self
            .fetch_timeline(
                if author_pks.is_empty() {
                    None
                } else {
                    Some(&author_pks)
                },
                Some(since),
                limit * 3, // fetch more than needed for ranking
            )
            .await?;

        // Collect event IDs for engagement data
        let event_ids: Vec<EventId> = events.iter().map(|e| e.id).collect();
        let engagements = self.fetch_engagement_data(&event_ids).await?;

        // Build posts tuples
        let posts: Vec<(String, String, u64)> = events
            .iter()
            .map(|e| (e.id.to_hex(), e.pubkey.to_hex(), e.created_at.as_secs()))
            .collect();

        // Empty stats for now (could be populated from profile metadata)
        let author_stats: HashMap<String, u64> = HashMap::new();

        let user_geohash: Option<String> = None; // TODO: load from settings

        let scored = self.recommendation.rank_feed(
            &posts,
            &engagements,
            &follow_list,
            &second_degree,
            &HashSet::new(), // followers — would need separate fetch
            &engagement_history,
            &HashMap::new(), // profiles — could cache
            &muted,
            &not_interested,
            &author_scores,
            user_geohash.as_deref(),
            &author_stats,
            limit,
        );

        Ok(scored)
    }

    // ─── DMs (NIP-17) ──────────────────────────────────────────

    /// Send an encrypted DM using NIP-17 gift wrapping.
    /// All seal/wrap layers are handled by `nostr-sdk`.
    pub async fn send_dm(
        &self,
        recipient: PublicKey,
        content: &str,
    ) -> Result<()> {
        self.client
            .send_private_msg(recipient, content, [])
            .await?;
        Ok(())
    }

    /// Fetch DM events (gift-wrapped, kind 1059).
    pub async fn fetch_dms(
        &self,
        since: Option<Timestamp>,
        limit: usize,
    ) -> Result<Vec<Event>> {
        let my_pk = self
            .current_pubkey()
            .await
            .ok_or(NuruNuruError::NoSigningMethod)?;

        let filter = filters::dm_filter(my_pk, since, limit);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(15))
            .await?;

        Ok(events.into_iter().collect())
    }

    // ─── Publishing ─────────────────────────────────────────────

    /// Publish a text note (kind 1).
    pub async fn publish_note(&self, content: &str, tags: Vec<Tag>) -> Result<EventId> {
        let mut builder = EventBuilder::text_note(content);
        for tag in tags {
            builder = builder.tag(tag);
        }
        let output = self.client.send_event_builder(builder).await?;
        Ok(output.val)
    }

    /// Publish a reaction (kind 7, NIP-25).
    pub async fn react(&self, event_id: EventId, author: PublicKey, reaction: &str) -> Result<EventId> {
        let target = ReactionTarget {
            event_id,
            public_key: author,
            coordinate: None,
            kind: Some(Kind::TextNote),
            relay_hint: None,
        };
        let builder = EventBuilder::reaction(target, reaction);
        let output = self.client.send_event_builder(builder).await?;
        Ok(output.val)
    }

    /// Repost an event (kind 6, NIP-18).
    pub async fn repost(&self, event: &Event) -> Result<EventId> {
        let builder = EventBuilder::repost(event, None);
        let output = self.client.send_event_builder(builder).await?;
        Ok(output.val)
    }

    /// Delete an event (kind 5, NIP-09).
    pub async fn delete_event(&self, event_id: EventId, reason: Option<&str>) -> Result<EventId> {
        let mut request = EventDeletionRequest::new().id(event_id);
        if let Some(r) = reason {
            request = request.reason(r);
        }
        let builder = EventBuilder::delete(request);
        let output = self.client.send_event_builder(builder).await?;
        Ok(output.val)
    }

    // ─── Search (NIP-50) ────────────────────────────────────────

    /// Full-text search via NIP-50.
    pub async fn search(&self, query: &str, limit: usize) -> Result<Vec<Event>> {
        let filter = filters::search_filter(query, limit);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(10))
            .await?;
        Ok(events.into_iter().collect())
    }

    // ─── Custom Emoji (NIP-30) ──────────────────────────────────

    /// Fetch custom emoji set for a user (kind 10030).
    pub async fn fetch_emoji_set(&self, pubkey: PublicKey) -> Result<Vec<(String, String)>> {
        let filter = filters::emoji_filter(pubkey);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(10))
            .await?;

        let emojis = events
            .into_iter()
            .next()
            .map(|e| {
                e.tags
                    .iter()
                    .filter(|t| t.kind() == TagKind::custom::<&str>("emoji"))
                    .filter_map(|t| {
                        let vals: Vec<&str> = t.as_slice().iter().map(|s| s.as_str()).collect();
                        if vals.len() >= 3 {
                            Some((vals[1].to_string(), vals[2].to_string()))
                        } else {
                            None
                        }
                    })
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();

        Ok(emojis)
    }

    // ─── User Preferences (local state) ─────────────────────────

    /// Mark a post as "not interested" for recommendation filtering.
    pub async fn mark_not_interested(&self, event_id: &str, author_pubkey: &str) {
        let mut ni = self.not_interested_posts.write().await;
        ni.insert(event_id.to_string());

        // Reduce author score
        let mut scores = self.author_scores.write().await;
        let current = scores.get(author_pubkey).copied().unwrap_or(1.0);
        scores.insert(author_pubkey.to_string(), (current * 0.7).max(0.1));
    }

    /// Record an engagement action for personalization.
    pub async fn record_engagement(&self, action: &str, author_pubkey: &str) {
        let mut history = self.engagement_history.write().await;
        match action {
            "like" => {
                *history
                    .liked_authors
                    .entry(author_pubkey.to_string())
                    .or_insert(0) += 1;
            }
            "repost" => {
                *history
                    .reposted_authors
                    .entry(author_pubkey.to_string())
                    .or_insert(0) += 1;
            }
            "reply" => {
                *history
                    .replied_authors
                    .entry(author_pubkey.to_string())
                    .or_insert(0) += 1;
            }
            _ => {}
        }
    }

    /// Load user state from serialized data (called from platform layer).
    pub async fn load_user_state(
        &self,
        follow_list: HashSet<String>,
        muted_pubkeys: HashSet<String>,
        engagement_history: EngagementHistory,
        not_interested_posts: HashSet<String>,
        author_scores: HashMap<String, f64>,
    ) {
        *self.follow_list.write().await = follow_list;
        *self.muted_pubkeys.write().await = muted_pubkeys;
        *self.engagement_history.write().await = engagement_history;
        *self.not_interested_posts.write().await = not_interested_posts;
        *self.author_scores.write().await = author_scores;
    }

    /// Export user state for persistence.
    pub async fn export_user_state(
        &self,
    ) -> (
        HashSet<String>,
        HashSet<String>,
        EngagementHistory,
        HashSet<String>,
        HashMap<String, f64>,
    ) {
        (
            self.follow_list.read().await.clone(),
            self.muted_pubkeys.read().await.clone(),
            self.engagement_history.read().await.clone(),
            self.not_interested_posts.read().await.clone(),
            self.author_scores.read().await.clone(),
        )
    }

    /// Get connection statistics.
    pub async fn connection_stats(&self) -> ConnectionStats {
        let relays = self.client.relays().await;
        let connected = relays
            .values()
            .filter(|r| r.status() == RelayStatus::Connected)
            .count();

        ConnectionStats {
            connected_relays: connected,
            total_relays: relays.len(),
            pending_subscriptions: 0,
        }
    }

    /// Get the list of configured relays with their connection status.
    pub async fn get_relay_list(&self) -> Vec<RelayInfo> {
        let relays = self.client.relays().await;
        relays
            .iter()
            .map(|(url, relay)| {
                let status = relay.status();
                let status_str = format!("{:?}", status);
                RelayInfo {
                    url: url.to_string(),
                    status: status_str,
                    connected: status == RelayStatus::Connected,
                }
            })
            .collect()
    }

    /// Add a relay URL and immediately connect to it.
    pub async fn add_relay(&self, url: &str) -> Result<()> {
        let relay_url = relay::parse_relay_url(url)?;
        self.client
            .add_relay(relay_url.clone())
            .await
            .map_err(|e| NuruNuruError::RelayError(e.to_string()))?;
        let _ = self.client.connect_relay(relay_url).await;
        Ok(())
    }

    /// Remove a relay URL and disconnect from it.
    pub async fn remove_relay(&self, url: &str) -> Result<()> {
        let relay_url = relay::parse_relay_url(url)?;
        self.client
            .remove_relay(relay_url)
            .await
            .map_err(|e| NuruNuruError::RelayError(e.to_string()))?;
        Ok(())
    }

    /// Disconnect and reconnect to all relays.
    pub async fn reconnect(&self) -> Result<()> {
        self.client.disconnect().await;
        self.client.connect().await;
        Ok(())
    }

    /// Query local nostrdb cache without hitting relays.
    pub async fn query_local(&self, filter: Filter) -> Result<Vec<Event>> {
        let events = self
            .client
            .database()
            .query(filter)
            .await
            .map_err(|e| NuruNuruError::DatabaseError(e.to_string()))?;
        Ok(events.into_iter().collect())
    }

    /// Publish an already-signed Nostr event to all connected relays.
    ///
    /// Unlike `publish_note` which builds and signs an event, this method
    /// takes a fully-signed event from the browser (signed via NIP-07 / Amber
    /// / NIP-46) and broadcasts it as-is. The nostr-sdk client verifies the
    /// signature before sending.
    ///
    /// Returns the event ID on success.
    pub async fn publish_raw_event(&self, event: Event) -> Result<EventId> {
        let output = self.client.send_event(&event).await?;
        Ok(output.val)
    }

    /// Store a raw event directly into nostrdb (bypasses relay network).
    ///
    /// Used by `/api/ingest` to persist browser-received events so they are
    /// available to the recommendation engine without waiting for relay fetch.
    ///
    /// Returns `true` if the event was newly saved, `false` if it was a
    /// duplicate or superseded by a newer replaceable event.
    pub async fn store_event(&self, event: Event) -> Result<bool> {
        let status = self
            .client
            .database()
            .save_event(&event)
            .await
            .map_err(|e| NuruNuruError::DatabaseError(e.to_string()))?;
        Ok(status.is_success())
    }
}
