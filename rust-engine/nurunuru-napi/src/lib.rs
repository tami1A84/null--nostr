//! napi-rs bridge for NuruNuru Core.
//!
//! Exposes the Rust engine to Node.js / Next.js as a native `.node` module.
//! All async methods return JS Promises via the napi tokio runtime.
//!
//! ## Usage from JS
//!
//! ```js
//! const { NuruNuruNapi } = require('./nurunuru-napi.node');
//!
//! const engine = await NuruNuruNapi.create(secretKeyHex, './nurunuru-db');
//! await engine.connect();
//! await engine.login(pubkeyHex);
//! const feed = await engine.getRecommendedFeed(50);
//! ```

use std::sync::Arc;

use napi::Result;
use napi_derive::napi;
use nostr::prelude::*;

use nurunuru_core::config::NuruNuruConfig;
use nurunuru_core::types::*;
use nurunuru_core::NuruNuruEngine;

// ─── napi-safe output types ─────────────────────────────────────

#[napi(object)]
pub struct NapiUserProfile {
    pub name: String,
    pub display_name: String,
    pub about: String,
    pub picture: String,
    pub banner: String,
    pub nip05: String,
    pub lud16: String,
    pub website: String,
    pub pubkey: String,
}

impl From<UserProfile> for NapiUserProfile {
    fn from(p: UserProfile) -> Self {
        Self {
            name: p.name,
            display_name: p.display_name,
            about: p.about,
            picture: p.picture,
            banner: p.banner,
            nip05: p.nip05,
            lud16: p.lud16,
            website: p.website,
            pubkey: p.pubkey,
        }
    }
}

#[napi(object)]
pub struct NapiScoredPost {
    pub event_id: String,
    pub pubkey: String,
    pub score: f64,
    /// Unix timestamp (seconds) as f64 for JS Number compatibility.
    pub created_at: f64,
}

impl From<ScoredPost> for NapiScoredPost {
    fn from(sp: ScoredPost) -> Self {
        Self {
            event_id: sp.event_id,
            pubkey: sp.pubkey,
            score: sp.score,
            created_at: sp.created_at as f64,
        }
    }
}

#[napi(object)]
pub struct NapiConnectionStats {
    pub connected_relays: u32,
    pub total_relays: u32,
}

#[napi(object)]
pub struct NapiEngagementData {
    pub likes: u32,
    pub reposts: u32,
    pub replies: u32,
    pub zaps: u32,
    pub quotes: u32,
}

impl From<EngagementData> for NapiEngagementData {
    fn from(d: EngagementData) -> Self {
        Self {
            likes: d.likes as u32,
            reposts: d.reposts as u32,
            replies: d.replies as u32,
            zaps: d.zaps as u32,
            quotes: d.quotes as u32,
        }
    }
}

// ─── Helper ─────────────────────────────────────────────────────

fn to_napi_err(e: impl std::fmt::Display) -> napi::Error {
    napi::Error::from_reason(e.to_string())
}

// ─── Main engine wrapper ────────────────────────────────────────

/// NuruNuru native engine for Node.js.
///
/// Create with `NuruNuruNapi.create(secretKeyHex, dbPath)`.
/// All methods that hit the network return Promises.
#[napi]
pub struct NuruNuruNapi {
    engine: Arc<NuruNuruEngine>,
}

#[napi]
impl NuruNuruNapi {
    // ─── Lifecycle ────────────────────────────────────────────

    /// Create a new engine instance (async factory).
    ///
    /// `secret_key_hex` — hex or nsec private key.
    /// `db_path` — directory for nostrdb (e.g. `"./nurunuru-db"`).
    #[napi(factory)]
    pub async fn create(secret_key_hex: String, db_path: String) -> Result<Self> {
        let keys = Keys::parse(&secret_key_hex).map_err(to_napi_err)?;

        let mut config = NuruNuruConfig::default();
        config.db_path = db_path;

        let engine = NuruNuruEngine::new(keys, config)
            .await
            .map_err(to_napi_err)?;

        Ok(Self { engine })
    }

