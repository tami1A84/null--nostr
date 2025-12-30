'use client'

import ErrorBoundary from './ErrorBoundary'

/**
 * Client-side layout wrapper with ErrorBoundary
 * This wraps the main application content with error handling
 *
 * @param {Object} props
 * @param {React.ReactNode} props.children - Child components
 * @returns {JSX.Element}
 */
export default function ClientLayout({ children }) {
  return (
    <ErrorBoundary>
      {children}
    </ErrorBoundary>
  )
}
