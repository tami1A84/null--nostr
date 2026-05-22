import Foundation

// MARK: - FFI Data Types (Swift mirrors of Rust FFI structs)

/// Mirrors Rust FfiMlsGroupInfo.
public struct FfiMlsGroupInfo {
    /// Nostr group id hex (Kind 445 `h` tag value).
    /// Do not expose or pass MDK/OpenMLS internal MLS group ids across the FFI/App boundary.
    public let groupIdHex:    String
    public let name:          String
    public let description:   String
    public let adminPubkeys:  [String]
    public let memberPubkeys: [String]
    public let relays:        [String]
    public let createdAt:     UInt64
    public let epoch:         UInt64
    /// MIP-01 v3 disappearing message duration in seconds.
    /// nil => disabled.
    public let disappearingMessageSecs: UInt64?
    public let isDm:          Bool

    public init(groupIdHex: String, name: String, description: String, adminPubkeys: [String], memberPubkeys: [String], relays: [String], createdAt: UInt64, epoch: UInt64, disappearingMessageSecs: UInt64? = nil, isDm: Bool) {
        self.groupIdHex = groupIdHex
        self.name = name
        self.description = description
        self.adminPubkeys = adminPubkeys
        self.memberPubkeys = memberPubkeys
        self.relays = relays
        self.createdAt = createdAt
        self.epoch = epoch
        self.disappearingMessageSecs = disappearingMessageSecs
        self.isDm = isDm
    }
}

/// Mirrors Rust FfiDecryptedMessage.
public struct FfiDecryptedMessage {
    public let senderPubkey: String
    public let content:      String
    public let timestamp:    UInt64
    /// Nostr group id hex (Kind 445 `h` tag value), not the internal MLS group id.
    public let groupIdHex:   String

    public init(senderPubkey: String, content: String, timestamp: UInt64, groupIdHex: String) {
        self.senderPubkey = senderPubkey
        self.content = content
        self.timestamp = timestamp
        self.groupIdHex = groupIdHex
    }
}

public enum FfiMlsProcessResult {
    case application(FfiDecryptedMessage)
    /// Issue #178 #5: structured commit delta. `groupIdHex` is the Nostr group
    /// id; `added` / `removed` are hex pubkeys of members that joined/left.
    case commit(groupIdHex: String, added: [String], removed: [String], epochAfter: UInt64)
    /// Issue #178 #6: a pending Proposal was stored — `mls_create_recovery_commit`
    /// must run on the publish path so the group does not stall.
    case needsSelfUpdate(groupIdHex: String, reason: String)
    case stateUpdate(String)
}

/// Mirrors Rust FfiPendingWelcome (Issue #178 #4 split flow).
public struct FfiPendingWelcome {
    public let welcomeEventIdHex:   String
    public let wrapperEventIdHex:   String
    public let groupIdHex:          String
    public let groupName:           String
    public let groupDescription:    String
    public let groupAdminPubkeys:   [String]
    public let groupRelays:         [String]
    public let welcomerPubkey:      String
    public let memberCount:         UInt32
    public let isDm:                Bool

    public init(
        welcomeEventIdHex: String,
        wrapperEventIdHex: String,
        groupIdHex: String,
        groupName: String,
        groupDescription: String,
        groupAdminPubkeys: [String],
        groupRelays: [String],
        welcomerPubkey: String,
        memberCount: UInt32,
        isDm: Bool
    ) {
        self.welcomeEventIdHex = welcomeEventIdHex
        self.wrapperEventIdHex = wrapperEventIdHex
        self.groupIdHex = groupIdHex
        self.groupName = groupName
        self.groupDescription = groupDescription
        self.groupAdminPubkeys = groupAdminPubkeys
        self.groupRelays = groupRelays
        self.welcomerPubkey = welcomerPubkey
        self.memberCount = memberCount
        self.isDm = isDm
    }
}

/// Mirrors Rust FfiEncryptedMessageData (Kind-445 event payload).
public struct FfiEncryptedMessageData {
    public let content:         String
    public let tags:            [[String]]
    public let ephemeralPubkey: String