    /// Connect to all configured relays.
    #[napi]
    pub async fn connect(&self) -> Result<()> {
        let engine = self.engine.clone();
        engine.connect().await;
        Ok(())
    }

    /// Disconnect from all relays.
    #[napi]
    pub async fn disconnect(&self) -> Result<()> {
        let engine = self.engine.clone();
        engine.disconnect().await.map_err(to_napi_err)
    }

    /// Set the current user and load their follow/mute lists.
    #[napi]
    pub async fn login(&self, pubkey_hex: String) -> Result<()> {
        let pk = PublicKey::from_hex(&pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        engine.login(pk).await.map_err(to_napi_err)
    }

    // ─── Profile ──────────────────────────────────────────────

    /// Fetch a user profile (kind 0 metadata).
    #[napi]
    pub async fn fetch_profile(&self, pubkey_hex: String) -> Result<Option<NapiUserProfile>> {
        let pk = PublicKey::from_hex(&pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        let profile = engine.fetch_profile(pk).await.map_err(to_napi_err)?;
        Ok(profile.map(NapiUserProfile::from))
    }

    /// Batch-fetch user profiles. Returns a JSON string:
    /// `{ [pubkeyHex]: { name, display_name, about, picture, banner, nip05, lud16, website, pubkey } }`.
    ///
    /// Called from `/api/profile/batch` to fetch multiple profiles in a single
    /// relay subscription rather than N individual requests.
    #[napi]
    pub async fn fetch_profiles_json(&self, pubkey_hexes: Vec<String>) -> Result<String> {
        let pubkeys: Vec<PublicKey> = pubkey_hexes
            .iter()
            .filter_map(|hex| PublicKey::from_hex(hex).ok())
            .collect();
        let engine = self.engine.clone();
        let profiles = engine.fetch_profiles(&pubkeys).await.map_err(to_napi_err)?;
        serde_json::to_string(&profiles).map_err(to_napi_err)
    }

    // ─── Follow List (NIP-02) ─────────────────────────────────

    /// Fetch follow list. Returns pubkey hex strings.
    #[napi]
    pub async fn fetch_follow_list(&self, pubkey_hex: String) -> Result<Vec<String>> {
        let pk = PublicKey::from_hex(&pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        engine.fetch_follow_list(pk).await.map_err(to_napi_err)
    }

    /// Follow a user (publishes updated kind 3).
    #[napi]
    pub async fn follow_user(&self, target_pubkey_hex: String) -> Result<()> {
        let pk = PublicKey::from_hex(&target_pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        engine.follow_user(pk).await.map_err(to_napi_err)
    }

    /// Unfollow a user (publishes updated kind 3).
    #[napi]
    pub async fn unfollow_user(&self, target_pubkey_hex: String) -> Result<()> {
        let pk = PublicKey::from_hex(&target_pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        engine.unfollow_user(pk).await.map_err(to_napi_err)
    }

    // ─── Feed & Timeline ──────────────────────────────────────

    /// Get recommended feed (X-algorithm ranking). Returns scored posts.
    #[napi]
    pub async fn get_recommended_feed(&self, limit: u32) -> Result<Vec<NapiScoredPost>> {
        let engine = self.engine.clone();
        let scored = engine
            .get_recommended_feed(limit as usize)
            .await
            .map_err(to_napi_err)?;
        Ok(scored.into_iter().map(NapiScoredPost::from).collect())
    }

    /// Fetch timeline events. Returns JSON strings (one per event).
    ///
    /// `author_pubkeys` — optional pubkey hex filter.
    /// `since_secs` — optional unix timestamp (seconds).
    #[napi]
    pub async fn fetch_timeline(
        &self,
        author_pubkeys: Option<Vec<String>>,
        since_secs: Option<f64>,
        limit: u32,
    ) -> Result<Vec<String>> {
        let authors: Option<Vec<PublicKey>> = author_pubkeys.map(|pks| {
            pks.iter()
                .filter_map(|hex| PublicKey::from_hex(hex).ok())
                .collect()
        });
        let since = since_secs.map(|s| Timestamp::from(s as u64));
        let engine = self.engine.clone();
        let events = engine
            .fetch_timeline(authors.as_deref(), since, limit as usize)
            .await
            .map_err(to_napi_err)?;
        events
            .iter()
            .map(|e| serde_json::to_string(e).map_err(to_napi_err))
            .collect()
    }

    /// Fetch engagement data (likes, reposts, replies, zaps) for events.
    /// `event_id_hexes` — array of event ID hex strings.
    /// Returns a JSON object `{ [eventIdHex]: NapiEngagementData }`.
    #[napi]
    pub async fn fetch_engagement_data(
        &self,
        event_id_hexes: Vec<String>,
    ) -> Result<String> {
        let event_ids: Vec<EventId> = event_id_hexes
            .iter()
            .filter_map(|hex| EventId::from_hex(hex).ok())
            .collect();
        let engine = self.engine.clone();
        let data = engine
            .fetch_engagement_data(&event_ids)
            .await
            .map_err(to_napi_err)?;
        serde_json::to_string(&data).map_err(to_napi_err)
    }

    // ─── Publishing ───────────────────────────────────────────

    /// Publish a text note (kind 1). Returns event ID hex.
    #[napi]
    pub async fn publish_note(&self, content: String) -> Result<String> {
        let engine = self.engine.clone();
        let eid = engine
            .publish_note(&content, vec![])
            .await
            .map_err(to_napi_err)?;
        Ok(eid.to_hex())
    }

    /// React to an event (NIP-25). Returns reaction event ID hex.
    #[napi]
    pub async fn react(
        &self,
        event_id_hex: String,
        author_pubkey_hex: String,
        reaction: String,
    ) -> Result<String> {
        let eid = EventId::from_hex(&event_id_hex).map_err(to_napi_err)?;
        let pk = PublicKey::from_hex(&author_pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        let result = engine.react(eid, pk, &reaction).await.map_err(to_napi_err)?;
        Ok(result.to_hex())
    }

    /// Repost an event (NIP-18). Takes full event JSON. Returns repost event ID hex.
    #[napi]
    pub async fn repost(&self, event_json: String) -> Result<String> {
        let event: Event = Event::from_json(&event_json).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        let result = engine.repost(&event).await.map_err(to_napi_err)?;
        Ok(result.to_hex())
    }

    /// Delete an event (NIP-09). Returns deletion event ID hex.
    #[napi]
    pub async fn delete_event(
        &self,
        event_id_hex: String,
        reason: Option<String>,
    ) -> Result<String> {
        let eid = EventId::from_hex(&event_id_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        let result = engine
            .delete_event(eid, reason.as_deref())
            .await
            .map_err(to_napi_err)?;
        Ok(result.to_hex())
    }

    // ─── DMs (NIP-17) ─────────────────────────────────────────

    /// Send an encrypted DM via NIP-17 gift wrapping.
    #[napi]
    pub async fn send_dm(&self, recipient_hex: String, content: String) -> Result<()> {
        let pk = PublicKey::from_hex(&recipient_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        engine.send_dm(pk, &content).await.map_err(to_napi_err)
    }

    /// Fetch DMs as JSON strings.
    #[napi]
    pub async fn fetch_dms(
        &self,
        since_secs: Option<f64>,
        limit: u32,
    ) -> Result<Vec<String>> {
        let since = since_secs.map(|s| Timestamp::from(s as u64));
        let engine = self.engine.clone();
        let events = engine
            .fetch_dms(since, limit as usize)
            .await
            .map_err(to_napi_err)?;
        events
            .iter()
            .map(|e| serde_json::to_string(e).map_err(to_napi_err))
            .collect()
    }

    // ─── Search (NIP-50) ──────────────────────────────────────

    /// Full-text search. Returns event JSON strings.
    #[napi]
    pub async fn search(&self, query: String, limit: u32) -> Result<Vec<String>> {
        let engine = self.engine.clone();
        let events = engine
            .search(&query, limit as usize)
            .await
            .map_err(to_napi_err)?;
        events
            .iter()
            .map(|e| serde_json::to_string(e).map_err(to_napi_err))
            .collect()
    }

    // ─── Local DB Write ───────────────────────────────────────

    /// Store a raw Nostr event directly into nostrdb.
    ///
    /// `event_json` — full NIP-01 event as a JSON string (must include `sig`).
    ///
    /// Returns `true` if the event was newly saved, `false` if it was a
    /// duplicate or superseded by a newer replaceable event.
    ///
    /// Called from `/api/ingest` to persist browser-received relay events
    /// so the recommendation engine can rank them without a relay round-trip.
    #[napi]
    pub async fn store_event(&self, event_json: String) -> Result<bool> {
        let event: Event = Event::from_json(&event_json).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        engine.store_event(event).await.map_err(to_napi_err)
    }

    // ─── Local DB Query ───────────────────────────────────────

    /// Query the local nostrdb cache without hitting relays.
    /// `filter_json` — Nostr filter as JSON string.
    /// Returns event JSON strings.
    #[napi]
    pub async fn query_local(&self, filter_json: String) -> Result<Vec<String>> {
        let filter: Filter =
            Filter::from_json(&filter_json).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        let events = engine.query_local(filter).await.map_err(to_napi_err)?;
        events
            .iter()
            .map(|e| serde_json::to_string(e).map_err(to_napi_err))
            .collect()
    }

    // ─── Personalization ──────────────────────────────────────

    /// Mark a post as "not interested" for recommendation filtering.
    #[napi]
    pub async fn mark_not_interested(
        &self,
        event_id: String,
        author_pubkey: String,
    ) -> Result<()> {
        let engine = self.engine.clone();
        engine
            .mark_not_interested(&event_id, &author_pubkey)
            .await;
        Ok(())
    }

    /// Record an engagement action (`like`, `repost`, `reply`) for personalization.
    #[napi]
    pub async fn record_engagement(
        &self,
        action: String,
        author_pubkey: String,
    ) -> Result<()> {
        let engine = self.engine.clone();
        engine
            .record_engagement(&action, &author_pubkey)
            .await;
        Ok(())
    }

    // ─── Custom Emoji (NIP-30) ────────────────────────────────

    /// Fetch custom emoji set. Returns array of `[shortcode, url]` pairs.
    #[napi]
    pub async fn fetch_emoji_set(&self, pubkey_hex: String) -> Result<Vec<Vec<String>>> {
        let pk = PublicKey::from_hex(&pubkey_hex).map_err(to_napi_err)?;
        let engine = self.engine.clone();
        let emojis = engine.fetch_emoji_set(pk).await.map_err(to_napi_err)?;
        Ok(emojis
            .into_iter()
            .map(|(shortcode, url)| vec![shortcode, url])
            .collect())
    }

    // ─── Utilities ────────────────────────────────────────────

    /// Get relay connection statistics.
    #[napi]
    pub async fn connection_stats(&self) -> Result<NapiConnectionStats> {
        let engine = self.engine.clone();
        let stats = engine.connection_stats().await;
        Ok(NapiConnectionStats {
            connected_relays: stats.connected_relays as u32,
            total_relays: stats.total_relays as u32,
        })
    }

    /// Format a unix timestamp in Japanese relative format (e.g. "3分", "2時間").
    #[napi]
    pub fn format_timestamp(&self, timestamp: f64) -> String {
        format_timestamp_ja(timestamp as u64)
    }
}
