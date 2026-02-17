/**
 * Rust ↔ JS bridge module (server-side only).
 *
 * Loads the native napi-rs engine using __non_webpack_require__
 * to bypass webpack bundling of .node files.
 *
 * Usage (in API routes or server components only):
 *   import { getEngine } from '@/lib/rust-bridge';
 *   const engine = getEngine();
 *   if (engine) {
 *     const client = await engine.NuruNuruNapi.create(key, './nurunuru-db');
 *     await client.connect();
 *   }
 */

let engine = null
let loaded = false

function loadEngine() {
  if (loaded) return engine
  loaded = true

  if (typeof window !== 'undefined') return null

  try {
    const path = require('node:path')
    const modulePath = path.resolve(process.cwd(), 'rust-engine/nurunuru-napi/nurunuru-napi.node')
    const nativeRequire = typeof __non_webpack_require__ !== 'undefined'
      ? __non_webpack_require__
      : require
    engine = nativeRequire(modulePath)
    console.log('[rust-bridge] Rust engine loaded')
  } catch (e) {
    engine = null
    console.log('[rust-bridge] Rust engine not available:', e.message)
  }
  return engine
}

function getEngine() {
  return loadEngine()
}

module.exports = { getEngine }
