//! UniFFI bindings for NuruNuru Core.
//!
//! This crate exposes a simplified, FFI-safe interface for:
//! - **Kotlin** (Android via JNA)
//! - **Swift** (iOS/macOS via C ABI)
//!
//! All async operations are bridged through a per-client Tokio runtime.
//!
//! ## Initialization sequence (Android)
//!
//! ```kotlin
//! // 1. Once at app startup (NuruNuruApp.onCreate):
//! initEngine("${context.filesDir}/nostrdb_ndb")
//!
//! // 2a. Internal signer (private key available):
//! val client = NuruNuruClient(secretKeyHex)
//!
//! // 2b. External signer (NIP-07, Amber, NIP-46):
//! val client = NuruNuruClient.newReadOnly(pubkeyHex)
//! ```

use std::sync::{Arc, RwLock};

use nurunuru_core::config::NuruNuruConfig;
use nurunuru_core::types::*;
use nurunuru_core::NuruNuruEngine;

uniffi::setup_scaffolding!("nurunuru");

// ─── Global DB path ────────────────────────────────────────────────────────

/// Database path set by `init_engine()`. Must be initialised before any
/// `NuruNuruClient` is created.
///
/// NOTE: We allow updates so callers can recover from a bad initial path
/// without restarting the app process.
static GLOBAL_DB_PATH: RwLock<Option<String>> = RwLock::new(None);

/// One-time global initialisation. Call this once in `Application.onCreate()`
/// before creating any `NuruNuruClient`.
///
/// `db_path` is the directory where nostrdb stores its data files.
/// Recommended value: `"${context.filesDir}/nostrdb_ndb"`.
///
/// Subsequent calls overwrite the previous value.
#[uniffi::export]
pub fn init_engine(db_path: String) -> Result<(), NuruNuruFfiError> {
    let mut guard = GLOBAL_DB_PATH
        .write()
        .map_err(|e| NuruNuruFfiError::RuntimeError(format!("db path lock poisoned: {e}")))?;
    *guard = Some(db_path);
    Ok(())
}

fn get_db_path() -> Result<String, NuruNuruFfiError> {
    let guard = GLOBAL_DB_PATH
        .read()
        .map_err(|e| NuruNuruFfiError::RuntimeError(format!("db path lock poisoned: {e}")))?;
    guard.clone().ok_or_else(|| {
        NuruNuruFfiError::RuntimeError(
            "init_engine() must be called before creating a NuruNuruClient".to_string(),
        )
    })
}

// ─── Client ────────────────────────────────────────────────────────────────

/// FFI-safe wrapper around the NuruNuru engine.
/// Holds a Tokio runtime for blocking-async bridging.
#[derive(uniffi::Object)]
pub struct NuruNuruClient {
    runtime: tokio::runtime::Runtime,
    engine: Arc<NuruNuruEngine>,
    /// Present only for internal-signer clients (created via `new(secret_key_hex)`).
    /// Used for NIP-04/44 encrypt/decrypt without exposing the key through the engine.
    secret_key: Option<nostr::SecretKey>,
}

#[uniffi::export]
impl NuruNuruClient {
    /// Create a signing client from a private key (hex or nsec).
    ///
    /// Requires `init_engine()` to have been called first.
    #[uniffi::constructor]
    pub fn new(secret_key_hex: String) -> Result<Arc<Self>, NuruNuruFfiError> {
        let db_path = get_db_path()?;

        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| NuruNuruFfiError::RuntimeError(e.to_string()))?;

