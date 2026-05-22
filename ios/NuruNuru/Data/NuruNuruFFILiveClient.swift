import Foundation

#if NURUNURU_FFI_AVAILABLE
import NuruNuruFFILib

/// Live implementation of `MlsFFIBridge` backed by `NuruNuruClient` (UniFFI).
/// `groupIdHex` parameters/results are Nostr group id hex values (Kind 445 `h` tags);
/// internal MDK/OpenMLS group ids are resolved and hidden by Rust.
final class MlsFFILiveClient: MlsFFIBridge, @unchecked Sendable {
    private let client: NuruNuruClient

    /// Internal-signer (full-access) init. Issue #181: callers MUST supply
    /// the 32-byte SQLCipher key derived from the user's nsec via
    /// `MlsDbKeyStore.deriveInternalKey(secretKeyHex:)`. The legacy unkeyed
    /// constructors `NuruNuruClient(secretKeyHex:)` / `newReadOnly(pubkeyHex:)`
    /// MUST NOT be called from app code — they produce plaintext MLS DBs.
    ///
    /// **Zeroization (B3)**: takes `mlsDbKey` as `inout Data` and wipes it
    /// after the FFI hand-off. `Data` is a value type, but `resetBytes(in:)`
    /// mutates the underlying storage in place via the COW machinery — the
    /// caller's binding becomes 32 zero bytes (verified). The previous
    /// `var z = dbKey` pattern was a no-op copy.
    init(secretKeyHex: String, dbPath: String, mlsDbKey: inout Data) throws {
        try initEngine(dbPath: dbPath)
        defer { mlsDbKey.resetBytes(in: 0..<mlsDbKey.count) }
        self.client = try NuruNuruClient.newWithMlsDbKey(
            secretKeyHex: secretKeyHex,
            mlsDbKey: mlsDbKey
        )
        try Self.assertEncrypted(client: client)
    }

    /// External-signer (read-only) init. Same contract as the full init.
    init(pubkeyHex: String, dbPath: String, mlsDbKey: inout Data) throws {
        try initEngine(dbPath: dbPath)
        defer { mlsDbKey.resetBytes(in: 0..<mlsDbKey.count) }
        self.client = try NuruNuruClient.newReadOnlyWithMlsDbKey(
            pubkeyHex: pubkeyHex,
            mlsDbKey: mlsDbKey
        )
        try Self.assertEncrypted(client: client)
    }

    /// Issue #181 B5+B7: hard-fail when the MLS DB ended up unencrypted (e.g.
    /// a regression in the engine, a missed migration, or a stale plaintext
    /// file that slipped past the purge). We refuse to expose an `MlsFFIBridge`
    /// that's writing keys to a plaintext SQLite — surface as a thrown error
    /// so the caller can show actionable UI instead of "Talk is empty".
    private static func assertEncrypted(client: NuruNuruClient) throws {
        let state = client.mlsIsEncrypted()
        guard state == true else {
            AppLogger.log("FFI", "MLS DB encryption assertion FAILED (state=\(String(describing: state))) — refusing to proceed (issue #181)")
            try? client.disconnect()
            throw MlsFFILiveClientError.encryptionRequired
        }
        AppLogger.log("FFI", "MLS DB encrypted (SQLCipher) — issue #181 guard OK")
    }

    func connect() { client.connect() }
    func disconnect() throws { try client.disconnect() }

    /// Issue #181 B7: expose the engine's encryption state for diagnostics
    /// / Settings UI ("MLS DB: encrypted"). Returns `nil` when there is no
    /// MLS manager bound yet.
    func mlsIsEncrypted() -> Bool? { client.mlsIsEncrypted() }

    func mlsCreateKeyPackage() throws -> FfiKeyPackageEventData {
        bridgeKeyPackage(try client.mlsCreateKeyPackage())
    }

    func mlsValidateKeyPackageEvent(eventJSON: String) throws {
        try client.mlsValidateKeyPackageEvent(keyPackageEventJson: eventJSON)
    }

