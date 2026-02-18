/**
 * POST /api/publish
 *
 * ブラウザで署名済みの Nostr イベントを受け取り、
 * Rust エンジン経由で接続中の全リレーにブロードキャストする。
 *
 * フロー:
 *   ブラウザ (NIP-07 / Amber / NIP-46 で署名)
 *     └─ POST /api/publish { event: signedEvent }
 *           └─ engine.publishEvent(eventJson) → client.send_event() → 全リレー
 *
 * Body (JSON):
 *   event - 署名済み Nostr イベント (NIP-01 フォーマット)
 *
 * Response:
 *   { id, relays, source: 'rust' }
 *   エラー時: { error, source: 'error' | 'unavailable' }
 */

import { getOrCreateEngine } from '@/lib/rust-engine-manager'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

/**
 * NIP-01 必須フィールドの簡易バリデーション
 */
function isValidEvent(event) {
  return (
    event &&
    typeof event.id === 'string' && /^[0-9a-f]{64}$/.test(event.id) &&
    typeof event.pubkey === 'string' && /^[0-9a-f]{64}$/.test(event.pubkey) &&
    typeof event.created_at === 'number' &&
    typeof event.kind === 'number' &&
    Array.isArray(event.tags) &&
    typeof event.content === 'string' &&
    typeof event.sig === 'string' && event.sig.length === 128
  )
}

export async function POST(req) {
  let body
  try {
    body = await req.json()
  } catch {
    return Response.json({ error: 'Invalid JSON body' }, { status: 400 })
  }

  const { event } = body

  if (!isValidEvent(event)) {
    return Response.json(
      { error: 'Invalid event: missing or malformed NIP-01 fields' },
      { status: 400 }
    )
  }

  const engine = await getOrCreateEngine()

  if (!engine) {
    // Rust エンジン未起動: フォールバック指示を返す
    return Response.json(
      { error: 'Rust engine unavailable', source: 'unavailable' },
      { status: 503 }
    )
  }

  try {
    const eventId = await engine.publishEvent(JSON.stringify(event))

    // 接続中リレー一覧を返す (クライアントへの情報提供)
    let relays = []
    try {
      const relayList = await engine.getRelayList()
      relays = relayList.filter(r => r.connected).map(r => r.url)
    } catch {
      // リレー一覧取得失敗は無視
    }

    return Response.json({
      id: eventId,
      relays,
      source: 'rust',
    })
  } catch (err) {
    console.error('[api/publish] Failed to publish event:', err.message)
    return Response.json(
      { error: err.message || 'Failed to publish event', source: 'error' },
      { status: 500 }
    )
  }
}
