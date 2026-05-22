//! MLS (Messaging Layer Security, RFC 9420) manager for Marmot protocol.
//!
//! Wraps `mdk-core` (`MDK<MdkSqliteStorage>`) and exposes a Nostr-centric API
//! that produces/consumes event payloads for the following Marmot kinds:
//!
//! | Kind  | Purpose                                           |
//! |-------|---------------------------------------------------|
//! | 30443 | KeyPackage — MIP-00 canonical (addressable)       |
//! | 444   | Welcome rumor — unsigned, gift-wrapped as 1059     |
//! | 1059  | Gift Wrap — NIP-59 wrapped Welcome (MIP-02)       |
//! | 445   | Group Message — encrypted MLS message/commit       |
//!
//! ## API notes
//!
//! MDK signs Kind-445 events internally with **ephemeral keys** for privacy
//! (forward secrecy + sender anonymisation).  The returned `EncryptedMessageData.content`
//! contains the **full signed event JSON**, ready for `publish_raw_event()`.
//!
//! Kind-30443 (KeyPackage) events are **unsigned** — the caller signs them
//! via the internal signer or Amber.
//!
//! Kind-444 (Welcome) rumors are gift-wrapped via NIP-59 in `engine.rs`
//! (`mls_add_member`) using the engine's signer.

use std::collections::BTreeSet;

use base64::Engine as _;
use mdk_core::groups::NostrGroupConfigData;
use mdk_sqlite_storage::{EncryptionConfig, MdkSqliteStorage};
use nostr::{EventBuilder, EventId, JsonUtil, PublicKey, RelayUrl, UnsignedEvent};
use serde_json::Value;

use crate::error::{NuruNuruError, Result};
use crate::types::{
    AddMemberResult, CommitDelta, DecryptedMessage, EncryptedMessageData, KeyPackageEventData,
    MlsGroupInfo, PendingWelcome, WelcomeEventData,
};

// ─── Type alias ──────────────────────────────────────────────────────────────

type Mdk = mdk_core::MDK<MdkSqliteStorage>;

fn redact_hex_prefix(value: &str) -> String {
    if value.len() <= 8 {
        value.to_string()
    } else {
        format!("{}…", &value[..8])
    }
}

fn mls_process_result_label<T: std::fmt::Debug>(value: &T) -> &'static str {
    let s = format!("{value:?}");
    if s.contains("Unprocessable") {
        "Unprocessable"
    } else if s.contains("Application") {
        "ApplicationMessage"
    } else if s.contains("Commit") {
        "Commit"
    } else if s.contains("Proposal") {
        "Proposal"
    } else if s.contains("Welcome") {
        "Welcome"
    } else {
        "Other"
    }
}

/// Issue #183: classify a `process_message_result` error as permanent (drop)
/// or retryable (leave in the catch-up pool). Mirrors Android's
/// `isPermanentMlsProcessDropError` in `NostrRepositoryTalk.kt` so both
/// sides agree on what is recoverable.
fn is_permanent_process_error(e: &NuruNuruError) -> bool {
    let msg = e.to_string().to_lowercase();
    let permanent_signals = [
        "invalid_base64_content",
        "invalid base64",
        "malformed_content_too_short",
        "too_short",
        "invalid kind",
        "wrong kind",
        "missing h",
        "wrong h",
        "group mismatch",
        "bad signature",
        "invalid signature",
        "not a nostr event",
        "invalid event json",
    ];
    permanent_signals.iter().any(|s| msg.contains(s))
}

fn mls_error_label(error: &dyn std::fmt::Display) -> &'static str {
    let lower = error.to_string().to_lowercase();
    if lower.contains("hmac") {
        "hmac_error"
    } else if lower.contains("welcome") || lower.contains("process_welcome") {
        "welcome_error"
    } else if lower.contains("content")
        || lower.contains("payload")
        || lower.contains("plaintext")
        || lower.contains("secret")
        || lower.contains("private")
    {
        "redacted_error"
    } else {
        "mls_error"
    }
}

// ─── MlsManager ──────────────────────────────────────────────────────────────

/// Issue #183: 30-day TTL for cached Kind-445 wrappers. Long enough to span
/// week-long device-off / app-killed windows that triggered the issue
/// (observed 2026-05-23), short enough that the sidecar SQLite does not
/// grow unbounded for chatty groups.
pub(crate) const MLS_REPLAY_CACHE_TTL_SECS: u64 = 30 * 24 * 60 * 60;

/// Issue #183: hard cap on cached wrappers per group. Defends against a
/// chatty group filling the sidecar before the periodic TTL prune runs.
/// When exceeded, the oldest entries for that group are evicted.
pub(crate) const MLS_REPLAY_CACHE_MAX_PER_GROUP: u64 = 2_000;

/// Wraps `mdk_core::MDK` and produces/consumes Nostr event payloads for
/// Marmot kinds 30443/444/1059/445. Identity is bound at construction; to
/// switch users, drop this manager (see `NuruNuruEngine::mls_reset`).
///
/// Also owns the issue-#183 "replay cache" — a sidecar SQLite file
/// (`{mls_db_path}.replay.sqlite3`) that stores raw Kind-445 wrappers as
/// they arrive so they can be replayed when a peer's Commit catches up
/// later. The cache stores only MLS ciphertext envelopes (kind, content,
/// tags, id, created_at); no decrypted plaintext is ever written.
pub struct MlsManager {
    mdk: Mdk,
    user_pubkey: PublicKey,
    db_path: String,
    encrypted: bool,
    /// Replay-cache connection (see Issue #183).
    ///
    /// Held behind a `Mutex` so the immutable `MlsManager` reference shared
    /// via `Arc<MlsManager>` (from `NuruNuruEngine::require_mls`) can still
    /// mutate the underlying SQLite connection. Lazily opened the first time
    /// it is needed; failures degrade gracefully (catch-up still works on
    /// the caller-supplied event list, just without cache hits).
    replay_cache: std::sync::Mutex<Option<rusqlite::Connection>>,
    replay_cache_path: String,
}

impl MlsManager {
    /// Unencrypted SQLite open. Prefer [`MlsManager::new_with_key`]
    /// (SQLCipher) for production; this is for tests / pre-migration installs.
    pub fn new(db_path: &str, nostr_pubkey: &str) -> Result<Self> {
        let pubkey = Self::parse_required_pubkey(nostr_pubkey)?;

        let storage = MdkSqliteStorage::new_unencrypted(db_path)
            .map_err(|e| NuruNuruError::MlsError(format!("SQLite open: {e}")))?;

        let mdk = Mdk::new(storage);

        Ok(Self {
            mdk,
            user_pubkey: pubkey,
            replay_cache_path: replay_cache_path_for(db_path),
            db_path: db_path.to_string(),
            encrypted: false,
            replay_cache: std::sync::Mutex::new(None),
        })
    }

    /// SQLCipher-encrypted SQLite open. Derive `db_key` via
    /// [`derive_mls_db_key`] or source it from a platform keychain/keystore.
    pub fn new_with_key(db_path: &str, nostr_pubkey: &str, db_key: [u8; 32]) -> Result<Self> {
        let pubkey = Self::parse_required_pubkey(nostr_pubkey)?;

        let config = EncryptionConfig::new(db_key);
        let storage = MdkSqliteStorage::new_with_key(db_path, config)
            .map_err(|e| NuruNuruError::MlsError(format!("SQLite encrypted open: {e}")))?;

        let mdk = Mdk::new(storage);

        Ok(Self {
            mdk,
            user_pubkey: pubkey,
            replay_cache_path: replay_cache_path_for(db_path),
            db_path: db_path.to_string(),
            encrypted: true,
            replay_cache: std::sync::Mutex::new(None),
        })
    }

    fn parse_required_pubkey(nostr_pubkey: &str) -> Result<PublicKey> {
        if nostr_pubkey.trim().is_empty() {
            return Err(NuruNuruError::MlsError(
                "MlsManager requires a non-empty Nostr pubkey at construction time".to_string(),
            ));
        }
        PublicKey::from_hex(nostr_pubkey)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid user pubkey: {e}")))
    }

    fn user_pubkey(&self) -> Result<PublicKey> {
        Ok(self.user_pubkey)
    }

    /// `true` when the underlying SQLite is SQLCipher-encrypted (used in tests).
    pub fn is_encrypted(&self) -> bool {
        self.encrypted
    }
}

