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

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Weak};
use std::time::Duration;

use base64::Engine as _;
use nostr::nips::nip09::EventDeletionRequest;
use nostr::nips::nip25::ReactionTarget;
use nostr::prelude::*;
use nostr::{TagKind, UnsignedEvent};
use nostr_ndb::NdbDatabase;
use nostr_sdk::prelude::*;
use tokio::sync::{Mutex, RwLock};

use crate::config::NuruNuruConfig;
use crate::error::{NuruNuruError, Result};
use crate::filters;
use crate::mls::MlsManager;
use crate::recommendation::RecommendationEngine;
use crate::relay;
use crate::types::*;

/// Shared buffer type for SSE subscriptions.
///
/// `Arc<Mutex<VecDeque<String>>>` — each active `/api/stream` subscription
/// gets one buffer. The background task holds a `Weak` reference; when the
/// strong reference is dropped (via `unsubscribe_stream`) the task exits.
type SubBuffer = Arc<Mutex<VecDeque<String>>>;

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

    // SSE streaming subscriptions: sub_id → event buffer
    subscription_buffers: Arc<Mutex<HashMap<String, SubBuffer>>>,

    /// MLS manager — bound lazily by `login()`, re-bound by `mls_reset()`.
    /// `Arc` so MLS calls run without holding the lock (allowing concurrent
    /// `mls_reset` / `set_mls_db_key`). `None` for read-only/anonymous clients.
    mls: RwLock<Option<Arc<MlsManager>>>,

    /// SQLCipher key for the MLS DB. `None` => legacy unencrypted (pre-#178).
    mls_db_key: RwLock<Option<[u8; 32]>>,
}

impl NuruNuruEngine {
    /// Create a new engine with the given signer and configuration.
    ///
    /// The signer can be `Keys` (private key), or a custom `NostrSigner`
    /// implementation for NIP-07/NIP-46 bridges.
    pub async fn new(signer: impl IntoNostrSigner, config: NuruNuruConfig) -> Result<Arc<Self>> {
        // Build nostr-sdk client.
        //
        // MLS-only mode: when db_path is empty, skip nostrdb initialization and
        // use the default in-memory client backend. This is useful for iOS MLS-only
        // integration where timeline/profile are pure Swift and Rust is used only
        // for MLS cryptographic state.
        let client = if config.db_path.is_empty() {
            tracing::info!(
                "[NuruNuruEngine] MLS-only mode: nostrdb disabled (in-memory client backend)"
            );
            Client::builder().signer(signer).build()
        } else {
            // Open nostrdb at the configured path.
            let ndb = NdbDatabase::open(&config.db_path)
                .map_err(|e| NuruNuruError::DatabaseError(e.to_string()))?;

            Client::builder().signer(signer).database(ndb).build()
        };

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

        // MLS manager is bound lazily by `login()` so the user pubkey is
        // *always* known before any MLS state is created. Read-only / anonymous
        // clients keep `None`.

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
            subscription_buffers: Arc::new(Mutex::new(HashMap::new())),
            mls: RwLock::new(None),
            mls_db_key: RwLock::new(None),
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

    /// Set the user's pubkey and load follow/mute lists. Binds the MLS
    /// manager too; use [`Self::mls_reset`] when switching identities so
    /// prior on-disk MLS state is wiped first.
    pub async fn login(&self, pubkey: PublicKey) -> Result<()> {
        {
            let mut pk = self.user_pubkey.write().await;
            *pk = Some(pubkey);
        }
        self.bind_mls_for_pubkey(pubkey).await?;

        // Load follow list, mute list in parallel
        let (follows, mutes) =
            tokio::join!(self.fetch_follow_list(pubkey), self.fetch_mute_list(pubkey),);

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

    async fn bind_mls_for_pubkey(&self, pubkey: PublicKey) -> Result<()> {
        if self.config.mls_db_path.is_empty() {
            return Ok(());
        }

        let pubkey_hex = pubkey.to_hex();
        let db_key = *self.mls_db_key.read().await;
        let had_key = db_key.is_some();
        let result = match db_key {
            Some(key) => MlsManager::new_with_key(&self.config.mls_db_path, &pubkey_hex, key),
            None => MlsManager::new(&self.config.mls_db_path, &pubkey_hex),
        };

        match result {
            Ok(manager) => {
                *self.mls.write().await = Some(Arc::new(manager));
                Ok(())
            }
            Err(e) => {
                *self.mls.write().await = None;
                if had_key {
                    // Issue #181 B5: when an encryption key was explicitly
                    // supplied and the DB still failed to open, this is
                    // almost always an unmigrated plaintext file. We MUST
                    // surface this so the app layer can purge + retry
                    // instead of silently dropping to mls=None and showing
                    // an empty Talk UI with no diagnostic.
                    tracing::error!(
                        "[NuruNuruEngine] MLS encrypted bind failed (likely plaintext legacy DB at {}): {e}",
                        self.config.mls_db_path
                    );
                    Err(NuruNuruError::MlsError(format!(
                        "MLS encrypted bind failed: {e}. The on-disk DB may be \
                         unencrypted (pre-#181). Purge it and retry."
                    )))
                } else {
                    tracing::warn!("[NuruNuruEngine] MLS unencrypted bind failed (non-fatal): {e}");
                    Ok(())
                }
            }
        }
    }

    /// Set the SQLCipher key for the MLS DB. Call before `login()`; calling
    /// after login drops the in-memory manager so the next bind picks it up.
    pub async fn set_mls_db_key(&self, key: [u8; 32]) {
        *self.mls_db_key.write().await = Some(key);
        *self.mls.write().await = None;
    }

    /// Issue #181: atomic "set key + login" used by FFI ctors that need to
    /// inject the SQLCipher key before the first `bind_mls_for_pubkey`
    /// invocation. The legacy `set_mls_db_key` setter cannot retrofit a
    /// client whose ctor already called `login()` internally.
    pub async fn login_with_mls_db_key(
        &self,
        pubkey: PublicKey,
        key: [u8; 32],
    ) -> Result<()> {
        *self.mls_db_key.write().await = Some(key);
        *self.mls.write().await = None;
        self.login(pubkey).await
    }

    /// Issue #181 B7: returns `true` if the currently-bound MLS manager
    /// uses SQLCipher; `false` for legacy unencrypted DB; `None` if no MLS
    /// manager is bound (e.g. read-only with no key, or bind failed).
    pub async fn mls_is_encrypted(&self) -> Option<bool> {
        self.mls.read().await.as_ref().map(|m| m.is_encrypted())
    }

    /// Issue #178 #11: wipe + reopen the MLS DB for a new identity (logout/
    /// login change on the same device).
    pub async fn mls_reset(&self, new_pubkey: PublicKey) -> Result<()> {
        if self.config.mls_db_path.is_empty() {
            return Err(NuruNuruError::MlsError(
                "mls_reset: no mls_db_path configured".to_string(),
            ));
        }

        // Drop the existing manager first so the file handle is released
        // before we unlink it.
        *self.mls.write().await = None;

        let db_path = self.config.mls_db_path.clone();
        // Issue #183: also wipe the replay cache sidecar so a new identity
        // does not inherit cached Kind-445 wrappers from the previous user.
        let replay_path = crate::mls::replay_cache_path_for(&db_path);
        let sidecars = [
            db_path.clone(),
            format!("{db_path}-wal"),
            format!("{db_path}-shm"),
            format!("{db_path}-journal"),
            replay_path.clone(),
            format!("{replay_path}-wal"),
            format!("{replay_path}-shm"),
            format!("{replay_path}-journal"),
        ];
        for path in &sidecars {
            match std::fs::remove_file(path) {
                Ok(()) => tracing::info!("[MLS] mls_reset removed {}", path),
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => tracing::warn!("[MLS] mls_reset could not remove {}: {}", path, e),
            }
        }

        // Update the in-memory identity too so subsequent calls see the new
        // pubkey even if `login()` is skipped.
        *self.user_pubkey.write().await = Some(new_pubkey);
        self.bind_mls_for_pubkey(new_pubkey).await
    }

    /// Get the current user's public key.
    pub async fn current_pubkey(&self) -> Option<PublicKey> {
        *self.user_pubkey.read().await
    }

    pub async fn client_signer_for_tests(&self) -> Result<Arc<dyn NostrSigner>> {
        self.client
            .signer()
            .await
            .map_err(|e| NuruNuruError::MlsError(format!("test signer unavailable: {e}")))
    }

    /// Test-only view into the immutable config (no `pub` setters; see
    /// `set_mls_db_key` / `mls_reset` for the runtime mutation surface).
    pub fn config_for_tests(&self) -> &NuruNuruConfig {
        &self.config
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
        let event = self.latest_contact_list_event(pubkey).await?;
        Ok(event
            .map(|e| Self::contact_pubkeys_from_tags(e.tags.iter()))
            .unwrap_or_default())
    }

    async fn latest_contact_list_event(&self, pubkey: PublicKey) -> Result<Option<Event>> {
        let filter = filters::follow_list_filter(pubkey);
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(10))
            .await?;
        Ok(events.into_iter().max_by_key(|e| e.created_at))
    }

    fn contact_pubkeys_from_tags<'a>(tags: impl Iterator<Item = &'a Tag>) -> Vec<String> {
        let mut seen = HashSet::new();
        let mut follows = Vec::new();
        for tag in tags {
            if tag.kind() == TagKind::p() {
                if let Some(pubkey) = tag.content().map(|s| s.to_string()) {
                    if seen.insert(pubkey.clone()) {
                        follows.push(pubkey);
                    }
                }
            }
        }
        follows
    }

