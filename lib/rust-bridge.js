/**
 * Rust bridge — disabled for web deployment.
 * nurunuru-napi is not used on the web server.
 * Native app support is handled via nurunuru-ffi (Step 10).
 */

function getEngine() {
  return null
}

module.exports = { getEngine }
