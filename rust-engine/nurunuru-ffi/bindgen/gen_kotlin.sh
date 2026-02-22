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

echo "==> Building nurunuru-ffi for host (for bindgen introspection)..."
cargo build --release

# Use host .so / .dylib for bindgen introspection (workspace-aware)
if [[ "$(uname)" == "Darwin" ]]; then
    LIB="../target/release/libnurunuru_ffi.dylib"
else
    LIB="../target/release/libnurunuru_ffi.so"
fi

echo "==> Generating Kotlin bindings from $LIB ..."
mkdir -p bindgen/kotlin-out

cargo run --bin uniffi-bindgen -- generate \
    --library "$LIB" \
    --language kotlin \
    --out-dir bindgen/kotlin-out/

echo ""
echo "✓ Kotlin bindings written to bindgen/kotlin-out/"
ls -1 bindgen/kotlin-out/
echo ""
echo "Next steps:"
echo "  make android-arm64    — build .so for aarch64-linux-android"
echo "  make android-x86_64   — build .so for x86_64-linux-android (emulator)"
echo "  Copy .so files to android/libs/<abi>/ and import the Android library module."