    fn merge_contact_tags(
        latest: Option<&Event>,
        fallback_follows: &[String],
        target_pubkey: PublicKey,
        follow: bool,
    ) -> Vec<Tag> {
        let target_hex = target_pubkey.to_hex();
        let mut seen_p = HashSet::new();
        let mut tags = Vec::new();

        if let Some(event) = latest {
            for tag in event.tags.iter() {
                if tag.kind() == TagKind::p() {
                    if let Some(pk) = tag.content().map(|s| s.to_string()) {
                        if pk == target_hex && !follow {
                            continue;
                        }
                        if seen_p.insert(pk) {
                            tags.push(tag.clone());
                        }
                    }
                } else {
                    // Preserve non-p tags from the latest NIP-02 event.
                    tags.push(tag.clone());
                }
            }
        } else {
            for hex in fallback_follows {
                if hex == &target_hex && !follow {
                    continue;
                }
                if seen_p.insert(hex.clone()) {
                    if let Ok(tag) = Tag::parse(["p", hex.as_str()]) {
                        tags.push(tag);
                    }
                }
            }
        }

        if follow && seen_p.insert(target_hex.clone()) {
            if let Ok(tag) = Tag::parse(["p", target_hex.as_str()]) {
                tags.push(tag);
            }
        }
        tags
    }

    /// Follow a user (publish updated kind 3).
    pub async fn follow_user(&self, target_pubkey: PublicKey) -> Result<()> {
        let my_pk = self
            .current_pubkey()
            .await
            .ok_or(NuruNuruError::NoSigningMethod)?;

        let latest = self.latest_contact_list_event(my_pk).await?;
        let current_follows = latest
            .as_ref()
            .map(|e| Self::contact_pubkeys_from_tags(e.tags.iter()))
            .unwrap_or_default();

        if current_follows.contains(&target_pubkey.to_hex()) {
            return Err(NuruNuruError::AlreadyFollowing);
        }

        // NIP-02 kind 3 is a replaceable *complete* contact list. Publish the
        // latest list with the new target appended, preserving non-p tags.
        let tags = Self::merge_contact_tags(latest.as_ref(), &current_follows, target_pubkey, true);
        let builder = EventBuilder::new(Kind::ContactList, "").tags(tags);
        self.client.send_event_builder(builder).await?;

        // Update local state
        let mut fl = self.follow_list.write().await;
        for pk in current_follows {
            fl.insert(pk);
        }
        fl.insert(target_pubkey.to_hex());

        Ok(())
    }

