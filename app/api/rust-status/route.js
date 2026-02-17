/**
 * GET /api/rust-status
 *
 * Returns the availability status of the Rust native engine.
 */
import { resolve } from 'node:path'

let engineStatus = null

try {
  const modulePath = resolve(process.cwd(), 'rust-engine/nurunuru-napi/nurunuru-napi.node')
  const nativeRequire = typeof __non_webpack_require__ !== 'undefined'
    ? __non_webpack_require__
    : require
  const engine = nativeRequire(modulePath)
  engineStatus = {
    available: true,
    exports: Object.keys(engine),
  }
} catch (e) {
  engineStatus = {
    available: false,
    error: e.message,
  }
}

export async function GET() {
  return Response.json({
    rustEngine: engineStatus,
    runtime: process.env.NEXT_RUNTIME || 'nodejs',
    nodeVersion: process.version,
  })
}
