// NuruNuruClient+Extensions.swift
// Swift-friendly convenience extensions on the UniFFI-generated NuruNuruClient.
//
// The core NuruNuruClient type and all FFI types (FfiUserProfile, FfiScoredPost,
// FfiConnectionStats, NuruNuruFfiError) are generated from Rust via UniFFI and
// placed in nurunuru_ffi.swift (generated — see `make xcframework`).

import Foundation

// MARK: - Default DB path

public extension NuruNuruClient {
    /// Returns the canonical local nostrdb path inside the iOS app sandbox.
    /// Pass this to the constructor to store data in the app's Documents folder.
    static func defaultDbPath() -> String {
        let docs = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)
            .first!
        return docs.appendingPathComponent("nurunuru-db").path
    }
}

// MARK: - Async/await bridge

public extension NuruNuruClient {
    /// Connect to relays on a background thread.
    func connectAsync() async {
        await Task.detached(priority: .userInitiated) { self.connect() }.value
    }

    /// Login (loads follow/mute lists) on a background thread.
    func loginAsync(pubkeyHex: String) async throws {
        try await Task.detached(priority: .userInitiated) {
            try self.login(pubkeyHex: pubkeyHex)
        }.value
    }

    /// Fetch profile on a background thread.
    func fetchProfileAsync(pubkeyHex: String) async throws -> FfiUserProfile? {
        try await Task.detached(priority: .userInitiated) {
            try self.fetchProfile(pubkeyHex: pubkeyHex)
        }.value
    }

    /// Get recommended feed on a background thread.
    func getRecommendedFeedAsync(limit: UInt32) async throws -> [FfiScoredPost] {
        try await Task.detached(priority: .userInitiated) {
            try self.getRecommendedFeed(limit: limit)
        }.value
    }

    /// Publish a text note on a background thread. Returns event ID hex.
    func publishNoteAsync(content: String) async throws -> String {
        try await Task.detached(priority: .userInitiated) {
            try self.publishNote(content: content)
        }.value
    }

    /// Send a DM on a background thread.
    func sendDmAsync(recipientHex: String, content: String) async throws {
        try await Task.detached(priority: .userInitiated) {
            try self.sendDm(recipientHex: recipientHex, content: content)
        }.value
    }

    /// Search on a background thread. Returns event ID hex strings.
    func searchAsync(query: String, limit: UInt32) async throws -> [String] {
        try await Task.detached(priority: .userInitiated) {
            try self.search(query: query, limit: limit)
        }.value
    }
}
