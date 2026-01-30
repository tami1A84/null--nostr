/**
 * Amber Signer Adapter
 *
 * Implementation for Amber (NIP-55) Android signer app.
 * Uses Android Intents to communicate with the Amber app.
 *
 * @module adapters/signing
 */

import type { Event } from 'nostr-tools'
import type {
  SigningAdapter,
  SignerFeature,
  UnsignedEvent
} from './SigningAdapter'
import { SigningError } from './SigningAdapter'

// Timeout for Amber operations (30 seconds)
const AMBER_TIMEOUT_MS = 30000

/**
 * Amber window extensions
 */
declare global {
  interface Window {
    amberSignTimeout?: ReturnType<typeof setTimeout> | null
    amberSignResolve?: ((value: Event) => void) | null
    amberSignReject?: ((error: Error) => void) | null
  }
}

/**
 * Generate a random request ID for Amber callbacks
 */
function generateRequestId(): string {
  return Math.random().toString(36).substring(2) + Date.now().toString(36)
}

/**
 * Check if we're running in a Capacitor Android environment
 */
export function isAmberEnvironment(): boolean {
  if (typeof window === 'undefined') return false

  // Check for Capacitor Android
  const capacitor = (window as { Capacitor?: { getPlatform?: () => string } }).Capacitor
  if (capacitor?.getPlatform?.() === 'android') {
    return true
  }

  return false
}

/**
 * Amber Signer implementation
 */
export class AmberSigner implements SigningAdapter {
  readonly type = 'amber' as const
  private publicKey: string | null = null

  constructor(pubkey?: string) {
    this.publicKey = pubkey ?? null
  }

  /**
   * Set the public key (required for Amber since it can't derive it)
   */
  setPublicKey(pubkey: string): void {
    this.publicKey = pubkey
  }

  async getPublicKey(): Promise<string> {
    // Amber doesn't have a getPublicKey method via intents
    // The pubkey must be obtained during login/registration
    if (!this.publicKey) {
      throw new SigningError(
        'Public key not set. Set it during login with Amber.',
        'NOT_AVAILABLE'
      )
    }
    return this.publicKey
  }

  async signEvent(event: UnsignedEvent): Promise<Event> {
    if (typeof window === 'undefined') {
      throw new SigningError('Amber requires browser environment', 'NOT_AVAILABLE')
    }

    // First try window.nostr if Amber injects it in WebView
    if (window.nostr && typeof window.nostr.signEvent === 'function') {
      try {
        const signedEvent = await window.nostr.signEvent(event)
        return signedEvent
      } catch (e) {
        console.log('window.nostr.signEvent failed, trying intent:', e)
      }
    }

    // Fall back to intent-based signing
    return this.signEventViaIntent(event)
  }

  private async signEventViaIntent(event: UnsignedEvent): Promise<Event> {
    return new Promise((resolve, reject) => {
      const requestId = generateRequestId()

      // Store the event for callback verification
      sessionStorage.setItem(`amber_sign_${requestId}`, JSON.stringify(event))
      sessionStorage.setItem('amber_sign_pending', requestId)

      // Build callback URL
      const callbackUrl = encodeURIComponent(
        window.location.origin + '/?amber_sign_callback=' + requestId
      )

      // Build intent URL for Amber
      const eventJson = encodeURIComponent(JSON.stringify(event))
      const intentUrl = `intent://sign?event=${eventJson}&callbackUrl=${callbackUrl}#Intent;scheme=nostrsigner;package=com.greenart7c3.nostrsigner;end`

      // Set up timeout
      const timeout = setTimeout(() => {
        sessionStorage.removeItem(`amber_sign_${requestId}`)
        sessionStorage.removeItem('amber_sign_pending')
        reject(new SigningError('Amber signing timed out', 'TIMEOUT'))
      }, AMBER_TIMEOUT_MS)

      window.amberSignTimeout = timeout
      window.amberSignResolve = resolve
      window.amberSignReject = (error: Error) => {
        reject(new SigningError(error.message, 'SIGNING_FAILED', error))
      }

      // Open Amber
      window.location.href = intentUrl
    })
  }

