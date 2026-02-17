/**
 * Rust ↔ JS bridge module.
 *
 * Attempts to load the native napi-rs engine.
 * Falls back to null when the .node binary is not available
 * (e.g. dev without Rust toolchain, CI, Vercel deploy).
 *
 * Usage:
 *   import { engine } from '@/lib/rust-bridge';
 *   if (engine) {
 *     const client = await engine.NuruNuruNapi.create(key, './nurunuru-db');
 *     await client.connect();
 *   }
 */

let engine = null;

try {
  engine = require('../rust-engine/nurunuru-napi/nurunuru-napi.node');
} catch {
  // Rust native module not built — use existing JS implementations.
  engine = null;
}

module.exports = { engine };
