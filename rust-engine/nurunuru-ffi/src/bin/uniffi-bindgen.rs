//! uniffi-bindgen — generates Swift / Kotlin bindings from the compiled
//! nurunuru-ffi shared library.
//!
//! ## Usage
//!
//! ```sh
//! # macOS → Swift (requires a built dylib)
//! cargo run --bin uniffi-bindgen -- generate \
//!   --library target/release/libnurunuru_ffi.dylib \
//!   --language swift \
//!   --out-dir bindgen/swift-out/
//!
//! # Linux → Kotlin/Android (requires a built .so)
//! cargo run --bin uniffi-bindgen -- generate \
//!   --library target/release/libnurunuru_ffi.so \
//!   --language kotlin \
//!   --out-dir bindgen/kotlin-out/
//! ```
//!
//! See `bindgen/Makefile` for convenience targets that also handle
//! cross-compilation for iOS device/simulator and Android ABI targets.

fn main() {
    uniffi::uniffi_bindgen_main()
}
