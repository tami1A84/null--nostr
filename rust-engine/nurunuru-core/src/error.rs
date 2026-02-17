use thiserror::Error;

pub type Result<T> = std::result::Result<T, NuruNuruError>;

/// Unified error type for NuruNuru engine operations.
/// Error codes mirror the JS `errors.js` structure:
/// - 1xxx: Network
/// - 2xxx: Auth/Signing
/// - 3xxx: Encryption
/// - 4xxx: Validation
/// - 5xxx: Relay
/// - 6xxx: Event
#[derive(Debug, Error)]
pub enum NuruNuruError {
    // --- Network (1xxx) ---
    #[error("リクエストがタイムアウトしました")]
    RequestTimeout,

    #[error("すべての再試行が失敗しました")]
    AllRetriesFailed,

    #[error("接続に失敗しました: {0}")]
    ConnectionFailed(String),

    // --- Auth/Signing (2xxx) ---
    #[error("署名機能が利用できません")]
    NoSigningMethod,

    #[error("署名に失敗しました: {0}")]
    SigningFailed(String),

    #[error("公開鍵の取得に失敗しました")]
    PublicKeyFailed,

    // --- Encryption (3xxx) ---
    #[error("暗号化に失敗しました: {0}")]
    EncryptionFailed(String),

    #[error("復号に失敗しました: {0}")]
    DecryptionFailed(String),

    // --- Validation (4xxx) ---
    #[error("バリデーションエラー: {0}")]
    ValidationError(String),

    #[error("無効なリレーURL: {0}")]
    InvalidRelayUrl(String),

    // --- Relay (5xxx) ---
    #[error("リレーエラー: {0}")]
    RelayError(String),

    // --- Event (6xxx) ---
    #[error("イベントエラー: {0}")]
    EventError(String),

    #[error("既にフォローしています")]
    AlreadyFollowing,

    #[error("フォローリストがありません")]
    NoFollowList,

    // --- Database ---
    #[error("データベースエラー: {0}")]
    DatabaseError(String),

    // --- Wrapped upstream errors ---
    #[error(transparent)]
    NostrSdk(#[from] nostr_sdk::client::Error),

    #[error("nostr protocol error: {0}")]
    NostrProtocol(String),

    #[error(transparent)]
    SerdeJson(#[from] serde_json::Error),
}
