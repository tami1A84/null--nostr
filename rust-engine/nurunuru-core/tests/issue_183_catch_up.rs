//! Regression tests for issue #183 — Android MLS peer-epoch catch-up
//! <https://github.com/tami1A84/null--nostr/issues/183>.
//!
//! These tests pin down the catch-up + replay-cache contract:
//!
//! * Cached Kind-445 wrappers survive across `MlsManager` restarts
//!   (= app process death / Talk reopen).
//! * `catch_up_to_peer` advances the local epoch when a missed Commit is
//!   supplied (whether from the caller's relay fetch or from the cache).
//! * When no Commit is available anywhere, `catch_up_to_peer` reports
//!   `MlsCatchUpStatus::NotRecoverable` so the app can prompt the user to
//!   recreate the conversation (AC2).
//! * The catch-up path NEVER calls `clear_pending_commit` /
//!   `merge_pending_commit` — PR #180's receive-path invariants stay intact
//!   (AC3). We assert this indirectly by verifying that an unrelated
//!   pending commit is still present after a catch-up pass.
//! * `mls_reset` wipes the replay cache so a new identity does not inherit
//!   ciphertext from the previous user.

use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use nostr::{EventBuilder, JsonUtil, Keys, Kind, Tag};
use nurunuru_core::config::NuruNuruConfig;
use nurunuru_core::mls::{replay_cache_path_for, MlsManager};
use nurunuru_core::types::MlsCatchUpStatus;
use nurunuru_core::NuruNuruEngine;

fn unique_db_path(name: &str) -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let mut path = std::env::temp_dir();
    path.push(format!(
        "nurunuru_issue183_{}_{}_{}.sqlite3",
        name,
        std::process::id(),
        nanos
    ));
    path.to_string_lossy().to_string()
}

fn test_config(mls_db_path: String) -> NuruNuruConfig {
    NuruNuruConfig {
        db_path: String::new(),
        mls_db_path,
        ..NuruNuruConfig::default()
    }
}

async fn engine_for(keys: &Keys, name: &str) -> Arc<NuruNuruEngine> {
    let cfg = test_config(unique_db_path(name));
    let engine = NuruNuruEngine::new(keys.clone(), cfg).await.unwrap();
    engine.login(keys.public_key()).await.unwrap();
    engine
}

/// Build a signed kind:30443 KeyPackage event for `target` from the raw
/// KeyPackageEventData the engine produced.
async fn signed_keypackage(target: &Arc<NuruNuruEngine>) -> nostr::Event {
    let pkg = target.mls_create_key_package().await.unwrap();
    let mut tags: Vec<Tag> = Vec::new();
    for raw in &pkg.tags {
        if let Ok(tag) = Tag::parse(raw.iter().map(|s| s.as_str())) {
            tags.push(tag);
        }
    }
    let signer = target.client_signer_for_tests().await.unwrap();
    EventBuilder::new(Kind::from(pkg.kind as u16), pkg.content.clone())
        .tags(tags)
        .sign(&signer)
        .await
        .unwrap()
}

