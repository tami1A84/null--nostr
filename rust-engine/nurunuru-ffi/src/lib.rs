//! UniFFI bindings for NuruNuru Core.
//!
//! This crate exposes a simplified, FFI-safe interface for:
//! - **Kotlin** (Android via JNI)
//! - **Swift** (iOS/macOS via C ABI)
//! - **Python** (for testing/tooling)
//!
//! All async operations are bridged through a Tokio runtime.

use std::sync::Arc;
use nostr::prelude::{FromBech32, ToBech32};

use nurunuru_core::config::NuruNuruConfig;
use nurunuru_core::types::*;
use nurunuru_core::NuruNuruEngine;

uniffi::setup_scaffolding!();

/// Parse keys. Workaround for nostr-sdk-jvm missing Android binaries.
#[uniffi::export]
pub fn parse_keys(input: String) -> Result<FfiParsedKeys, NuruNuruFfiError> {
    let keys = nostr::Keys::parse(&input)
        .map_err(|e: nostr::key::Error| NuruNuruFfiError::KeyError(e.to_string()))?;
    Ok(FfiParsedKeys {
        secret_key_hex: keys.secret_key().to_secret_hex(),
        public_key_hex: keys.public_key().to_hex(),
    })
}

/// Convert a pubkey hex to npub bech32.
#[uniffi::export]
pub fn pubkey_to_npub(pubkey_hex: String) -> Result<String, NuruNuruFfiError> {
    let pk = nostr::PublicKey::from_hex(&pubkey_hex)
        .map_err(|e: nostr::key::Error| NuruNuruFfiError::KeyError(e.to_string()))?;
    Ok(pk.to_bech32().map_err(|e: nostr::nips::nip19::Error| NuruNuruFfiError::KeyError(e.to_string()))?)
}

/// Convert an npub bech32 to pubkey hex.
#[uniffi::export]
pub fn npub_to_hex(npub: String) -> Result<String, NuruNuruFfiError> {
    let pk = nostr::PublicKey::from_bech32(&npub)
        .map_err(|e: nostr::nips::nip19::Error| NuruNuruFfiError::KeyError(e.to_string()))?;
    Ok(pk.to_hex())
}

/// FFI-safe wrapper around the engine.
/// Holds a Tokio runtime for async bridging.
#[derive(uniffi::Object)]
pub struct NuruNuruClient {
    runtime: tokio::runtime::Runtime,
    engine: Arc<NuruNuruEngine>,
}

#[uniffi::export]
impl NuruNuruClient {
    /// Create a new client with a private key (hex or nsec).
    #[uniffi::constructor]
    pub fn new(secret_key_hex: String, db_path: String) -> Result<Arc<Self>, NuruNuruFfiError> {
        let rt = tokio::runtime::Runtime::new()
            .map_err(|e| NuruNuruFfiError::RuntimeError(e.to_string()))?;

        let engine = rt.block_on(async {
            let keys = nostr::Keys::parse(&secret_key_hex)
                .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;

            let mut config = NuruNuruConfig::default();
            config.db_path = db_path;

            NuruNuruEngine::new(keys, config)
                .await
                .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
        })?;

        Ok(Arc::new(Self {
            runtime: rt,
            engine,
        }))
    }

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

