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
#[derive(Debug, Clone)]
pub struct ScoredPost {
    pub event_id: String,
    pub pubkey: String,
    pub score: f64,
    pub created_at: u64,
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

// ─── NIP-EE / MLS Types ─────────────────────────────────────────────────────

/// MLS group information (Marmot Kind 30443/1059/445).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MlsGroupInfo {
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
    /// MIP-01 v3: disappearing message duration in seconds.
    ///
    /// None => disabled (messages persist forever)
    /// Some(n>0) => auto-expire after n seconds
    ///
    /// NOTE: current mdk-core(0.7.x) does not expose this field yet, so callers
    /// may observe None until upstream support lands.
    pub disappearing_message_secs: Option<u64>,
    /// true if this is a 1:1 DM (2-person group)
    pub is_dm: bool,
}

/// KeyPackage event data for Marmot MIP-00 publishing (kind 30443 canonical, 443 legacy).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyPackageEventData {
    /// Event kind — 30443 (addressable, NIP-33) canonical; 443 is legacy migration fallback.
    pub kind: u32,
    /// Serialised MLS KeyPackage (base64-encoded TLS-serialized KeyPackageBundle)
    pub content: String,
    /// Marmot-compliant tags for kind:30443: d, mls_protocol_version, mls_ciphersuite,
    /// mls_extensions, mls_proposals, encoding, i, relays, client
    pub tags: Vec<Vec<String>>,
    /// Legacy-compatible tags for kind:443 (no `d` tag), supplied by MDK.
    pub legacy_tags: Vec<Vec<String>>,
    /// Canonical `d` tag value (32-byte hex string) used for 30443 replacement lifecycle.
    pub d_tag: String,
    /// Serialized MDK KeyPackage hash_ref. Use this for local init-key cleanup after the
    /// matching Welcome is accepted; deleting by parsing a relay event can target the wrong
    /// material if the event was normalized/republished across 30443/443 interop paths.
    pub hash_ref: Vec<u8>,
}

/// Result from adding a member to an MLS group.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AddMemberResult {
    /// Kind 445 commit event data (broadcast to all group relays)
    pub commit_event_data: EncryptedMessageData,
    /// Kind 444 welcome event data (sent to new member via NIP-59 gift-wrap)
    pub welcome_event_data: WelcomeEventData,
}

/// Welcome event data for Kind 444 → NIP-59 gift-wrapped as Kind 1059 (Marmot MIP-02).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WelcomeEventData {
    pub recipient_pubkey: String,
    /// NIP-59 gift-wrapped event JSON (Kind 1059), ready for `publish_raw_event`.
    /// Empty if gift-wrapping has not been applied yet (caller must wrap).
    pub gift_wrapped_event_json: String,
    /// Inner rumor JSON (Kind 444, unsigned) — for local storage/debugging.
    pub inner_rumor_json: String,
    pub tags: Vec<Vec<String>>,
}

/// Encrypted MLS message data for Kind 445 publishing.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedMessageData {
    /// NIP-44 encrypted content (using MLS exporter secret)
    pub content: String,
    /// Tags including ["h", "<group_id_hex>"] where group_id_hex is the
    /// Nostr group id hex / Kind 445 h tag value.
    pub tags: Vec<Vec<String>>,
    /// Ephemeral sender pubkey
    pub ephemeral_pubkey: String,
}

/// Decrypted MLS message after processing a Kind 445 event.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DecryptedMessage {
    pub sender_pubkey: String,
    pub content: String,
    pub timestamp: u64,
    /// Nostr group id hex / Kind 445 h tag value.
    pub group_id_hex: String,
}

/// Issue #178 #5: membership delta the wrapper computes by diffing members
/// before vs after MDK applies a Commit. Lets the UI render add/remove
/// without a `get_group_info` re-query.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct CommitDelta {
    pub added_pubkeys: Vec<String>,
    pub removed_pubkeys: Vec<String>,
    pub epoch_after: u64,
}

/// Structured result for processing a Kind 445 event.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum MlsProcessResult {
    ApplicationMessage(DecryptedMessage),
    /// Issue #178 #5: commit applied; delta tells the UI who joined/left.
    Commit {
        group_id_hex: String,
        delta: CommitDelta,
    },
    /// Issue #178 #6: pending proposal stored; app must run a self-update.
    NeedsSelfUpdate {
        group_id_hex: String,
        reason: String,
    },
    /// Catch-all for unprocessable / unhandled MDK results.
    StateUpdate {
        kind: String,
    },
}

/// Issue #183: outcome of `MlsManager::catch_up_to_peer`.
///
/// The wrapper attempts a deterministic catch-up by replaying every
/// candidate Kind-445 event (caller-supplied + cached) in `createdAt` order
/// until either MDK reports an Application/Commit at every position or
/// progress stalls. The status reflects whether the local epoch is now
/// usable, plus a hint about what the UI should do next.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MlsCatchUpStatus {
    /// `epoch_after > epoch_before` and the unresolved retryable count is
    /// `0`. The local installation is aligned with the peer.
    Recovered,
    /// At least one retryable event was applied but unresolved events remain.
    /// Caller should poll relays again (the missing Commit may still be in
    /// flight) before escalating to `NotRecoverable`.
    PartiallyRecovered,
    /// No new state was applied. Same epoch, same retryable count. Either
    /// the missing Commit has aged out of every configured relay (and is not
    /// in the local replay cache) or it never reached this device. The UI
    /// should prompt the user to recreate the conversation.
    NotRecoverable,
    /// The group is not present in the local MLS store. Caller should not
    /// schedule retries.
    NoSuchGroup,
}

/// Issue #183: structured report returned by `catch_up_to_peer`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MlsCatchUpReport {
    pub group_id_hex: String,
    /// Local epoch before the replay pass.
    pub epoch_before: u64,
    /// Local epoch after the replay pass.
    pub epoch_after: u64,
    /// Total candidate Kind-445 events the replay considered (caller
    /// supplied + cached, after dedup).
    pub candidates_considered: u32,
    /// Application messages decrypted during the replay.
    pub application_messages_applied: u32,
    /// Commits / Proposals applied during the replay.
    pub commits_applied: u32,
    /// Candidates that could not be applied even after every retry pass.
    pub still_unprocessable: u32,
    /// Candidates retrieved from the local replay cache (not duplicated in
    /// the caller-supplied list). Surfaced for diagnostics.
    pub cache_hits: u32,
    pub status: MlsCatchUpStatus,
}

/// Issue #178 #4: a Welcome staged for accept/decline UX.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingWelcome {
    /// Inner kind:444 rumor id — the lookup key MDK uses.
    pub welcome_event_id_hex: String,
    /// Outer kind:1059 gift-wrap id — for app-side dedup.
    pub wrapper_event_id_hex: String,
    /// `nostr_group_id` (32-byte hex) — the same id used in Kind-445 `h` tags.
    pub group_id_hex: String,
    pub group_name: String,
    pub group_description: String,
    pub group_admin_pubkeys: Vec<String>,
    pub group_relays: Vec<String>,
    /// Pubkey of the inviter (welcomer).
    pub welcomer_pubkey: String,
    /// Number of members in the group at Welcome time (creator + invitees).
    pub member_count: u32,
    /// `true` when member_count <= 2 (1:1 DM).
    pub is_dm: bool,
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