    public init(content: String, tags: [[String]], ephemeralPubkey: String) {
        self.content = content
        self.tags = tags
        self.ephemeralPubkey = ephemeralPubkey
    }
}

/// Mirrors Rust FfiWelcomeEventData (Marmot MIP-02).
public struct FfiWelcomeEventData {
    public let recipientPubkey:      String
    public let content:              String
    public let tags:                 [[String]]
    public let giftWrappedEventJson: String
    public let innerRumorJson:       String

    public init(
        recipientPubkey: String,
        content: String,
        tags: [[String]],
        giftWrappedEventJson: String = "",
        innerRumorJson: String = ""
    ) {
        self.recipientPubkey = recipientPubkey
        self.content = content
        self.tags = tags
        self.giftWrappedEventJson = giftWrappedEventJson
        self.innerRumorJson = innerRumorJson
    }
}

/// Mirrors Rust FfiAddMemberResult.
public struct FfiAddMemberResult {
    public let commitEventData:  FfiEncryptedMessageData
    public let welcomeEventData: FfiWelcomeEventData

    public init(commitEventData: FfiEncryptedMessageData, welcomeEventData: FfiWelcomeEventData) {
        self.commitEventData = commitEventData
        self.welcomeEventData = welcomeEventData
    }
}

/// Mirrors Rust FfiKeyPackageEventData (Marmot MIP-00).
public struct FfiKeyPackageEventData {
    public let kind:       UInt32
    public let content:    String
    public let tags:       [[String]]
    public let legacyTags: [[String]]
    public let dTag:       String
    public let hashRef:    [UInt8]

    public init(kind: UInt32 = 30443, content: String, tags: [[String]], legacyTags: [[String]] = [], dTag: String = "", hashRef: [UInt8] = []) {
        self.kind = kind
        self.content = content
        self.tags = tags
        self.legacyTags = legacyTags
        self.dTag = dTag
        self.hashRef = hashRef
    }
}

// MARK: - MLS-only FFI Bridge Protocol

/// MLS 暗号層のみの FFI ブリッジ。
/// Timeline / Profile / Publishing は pure Swift が担当。
///
/// Contract: every `groupIdHex` at this FFI/App boundary is the Nostr group id
/// hex (Kind 445 `h` tag value). MDK/OpenMLS internal MLS group ids must stay
/// inside Rust and must never be exposed to iOS callers.
protocol MlsFFIBridge: AnyObject, Sendable {

    // ── 初期化 ──
    func connect()
    func disconnect() throws

    // ── Issue #181: encrypted-DB guard ──
    /// Returns `true` when the MLS SQLite DB is encrypted (SQLCipher),
    /// `false` when plaintext, `nil` when no MLS manager is bound yet.
    /// The live client refuses to construct unless this returns `true`.
    func mlsIsEncrypted() -> Bool?

    // ── KeyPackage (Kind 30443, MIP-00) ──
    func mlsCreateKeyPackage() throws -> FfiKeyPackageEventData
    func mlsValidateKeyPackageEvent(eventJSON: String) throws
    func mlsDeleteConsumedKeyPackageFromEventJSON(eventJSON: String) throws
    func mlsDeleteConsumedKeyPackageByHashRef(hashRef: [UInt8]) throws
    /// Returns Nostr group id hex values that can be passed to `mlsCreateRecoveryCommit`.
    func mlsGroupsNeedingSelfUpdate(thresholdSecs: UInt64) throws -> [String]

    // ── グループ管理 ──
    func mlsCreateGroup(name: String, adminPubkeys: [String], relays: [String]) throws -> FfiMlsGroupInfo
    func mlsAddMember(groupIdHex: String, keyPackageEventJSON: String) throws -> FfiAddMemberResult
    func mlsRemoveMember(groupIdHex: String, memberPubkeyHex: String) throws -> FfiEncryptedMessageData
    func mlsLeaveGroup(groupIdHex: String) throws -> FfiEncryptedMessageData
    func mlsListGroups() throws -> [FfiMlsGroupInfo]
    func mlsGetGroupInfo(groupIdHex: String) throws -> FfiMlsGroupInfo

