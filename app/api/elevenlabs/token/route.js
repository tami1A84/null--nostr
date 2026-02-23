import { NextResponse } from 'next/server';

export async function POST(request) {
  try {
    const { apiKey } = await request.json();

    if (!apiKey) {
      return NextResponse.json({ error: 'API key is required' }, { status: 400 });
    }

    // Call ElevenLabs API to get a single-use token for Scribe
    const response = await fetch('https://api.elevenlabs.io/v1/single-use-token/realtime_scribe', {
      method: 'POST',
      headers: {
        'xi-api-key': apiKey,
      },
    });

    if (!response.ok) {
      const errorData = await response.json();
      return NextResponse.json(errorData, { status: response.status });
    }

    const data = await response.json();
    // The response is { token: "..." }
    return NextResponse.json(data);
  } catch (error) {
    console.error('ElevenLabs token error:', error);
    return NextResponse.json({ error: 'Internal server error' }, { status: 500 });
  }
}
