//! # NuruNuru Core — High-Performance Nostr Engine
//!
//! Replaces the JavaScript `lib/` business logic with Rust, powered by:
//! - **rust-nostr** (`nostr-sdk`): Protocol handling, relay connections, NIP support
//! - **nostrdb** (via `nostr-ndb`): LMDB-backed zero-copy event cache & index
//!
//! ## Architecture
//!
//! ```text
//! ┌─────────────────────────────────────────────────┐
//! │                  NuruNuruEngine                  │
//! │  ┌───────────┐ ┌──────────────┐ ┌────────────┐  │
//! │  │  Client    │ │ Recommend.   │ │  Config    │  │
//! │  │ (nostr-sdk)│ │   Engine     │ │  & Relay   │  │
//! │  └─────┬─────┘ └──────┬───────┘ └────────────┘  │
//! │        │               │                         │
//! │  ┌─────▼───────────────▼──────────────────────┐  │
//! │  │          nostrdb (LMDB cache)              │  │
//! │  │  events · profiles · fulltext · indexes    │  │
//! │  └────────────────────────────────────────────┘  │
//! └─────────────────────────────────────────────────┘
//!          ▲               ▲                ▲
//!    UniFFI (Kotlin)  UniFFI (Swift)   WASM (Web)
//! ```

pub mod config;
pub mod engine;
pub mod error;
pub mod filters;
pub mod recommendation;
pub mod relay;
pub mod types;
pub mod validation;

pub use config::NuruNuruConfig;
pub use engine::NuruNuruEngine;
pub use error::{NuruNuruError, Result};
pub use recommendation::RecommendationEngine;
pub use types::*;
