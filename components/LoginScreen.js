'use client'

import { useState, useEffect, useRef } from 'react'
import { nip19 } from 'nostr-tools'
import { savePubkey, setStoredPrivateKey, hexToBytes } from '@/lib/nostr'
import SignUpModal from './SignUpModal'

export default function LoginScreen({ onLogin }) {
  const [checking, setChecking] = useState(true)
  const [error, setError] = useState('')
  
  // Nosskey (passkey) states
  const [nosskeySupported, setNosskeySupported] = useState(false)
  const [nosskeyHasKey, setNosskeyHasKey] = useState(false)
  const [nosskeyLoading, setNosskeyLoading] = useState(false)
  const [showNostrLoginOption, setShowNostrLoginOption] = useState(false)
  const [showSignUpModal, setShowSignUpModal] = useState(false)
  const [createdPubkey, setCreatedPubkey] = useState(null)
  const nosskeyManagerRef = useRef(null)
  
  // nostr-login states
  const nostrLoginInitialized = useRef(false)
  const [nostrLoginReady, setNostrLoginReady] = useState(false)
  const [nostrLoginError, setNostrLoginError] = useState(false)

  // Initialize passkey support check
  useEffect(() => {
    const init = async () => {
      // Small delay to allow hydration
      await new Promise(r => setTimeout(r, 100))
      
      try {
        const { NosskeyManager } = await import('nosskey-sdk')
        const manager = new NosskeyManager({
          storageOptions: { enabled: true, storageKey: 'nurunuru_nosskey' },
          cacheOptions: { enabled: true, timeoutMs: 3600000 }
        })
        nosskeyManagerRef.current = manager
        
        // 1. First check storage for existing key info (Synchronous/No prompt)
        const hasKey = manager.hasKeyInfo()
        let foundPubkey = false

        if (hasKey) {
          const keyInfo = manager.getCurrentKeyInfo()
          const storedPubkey = keyInfo?.pubkey || keyInfo?.publicKey
          if (storedPubkey) {
            setNosskeyHasKey(true)
            foundPubkey = true
          }
        }

        // 2. Fallback direct localStorage check
        if (!foundPubkey) {
          try {
            const storedData = localStorage.getItem('nurunuru_nosskey')
            if (storedData) {
              const parsed = JSON.parse(storedData)
              if (parsed?.pubkey || parsed?.publicKey) {
                manager.setCurrentKeyInfo(parsed)
                setNosskeyHasKey(true)
                foundPubkey = true
              }
            }
          } catch (e) {
            console.error('LocalStorage check error:', e)
          }
        }

        // 3. Silently check for PRF support
        // We do this after checking storage to avoid unnecessary prompts if possible
        // Note: isPrfSupported() is generally safe but we call it only once
        try {
          const supported = await manager.isPrfSupported()
          setNosskeySupported(supported)
        } catch (e) {
          console.warn('PRF support check failed:', e)
          setNosskeySupported(false)
        }
      } catch (e) {
        console.error('Nosskey initialization failed:', e)
      }
      
      setChecking(false)
    }
    init()
  }, [])

  // Initialize nostr-login via CDN when needed
  useEffect(() => {
    // Automatically init nostr-login for non-passkey users
    const shouldInit = showNostrLoginOption || (!checking && !nosskeyHasKey)
    
    if (!shouldInit || nostrLoginInitialized.current) return
    
    const initNostrLogin = () => {
      nostrLoginInitialized.current = true
      
      console.log('Loading nostr-login from CDN...')
      
      // Check if script already loaded
      if (document.querySelector('script[src*="nostr-login"]')) {
        console.log('nostr-login script already exists')
        setupNostrLogin()
        return
      }

      // Load nostr-login from CDN
      const script = document.createElement('script')
      script.src = 'https://unpkg.com/nostr-login@latest/dist/unpkg.js'
      script.async = true
      // Set options via data attributes
      script.dataset.darkMode = 'true'
      script.dataset.title = 'ぬるぬる'
      script.dataset.description = 'Nostrクライアント'
      script.dataset.perms = 'sign_event:1,sign_event:4,sign_event:7,sign_event:9735,nip04_encrypt,nip04_decrypt,nip44_encrypt,nip44_decrypt'
      script.dataset.methods = 'extension,connect,readOnly,local'
      script.dataset.noBanner = 'true'

      script.onload = () => {
        console.log('nostr-login script loaded')
        setupNostrLogin()
      }
      script.onerror = (e) => {
        console.error('Failed to load nostr-login script:', e)
        setNostrLoginError(true)
      }
      document.head.appendChild(script)
    }

    const setupNostrLogin = () => {
      try {
        // Listen for login events
        document.addEventListener('nlAuth', (e) => {
          console.log('nlAuth event:', e.detail)
          if (e.detail.type === 'login' || e.detail.type === 'signup') {
            const pubkey = e.detail.pubkey
            if (pubkey) {
              savePubkey(pubkey)
              localStorage.setItem('nurunuru_login_method', 'nostr-login')
              onLogin(pubkey)
            }
          }
        })
        
        console.log('nostr-login ready')
        setNostrLoginReady(true)
      } catch (e) {
        console.error('nostr-login setup error:', e)
        setNostrLoginError(true)
      }
    }
    
    initNostrLogin()
  }, [showNostrLoginOption, checking, nosskeySupported, onLogin])

  // Launch nostr-login via event
  const handleNostrLoginLaunch = () => {
    if (!nostrLoginReady) return
    console.log('Launching nostr-login')
    
    try {
      // Use nlLaunch event to open the modal
      document.dispatchEvent(new CustomEvent('nlLaunch', { detail: 'welcome' }))
    } catch (e) {
      console.error('Failed to launch nostr-login:', e)
      setError('ログイン画面を開けませんでした')
    }
  }

  // Passkey login handler
  const handleNosskeyLogin = async () => {
    setNosskeyLoading(true)
    setError('')
    try {
      const manager = nosskeyManagerRef.current
      if (!manager) throw new Error('Manager not initialized')

      // Check for redirect_uri for app login
      const urlParams = new URLSearchParams(window.location.search)
      const redirectUri = urlParams.get('redirect_uri')
      
      // First check for stored key info
      let keyInfo = manager.getCurrentKeyInfo()
      let storedPubkey = keyInfo?.pubkey || keyInfo?.publicKey
      
      console.log('Login - stored key info:', keyInfo)
      console.log('Login - stored pubkey:', storedPubkey)
      
      // If we have stored key info, use it
      if (keyInfo && storedPubkey) {
        // Handle app redirect if requested
        if (redirectUri) {
          try {
            const privateKeyHex = await manager.exportNostrKey(keyInfo)
            if (privateKeyHex) {
              const nsec = nip19.nsecEncode(hexToBytes(privateKeyHex))
              window.location.href = `${redirectUri}${redirectUri.includes('?') ? '&' : '?'}nsec=${nsec}`
              return
            }
          } catch (e) {
            console.error('Failed to export key for redirect:', e)
          }
        }

        savePubkey(storedPubkey)
        localStorage.setItem('nurunuru_login_method', 'nosskey')
        window.nosskeyManager = manager
        onLogin(storedPubkey)
        return
      }
      
      // No stored key info - try to authenticate with existing passkey
      // This allows login even if localStorage was cleared
      console.log('No stored key info, attempting passkey authentication...')
      
      try {
        // Call createNostrKey without credentialId to let browser select passkey
        const result = await manager.createNostrKey()
        console.log('Passkey auth result:', result)
        
        if (result && result.pubkey) {
          // Save the key info
          manager.setCurrentKeyInfo(result)

          // Handle app redirect if requested
          if (redirectUri) {
            try {
              const privateKeyHex = await manager.exportNostrKey(result)
              if (privateKeyHex) {
                const nsec = nip19.nsecEncode(hexToBytes(privateKeyHex))
                window.location.href = `${redirectUri}${redirectUri.includes('?') ? '&' : '?'}nsec=${nsec}`
                return
              }
            } catch (e) {
              console.error('Failed to export key for redirect:', e)
            }
          }

          savePubkey(result.pubkey)
          localStorage.setItem('nurunuru_login_method', 'nosskey')
          window.nosskeyManager = manager
          onLogin(result.pubkey)
        } else {
          setError('パスキーが見つかりません。新規登録してください。')
        }
      } catch (authError) {
        console.error('Passkey auth error:', authError)
        if (authError.name === 'NotAllowedError') {
          setError('認証がキャンセルされました')
        } else if (authError.message?.includes('No credentials')) {
          setError('パスキーが見つかりません。新規登録してください。')
        } else {
          setError('パスキーの認証に失敗しました')
        }
      }
    } catch (e) {
      console.error('Nosskey login error:', e)
      setError(e.message || 'ログインに失敗しました')
    } finally {
      setNosskeyLoading(false)
    }
  }

  // Complete onboarding after passkey creation
  const completeOnboarding = () => {
    if (createdPubkey) {
      savePubkey(createdPubkey)
      localStorage.setItem('nurunuru_login_method', 'nosskey')
      onLogin(createdPubkey)
    }
  }

  // Loading screen
  if (checking) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-[var(--bg-primary)]">
        <div className="w-20 h-20 mb-6 animate-pulse">
          <img src="/nurunuru-star.png" alt="ぬるぬる" className="w-full h-full object-contain rounded-2xl" />
        </div>
        <p className="text-[var(--text-tertiary)] text-sm">読み込み中...</p>
      </div>
    )
  }

  // Main login screen
  return (
    <div className="min-h-screen flex flex-col items-center justify-center p-6 bg-[var(--bg-primary)]">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8 animate-fadeIn">
          <div className="w-28 h-28 mx-auto mb-4 rounded-3xl overflow-hidden shadow-lg">
            <img src="/nurunuru-star.png" alt="ぬるぬる" className="w-full h-full object-cover" />
          </div>
          <h1 className="text-3xl font-bold text-[var(--text-primary)]">ぬるぬる</h1>
        </div>

        {/* Login options */}
        <div className="space-y-4">
          {nosskeyHasKey ? (
            /* Passkey User: Prominent Passkey Login */
            <div className="animate-slideUp space-y-4">
              <button
                onClick={handleNosskeyLogin}
                disabled={nosskeyLoading}
                className="w-full btn-line text-lg py-5 shadow-lg shadow-green-500/10 disabled:opacity-50"
              >
                {nosskeyLoading ? (
                  <span className="flex items-center justify-center gap-3">
                    <svg className="w-6 h-6 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                      <circle cx="12" cy="12" r="10" strokeOpacity="0.25"/>
                      <path d="M12 2a10 10 0 019.5 7" strokeLinecap="round"/>
                    </svg>
                    認証中...
                  </span>
                ) : (
                  <span className="flex items-center justify-center gap-3">
                    <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                      <path d="M12 2a4 4 0 014 4v2h2a2 2 0 012 2v10a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h2V6a4 4 0 014-4z"/>
                      <circle cx="12" cy="15" r="1.5"/>
                    </svg>
                    パスキーでログイン
                  </span>
                )}
              </button>

              <div className="text-center">
                <button
                  onClick={() => setShowNostrLoginOption(!showNostrLoginOption)}
                  className="text-sm text-[var(--text-tertiary)] hover:text-[var(--text-secondary)] transition-colors inline-flex items-center gap-1"
                >
                  <span>その他のログイン方法</span>
                  <svg className={`w-3.5 h-3.5 transition-transform ${showNostrLoginOption ? 'rotate-180' : ''}`} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="6 9 12 15 18 9"/>
                  </svg>
                </button>
              </div>

              {showNostrLoginOption && (
                <div className="pt-2 animate-fadeIn">
                  <button
                    onClick={handleNostrLoginLaunch}
                    disabled={!nostrLoginReady}
                    className="w-full btn-secondary py-3 text-sm disabled:opacity-50"
                  >
                    Nostrでログイン (拡張機能 / Connect)
                  </button>
                </div>
              )}
            </div>
          ) : (
            /* Non-Passkey User: Sign Up & Login */
            <div className="animate-slideUp space-y-4">
              <div className="space-y-3">
                <button
                  onClick={() => setShowSignUpModal(true)}
                  className="w-full btn-line text-lg py-5 shadow-lg shadow-green-500/10"
                >
                  <span className="flex items-center justify-center gap-3">
                    <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                      <path d="M16 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
                      <circle cx="8.5" cy="7" r="4" />
                      <line x1="20" y1="8" x2="20" y2="14" />
                      <line x1="17" y1="11" x2="23" y2="11" />
                    </svg>
                    新規登録
                  </span>
                </button>

                <button
                  onClick={handleNostrLoginLaunch}
                  disabled={!nostrLoginReady}
                  className="w-full btn-secondary text-lg py-4 disabled:opacity-50"
                >
                  <span className="flex items-center justify-center gap-3">
                    <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                      <path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4" />
                      <polyline points="10 17 15 12 10 7" />
                      <line x1="15" y1="12" x2="3" y2="12" />
                    </svg>
                    ログイン
                  </span>
                </button>
              </div>

              <div className="text-center">
                <p className="text-xs text-[var(--text-tertiary)]">
                  NIP-46 / 拡張機能 / 読み取り専用
                </p>
              </div>
            </div>
          )}

          {error && (
            <div className="mt-4 p-4 rounded-2xl bg-red-500/10 border border-red-500/20 animate-scaleIn">
              <p className="text-sm text-red-500 text-center">{error}</p>
            </div>
          )}
        </div>
      </div>

      {showSignUpModal && (
        <SignUpModal
          onClose={() => setShowSignUpModal(false)}
          onSuccess={(pubkey) => onLogin(pubkey)}
          nosskeyManager={nosskeyManagerRef.current}
        />
      )}

      <div className="py-6 text-center animate-fadeIn">
        <a 
          href="https://github.com/nostr-jp" 
          target="_blank" 
          rel="noopener noreferrer"
          className="text-xs text-[var(--text-tertiary)] hover:text-[var(--line-green)] transition-colors"
        >
          Powered by Nostr
        </a>
      </div>
    </div>
  )
}