  async nip04Encrypt(_pubkey: string, _plaintext: string): Promise<string> {
    // Amber supports NIP-04 via intents, but the implementation is complex
    // For now, throw not supported
    throw new SigningError(
      'NIP-04 encryption via Amber intents not yet implemented',
      'NOT_SUPPORTED'
    )
  }

  async nip04Decrypt(_pubkey: string, _ciphertext: string): Promise<string> {
    throw new SigningError(
      'NIP-04 decryption via Amber intents not yet implemented',
      'NOT_SUPPORTED'
    )
  }

  async nip44Encrypt(_pubkey: string, _plaintext: string): Promise<string> {
    throw new SigningError(
      'NIP-44 encryption via Amber intents not yet implemented',
      'NOT_SUPPORTED'
    )
  }

  async nip44Decrypt(_pubkey: string, _ciphertext: string): Promise<string> {
    throw new SigningError(
      'NIP-44 decryption via Amber intents not yet implemented',
      'NOT_SUPPORTED'
    )
  }

  supports(feature: SignerFeature): boolean {
    // Amber supports these features, but our implementation doesn't yet
    switch (feature) {
      case 'nip04':
        return false // Not implemented yet
      case 'nip44':
        return false // Not implemented yet
      case 'getRelays':
        return false
      case 'delegation':
        return false
      default:
        return false
    }
  }
}

/**
 * Handle Amber sign callback
 * Call this from page component on mount to process callback parameters
 */
export function handleAmberSignCallback(): void {
  if (typeof window === 'undefined') return

  const urlParams = new URLSearchParams(window.location.search)
  const callbackParam = urlParams.get('amber_sign_callback')

  if (!callbackParam) return

  const requestId = callbackParam

  const cleanup = () => {
    if (window.amberSignTimeout) {
      clearTimeout(window.amberSignTimeout)
      window.amberSignTimeout = null
    }
    // Clear URL parameters
    const newUrl = window.location.pathname
    window.history.replaceState({}, document.title, newUrl)
  }

  const rejectWithError = (message: string) => {
    cleanup()
    sessionStorage.removeItem(`amber_sign_${requestId}`)
    sessionStorage.removeItem('amber_sign_pending')

    console.error('Amber callback error:', message)
    if (window.amberSignReject) {
      window.amberSignReject(new Error(message))
    }

    window.amberSignResolve = null
    window.amberSignReject = null
  }

  try {
    const signature = urlParams.get('signature')
    const eventParam = urlParams.get('event')
    const error = urlParams.get('error')

    if (error) {
      return rejectWithError(error)
    }

    // Try to parse signed event
    let signedEvent: Event | null = null

    if (eventParam) {
      try {
        signedEvent = JSON.parse(decodeURIComponent(eventParam)) as Event
        if (!signedEvent.id || !signedEvent.sig) {
          return rejectWithError('Invalid event returned from Amber')
        }
      } catch {
        // If event parsing fails, try to reconstruct from signature
        const pendingRequestId = sessionStorage.getItem('amber_sign_pending')
        if (pendingRequestId !== requestId) {
          return rejectWithError('Request ID mismatch')
        }

        const storedEventJson = sessionStorage.getItem(`amber_sign_${pendingRequestId}`)
        if (!storedEventJson) {
          return rejectWithError('Original event not found')
        }

        if (signature) {
          const storedEvent = JSON.parse(storedEventJson) as UnsignedEvent
          signedEvent = {
            ...storedEvent,
            pubkey: storedEvent.pubkey || '',
            id: '', // Should be computed
            sig: signature
          } as Event
        }
      }
    }

    cleanup()
    sessionStorage.removeItem(`amber_sign_${requestId}`)
    sessionStorage.removeItem('amber_sign_pending')

    if (window.amberSignResolve && signedEvent) {
      window.amberSignResolve(signedEvent)
    }

    window.amberSignResolve = null
    window.amberSignReject = null
  } catch (e) {
    rejectWithError(e instanceof Error ? e.message : 'Unknown error')
  }
}

/**
 * Create a new AmberSigner instance
 */
export function createAmberSigner(pubkey?: string): AmberSigner {
  return new AmberSigner(pubkey)
}
