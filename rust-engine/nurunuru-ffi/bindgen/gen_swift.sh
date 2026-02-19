#!/usr/bin/env bash
# gen_swift.sh — Generate Swift bindings for iOS / macOS.
#
# Prerequisites:
#   - macOS host with Xcode Command Line Tools
#   - Rust toolchain: rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
#
# Output:
#   bindgen/swift-out/
#     nurunuru_ffi.swift       — Generated Swift API
#     nurunuru_ffiFFI.h        — C header for the XCFramework
#     nurunuru_ffiFFI.modulemap

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$(dirname "$SCRIPT_DIR")"

cd "$CRATE_DIR"

echo "==> Building nurunuru-ffi for host (macOS)..."
cargo build --release

# Determine the dylib path
if [[ "$(uname)" == "Darwin" ]]; then
    DYLIB="target/release/libnurunuru_ffi.dylib"
else
    echo "ERROR: Swift binding generation requires a macOS host."
    exit 1
fi

echo "==> Generating Swift bindings from $DYLIB ..."
mkdir -p bindgen/swift-out

cargo run --bin uniffi-bindgen -- generate \
    --library "$DYLIB" \
    --language swift \
    --out-dir bindgen/swift-out/

echo ""
echo "✓ Swift bindings written to bindgen/swift-out/"
ls -1 bindgen/swift-out/
echo ""
echo "Next steps:"
echo "  make xcframework   — build XCFramework for iOS device + simulator"
echo "  make ios-device    — build static lib for aarch64-apple-ios only"