/// HKDF-SHA256 over the raw 32-byte secret key. Deterministic, so the
/// encrypted DB can be reopened on the same device without keychain
/// round-trips. `app_salt` scopes the key (use e.g. `b"io.nurunuru.mdk.v1"`).
pub fn derive_mls_db_key(nsec_bytes: &[u8; 32], app_salt: &[u8]) -> [u8; 32] {
    use hkdf::Hkdf;
    use sha2::Sha256;
    let hk = Hkdf::<Sha256>::new(Some(app_salt), nsec_bytes);
    let mut out = [0u8; 32];
    hk.expand(b"mdk-sqlite-db-key", &mut out)
        .expect("32 bytes is well within HKDF-SHA256 output limits");
    out
}

/// Canonical helper: given the engine's `db_path` (e.g. `${filesDir}/nostrdb_ndb`),
/// returns the path the engine actually opens for the MLS SQLite database.
///
/// This is the **single source of truth** for the MLS DB path used across
/// Rust + Android + iOS. Migration/diagnostic code MUST go through this
/// helper instead of duplicating the `"{}_mls.sqlite3"` format string,
/// to avoid cross-platform path drift (issue #181).
pub fn mls_db_path_for(db_path: &str) -> String {
    format!("{db_path}_mls.sqlite3")
}

/// Issue #183: derive the replay-cache SQLite path from the MLS DB path.
/// The replay cache lives next to the MLS DB (`{mls_db_path}.replay.sqlite3`)
/// and stores raw Kind-445 wrappers so peer Commits can be replayed after
/// they have aged out of relays.
pub fn replay_cache_path_for(mls_db_path: &str) -> String {
    format!("{mls_db_path}.replay.sqlite3")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Issue #181 PR-0: tests-first. Construct an encrypted MLS DB and
    /// assert its on-disk first 16 bytes are NOT the SQLite plaintext magic
    /// `"SQLite format 3\0"`. SQLCipher overwrites the header with the
    /// salt+IV. Plaintext SQLite always starts with the magic; any
    /// non-magic prefix on a non-empty file proves SQLCipher applied keying.
    #[test]
    fn new_with_key_produces_sqlcipher_header() {
        use std::io::Read;
        let tmp = tempfile::tempdir().expect("tmpdir");
        let db_base = tmp.path().join("nostrdb_ndb");
        let mls_path = mls_db_path_for(db_base.to_str().unwrap());
        let dummy_pubkey =
            "0000000000000000000000000000000000000000000000000000000000000001";

        let key = [0x42u8; 32];
        let mgr = MlsManager::new_with_key(&mls_path, dummy_pubkey, key)
            .expect("encrypted MLS open");
        assert!(mgr.is_encrypted());
        drop(mgr); // close handle so we can re-read the file bytes

        let mut f = std::fs::File::open(&mls_path).expect("open mls db");
        let mut buf = [0u8; 16];
        let n = f.read(&mut buf).expect("read header");
        assert_eq!(n, 16, "expected 16-byte header read");
        const PLAINTEXT_MAGIC: &[u8; 16] = b"SQLite format 3\0";
        assert_ne!(
            &buf, PLAINTEXT_MAGIC,
            "SQLCipher-keyed DB header still matches plaintext SQLite magic — \
             encryption did NOT apply (issue #181 regression)"
        );
    }
}

impl MlsManager {
    /// Resolve a `nostr_group_id` hex string (32 bytes = 64 hex chars) to the
    /// internal `mdk_storage_traits::GroupId` (= `mls_group_id`) used by MDK API calls.
    ///
    /// All external group identifiers stored in `MlsGroupInfo.group_id_hex` and
    /// used in Nostr `h` tags are `nostr_group_id`.  MDK operations (create_message,
    /// add_member, …) require the internal `GroupId`.
    fn resolve_group_id(&self, nostr_group_id_hex: &str) -> Result<mdk_storage_traits::GroupId> {
        let bytes = hex::decode(nostr_group_id_hex)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid nostr_group_id hex: {e}")))?;
        let nostr_id: [u8; 32] = bytes.try_into().map_err(|_| {
            NuruNuruError::MlsError(format!(
                "nostr_group_id must be 32 bytes, got {nostr_group_id_hex}"
            ))
        })?;
        let groups = self
            .mdk
            .get_groups()
            .map_err(|e| NuruNuruError::MlsError(format!("get_groups: {e}")))?;
        groups
            .into_iter()
            .find(|g| g.nostr_group_id == nostr_id)
            .map(|g| g.mls_group_id)
            .ok_or_else(|| {
                NuruNuruError::MlsError(format!(
                    "Group not found for nostr_group_id: {nostr_group_id_hex}"
                ))
            })
    }

    /// Convert a `mdk_storage_traits::GroupId` to a lowercase hex string.
    fn group_id_to_hex(id: &mdk_storage_traits::GroupId) -> String {
        hex::encode(id.as_slice())
    }

    /// Convert `Vec<nostr::Tag>` to `Vec<Vec<String>>` for the FFI boundary.
    fn tags_to_vecs(tags: Vec<nostr::Tag>) -> Vec<Vec<String>> {
        tags.into_iter()
            .map(|t| t.as_slice().iter().map(|s| s.to_string()).collect())
            .collect()
    }

    /// Convert a `mdk_storage_traits::groups::types::Group` to `MlsGroupInfo`.
    ///
    /// `member_pubkeys`, `relays`, and `is_dm` are left empty / false here;
    /// callers that need them (`list_groups`, `get_group_info`) enrich via
    /// `get_members` / `get_relays` immediately after.
    fn group_to_info(group: mdk_storage_traits::groups::types::Group) -> MlsGroupInfo {
        // Use `nostr_group_id` (32 bytes) — this is the value MDK puts in the
        // `h` tag of Kind-445 events, and what we must use for subscription filters.
        // `mls_group_id` is the internal storage key (16 bytes) and does NOT match.
        let group_id_hex = hex::encode(group.nostr_group_id);
        MlsGroupInfo {
            group_id_hex,
            name: group.name,
            description: group.description,
            admin_pubkeys: group.admin_pubkeys.iter().map(|pk| pk.to_hex()).collect(),
            member_pubkeys: Vec::new(), // enriched below via get_members
            relays: Vec::new(),         // enriched below via get_relays
            created_at: 0,
            epoch: group.epoch,
            disappearing_message_secs: group.disappearing_message_secs,
            is_dm: false, // updated after member count is known
        }
    }

    /// Ensure unsigned inner Marmot rumors serialize in Amethyst/Quartz-compatible
    /// shape. Quartz rumors carry an `id` field and an empty `sig` field; MDK only
    /// requires/verifies `id`, but including both makes persisted/decrypted inner
    /// JSON parseable by Amethyst's `Event.fromJson()` and visible in its UI.
    fn ensure_marmot_rumor_identity(rumor: &mut UnsignedEvent) -> Result<()> {
        rumor.ensure_id();
        let v = serde_json::to_value(&*rumor)
            .map_err(|e| NuruNuruError::MlsError(format!("serialize rumor: {e}")))?;
        let mut obj = v
            .as_object()
            .cloned()
            .ok_or_else(|| NuruNuruError::MlsError("serialize rumor: not object".to_string()))?;
        if let Some(id) = rumor.id {
            obj.insert("id".to_string(), Value::String(id.to_hex()));
        }
        obj.insert("sig".to_string(), Value::String(String::new()));
        let with_sig = Value::Object(obj);
        *rumor = serde_json::from_value(with_sig)
            .map_err(|e| NuruNuruError::MlsError(format!("normalize rumor: {e}")))?;
        Ok(())
    }

    /// Convert a stored/decrypted MDK rumor to JSON that Amethyst can parse.
    /// MDK stores unsigned events without `sig`; Amethyst's Kotlin `Event` model
    /// expects `id` + `sig` fields even for rumors, with `sig` empty.
    fn unsigned_event_json_with_empty_sig(event: &UnsignedEvent) -> Result<String> {
        let mut rumor = event.clone();
        Self::ensure_marmot_rumor_identity(&mut rumor)?;
        let v = serde_json::to_value(&rumor)
            .map_err(|e| NuruNuruError::MlsError(format!("serialize stored rumor: {e}")))?;
        serde_json::to_string(&v)
            .map_err(|e| NuruNuruError::MlsError(format!("serialize stored rumor json: {e}")))
    }

    fn display_content_from_decrypted_event(event: &UnsignedEvent, fallback: String) -> String {
        let is_chat = event.kind.as_u16() == 9;
        if is_chat && !event.content.is_empty() {
            event.content.clone()
        } else if fallback.is_empty() {
            Self::unsigned_event_json_with_empty_sig(event).unwrap_or_default()
        } else {
            fallback
        }
    }

