import { NextResponse } from 'next/server'

/**
 * NIP-05 proxy endpoint to avoid CORS issues
 * GET /api/nip05?domain=example.com&name=user
 */
export async function GET(request) {
  const { searchParams } = new URL(request.url)
  const domain = searchParams.get('domain')
  const name = searchParams.get('name')

  if (!domain || !name) {
    return NextResponse.json(
      { error: 'Missing domain or name parameter' },
      { status: 400 }
    )
  }

  // Validate domain to prevent SSRF
  const domainRegex = /^[a-zA-Z0-9][a-zA-Z0-9-]*(\.[a-zA-Z0-9][a-zA-Z0-9-]*)*$/
  if (!domainRegex.test(domain)) {
    return NextResponse.json(
      { error: 'Invalid domain format' },
      { status: 400 }
    )
  }

  const url = `https://${domain}/.well-known/nostr.json?name=${encodeURIComponent(name)}`

  try {
    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), 5000)

    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'Null-Nostr/1.0'
      }
    })

    clearTimeout(timeoutId)

    if (!response.ok) {
      return NextResponse.json(
        { error: `Upstream returned ${response.status}` },
        { status: response.status }
      )
    }

    const data = await response.json()

    return NextResponse.json(data, {
      headers: {
        'Cache-Control': 'public, max-age=300' // Cache for 5 minutes
      }
    })
  } catch (error) {
    if (error.name === 'AbortError') {
      return NextResponse.json(
        { error: 'Request timeout' },
        { status: 504 }
      )
    }

    return NextResponse.json(
      { error: 'Failed to fetch NIP-05 data' },
      { status: 502 }
    )
  }
}