    func mlsDeleteConsumedKeyPackageFromEventJSON(eventJSON: String) throws {
        try client.mlsDeleteConsumedKeyPackageFromEventJson(keyPackageEventJson: eventJSON)
    }

    func mlsDeleteConsumedKeyPackageByHashRef(hashRef: [UInt8]) throws {
        try client.mlsDeleteConsumedKeyPackageByHashRef(hashRef: Data(hashRef))
    }

    func mlsGroupsNeedingSelfUpdate(thresholdSecs: UInt64) throws -> [String] {
        try client.mlsGroupsNeedingSelfUpdate(thresholdSecs: thresholdSecs)
    }

    func mlsCreateGroup(name: String, adminPubkeys: [String], relays: [String]) throws -> FfiMlsGroupInfo {
        bridgeGroupInfo(try client.mlsCreateGroup(name: name, adminPubkeys: adminPubkeys, relays: relays))
    }

    func mlsAddMember(groupIdHex: String, keyPackageEventJSON: String) throws -> FfiAddMemberResult {
        let r = try client.mlsAddMember(groupIdHex: groupIdHex, keyPackageEventJson: keyPackageEventJSON)
        return bridgeAddMemberResult(r)
    }

    func mlsRemoveMember(groupIdHex: String, memberPubkeyHex: String) throws -> FfiEncryptedMessageData {
        bridgeEncryptedMsg(try client.mlsRemoveMember(groupIdHex: groupIdHex, memberPubkey: memberPubkeyHex))
    }

    func mlsLeaveGroup(groupIdHex: String) throws -> FfiEncryptedMessageData {
        bridgeEncryptedMsg(try client.mlsLeaveGroup(groupIdHex: groupIdHex))
    }

    func mlsListGroups() throws -> [FfiMlsGroupInfo] {
        try client.mlsListGroups().map { bridgeGroupInfo($0) }
    }

    func mlsGetGroupInfo(groupIdHex: String) throws -> FfiMlsGroupInfo {
        bridgeGroupInfo(try client.mlsGetGroupInfo(groupIdHex: groupIdHex))
    }

    func mlsCreateMessage(groupIdHex: String, content: String) throws -> FfiEncryptedMessageData {
        bridgeEncryptedMsg(try client.mlsCreateMessage(groupIdHex: groupIdHex, content: content))
    }

    func mlsProcessMessage(groupIdHex: String, eventJSON: String) throws -> FfiDecryptedMessage {
        bridgeDecryptedMessage(try client.mlsProcessMessage(groupIdHex: groupIdHex, eventJson: eventJSON))
    }

    func mlsProcessMessageResult(groupIdHex: String, eventJSON: String) throws -> FfiMlsProcessResult {
        let result = try client.mlsProcessMessageResult(groupIdHex: groupIdHex, eventJson: eventJSON)
        switch result {
        case let .application(message):
            return .application(bridgeDecryptedMessage(message))
        case let .commit(gid, added, removed, epochAfter):
            return .commit(groupIdHex: gid, added: added, removed: removed, epochAfter: epochAfter)
        case let .needsSelfUpdate(gid, reason):
            return .needsSelfUpdate(groupIdHex: gid, reason: reason)
        case let .stateUpdate(kind):
            return .stateUpdate(kind)
        }
    }

    func mlsProcessWelcome(welcomeEventJSON: String) throws -> FfiMlsGroupInfo {
        bridgeGroupInfo(try client.mlsProcessWelcome(welcomeEventJson: welcomeEventJSON))
    }

    // Issue #178 #4 — split Welcome flow.
    func mlsPreviewWelcome(welcomeEventJSON: String) throws -> FfiPendingWelcome {
        bridgePendingWelcome(try client.mlsPreviewWelcome(welcomeEventJson: welcomeEventJSON))
    }

    func mlsAcceptWelcome(welcomeEventIdHex: String) throws -> FfiPendingWelcome {
        bridgePendingWelcome(try client.mlsAcceptWelcome(welcomeEventIdHex: welcomeEventIdHex))
    }

