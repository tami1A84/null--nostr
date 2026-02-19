fn main() {
    // Proc-macro approach: uniffi::setup_scaffolding!() in src/lib.rs handles
    // all FFI scaffolding generation at compile time via #[uniffi::export] and
    // #[derive(uniffi::Object/Record/Error)] attributes.
    //
    // Binding generation (Swift / Kotlin) is done at development time via:
    //   cargo run --bin uniffi-bindgen -- generate --library <dylib> --language swift --out-dir bindgen/swift-out/
    //   cargo run --bin uniffi-bindgen -- generate --library <so>    --language kotlin --out-dir bindgen/kotlin-out/
    //
    // See bindgen/Makefile for convenience targets.
}