    /// Unfollow a user (publish updated kind 3).
    pub async fn unfollow_user(&self, target_pubkey: PublicKey) -> Result<()> {
        let my_pk = self
            .current_pubkey()
            .await
            .ok_or(NuruNuruError::NoSigningMethod)?;

        let latest = self.latest_contact_list_event(my_pk).await?;
        let current_follows = latest
            .as_ref()
            .map(|e| Self::contact_pubkeys_from_tags(e.tags.iter()))
            .unwrap_or_default();

        let target_hex = target_pubkey.to_hex();
        let tags =
            Self::merge_contact_tags(latest.as_ref(), &current_follows, target_pubkey, false);
        let builder = EventBuilder::new(Kind::ContactList, "").tags(tags);
        self.client.send_event_builder(builder).await?;

        // Update local state
        let mut fl = self.follow_list.write().await;
        for pk in current_follows {
            if pk != target_hex {
                fl.insert(pk);
            }
        }
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
        let tl_filters = filters::timeline_filters(authors, since, None, limit, limit / 2);

        let mut all_events = Vec::new();
        for f in tl_filters {
            let events = self.client.fetch_events(f, Duration::from_secs(15)).await?;
            all_events.extend(events);
        }

        // Sort by created_at descending
        all_events.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        all_events.truncate(limit);

        Ok(all_events)
    }