        let (engine, secret_key) = rt.block_on(async {
            let keys = nostr::Keys::parse(&secret_key_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
            let sk = keys.secret_key().clone();

            let mut config = NuruNuruConfig::default();
            config.mls_db_path = format!("{}_mls.sqlite3", db_path);
            #[cfg(target_os = "ios")]
            {
                // iOS MLS-only integration: disable nostrdb initialization.
                // Timeline/profile/follow are handled by pure Swift.
                config.db_path = String::new();
            }
            #[cfg(not(target_os = "ios"))]
            {
                config.db_path = db_path;
            }

            let engine = NuruNuruEngine::new(keys, config)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            // Set the user's public key so follow-list, MLS, and recommendation
            // queries operate with the correct identity from the start.
            let pk = nostr::Keys::parse(&secret_key_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?
                .public_key();
            engine
                .login(pk)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            Ok::<_, NuruNuruFfiError>((engine, sk))
        })?;

        Ok(Arc::new(Self {
            runtime: rt,
            engine,
            secret_key: Some(secret_key),
        }))
    }

    /// Issue #181: signing client constructor that *also* injects the
    /// SQLCipher key for the MLS DB before the engine's internal
    /// `login()` runs. This is the **only** way to get an encrypted MLS
    /// open on first launch — the legacy `set_mls_db_key` setter races
    /// the ctor's internal `login()` call and is effectively a no-op for
    /// the initial bind.
    ///
    /// `mls_db_key` MUST be exactly 32 bytes (derive via
    /// [`derive_mls_db_key_from_secret`] or platform keystore).
    ///
    /// Requires `init_engine()` to have been called first.
    #[uniffi::constructor]
    pub fn new_with_mls_db_key(
        secret_key_hex: String,
        mls_db_key: Vec<u8>,
    ) -> Result<Arc<Self>, NuruNuruFfiError> {
        let key_bytes: [u8; 32] = mls_db_key.try_into().map_err(|v: Vec<u8>| {
            NuruNuruFfiError::EngineError(format!(
                "new_with_mls_db_key: key must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        let db_path = get_db_path()?;

        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| NuruNuruFfiError::RuntimeError(e.to_string()))?;

        let (engine, secret_key) = rt.block_on(async {
            let keys = nostr::Keys::parse(&secret_key_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
            let sk = keys.secret_key().clone();

            let mut config = NuruNuruConfig::default();
            config.mls_db_path = format!("{}_mls.sqlite3", db_path);
            #[cfg(target_os = "ios")]
            {
                config.db_path = String::new();
            }
            #[cfg(not(target_os = "ios"))]
            {
                config.db_path = db_path;
            }

            let engine = NuruNuruEngine::new(keys, config)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            let pk = nostr::Keys::parse(&secret_key_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?
                .public_key();
            engine
                .login_with_mls_db_key(pk, key_bytes)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            Ok::<_, NuruNuruFfiError>((engine, sk))
        })?;

        Ok(Arc::new(Self {
            runtime: rt,
            engine,
            secret_key: Some(secret_key),
        }))
    }

    /// Create a read-only client for users who sign externally (NIP-07 / Amber / NIP-46).
    ///
    /// The client can fetch timeline and profile data normally. Signing happens
    /// out-of-band in the app layer via `create_unsigned_note` + `publish_raw_event`.
    ///
    /// Requires `init_engine()` to have been called first.
    #[uniffi::constructor]
    pub fn new_read_only(pubkey_hex: String) -> Result<Arc<Self>, NuruNuruFfiError> {
        let db_path = get_db_path()?;

        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| NuruNuruFfiError::RuntimeError(e.to_string()))?;

        let engine = rt.block_on(async {
            // Use an ephemeral keypair for relay-level authentication.
            // The logical user identity is set via login() below so that
            // follow-list and recommendation queries use the correct pubkey.
            let keys = nostr::Keys::generate();

            let mut config = NuruNuruConfig::default();
            config.mls_db_path = format!("{}_mls.sqlite3", db_path);
            #[cfg(target_os = "ios")]
            {
                // iOS MLS-only integration: disable nostrdb initialization.
                // Timeline/profile/follow are handled by pure Swift.
                config.db_path = String::new();
            }
            #[cfg(not(target_os = "ios"))]
            {
                config.db_path = db_path;
            }

            let engine = NuruNuruEngine::new(keys, config)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            // Set the user's actual public key.
            // follow/mute fetches may time out (relays not yet connected), but
            // login() handles that gracefully and returns Ok(()).
            let pk = nostr::PublicKey::from_hex(&pubkey_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
            engine
                .login(pk)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            Ok::<_, NuruNuruFfiError>(engine)
        })?;

        Ok(Arc::new(Self {
            runtime: rt,
            engine,
            secret_key: None,
        }))
    }

    /// Issue #181: read-only client constructor that *also* injects the
    /// SQLCipher key for the MLS DB before the engine's internal
    /// `login()` runs. Mirrors [`Self::new_with_mls_db_key`] for the
    /// external-signer (Amber / NIP-46) path.
    ///
    /// For external signers, derive `mls_db_key` from a pubkey-scoped
    /// random secret stored in the OS keystore (NOT HKDF, since there is
    /// no nsec to derive from in the Rust process).
    ///
    /// Requires `init_engine()` to have been called first.
    #[uniffi::constructor]
    pub fn new_read_only_with_mls_db_key(
        pubkey_hex: String,
        mls_db_key: Vec<u8>,
    ) -> Result<Arc<Self>, NuruNuruFfiError> {
        let key_bytes: [u8; 32] = mls_db_key.try_into().map_err(|v: Vec<u8>| {
            NuruNuruFfiError::EngineError(format!(
                "new_read_only_with_mls_db_key: key must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        let db_path = get_db_path()?;

        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| NuruNuruFfiError::RuntimeError(e.to_string()))?;

        let engine = rt.block_on(async {
            let keys = nostr::Keys::generate();

            let mut config = NuruNuruConfig::default();
            config.mls_db_path = format!("{}_mls.sqlite3", db_path);
            #[cfg(target_os = "ios")]
            {
                config.db_path = String::new();
            }
            #[cfg(not(target_os = "ios"))]
            {
                config.db_path = db_path;
            }

            let engine = NuruNuruEngine::new(keys, config)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            let pk = nostr::PublicKey::from_hex(&pubkey_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
            engine
                .login_with_mls_db_key(pk, key_bytes)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

            Ok::<_, NuruNuruFfiError>(engine)
        })?;

        Ok(Arc::new(Self {
            runtime: rt,
            engine,
            secret_key: None,
        }))
    }

    // ─── Relay lifecycle ───────────────────────────────────────────────────

    /// Connect to all configured relays.
    pub fn connect(&self) {
        self.runtime.block_on(self.engine.connect());
    }

    /// Disconnect from all relays.
    pub fn disconnect(&self) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.disconnect())
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── Identity ──────────────────────────────────────────────────────────

    /// Set the current user's public key and load follow/mute lists.
    pub fn login(&self, pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.login(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── Publishing ────────────────────────────────────────────────────────

    /// Publish a text note (kind 1). Returns the event ID hex.
    /// For internal signers only — the engine signs with the stored private key.
    pub fn publish_note(&self, content: String) -> Result<String, NuruNuruFfiError> {
        let eid = self
            .runtime
            .block_on(self.engine.publish_note(&content, vec![]))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Create an **unsigned** kind-1 text note JSON for external signing.
    ///
    /// The returned JSON should be passed to the app-layer signer (NIP-07 / Amber),
    /// then the signed result given to `publish_raw_event`.
    pub fn create_unsigned_note(
        &self,
        pubkey_hex: String,
        content: String,
    ) -> Result<String, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let unsigned = nostr::EventBuilder::text_note(content).build(pk);
        serde_json::to_string(&unsigned).map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Create an **unsigned** reaction (kind 7) JSON for external signing.
    pub fn create_unsigned_reaction(
        &self,
        event_id_hex: String,
        author_pubkey_hex: String,
        emoji: String,
        creator_pubkey_hex: String,
    ) -> Result<String, NuruNuruFfiError> {
        let event_id = nostr::EventId::from_hex(&event_id_hex)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid event id: {e}")))?;
        let author = nostr::PublicKey::from_hex(&author_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let creator = nostr::PublicKey::from_hex(&creator_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let target = nostr::nips::nip25::ReactionTarget {
            event_id,
            public_key: author,
            coordinate: None,
            kind: Some(nostr::Kind::TextNote),
            relay_hint: None,
        };
        let unsigned = nostr::EventBuilder::reaction(target, &emoji).build(creator);
        serde_json::to_string(&unsigned).map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Create an **unsigned** repost (kind 6) JSON for external signing.
    pub fn create_unsigned_repost(
        &self,
        event_json: String,
        creator_pubkey_hex: String,
    ) -> Result<String, NuruNuruFfiError> {
        let event: nostr::Event = serde_json::from_str(&event_json)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid event JSON: {e}")))?;
        let creator = nostr::PublicKey::from_hex(&creator_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let unsigned = nostr::EventBuilder::repost(&event, None).build(creator);
        serde_json::to_string(&unsigned).map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Create an **unsigned** text note with tags JSON for external signing.
    pub fn create_unsigned_note_with_tags(
        &self,
        content: String,
        tags: Vec<Vec<String>>,
        creator_pubkey_hex: String,
    ) -> Result<String, NuruNuruFfiError> {
        let creator = nostr::PublicKey::from_hex(&creator_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let parsed_tags = parse_ffi_tags(tags)?;
        let mut builder = nostr::EventBuilder::text_note(&content);
        for tag in parsed_tags {
            builder = builder.tag(tag);
        }
        let unsigned = builder.build(creator);
        serde_json::to_string(&unsigned).map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Create an **unsigned** event of any kind for external signing.
    pub fn create_unsigned_event(
        &self,
        kind: u32,
        content: String,
        tags: Vec<Vec<String>>,
        creator_pubkey_hex: String,
    ) -> Result<String, NuruNuruFfiError> {
        let creator = nostr::PublicKey::from_hex(&creator_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let parsed_tags = parse_ffi_tags(tags)?;
        let event_kind = nostr::Kind::from(kind as u16);
        let mut builder = nostr::EventBuilder::new(event_kind, &content);
        for tag in parsed_tags {
            builder = builder.tag(tag);
        }
        let unsigned = builder.build(creator);
        serde_json::to_string(&unsigned).map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Publish an already-signed Nostr event JSON to all connected relays.
    ///
    /// Used by the external signer flow: the app receives an unsigned event from
    /// `create_unsigned_note`, signs it via NIP-07 / Amber, then passes the
    /// signed JSON here. Returns the event ID hex on success.
    pub fn publish_raw_event(&self, event_json: String) -> Result<String, NuruNuruFfiError> {
        let event: nostr::Event = serde_json::from_str(&event_json)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid event JSON: {e}")))?;
        let eid = self
            .runtime
            .block_on(self.engine.publish_raw_event(event))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    // ─── Timeline fetch ────────────────────────────────────────────────────

    /// Connect to relays and fetch the global timeline (Kind 1 text notes,
    /// no author filter). Returns up to `limit` events as serialised JSON
    /// strings, newest-first.
    ///
    /// Internally calls `engine.fetch_timeline(authors=None)`, which issues a
    /// REQ to all connected relays and waits up to 15 s for results.
    /// Call `connect()` first so the relays are ready.
    pub fn fetch_global_timeline(&self, limit: u32) -> Result<Vec<String>, NuruNuruFfiError> {
        self.fetch_timeline_inner(None, limit)
    }

    /// Fast global timeline for first paint. Returns raw displayable events only.
    pub fn fetch_global_timeline_fast(
        &self,
        limit: u32,
        timeout_secs: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        self.fetch_timeline_fast_inner(None, limit, timeout_secs)
    }

    /// Fetch the follow timeline for a set of authors (Kind 1 text notes).
    ///
    /// Issues a single REQ to all connected relays filtered by the given
    /// pubkey hex list.  nostrdb caches the results for subsequent
    /// `query_local` calls.
    ///
    /// Pass up to 500 pubkeys (relay REQ limit).  Callers should obtain the
    /// follow list first via `fetch_follow_list` or the local app cache.
    pub fn fetch_follow_timeline(
        &self,
        authors: Vec<String>,
        limit: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        if authors.is_empty() {
            return Ok(vec![]);
        }
        self.fetch_timeline_inner(Some(authors), limit)
    }

    /// Fast follow timeline for first paint. No engagement/profile/quote enrich.
    pub fn fetch_follow_timeline_fast(
        &self,
        authors: Vec<String>,
        limit: u32,
        timeout_secs: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        if authors.is_empty() {
            return Ok(vec![]);
        }
        self.fetch_timeline_fast_inner(Some(authors), limit, timeout_secs)
    }

    // ─── Relay fetch ───────────────────────────────────────────────────────

    /// Fetch events from connected relays using a NIP-01 JSON filter.
    ///
    /// `filter_json` must be a JSON object matching the NIP-01 filter spec:
    /// ```json
    /// {"kinds":[1],"authors":["hex..."],"limit":50,"since":1700000000}
    /// ```
    /// Tag filters use the `#<tag>` format: `{"#p":["hex..."],"#e":["id..."]}`.
    /// NIP-50 full-text search: `{"kinds":[1],"search":"query","limit":30}`.
    ///
    /// Returns serialised event JSON strings, newest-first.
    /// `timeout_secs` controls how long to wait for relay responses.
    pub fn fetch_events_from_relay(
        &self,
        filter_json: String,
        timeout_secs: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        let filter: nostr::Filter = serde_json::from_str(&filter_json)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid filter JSON: {e}")))?;

        let mut events = self
            .runtime
            .block_on(self.engine.fetch_events_raw(filter, timeout_secs as u64))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        // Sort newest-first before returning
        events.sort_by(|a, b| b.created_at.cmp(&a.created_at));

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }

    /// Fetch events from specific relays only.
    ///
    /// Automatically adds and connects to any relay not yet known.
    /// Used for MLS group messages (Kind 445) that may be published to
    /// relays outside the default set.
    pub fn fetch_events_from_relays(
        &self,
        filter_json: String,
        relay_urls: Vec<String>,
        timeout_secs: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        let filter: nostr::Filter = serde_json::from_str(&filter_json)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid filter JSON: {e}")))?;

        let mut events = self
            .runtime
            .block_on(
                self.engine
                    .fetch_events_from_relays(filter, relay_urls, timeout_secs as u64),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        events.sort_by(|a, b| b.created_at.cmp(&a.created_at));

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }

    /// Add a relay and immediately connect to it.
    pub fn add_relay(&self, url: String) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.add_relay(&url))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── Local cache ───────────────────────────────────────────────────────

    /// Query the local nostrdb cache by author pubkeys.
    ///
    /// Returns serialised JSON strings of matching kind-1 (text note) events,
    /// newest-first, up to `limit` results.
    pub fn query_local(
        &self,
        authors: Vec<String>,
        limit: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        let pubkeys: Vec<nostr::PublicKey> = authors
            .iter()
            .filter_map(|h| nostr::PublicKey::from_hex(h).ok())
            .collect();

        let since_24h = nostr::Timestamp::from(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
                .saturating_sub(86_400),
        );

        let filter = nostr::Filter::new()
            .authors(pubkeys)
            .kind(nostr::Kind::TextNote)
            .since(since_24h)
            .limit(limit as usize);

        let events = self
            .runtime
            .block_on(self.engine.query_local(filter))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }

    /// Query the local nostrdb cache for the global timeline (no author filter).
    ///
    /// Returns serialised JSON strings of kind-1 events, newest-first, up to `limit`.
    pub fn query_local_global(&self, limit: u32) -> Result<Vec<String>, NuruNuruFfiError> {
        let since_24h = nostr::Timestamp::from(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs()
                .saturating_sub(86_400),
        );

        let filter = nostr::Filter::new()
            .kind(nostr::Kind::TextNote)
            .since(since_24h)
            .limit(limit as usize);

        let events = self
            .runtime
            .block_on(self.engine.query_local(filter))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }

    // ─── Profiles ──────────────────────────────────────────────────────────

    /// Fetch a user profile (kind 0 metadata). Returns `None` if not found.
    pub fn fetch_profile(
        &self,
        pubkey_hex: String,
    ) -> Result<Option<FfiUserProfile>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let profile = self
            .runtime
            .block_on(self.engine.fetch_profile(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(profile.map(core_profile_to_ffi))
    }

    /// Batch-fetch user profiles (kind 0 metadata).
    ///
    /// For each pubkey: nostrdb is checked first; only the pubkeys not already
    /// cached are fetched from relays in a single REQ.  This is the recommended
    /// path to avoid per-profile JNI calls during timeline enrichment.
    ///
    /// Returns one `FfiUserProfile` per pubkey that was found.  Pubkeys with no
    /// profile event on any relay will be absent from the result.
    pub fn fetch_profiles(
        &self,
        pubkeys: Vec<String>,
    ) -> Result<Vec<FfiUserProfile>, NuruNuruFfiError> {
        if pubkeys.is_empty() {
            return Ok(vec![]);
        }

        let pks: Vec<nostr::PublicKey> = pubkeys
            .iter()
            .filter_map(|h| nostr::PublicKey::from_hex(h).ok())
            .collect();

        let profiles_map = self
            .runtime
            .block_on(self.engine.fetch_profiles(&pks))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        Ok(profiles_map
            .into_values()
            .map(core_profile_to_ffi)
            .collect())
    }

    // ─── Social graph ──────────────────────────────────────────────────────

    /// Fetch the follow list for a user. Returns pubkey hex strings.
    pub fn fetch_follow_list(&self, pubkey_hex: String) -> Result<Vec<String>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.fetch_follow_list(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Follow a user (publishes an updated kind-3 contact list).
    pub fn follow_user(&self, target_pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&target_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.follow_user(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Unfollow a user (publishes an updated kind-3 contact list).
    pub fn unfollow_user(&self, target_pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&target_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.unfollow_user(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── DMs (NIP-17, legacy) ──────────────────────────────────────────────

    /// Send an encrypted DM (NIP-17).
    ///
    /// **Deprecated**: Use MLS group messaging (`mls_create_message`) for new
    /// conversations.  This method is kept for backwards compatibility during
    /// the NIP-17 → NIP-EE migration period.
    #[deprecated(note = "Use mls_create_message for new conversations (NIP-EE)")]
    pub fn send_dm(&self, recipient_hex: String, content: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&recipient_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.send_dm(pk, &content))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── MLS / NIP-EE ──────────────────────────────────────────────────────

    /// Generate a fresh MLS KeyPackage and return Kind-30443 event data (Marmot MIP-00).
    ///
    /// The caller builds an unsigned Kind-30443 event via `create_unsigned_event`,
    /// signs it (internal key or Amber), then publishes via `publish_raw_event`.
    pub fn mls_create_key_package(&self) -> Result<FfiKeyPackageEventData, NuruNuruFfiError> {
        let data = self
            .runtime
            .block_on(self.engine.mls_create_key_package())
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(FfiKeyPackageEventData {
            kind: data.kind,
            content: data.content,
            tags: data.tags,
            legacy_tags: data.legacy_tags,
            d_tag: data.d_tag,
            hash_ref: data.hash_ref,
        })
    }

    /// Strictly validate a KeyPackage event JSON (MIP-00).
    ///
    /// Verifies required tags/capabilities and checks `i` tag against computed
    /// KeyPackageRef by parsing the content through MDK.
    pub fn mls_validate_key_package_event(
        &self,
        key_package_event_json: String,
    ) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(
                self.engine
                    .mls_validate_key_package_event(&key_package_event_json),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Delete consumed KeyPackage private/init-key material from local MLS storage (MIP-02).
    pub fn mls_delete_consumed_key_package_from_event_json(
        &self,
        key_package_event_json: String,
    ) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(
                self.engine
                    .mls_delete_consumed_key_package_from_event_json(&key_package_event_json),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Delete consumed KeyPackage private/init-key material using the exact hash_ref returned at creation.
    pub fn mls_delete_consumed_key_package_by_hash_ref(
        &self,
        hash_ref: Vec<u8>,
    ) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(
                self.engine
                    .mls_delete_consumed_key_package_by_hash_ref(&hash_ref),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Return Nostr group IDs (64-char hex Kind-445 `h` tag values) that need self-update.
    ///
    /// FFI/API contract: every `group_id_hex` crossing this boundary is the
    /// Nostr group id, not MDK's internal MLS storage group id. Returned values
    /// can be passed directly to `mls_create_recovery_commit(group_id_hex)`.
    pub fn mls_groups_needing_self_update(
        &self,
        threshold_secs: u64,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.mls_groups_needing_self_update(threshold_secs))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Create a new MLS group.
    pub fn mls_create_group(
        &self,
        name: String,
        admin_pubkeys: Vec<String>,
        relays: Vec<String>,
    ) -> Result<FfiMlsGroupInfo, NuruNuruFfiError> {
        let info = self
            .runtime
            .block_on(self.engine.mls_create_group(name, admin_pubkeys, relays))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_group_info_to_ffi(info))
    }

    /// Add a member to a group using their Kind-30443 KeyPackage event JSON.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    ///
    /// Returns commit (Kind 445) and gift-wrapped welcome (Kind 1059) event data.
    /// The welcome's `gift_wrapped_event_json` is ready for `publish_raw_event`.
    pub fn mls_add_member(
        &self,
        group_id_hex: String,
        key_package_event_json: String,
    ) -> Result<FfiAddMemberResult, NuruNuruFfiError> {
        let result = self
            .runtime
            .block_on(
                self.engine
                    .mls_add_member(&group_id_hex, &key_package_event_json),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(FfiAddMemberResult {
            commit_event_data: core_encrypted_msg_to_ffi(result.commit_event_data),
            welcome_event_data: FfiWelcomeEventData {
                recipient_pubkey: result.welcome_event_data.recipient_pubkey,
                gift_wrapped_event_json: result.welcome_event_data.gift_wrapped_event_json,
                inner_rumor_json: result.welcome_event_data.inner_rumor_json,
                tags: result.welcome_event_data.tags,
            },
        })
    }

    /// Encrypt an application message for a group (Kind 445 event data).
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_create_message(
        &self,
        group_id_hex: String,
        content: String,
    ) -> Result<FfiEncryptedMessageData, NuruNuruFfiError> {
        let data = self
            .runtime
            .block_on(self.engine.mls_create_message(&group_id_hex, &content))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_encrypted_msg_to_ffi(data))
    }

    /// Process an incoming Kind-445 event and return a structured result.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_process_message_result(
        &self,
        group_id_hex: String,
        event_json: String,
    ) -> Result<FfiMlsProcessResult, NuruNuruFfiError> {
        let result = self
            .runtime
            .block_on(
                self.engine
                    .mls_process_message_result(&group_id_hex, &event_json),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(match result {
            nurunuru_core::types::MlsProcessResult::ApplicationMessage(msg) => {
                FfiMlsProcessResult::Application {
                    message: FfiDecryptedMessage {
                        sender_pubkey: msg.sender_pubkey,
                        content: msg.content,
                        timestamp: msg.timestamp,
                        group_id_hex: msg.group_id_hex,
                    },
                }
            }
            nurunuru_core::types::MlsProcessResult::Commit {
                group_id_hex,
                delta,
            } => FfiMlsProcessResult::Commit {
                group_id_hex,
                added_pubkeys: delta.added_pubkeys,
                removed_pubkeys: delta.removed_pubkeys,
                epoch_after: delta.epoch_after,
            },
            nurunuru_core::types::MlsProcessResult::NeedsSelfUpdate {
                group_id_hex,
                reason,
            } => FfiMlsProcessResult::NeedsSelfUpdate {
                group_id_hex,
                reason,
            },
            nurunuru_core::types::MlsProcessResult::StateUpdate { kind } => {
                FfiMlsProcessResult::StateUpdate { kind }
            }
        })
    }

    /// Backward-compatible wrapper used by older clients.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_process_message(
        &self,
        group_id_hex: String,
        event_json: String,
    ) -> Result<FfiDecryptedMessage, NuruNuruFfiError> {
        match self.mls_process_message_result(group_id_hex, event_json)? {
            FfiMlsProcessResult::Application { message } => Ok(message),
            FfiMlsProcessResult::Commit { .. }
            | FfiMlsProcessResult::NeedsSelfUpdate { .. }
            | FfiMlsProcessResult::StateUpdate { .. } => Err(NuruNuruFfiError::MlsStateUpdate),
        }
    }

    /// Fused process+accept (back-compat). Issue #178 #4: prefer the split
    /// flow `mls_preview_welcome` → `mls_accept_welcome`/`mls_decline_welcome`.
    pub fn mls_process_welcome(
        &self,
        welcome_event_json: String,
    ) -> Result<FfiMlsGroupInfo, NuruNuruFfiError> {
        let info = self
            .runtime
            .block_on(self.engine.mls_process_welcome(&welcome_event_json))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_group_info_to_ffi(info))
    }

    /// Stage an incoming Welcome without joining. Returns the pending welcome
    /// so the UI can show "Bob invited you to Foo" before any cryptographic
    /// state is created. Follow with `mls_accept_welcome` or `mls_decline_welcome`.
    pub fn mls_preview_welcome(
        &self,
        welcome_event_json: String,
    ) -> Result<FfiPendingWelcome, NuruNuruFfiError> {
        let pending = self
            .runtime
            .block_on(self.engine.mls_preview_welcome(&welcome_event_json))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_pending_welcome_to_ffi(pending))
    }

    /// Accept a previously previewed Welcome by its **welcome event id** (kind:444 rumor id).
    /// The id is the `welcome_event_id_hex` field returned by `mls_preview_welcome`.
    pub fn mls_accept_welcome(
        &self,
        welcome_event_id_hex: String,
    ) -> Result<FfiPendingWelcome, NuruNuruFfiError> {
        let pending = self
            .runtime
            .block_on(self.engine.mls_accept_welcome(&welcome_event_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_pending_welcome_to_ffi(pending))
    }

    /// Decline a previously previewed Welcome by its **welcome event id** (kind:444 rumor id).
    pub fn mls_decline_welcome(
        &self,
        welcome_event_id_hex: String,
    ) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.mls_decline_welcome(&welcome_event_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// List Welcomes that have been previewed but not yet accepted/declined.
    pub fn mls_get_pending_welcomes(&self) -> Result<Vec<FfiPendingWelcome>, NuruNuruFfiError> {
        let pending = self
            .runtime
            .block_on(self.engine.mls_get_pending_welcomes())
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(pending
            .into_iter()
            .map(core_pending_welcome_to_ffi)
            .collect())
    }

    // ─── MLS subscriptions (issue #178 #9, #10) ───────────────────────────

    /// Issue #178 #9: subscribe to my Welcomes (kind:1059 #p=self). Returns
    /// a sub_id for `poll_live_events`/`stop_live_subscription`. `since_secs=0` means now.
    pub fn mls_subscribe_welcomes(&self, since_secs: u64) -> Result<String, NuruNuruFfiError> {
        let since = if since_secs == 0 {
            None
        } else {
            Some(nostr::Timestamp::from(since_secs))
        };
        self.runtime
            .block_on(self.engine.mls_subscribe_welcomes(since))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Issue #178 #10: subscribe to kind:30443 rotations from the given
    /// contacts (empty list = all kind:30443).
    pub fn mls_subscribe_keypackage_rotations(
        &self,
        contact_pubkeys: Vec<String>,
    ) -> Result<String, NuruNuruFfiError> {
        let pks: Vec<nostr::PublicKey> = contact_pubkeys
            .iter()
            .filter_map(|h| nostr::PublicKey::from_hex(h).ok())
            .collect();
        self.runtime
            .block_on(self.engine.mls_subscribe_keypackage_rotations(pks))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── MLS identity / encryption (issue #178 #1, #11) ───────────────────

    /// Issue #178 #1: set the 32-byte SQLCipher key. Call before `login()`.
    pub fn set_mls_db_key(&self, key: Vec<u8>) -> Result<(), NuruNuruFfiError> {
        let bytes: [u8; 32] = key.try_into().map_err(|v: Vec<u8>| {
            NuruNuruFfiError::EngineError(format!(
                "set_mls_db_key: key must be 32 bytes, got {}",
                v.len()
            ))
        })?;
        self.runtime.block_on(self.engine.set_mls_db_key(bytes));
        Ok(())
    }

    /// Issue #178 #11: wipe + reopen the MLS DB for a new identity.
    pub fn mls_reset(&self, new_pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&new_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.mls_reset(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Issue #181 B7: returns `Some(true)` when the MLS DB is currently
    /// open under SQLCipher, `Some(false)` for legacy unencrypted open,
    /// `None` when no MLS manager is bound (e.g. ctor without key, or
    /// bind failed). The app layer MUST assert `Some(true)` after
    /// constructing the client via `new_with_mls_db_key` and refuse to
    /// proceed otherwise.
    pub fn mls_is_encrypted(&self) -> Option<bool> {
        self.runtime.block_on(self.engine.mls_is_encrypted())
    }

    /// Retrieve decrypted message history for a group from MDK's local SQLite.
    /// Use this on app startup to restore history without re-processing relay events.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_get_message_history(
        &self,
        group_id_hex: String,
        limit: u64,
    ) -> Result<Vec<FfiDecryptedMessage>, NuruNuruFfiError> {
        let msgs = self
            .runtime
            .block_on(self.engine.mls_get_message_history(&group_id_hex, limit))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(msgs
            .into_iter()
            .map(|m| FfiDecryptedMessage {
                sender_pubkey: m.sender_pubkey,
                content: m.content,
                timestamp: m.timestamp,
                group_id_hex: m.group_id_hex,
            })
            .collect())
    }

    /// List all MLS groups the user belongs to.
    pub fn mls_list_groups(&self) -> Result<Vec<FfiMlsGroupInfo>, NuruNuruFfiError> {
        let groups = self
            .runtime
            .block_on(self.engine.mls_list_groups())
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(groups.into_iter().map(core_group_info_to_ffi).collect())
    }

    /// Get metadata for a single MLS group.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_get_group_info(
        &self,
        group_id_hex: String,
    ) -> Result<FfiMlsGroupInfo, NuruNuruFfiError> {
        let info = self
            .runtime
            .block_on(self.engine.mls_get_group_info(&group_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_group_info_to_ffi(info))
    }

    // NOTE: mls_self_demote() will be added when mdk-core releases self_demote().

    /// Leave a group. Returns the Kind-445 commit event data to publish.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_leave_group(
        &self,
        group_id_hex: String,
    ) -> Result<FfiEncryptedMessageData, NuruNuruFfiError> {
        let data = self
            .runtime
            .block_on(self.engine.mls_leave_group(&group_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_encrypted_msg_to_ffi(data))
    }

    /// Remove a member from a group. Returns the Kind-445 commit event data.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_remove_member(
        &self,
        group_id_hex: String,
        member_pubkey: String,
    ) -> Result<FfiEncryptedMessageData, NuruNuruFfiError> {
        let data = self
            .runtime
            .block_on(self.engine.mls_remove_member(&group_id_hex, &member_pubkey))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_encrypted_msg_to_ffi(data))
    }

    /// Merge the pending MLS commit after successfully publishing the commit event to relays.
    ///
    /// Must be called after `mls_add_member`, `mls_remove_member`, or `mls_leave_group`
    /// once the commit event has been published. Without this, subsequent operations on the
    /// same group will fail with "Can't execute operation because a pending commit exists".
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_merge_pending_commit(&self, group_id_hex: String) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.mls_merge_pending_commit(&group_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Create a recovery self-update commit event for stuck pending proposals.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_create_recovery_commit(
        &self,
        group_id_hex: String,
    ) -> Result<FfiEncryptedMessageData, NuruNuruFfiError> {
        let data = self
            .runtime
            .block_on(self.engine.mls_create_recovery_commit(&group_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_encrypted_msg_to_ffi(data))
    }

    /// Clear (rollback) pending MLS commit for recovery from stuck state.
    ///
    /// group_id_hex argument: external group id is Nostr group id, wrapper resolves to internal MLS group id.
    pub fn mls_clear_pending_commit(&self, group_id_hex: String) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.mls_clear_pending_commit(&group_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Issue #183: replay every available Kind-445 wrapper for a group
    /// (caller-supplied candidates + locally cached) to catch up the local
    /// MDK epoch to the peer's epoch.
    ///
    /// `candidate_events_json` — raw JSON of Kind-445 events the caller just
    /// fetched from relays. May overlap with the cache; deduped by event id.
    ///
    /// Returns a structured report so the app can decide whether to prompt
    /// the user to recreate the conversation when recovery is not possible
    /// (issue #183 AC2). Never calls clear_pending_commit /
    /// merge_pending_commit, preserving PR #180 receive-path semantics
    /// (AC3).
    pub fn mls_catch_up_to_peer(
        &self,
        group_id_hex: String,
        candidate_events_json: Vec<String>,
    ) -> Result<FfiMlsCatchUpReport, NuruNuruFfiError> {
        let report = self
            .runtime
            .block_on(
                self.engine
                    .mls_catch_up_to_peer(&group_id_hex, candidate_events_json),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(core_catch_up_report_to_ffi(report))
    }

    /// Issue #183: prune Kind-445 wrappers older than the 30-day TTL.
    /// Returns the number of rows removed. Best-effort: safe to call on any
    /// cadence (no-op when the cache file does not exist).
    pub fn mls_prune_replay_cache(&self) -> Result<u64, NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.mls_prune_replay_cache())
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Issue #183 diagnostic: number of cached Kind-445 wrappers for a group.
    pub fn mls_replay_cache_size(
        &self,
        group_id_hex: String,
    ) -> Result<u64, NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.mls_replay_cache_size(&group_id_hex))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── Search / Feed ─────────────────────────────────────────────────────

    /// Full-text search (NIP-50). Returns matching event ID hex strings.
    pub fn search(&self, query: String, limit: u32) -> Result<Vec<String>, NuruNuruFfiError> {
        let events = self
            .runtime
            .block_on(self.engine.search(&query, limit as usize))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(events.iter().map(|e| e.id.to_hex()).collect())
    }

    /// Get the recommended feed. Returns scored event metadata.
    pub fn get_recommended_feed(&self, limit: u32) -> Result<Vec<FfiScoredPost>, NuruNuruFfiError> {
        let scored = self
            .runtime
            .block_on(self.engine.get_recommended_feed(limit as usize))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(scored
            .into_iter()
            .map(|sp| FfiScoredPost {
                event_id: sp.event_id,
                pubkey: sp.pubkey,
                score: sp.score,
                created_at: sp.created_at,
            })
            .collect())
    }

    /// Fetch the recommended "For You" timeline.
    ///
    /// Applies the X-algorithm-inspired ranking with:
    /// - Parallel relay fetching (network candidates + viral out-of-network)
    /// - Author profile enrichment for NIP-05 quality boost
    /// - Geohash proximity boosting when `user_geohash` is provided
    ///
    /// Returns serialised event JSON strings ordered by recommendation score.
    /// `user_geohash` — optional geohash from app settings (e.g. `"xn76u"`).
    pub fn fetch_recommended_timeline(
        &self,
        limit: u32,
        user_geohash: Option<String>,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        let events = self
            .runtime
            .block_on(
                self.engine
                    .get_recommended_events_ordered(limit as usize, user_geohash),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }

    // ─── Personalisation signals ────────────────────────────────────────────

    /// Mark a post as "not interested" to suppress it from the feed.
    pub fn mark_not_interested(&self, event_id: String, author_pubkey: String) {
        self.runtime
            .block_on(self.engine.mark_not_interested(&event_id, &author_pubkey));
    }

    /// Record an engagement action (like / repost / reply) for personalisation.
    pub fn record_engagement(&self, action: String, author_pubkey: String) {
        self.runtime
            .block_on(self.engine.record_engagement(&action, &author_pubkey));
    }

    // ─── Publishing (write operations) ────────────────────────────────────

    /// Publish a text note with tags (Kind 1).
    ///
    /// `tags` is a list of tag arrays, e.g.:
    /// `[["e","<event-id>","","reply"],["p","<pubkey>"]]`
    pub fn publish_note_with_tags(
        &self,
        content: String,
        tags: Vec<Vec<String>>,
    ) -> Result<String, NuruNuruFfiError> {
        let parsed_tags = parse_ffi_tags(tags)?;
        let eid = self
            .runtime
            .block_on(self.engine.publish_note(&content, parsed_tags))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// React to an event (Kind 7, NIP-25).
    ///
    /// `emoji` is typically `"+"` (like), `"-"` (dislike), or a custom
    /// emoji shortcode.  Returns the reaction event ID hex.
    pub fn react(
        &self,
        event_id_hex: String,
        author_pubkey_hex: String,
        emoji: String,
    ) -> Result<String, NuruNuruFfiError> {
        let event_id = nostr::EventId::from_hex(&event_id_hex)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid event id: {e}")))?;
        let author = nostr::PublicKey::from_hex(&author_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let eid = self
            .runtime
            .block_on(self.engine.react(event_id, author, &emoji))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Repost an event (Kind 6, NIP-18).
    ///
    /// `event_json` must be the full serialised Nostr event JSON received from
    /// a relay (including `id`, `pubkey`, `sig`).
    /// Returns the repost event ID hex.
    pub fn repost(&self, event_json: String) -> Result<String, NuruNuruFfiError> {
        let event: nostr::Event = serde_json::from_str(&event_json)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid event JSON: {e}")))?;
        let eid = self
            .runtime
            .block_on(self.engine.repost(&event))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Delete an event (Kind 5, NIP-09).
    ///
    /// Returns the deletion event ID hex.
    pub fn delete_event(
        &self,
        event_id_hex: String,
        reason: Option<String>,
    ) -> Result<String, NuruNuruFfiError> {
        let event_id = nostr::EventId::from_hex(&event_id_hex)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("Invalid event id: {e}")))?;
        let eid = self
            .runtime
            .block_on(self.engine.delete_event(event_id, reason.as_deref()))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Generic event publisher for kinds without a dedicated method.
    ///
    /// Covers: Kind 0 (profile), Kind 3 (contacts), Kind 10000 (mute),
    /// Kind 10002 (relay list), Kind 1984 (report), Kind 1985 (label), …
    ///
    /// `tags` — list of tag arrays: `[["e","<id>"],["p","<pk>","<relay>"]]`
    /// Returns the published event ID hex.
    pub fn publish_event(
        &self,
        kind: u32,
        content: String,
        tags: Vec<Vec<String>>,
    ) -> Result<String, NuruNuruFfiError> {
        let parsed_tags = parse_ffi_tags(tags)?;
        let event_kind = nostr::Kind::from(kind as u16);

        let mut builder = nostr::EventBuilder::new(event_kind, &content);
        for tag in parsed_tags {
            builder = builder.tag(tag);
        }

        let eid = self
            .runtime
            .block_on(self.engine.send_builder(builder))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Publish a text note to specific relays only (NIP-70 relay selection).
    ///
    /// `relay_urls` is a list of `wss://...` relay URLs. Only those relays
    /// will receive the event. Returns the signed event ID hex.
    pub fn publish_note_with_tags_to_relays(
        &self,
        content: String,
        tags: Vec<Vec<String>>,
        relay_urls: Vec<String>,
    ) -> Result<String, NuruNuruFfiError> {
        let parsed_tags = parse_ffi_tags(tags)?;
        let eid = self
            .runtime
            .block_on(
                self.engine
                    .publish_note_to_relays(&content, parsed_tags, relay_urls),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Update user profile (Kind 0, NIP-01).
    ///
    /// `metadata_json` must be a JSON object with profile fields:
    /// `{"name":"...","display_name":"...","about":"...","picture":"...","nip05":"...","lud16":"..."}`
    ///
    /// Returns the published event ID hex.
    pub fn update_profile(&self, metadata_json: String) -> Result<String, NuruNuruFfiError> {
        self.publish_event(0, metadata_json, vec![])
    }

    // ─── NIP-04/44 Encryption (internal signer only) ──────────────────────

    /// NIP-04 encrypt a message for a recipient (legacy DM, Kind 4).
    ///
    /// Only available for internal-signer clients (created via `new(secret_key_hex)`).
    /// Returns the ciphertext string suitable for use as a Kind-4 event content.
    pub fn nip04_encrypt(
        &self,
        recipient_pubkey_hex: String,
        plaintext: String,
    ) -> Result<String, NuruNuruFfiError> {
        let sk = self.secret_key.as_ref().ok_or_else(|| {
            NuruNuruFfiError::EngineError(
                "nip04_encrypt requires an internal signer client".to_string(),
            )
        })?;
        let pk = nostr::PublicKey::from_hex(&recipient_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        nostr::nips::nip04::encrypt(sk, &pk, &plaintext)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("NIP-04 encrypt: {e}")))
    }

    /// NIP-04 decrypt a message from a sender (legacy DM, Kind 4).
    ///
    /// Only available for internal-signer clients.
    pub fn nip04_decrypt(
        &self,
        sender_pubkey_hex: String,
        ciphertext: String,
    ) -> Result<String, NuruNuruFfiError> {
        let sk = self.secret_key.as_ref().ok_or_else(|| {
            NuruNuruFfiError::EngineError(
                "nip04_decrypt requires an internal signer client".to_string(),
            )
        })?;
        let pk = nostr::PublicKey::from_hex(&sender_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        nostr::nips::nip04::decrypt(sk, &pk, &ciphertext)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("NIP-04 decrypt: {e}")))
    }

    /// NIP-44 encrypt a message for a recipient (NIP-17 gift-wrap, seals, etc.).
    ///
    /// Only available for internal-signer clients.
    pub fn nip44_encrypt(
        &self,
        recipient_pubkey_hex: String,
        plaintext: String,
    ) -> Result<String, NuruNuruFfiError> {
        let sk = self.secret_key.as_ref().ok_or_else(|| {
            NuruNuruFfiError::EngineError(
                "nip44_encrypt requires an internal signer client".to_string(),
            )
        })?;
        let pk = nostr::PublicKey::from_hex(&recipient_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        nostr::nips::nip44::encrypt(sk, &pk, &plaintext, nostr::nips::nip44::Version::default())
            .map_err(|e| NuruNuruFfiError::EngineError(format!("NIP-44 encrypt: {e}")))
    }

    /// NIP-44 decrypt a message from a sender.
    ///
    /// Only available for internal-signer clients.
    pub fn nip44_decrypt(
        &self,
        sender_pubkey_hex: String,
        ciphertext: String,
    ) -> Result<String, NuruNuruFfiError> {
        let sk = self.secret_key.as_ref().ok_or_else(|| {
            NuruNuruFfiError::EngineError(
                "nip44_decrypt requires an internal signer client".to_string(),
            )
        })?;
        let pk = nostr::PublicKey::from_hex(&sender_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        nostr::nips::nip44::decrypt(sk, &pk, &ciphertext)
            .map_err(|e| NuruNuruFfiError::EngineError(format!("NIP-44 decrypt: {e}")))
    }

    // ─── Live Streaming ────────────────────────────────────────────────────

    /// Start a persistent relay subscription for live events.
    ///
    /// Pass an empty `authors` vec for the global feed, or a list of pubkey
    /// hex strings for the follow timeline.
    ///
    /// Returns a subscription ID to pass to `poll_live_events` and
    /// `stop_live_subscription`.  The subscription emits Kind-1 text notes
    /// with `since = now` so only new events (posted after this call) arrive.
    pub fn start_live_subscription(
        &self,
        authors: Vec<String>,
    ) -> Result<String, NuruNuruFfiError> {
        let author_pks: Vec<nostr::PublicKey> = authors
            .iter()
            .filter_map(|h| nostr::PublicKey::from_hex(h).ok())
            .collect();

        // since = now so we only receive events posted after subscribing.
        let since = nostr::Timestamp::now();
        let mut filter = nostr::Filter::new()
            .kind(nostr::Kind::TextNote)
            .since(since);
        if !author_pks.is_empty() {
            filter = filter.authors(author_pks);
        }

        self.runtime
            .block_on(self.engine.subscribe_stream(filter))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Drain up to `max_count` buffered live events. Returns serialised JSON
    /// strings. Returns an empty vec when no new events have arrived.
    ///
    /// Safe to call on a background thread; will return immediately.
    pub fn poll_live_events(&self, sub_id: String, max_count: u32) -> Vec<String> {
        self.runtime
            .block_on(self.engine.poll_subscription(&sub_id, max_count as usize))
    }

    /// Cancel a live subscription and release all associated resources.
    pub fn stop_live_subscription(&self, sub_id: String) -> Result<(), NuruNuruFfiError> {
        self.runtime
            .block_on(self.engine.unsubscribe_stream(&sub_id))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── Diagnostics ───────────────────────────────────────────────────────

    /// Return current relay connection statistics.
    pub fn connection_stats(&self) -> FfiConnectionStats {
        let stats = self.runtime.block_on(self.engine.connection_stats());
        FfiConnectionStats {
            connected_relays: stats.connected_relays as u32,
            total_relays: stats.total_relays as u32,
        }
    }

    /// Format a Unix timestamp as a Japanese relative string (e.g. "3分").
    pub fn format_timestamp(&self, timestamp: u64) -> String {
        format_timestamp_ja(timestamp)
    }
}

// ─── Non-exported impl helpers ─────────────────────────────────────────────

/// Convert `Vec<Vec<String>>` tag lists from the FFI boundary into
/// `Vec<nostr::Tag>`.  Invalid tag arrays are silently skipped.
fn parse_ffi_tags(raw: Vec<Vec<String>>) -> Result<Vec<nostr::Tag>, NuruNuruFfiError> {
    let mut out = Vec::with_capacity(raw.len());
    for parts in raw {
        if parts.is_empty() {
            continue;
        }
        match nostr::Tag::parse(parts) {
            Ok(t) => out.push(t),
            Err(e) => {
                // Warn but don't fail the whole publish — a bad tag shouldn't
                // prevent the event from being sent.
                eprintln!("[nurunuru-ffi] Skipping unparseable tag: {e}");
            }
        }
    }
    Ok(out)
}

impl NuruNuruClient {
    /// Shared implementation for global and follow timelines.
    /// Not exported to UniFFI — called only from the exported pub methods above.
    fn fetch_timeline_inner(
        &self,
        author_hexes: Option<Vec<String>>,
        limit: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        let author_pks: Option<Vec<nostr::PublicKey>> = author_hexes.map(|hexes| {
            hexes
                .iter()
                .filter_map(|h| nostr::PublicKey::from_hex(h).ok())
                .collect()
        });

        let events = self
            .runtime
            .block_on(
                self.engine
                    .fetch_timeline(author_pks.as_deref(), None, limit as usize),
            )
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }

    fn fetch_timeline_fast_inner(
        &self,
        author_hexes: Option<Vec<String>>,
        limit: u32,
        timeout_secs: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        let author_pks: Option<Vec<nostr::PublicKey>> = author_hexes.map(|hexes| {
            hexes
                .iter()
                .filter_map(|h| nostr::PublicKey::from_hex(h).ok())
                .collect()
        });
        let timeout = std::time::Duration::from_secs(timeout_secs.max(1) as u64);
        let since = Some(nostr::Timestamp::now() - 86400 * 2);
        let events = self
            .runtime
            .block_on(self.engine.fetch_timeline_fast(
                author_pks.as_deref(),
                since,
                limit as usize,
                timeout,
            ))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        events
            .iter()
            .map(|e| {
                serde_json::to_string(e)
                    .map_err(|err| NuruNuruFfiError::EngineError(err.to_string()))
            })
            .collect()
    }
}

// ─── Internal helpers ──────────────────────────────────────────────────────

fn core_profile_to_ffi(p: nurunuru_core::types::UserProfile) -> FfiUserProfile {
    FfiUserProfile {
        pubkey: p.pubkey,
        name: p.name,
        display_name: p.display_name,
        about: p.about,
        picture: p.picture,
        nip05: p.nip05,
        lud16: p.lud16,
    }
}

// ─── FFI-safe types ────────────────────────────────────────────────────────

#[derive(uniffi::Record)]
pub struct FfiUserProfile {
    pub name: String,
    pub display_name: String,
    pub about: String,
    pub picture: String,
    pub nip05: String,
    pub lud16: String,
    pub pubkey: String,
}

#[derive(uniffi::Record)]
pub struct FfiScoredPost {
    pub event_id: String,
    pub pubkey: String,
    pub score: f64,
    pub created_at: u64,
}

#[derive(uniffi::Record)]
pub struct FfiConnectionStats {
    pub connected_relays: u32,
    pub total_relays: u32,
}

// ─── MLS FFI Record types ───────────────────────────────────────────────────

#[derive(uniffi::Record)]
pub struct FfiMlsGroupInfo {
    /// Nostr group id hex / Kind 445 h tag value.
    ///
    /// This is the external group id used across FFI/App boundaries; it is not
    /// MDK/OpenMLS's internal MLS group id.
    pub group_id_hex: String,
    pub name: String,
    pub description: String,
    pub admin_pubkeys: Vec<String>,
    pub member_pubkeys: Vec<String>,
    pub relays: Vec<String>,
    pub created_at: u64,
    pub epoch: u64,
    /// MIP-01 v3 disappearing message duration in seconds.
    /// None => disabled.
    pub disappearing_message_secs: Option<u64>,
    pub is_dm: bool,
}

#[derive(uniffi::Record)]
pub struct FfiKeyPackageEventData {
    /// Event kind: 30443 (Marmot MIP-00, addressable)
    pub kind: u32,
    pub content: String,
    /// Canonical kind:30443 tags (includes `d`)
    pub tags: Vec<Vec<String>>,
    /// Legacy kind:443 tags (excludes `d`) for migration dual-publish.
    pub legacy_tags: Vec<Vec<String>>,
    /// Canonical d-tag identifier for keypackage slot replacement.
    pub d_tag: String,
    /// Serialized MDK KeyPackage hash_ref for exact local init-key cleanup after Welcome accept.
    pub hash_ref: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct FfiEncryptedMessageData {
    pub content: String,
    pub tags: Vec<Vec<String>>,
    pub ephemeral_pubkey: String,
}

#[derive(uniffi::Record)]
pub struct FfiWelcomeEventData {
    pub recipient_pubkey: String,
    /// NIP-59 gift-wrapped event JSON (Kind 1059), ready for `publish_raw_event`.
    pub gift_wrapped_event_json: String,
    /// Inner rumor JSON (Kind 444, unsigned) — for local storage/debugging.
    pub inner_rumor_json: String,
    pub tags: Vec<Vec<String>>,
}

#[derive(uniffi::Record)]
pub struct FfiAddMemberResult {
    pub commit_event_data: FfiEncryptedMessageData,
    pub welcome_event_data: FfiWelcomeEventData,
}

#[derive(uniffi::Record)]
pub struct FfiDecryptedMessage {
    pub sender_pubkey: String,
    pub content: String,
    pub timestamp: u64,
    /// Nostr group id hex / Kind 445 h tag value.
    pub group_id_hex: String,
}

#[derive(uniffi::Enum)]
pub enum FfiMlsProcessResult {
    Application {
        message: FfiDecryptedMessage,
    },
    /// A Commit was applied. Carries the membership delta so the UI doesn't
    /// need to re-query group info (issue #178 #5).
    Commit {
        group_id_hex: String,
        added_pubkeys: Vec<String>,
        removed_pubkeys: Vec<String>,
        epoch_after: u64,
    },
    /// A pending proposal was stored — call `mls_create_recovery_commit` to
    /// resolve it before the group stalls (issue #178 #6).
    NeedsSelfUpdate {
        group_id_hex: String,
        reason: String,
    },
    /// Catch-all for unprocessable / unhandled results.
    StateUpdate {
        kind: String,
    },
}

/// Issue #183: status of `mls_catch_up_to_peer`.
#[derive(uniffi::Enum, Clone, Copy)]
pub enum FfiMlsCatchUpStatus {
    /// Local epoch advanced and no retryable events remain — aligned with peer.
    Recovered,
    /// At least one event was applied but some retryables remain.
    /// Caller should poll relays again before escalating.
    PartiallyRecovered,
    /// No progress made; the missing Commit is no longer retrievable.
    /// UI should prompt the user to recreate the conversation.
    NotRecoverable,
    /// The group is not present in the local MLS store.
    NoSuchGroup,
}

/// Issue #183: structured report returned by `mls_catch_up_to_peer`.
#[derive(uniffi::Record)]
pub struct FfiMlsCatchUpReport {
    pub group_id_hex: String,
    pub epoch_before: u64,
    pub epoch_after: u64,
    pub candidates_considered: u32,
    pub application_messages_applied: u32,
    pub commits_applied: u32,
    pub still_unprocessable: u32,
    pub cache_hits: u32,
    pub status: FfiMlsCatchUpStatus,
}

#[derive(uniffi::Record)]
pub struct FfiPendingWelcome {
    /// Inner Welcome rumor (kind:444) event id — the lookup key for
    /// `mls_accept_welcome` / `mls_decline_welcome`.
    pub welcome_event_id_hex: String,
    /// Outer NIP-59 gift-wrap (kind:1059) event id — for app-side dedup
    /// against relay-level caches.
    pub wrapper_event_id_hex: String,
    pub group_id_hex: String,
    pub group_name: String,
    pub group_description: String,
    pub group_admin_pubkeys: Vec<String>,
    pub group_relays: Vec<String>,
    pub welcomer_pubkey: String,
    pub member_count: u32,
    pub is_dm: bool,
}

// ─── MLS conversion helpers ─────────────────────────────────────────────────

fn core_group_info_to_ffi(info: nurunuru_core::types::MlsGroupInfo) -> FfiMlsGroupInfo {
    FfiMlsGroupInfo {
        group_id_hex: info.group_id_hex,
        name: info.name,
        description: info.description,
        admin_pubkeys: info.admin_pubkeys,
        member_pubkeys: info.member_pubkeys,
        relays: info.relays,
        created_at: info.created_at,
        epoch: info.epoch,
        disappearing_message_secs: info.disappearing_message_secs,
        is_dm: info.is_dm,
    }
}

fn core_encrypted_msg_to_ffi(
    data: nurunuru_core::types::EncryptedMessageData,
) -> FfiEncryptedMessageData {
    FfiEncryptedMessageData {
        content: data.content,
        tags: data.tags,
        ephemeral_pubkey: data.ephemeral_pubkey,
    }
}

fn core_catch_up_report_to_ffi(
    r: nurunuru_core::types::MlsCatchUpReport,
) -> FfiMlsCatchUpReport {
    FfiMlsCatchUpReport {
        group_id_hex: r.group_id_hex,
        epoch_before: r.epoch_before,
        epoch_after: r.epoch_after,
        candidates_considered: r.candidates_considered,
        application_messages_applied: r.application_messages_applied,
        commits_applied: r.commits_applied,
        still_unprocessable: r.still_unprocessable,
        cache_hits: r.cache_hits,
        status: match r.status {
            nurunuru_core::types::MlsCatchUpStatus::Recovered => FfiMlsCatchUpStatus::Recovered,
            nurunuru_core::types::MlsCatchUpStatus::PartiallyRecovered => {
                FfiMlsCatchUpStatus::PartiallyRecovered
            }
            nurunuru_core::types::MlsCatchUpStatus::NotRecoverable => {
                FfiMlsCatchUpStatus::NotRecoverable
            }
            nurunuru_core::types::MlsCatchUpStatus::NoSuchGroup => FfiMlsCatchUpStatus::NoSuchGroup,
        },
    }
}

fn core_pending_welcome_to_ffi(p: nurunuru_core::types::PendingWelcome) -> FfiPendingWelcome {
    FfiPendingWelcome {
        welcome_event_id_hex: p.welcome_event_id_hex,
        wrapper_event_id_hex: p.wrapper_event_id_hex,
        group_id_hex: p.group_id_hex,
        group_name: p.group_name,
        group_description: p.group_description,
        group_admin_pubkeys: p.group_admin_pubkeys,
        group_relays: p.group_relays,
        welcomer_pubkey: p.welcomer_pubkey,
        member_count: p.member_count,
        is_dm: p.is_dm,
    }
}

/// HKDF-SHA256 over the secret key (hex or nsec) → 32-byte key for
/// `set_mls_db_key`. `app_salt` scopes the key (e.g. `"io.nurunuru.mdk.v1"`).
#[uniffi::export]
pub fn derive_mls_db_key_from_secret(
    secret_key_hex: String,
    app_salt: String,
) -> Result<Vec<u8>, NuruNuruFfiError> {
    let keys = nostr::Keys::parse(&secret_key_hex)
        .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
    let sk_bytes = keys.secret_key().to_secret_bytes();
    let key = nurunuru_core::mls::derive_mls_db_key(&sk_bytes, app_salt.as_bytes());
    Ok(key.to_vec())
}

/// Issue #181: single source of truth for the on-disk MLS SQLite path.
/// Given the engine's `db_path` (e.g. `${filesDir}/nostrdb_ndb`), returns
/// the path the engine will actually open
/// (e.g. `${filesDir}/nostrdb_ndb_mls.sqlite3`).
///
/// Migration / diagnostic code in Android (Kotlin) and iOS (Swift) MUST
/// route through this FFI function rather than reproducing the
/// `"{}_mls.sqlite3"` format locally, to prevent cross-platform path drift
/// (issue #181 B1).
#[uniffi::export]
pub fn mls_db_path_for(db_path: String) -> String {
    nurunuru_core::mls::mls_db_path_for(&db_path)
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum NuruNuruFfiError {
    #[error("Runtime error: {0}")]
    RuntimeError(String),
    #[error("Key error: {0}")]
    KeyError(String),
    #[error("Engine error: {0}")]
    EngineError(String),
    /// Returned by `mls_process_message` for Commit / Proposal messages.
    /// MDK already updated local MLS state; the message is not displayable.
    #[error("MLS state update (not displayable)")]
    MlsStateUpdate,
}