    // ─── Key Package (Kind 30443, MIP-00) ───────────────────────────────────

    /// Generate a fresh MLS KeyPackage and return Kind-30443 event data.
    ///
    /// The caller signs the event via `create_unsigned_event(30443, content, tags, pubkey_hex)`.
    /// MDK automatically generates all Marmot-required tags:
    /// `d`, `mls_protocol_version`, `mls_ciphersuite`, `mls_extensions`,
    /// `mls_proposals`, `encoding`, `i`, `relays`, `client`.
    pub fn create_key_package_event(&self, relay_urls: &[RelayUrl]) -> Result<KeyPackageEventData> {
        let pubkey = self.user_pubkey()?;
        let data = self
            .mdk
            .create_key_package_for_event(&pubkey, relay_urls.iter().cloned())
            .map_err(|e| NuruNuruError::MlsError(format!("create_key_package: {e}")))?;

        Ok(KeyPackageEventData {
            kind: 30443, // Marmot MIP-00 canonical kind
            content: data.content,
            tags: Self::tags_to_vecs(data.tags_30443),
            legacy_tags: Self::tags_to_vecs(data.tags_443),
            d_tag: data.d_tag,
            hash_ref: data.hash_ref,
        })
    }

    /// Parse an incoming Marmot KeyPackage event JSON without mutating signed fields.
    ///
    /// MDK 0.8 accepts both canonical kind:30443 and migration legacy kind:443.  Do
    /// not rewrite kind/tags here: changing a peer-signed event would invalidate the
    /// event id/signature and can make MDK reject otherwise valid KeyPackages.
    fn normalize_key_package_event_for_mdk(
        &self,
        key_package_event_json: &str,
    ) -> Result<nostr::Event> {
        serde_json::from_str(key_package_event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid KeyPackage event JSON: {e}")))
    }

    /// Strictly validate a KeyPackage event (including KeyPackageRef `i` tag content match).
    ///
    /// Uses MDK `parse_key_package`, which validates:
    /// - credential/event signer identity binding
    /// - required tags/capabilities
    /// - `i` tag hex format and computed-ref match
    pub fn validate_key_package_event(&self, key_package_event_json: &str) -> Result<()> {
        let kp_event = self.normalize_key_package_event_for_mdk(key_package_event_json)?;
        self.mdk
            .parse_key_package(&kp_event)
            .map_err(|e| NuruNuruError::MlsError(format!("validate_key_package_event: {e}")))?;
        Ok(())
    }

    /// Delete consumed KeyPackage private/init-key material from local MDK storage.
    ///
    /// MIP-02 requires deleting consumed init_key material after successful Welcome processing.
    /// We parse the provided KeyPackage event JSON and delete the corresponding stored key package by hash_ref.
    pub fn delete_consumed_key_package_from_event_json(
        &self,
        key_package_event_json: &str,
    ) -> Result<()> {
        let kp_event = self.normalize_key_package_event_for_mdk(key_package_event_json)?;
        let key_package = self.mdk.parse_key_package(&kp_event).map_err(|e| {
            NuruNuruError::MlsError(format!(
                "delete_consumed_key_package: parse_key_package: {e}"
            ))
        })?;
        self.mdk
            .delete_key_package_from_storage(&key_package)
            .map_err(|e| {
                NuruNuruError::MlsError(format!(
                    "delete_consumed_key_package: delete_key_package_from_storage: {e}"
                ))
            })?;
        Ok(())
    }

    /// Delete consumed KeyPackage private/init-key material using the exact hash_ref returned
    /// by MDK when the local KeyPackage was created. This is the safest MIP-02 lifecycle path:
    /// the relay event may be canonical 30443, legacy 443, or republished/normalized, but the
    /// local init-key storage entry is addressed by this hash_ref.
    pub fn delete_consumed_key_package_by_hash_ref(&self, hash_ref: &[u8]) -> Result<()> {
        self.mdk
            .delete_key_package_from_storage_by_hash_ref(hash_ref)
            .map_err(|e| {
                NuruNuruError::MlsError(format!("delete_consumed_key_package_by_hash_ref: {e}"))
            })?;
        Ok(())
    }

    /// List groups that currently require self-update (MIP-02 post-join and periodic rotation).
    pub fn groups_needing_self_update(&self, threshold_secs: u64) -> Result<Vec<String>> {
        let ids = self
            .mdk
            .groups_needing_self_update(threshold_secs)
            .map_err(|e| NuruNuruError::MlsError(format!("groups_needing_self_update: {e}")))?;

        // Public/FFI group IDs are Nostr group IDs (the 32-byte value used in
        // Kind-445 `h` tags), not MDK's internal MLS group IDs. MDK returns
        // internal IDs here, so translate them before crossing our API boundary.
        ids.into_iter()
            .map(|mls_group_id| {
                let group = self
                    .mdk
                    .get_group(&mls_group_id)
                    .map_err(|e| NuruNuruError::MlsError(format!("get_group: {e}")))?
                    .ok_or_else(|| {
                        NuruNuruError::MlsError(format!(
                            "Group not found for mls_group_id: {}",
                            Self::group_id_to_hex(&mls_group_id)
                        ))
                    })?;
                Ok(hex::encode(group.nostr_group_id))
            })
            .collect()
    }

    // ─── Group management ─────────────────────────────────────────────────

    /// Create a new empty MLS group (no initial members).
    ///
    /// Members are added separately via `add_member`.
    pub fn create_group(
        &self,
        name: String,
        admin_pubkeys: Vec<String>,
        relays: Vec<String>,
    ) -> Result<MlsGroupInfo> {
        let creator_pk = self.user_pubkey()?;

        let admins: Vec<PublicKey> = admin_pubkeys
            .iter()
            .filter_map(|hex| PublicKey::from_hex(hex).ok())
            .collect();

        let relay_urls: Vec<RelayUrl> = relays
            .iter()
            .filter_map(|s| RelayUrl::parse(s).ok())
            .collect();

        let config = NostrGroupConfigData {
            name,
            description: String::new(),
            image_hash: None,
            image_key: None,
            image_nonce: None,
            relays: relay_urls,
            admins,
            disappearing_message_secs: None,
        };

        let result = self
            .mdk
            .create_group(&creator_pk, vec![], config)
            .map_err(|e| NuruNuruError::MlsError(format!("create_group: {e}")))?;

        let info = Self::group_to_info(result.group);
        Ok(info)
    }

    /// Add a member to an existing group using their Kind-30443/443 KeyPackage event JSON.
    ///
    /// Returns:
    /// - `commit_event_data.content` — full JSON of the signed Kind-445 Commit event
    ///   (ready for `publish_raw_event`)
    /// - `welcome_event_data` — unsigned Kind-444 rumor for the new member.
    ///   `gift_wrapped_event_json` is empty here; the caller (engine.rs) must
    ///   apply NIP-59 gift-wrapping before publishing.
    pub fn add_member(
        &self,
        group_id_hex: &str,
        key_package_event_json: &str,
    ) -> Result<AddMemberResult> {
        let group_id = self.resolve_group_id(group_id_hex)?;

        // MDK 0.8 accepts canonical 30443 and legacy 443; keep the peer-signed event immutable.
        let kp_event = self.normalize_key_package_event_for_mdk(key_package_event_json)?;
        let key_package_owner_pubkey = kp_event.pubkey;

        let result = self
            .mdk
            .add_members(&group_id, &[kp_event])
            .map_err(|e| NuruNuruError::MlsError(format!("add_members: {e}")))?;

        // evolution_event is a fully signed Event (ephemeral key) — serialise to JSON
        let commit_json = result.evolution_event.as_json();
        let commit_tags_vec = Self::tags_to_vecs(result.evolution_event.tags.to_vec());
        let commit_pubkey = result.evolution_event.pubkey.to_hex();

        // welcome_rumors[0] is the unsigned Kind-444 rumor for the new member
        let welcome = result.welcome_rumors.and_then(|mut v| {
            if v.is_empty() {
                None
            } else {
                Some(v.remove(0))
            }
        });

        let (welcome_recipient, welcome_rumor_json, welcome_tags) = match welcome {
            Some(rumor) => {
                // NIP-59 gift-wrap encryption must target the KeyPackage event owner
                // (the member being added). The unsigned Kind-444 Welcome rumor's
                // `pubkey` is MDK-controlled and is not guaranteed to be the new
                // member's Nostr identity. Using it as the gift-wrap recipient makes
                // the returned Kind-1059 undecryptable by the actual recipient
                // (invalid HMAC during unwrap).
                let recipient = key_package_owner_pubkey.to_hex();
                let rumor_json = rumor.as_json();
                let tags = Self::tags_to_vecs(
                    serde_json::from_str::<serde_json::Value>(&rumor_json)
                        .ok()
                        .and_then(|v| v.get("tags").cloned())
                        .and_then(|t| serde_json::from_value::<Vec<nostr::Tag>>(t).ok())
                        .unwrap_or_default(),
                );
                (recipient, rumor_json, tags)
            }
            None => (String::new(), String::new(), Vec::new()),
        };

        Ok(AddMemberResult {
            commit_event_data: EncryptedMessageData {
                content: commit_json,
                tags: commit_tags_vec,
                ephemeral_pubkey: commit_pubkey,
            },
            welcome_event_data: WelcomeEventData {
                recipient_pubkey: welcome_recipient,
                gift_wrapped_event_json: String::new(), // filled by engine after gift-wrapping
                inner_rumor_json: welcome_rumor_json,
                tags: welcome_tags,
            },
        })
    }

    /// Merge the pending commit for a group after the commit has been published to relays.
    ///
    /// Must be called after `add_member`, `remove_member`, or `leave_group` once the
    /// commit event has been successfully published. Without this, subsequent operations
    /// on the same group will fail with "pending commit exists".
    pub fn merge_pending_commit(&self, group_id_hex: &str) -> Result<()> {
        let group_id = self.resolve_group_id(group_id_hex)?;
        self.mdk
            .merge_pending_commit(&group_id)
            .map_err(|e| NuruNuruError::MlsError(format!("merge_pending_commit: {e}")))
    }

    /// Create a recovery self-update commit event to resolve stuck pending proposals.
    ///
    /// Caller must publish returned Kind-445 commit event, then call `merge_pending_commit`.
    pub fn create_recovery_commit(&self, group_id_hex: &str) -> Result<EncryptedMessageData> {
        let group_id = self.resolve_group_id(group_id_hex)?;
        let result = self
            .mdk
            .self_update(&group_id)
            .map_err(|e| NuruNuruError::MlsError(format!("self_update_recovery: {e}")))?;

        Ok(EncryptedMessageData {
            content: result.evolution_event.as_json(),
            tags: Self::tags_to_vecs(result.evolution_event.tags.to_vec()),
            ephemeral_pubkey: result.evolution_event.pubkey.to_hex(),
        })
    }

    /// Clear (rollback) a pending commit for recovery after stuck states.
    ///
    /// This is a recovery API used when clients get stuck with
    /// "pending commit/proposal exists" and cannot proceed.
    pub fn clear_pending_commit(&self, group_id_hex: &str) -> Result<()> {
        let group_id = self.resolve_group_id(group_id_hex)?;
        self.mdk
            .clear_pending_commit(&group_id)
            .map_err(|e| NuruNuruError::MlsError(format!("clear_pending_commit: {e}")))
    }

    /// Remove a member from the group.
    ///
    /// `content` in the returned `EncryptedMessageData` is the full JSON of the
    /// signed Kind-445 Commit event, ready for `publish_raw_event()`.
    pub fn remove_member(
        &self,
        group_id_hex: &str,
        member_pubkey: &str,
    ) -> Result<EncryptedMessageData> {
        let group_id = self.resolve_group_id(group_id_hex)?;
        let pk = PublicKey::from_hex(member_pubkey)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid member pubkey: {e}")))?;

        let result = self
            .mdk
            .remove_members(&group_id, &[pk])
            .map_err(|e| NuruNuruError::MlsError(format!("remove_members: {e}")))?;

        Ok(EncryptedMessageData {
            content: result.evolution_event.as_json(),
            tags: Self::tags_to_vecs(result.evolution_event.tags.to_vec()),
            ephemeral_pubkey: result.evolution_event.pubkey.to_hex(),
        })
    }

    /// Leave a group.
    ///
    /// `content` in the returned `EncryptedMessageData` is the full JSON of the
    /// signed Kind-445 Commit event, ready for `publish_raw_event()`.
    pub fn leave_group(&self, group_id_hex: &str) -> Result<EncryptedMessageData> {
        let group_id = self.resolve_group_id(group_id_hex)?;

        let result = self
            .mdk
            .leave_group(&group_id)
            .map_err(|e| NuruNuruError::MlsError(format!("leave_group: {e}")))?;

        Ok(EncryptedMessageData {
            content: result.evolution_event.as_json(),
            tags: Self::tags_to_vecs(result.evolution_event.tags.to_vec()),
            ephemeral_pubkey: result.evolution_event.pubkey.to_hex(),
        })
    }

    // NOTE: self_demote() is available in mdk-core main branch but not yet
    // released in 0.7.1. Will be added when mdk-core is updated.

    // ─── Messaging (Kind 445) ─────────────────────────────────────────────

    /// Encrypt an application message for the group.
    ///
    /// Builds a Kind-14 rumor with the user's pubkey, encrypts it via MLS,
    /// and returns a signed Kind-445 event (ephemeral key).
    ///
    /// `content` in the returned `EncryptedMessageData` is the full JSON of the
    /// signed Kind-445 event, ready for `publish_raw_event()`.
    pub fn create_message(
        &self,
        group_id_hex: &str,
        content: &str,
    ) -> Result<EncryptedMessageData> {
        let group_id = self.resolve_group_id(group_id_hex)?;
        let pubkey = self.user_pubkey()?;

        // Build the inner rumor as a Nostr chat event (kind:9) per Marmot MIP-03.
        // MDK's own examples use Kind::Custom(9), and its processing path stores
        // kind/content generically after decrypting the MLS application message.
        //
        // NOTE: reactions/other kinds are supported when callers provide prebuilt
        // MLS messages via lower layers; this high-level helper is chat-text focused.
        let mut rumor: UnsignedEvent =
            EventBuilder::new(nostr::Kind::from(9u16), content).build(pubkey);
        Self::ensure_marmot_rumor_identity(&mut rumor)?;

        let event = self
            .mdk
            .create_message(&group_id, rumor, None)
            .map_err(|e| {
                // Never clear pending commits/proposals while creating an application
                // message. Marmot Commit ordering is stateful; silently clearing pending
                // local state can make iOS encrypt a kind:9 message from an epoch that
                // WhiteNoise Android does not have. Surface the error so the app can
                // catch up/repair the same group instead of sending a ghost message.
                NuruNuruError::MlsError(format!("create_message: {e}"))
            })?;

        Ok(EncryptedMessageData {
            content: event.as_json(),
            tags: Self::tags_to_vecs(event.tags.to_vec()),
            ephemeral_pubkey: event.pubkey.to_hex(),
        })
    }

    /// Compute a `CommitDelta` by diffing membership before vs after MDK
    /// applied the commit. Issue #178 #5: lets the UI render add/remove
    /// without a racey `get_group_info` re-query.
    fn build_commit_delta_result(
        &self,
        group_id_hex: &str,
        members_before: Option<BTreeSet<String>>,
        log_tag: &str,
        event_id: &nostr::EventId,
    ) -> crate::types::MlsProcessResult {
        let (members_after, epoch_after) = match self.resolve_group_id(group_id_hex) {
            Ok(gid) => {
                let after: BTreeSet<String> = self
                    .mdk
                    .get_members(&gid)
                    .map(|set| set.iter().map(|pk| pk.to_hex()).collect())
                    .unwrap_or_default();
                let epoch = self
                    .mdk
                    .get_group(&gid)
                    .ok()
                    .flatten()
                    .map(|g| g.epoch)
                    .unwrap_or(0);
                (after, epoch)
            }
            Err(_) => (BTreeSet::new(), 0),
        };
        let before = members_before.unwrap_or_default();
        let added: Vec<String> = members_after.difference(&before).cloned().collect();
        let removed: Vec<String> = before.difference(&members_after).cloned().collect();
        tracing::info!(
            "[MLS] process_message {} group={} event_id={} added={} removed={} epoch={}",
            log_tag,
            group_id_hex,
            event_id.to_hex(),
            added.len(),
            removed.len(),
            epoch_after
        );
        crate::types::MlsProcessResult::Commit {
            group_id_hex: group_id_hex.to_string(),
            delta: CommitDelta {
                added_pubkeys: added,
                removed_pubkeys: removed,
                epoch_after,
            },
        }
    }

    /// Process an incoming Kind-445 event (Proposal / Commit / Application).
    pub fn process_message_result(
        &self,
        group_id_hex: &str,
        event_json: &str,
    ) -> Result<crate::types::MlsProcessResult> {
        let event: nostr::Event = serde_json::from_str(event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid event JSON: {e}")))?;

        // MIP-03: Group Event MUST be kind:445.
        if u16::from(event.kind) != 445 {
            return Ok(crate::types::MlsProcessResult::StateUpdate {
                kind: "unhandled:Unprocessable:invalid_kind".to_string(),
            });
        }

        // MIP-03 decryption edge-case guards before handing to MDK:
        // - content must be valid base64
        // - decoded payload must be at least 28 bytes (12-byte nonce + 16-byte tag)
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(event.content.as_bytes())
            .map_err(|_| {
                NuruNuruError::MlsError("process_message: invalid_base64_content".to_string())
            })?;
        if decoded.len() < 28 {
            return Err(NuruNuruError::MlsError(
                "process_message: malformed_content_too_short".to_string(),
            ));
        }

        let h_tag = event
            .tags
            .iter()
            .find(|t| t.kind() == nostr::TagKind::h())
            .and_then(|t| t.content())
            .map(|s| s.to_string())
            .unwrap_or_default();

        tracing::info!(
            "[MLS] process_message start group={} event_id={} author={} created_at={} kind={} h_tag={:?}",
            group_id_hex,
            event.id.to_hex(),
            redact_hex_prefix(&event.pubkey.to_hex()),
            event.created_at.as_secs(),
            u16::from(event.kind),
            if h_tag.is_empty() { None } else { Some(h_tag.as_str()) }
        );

        // MIP-03 routing guard: outer kind:445 MUST carry matching h tag.
        if h_tag.is_empty() {
            return Ok(crate::types::MlsProcessResult::StateUpdate {
                kind: "unhandled:Unprocessable:missing_h_tag".to_string(),
            });
        }
        if h_tag != group_id_hex {
            return Ok(crate::types::MlsProcessResult::StateUpdate {
                kind: "unhandled:Unprocessable:group_id_mismatch".to_string(),
            });
        }

        // Snapshot members so we can diff after Commit (MDK 0.8 only returns mls_group_id).
        let members_before: Option<BTreeSet<String>> = self
            .resolve_group_id(group_id_hex)
            .ok()
            .and_then(|gid| self.mdk.get_members(&gid).ok())
            .map(|set| set.iter().map(|pk| pk.to_hex()).collect());

        // Issue #183: persist the wrapper into the replay cache *before*
        // handing to MDK so a peer Commit that we currently cannot decrypt
        // (state_not_ready) survives relay aging and can be retried later
        // by `catch_up_to_peer`. Best-effort: failures here must not change
        // the existing receive semantics (PR #180 / AC3).
        if let Err(e) = self.cache_kind445_event(group_id_hex, &event) {
            tracing::debug!(
                "[MLS][replay_cache] cache write failed group={} event_id={} reason={}",
                group_id_hex,
                event.id.to_hex(),
                mls_error_label(&e)
            );
        }

        let result = self
            .mdk
            .process_message(&event)
            .map_err(|e| NuruNuruError::MlsError(format!("process_message: {e}")))?;

        match result {
            mdk_core::messages::MessageProcessingResult::ApplicationMessage(msg) => {
                tracing::info!(
                    "[MLS] process_message application group={} event_id={} sender={} len={}",
                    group_id_hex,
                    event.id.to_hex(),
                    redact_hex_prefix(&msg.pubkey.to_hex()),
                    msg.content.len()
                );
                let content = Self::display_content_from_decrypted_event(&msg.event, msg.content);
                Ok(crate::types::MlsProcessResult::ApplicationMessage(
                    DecryptedMessage {
                        sender_pubkey: msg.pubkey.to_hex(),
                        content,
                        timestamp: msg.created_at.as_secs(),
                        group_id_hex: group_id_hex.to_string(),
                    },
                ))
            }
            mdk_core::messages::MessageProcessingResult::Commit { .. } => Ok(
                self.build_commit_delta_result(group_id_hex, members_before, "commit", &event.id)
            ),
            // MDK auto-commits some proposals (e.g. self-remove when receiver
            // is admin); surface them as commits too.
            mdk_core::messages::MessageProcessingResult::Proposal(_) => Ok(self
                .build_commit_delta_result(
                    group_id_hex,
                    members_before,
                    "proposal_auto_committed",
                    &event.id,
                )),
            mdk_core::messages::MessageProcessingResult::PendingProposal { .. } => {
                // A pending proposal sits in MDK storage. Multi-member groups
                // will stall on the next clean-state operation ("pending
                // proposal exists") and the group can fork. Surface a signal
                // so the engine/app schedules a `self_update` commit to resolve
                // it instead of silently logging.
                tracing::warn!(
                    "[MLS] process_message pending_proposal group={} event_id={} — needs self_update",
                    group_id_hex,
                    event.id.to_hex()
                );
                Ok(crate::types::MlsProcessResult::NeedsSelfUpdate {
                    group_id_hex: group_id_hex.to_string(),
                    reason: "pending_proposal".to_string(),
                })
            }
            other => {
                let kind_s = format!("{:?}", other);
                if kind_s.contains("Unprocessable") {
                    // Diagnostic classification for retryable state-updates.
                    // Keep machine-readable reason in `kind` for app-layer policy,
                    // and emit detailed logs for on-device triage.
                    let h_tag = event
                        .tags
                        .iter()
                        .find(|t| t.kind() == nostr::TagKind::h())
                        .and_then(|t| t.content())
                        .map(|s| s.to_string())
                        .unwrap_or_default();
                    let group_info = self.get_group_info(group_id_hex).ok();
                    let local_epoch = group_info.as_ref().map(|g| g.epoch).unwrap_or_default();
                    let local_admins = group_info
                        .as_ref()
                        .map(|g| g.admin_pubkeys.len())
                        .unwrap_or_default();
                    let local_relays = group_info
                        .as_ref()
                        .map(|g| g.relays.len())
                        .unwrap_or_default();
                    let reason = if h_tag.is_empty() {
                        "missing_h_tag"
                    } else if h_tag != group_id_hex {
                        "group_id_mismatch"
                    } else {
                        "state_not_ready"
                    };
                    tracing::warn!(
                        "[MLS] process_message retryable_unprocessable group={} event_id={} reason={} local_epoch={} local_admins={} local_relays={} event_h={} author_prefix={} created_at={} result={}",
                        group_id_hex,
                        event.id.to_hex(),
                        reason,
                        local_epoch,
                        local_admins,
                        local_relays,
                        h_tag,
                        redact_hex_prefix(&event.pubkey.to_hex()),
                        event.created_at.as_secs(),
                        mls_process_result_label(&other)
                    );
                    Ok(crate::types::MlsProcessResult::StateUpdate {
                        kind: format!("unhandled:Unprocessable:{}", reason),
                    })
                } else {
                    let result_label = mls_process_result_label(&other);
                    tracing::warn!(
                        "[MLS] process_message unhandled group={} event_id={} author_prefix={} created_at={} result={} — treated as state update",
                        group_id_hex,
                        event.id.to_hex(),
                        redact_hex_prefix(&event.pubkey.to_hex()),
                        event.created_at.as_secs(),
                        result_label
                    );
                    Ok(crate::types::MlsProcessResult::StateUpdate {
                        kind: format!("unhandled:{result_label}"),
                    })
                }
            }
        }
    }

    /// Backward-compatible wrapper that returns only application messages.
    pub fn process_message(
        &self,
        group_id_hex: &str,
        event_json: &str,
    ) -> Result<DecryptedMessage> {
        match self.process_message_result(group_id_hex, event_json)? {
            crate::types::MlsProcessResult::ApplicationMessage(msg) => Ok(msg),
            crate::types::MlsProcessResult::Commit { .. }
            | crate::types::MlsProcessResult::NeedsSelfUpdate { .. }
            | crate::types::MlsProcessResult::StateUpdate { .. } => {
                Err(NuruNuruError::MlsStateUpdate)
            }
        }
    }

    // ─── Welcome (Kind 444) ───────────────────────────────────────────────

    /// Process + accept a Welcome in one step (back-compat). Prefer the split
    /// API ([`Self::preview_welcome_rumor`] +
    /// [`Self::accept_pending_welcome`] / [`Self::decline_pending_welcome`])
    /// so users can decline — see issue #178 #4.
    pub fn process_welcome(&self, welcome_event_json: &str) -> Result<MlsGroupInfo> {
        let mut rumor: UnsignedEvent = serde_json::from_str(welcome_event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid welcome JSON: {e}")))?;

        // Legacy callers do not have the outer 1059 wrapper id. Use the rumor id
        // only as a stable local tracking key in that path.
        let wrapper_event_id: nostr::EventId = rumor.id();
        self.process_welcome_rumor(&wrapper_event_id, &rumor)
    }

    /// Back-compat fused process+accept. Prefer the split API for new code.
    pub fn process_welcome_rumor(
        &self,
        wrapper_event_id: &nostr::EventId,
        rumor: &UnsignedEvent,
    ) -> Result<MlsGroupInfo> {
        let preview = self.preview_welcome_rumor(wrapper_event_id, rumor)?;
        let welcome_id = EventId::from_hex(&preview.welcome_event_id_hex)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid welcome_event_id: {e}")))?;
        let _ = self.accept_pending_welcome(&welcome_id)?;

        let group_id_hex = preview.group_id_hex.clone();
        let is_dm = preview.is_dm;

        // The Welcome record itself doesn't carry disappearing_message_secs;
        // read it from the stored group metadata if already available.
        let disappearing_message_secs = self
            .resolve_group_id(&group_id_hex)
            .ok()
            .and_then(|gid| self.mdk.get_group(&gid).ok().flatten())
            .and_then(|g| g.disappearing_message_secs);

        Ok(MlsGroupInfo {
            group_id_hex,
            name: preview.group_name,
            description: preview.group_description,
            admin_pubkeys: preview.group_admin_pubkeys,
            member_pubkeys: Vec::new(),
            relays: preview.group_relays,
            created_at: 0,
            epoch: 0,
            disappearing_message_secs,
            is_dm,
        })
    }

    /// Stage a Welcome without joining. `wrapper_event_id` is the outer
    /// kind:1059 id when available (MDK keys processed state on it).
    pub fn preview_welcome_rumor(
        &self,
        wrapper_event_id: &nostr::EventId,
        rumor: &UnsignedEvent,
    ) -> Result<PendingWelcome> {
        let welcome = self
            .mdk
            .process_welcome(wrapper_event_id, rumor)
            .map_err(|e| NuruNuruError::MlsError(format!("process_welcome: {e}")))?;

        Ok(Self::welcome_to_pending(&welcome))
    }

    fn welcome_to_pending(
        welcome: &mdk_storage_traits::welcomes::types::Welcome,
    ) -> PendingWelcome {
        PendingWelcome {
            welcome_event_id_hex: welcome.id.to_hex(),
            wrapper_event_id_hex: welcome.wrapper_event_id.to_hex(),
            group_id_hex: hex::encode(welcome.nostr_group_id),
            group_name: welcome.group_name.clone(),
            group_description: welcome.group_description.clone(),
            group_admin_pubkeys: welcome
                .group_admin_pubkeys
                .iter()
                .map(|pk| pk.to_hex())
                .collect(),
            group_relays: welcome.group_relays.iter().map(|r| r.to_string()).collect(),
            welcomer_pubkey: welcome.welcomer.to_hex(),
            member_count: welcome.member_count,
            is_dm: welcome.member_count <= 2,
        }
    }

    /// List Welcomes that have been previewed but not yet accepted/declined.
    pub fn get_pending_welcomes(&self) -> Result<Vec<PendingWelcome>> {
        let welcomes = self
            .mdk
            .get_pending_welcomes(None)
            .map_err(|e| NuruNuruError::MlsError(format!("get_pending_welcomes: {e}")))?;
        Ok(welcomes.iter().map(Self::welcome_to_pending).collect())
    }

    /// Accept a previewed Welcome — joins the group and marks the MIP-02
    /// post-join self-update required.
    pub fn accept_pending_welcome(&self, welcome_event_id: &EventId) -> Result<PendingWelcome> {
        let welcome = self
            .mdk
            .get_welcome(welcome_event_id)
            .map_err(|e| NuruNuruError::MlsError(format!("get_welcome: {e}")))?
            .ok_or_else(|| {
                NuruNuruError::MlsError(format!(
                    "accept_pending_welcome: welcome not found for welcome_event_id={}",
                    welcome_event_id.to_hex()
                ))
            })?;

        self.mdk
            .accept_welcome(&welcome)
            .map_err(|e| NuruNuruError::MlsError(format!("accept_welcome: {e}")))?;

        // TODO(mdk): receive-side init-key delete API not yet exposed (issue #178 #8).
        // Send-side `delete_consumed_key_package_by_hash_ref` covers the common case.

        Ok(Self::welcome_to_pending(&welcome))
    }

    /// Decline a previewed Welcome.
    pub fn decline_pending_welcome(&self, welcome_event_id: &EventId) -> Result<()> {
        let welcome = self
            .mdk
            .get_welcome(welcome_event_id)
            .map_err(|e| NuruNuruError::MlsError(format!("get_welcome: {e}")))?
            .ok_or_else(|| {
                NuruNuruError::MlsError(format!(
                    "decline_pending_welcome: welcome not found for welcome_event_id={}",
                    welcome_event_id.to_hex()
                ))
            })?;

        self.mdk
            .decline_welcome(&welcome)
            .map_err(|e| NuruNuruError::MlsError(format!("decline_welcome: {e}")))
    }

    // ─── Group queries ────────────────────────────────────────────────────

    /// List all groups the local user is a member of.
    pub fn list_groups(&self) -> Result<Vec<MlsGroupInfo>> {
        let groups = self
            .mdk
            .get_groups()
            .map_err(|e| NuruNuruError::MlsError(format!("get_groups: {e}")))?;

        let mut infos = Vec::with_capacity(groups.len());
        for group in groups {
            let group_id = group.mls_group_id.clone();
            let mut info = Self::group_to_info(group);

            // Enrich with relay and member info
            match self.mdk.get_relays(&group_id) {
                Ok(relays) => info.relays = relays.iter().map(|r| r.to_string()).collect(),
                Err(e) => tracing::warn!(
                    "[MLS] get_relays failed for {}: {}",
                    info.group_id_hex,
                    mls_error_label(&e)
                ),
            }
            match self.mdk.get_members(&group_id) {
                Ok(members) => {
                    info.member_pubkeys = members.iter().map(|pk| pk.to_hex()).collect();
                    info.is_dm = info.member_pubkeys.len() <= 2;
                }
                Err(e) => tracing::warn!(
                    "[MLS] get_members failed for {}: {}",
                    info.group_id_hex,
                    mls_error_label(&e)
                ),
            }

            infos.push(info);
        }

        Ok(infos)
    }

    /// Get metadata for a single group.
    pub fn get_group_info(&self, group_id_hex: &str) -> Result<MlsGroupInfo> {
        let group_id = self.resolve_group_id(group_id_hex)?;

        let group = self
            .mdk
            .get_group(&group_id)
            .map_err(|e| NuruNuruError::MlsError(format!("get_group: {e}")))?
            .ok_or_else(|| NuruNuruError::MlsError(format!("Group not found: {group_id_hex}")))?;

        let mut info = Self::group_to_info(group);

        match self.mdk.get_relays(&group_id) {
            Ok(relays) => info.relays = relays.iter().map(|r| r.to_string()).collect(),
            Err(e) => tracing::warn!(
                "[MLS] get_relays failed for {}: {}",
                group_id_hex,
                mls_error_label(&e)
            ),
        }
        match self.mdk.get_members(&group_id) {
            Ok(members) => {
                info.member_pubkeys = members.iter().map(|pk| pk.to_hex()).collect();
                info.is_dm = info.member_pubkeys.len() <= 2;
            }
            Err(e) => tracing::warn!(
                "[MLS] get_members failed for {}: {}",
                group_id_hex,
                mls_error_label(&e)
            ),
        }

        Ok(info)
    }

    /// Retrieve previously decrypted application messages for a group from
    /// MDK's local SQLite store.  This is the persistent complement to
    /// `process_message` — use this on app startup to restore history without
    /// needing to re-process relay events (which would fail after epoch moves).
    pub fn get_message_history(
        &self,
        nostr_group_id_hex: &str,
        limit: u64,
    ) -> Result<Vec<DecryptedMessage>> {
        let mls_group_id = self.resolve_group_id(nostr_group_id_hex)?;

        let pagination = mdk_storage_traits::groups::Pagination::new(Some(limit as usize), Some(0));
        let messages = self
            .mdk
            .get_messages(&mls_group_id, Some(pagination))
            .map_err(|e| NuruNuruError::MlsError(format!("get_messages: {e}")))?;

        Ok(messages
            .into_iter()
            .map(|m| DecryptedMessage {
                sender_pubkey: m.pubkey.to_hex(),
                content: Self::display_content_from_decrypted_event(&m.event, m.content),
                timestamp: m.created_at.as_secs(),
                group_id_hex: nostr_group_id_hex.to_string(),
            })
            .collect())
    }

    /// Return the SQLite path this manager is using (for diagnostics).
    pub fn db_path(&self) -> &str {
        &self.db_path
    }

    /// Return the replay-cache SQLite path (Issue #183, for diagnostics).
    pub fn replay_cache_path(&self) -> &str {
        &self.replay_cache_path
    }
}

// ─── Issue #183: Kind-445 replay cache + peer-epoch catch-up ─────────────────

/// Schema for the sidecar replay cache. Stored in a plain (un-encrypted)
/// SQLite file: the rows carry MLS ciphertext only — never plaintext — and
/// the contents are protected by the group's MLS keys. Storing MLS
/// ciphertext at rest is the same threat model as keeping a Nostr relay
/// dump on the device.
const REPLAY_CACHE_SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS replay_cache (
        event_id    TEXT PRIMARY KEY,
        group_id    TEXT NOT NULL,
        created_at  INTEGER NOT NULL,
        cached_at   INTEGER NOT NULL,
        event_json  TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS replay_cache_group_idx
        ON replay_cache(group_id, created_at);
    CREATE INDEX IF NOT EXISTS replay_cache_cached_at_idx
        ON replay_cache(cached_at);
";

impl MlsManager {
    /// Open (or initialise) the replay-cache SQLite connection on first use.
    /// Returns an error only when the file cannot be created at all;
    /// schema/index creation failures are also surfaced so the caller can
    /// log a diagnostic, but callers that just want best-effort caching
    /// should treat them as recoverable.
    fn ensure_replay_cache(&self) -> Result<()> {
        let mut guard = self.replay_cache.lock().map_err(|_| {
            NuruNuruError::MlsError("replay_cache mutex poisoned".to_string())
        })?;
        if guard.is_some() {
            return Ok(());
        }
        let conn = rusqlite::Connection::open(&self.replay_cache_path).map_err(|e| {
            NuruNuruError::MlsError(format!("replay_cache open: {e}"))
        })?;
        conn.execute_batch(REPLAY_CACHE_SCHEMA).map_err(|e| {
            NuruNuruError::MlsError(format!("replay_cache schema: {e}"))
        })?;
        *guard = Some(conn);
        Ok(())
    }

    /// Issue #183: persist a single Kind-445 wrapper into the replay cache.
    ///
    /// Called from `process_message_result` for every kind:445 event that
    /// passes the basic envelope guards (kind, h-tag, base64 length). MLS
    /// ciphertext only — never plaintext. Best-effort: any storage error
    /// is logged at debug level and swallowed so the receive path stays
    /// non-destructive (AC3 — no regression to PR #180 receive semantics).
    pub fn cache_kind445_event(&self, group_id_hex: &str, event: &nostr::Event) -> Result<()> {
        self.ensure_replay_cache()?;
        let event_json = serde_json::to_string(event)
            .map_err(|e| NuruNuruError::MlsError(format!("replay_cache serialize: {e}")))?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let event_id = event.id.to_hex();
        let created_at = event.created_at.as_secs() as i64;
        let group = group_id_hex.to_string();

        let guard = self.replay_cache.lock().map_err(|_| {
            NuruNuruError::MlsError("replay_cache mutex poisoned".to_string())
        })?;
        let conn = guard
            .as_ref()
            .ok_or_else(|| NuruNuruError::MlsError("replay_cache not initialised".to_string()))?;

        // Idempotent INSERT — duplicate event_id is the common case.
        conn.execute(
            "INSERT OR IGNORE INTO replay_cache \
                (event_id, group_id, created_at, cached_at, event_json) \
              VALUES (?1, ?2, ?3, ?4, ?5)",
            rusqlite::params![event_id, group, created_at, now, event_json],
        )
        .map_err(|e| NuruNuruError::MlsError(format!("replay_cache insert: {e}")))?;

        // Per-group cap: evict oldest entries if we are over MLS_REPLAY_CACHE_MAX_PER_GROUP.
        let count: u64 = conn
            .query_row(
                "SELECT COUNT(1) FROM replay_cache WHERE group_id = ?1",
                rusqlite::params![group],
                |row| row.get::<_, i64>(0).map(|v| v as u64),
            )
            .unwrap_or(0);
        if count > MLS_REPLAY_CACHE_MAX_PER_GROUP {
            let excess = count - MLS_REPLAY_CACHE_MAX_PER_GROUP;
            let _ = conn.execute(
                "DELETE FROM replay_cache \
                    WHERE event_id IN ( \
                        SELECT event_id FROM replay_cache \
                          WHERE group_id = ?1 \
                          ORDER BY created_at ASC \
                          LIMIT ?2 \
                    )",
                rusqlite::params![group, excess as i64],
            );
        }
        Ok(())
    }

    /// Issue #183: prune cache entries older than [`MLS_REPLAY_CACHE_TTL_SECS`].
    /// Returns the number of rows removed. Safe to call on any cadence
    /// (no-op when the cache file does not exist).
    pub fn prune_replay_cache(&self) -> Result<u64> {
        if !std::path::Path::new(&self.replay_cache_path).exists() {
            return Ok(0);
        }
        self.ensure_replay_cache()?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let cutoff = now - MLS_REPLAY_CACHE_TTL_SECS as i64;
        let guard = self.replay_cache.lock().map_err(|_| {
            NuruNuruError::MlsError("replay_cache mutex poisoned".to_string())
        })?;
        let conn = guard.as_ref().ok_or_else(|| {
            NuruNuruError::MlsError("replay_cache not initialised".to_string())
        })?;
        let removed = conn
            .execute(
                "DELETE FROM replay_cache WHERE cached_at < ?1",
                rusqlite::params![cutoff],
            )
            .map_err(|e| NuruNuruError::MlsError(format!("replay_cache prune: {e}")))?;
        Ok(removed as u64)
    }

    /// Issue #183: load all cached wrappers for `group_id_hex`, oldest first.
    ///
    /// Returns the raw event JSON; the caller can deduplicate against any
    /// additional candidates (e.g. fresh relay reads) before calling
    /// [`Self::catch_up_to_peer`].
    pub fn load_cached_kind445(&self, group_id_hex: &str) -> Result<Vec<String>> {
        if !std::path::Path::new(&self.replay_cache_path).exists() {
            return Ok(Vec::new());
        }
        self.ensure_replay_cache()?;
        let guard = self.replay_cache.lock().map_err(|_| {
            NuruNuruError::MlsError("replay_cache mutex poisoned".to_string())
        })?;
        let conn = guard.as_ref().ok_or_else(|| {
            NuruNuruError::MlsError("replay_cache not initialised".to_string())
        })?;
        let mut stmt = conn
            .prepare(
                "SELECT event_json FROM replay_cache \
                   WHERE group_id = ?1 \
                ORDER BY created_at ASC, event_id ASC",
            )
            .map_err(|e| NuruNuruError::MlsError(format!("replay_cache prepare: {e}")))?;
        let rows = stmt
            .query_map(rusqlite::params![group_id_hex], |row| row.get::<_, String>(0))
            .map_err(|e| NuruNuruError::MlsError(format!("replay_cache query: {e}")))?;
        let mut out = Vec::new();
        for row in rows {
            match row {
                Ok(s) => out.push(s),
                Err(e) => {
                    tracing::warn!(
                        "[MLS][replay_cache] row decode failed: {}",
                        mls_error_label(&e)
                    );
                }
            }
        }
        Ok(out)
    }

    /// Issue #183: per-group cache size (for diagnostics + tests).
    pub fn replay_cache_size(&self, group_id_hex: &str) -> Result<u64> {
        if !std::path::Path::new(&self.replay_cache_path).exists() {
            return Ok(0);
        }
        self.ensure_replay_cache()?;
        let guard = self.replay_cache.lock().map_err(|_| {
            NuruNuruError::MlsError("replay_cache mutex poisoned".to_string())
        })?;
        let conn = guard.as_ref().ok_or_else(|| {
            NuruNuruError::MlsError("replay_cache not initialised".to_string())
        })?;
        let count: u64 = conn
            .query_row(
                "SELECT COUNT(1) FROM replay_cache WHERE group_id = ?1",
                rusqlite::params![group_id_hex],
                |row| row.get::<_, i64>(0).map(|v| v as u64),
            )
            .unwrap_or(0);
        Ok(count)
    }

    /// Issue #183: peer-epoch catch-up.
    ///
    /// Attempts to advance the local MDK epoch for `group_id_hex` by
    /// replaying every available Kind-445 wrapper in `created_at` order
    /// until either every event has been classified (Application / Commit /
    /// permanently dropped) or progress stalls.
    ///
    /// Inputs:
    ///   * `candidate_events_json` — raw event JSON strings the caller just
    ///     fetched from relays. May overlap with the replay cache; the
    ///     wrapper dedupes by `event_id`.
    ///
    /// Returns a [`crate::types::MlsCatchUpReport`] describing what was
    /// applied and whether the local epoch is now usable. The caller's UI
    /// layer (TalkViewModel on Android) uses
    /// [`crate::types::MlsCatchUpStatus`] to decide whether to prompt the
    /// user to recreate the conversation (AC2).
    ///
    /// Receive-path semantics: this method only invokes
    /// `mdk.process_message`. It never calls `clear_pending_commit` or
    /// `merge_pending_commit`. This is the same contract `process_message_result`
    /// holds, so PR #180's receive-path invariants are preserved (AC3).
    pub fn catch_up_to_peer(
        &self,
        group_id_hex: &str,
        candidate_events_json: &[String],
    ) -> Result<crate::types::MlsCatchUpReport> {
        use crate::types::{MlsCatchUpReport, MlsCatchUpStatus};

        // Resolve up-front so a missing group short-circuits with a clean status.
        let group_resolved = self.resolve_group_id(group_id_hex);
        let group_id_for_epoch = match group_resolved {
            Ok(g) => g,
            Err(_) => {
                return Ok(MlsCatchUpReport {
                    group_id_hex: group_id_hex.to_string(),
                    epoch_before: 0,
                    epoch_after: 0,
                    candidates_considered: 0,
                    application_messages_applied: 0,
                    commits_applied: 0,
                    still_unprocessable: 0,
                    cache_hits: 0,
                    status: MlsCatchUpStatus::NoSuchGroup,
                });
            }
        };

        let epoch_before = self
            .mdk
            .get_group(&group_id_for_epoch)
            .ok()
            .flatten()
            .map(|g| g.epoch)
            .unwrap_or(0);

        // Collect caller-supplied candidates.
        let mut by_id: std::collections::BTreeMap<String, (u64, String)> =
            std::collections::BTreeMap::new();
        for raw in candidate_events_json {
            // Parse just enough to dedupe + order; full validation happens in
            // process_message_result.
            let parsed: std::result::Result<nostr::Event, _> = serde_json::from_str(raw);
            if let Ok(ev) = parsed {
                if u16::from(ev.kind) != 445 {
                    continue;
                }
                by_id.insert(ev.id.to_hex(), (ev.created_at.as_secs(), raw.clone()));
            }
        }
        let supplied_count = by_id.len();

        // Merge in cached wrappers. Failures degrade gracefully — the cache
        // is best-effort by design.
        let cached_jsons = match self.load_cached_kind445(group_id_hex) {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(
                    "[MLS][catch_up] replay cache read failed group={} reason={}",
                    group_id_hex,
                    mls_error_label(&e)
                );
                Vec::new()
            }
        };
        let mut cache_hits = 0u32;
        for raw in cached_jsons {
            let parsed: std::result::Result<nostr::Event, _> = serde_json::from_str(&raw);
            if let Ok(ev) = parsed {
                if u16::from(ev.kind) != 445 {
                    continue;
                }
                let id_hex = ev.id.to_hex();
                if !by_id.contains_key(&id_hex) {
                    cache_hits += 1;
                }
                by_id
                    .entry(id_hex)
                    .or_insert_with(|| (ev.created_at.as_secs(), raw));
            }
        }

        let mut ordered: Vec<(String, u64, String)> = by_id
            .into_iter()
            .map(|(id, (ts, raw))| (id, ts, raw))
            .collect();
        ordered.sort_by(|a, b| a.1.cmp(&b.1).then_with(|| a.0.cmp(&b.0)));
        let candidates_considered = ordered.len() as u32;

        tracing::info!(
            "[MLS][catch_up] start group={} supplied={} cache_hits={} total={} epoch_before={}",
            group_id_hex,
            supplied_count,
            cache_hits,
            candidates_considered,
            epoch_before
        );

        let mut application_messages_applied = 0u32;
        let mut commits_applied = 0u32;
        let mut applied_or_dropped: std::collections::HashSet<String> =
            std::collections::HashSet::new();

        // Deterministic, finite replay. 8 passes is enough to drain a chain
        // of out-of-order commits/applications without risking an infinite
        // loop on a pathological event set.
        const MAX_PASSES: u8 = 8;
        for pass in 0..MAX_PASSES {
            let mut progressed = false;
            for (id, _ts, raw) in &ordered {
                if applied_or_dropped.contains(id) {
                    continue;
                }
                match self.process_message_result(group_id_hex, raw) {
                    Ok(crate::types::MlsProcessResult::ApplicationMessage(_)) => {
                        application_messages_applied += 1;
                        applied_or_dropped.insert(id.clone());
                        progressed = true;
                    }
                    Ok(crate::types::MlsProcessResult::Commit { .. }) => {
                        commits_applied += 1;
                        applied_or_dropped.insert(id.clone());
                        progressed = true;
                    }
                    Ok(crate::types::MlsProcessResult::NeedsSelfUpdate { .. }) => {
                        // Pending proposal: mark as touched so we do not
                        // re-attempt forever, but do not credit it as a
                        // catch-up "apply" since no new app message is now
                        // decryptable.
                        applied_or_dropped.insert(id.clone());
                        progressed = true;
                    }
                    Ok(crate::types::MlsProcessResult::StateUpdate { kind }) => {
                        // Permanent classifications: drop immediately so we
                        // do not retry. Retryable (state_not_ready / pending
                        // / proposal / commit-shape) stays in the pool until
                        // a later pass; another event may advance MDK enough
                        // to make it decryptable.
                        let is_permanent = kind.starts_with("unhandled:Unprocessable:missing_h_tag")
                            || kind.starts_with("unhandled:Unprocessable:group_id_mismatch")
                            || kind.starts_with("unhandled:Unprocessable:invalid_kind");
                        if is_permanent {
                            applied_or_dropped.insert(id.clone());
                            progressed = true;
                        }
                    }
                    Err(e) => {
                        // process_message_result itself failed.
                        //
                        // MDK surfaces state_not_ready / missing-epoch-key
                        // cases as `Err("Failed to decrypt message with any
                        // exporter secret...")` rather than
                        // `Ok(StateUpdate { state_not_ready })`. Those are
                        // the exact events the catch-up loop wants to retry
                        // — leave them in the unresolved pool so a later
                        // pass (after a sibling Commit advances MDK) can
                        // decrypt them.
                        //
                        // Only structural problems (bad base64, wrong h tag,
                        // not a Nostr event JSON, bad signature) are
                        // permanent drops. The classifier mirrors Android's
                        // `isPermanentMlsProcessDropError` so the two sides
                        // agree on what is recoverable.
                        if is_permanent_process_error(&e) {
                            applied_or_dropped.insert(id.clone());
                            progressed = true;
                        }
                    }
                }
            }
            if !progressed {
                tracing::info!(
                    "[MLS][catch_up] pass={} stalled — exiting early group={}",
                    pass,
                    group_id_hex
                );
                break;
            }
        }

        let still_unprocessable =
            (candidates_considered as usize - applied_or_dropped.len()) as u32;
        let epoch_after = self
            .mdk
            .get_group(&group_id_for_epoch)
            .ok()
            .flatten()
            .map(|g| g.epoch)
            .unwrap_or(epoch_before);

        let status = if epoch_after > epoch_before && still_unprocessable == 0 {
            MlsCatchUpStatus::Recovered
        } else if (epoch_after > epoch_before || commits_applied > 0
            || application_messages_applied > 0)
            && still_unprocessable > 0
        {
            MlsCatchUpStatus::PartiallyRecovered
        } else if still_unprocessable > 0 {
            MlsCatchUpStatus::NotRecoverable
        } else {
            // No retryables, no progress — already aligned with peer.
            MlsCatchUpStatus::Recovered
        };

        tracing::info!(
            "[MLS][catch_up] done group={} epoch_before={} epoch_after={} considered={} apps={} commits={} unresolved={} cache_hits={} status={:?}",
            group_id_hex,
            epoch_before,
            epoch_after,
            candidates_considered,
            application_messages_applied,
            commits_applied,
            still_unprocessable,
            cache_hits,
            status
        );

        Ok(MlsCatchUpReport {
            group_id_hex: group_id_hex.to_string(),
            epoch_before,
            epoch_after,
            candidates_considered,
            application_messages_applied,
            commits_applied,
            still_unprocessable,
            cache_hits,
            status,
        })
    }
}
