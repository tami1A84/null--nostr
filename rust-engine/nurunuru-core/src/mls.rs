//! MLS (Messaging Layer Security, RFC 9420) manager for NIP-EE / Marmot.
//!
//! Wraps `mdk-core` (`MDK<MdkSqliteStorage>`) and exposes a Nostr-centric API
//! that produces/consumes event payloads for the following NIP-EE kinds:
//!
//! | Kind  | Purpose                                      |
//! |-------|----------------------------------------------|
//! | 443   | KeyPackage — async group join credential     |
//! | 444   | Welcome — gift-wrapped MLS Welcome object    |
//! | 445   | Group Message — encrypted MLS message/commit |
//! | 10051 | KeyPackage relay list                        |
//!
//! ## API notes
//!
//! MDK signs Kind-445 events internally with **ephemeral keys** for privacy
//! (forward secrecy + sender anonymisation).  The returned `EncryptedMessageData.content`
//! contains the **full signed event JSON**, ready for `publish_raw_event()`.
//!
//! Kind-443 (KeyPackage) and Kind-444 (Welcome) events are **unsigned** —
//! the caller signs them via the internal signer or Amber.

use mdk_core::groups::NostrGroupConfigData;
use mdk_sqlite_storage::MdkSqliteStorage;
use nostr::{EventBuilder, JsonUtil, PublicKey, RelayUrl, UnsignedEvent};

use crate::error::{NuruNuruError, Result};
use crate::types::{
    AddMemberResult, DecryptedMessage, EncryptedMessageData, KeyPackageEventData, MlsGroupInfo,
    WelcomeEventData,
};

// ─── Type alias ──────────────────────────────────────────────────────────────

type Mdk = mdk_core::MDK<MdkSqliteStorage>;

// ─── MlsManager ──────────────────────────────────────────────────────────────

/// Thin wrapper around `mdk_core::MDK` that translates MLS operations into
/// Nostr event payloads ready for signing and publishing.
///
/// `MlsManager` is not `Clone`; hold it behind `Option<MlsManager>` in the
/// engine.  Read-only (anonymous) clients keep `None`.
pub struct MlsManager {
    mdk: Mdk,
    /// Hex-encoded Nostr public key of the local user.  Set on `login()`.
    /// Uses `RwLock` so `set_user_pubkey` can be called via `&self` from
    /// `NuruNuruEngine::login()` without requiring `&mut self` on the engine.
    user_pubkey_hex: std::sync::RwLock<String>,
    db_path: String,
}

impl MlsManager {
    /// Open (or create) the MLS SQLite database and initialise MDK.
    ///
    /// `db_path`      — absolute path to the `.sqlite3` file
    /// `nostr_pubkey` — hex-encoded Nostr public key (may be empty at init,
    ///                  call `set_user_pubkey` after login)
    pub fn new(db_path: &str, nostr_pubkey: &str) -> Result<Self> {
        let storage = MdkSqliteStorage::new_unencrypted(db_path)
            .map_err(|e| NuruNuruError::MlsError(format!("SQLite open: {e}")))?;

        let mdk = Mdk::new(storage);

        Ok(Self {
            mdk,
            user_pubkey_hex: std::sync::RwLock::new(nostr_pubkey.to_string()),
            db_path: db_path.to_string(),
        })
    }

    /// Update the stored user pubkey after login().
    /// Takes `&self` so it can be called from `NuruNuruEngine::login()` via `Arc<Self>`.
    pub fn set_user_pubkey(&self, pubkey_hex: &str) {
        if let Ok(mut w) = self.user_pubkey_hex.write() {
            *w = pubkey_hex.to_string();
        }
    }

    fn user_pubkey(&self) -> Result<PublicKey> {
        let hex = self.user_pubkey_hex.read()
            .map_err(|_| NuruNuruError::MlsError("pubkey lock poisoned".to_string()))?;
        PublicKey::from_hex(&*hex)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid user pubkey: {e}")))
    }