    /// Login with a public key and load user data.
    pub fn login(&self, pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.login(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Publish a text note. Returns the event ID hex.
    pub fn publish_note(&self, content: String) -> Result<String, NuruNuruFfiError> {
        let eid = self
            .runtime
            .block_on(self.engine.publish_note(&content, vec![]))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(eid.to_hex())
    }

    /// Fetch a user profile.
    pub fn fetch_profile(&self, pubkey_hex: String) -> Result<Option<FfiUserProfile>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let profile = self
            .runtime
            .block_on(self.engine.fetch_profile(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(profile.map(|p| FfiUserProfile {
            name: p.name,
            display_name: p.display_name,
            about: p.about,
            picture: p.picture,
            nip05: p.nip05,
            lud16: p.lud16,
            pubkey: p.pubkey,
        }))
    }

    /// Fetch follow list. Returns list of pubkey hex strings.
    pub fn fetch_follow_list(&self, pubkey_hex: String) -> Result<Vec<String>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.fetch_follow_list(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Follow a user.
    pub fn follow_user(&self, target_pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&target_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.follow_user(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Unfollow a user.
    pub fn unfollow_user(&self, target_pubkey_hex: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&target_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.unfollow_user(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Send an encrypted DM (NIP-17).
    pub fn send_dm(&self, recipient_hex: String, content: String) -> Result<(), NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&recipient_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.send_dm(pk, &content))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Full-text search (NIP-50).
    pub fn search(&self, query: String, limit: u32) -> Result<Vec<String>, NuruNuruFfiError> {
        let events = self
            .runtime
            .block_on(self.engine.search(&query, limit as usize))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(events.iter().map(|e| e.id.to_hex()).collect())
    }

    /// Get recommended feed. Returns scored event IDs.
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

    /// Fetch recent notes from a specific user.
    pub fn fetch_user_notes(
        &self,
        pubkey_hex: String,
        limit: u32,
    ) -> Result<Vec<FfiScoredPost>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let events = self
            .runtime
            .block_on(self.engine.fetch_timeline(Some(&[pk]), None, limit as usize))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(events
            .into_iter()
            .map(|e| FfiScoredPost {
                event_id: e.id.to_hex(),
                pubkey: e.pubkey.to_hex(),
                score: 0.0,
                created_at: e.created_at.as_secs(),
            })
            .collect())
    }

    /// Fetch all DM conversations.
    pub fn fetch_dm_conversations(&self) -> Result<Vec<FfiDmConversation>, NuruNuruFfiError> {
        let events = self.runtime.block_on(self.engine.fetch_dms(None, 100))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        // Simple grouping by author for now (real app would unwrap GiftWraps)
        let mut convs = std::collections::HashMap::new();
        for e in events {
            let pk = e.pubkey.to_hex();
            convs.entry(pk).or_insert(FfiDmConversation {
                partner_pubkey: e.pubkey.to_hex(),
                last_message: "暗号化されたメッセージ".to_string(),
                last_message_at: e.created_at.as_secs(),
            });
        }
        Ok(convs.into_values().collect())
    }

    /// Fetch messages in a conversation.
    pub fn fetch_dm_messages(
        &self,
        partner_pubkey_hex: String,
        limit: u32,
    ) -> Result<Vec<FfiDmMessage>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&partner_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let events = self.runtime.block_on(self.engine.fetch_dms(None, limit as usize))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;

        Ok(events.into_iter()
            .filter(|e| e.pubkey == pk)
            .map(|e| FfiDmMessage {
                event_id: e.id.to_hex(),
                sender_pubkey: e.pubkey.to_hex(),
                content: "メッセージの内容を取得中...".to_string(),
                created_at: e.created_at.as_secs(),
            })
            .collect())
    }

    /// Mark a post as "not interested".
    pub fn mark_not_interested(&self, event_id: String, author_pubkey: String) {
        self.runtime
            .block_on(self.engine.mark_not_interested(&event_id, &author_pubkey));
    }

    /// Record engagement for personalization.
    pub fn record_engagement(&self, action: String, author_pubkey: String) {
        self.runtime
            .block_on(self.engine.record_engagement(&action, &author_pubkey));
    }

    /// Get connection statistics.
    pub fn connection_stats(&self) -> FfiConnectionStats {
        let stats = self.runtime.block_on(self.engine.connection_stats());
        FfiConnectionStats {
            connected_relays: stats.connected_relays as u32,
            total_relays: stats.total_relays as u32,
        }
    }

    /// Format a timestamp in Japanese relative format.
    pub fn format_timestamp(&self, timestamp: u64) -> String {
        format_timestamp_ja(timestamp)
    }

    /// Zap a user's post.
    pub fn zap(
        &self,
        event_id_hex: String,
        author_pubkey_hex: String,
        amount_sats: u64,
        message: Option<String>,
    ) -> Result<(), NuruNuruFfiError> {
        let eid = nostr::EventId::from_hex(&event_id_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let pk = nostr::PublicKey::from_hex(&author_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.zap(eid, pk, amount_sats, message.as_deref()))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Fetch earned badges for a user.
    pub fn fetch_badges(&self, pubkey_hex: String) -> Result<Vec<FfiBadgeAward>, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let awards = self
            .runtime
            .block_on(self.engine.fetch_badges(pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(awards
            .into_iter()
            .map(|a| FfiBadgeAward {
                badge_id: a.badge_id,
                award_event_id: a.award_event_id,
                award_pubkey: a.award_pubkey,
            })
            .collect())
    }

    /// Create a Birdwatch label for an event.
    pub fn birdwatch_post(
        &self,
        event_id_hex: String,
        author_pubkey_hex: String,
        context_type: String,
        content: String,
    ) -> Result<String, NuruNuruFfiError> {
        let eid = nostr::EventId::from_hex(&event_id_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let pk = nostr::PublicKey::from_hex(&author_pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let id = self
            .runtime
            .block_on(self.engine.birdwatch_post(eid, pk, &context_type, &content))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(id.to_hex())
    }

    /// Verify NIP-05 identifier.
    pub fn verify_nip05(&self, nip05: String, pubkey_hex: String) -> Result<bool, NuruNuruFfiError> {
        let pk = nostr::PublicKey::from_hex(&pubkey_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        self.runtime
            .block_on(self.engine.verify_nip05(&nip05, pk))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))
    }

    /// Request to vanish (IRREVERSIBLE).
    pub fn request_vanish(&self, reason: Option<String>) -> Result<String, NuruNuruFfiError> {
        let id = self
            .runtime
            .block_on(self.engine.request_vanish(reason.as_deref()))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(id.to_hex())
    }

    /// Delete an event.
    pub fn delete_event(
        &self,
        event_id_hex: String,
        reason: Option<String>,
    ) -> Result<String, NuruNuruFfiError> {
        let eid = nostr::EventId::from_hex(&event_id_hex)
            .map_err(|e| NuruNuruFfiError::KeyError(e.to_string()))?;
        let id = self
            .runtime
            .block_on(self.engine.delete_event(eid, reason.as_deref()))
            .map_err(|e| NuruNuruFfiError::EngineError(e.to_string()))?;
        Ok(id.to_hex())
    }
}

// ─── FFI-safe types ────────────────────────────────────────────

#[derive(uniffi::Record)]
pub struct FfiParsedKeys {
    pub secret_key_hex: String,
    pub public_key_hex: String,
}

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

#[derive(uniffi::Record)]
pub struct FfiBadgeAward {
    pub badge_id: String,
    pub award_event_id: String,
    pub award_pubkey: String,
}

#[derive(uniffi::Record)]
pub struct FfiDmMessage {
    pub event_id: String,
    pub sender_pubkey: String,
    pub content: String,
    pub created_at: u64,
}

#[derive(uniffi::Record)]
pub struct FfiDmConversation {
    pub partner_pubkey: String,
    pub last_message: String,
    pub last_message_at: u64,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum NuruNuruFfiError {
    #[error("Runtime error: {0}")]
    RuntimeError(String),
    #[error("Key error: {0}")]
    KeyError(String),
    #[error("Engine error: {0}")]
    EngineError(String),
}
