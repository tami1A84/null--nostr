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

use std::sync::{Arc, OnceLock};

use nurunuru_core::config::NuruNuruConfig;
use nurunuru_core::types::*;
use nurunuru_core::NuruNuruEngine;

uniffi::setup_scaffolding!("nurunuru");

// ─── Global DB path ────────────────────────────────────────────────────────

/// Database path set by `init_engine()`. Must be initialised before any
/// `NuruNuruClient` is created.
static GLOBAL_DB_PATH: OnceLock<String> = OnceLock::new();

/// One-time global initialisation. Call this once in `Application.onCreate()`
/// before creating any `NuruNuruClient`.
///
/// `db_path` is the directory where nostrdb stores its data files.
/// Recommended value: `"${context.filesDir}/nostrdb_ndb"`.
///
/// Subsequent calls after the first are silently ignored (idempotent).
#[uniffi::export]
pub fn init_engine(db_path: String) -> Result<(), NuruNuruFfiError> {
    // OnceLock::set returns Err if already set; we treat that as a no-op.
    let _ = GLOBAL_DB_PATH.set(db_path);
    Ok(())
}

fn get_db_path() -> Result<String, NuruNuruFfiError> {
    GLOBAL_DB_PATH
        .get()
        .cloned()
        .ok_or_else(|| NuruNuruFfiError::RuntimeError(
            "init_engine() must be called before creating a NuruNuruClient".to_string(),
        ))
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
            config.db_path = db_path;

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

        Ok(Arc::new(Self { runtime: rt, engine, secret_key: Some(secret_key) }))
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
            config.db_path = db_path;

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

        Ok(Arc::new(Self { runtime: rt, engine, secret_key: None }))
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
        serde_json::to_string(&unsigned)
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
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
        serde_json::to_string(&unsigned)
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
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
        serde_json::to_string(&unsigned)
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
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
        serde_json::to_string(&unsigned)
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
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
        serde_json::to_string(&unsigned)
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
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
    pub fn fetch_global_timeline(
        &self,
        limit: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
        self.fetch_timeline_inner(None, limit)
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
    pub fn query_local_global(
        &self,
        limit: u32,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
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

        Ok(profiles_map.into_values().map(core_profile_to_ffi).collect())
    }

    // ─── Social graph ──────────────────────────────────────────────────────

    /// Fetch the follow list for a user. Returns pubkey hex strings.
    pub fn fetch_follow_list(
        &self,
        pubkey_hex: String,
    ) -> Result<Vec<String>, NuruNuruFfiError> {
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
    pub fn send_dm(
        &self,
        recipient_hex: String,
        content: String,
    ) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&recipient_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.send_dm(pk, &content))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    // ─── MLS / NIP-EE ──────────────────────────────────────────────────────

    /// Generate a fresh MLS KeyPackage and return Kind-443 event data.
    ///
    /// The caller builds an unsigned Kind-443 event via `create_unsigned_event`,
    /// signs it (internal key or Amber), then publishes via `publish_raw_event`.
    pub fn mls_create_key_package(&self) -> Result<FfiKeyPackageEventData, NuruNuruFfiError> {
        let data = self
            .runtime
            .block_on(self.engine.mls_create_key_package())
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(FfiKeyPackageEventData {
            content: data.content,
            tags: data.tags,
        })
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

    /// Add a member to a group using their Kind-443 KeyPackage event JSON.
    ///
    /// Returns commit (Kind 445) and welcome (Kind 444) event data.
    pub fn mls_add_member(
        &self,
        group_id_hex: String,
        key_package_event_json: String,
    ) -> Result<FfiAddMemberResult, NuruNuruFfiError> {
        let result = self
            .runtime
            .block_on(self.engine.mls_add_member(&group_id_hex, &key_package_event_json))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(FfiAddMemberResult {
            commit_event_data: core_encrypted_msg_to_ffi(result.commit_event_data),
            welcome_event_data: FfiWelcomeEventData {
                recipient_pubkey: result.welcome_event_data.recipient_pubkey,
                content: result.welcome_event_data.content,
                tags: result.welcome_event_data.tags,
            },
        })
    }

    /// Encrypt an application message for a group (Kind 445 event data).
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

    /// Process an incoming Kind-445 event and return the decrypted message.
    pub fn mls_process_message(
        &self,
        group_id_hex: String,
        event_json: String,
    ) -> Result<FfiDecryptedMessage, NuruNuruFfiError> {
        let msg = self
            .runtime
            .block_on(self.engine.mls_process_message(&group_id_hex, &event_json))
            .map_err(|e| match e {
                nurunuru_core::NuruNuruError::MlsStateUpdate => NuruNuruFfiError::MlsStateUpdate,
                other => NuruNuruFfiError::EngineError(other.to_string()),
            })?;
        Ok(FfiDecryptedMessage {
            sender_pubkey: msg.sender_pubkey,
            content: msg.content,
            timestamp: msg.timestamp,
            group_id_hex: msg.group_id_hex,
        })
    }

    /// Process an incoming Kind-444 Welcome event and join the group.
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

    /// Retrieve decrypted message history for a group from MDK's local SQLite.
    /// Use this on app startup to restore history without re-processing relay events.
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

    /// Leave a group. Returns the Kind-445 commit event data to publish.
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
    pub fn get_recommended_feed(
        &self,
        limit: u32,
    ) -> Result<Vec<FfiScoredPost>, NuruNuruFfiError> {
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
    pub fn repost(
        &self,
        event_json: String,
    ) -> Result<String, NuruNuruFfiError> {
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
        let sk = self.secret_key.as_ref()
            .ok_or_else(|| NuruNuruFfiError::EngineError(
                "nip04_encrypt requires an internal signer client".to_string()))?;
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
        let sk = self.secret_key.as_ref()
            .ok_or_else(|| NuruNuruFfiError::EngineError(
                "nip04_decrypt requires an internal signer client".to_string()))?;
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
        let sk = self.secret_key.as_ref()
            .ok_or_else(|| NuruNuruFfiError::EngineError(
                "nip44_encrypt requires an internal signer client".to_string()))?;
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
        let sk = self.secret_key.as_ref()
            .ok_or_else(|| NuruNuruFfiError::EngineError(
                "nip44_decrypt requires an internal signer client".to_string()))?;
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
    pub fn poll_live_events(
        &self,
        sub_id: String,
        max_count: u32,
    ) -> Vec<String> {
        self.runtime
            .block_on(self.engine.poll_subscription(&sub_id, max_count as usize))
    }

    /// Cancel a live subscription and release all associated resources.
    pub fn stop_live_subscription(
        &self,
        sub_id: String,
    ) -> Result<(), NuruNuruFfiError> {
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
fn parse_ffi_tags(
    raw: Vec<Vec<String>>,
) -> Result<Vec<nostr::Tag>, NuruNuruFfiError> {
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
            .block_on(self.engine.fetch_timeline(
                author_pks.as_deref(),
                None,
                limit as usize,
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
    pub group_id_hex: String,
    pub name: String,
    pub description: String,
    pub admin_pubkeys: Vec<String>,
    pub member_pubkeys: Vec<String>,
    pub relays: Vec<String>,
    pub created_at: u64,
    pub epoch: u64,
    pub is_dm: bool,
}

#[derive(uniffi::Record)]
pub struct FfiKeyPackageEventData {
    pub content: String,
    pub tags: Vec<Vec<String>>,
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
    pub content: String,
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
    pub group_id_hex: String,
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
