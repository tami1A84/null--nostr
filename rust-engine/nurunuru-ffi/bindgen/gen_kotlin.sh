#!/usr/bin/env bash
# gen_kotlin.sh — Generate Kotlin bindings for Android.
#
# Prerequisites:
#   - Linux or macOS host
#   - Rust toolchain: rustup target add aarch64-linux-android x86_64-linux-android
#   - Android NDK set up (for cross-compilation)
#
# Output:
#   bindgen/kotlin-out/
#     uniffi/nurunuru_ffi/nurunuru_ffi.kt   — Generated Kotlin API (JNA-based)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$(dirname "$SCRIPT_DIR")"

cd "$CRATE_DIR"

echo "==> Building nurunuru-ffi (debug, for bindgen introspection)..."
# NOTE: bindgen requires the DEBUG build because the release build strips the
# static symbol table (.symtab). uniffi-bindgen reads UNIFFI_META_* symbols
# from .symtab; only .dynsym survives a release strip.
cargo build

# Use debug .so / .dylib for bindgen introspection.
# The workspace places artifacts in the workspace-level target/, one level up.
if [[ "$(uname)" == "Darwin" ]]; then
    LIB="$CRATE_DIR/../target/debug/libuniffi_nurunuru.dylib"
else
    LIB="$CRATE_DIR/../target/debug/libuniffi_nurunuru.so"
fi

echo "==> Generating Kotlin bindings from $LIB ..."
mkdir -p "$CRATE_DIR/bindgen/kotlin-out"

cargo run --bin uniffi-bindgen -- generate \
    --library \
    --language kotlin \
    --out-dir "$CRATE_DIR/bindgen/kotlin-out/" \
    "$LIB"

echo ""
echo "✓ Kotlin bindings written to bindgen/kotlin-out/"
ls -1 bindgen/kotlin-out/
echo ""
echo "Next steps:"
echo "  make android-arm64    — build .so for aarch64-linux-android"
echo "  make android-x86_64   — build .so for x86_64-linux-android (emulator)"
echo "  Copy .so files to android/libs/<abi>/ and import the Android library module."
