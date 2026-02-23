/** @type {import('next').NextConfig} */
const isCapacitorBuild = process.env.CAPACITOR_BUILD === 'true'
const isProd = process.env.NODE_ENV === 'production'

const nextConfig = {
  reactStrictMode: true,
  // Strip console.log/warn/debug from production builds (keep console.error for diagnostics)
  compiler: isProd ? {
    removeConsole: {
      exclude: ['error'],
    },
  } : {},
  // output: 'export' is only enabled for Capacitor builds
  // Vercel deployments need dynamic API routes (e.g., /api/nip05)
  ...(isCapacitorBuild && { output: 'export' }),
  trailingSlash: true,
  images: {
    unoptimized: true, // Required for static export and simpler image handling
    remotePatterns: [
      {
        protocol: 'https',
        hostname: '**',
      },
    ],
  },
  // Suppress "Critical dependency" warnings from native .node module loading
  webpack: (config) => {
    config.ignoreWarnings = [
      ...(config.ignoreWarnings || []),
      { message: /Critical dependency: require function is used in a way/ },
    ]
    return config
  },
  // Note: headers() only works when NOT using output: 'export'
  ...(!isCapacitorBuild && {
    async headers() {
      return [
        {
          source: '/sw.js',
          headers: [
            {
              key: 'Cache-Control',
              value: 'no-cache, no-store, must-revalidate',
            },
            {
              key: 'Service-Worker-Allowed',
              value: '/',
            },
          ],
        },
        {
          source: '/(.*)',
          headers: [
            // Prevent clickjacking
            { key: 'X-Frame-Options', value: 'DENY' },
            // Prevent MIME sniffing
            { key: 'X-Content-Type-Options', value: 'nosniff' },
            // Control referrer information
            { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
            // Enforce HTTPS (1 year, include subdomains)
            { key: 'Strict-Transport-Security', value: 'max-age=31536000; includeSubDomains' },
            // Restrict browser features
            {
              key: 'Permissions-Policy',
              value: 'camera=(), microphone=(self), geolocation=(self), payment=()',
            },
            // Content Security Policy
            {
              key: 'Content-Security-Policy',
              value: [
                "default-src 'self'",
                // Scripts: self + inline + nostr-login from unpkg
                "script-src 'self' 'unsafe-inline' 'unsafe-eval' https://unpkg.com",
                // Styles: self + inline (Tailwind uses inline styles)
                "style-src 'self' 'unsafe-inline'",
                // Images: self + https (profile pictures from any HTTPS source)
                "img-src 'self' data: blob: https:",
                // Fonts: self
                "font-src 'self' data:",
                // Connect: self + wss (Nostr relay WebSockets) + HTTPS APIs + nostr-login + ElevenLabs
                "connect-src 'self' wss: https: https://unpkg.com https://nostr-login.pages.dev https://*.nostr-login.pages.dev https://api.elevenlabs.io",
                // Workers: self (Service Worker)
                "worker-src 'self' blob:",
                // Manifest
                "manifest-src 'self'",
                // Block object/embed/base
                "object-src 'none'",
                "base-uri 'self'",
                // Allow nostr-login iframes
                "frame-src 'self' https://nostr-login.pages.dev https://*.nostr-login.pages.dev",
                "frame-ancestors 'none'",
              ].join('; '),
            },
          ],
        },
      ]
    },
  }),
}

module.exports = nextConfig