    /// Resolve a `nostr_group_id` hex string (32 bytes = 64 hex chars) to the
    /// internal `mdk_storage_traits::GroupId` (= `mls_group_id`) used by MDK API calls.
    ///
    /// All external group identifiers stored in `MlsGroupInfo.group_id_hex` and
    /// used in Nostr `h` tags are `nostr_group_id`.  MDK operations (create_message,
    /// add_member, …) require the internal `GroupId`.
    fn resolve_group_id(&self, nostr_group_id_hex: &str) -> Result<mdk_storage_traits::GroupId> {
        let bytes = hex::decode(nostr_group_id_hex)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid nostr_group_id hex: {e}")))?;
        let nostr_id: [u8; 32] = bytes.try_into()
            .map_err(|_| NuruNuruError::MlsError(
                format!("nostr_group_id must be 32 bytes, got {nostr_group_id_hex}")
            ))?;
        let groups = self.mdk.get_groups()
            .map_err(|e| NuruNuruError::MlsError(format!("get_groups: {e}")))?;
        groups.into_iter()
            .find(|g| g.nostr_group_id == nostr_id)
            .map(|g| g.mls_group_id)
            .ok_or_else(|| NuruNuruError::MlsError(
                format!("Group not found for nostr_group_id: {nostr_group_id_hex}")
            ))
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
            is_dm: false,               // updated after member count is known
        }
    }

    // ─── Key Package (Kind 443) ───────────────────────────────────────────

    /// Generate a fresh MLS KeyPackage and return Kind-443 event data.
    ///
    /// The caller signs the event via `create_unsigned_event(443, content, tags, pubkey_hex)`.
    pub fn create_key_package_event(&self) -> Result<KeyPackageEventData> {
        let pubkey = self.user_pubkey()?;
        // Publish on default relays; the caller can override the relay list tag later.
        let (content, tags, _hash_ref) = self
            .mdk
            .create_key_package_for_event(&pubkey, std::iter::empty::<RelayUrl>())
            .map_err(|e| NuruNuruError::MlsError(format!("create_key_package: {e}")))?;

        Ok(KeyPackageEventData {
            content,
            tags: Self::tags_to_vecs(tags),
        })
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
        };

        let result = self
            .mdk
            .create_group(&creator_pk, vec![], config)
            .map_err(|e| NuruNuruError::MlsError(format!("create_group: {e}")))?;

        let info = Self::group_to_info(result.group);
        Ok(info)
    }

    /// Add a member to an existing group using their Kind-443 KeyPackage event JSON.
    ///
    /// Returns:
    /// - `commit_event_data.content` — full JSON of the signed Kind-445 Commit event
    ///   (ready for `publish_raw_event`)
    /// - `welcome_event_data` — unsigned Kind-444 rumor for the new member
    ///   (needs signing + NIP-59 gift-wrap before publishing)
    pub fn add_member(
        &self,
        group_id_hex: &str,
        key_package_event_json: &str,
    ) -> Result<AddMemberResult> {
        let group_id = self.resolve_group_id(group_id_hex)?;

        let kp_event: nostr::Event = serde_json::from_str(key_package_event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid KeyPackage event JSON: {e}")))?;

        let result = self
            .mdk
            .add_members(&group_id, &[kp_event])
            .map_err(|e| NuruNuruError::MlsError(format!("add_members: {e}")))?;

        // evolution_event is a fully signed Event (ephemeral key) — serialise to JSON
        let commit_json = result.evolution_event.as_json();
        let commit_tags_vec = Self::tags_to_vecs(result.evolution_event.tags.to_vec());
        let commit_pubkey = result.evolution_event.pubkey.to_hex();

        // welcome_rumors[0] is the unsigned Kind-444 rumor for the new member
        let welcome = result
            .welcome_rumors
            .and_then(|mut v| if v.is_empty() { None } else { Some(v.remove(0)) });

        let (welcome_recipient, welcome_content, welcome_tags) = match welcome {
            Some(rumor) => {
                let recipient = rumor.pubkey.to_hex();
                let content = rumor.as_json();
                let tags = Self::tags_to_vecs(
                    serde_json::from_str::<serde_json::Value>(&content)
                        .ok()
                        .and_then(|v| v.get("tags").cloned())
                        .and_then(|t| serde_json::from_value::<Vec<nostr::Tag>>(t).ok())
                        .unwrap_or_default(),
                );
                (recipient, content, tags)
            }
            None => (String::new(), String::new(), Vec::new()),
        };

        Ok(AddMemberResult {
            commit_event_data: EncryptedMessageData {
                // content holds the full signed event JSON for direct publish_raw_event()
                content: commit_json,
                tags: commit_tags_vec,
                ephemeral_pubkey: commit_pubkey,
            },
            welcome_event_data: WelcomeEventData {
                recipient_pubkey: welcome_recipient,
                content: welcome_content,
                tags: welcome_tags,
            },
        })
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

        // Build the inner rumor (Kind 14 = private direct message, NIP-17)
        let rumor: UnsignedEvent =
            EventBuilder::new(nostr::Kind::from(14u16), content).build(pubkey);

        let event = self
            .mdk
            .create_message(&group_id, rumor)
            .map_err(|e| NuruNuruError::MlsError(format!("create_message: {e}")))?;

        Ok(EncryptedMessageData {
            content: event.as_json(),
            tags: Self::tags_to_vecs(event.tags.to_vec()),
            ephemeral_pubkey: event.pubkey.to_hex(),
        })
    }

    /// Process an incoming Kind-445 event (Proposal / Commit / Application).
    ///
    /// Returns the decrypted application message, or an error for non-application messages.
    pub fn process_message(
        &self,
        group_id_hex: &str,
        event_json: &str,
    ) -> Result<DecryptedMessage> {
        let event: nostr::Event = serde_json::from_str(event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid event JSON: {e}")))?;

        let result = self
            .mdk
            .process_message(&event)
            .map_err(|e| NuruNuruError::MlsError(format!("process_message: {e}")))?;

        match result {
            mdk_core::messages::MessageProcessingResult::ApplicationMessage(msg) => {
                Ok(DecryptedMessage {
                    sender_pubkey: msg.pubkey.to_hex(),
                    content: msg.content,
                    timestamp: msg.created_at.as_secs(),
                    group_id_hex: group_id_hex.to_string(),
                })
            }
            // State-transition messages: MDK already updated local state, nothing to display.
            // Callers should treat these as non-fatal and skip adding to the message list.
            mdk_core::messages::MessageProcessingResult::Commit { .. } => {
                tracing::debug!("[MLS] Commit processed for group {group_id_hex} — state updated");
                Err(NuruNuruError::MlsStateUpdate)
            }
            mdk_core::messages::MessageProcessingResult::Proposal(_) => {
                tracing::debug!("[MLS] Proposal auto-committed for group {group_id_hex}");
                Err(NuruNuruError::MlsStateUpdate)
            }
            mdk_core::messages::MessageProcessingResult::PendingProposal { .. } => {
                tracing::debug!("[MLS] Pending proposal stored for group {group_id_hex}");
                Err(NuruNuruError::MlsStateUpdate)
            }
            _ => {
                tracing::warn!("[MLS] Unhandled message type for group {group_id_hex}");
                Err(NuruNuruError::MlsStateUpdate)
            }
        }
    }

    // ─── Welcome (Kind 444) ───────────────────────────────────────────────

    /// Process an incoming Kind-444 Welcome and join the group.
    ///
    /// `welcome_event_json` — JSON of the **rumor** (inner, unwrapped unsigned event).
    /// The wrapper Event ID is extracted from the rumor's `id` field for MDK tracking.
    pub fn process_welcome(&self, welcome_event_json: &str) -> Result<MlsGroupInfo> {
        let mut rumor: UnsignedEvent = serde_json::from_str(welcome_event_json)
            .map_err(|e| NuruNuruError::MlsError(format!("Invalid welcome JSON: {e}")))?;

        // Use the rumor's computed ID as the wrapper_event_id (proxy for tracking).
        let wrapper_event_id: nostr::EventId = rumor.id();

        let welcome = self
            .mdk
            .process_welcome(&wrapper_event_id, &rumor)
            .map_err(|e| NuruNuruError::MlsError(format!("process_welcome: {e}")))?;

        let group_id_hex = hex::encode(welcome.nostr_group_id);
        let is_dm = welcome.member_count <= 2;

        Ok(MlsGroupInfo {
            group_id_hex,
            name: welcome.group_name,
            description: welcome.group_description,
            admin_pubkeys: welcome
                .group_admin_pubkeys
                .iter()
                .map(|pk| pk.to_hex())
                .collect(),
            member_pubkeys: Vec::new(),
            relays: welcome
                .group_relays
                .iter()
                .map(|r| r.to_string())
                .collect(),
            created_at: 0,
            epoch: 0,
            is_dm,
        })
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
                Err(e) => tracing::warn!("[MLS] get_relays failed for {}: {e}", info.group_id_hex),
            }
            match self.mdk.get_members(&group_id) {
                Ok(members) => {
                    info.member_pubkeys = members.iter().map(|pk| pk.to_hex()).collect();
                    info.is_dm = info.member_pubkeys.len() <= 2;
                }
                Err(e) => tracing::warn!("[MLS] get_members failed for {}: {e}", info.group_id_hex),
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
            Err(e) => tracing::warn!("[MLS] get_relays failed for {group_id_hex}: {e}"),
        }
        match self.mdk.get_members(&group_id) {
            Ok(members) => {
                info.member_pubkeys = members.iter().map(|pk| pk.to_hex()).collect();
                info.is_dm = info.member_pubkeys.len() <= 2;
            }
            Err(e) => tracing::warn!("[MLS] get_members failed for {group_id_hex}: {e}"),
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
                content: m.content,
                timestamp: m.created_at.as_secs(),
                group_id_hex: nostr_group_id_hex.to_string(),
            })
            .collect())
    }

    /// Return the SQLite path this manager is using (for diagnostics).
    pub fn db_path(&self) -> &str {
        &self.db_path
    }
}