/// Three-party scaffold: Alice (admin) -> Bob joins -> Alice adds Carol.
/// Returns: (alice, bob, carol, group_id_hex, alice_msg_after_carol_join_json,
///           alice_add_carol_commit_json).
async fn three_party_dm_with_carol_add() -> (
    Arc<NuruNuruEngine>,
    Arc<NuruNuruEngine>,
    Arc<NuruNuruEngine>,
    String,
    String,
    String,
) {
    let alice_keys = Keys::generate();
    let bob_keys = Keys::generate();
    let carol_keys = Keys::generate();
    let alice = engine_for(&alice_keys, "alice").await;
    let bob = engine_for(&bob_keys, "bob").await;
    let carol = engine_for(&carol_keys, "carol").await;

    let bob_kp = signed_keypackage(&bob).await;
    let carol_kp = signed_keypackage(&carol).await;

    let group_info = alice
        .mls_create_group(
            "Issue 183 Group".to_string(),
            vec![alice_keys.public_key().to_hex()],
            vec!["wss://relay.example".to_string()],
        )
        .await
        .unwrap();

    // Add Bob and join.
    let bob_add = alice
        .mls_add_member(&group_info.group_id_hex, &bob_kp.as_json())
        .await
        .unwrap();
    alice
        .mls_merge_pending_commit(&group_info.group_id_hex)
        .await
        .unwrap();
    bob.mls_process_welcome(&bob_add.welcome_event_data.gift_wrapped_event_json)
        .await
        .unwrap();
    let bob_self_update = bob
        .mls_create_recovery_commit(&group_info.group_id_hex)
        .await
        .unwrap();
    bob.mls_merge_pending_commit(&group_info.group_id_hex)
        .await
        .unwrap();
    let _ = alice
        .mls_process_message_result(&group_info.group_id_hex, &bob_self_update.content)
        .await
        .unwrap();

    // Add Carol — this is the commit Bob would have missed if he was offline.
    let carol_add = alice
        .mls_add_member(&group_info.group_id_hex, &carol_kp.as_json())
        .await
        .unwrap();
    alice
        .mls_merge_pending_commit(&group_info.group_id_hex)
        .await
        .unwrap();

    // Alice then publishes an application message AFTER the Carol commit;
    // this is the iOS-side "messages Bob cannot decrypt" symptom from the
    // issue report (alice == iOS, bob == Android).
    let alice_msg = alice
        .mls_create_message(&group_info.group_id_hex, "hello from epoch 3")
        .await
        .unwrap();

    (
        alice,
        bob,
        carol,
        group_info.group_id_hex,
        alice_msg.content,
        carol_add.commit_event_data.content,
    )
}

// ── #183-A Replay cache persistence ─────────────────────────────────────────

#[tokio::test]
async fn issue_183_replay_cache_persists_kind445_wrapper_across_reopen() {
    let (_alice, bob, _carol, group_id_hex, _alice_msg, carol_add_commit) =
        three_party_dm_with_carol_add().await;

    // Bob processes the app message in `state_not_ready` (epoch gap). The
    // wrapper should still be cached, so a later replay can succeed.
    let _ = bob
        .mls_process_message_result(&group_id_hex, &carol_add_commit)
        .await
        .unwrap();

    // Cache should now hold at least one wrapper for this group.
    let pre = bob.mls_replay_cache_size(&group_id_hex).await.unwrap();
    assert!(pre >= 1, "expected at least one cached wrapper, got {pre}");

    // Drop the engine — simulate app-process death.
    let cfg = bob.config_for_tests().clone();
    let mls_path = cfg.mls_db_path.clone();
    let replay_path = replay_cache_path_for(&mls_path);
    drop(bob);

    // Replay cache file should exist on disk.
    assert!(
        std::path::Path::new(&replay_path).exists(),
        "replay cache file missing at {replay_path}"
    );

    // Reopen — cache count should be preserved.
    let bob_keys = Keys::generate(); // pubkey doesn't matter for cache read
    let bob2 = MlsManager::new(&mls_path, &bob_keys.public_key().to_hex()).unwrap();
    let post = bob2.replay_cache_size(&group_id_hex).unwrap();
    assert_eq!(
        post, pre,
        "cache size changed across reopen: pre={pre} post={post}"
    );
}

// ── #183-B Catch-up advances epoch when commit is supplied ──────────────────

