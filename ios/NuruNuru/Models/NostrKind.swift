import Foundation

/// Nostr event kinds — synced with Android NostrKind and Web lib/nostr-kinds.js.
enum NostrKind {
    static let metadata         = 0
    static let textNote         = 1
    static let recommendServer  = 2
    static let contactList      = 3
    static let encryptedDm      = 4
    static let deletion         = 5
    static let repost           = 6
    static let reaction         = 7
    static let badgeAward       = 8
    static let sealedDm         = 13
    static let directMessage    = 14     // NIP-17 chat message
    static let fileMessage      = 15     // NIP-17 file message
    static let genericRepost    = 16
    static let videoEvent       = 21     // NIP-71 regular video event
    static let portraitShortVideo = 22   // NIP-71 short-form portrait video event
    static let channelCreate    = 40
    static let channelMeta      = 41
    static let channelMessage   = 42
    static let channelHide      = 43
    static let channelMute      = 44
    static let vanishRequest    = 62     // NIP-62
    static let dmGiftWrap       = 1059
    static let report           = 1984
    static let label            = 1985   // Birdwatch
    static let nip98Auth        = 27235
    static let blossomAuth      = 24242
    static let blossomUserServerList = 10063
    static let nsiteRoot        = 15128  // NIP-5A root nsite manifest
    static let clientAuth       = 22242  // NIP-42 relay authentication
    static let zapRequest       = 9734
    static let zapReceipt       = 9735
    static let muteList         = 10000
    static let pinList          = 10001
    static let relayList        = 10002
    static let bookmarks        = 10003
    static let communities      = 10004
    static let publicChats      = 10005
    static let blockedRelays    = 10006
    static let searchRelays     = 10007
    static let userGroups       = 10009
    static let interests        = 10015
    static let emojiList        = 10030
    static let dmRelayList      = 10050  // NIP-17 DM receiving relay list
    static let longForm         = 30023
    static let draftLongForm    = 30024
    static let emojiSet         = 30030
    static let badgeDefinition  = 30009
    static let profileBadges    = 30008
    static let nsiteLegacy      = 34128  // NIP-5A legacy nsite manifest (deprecated upstream)
    static let addressableVideo = 34235  // NIP-71 addressable video event
    static let addressableShortVideo = 34236
    static let nsiteNamed       = 35128  // NIP-5A named nsite manifest
    static let calendarRsvp     = 31925
    static let dateCandidate    = 31926
    static let timeBasedEvent   = 31927
    static let chronostrEvent   = 31928
    // MLS / Marmot (MIP-00〜03)
    static let mlsKeyPackage            = 30443     // Marmot MIP-00 canonical (addressable)
    static let mlsKeyPackageLegacy      = 443       // Legacy regular event (migration fallback)
    static let mlsWelcome               = 1059      // NIP-59 gift-wrapped Welcome (Marmot MIP-02)
    static let mlsWelcomeInner          = 444       // Inner rumor kind (unwrapped by Rust / MDK 0.7.x)
    static let mlsWelcomeInnerMarmot    = 10444     // Marmot/WhiteNoise alias seen in the ecosystem
    static let mlsGroupMessage          = 445       // Unchanged
    static let mlsKeyPackageRelays      = 10051
}

/// Default relays — mirrors Android DEFAULT_RELAYS.
let defaultRelays: [String] = [
    "wss://yabu.me",
    "wss://relay-jp.nostr.wirednet.jp",
    "wss://r.kojira.io",
    "wss://relay.damus.io"
]
