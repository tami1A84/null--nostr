/**
 * /api/stream — Server-Sent Events (SSE) proxy for Nostr relay subscriptions.
 *
 * Step 8: Rust SSE プロキシ実装
 *
 * Architecture:
 *   Browser (EventSource) → GET /api/stream?filter={...}
 *     → Rust engine.subscribeStream(filter)   ← REQ sent to all relays
 *     → polling loop: engine.pollSubscription()
 *     → data: {eventJson}\n\n                 ← SSE push to browser
 *     (on disconnect) → engine.unsubscribeStream()  ← CLOSE sent to relays
 *
 * Runtime: Node.js (NOT Edge) — required for native .node module access.
 */

export const runtime = 'nodejs'

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

// Poll interval in milliseconds — how often we check the Rust event buffer.
// 50 ms gives a good balance between latency and CPU usage.
const POLL_INTERVAL_MS = 50

// Heartbeat interval — keeps the SSE connection alive through proxies/firewalls.
const HEARTBEAT_INTERVAL_MS = 25_000

// Maximum events drained per poll cycle.
const MAX_EVENTS_PER_POLL = 50

export async function GET(req) {
  const { searchParams } = new URL(req.url)
  const filterJson = searchParams.get('filter')

  if (!filterJson) {
    return Response.json(
      { error: 'filter クエリパラメータが必要です' },
      { status: 400 }
    )
  }

  // Validate that filterJson is valid JSON
  try {
    JSON.parse(filterJson)
  } catch {
    return Response.json(
      { error: 'filter パラメータが無効な JSON です' },
      { status: 400 }
    )
  }

  const engine = await getOrCreateEngine()
  if (!engine) {
    return Response.json(
      { error: 'Rust エンジンが利用できません', source: 'unavailable' },
      { status: 503 }
    )
  }

  const encoder = new TextEncoder()
  let subId = null
  let cancelled = false
  let heartbeatTimer = null

  const stream = new ReadableStream({
    async start(controller) {
      const enqueue = (data) => {
        if (!cancelled) {
          try {
            controller.enqueue(encoder.encode(data))
          } catch {
            // Controller might be closed if client disconnected
            cancelled = true
          }
        }
      }

      // Send an initial comment to establish the connection immediately.
      enqueue(': connected\n\n')

      // Heartbeat to keep the connection alive through proxies.
      heartbeatTimer = setInterval(() => {
        enqueue(': heartbeat\n\n')
      }, HEARTBEAT_INTERVAL_MS)

      try {
        // Start subscription in the Rust engine — sends REQ to all relays.
        subId = await engine.subscribeStream(filterJson)

        // Poll the event buffer and forward events to the browser.
        while (!cancelled) {
          const events = await engine.pollSubscription(subId, MAX_EVENTS_PER_POLL)

          for (const eventJson of events) {
            enqueue(`data: ${eventJson}\n\n`)
          }

          // Wait before next poll (yields event loop so Node.js can handle
          // other requests concurrently).
          await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
        }
      } catch (err) {
        // Send error event before closing.
        enqueue(`event: error\ndata: ${JSON.stringify({ message: err.message })}\n\n`)
      } finally {
        clearInterval(heartbeatTimer)
        if (subId) {
          try {
            await engine.unsubscribeStream(subId)
          } catch {
            // Best-effort cleanup
          }
        }
        if (!cancelled) {
          try {
            controller.close()
          } catch {
            // Already closed
          }
        }
      }
    },

    cancel() {
      // Called when the browser closes the EventSource connection.
      cancelled = true
      clearInterval(heartbeatTimer)
      // Cleanup happens in the finally block of start().
    },
  })

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no', // Disable nginx buffering
    },
  })
}
