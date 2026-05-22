#!/usr/bin/env bash
# gen_swift.sh — Generate Swift bindings for iOS / macOS.
#
# Prerequisites:
#   - macOS host with Xcode Command Line Tools
#   - Rust toolchain: rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
#
# Output:
#   bindgen/swift-out/
#     nurunuru.swift           — Generated Swift API
#     nurunuru_ffiFFI.h        — C header for the XCFramework
#     nurunuru_ffiFFI.modulemap

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$(dirname "$SCRIPT_DIR")"

cd "$CRATE_DIR"

echo "==> Building nurunuru-ffi (debug, for bindgen introspection)..."
# NOTE: bindgen requires the DEBUG build because the workspace release profile
# has `strip = true`. uniffi-bindgen reads UNIFFI_META_* symbols from the
# static symbol table (.symtab); release strip removes it, leaving the
# generated bindings incomplete (missing types/functions silently). Mirrors
# the approach used by gen_kotlin.sh.
cargo build

# Determine the host library path used by uniffi-bindgen.
if [[ "$(uname)" == "Darwin" ]]; then
    LIB_PATH="../target/debug/libuniffi_nurunuru.dylib"
else
    echo "ERROR: Swift binding generation requires a macOS host."
    exit 1
fi

echo "==> Generating Swift bindings from $LIB_PATH ..."
mkdir -p bindgen/swift-out

cargo run --bin uniffi-bindgen -- generate \
    --library "$LIB_PATH" \
    --language swift \
    --out-dir bindgen/swift-out/

echo ""
echo "✓ Swift bindings written to bindgen/swift-out/"
ls -1 bindgen/swift-out/
echo ""
echo "Next steps:"
echo "  make xcframework   — build XCFramework for iOS device + simulator"
echo "  make ios-device    — build static lib for aarch64-apple-ios only"