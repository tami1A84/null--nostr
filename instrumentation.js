/**
 * Next.js Instrumentation — disabled.
 * Rust engine (nurunuru-napi) is no longer used on the web server.
 * Native app support is handled via nurunuru-ffi (Step 10).
 */
export async function register() {
  // No-op: Rust engine not used in web deployment
}