    func mlsDeclineWelcome(welcomeEventIdHex: String) throws {
        try client.mlsDeclineWelcome(welcomeEventIdHex: welcomeEventIdHex)
    }

    func mlsGetPendingWelcomes() throws -> [FfiPendingWelcome] {
        try client.mlsGetPendingWelcomes().map { bridgePendingWelcome($0) }
    }

    // Issue #178 #9, #10 — engine-side subscriptions.
    func mlsSubscribeWelcomes(sinceSecs: UInt64) throws -> String {
        try client.mlsSubscribeWelcomes(sinceSecs: sinceSecs)
    }

    func mlsSubscribeKeypackageRotations(contactPubkeys: [String]) throws -> String {
        try client.mlsSubscribeKeypackageRotations(contactPubkeys: contactPubkeys)
    }

    // Issue #178 #1, #11 — identity / encryption.
    func setMlsDbKey(key: [UInt8]) throws {
        try client.setMlsDbKey(key: Data(key))
    }

    func mlsReset(newPubkeyHex: String) throws {
        try client.mlsReset(newPubkeyHex: newPubkeyHex)
    }

    func mlsGetMessageHistory(groupIdHex: String, limit: UInt64) throws -> [FfiDecryptedMessage] {
        try client.mlsGetMessageHistory(groupIdHex: groupIdHex, limit: limit).map { bridgeDecryptedMessage($0) }
    }

    func mlsMergePendingCommit(groupIdHex: String) throws {
        try client.mlsMergePendingCommit(groupIdHex: groupIdHex)
    }

    func mlsCreateRecoveryCommit(groupIdHex: String) throws -> FfiEncryptedMessageData {
        bridgeEncryptedMsg(try client.mlsCreateRecoveryCommit(groupIdHex: groupIdHex))
    }

    func mlsClearPendingCommit(groupIdHex: String) throws {
        try client.mlsClearPendingCommit(groupIdHex: groupIdHex)
    }

    // Issue #183 — peer-epoch deep catch-up.
    func mlsCatchUpToPeer(groupIdHex: String, candidateEventsJson: [String]) throws -> FfiMlsCatchUpReport {
        let r = try client.mlsCatchUpToPeer(groupIdHex: groupIdHex, candidateEventsJson: candidateEventsJson)
        return FfiMlsCatchUpReport(
            groupIdHex: r.groupIdHex,
            epochBefore: r.epochBefore,
            epochAfter: r.epochAfter,
            candidatesConsidered: r.candidatesConsidered,
            applicationMessagesApplied: r.applicationMessagesApplied,
            commitsApplied: r.commitsApplied,
            stillUnprocessable: r.stillUnprocessable,
            cacheHits: r.cacheHits,
            status: bridgeCatchUpStatus(r.status)
        )
    }

    func mlsPruneReplayCache() throws -> UInt64 {
        try client.mlsPruneReplayCache()
    }

    func mlsReplayCacheSize(groupIdHex: String) throws -> UInt64 {
        try client.mlsReplayCacheSize(groupIdHex: groupIdHex)
    }

    private func bridgeCatchUpStatus(_ s: NuruNuruFFILib.FfiMlsCatchUpStatus) -> FfiMlsCatchUpStatus {
        switch s {
        case .recovered:          return .recovered
        case .partiallyRecovered: return .partiallyRecovered
        case .notRecoverable:     return .notRecoverable
        case .noSuchGroup:        return .noSuchGroup
        }
    }

    // MARK: - Helpers

    private func bridgeGroupInfo(_ g: NuruNuruFFILib.FfiMlsGroupInfo) -> FfiMlsGroupInfo {
        FfiMlsGroupInfo(
            groupIdHex: g.groupIdHex,
            name: g.name,
            description: g.description,
            adminPubkeys: g.adminPubkeys,
            memberPubkeys: g.memberPubkeys,
            relays: g.relays,
            createdAt: g.createdAt,
            epoch: g.epoch,
            disappearingMessageSecs: extractDisappearingMessageSecs(g),
            isDm: g.isDm
        )
    }