    /// Fast timeline fetch for first paint. Fetches only displayable note/repost
    /// events with a short timeout; UI layers enrich metadata after rendering.
    pub async fn fetch_timeline_fast(
        &self,
        authors: Option<&[PublicKey]>,
        since: Option<Timestamp>,
        limit: usize,
        timeout: Duration,
    ) -> Result<Vec<Event>> {
        let tl_filters = filters::timeline_filters(authors, since, None, limit, (limit / 3).max(1));
        let mut all_events = Vec::new();
        let mut handles = Vec::new();
        for filter in tl_filters {
            let client = self.client.clone();
            handles.push(tokio::spawn(async move {
                client.fetch_events(filter, timeout).await
            }));
        }
        for handle in handles {
            if let Ok(Ok(events)) = handle.await {
                all_events.extend(events.into_iter());
            }
        }
        let mut seen = HashSet::new();
        all_events.retain(|e| seen.insert(e.id));
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

    /// Shared recommendation pipeline.
    ///
    /// Returns a map of `event_id → Event` (for later resolution) and
    /// the scored+ranked posts.  Used by both the metadata API and the
    /// timeline API so the heavy fetch logic lives in one place.
    async fn build_recommendation_candidates(
        &self,
        limit: usize,
        user_geohash: Option<&str>,
    ) -> Result<(HashMap<String, Event>, Vec<ScoredPost>)> {
        let follow_list = self.follow_list.read().await.clone();
        let muted = self.muted_pubkeys.read().await.clone();
        let second_degree = self.second_degree_follows.read().await.clone();
        let engagement_history = self.engagement_history.read().await.clone();
        let not_interested = self.not_interested_posts.read().await.clone();
        let author_scores = self.author_scores.read().await.clone();

        // Build author list for network fetch (follow + 2nd-degree, capped at 200)
        let author_pks: Vec<PublicKey> = follow_list
            .iter()
            .chain(second_degree.iter())
            .take(200)
            .filter_map(|hex| PublicKey::from_hex(hex).ok())
            .collect();

        let since_48h = filters::since_hours_ago(48);
        let since_1h = filters::since_hours_ago(1);

        // Parallel fetch: network candidates (follow+2nd-degree, 48h) and
        // out-of-network viral candidates (global, last 1h).
        let (network_result, viral_result) = tokio::join!(
            self.fetch_timeline(
                if author_pks.is_empty() {
                    None
                } else {
                    Some(&author_pks)
                },
                Some(since_48h),
                limit * 2,
            ),
            self.fetch_timeline(None, Some(since_1h), limit),
        );

        let network_events = network_result.unwrap_or_default();
        let viral_events = viral_result.unwrap_or_default();

        // Merge and deduplicate by event ID
        let mut seen_ids: HashSet<EventId> = HashSet::new();
        let mut all_events: Vec<Event> =
            Vec::with_capacity(network_events.len() + viral_events.len());
        for event in network_events.into_iter().chain(viral_events.into_iter()) {
            if seen_ids.insert(event.id) {
                all_events.push(event);
            }
        }

        // Collect unique authors for profile batch fetch
        let unique_authors: Vec<PublicKey> = all_events
            .iter()
            .map(|e| e.pubkey)
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();

        // Parallel: engagement data + author profiles
        let event_ids: Vec<EventId> = all_events.iter().map(|e| e.id).collect();
        let (engagements_result, profiles_result) = tokio::join!(
            self.fetch_engagement_data(&event_ids),
            self.fetch_profiles(&unique_authors),
        );
        let engagements = engagements_result.unwrap_or_default();
        let profiles = profiles_result.unwrap_or_default();

        // Build event map (event_id hex → Event) for resolution after scoring
        let event_map: HashMap<String, Event> = all_events
            .iter()
            .map(|e| (e.id.to_hex(), e.clone()))
            .collect();

        let posts: Vec<(String, String, u64)> = all_events
            .iter()
            .map(|e| (e.id.to_hex(), e.pubkey.to_hex(), e.created_at.as_secs()))
            .collect();

        let author_stats: HashMap<String, u64> = HashMap::new();

        let scored = self.recommendation.rank_feed(
            &posts,
            &engagements,
            &follow_list,
            &second_degree,
            &HashSet::new(),
            &engagement_history,
            &profiles,
            &muted,
            &not_interested,
            &author_scores,
            user_geohash,
            &author_stats,
            limit,
        );

        Ok((event_map, scored))
    }

    /// Get a recommended feed using the X-algorithm-inspired ranking.
    /// Returns scored post metadata (event_id, pubkey, score, created_at).
    pub async fn get_recommended_feed(&self, limit: usize) -> Result<Vec<ScoredPost>> {
        let (_, scored) = self.build_recommendation_candidates(limit, None).await?;
        Ok(scored)
    }

    /// Get full Event objects ordered by recommendation score.
    ///
    /// `user_geohash` — the user's geohash from app settings (e.g. `"xn76u"`).
    /// Pass `None` to skip proximity boosting.
    pub async fn get_recommended_events_ordered(
        &self,
        limit: usize,
        user_geohash: Option<String>,
    ) -> Result<Vec<Event>> {
        let (event_map, scored) = self
            .build_recommendation_candidates(limit, user_geohash.as_deref())
            .await?;

        let ordered = scored
            .into_iter()
            .filter_map(|sp| event_map.get(&sp.event_id).cloned())
            .collect();

        Ok(ordered)
    }

    // ─── DMs (NIP-17) ──────────────────────────────────────────

    /// Send an encrypted DM using NIP-17 gift wrapping.
    /// All seal/wrap layers are handled by `nostr-sdk`.
    pub async fn send_dm(&self, recipient: PublicKey, content: &str) -> Result<()> {
        self.client.send_private_msg(recipient, content, []).await?;
        Ok(())
    }

    /// Fetch DM events (gift-wrapped, kind 1059).
    pub async fn fetch_dms(&self, since: Option<Timestamp>, limit: usize) -> Result<Vec<Event>> {
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
    pub async fn react(
        &self,
        event_id: EventId,
        author: PublicKey,
        reaction: &str,
    ) -> Result<EventId> {
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

    /// Fetch events from connected relays using an arbitrary filter.
    ///
    /// Unlike `query_local` (which reads only the local nostrdb cache),
    /// this method issues a real REQ to all connected relays and waits
    /// up to `timeout_secs` seconds for responses.
    pub async fn fetch_events_raw(&self, filter: Filter, timeout_secs: u64) -> Result<Vec<Event>> {
        let events = self
            .client
            .fetch_events(filter, Duration::from_secs(timeout_secs))
            .await?;
        Ok(events.into_iter().collect())
    }

    /// Fetch events from *specific* relays only.
    ///
    /// Temporarily adds any relay not yet known, sends a REQ, then returns.
    /// Useful for MLS group messages where Kind-445 may be published to
    /// relays outside the default JP set.
    pub async fn fetch_events_from_relays(
        &self,
        filter: Filter,
        relay_urls: Vec<String>,
        timeout_secs: u64,
    ) -> Result<Vec<Event>> {
        // Ensure target relays are known and start connection attempts concurrently.
        // One bad relay must not serialize/hold the whole fetch path.
        let mut connect_tasks = Vec::new();
        for url in &relay_urls {
            if let Ok(relay_url) = RelayUrl::parse(url) {
                let client = self.client.clone();
                connect_tasks.push(tokio::spawn(async move {
                    let _ = client.add_relay(relay_url.clone()).await;
                    let _ = tokio::time::timeout(
                        Duration::from_millis(900),
                        client.connect_relay(relay_url),
                    )
                    .await;
                }));
            }
        }
        for task in connect_tasks {
            let _ = task.await;
        }

        let urls: Vec<RelayUrl> = relay_urls
            .iter()
            .filter_map(|u| RelayUrl::parse(u).ok())
            .collect();

        let events = self
            .client
            .fetch_events_from(urls, filter, Duration::from_secs(timeout_secs))
            .await?;
        Ok(events.into_iter().collect())
    }

    /// Send any `EventBuilder` — used by the FFI's generic `publish_event`.
    pub async fn send_builder(&self, builder: EventBuilder) -> Result<EventId> {
        let output = self.client.send_event_builder(builder).await?;
        Ok(output.val)
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

    /// Publish a note to specific relays only (NIP-70 relay selection).
    pub async fn publish_note_to_relays(
        &self,
        content: &str,
        tags: Vec<Tag>,
        relay_urls: Vec<String>,
    ) -> Result<EventId> {
        let mut builder = EventBuilder::text_note(content);
        for tag in tags {
            builder = builder.tag(tag);
        }
        let urls: Vec<nostr::types::Url> =
            relay_urls.iter().filter_map(|u| u.parse().ok()).collect();
        let event = self.client.sign_event_builder(builder).await?;
        let output = self.client.send_event_to(urls, &event).await?;
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

    // ─── SSE Streaming (Step 8) ─────────────────────────────────

    /// Start a persistent relay subscription and return its ID.
    ///
    /// Events matching `filter` are buffered in memory.  Call `poll_subscription`
    /// to drain the buffer, and `unsubscribe_stream` to cancel.
    ///
    /// Internally spawns a background tokio task that listens to the nostr-sdk
    /// notification broadcast and appends matching events to a shared buffer.
    /// The task exits automatically when `unsubscribe_stream` drops the buffer.
    pub async fn subscribe_stream(&self, filter: Filter) -> Result<String> {
        // Acquire notification receiver BEFORE subscribing so we don't miss
        // events that arrive immediately after the REQ is sent.
        let mut notif_rx = self.client.notifications();

        // Subscribe — nostr-sdk sends REQ to all connected relays.
        let output = self
            .client
            .subscribe(filter, None)
            .await
            .map_err(|e| NuruNuruError::RelayError(e.to_string()))?;
        let sub_id = output.val.to_string();

        // Create event buffer and register it in the map.
        let buf: SubBuffer = Arc::new(Mutex::new(VecDeque::new()));
        let buf_weak: Weak<Mutex<VecDeque<String>>> = Arc::downgrade(&buf);
        {
            let mut map = self.subscription_buffers.lock().await;
            map.insert(sub_id.clone(), buf);
        }

        let sub_id_clone = sub_id.clone();

        // Background task: forward matching events into the buffer.
        // Uses a Weak reference — when the strong Arc is dropped by
        // `unsubscribe_stream`, `upgrade()` returns None and the task exits.
        tokio::spawn(async move {
            loop {
                match notif_rx.recv().await {
                    Ok(notification) => {
                        if let RelayPoolNotification::Event {
                            subscription_id,
                            event,
                            ..
                        } = notification
                        {
                            if subscription_id.to_string() == sub_id_clone {
                                if let Some(buf) = buf_weak.upgrade() {
                                    if let Ok(json) = serde_json::to_string(&*event) {
                                        let mut guard = buf.lock().await;
                                        if guard.len() < 2000 {
                                            guard.push_back(json);
                                        }
                                    }
                                } else {
                                    // Buffer was dropped — subscription cancelled.
                                    break;
                                }
                            }
                        } else if let RelayPoolNotification::Shutdown = notification {
                            break;
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        tracing::warn!(
                            "[subscribe_stream] Missed {} notifications for sub {}",
                            n,
                            sub_id_clone
                        );
                        // Continue — don't break on lag.
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        });

        Ok(sub_id)
    }

    /// Drain up to `max_count` buffered events from a streaming subscription.
    ///
    /// Returns event JSON strings (empty vec when the buffer is empty or the
    /// subscription ID is unknown).
    pub async fn poll_subscription(&self, sub_id: &str, max_count: usize) -> Vec<String> {
        let map = self.subscription_buffers.lock().await;
        if let Some(buf) = map.get(sub_id) {
            let buf = buf.clone(); // clone Arc so we can drop the map lock
            drop(map);
            let mut guard = buf.lock().await;
            let count = max_count.min(guard.len());
            guard.drain(..count).collect()
        } else {
            vec![]
        }
    }

    /// Cancel a streaming subscription and clean up all resources.
    ///
    /// Drops the buffer (background task detects this via `Weak::upgrade` →
    /// `None` and exits on the next iteration), then sends CLOSE to relays.
    pub async fn unsubscribe_stream(&self, sub_id: &str) -> Result<()> {
        {
            let mut map = self.subscription_buffers.lock().await;
            map.remove(sub_id); // drops Arc → background task will exit
        }
        // Send CLOSE to relays.
        self.client.unsubscribe(&SubscriptionId::new(sub_id)).await;
        Ok(())
    }

    // ─── MLS / NIP-EE Delegation ─────────────────────────────────────────

    /// Acquire a handle to the bound MLS manager, or error if MLS is not
    /// initialised. Clones the `Arc` and releases the read lock so the MLS
    /// call can run without blocking concurrent `mls_reset` / configuration.
    async fn require_mls(&self) -> Result<Arc<MlsManager>> {
        self.mls
            .read()
            .await
            .as_ref()
            .cloned()
            .ok_or_else(|| NuruNuruError::MlsError("MLS not initialised for this client".into()))
    }

    /// Generate a fresh MLS KeyPackage (Kind 30443, Marmot MIP-00).
    ///
    /// Uses the engine's configured relay list for the `relays` tag.
    pub async fn mls_create_key_package(&self) -> Result<KeyPackageEventData> {
        let relay_urls: Vec<RelayUrl> = self.client.relays().await.keys().cloned().collect();
        self.require_mls()
            .await?
            .create_key_package_event(&relay_urls)
    }

    /// Strictly validate a KeyPackage event JSON (MIP-00).
    ///
    /// This validates required tags/capabilities and verifies `i` (KeyPackageRef)
    /// against decoded content via MDK parser.
    pub async fn mls_validate_key_package_event(&self, key_package_event_json: &str) -> Result<()> {
        self.require_mls()
            .await?
            .validate_key_package_event(key_package_event_json)
    }

    /// Delete consumed KeyPackage private/init-key material from local MLS storage (MIP-02).
    pub async fn mls_delete_consumed_key_package_from_event_json(
        &self,
        key_package_event_json: &str,
    ) -> Result<()> {
        self.require_mls()
            .await?
            .delete_consumed_key_package_from_event_json(key_package_event_json)
    }

    /// Delete consumed KeyPackage private/init-key material using the exact hash_ref returned at creation.
    pub async fn mls_delete_consumed_key_package_by_hash_ref(&self, hash_ref: &[u8]) -> Result<()> {
        self.require_mls()
            .await?
            .delete_consumed_key_package_by_hash_ref(hash_ref)
    }

    /// Return MLS group IDs (hex) that need self-update per MDK state tracking.
    pub async fn mls_groups_needing_self_update(&self, threshold_secs: u64) -> Result<Vec<String>> {
        self.require_mls()
            .await?
            .groups_needing_self_update(threshold_secs)
    }

    /// Create a new MLS group.
    pub async fn mls_create_group(
        &self,
        name: String,
        admin_pubkeys: Vec<String>,
        relays: Vec<String>,
    ) -> Result<MlsGroupInfo> {
        self.require_mls()
            .await?
            .create_group(name, admin_pubkeys, relays)
    }

    /// Add a member to a group using their Kind-30443 KeyPackage event JSON.
    ///
    /// The returned `AddMemberResult.welcome_event_data.gift_wrapped_event_json`
    /// contains a NIP-59 gift-wrapped event (Kind 1059) ready for `publish_raw_event`.
    ///
    /// Flow (per Marmot MIP-02):
    /// 1. MLS add_members → Commit + Welcome rumor (Kind 444, unsigned)
    /// 2. Gift-wrap the rumor: Kind 444 → Kind 13 seal → Kind 1059 gift-wrap
    /// 3. Return both commit and gift-wrapped welcome
    pub async fn mls_add_member(
        &self,
        group_id_hex: &str,
        key_package_event_json: &str,
    ) -> Result<AddMemberResult> {
        let mut result = self
            .require_mls()
            .await?
            .add_member(group_id_hex, key_package_event_json)?;

        // Apply NIP-59 gift-wrap to the Welcome rumor if present
        if !result.welcome_event_data.inner_rumor_json.is_empty()
            && !result.welcome_event_data.recipient_pubkey.is_empty()
        {
            let recipient_pk = PublicKey::from_hex(&result.welcome_event_data.recipient_pubkey)
                .map_err(|e| NuruNuruError::MlsError(format!("Invalid recipient pubkey: {e}")))?;

            let rumor: UnsignedEvent =
                serde_json::from_str(&result.welcome_event_data.inner_rumor_json).map_err(|e| {
                    NuruNuruError::MlsError(format!("Invalid welcome rumor JSON: {e}"))
                })?;

            let signer =
                self.client.signer().await.map_err(|e| {
                    NuruNuruError::MlsError(format!("No signer for gift-wrap: {e}"))
                })?;

            let gift_wrap = EventBuilder::gift_wrap(&signer, &recipient_pk, rumor, [])
                .await
                .map_err(|e| NuruNuruError::MlsError(format!("gift_wrap failed: {e}")))?;

            // Defensive check: the generated gift-wrap must be decryptable by the
            // recipient signer when the recipient is this engine's own user. This
            // catches local-recipient wrapping regressions without requiring the
            // sender to know a remote member's private key.
            if self.current_pubkey().await == Some(recipient_pk) {
                nostr::nips::nip59::extract_rumor(&signer, &gift_wrap)
                    .await
                    .map_err(|e| {
                        NuruNuruError::MlsError(format!("gift_wrap self-check failed: {e}"))
                    })?;
            }

            result.welcome_event_data.gift_wrapped_event_json = gift_wrap.as_json();
        }

        Ok(result)
    }

    /// Encrypt an application message for a group (Kind 445).
    pub async fn mls_create_message(
        &self,
        group_id_hex: &str,
        content: &str,
    ) -> Result<EncryptedMessageData> {
        self.require_mls()
            .await?
            .create_message(group_id_hex, content)
    }

    /// Decrypt/process an incoming Kind-445 event.
    pub async fn mls_process_message(
        &self,
        group_id_hex: &str,
        event_json: &str,
    ) -> Result<DecryptedMessage> {
        self.require_mls()
            .await?
            .process_message(group_id_hex, event_json)
    }

    /// Structured processing result for an incoming Kind-445 event.
    pub async fn mls_process_message_result(
        &self,
        group_id_hex: &str,
        event_json: &str,
    ) -> Result<MlsProcessResult> {
        self.require_mls()
            .await?
            .process_message_result(group_id_hex, event_json)
    }

    fn normalize_marmot_welcome_rumor(mut rumor: UnsignedEvent) -> Result<UnsignedEvent> {
        if rumor.kind != nostr::Kind::MlsWelcome && rumor.kind.as_u16() != 10_444 {
            return Ok(rumor);
        }

        // In the nostr crate MlsWelcome follows the historical NIP-104 value
        // (444), but Marmot MIP-02/WhiteNoise may use kind 10444 for the inner
        // Welcome rumor carried by gift-wrap. MDK 0.7.x validation currently
        // checks against nostr::Kind::MlsWelcome, so normalize that protocol
        // alias here before passing the rumor to MDK.
        rumor.kind = nostr::Kind::MlsWelcome;

        // MDK 0.7.x strictly validates the transport tags on kind:444 before
        // decoding the MLS Welcome. Some WhiteNoise/Marmot relays contain older
        // or variant rumors where these tags are missing, malformed, or include
        // an empty `client` tag. Normalize the envelope tags only; the Welcome
        // payload in `content` is not modified.
        let mut tags: Vec<Tag> = Vec::new();
        let mut has_valid_event_ref = false;

        for tag in rumor.tags.to_vec() {
            let slice = tag.as_slice();
            match slice.first().map(|v| v.as_str()) {
                Some("relays") => {
                    // Replace relays with a known-valid relay below. Keeping a
                    // malformed relay URL causes MDK validation to reject the
                    // otherwise decryptable Welcome.
                }
                Some("client") => {
                    // MDK accepts client only when non-empty. Drop empty/invalid
                    // client tags instead of failing interop.
                    if slice.get(1).is_some_and(|v| !v.is_empty()) {
                        tags.push(tag);
                    }
                }
                Some("e") => {
                    if slice.get(1).is_some_and(|v| !v.is_empty()) {
                        has_valid_event_ref = true;
                        tags.push(tag);
                    }
                }
                Some("encoding") => {
                    // Always canonicalize to exactly encoding=base64 below.
                }
                _ => tags.push(tag),
            }
        }

        tags.push(
            Tag::parse(["relays", "wss://yabu.me"]).map_err(|e| {
                NuruNuruError::MlsError(format!("normalize_welcome: relays tag: {e}"))
            })?,
        );
        tags.push(Tag::parse(["encoding", "base64"]).map_err(|e| {
            NuruNuruError::MlsError(format!("normalize_welcome: encoding tag: {e}"))
        })?);

        if !has_valid_event_ref {
            let event_ref = if let Some(id) = rumor.id {
                id.to_hex()
            } else {
                // We only need a non-empty event reference for MDK 0.7.x MIP-02
                // structural validation. The real wrapper id is supplied by the
                // surrounding 1059 event in the common path.
                "0000000000000000000000000000000000000000000000000000000000000000".to_string()
            };
            tags.push(
                Tag::parse(["e", event_ref.as_str()]).map_err(|e| {
                    NuruNuruError::MlsError(format!("normalize_welcome: e tag: {e}"))
                })?,
            );
        }

        rumor.tags = nostr::Tags::from_list(tags);
        rumor.ensure_id();
        Ok(rumor)
    }

    fn welcome_debug_summary(wrapper_event_id: &nostr::EventId, rumor: &UnsignedEvent) -> String {
        let tag_names: Vec<String> = rumor
            .tags
            .iter()
            .map(|tag| tag.as_slice().first().cloned().unwrap_or_default())
            .collect();
        let tag_count = tag_names.len();
        let has_relays = rumor.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.first().is_some_and(|v| v == "relays")
                && slice.len() > 1
                && slice
                    .iter()
                    .skip(1)
                    .all(|url| nostr::RelayUrl::parse(url).is_ok())
        });
        let has_encoding = rumor.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.len() >= 2 && slice[0] == "encoding" && slice[1].eq_ignore_ascii_case("base64")
        });
        let has_e = rumor.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.first().is_some_and(|v| v == "e") && slice.get(1).is_some_and(|v| !v.is_empty())
        });
        let empty_client = rumor.tags.iter().any(|tag| {
            let slice = tag.as_slice();
            slice.first().is_some_and(|v| v == "client")
                && !slice.get(1).is_some_and(|v| !v.is_empty())
        });
        let content_b64 = base64::engine::general_purpose::STANDARD
            .decode(rumor.content.as_bytes())
            .map(|bytes| format!("ok:{}", bytes.len()))
            .unwrap_or_else(|_| "invalid".to_string());
        format!(
            "wrapper={} rumorId={} kind={} tags={} names=[{}] relays={} encoding={} e={} emptyClient={} contentLen={} contentB64={}",
            wrapper_event_id.to_hex(),
            rumor.id.map(|id| id.to_hex()).unwrap_or_else(|| "none".to_string()),
            rumor.kind.as_u16(),
            tag_count,
            tag_names.join(","),
            has_relays,
            has_encoding,
            has_e,
            empty_client,
            rumor.content.len(),
            content_b64
        )
    }

    /// Process an incoming Welcome event and join the group.
    ///
    /// Accepts Kind 1059 (NIP-59 gift-wrap, Marmot MIP-02), Kind 444 (legacy
    /// signed/unsigned Welcome), or a raw unwrapped rumor JSON.
    pub async fn mls_process_welcome(&self, welcome_event_json: &str) -> Result<MlsGroupInfo> {
        let (wrapper_event_id, rumor) = self.unwrap_welcome_input(welcome_event_json).await?;
        tracing::info!(
            "[MLS] normalized welcome: {}",
            Self::welcome_debug_summary(&wrapper_event_id, &rumor)
        );
        let mls = self.require_mls().await?;
        mls.process_welcome_rumor(&wrapper_event_id, &rumor)
            .map_err(|e| {
                NuruNuruError::MlsError(format!(
                    "{}; {}",
                    e,
                    Self::welcome_debug_summary(&wrapper_event_id, &rumor)
                ))
            })
    }

    /// Issue #178 #4: stage a Welcome for accept/decline UX. Accepts the same
    /// input formats as [`Self::mls_process_welcome`].
    pub async fn mls_preview_welcome(&self, welcome_event_json: &str) -> Result<PendingWelcome> {
        let (wrapper_event_id, rumor) = self.unwrap_welcome_input(welcome_event_json).await?;
        let mls = self.require_mls().await?;
        mls.preview_welcome_rumor(&wrapper_event_id, &rumor)
            .map_err(|e| {
                NuruNuruError::MlsError(format!(
                    "{}; {}",
                    e,
                    Self::welcome_debug_summary(&wrapper_event_id, &rumor)
                ))
            })
    }

    /// Accept a previously previewed Welcome by its inner Welcome rumor event id (Kind 444).
    pub async fn mls_accept_welcome(&self, welcome_event_id_hex: &str) -> Result<PendingWelcome> {
        let id = EventId::from_hex(welcome_event_id_hex)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid welcome event id: {e}")))?;
        self.require_mls().await?.accept_pending_welcome(&id)
    }

    /// Decline a previously previewed Welcome by its inner Welcome rumor event id (Kind 444).
    pub async fn mls_decline_welcome(&self, welcome_event_id_hex: &str) -> Result<()> {
        let id = EventId::from_hex(welcome_event_id_hex)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid welcome event id: {e}")))?;
        self.require_mls().await?.decline_pending_welcome(&id)
    }

    /// List Welcomes that have been previewed but not yet accepted/declined.
    pub async fn mls_get_pending_welcomes(&self) -> Result<Vec<PendingWelcome>> {
        self.require_mls().await?.get_pending_welcomes()
    }

    /// Normalise any welcome input (kind:1059 gift-wrap, signed kind:444, raw
    /// rumor JSON) into `(wrapper_event_id, rumor)` for MDK.
    async fn unwrap_welcome_input(
        &self,
        welcome_event_json: &str,
    ) -> Result<(EventId, UnsignedEvent)> {
        if let Ok(event) = serde_json::from_str::<nostr::Event>(welcome_event_json) {
            if event.kind == nostr::Kind::GiftWrap {
                let signer =
                    self.client.signer().await.map_err(|e| {
                        NuruNuruError::MlsError(format!("No signer for unwrap: {e}"))
                    })?;
                let unwrapped = nostr::nips::nip59::extract_rumor(&signer, &event)
                    .await
                    .map_err(|e| {
                        NuruNuruError::MlsError(format!("gift-wrap unwrap failed: {e}"))
                    })?;
                let raw_kind = unwrapped.rumor.kind.as_u16();
                if unwrapped.rumor.kind != nostr::Kind::MlsWelcome && raw_kind != 10_444 {
                    return Err(NuruNuruError::MlsError(format!(
                        "not_mls_welcome_rumor: kind={raw_kind}"
                    )));
                }
                let rumor = Self::normalize_marmot_welcome_rumor(unwrapped.rumor)?;
                return Ok((event.id, rumor));
            }
            if event.kind == nostr::Kind::MlsWelcome || event.kind.as_u16() == 10_444 {
                let rumor = UnsignedEvent {
                    id: Some(event.id),
                    pubkey: event.pubkey,
                    created_at: event.created_at,
                    kind: event.kind,
                    tags: event.tags,
                    content: event.content,
                };
                let rumor = Self::normalize_marmot_welcome_rumor(rumor)?;
                return Ok((event.id, rumor));
            }
        }

        let rumor: UnsignedEvent = serde_json::from_str(welcome_event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid welcome JSON: {e}")))?;
        let mut rumor = Self::normalize_marmot_welcome_rumor(rumor)?;
        let wrapper_event_id = rumor.id();
        Ok((wrapper_event_id, rumor))
    }

    /// Retrieve decrypted message history for a group from MDK's local SQLite.
    pub async fn mls_get_message_history(
        &self,
        group_id_hex: &str,
        limit: u64,
    ) -> Result<Vec<DecryptedMessage>> {
        self.require_mls()
            .await?
            .get_message_history(group_id_hex, limit)
    }

    /// List all MLS groups the user belongs to.
    pub async fn mls_list_groups(&self) -> Result<Vec<MlsGroupInfo>> {
        self.require_mls().await?.list_groups()
    }

    /// Get metadata for a single MLS group.
    pub async fn mls_get_group_info(&self, group_id_hex: &str) -> Result<MlsGroupInfo> {
        self.require_mls().await?.get_group_info(group_id_hex)
    }

    // NOTE: mls_self_demote() will be added when mdk-core releases self_demote().
    // Currently in mdk-core main branch but not in 0.7.1.

    /// Leave a group (publishes self-removal commit event data).
    /// Uses SelfRemove proposal internally (Marmot MIP-03).
    pub async fn mls_leave_group(&self, group_id_hex: &str) -> Result<EncryptedMessageData> {
        self.require_mls().await?.leave_group(group_id_hex)
    }

    /// Remove a member from a group.
    pub async fn mls_remove_member(
        &self,
        group_id_hex: &str,
        member_pubkey: &str,
    ) -> Result<EncryptedMessageData> {
        self.require_mls()
            .await?
            .remove_member(group_id_hex, member_pubkey)
    }

    /// Merge a pending commit **only after** the matching commit event was
    /// successfully published. Issue #178 #2: calling this on every receive
    /// poll forks the group if a prior publish silently failed.
    pub async fn mls_merge_pending_commit(&self, group_id_hex: &str) -> Result<()> {
        self.require_mls().await?.merge_pending_commit(group_id_hex)
    }

    /// Create a recovery self-update commit event for stuck pending proposals.
    pub async fn mls_create_recovery_commit(
        &self,
        group_id_hex: &str,
    ) -> Result<EncryptedMessageData> {
        self.require_mls()
            .await?
            .create_recovery_commit(group_id_hex)
    }

    /// Clear (rollback) pending commit for recovery from stuck MLS state.
    pub async fn mls_clear_pending_commit(&self, group_id_hex: &str) -> Result<()> {
        self.require_mls().await?.clear_pending_commit(group_id_hex)
    }

    // ─── Issue #183: peer-epoch catch-up ──────────────────────────────────

    /// Issue #183: replay all available Kind-445 wrappers for a group
    /// (caller-supplied candidates + locally cached) to catch the local
    /// MDK epoch up to the peer's epoch.
    ///
    /// Receive-path semantics: this method NEVER calls
    /// `clear_pending_commit` or `merge_pending_commit`. PR #180's receive
    /// invariants are preserved (AC3).
    pub async fn mls_catch_up_to_peer(
        &self,
        group_id_hex: &str,
        candidate_events_json: Vec<String>,
    ) -> Result<crate::types::MlsCatchUpReport> {
        self.require_mls()
            .await?
            .catch_up_to_peer(group_id_hex, &candidate_events_json)
    }

    /// Issue #183: prune Kind-445 wrappers older than the replay cache TTL
    /// (30 days). Safe to call on any cadence — no-op when the cache does
    /// not yet exist.
    pub async fn mls_prune_replay_cache(&self) -> Result<u64> {
        self.require_mls().await?.prune_replay_cache()
    }

    /// Issue #183 (diagnostic): number of cached Kind-445 wrappers for a group.
    pub async fn mls_replay_cache_size(&self, group_id_hex: &str) -> Result<u64> {
        self.require_mls()
            .await?
            .replay_cache_size(group_id_hex)
    }

    // ─── MLS subscription helpers (issue #178 #9, #10) ────────────────────

    /// Issue #178 #9: subscribe to my Welcomes (kind:1059 #p=self). Returns
    /// the engine sub_id; poll via [`Self::poll_subscription`].
    pub async fn mls_subscribe_welcomes(&self, since: Option<Timestamp>) -> Result<String> {
        let my_pk = self
            .current_pubkey()
            .await
            .ok_or(NuruNuruError::NoSigningMethod)?;
        let mut filter = Filter::new().kind(Kind::GiftWrap).pubkey(my_pk);
        if let Some(ts) = since {
            filter = filter.since(ts);
        }
        self.subscribe_stream(filter).await
    }

    /// Issue #178 #10: subscribe to KeyPackage rotations (kind:30443) from
    /// the given contacts. Empty list = all kind:30443 (expensive).
    pub async fn mls_subscribe_keypackage_rotations(
        &self,
        contact_pubkeys: Vec<PublicKey>,
    ) -> Result<String> {
        let mut filter = Filter::new().kind(Kind::from(30443u16));
        if !contact_pubkeys.is_empty() {
            filter = filter.authors(contact_pubkeys);
        }
        self.subscribe_stream(filter).await
    }
}

#[cfg(test)]
mod giftwrap_engine_tests {
    use super::*;
    use crate::config::NuruNuruConfig;
    use nostr::{EventBuilder, Keys, Kind, Tag};
    use std::time::{SystemTime, UNIX_EPOCH};

    fn unique_db_path(name: &str) -> String {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let mut path = std::env::temp_dir();
        path.push(format!(
            "nurunuru_engine_giftwrap_test_{}_{}_{}.sqlite3",
            name,
            std::process::id(),
            nanos
        ));
        path.to_string_lossy().to_string()
    }

    fn test_config(mls_db_path: String) -> NuruNuruConfig {
        let mut cfg = NuruNuruConfig::default();
        cfg.db_path = String::new();
        cfg.mls_db_path = mls_db_path;
        cfg
    }

    async fn new_logged_in_engine(keys: &Keys, name: &str) -> Arc<NuruNuruEngine> {
        let cfg = test_config(unique_db_path(name));
        let engine = NuruNuruEngine::new(keys.clone(), cfg).await.unwrap();
        engine.login(keys.public_key()).await.unwrap();
        engine
    }

    #[tokio::test]
    async fn engine_signer_can_roundtrip_direct_gift_wrap() {
        let alice_keys = Keys::generate();
        let bob_keys = Keys::generate();

        let alice = new_logged_in_engine(&alice_keys, "alice_engine_signer").await;
        let bob = new_logged_in_engine(&bob_keys, "bob_engine_signer").await;

        let rumor = EventBuilder::new(Kind::Custom(444), "hello-welcome")
            .tags(vec![Tag::parse([
                "p",
                bob_keys.public_key().to_hex().as_str(),
            ])
            .unwrap()])
            .build(alice_keys.public_key());

        let alice_signer = alice.client.signer().await.unwrap();
        let bob_signer = bob.client.signer().await.unwrap();

        let gift =
            EventBuilder::gift_wrap(&alice_signer, &bob_keys.public_key(), rumor.clone(), [])
                .await
                .unwrap();
        let extracted = nostr::nips::nip59::extract_rumor(&bob_signer, &gift)
            .await
            .unwrap();

        assert_eq!(u16::from(extracted.rumor.kind), u16::from(rumor.kind));
        assert_eq!(extracted.rumor.pubkey, rumor.pubkey);
        assert_eq!(extracted.rumor.tags.to_vec(), rumor.tags.to_vec());
        assert_eq!(extracted.rumor.content, rumor.content);
    }
}
