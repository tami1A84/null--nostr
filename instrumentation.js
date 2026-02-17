/**
 * Next.js Instrumentation — runs once on server startup.
 * Loads the Rust native engine and logs its availability.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const path = await import('node:path')
    try {
      const modulePath = path.resolve(process.cwd(), 'rust-engine/nurunuru-napi/nurunuru-napi.node')
      // Use non-webpack require to load native .node module
      const nativeRequire = typeof __non_webpack_require__ !== 'undefined'
        ? __non_webpack_require__
        : require
      const engine = nativeRequire(modulePath)
      const fns = Object.keys(engine)
      console.log(`[rust-bridge] Rust engine loaded — exports: ${fns.join(', ')}`)
    } catch (e) {
      console.log(`[rust-bridge] Rust engine not available: ${e.message}`)
      console.log('[rust-bridge] JS fallback mode')
    }
  }
}