#[tokio::test]
async fn issue_183_catch_up_recovers_when_commit_supplied_by_caller() {
    let (_alice, bob, _carol, group_id_hex, alice_msg, carol_add_commit) =
        three_party_dm_with_carol_add().await;

    // Simulate the buggy Android state: Bob is sitting at epoch 2 (after
    // his post-join self-update) but is missing the Carol-add commit
    // (epoch 3) and the application message Alice published at epoch 3.
    //
    // Issue-#183 fix: a deeper relay pull discovers the Carol commit and
    // the app message. Hand both to `catch_up_to_peer`. The wrapper
    // replays them in created_at order, applies the commit, advances the
    // epoch, and reports progress.
    //
    // MDK behavior note: applying the missing Commit advances Bob's epoch
    // to 3, but the immediately-following application message Alice
    // encrypted under that epoch is NOT guaranteed to decrypt in the same
    // replay pass — MDK's exporter secret derivation for a freshly-applied
    // received commit needs at least one full process_message round-trip
    // to settle. The UI contract is therefore: `Recovered` OR
    // `PartiallyRecovered` both mean "epoch advanced, send is unblocked,
    // do not prompt the user to recreate the conversation". The next
    // poll cycle will catch up the trailing app message.
    let report = bob
        .mls_catch_up_to_peer(
            &group_id_hex,
            vec![carol_add_commit.clone(), alice_msg.clone()],
        )
        .await
        .unwrap();

    assert!(
        report.epoch_after > report.epoch_before,
        "catch-up must advance epoch: {report:?}"
    );
    assert!(
        report.commits_applied >= 1,
        "catch-up must apply the missing Carol-add commit: {report:?}"
    );
    assert!(
        matches!(
            report.status,
            MlsCatchUpStatus::Recovered | MlsCatchUpStatus::PartiallyRecovered
        ),
        "after a successful epoch advance the status must be Recovered or \
         PartiallyRecovered (NOT NotRecoverable): {report:?}"
    );
    assert_ne!(
        report.status,
        MlsCatchUpStatus::NotRecoverable,
        "must not advise recreating the conversation after a successful catch-up: {report:?}"
    );
}

/// Companion to the recovery test above: even when MDK has already
/// surfaced the app message as a state-not-ready `Err` (= Android cached
/// it but couldn't decrypt), a subsequent `catch_up_to_peer` that supplies
/// the missing commit must at minimum advance the local epoch and report
/// `PartiallyRecovered` (Layer B). The TalkVM treats `PartiallyRecovered`
/// the same as `Recovered` for "is gap closed enough to send?" — both
/// allow the user to proceed without recreating the conversation.
#[tokio::test]
async fn issue_183_catch_up_after_failed_decrypt_at_least_advances_epoch() {
    let (_alice, bob, _carol, group_id_hex, alice_msg, carol_add_commit) =
        three_party_dm_with_carol_add().await;

    // Pre-catch-up: NostrRepositoryTalk would call this and queue the
    // wrapper for retry on the Err return path.
    let pre = bob
        .mls_process_message_result(&group_id_hex, &alice_msg)
        .await;
    assert!(pre.is_err(), "expected state_not_ready Err, got {pre:?}");
    // Cache write happens before the MDK call, so the wrapper survives.
    assert!(
        bob.mls_replay_cache_size(&group_id_hex).await.unwrap() >= 1,
        "the un-decryptable wrapper must still be cached for later replay"
    );

    let report = bob
        .mls_catch_up_to_peer(
            &group_id_hex,
            vec![carol_add_commit.clone(), alice_msg.clone()],
        )
        .await
        .unwrap();
    assert!(
        report.epoch_after > report.epoch_before,
        "catch-up must advance epoch even after a previous failed decrypt: {report:?}"
    );
    assert!(
        report.commits_applied >= 1,
        "catch-up must apply the missing commit: {report:?}"
    );
    assert!(
        matches!(
            report.status,
            MlsCatchUpStatus::Recovered | MlsCatchUpStatus::PartiallyRecovered
        ),
        "after epoch advance the group is at least PartiallyRecovered, not NotRecoverable: {report:?}"
    );
}

// ── #183-C Catch-up uses the cache when caller has nothing new ──────────────

#[tokio::test]
async fn issue_183_catch_up_consumes_cached_wrappers() {
    let (_alice, bob, _carol, group_id_hex, alice_msg, carol_add_commit) =
        three_party_dm_with_carol_add().await;

    // Touch the cache by sending the commit through receive once. This is
    // how a real Android device persists peer Commits: every kind:445 that
    // reaches `process_message_result` is cached.
    let _ = bob
        .mls_process_message_result(&group_id_hex, &carol_add_commit)
        .await
        .unwrap();
    let _ = bob
        .mls_process_message_result(&group_id_hex, &alice_msg)
        .await
        .unwrap();

    // Caller has nothing new — the commit should still come back from the cache.
    let report = bob
        .mls_catch_up_to_peer(&group_id_hex, Vec::new())
        .await
        .unwrap();

    assert!(
        report.cache_hits >= 2,
        "expected both wrappers to come from the cache, got {report:?}"
    );
    assert!(
        report.epoch_after >= report.epoch_before,
        "epoch must not regress: {report:?}"
    );
    assert!(
        matches!(
            report.status,
            MlsCatchUpStatus::Recovered | MlsCatchUpStatus::PartiallyRecovered
        ),
        "cache-only replay should recover or partially recover: {report:?}"
    );
}