    // ── メッセージ送受信 (Kind 445) ──
    func mlsCreateMessage(groupIdHex: String, content: String) throws -> FfiEncryptedMessageData
    func mlsProcessMessage(groupIdHex: String, eventJSON: String) throws -> FfiDecryptedMessage
    func mlsProcessMessageResult(groupIdHex: String, eventJSON: String) throws -> FfiMlsProcessResult

    // ── Welcome (Kind 444 / 1059) ──
    // mlsProcessWelcome is the legacy fused process+accept call. Prefer the
    // split flow below so users see invites before crypto state is created.
    func mlsProcessWelcome(welcomeEventJSON: String) throws -> FfiMlsGroupInfo
    /// Issue #178 #4: preview an incoming Welcome (gift-wrap or rumor JSON)
    /// without joining. Returns the pending Welcome for app-level
    /// accept/decline UX.
    func mlsPreviewWelcome(welcomeEventJSON: String) throws -> FfiPendingWelcome
    /// Accept a previously previewed Welcome (lookup key = welcomeEventIdHex).
    func mlsAcceptWelcome(welcomeEventIdHex: String) throws -> FfiPendingWelcome
    /// Decline a previously previewed Welcome (lookup key = welcomeEventIdHex).
    func mlsDeclineWelcome(welcomeEventIdHex: String) throws
    /// List Welcomes previewed but not yet accepted/declined.
    func mlsGetPendingWelcomes() throws -> [FfiPendingWelcome]

    // ── 履歴 + 状態管理 ──
    func mlsGetMessageHistory(groupIdHex: String, limit: UInt64) throws -> [FfiDecryptedMessage]
    func mlsMergePendingCommit(groupIdHex: String) throws
    func mlsCreateRecoveryCommit(groupIdHex: String) throws -> FfiEncryptedMessageData
    func mlsClearPendingCommit(groupIdHex: String) throws

    // ── Subscriptions (Issue #178 #9, #10) ──
    /// Subscribe to *my* Welcomes (kind:1059 #p=self). Returns a sub_id.
    func mlsSubscribeWelcomes(sinceSecs: UInt64) throws -> String
    /// Subscribe to KeyPackage rotations (kind:30443) from given contacts.
    func mlsSubscribeKeypackageRotations(contactPubkeys: [String]) throws -> String

    // ── Identity / encryption (Issue #178 #1, #11) ──
    /// Provide a 32-byte SQLCipher key. Must be called before `login()`.
    func setMlsDbKey(key: [UInt8]) throws
    /// Wipe + reopen the MLS DB for a new identity.
    func mlsReset(newPubkeyHex: String) throws
}

// MARK: - Stub (fallback when XCFramework is not yet linked)

final class MlsFFIStub: MlsFFIBridge, @unchecked Sendable {
    func connect() {}
    func disconnect() throws {}
    func mlsIsEncrypted() -> Bool? { nil }

    func mlsCreateKeyPackage() throws -> FfiKeyPackageEventData {
        FfiKeyPackageEventData(kind: 30443, content: "", tags: [], legacyTags: [], dTag: "")
    }

    func mlsValidateKeyPackageEvent(eventJSON: String) throws {}
    func mlsDeleteConsumedKeyPackageFromEventJSON(eventJSON: String) throws {}
    func mlsDeleteConsumedKeyPackageByHashRef(hashRef: [UInt8]) throws {}
    func mlsGroupsNeedingSelfUpdate(thresholdSecs: UInt64) throws -> [String] { [] }

    func mlsCreateGroup(name: String, adminPubkeys: [String], relays: [String]) throws -> FfiMlsGroupInfo {
        FfiMlsGroupInfo(
            groupIdHex: UUID().uuidString,
            name: name,
            description: "",
            adminPubkeys: adminPubkeys,
            memberPubkeys: adminPubkeys,
            relays: relays,
            createdAt: 0,
            epoch: 0,
            disappearingMessageSecs: nil,
            isDm: false
        )
    }