    // Extract disappearing_message_secs from generated UniFFI type across binding versions.
    private func extractDisappearingMessageSecs(_ g: NuruNuruFFILib.FfiMlsGroupInfo) -> UInt64? {
        let mirror = Mirror(reflecting: g)
        for child in mirror.children {
            guard let label = child.label else { continue }
            if label == "disappearingMessageSecs" || label == "disappearing_message_secs" {
                if let v = child.value as? UInt64 { return v }
                if let v = child.value as? Int { return v >= 0 ? UInt64(v) : nil }
                if let v = child.value as? NSNumber { return v.uint64Value }
                let opt = Mirror(reflecting: child.value)
                if opt.displayStyle == .optional, let some = opt.children.first?.value {
                    if let v = some as? UInt64 { return v }
                    if let v = some as? Int { return v >= 0 ? UInt64(v) : nil }
                    if let v = some as? NSNumber { return v.uint64Value }
                }
            }
        }
        return nil
    }

    private func bridgeDecryptedMessage(_ m: NuruNuruFFILib.FfiDecryptedMessage) -> FfiDecryptedMessage {
        FfiDecryptedMessage(
            senderPubkey: m.senderPubkey,
            content: m.content,
            timestamp: m.timestamp,
            groupIdHex: m.groupIdHex
        )
    }

    private func bridgeEncryptedMsg(_ d: NuruNuruFFILib.FfiEncryptedMessageData) -> FfiEncryptedMessageData {
        FfiEncryptedMessageData(content: d.content, tags: d.tags, ephemeralPubkey: d.ephemeralPubkey)
    }

    private func bridgeWelcomeEvent(_ w: NuruNuruFFILib.FfiWelcomeEventData) -> FfiWelcomeEventData {
        FfiWelcomeEventData(
            recipientPubkey: w.recipientPubkey,
            content: w.innerRumorJson,
            tags: w.tags,
            giftWrappedEventJson: w.giftWrappedEventJson,
            innerRumorJson: w.innerRumorJson
        )
    }

    private func bridgeKeyPackage(_ d: NuruNuruFFILib.FfiKeyPackageEventData) -> FfiKeyPackageEventData {
        FfiKeyPackageEventData(kind: d.kind, content: d.content, tags: d.tags, legacyTags: d.legacyTags, dTag: d.dTag, hashRef: Array(d.hashRef))
    }

    private func bridgeAddMemberResult(_ r: NuruNuruFFILib.FfiAddMemberResult) -> FfiAddMemberResult {
        FfiAddMemberResult(
            commitEventData: bridgeEncryptedMsg(r.commitEventData),
            welcomeEventData: bridgeWelcomeEvent(r.welcomeEventData)
        )
    }

    private func bridgePendingWelcome(_ p: NuruNuruFFILib.FfiPendingWelcome) -> FfiPendingWelcome {
        FfiPendingWelcome(
            welcomeEventIdHex: p.welcomeEventIdHex,
            wrapperEventIdHex: p.wrapperEventIdHex,
            groupIdHex: p.groupIdHex,
            groupName: p.groupName,
            groupDescription: p.groupDescription,
            groupAdminPubkeys: p.groupAdminPubkeys,
            groupRelays: p.groupRelays,
            welcomerPubkey: p.welcomerPubkey,
            memberCount: p.memberCount,
            isDm: p.isDm
        )
    }
}

/// Issue #181: errors thrown by the live (encrypted) MLS bridge ctor.
enum MlsFFILiveClientError: Error, LocalizedError {
    /// `mlsIsEncrypted()` returned `false` or `nil` after init — refuse to
    /// proceed because the DB would write key material in cleartext.
    case encryptionRequired

    var errorDescription: String? {
        switch self {
        case .encryptionRequired:
            return "MLS DB must be encrypted (SQLCipher) — refusing to start in plaintext mode (issue #181)"
        }
    }
}
#endif