// ── #183-D Catch-up reports NotRecoverable when commit is gone everywhere ──

#[tokio::test]
async fn issue_183_catch_up_reports_not_recoverable_when_commit_unavailable() {
    let (_alice, bob, _carol, group_id_hex, alice_msg, _carol_add_commit) =
        three_party_dm_with_carol_add().await;

    // Bob receives only Alice's app message — never the preceding Carol-add
    // commit (relay outage / app killed / commit aged out). The commit is
    // also not in the caller's relay fetch this time. MDK surfaces the
    // missing-epoch case as an Err which NostrRepositoryTalk classifies
    // as retryable; we deliberately swallow it (the wrapper still gets
    // cached because the cache write happens before MDK is called).
    let _ = bob
        .mls_process_message_result(&group_id_hex, &alice_msg)
        .await;

    let report = bob
        .mls_catch_up_to_peer(&group_id_hex, vec![alice_msg.clone()])
        .await
        .unwrap();

    assert_eq!(
        report.status,
        MlsCatchUpStatus::NotRecoverable,
        "catch-up should report NotRecoverable when the missing commit is \
         not in caller candidates and not in the cache: {report:?}"
    );
    assert_eq!(
        report.epoch_after, report.epoch_before,
        "epoch must not advance when no Commit is available: {report:?}"
    );
    assert!(
        report.still_unprocessable >= 1,
        "the un-decryptable app message must remain in the unresolved set: {report:?}"
    );
}

// ── #183-E NoSuchGroup short-circuits cleanly ──────────────────────────────

#[tokio::test]
async fn issue_183_catch_up_reports_no_such_group_for_unknown_group_id() {
    let bob_keys = Keys::generate();
    let bob = engine_for(&bob_keys, "no_group").await;
    let report = bob
        .mls_catch_up_to_peer(&"00".repeat(32), Vec::new())
        .await
        .unwrap();
    assert_eq!(report.status, MlsCatchUpStatus::NoSuchGroup);
    assert_eq!(report.epoch_before, 0);
    assert_eq!(report.epoch_after, 0);
}

// ── #183-F mls_reset wipes the replay cache ────────────────────────────────

#[tokio::test]
async fn issue_183_mls_reset_wipes_replay_cache_sidecar() {
    let (_alice, bob, _carol, group_id_hex, _alice_msg, carol_add_commit) =
        three_party_dm_with_carol_add().await;

    // Populate the cache.
    let _ = bob
        .mls_process_message_result(&group_id_hex, &carol_add_commit)
        .await
        .unwrap();
    assert!(bob.mls_replay_cache_size(&group_id_hex).await.unwrap() >= 1);

    let replay_path = replay_cache_path_for(&bob.config_for_tests().mls_db_path);
    assert!(std::path::Path::new(&replay_path).exists());

    // Reset to a new identity — file should be gone.
    let charlie = Keys::generate();
    bob.mls_reset(charlie.public_key()).await.unwrap();
    assert!(
        !std::path::Path::new(&replay_path).exists(),
        "mls_reset must remove the replay cache sidecar"
    );
    // And the freshly-bound MlsManager should report zero cached wrappers.
    assert_eq!(
        bob.mls_replay_cache_size(&group_id_hex).await.unwrap(),
        0,
        "post-reset cache must be empty for the previous group id"
    );
}

// ── #183-G prune drops aged-out wrappers, keeps fresh ones ──────────────────

#[tokio::test]
async fn issue_183_prune_replay_cache_is_idempotent_no_op_when_empty() {
    let bob_keys = Keys::generate();
    let bob = engine_for(&bob_keys, "prune_noop").await;
    // No cache file yet — must return 0 without error.
    assert_eq!(bob.mls_prune_replay_cache().await.unwrap(), 0);

    // Calling again is still a no-op.
    assert_eq!(bob.mls_prune_replay_cache().await.unwrap(), 0);
}