    func mlsAddMember(groupIdHex: String, keyPackageEventJSON: String) throws -> FfiAddMemberResult {
        FfiAddMemberResult(
            commitEventData: FfiEncryptedMessageData(content: "", tags: [], ephemeralPubkey: ""),
            welcomeEventData: FfiWelcomeEventData(recipientPubkey: "", content: "", tags: [], giftWrappedEventJson: "", innerRumorJson: "")
        )
    }

    func mlsRemoveMember(groupIdHex: String, memberPubkeyHex: String) throws -> FfiEncryptedMessageData {
        FfiEncryptedMessageData(content: "", tags: [], ephemeralPubkey: "")
    }

    func mlsLeaveGroup(groupIdHex: String) throws -> FfiEncryptedMessageData {
        FfiEncryptedMessageData(content: "", tags: [], ephemeralPubkey: "")
    }

    func mlsListGroups() throws -> [FfiMlsGroupInfo] { [] }

    func mlsGetGroupInfo(groupIdHex: String) throws -> FfiMlsGroupInfo {
        FfiMlsGroupInfo(groupIdHex: groupIdHex, name: "", description: "", adminPubkeys: [], memberPubkeys: [], relays: [], createdAt: 0, epoch: 0, disappearingMessageSecs: nil, isDm: false)
    }

    func mlsCreateMessage(groupIdHex: String, content: String) throws -> FfiEncryptedMessageData {
        FfiEncryptedMessageData(content: content, tags: [], ephemeralPubkey: "")
    }

    func mlsProcessMessage(groupIdHex: String, eventJSON: String) throws -> FfiDecryptedMessage {
        FfiDecryptedMessage(senderPubkey: "", content: "", timestamp: 0, groupIdHex: groupIdHex)
    }

    func mlsProcessMessageResult(groupIdHex: String, eventJSON: String) throws -> FfiMlsProcessResult {
        .application(FfiDecryptedMessage(senderPubkey: "", content: "", timestamp: 0, groupIdHex: groupIdHex))
    }

    func mlsProcessWelcome(welcomeEventJSON: String) throws -> FfiMlsGroupInfo {
        FfiMlsGroupInfo(groupIdHex: "", name: "", description: "", adminPubkeys: [], memberPubkeys: [], relays: [], createdAt: 0, epoch: 0, disappearingMessageSecs: nil, isDm: false)
    }

    func mlsGetMessageHistory(groupIdHex: String, limit: UInt64) throws -> [FfiDecryptedMessage] { [] }
    func mlsMergePendingCommit(groupIdHex: String) throws {}

    func mlsCreateRecoveryCommit(groupIdHex: String) throws -> FfiEncryptedMessageData {
        FfiEncryptedMessageData(content: "", tags: [], ephemeralPubkey: "")
    }

    func mlsClearPendingCommit(groupIdHex: String) throws {}

    // Issue #178 #4 split-welcome stubs
    func mlsPreviewWelcome(welcomeEventJSON: String) throws -> FfiPendingWelcome {
        FfiPendingWelcome(
            welcomeEventIdHex: "",
            wrapperEventIdHex: "",
            groupIdHex: "",
            groupName: "",
            groupDescription: "",
            groupAdminPubkeys: [],
            groupRelays: [],
            welcomerPubkey: "",
            memberCount: 0,
            isDm: false
        )
    }

    func mlsAcceptWelcome(welcomeEventIdHex: String) throws -> FfiPendingWelcome {
        try mlsPreviewWelcome(welcomeEventJSON: "")
    }

    func mlsDeclineWelcome(welcomeEventIdHex: String) throws {}

    func mlsGetPendingWelcomes() throws -> [FfiPendingWelcome] { [] }

    // Issue #178 #9, #10 subscription stubs
    func mlsSubscribeWelcomes(sinceSecs: UInt64) throws -> String { "" }
    func mlsSubscribeKeypackageRotations(contactPubkeys: [String]) throws -> String { "" }

    // Issue #178 #1, #11 identity/encryption stubs
    func setMlsDbKey(key: [UInt8]) throws {}
    func mlsReset(newPubkeyHex: String) throws {}
}
