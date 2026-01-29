/** @type {import('next').NextConfig} */
const isCapacitorBuild = process.env.CAPACITOR_BUILD === 'true'

const nextConfig = {
  reactStrictMode: true,
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
      ]
    },
  }),
}

module.exports = nextConfig
