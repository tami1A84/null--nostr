/** @type {import('next').NextConfig} */
const isCapacitorBuild = process.env.CAPACITOR_BUILD === 'true'

const nextConfig = {
  reactStrictMode: true,
  experimental: {
    instrumentationHook: true,
  },
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
      ]
    },
  }),
}

module.exports = nextConfig
